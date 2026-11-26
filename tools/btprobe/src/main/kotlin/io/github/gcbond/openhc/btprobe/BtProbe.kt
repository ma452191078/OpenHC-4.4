package io.github.gcbond.openhc.btprobe

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import java.io.IOException
import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * 逐项检测：系统信息 → 本机蓝牙地址 → 监听 A → 监听 B → 备选开法 → 等待连接（可选）→ 关闭 → 隐藏接口告警汇总。
 * 在一个后台线程上按顺序执行，每得到一项结果就交给 [emit]。所有监听都开着时才读通道号、做对比，
 * 读完再进入等待，最后统一关闭。
 *
 * 不用任何绕过隐藏接口限制的手法：放行与否完全按系统对本应用 targetSdk 的规则，这正是要测的。
 */
// 蓝牙权限在开始检测前由界面申请。没授予时下面的调用会抛 SecurityException，按「异常」记进结果，这本身就是要看的。
@SuppressLint("MissingPermission")
class BtProbe(
    private val context: Context,
    private val emit: (ProbeItem) -> Unit,
    private val onWaiting: (secondsLeft: Int) -> Unit,
    private val stopWaitingRequested: () -> Boolean,
) {

    private class Listener(val label: String, val socket: BluetoothServerSocket, val uuid: UUID?) {
        @Volatile
        var channel: Int? = null
    }

    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val listeners = ArrayList<Listener>()
    private val acceptThreads = ArrayList<Thread>()
    private val connections = AtomicInteger()

    @Volatile
    private var closing = false

    fun run(waitForConnections: Boolean, waitSeconds: Int) {
        HiddenApiWatcher.install()
        // 自检放在取起点之前，自检那次访问不算进最后的汇总。
        val selfCheck = HiddenApiWatcher.calibrate()
        val runMark = HiddenApiWatcher.mark()
        try {
            systemInfo(selfCheck)
            localAddress()
            val a = probeListener("A", SEC_A, NAME_A, UUID_A)
            val b = probeListener("B", SEC_B, NAME_B, UUID_B)
            compareChannels(a, b)
            alternativeListener(listOfNotNull(a, b))
            if (waitForConnections) {
                acceptConnections(waitSeconds)
            } else {
                item(SEC_ACCEPT, "等待连接", Status.SKIP, "没打开「检测完后保持监听」开关")
            }
        } catch (e: Exception) {
            item(SEC_ABORT, "检测中途出错", Status.ERROR, e.stackTraceToString().take(MAX_STACK_CHARS))
        } finally {
            closeAll()
            hiddenApiSummary(runMark)
        }
    }

    // ---------------------------------------------------------------- 系统信息

    private fun systemInfo(selfCheck: HiddenApiWatcher.SelfCheck?) {
        val s = SEC_SYSTEM
        item(s, "安卓版本", Status.OK, "${Build.VERSION.RELEASE}（SDK ${Build.VERSION.SDK_INT}）")
        item(s, "厂商 / 品牌 / 型号", Status.OK, "${Build.MANUFACTURER} / ${Build.BRAND} / ${Build.MODEL}")
        item(s, "系统版本号", Status.OK, Build.DISPLAY)
        val info = context.applicationInfo
        item(s, "本应用", Status.OK, "${context.packageName}，风味 ${BuildConfig.FLAVOR}，版本 ${BuildConfig.VERSION_NAME}")
        item(s, "targetSdk", Status.OK, info.targetSdkVersion.toString())
        val debuggable = (info.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        item(
            s, "可调试", Status.OK,
            if (debuggable) "是：放行的隐藏接口访问也会在 logcat 留告警" else "否：logcat 只记拦下的隐藏接口访问",
        )
        if (Build.VERSION.SDK_INT < 28) {
            item(s, "隐藏接口限制", Status.SKIP, "无：安卓 9 以下没有这项限制，反射失败只可能是成员不存在")
        } else {
            item(s, "隐藏接口限制", Status.OK, "有（安卓 9 起）")
            hiddenApiGlobalSettings(s)
            selfCheckItem(s, selfCheck)
        }
        if (Build.VERSION.SDK_INT >= 31) {
            for (permission in RUNTIME_PERMISSIONS) {
                val granted = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
                item(
                    s, "运行时权限 ${permission.substringAfterLast('.')}",
                    if (granted) Status.OK else Status.FAIL,
                    if (granted) "已授予" else "未授予：蓝牙相关的检测会失败",
                )
            }
        } else {
            item(s, "运行时蓝牙权限", Status.SKIP, "安卓 12 以下不需要，用安装时授予的 BLUETOOTH / BLUETOOTH_ADMIN")
        }
        val adapter = adapter
        if (adapter == null) {
            item(s, "蓝牙", Status.FAIL, "这台设备没有蓝牙适配器")
            return
        }
        val enabled = attempt { adapter.isEnabled }
        when {
            enabled.error != null -> item(s, "蓝牙是否开启", Status.ERROR, describeError(enabled))
            enabled.value == true -> item(s, "蓝牙是否开启", Status.OK, "已开启")
            else -> item(s, "蓝牙是否开启", Status.FAIL, "未开启：监听相关的检测都会失败，请先打开蓝牙再测")
        }
    }

    /**
     * 有人用 adb 改过这几个全局设置的话，隐藏接口对所有应用放行或部分豁免，这台设备上测出来的结果不代表普通设备。
     * 安卓 9 用两个按 targetSdk 分的键，安卓 10 起合成一个；豁免名单的键各版本相同。只查本版本用得到的键：
     * 安卓 12 起，targetSdk 高的应用读本版本没有的隐藏键会直接被拒。
     */
    private fun hiddenApiGlobalSettings(s: String) {
        val name = "隐藏接口全局设置（应为未设置）"
        val keys = if (Build.VERSION.SDK_INT == 28) HIDDEN_API_SETTINGS_P else HIDDEN_API_SETTINGS
        val values = LinkedHashMap<String, String?>()
        val unreadable = ArrayList<String>()
        for (key in keys) {
            val read = attempt { Settings.Global.getString(context.contentResolver, key) }
            if (read.error != null) unreadable += key else values[key] = read.value
        }
        val changed = values.filterValues { !it.isNullOrEmpty() }
        when {
            changed.isNotEmpty() -> item(
                s, name, Status.FAIL,
                "被改过：" + changed.entries.joinToString("，") { "${it.key}=${it.value}" } +
                    "。这台设备上的隐藏接口结果不代表普通设备，请先用 adb shell settings delete global <键名> 恢复后再测",
            )
            unreadable.isNotEmpty() -> item(
                s, name, Status.SKIP,
                "读不到 ${unreadable.joinToString("、")}，请用 adb shell settings get global <键名> 确认输出 null",
            )
            else -> item(s, name, Status.OK, "都未设置：${keys.joinToString("、")}")
        }
    }

    private fun selfCheckItem(s: String, selfCheck: HiddenApiWatcher.SelfCheck?) {
        val name = "隐藏接口访问监听自检"
        when {
            selfCheck == null -> item(s, name, Status.FAIL, "监听没开成：反射失败时分不清是被拦还是没有该成员，只能靠 logcat")
            selfCheck.reported -> item(
                s, name, Status.OK,
                "正常：查已知隐藏字段 BluetoothSocket.mSocket 时记到了这次访问" +
                    if (selfCheck.lookupSucceeded) "" else "（这台设备上它被拦下了，说明拦下的访问也会上报）",
            )
            else -> item(
                s, name, Status.FAIL,
                "查已知隐藏字段 BluetoothSocket.mSocket 时没记到访问：后面「多半没有该成员」之类的判断不可信，以 logcat 为准",
            )
        }
    }

    // ---------------------------------------------------------------- 本机蓝牙地址

    private fun localAddress() {
        val s = SEC_ADDRESS
        // 读法简称 → 规范化后的地址；只收看起来是真实地址的，最后对比是否一致。
        val found = LinkedHashMap<String, String>()

        val adapter = adapter
        val publicName = "BluetoothAdapter.getAddress()（公开接口）"
        if (adapter == null) {
            item(s, publicName, Status.SKIP, "没有蓝牙适配器")
        } else {
            addressItem(s, publicName, "getAddress()", attempt { adapter.address }, found)
        }

        addressItem(
            s, "Settings.Secure 的 bluetooth_address（公开方法读系统设置项）", "bluetooth_address",
            attempt { Settings.Secure.getString(context.contentResolver, "bluetooth_address") }, found,
        )

        val getter = attempt { Class.forName("android.os.SystemProperties").getMethod("get", String::class.java) }
        val get = getter.value
        if (get == null) {
            item(s, "反射 SystemProperties.get(String)", Status.ERROR, describeError(getter))
        } else {
            for ((key, note) in PROPERTY_KEYS) {
                addressItem(
                    s, "系统属性 $key（反射 SystemProperties.get）$note", key,
                    attempt { get.invoke(null, key) as String? }, found, accessNote(getter),
                )
            }
        }

        internalServiceAddress(s, found)

        val distinct = found.values.distinct()
        when {
            found.isEmpty() -> item(
                s, "结论", Status.FAIL,
                "所有读法都拿不到真实地址：二维码里的地址要另想办法（例如让车主手动填）",
            )
            distinct.size == 1 -> item(
                s, "结论", Status.OK,
                "${found.keys.joinToString("、")} 读到同一个地址 ${distinct[0]}；" +
                    "请再和系统记录的本机地址核对（dumpsys bluetooth_manager，或另一台设备扫描到的地址）",
            )
            else -> item(
                s, "结论", Status.FAIL,
                "不同读法读到的地址不一致：" + found.entries.joinToString("，") { "${it.key}=${it.value}" },
            )
        }
    }

    private fun addressItem(
        section: String,
        name: String,
        key: String,
        result: Attempt<String?>,
        found: MutableMap<String, String>,
        extraNote: String = "",
    ) {
        if (result.error != null) {
            item(section, name, Status.ERROR, describeError(result) + extraNote)
            return
        }
        val raw = result.value
        val shown = when {
            raw == null -> "null"
            raw.isEmpty() -> "（空）"
            else -> raw
        }
        val (status, why) = when (ProbeText.classifyAddress(raw)) {
            ProbeText.AddressKind.PLAUSIBLE -> Status.OK to "格式正确，看起来是真实地址"
            ProbeText.AddressKind.PLACEHOLDER -> Status.FAIL to "系统给普通应用的占位值"
            ProbeText.AddressKind.ALL_ZERO -> Status.FAIL to "全零，无效"
            ProbeText.AddressKind.MISSING -> Status.FAIL to "没读到：不存在，或没有读取权限"
            ProbeText.AddressKind.MALFORMED -> Status.FAIL to "不是蓝牙地址的写法"
        }
        val normalized = ProbeText.normalizeAddress(raw)
        if (status == Status.OK && normalized != null) found[key] = normalized
        val tail = "（$why）" + accessNote(result) + extraNote
        item(section, name, status, shown + tail, ProbeText.maskAddress(shown) + tail)
    }

    /**
     * 反射 BluetoothAdapter 持有的蓝牙服务接口，直接调它的无参 getAddress()。
     * 公开的 getAddress() 走的是另一个系统服务，那里会给普通应用换成占位值；这里看蓝牙服务本身怎么检查权限。
     */
    private fun internalServiceAddress(s: String, found: MutableMap<String, String>) {
        val name = "反射内部服务：BluetoothAdapter.mService 的 getAddress()"
        val adapter = adapter
        if (adapter == null) {
            item(s, name, Status.SKIP, "没有蓝牙适配器")
            return
        }
        val field = attempt { BluetoothAdapter::class.java.getDeclaredField("mService").apply { isAccessible = true } }
        val serviceField = field.value
        if (serviceField == null) {
            item(s, "$name（取 mService 字段）", Status.ERROR, describeError(field))
            return
        }
        val service = attempt { serviceField.get(adapter) }
        val binder = service.value
        if (binder == null) {
            if (service.error != null) {
                item(s, "$name（读 mService）", Status.ERROR, describeError(service))
            } else {
                item(s, name, Status.FAIL, "mService 为空：蓝牙服务还没连上（蓝牙没开时常见）" + accessNote(field))
            }
            return
        }
        // 按接口查方法：接口方法才是隐藏接口名单里登记的成员，实现类的同名方法不另算。
        val method = attempt { Class.forName("android.bluetooth.IBluetooth").getMethod("getAddress") }
        val getAddress = method.value
        if (getAddress == null) {
            item(
                s, name, Status.ERROR,
                describeError(method) + "；这个版本接口里能访问到的同名方法：" + accessibleAddressMethods(),
            )
            return
        }
        addressItem(
            s, name, "mService.getAddress()",
            attempt { getAddress.invoke(binder) as String? }, found, accessNote(field) + accessNote(method),
        )
    }

    private fun accessibleAddressMethods(): String {
        val listed = attempt {
            Class.forName("android.bluetooth.IBluetooth").methods
                .filter { it.name.startsWith("getAddress") }
                .joinToString("、") { describe(it) }
        }
        return listed.value?.ifEmpty { "没有" } ?: describeError(listed)
    }

    private fun describe(method: Method): String =
        method.name + method.parameterTypes.joinToString(", ", "(", ")") { it.simpleName }

    // ---------------------------------------------------------------- 监听与通道号

    private fun probeListener(label: String, section: String, serviceName: String, uuid: UUID): Listener? {
        val adapter = usableAdapter(section) ?: return null
        val openName = "开监听：listenUsingInsecureRfcommWithServiceRecord（公开接口，不加密，带服务记录）"
        val open = attempt { adapter.listenUsingInsecureRfcommWithServiceRecord(serviceName, uuid) }
        val socket = open.value
        if (socket == null) {
            item(section, openName, Status.ERROR, describeError(open))
            return null
        }
        item(section, openName, Status.OK, "服务名 $serviceName，UUID $uuid")
        val listener = Listener(label, socket, uuid)
        listeners += listener
        listener.channel = summarizeChannels(section, readChannels(section, socket))
        return listener
    }

    /** 用各种办法读同一个监听的通道号，每种办法记一项；返回读到有效通道号的读法。 */
    // lint 知道 getChannel()、getPort() 在高 targetSdk 下会被拦；探针要测的正是这一点，所以只压这一条。
    @SuppressLint("SoonBlockedPrivateApi")
    private fun readChannels(section: String, socket: BluetoothServerSocket): Map<String, Int> {
        val valid = LinkedHashMap<String, Int>()

        // 读法 1：公开的 toString()。它打印的是服务端 socket 自己记的通道号字段。
        val text = attempt { socket.toString() }
        if (text.error != null) {
            item(section, "读法 1：toString()（公开方法）", Status.ERROR, describeError(text))
        } else {
            channelItem(
                section, "读法 1：toString()（公开方法）", "toString()",
                ProbeText.channelFromToString(text.value), OUTER_FIELD_MINUS_ONE, "原文：${text.value}", valid,
            )
        }

        // 读法 2：反射服务端 socket 的 mSocket 字段（内部的 BluetoothSocket），再读它的 mPort 字段。
        // 开监听时协议栈把实际分配的通道号写回的是内部 socket 的这个字段。
        val innerName = "读法 2a：反射 mSocket 字段（内部 socket）"
        val inner = attempt {
            BluetoothServerSocket::class.java.getDeclaredField("mSocket").apply { isAccessible = true }
                .get(socket) as BluetoothSocket?
        }
        val innerSocket = inner.value
        when {
            inner.error != null -> item(section, innerName, Status.ERROR, describeError(inner))
            innerSocket == null -> item(section, innerName, Status.FAIL, "字段值为 null" + accessNote(inner))
            else -> item(section, innerName, Status.OK, "取到内部 socket" + accessNote(inner))
        }
        val portName = "读法 2b：反射内部 socket 的 mPort 字段"
        if (innerSocket == null) {
            item(section, portName, Status.SKIP, "拿不到内部 socket")
        } else {
            val port = attempt {
                BluetoothSocket::class.java.getDeclaredField("mPort").apply { isAccessible = true }.getInt(innerSocket)
            }
            if (port.error != null) {
                item(section, portName, Status.ERROR, describeError(port))
            } else {
                channelItem(section, portName, "mSocket.mPort", port.value, INNER_FIELD_MINUS_ONE, accessNote(port), valid)
            }
        }

        // 读法 3：反射隐藏方法 getChannel()。返回的是 toString() 打印的同一个字段。
        val channelName = "读法 3：反射隐藏方法 getChannel()"
        val channel = attempt { BluetoothServerSocket::class.java.getDeclaredMethod("getChannel").invoke(socket) as Int }
        if (channel.error != null) {
            item(section, channelName, Status.ERROR, describeError(channel))
        } else {
            channelItem(section, channelName, "getChannel()", channel.value, OUTER_FIELD_MINUS_ONE, accessNote(channel), valid)
        }

        // 读法 4：公开方法 getPsm()（安卓 10 起，本意给 L2CAP 用），读的也是同一个字段，不受隐藏接口限制。
        val psmName = "读法 4：getPsm()（公开方法，安卓 10 起）"
        if (Build.VERSION.SDK_INT >= 29) {
            val psm = attempt { socket.psm }
            if (psm.error != null) {
                item(section, psmName, Status.ERROR, describeError(psm))
            } else {
                channelItem(section, psmName, "getPsm()", psm.value, OUTER_FIELD_MINUS_ONE, "", valid)
            }
        } else {
            item(section, psmName, Status.SKIP, "安卓 10 起才有")
        }

        // 读法 5：反射内部 socket 的隐藏方法 getPort()，与 mPort 同值，但在隐藏接口名单里是另一个成员。
        val getPortName = "读法 5：反射内部 socket 的隐藏方法 getPort()"
        if (innerSocket == null) {
            item(section, getPortName, Status.SKIP, "拿不到内部 socket")
        } else {
            val port = attempt {
                BluetoothSocket::class.java.getDeclaredMethod("getPort").apply { isAccessible = true }
                    .invoke(innerSocket) as Int
            }
            if (port.error != null) {
                item(section, getPortName, Status.ERROR, describeError(port))
            } else {
                channelItem(section, getPortName, "getPort()", port.value, INNER_FIELD_MINUS_ONE, accessNote(port), valid)
            }
        }
        return valid
    }

    private fun channelItem(
        section: String,
        name: String,
        key: String,
        channel: Int?,
        minusOneMeaning: String,
        extra: String,
        valid: MutableMap<String, Int>,
    ) {
        val tail = if (extra.isEmpty()) "" else "；$extra"
        if (channel != null && ProbeText.isValidChannel(channel)) {
            valid[key] = channel
            item(section, name, Status.OK, "通道号 $channel$tail")
            return
        }
        val why = when (channel) {
            null -> "没取到通道号"
            -1 -> "-1：$minusOneMeaning"
            else -> "$channel：不在有效范围 1～30 内"
        }
        item(section, name, Status.FAIL, why + tail)
    }

    private fun summarizeChannels(section: String, valid: Map<String, Int>): Int? {
        val distinct = valid.values.distinct()
        return when {
            valid.isEmpty() -> {
                item(section, "结论", Status.FAIL, "没有一种读法拿到有效通道号")
                null
            }
            distinct.size == 1 -> {
                item(
                    section, "结论", Status.OK,
                    "通道号 ${distinct[0]}（${valid.keys.joinToString("、")} 读到）；" +
                        "请再和 SDP 查询、dumpsys 或按通道号直连的结果核对",
                )
                distinct[0]
            }
            else -> {
                item(section, "结论", Status.FAIL, "读法之间不一致：" + valid.entries.joinToString("，") { "${it.key}=${it.value}" })
                null
            }
        }
    }

    private fun compareChannels(a: Listener?, b: Listener?) {
        val name = "A、B 通道号对比（两个监听同时开着）"
        val ca = a?.channel
        val cb = b?.channel
        when {
            ca == null || cb == null -> item(SEC_B, name, Status.SKIP, "至少有一个监听没拿到有效通道号")
            ca != cb -> item(SEC_B, name, Status.OK, "A=$ca，B=$cb，不同")
            else -> item(SEC_B, name, Status.FAIL, "A、B 都是 $ca：同时开着的两个监听不该同号，读法可能读错了")
        }
    }

    /**
     * 备选：用隐藏方法开一个不加密、不建服务记录、由协议栈分配通道的监听。系统开这种监听时会把实际通道号
     * 写回服务端 socket 自己的字段，于是公开的 toString() / getPsm() 就能读到。第二个监听本来就是按通道号直连、
     * 不查 SDP，这种开法正好合用；代价是开监听这一步要用隐藏方法。
     */
    // 同上：lint 知道 listenUsingInsecureRfcommOn 在高 targetSdk 下会被拦，探针要测的正是这一点。
    @SuppressLint("SoonBlockedPrivateApi")
    private fun alternativeListener(others: List<Listener>) {
        val s = SEC_ALT
        val adapter = usableAdapter(s) ?: return
        // 老版本是带通道号参数的写法；新版本改成了无参。两个签名都试，各记一项。
        val withPortName = "开法 1：反射隐藏方法 listenUsingInsecureRfcommOn(int)，传 -2"
        val withPort = attempt {
            BluetoothAdapter::class.java
                .getDeclaredMethod("listenUsingInsecureRfcommOn", Int::class.javaPrimitiveType)
                .invoke(adapter, AUTO_CHANNEL_NO_SDP) as BluetoothServerSocket?
        }
        var socket = withPort.value
        if (socket != null) {
            item(s, withPortName, Status.OK, "开成了" + accessNote(withPort))
        } else {
            item(s, withPortName, Status.ERROR, describeError(withPort))
        }

        val noArgName = "开法 2：反射隐藏方法 listenUsingInsecureRfcommOn()（新版本的无参写法）"
        if (socket != null) {
            item(s, noArgName, Status.SKIP, "开法 1 已经开成，不再试")
        } else {
            val noArg = attempt {
                BluetoothAdapter::class.java.getDeclaredMethod("listenUsingInsecureRfcommOn")
                    .invoke(adapter) as BluetoothServerSocket?
            }
            socket = noArg.value
            if (socket != null) {
                item(s, noArgName, Status.OK, "开成了" + accessNote(noArg))
            } else {
                item(s, noArgName, Status.ERROR, describeError(noArg))
            }
        }

        if (socket == null) {
            item(s, "结论", Status.FAIL, "两种写法都没开成：这条路在这台设备、这个 targetSdk 下走不通")
            return
        }
        val listener = Listener("C", socket, null)
        listeners += listener
        val channel = summarizeChannels(s, readChannels(s, socket))
        listener.channel = channel
        if (channel != null && others.isNotEmpty()) {
            val clash = others.filter { it.channel == channel }
            val all = others.joinToString("，") { "${it.label}=${it.channel ?: "未知"}" }
            if (clash.isEmpty()) {
                item(s, "与 A、B 对比", Status.OK, "C=$channel，$all，互不相同")
            } else {
                item(s, "与 A、B 对比", Status.FAIL, "C=$channel，$all：与 ${clash.joinToString("、") { it.label }} 同号，读法可能读错了")
            }
        }
    }

    private fun usableAdapter(section: String): BluetoothAdapter? {
        val adapter = adapter
        if (adapter == null) {
            item(section, "开监听", Status.FAIL, "这台设备没有蓝牙适配器")
            return null
        }
        if (attempt { adapter.isEnabled }.value != true) {
            item(section, "开监听", Status.FAIL, "蓝牙未开启（或没有权限读蓝牙状态），请先打开蓝牙再测")
            return null
        }
        return adapter
    }

    // ---------------------------------------------------------------- 等待连接

    private fun acceptConnections(seconds: Int) {
        val s = SEC_ACCEPT
        val open = listeners.toList()
        if (open.isEmpty()) {
            item(s, "等待连接", Status.SKIP, "没有开着的监听")
            return
        }
        val targets = open.joinToString("\n") { l ->
            val how = l.uuid?.let { "UUID $it" } ?: "不建服务记录，只能按通道号连"
            "监听 ${l.label}：$how，通道号 ${l.channel ?: "未知"}"
        }
        item(s, "等待连接", Status.OK, "保持监听 $seconds 秒：可以用另一台设备连这个 UUID（或按通道号直连）来验证\n$targets")
        for (l in open) acceptThreads += thread(name = "btprobe-accept-${l.label}") { acceptLoop(l) }

        val deadline = SystemClock.elapsedRealtime() + seconds * 1000L
        var stoppedByUser = false
        while (true) {
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0) break
            if (stopWaitingRequested()) {
                stoppedByUser = true
                break
            }
            onWaiting(((left + 999) / 1000).toInt())
            try {
                Thread.sleep(minOf(left, WAIT_TICK_MS))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        onWaiting(0)
        val how = if (stoppedByUser) "已手动停止等待" else "$seconds 秒到了"
        val count = connections.get()
        if (count == 0) {
            item(s, "等待结果", Status.SKIP, "$how，期间没有设备连入（这项是可选的）")
        } else {
            item(s, "等待结果", Status.OK, "$how，一共连入 $count 次")
        }
    }

    /** 接受连接，记下对端地址后马上断开；监听被关掉时 accept 抛异常，循环结束。 */
    private fun acceptLoop(listener: Listener) {
        while (!closing) {
            val client = try {
                listener.socket.accept()
            } catch (e: IOException) {
                if (!closing) {
                    item(SEC_ACCEPT, "监听 ${listener.label} 停止接受连接", Status.ERROR, describeError(Attempt<Unit>(null, e, emptyList())))
                }
                return
            }
            val remote = attempt { client.remoteDevice?.address }.value
            attempt { client.close() }
            connections.incrementAndGet()
            item(
                SEC_ACCEPT, "监听 ${listener.label} 有设备连入", Status.OK,
                "对端 ${remote ?: "未知"}，已断开",
                "对端 ${remote?.let { ProbeText.maskAddress(it) } ?: "未知"}，已断开",
            )
        }
    }

    // ---------------------------------------------------------------- 结束与汇总

    private fun closeAll() {
        closing = true
        if (listeners.isEmpty()) item(SEC_END, "关闭监听", Status.SKIP, "没有开着的监听")
        for (l in listeners) {
            val closed = attempt { l.socket.close() }
            if (closed.error == null) {
                item(SEC_END, "关闭监听 ${l.label}", Status.OK, "已关闭")
            } else {
                item(SEC_END, "关闭监听 ${l.label}", Status.ERROR, describeError(closed))
            }
        }
        for (t in acceptThreads) {
            try {
                t.join(ACCEPT_JOIN_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        listeners.clear()
        acceptThreads.clear()
    }

    private fun hiddenApiSummary(runMark: Long) {
        val s = SEC_HIDDEN
        if (Build.VERSION.SDK_INT < 28) {
            item(s, "隐藏接口限制", Status.SKIP, "安卓 9 以下没有隐藏接口限制，上面的反射失败只可能是成员不存在")
            return
        }
        val hits = HiddenApiWatcher.hitsSince(runMark)
        val listed = if (hits.isEmpty()) "没有" else hits.joinToString("\n")
        when {
            HiddenApiWatcher.calibrated != true -> item(
                s, "应用内监听到的访问", Status.FAIL,
                "监听没生效（见系统信息里的自检）：前面「多半没有该成员」之类的判断不可信，以 logcat 为准。记到的访问：$listed",
            )
            hits.isEmpty() -> item(s, "应用内监听到的访问", Status.SKIP, "没有")
            else -> item(s, "应用内监听到的访问（放行和拦下的都算）", Status.OK, listed)
        }
        val lines = HiddenApiWatcher.readLogcatWarnings()
        when {
            lines == null -> item(
                s, "logcat 告警行", Status.SKIP,
                "读不到本应用的 logcat，请在电脑上查：adb logcat -d | grep \"Accessing hidden\"",
            )
            lines.isEmpty() -> item(
                s, "logcat 告警行", Status.SKIP,
                "日志里没找到相关告警行，可在电脑上再查一次：adb logcat -d | grep \"Accessing hidden\"",
            )
            else -> item(s, "logcat 告警行（括号里是所在名单，以及放行 allowed / 拦下 denied）", Status.OK, lines.joinToString("\n"))
        }
    }

    private fun item(section: String, name: String, status: Status, value: String, exportValue: String = value) {
        emit(ProbeItem(section, name, status, value, exportValue))
    }

    companion object {
        /** 安卓 12 起要在运行时申请的权限。 */
        val RUNTIME_PERMISSIONS = listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)

        // 协议要求的两个 RFCOMM 监听：A 带服务记录，手机用 SDP 查通道号；B 在认证阶段再开，
        // 车机把它的实际通道号通过协议告诉手机，手机按通道号直连、不查 SDP。
        val UUID_A: UUID = UUID.fromString("0000FE35-0000-1000-8000-00805F9B34FB")
        val UUID_B: UUID = UUID.fromString("0000FE36-0000-1000-8000-00805F9B34FB")
        const val NAME_A = "OpenHC-Probe-A"
        const val NAME_B = "OpenHC-Probe-B"

        const val SEC_SYSTEM = "系统信息"
        const val SEC_ADDRESS = "本机蓝牙地址"
        const val SEC_A = "监听 A（UUID FE35，带服务记录）"
        const val SEC_B = "监听 B（UUID FE36，带服务记录）"
        const val SEC_ALT = "备选：不加密、不建服务记录、由系统分配通道（隐藏方法）"
        const val SEC_ACCEPT = "接受连接（可选）"
        const val SEC_END = "结束"
        const val SEC_HIDDEN = "隐藏接口告警"
        const val SEC_ABORT = "检测中断"

        // 放开或豁免隐藏接口限制的全局设置：安卓 9 的键，与安卓 10 起的键。
        private val HIDDEN_API_SETTINGS_P =
            listOf("hidden_api_policy_pre_p_apps", "hidden_api_policy_p_apps", "hidden_api_blacklist_exemptions")
        private val HIDDEN_API_SETTINGS = listOf("hidden_api_policy", "hidden_api_blacklist_exemptions")

        // 开 RFCOMM 监听时通道号传 -2：由协议栈分配通道、不建服务记录（框架自安卓 6.0 起的约定）。
        private const val AUTO_CHANNEL_NO_SDP = -2

        // 除了公开接口的读法，其余两个是常见的出厂地址、蓝牙栈地址属性，作为补充一并试。
        private val PROPERTY_KEYS = listOf(
            "persist.sys.bt_addr" to "",
            "ro.boot.btmacaddr" to "（补充）",
            "persist.service.bdroid.bdaddr" to "（补充）",
        )

        private const val OUTER_FIELD_MINUS_ONE =
            "服务端 socket 自己记的通道号没被回写（按 UUID 开的监听在原生安卓里就是这样，不代表监听没开成）"
        private const val INNER_FIELD_MINUS_ONE = "内部 socket 的通道号还是初始值，协议栈没回写"

        private const val WAIT_TICK_MS = 200L
        private const val ACCEPT_JOIN_MS = 2_000L
        private const val MAX_STACK_CHARS = 2_000
    }
}
