package io.github.gcbond.openhc.platform.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.Settings
import java.lang.reflect.InvocationTargetException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/** 本地热点开好以后交出的实际信息，原样写进给手机的连接串。toString 不含网络名和密码。 */
class LocalHotspot(
    /** 车机在热点网段上的 IPv4（热点网关），已经能 bind。 */
    val localIp: String,
    val networkName: String,
    /** 小写、冒号分隔。 */
    val bssid: String,
    val passphrase: String,
    /** 报给手机的频率（MHz），多数时候是推测值，见 [frequencySource]。 */
    val frequencyMhz: Int,
    /** 热点网卡名，日志用。 */
    val interfaceName: String,
    val bssidSource: HotspotMacSource,
    val frequencySource: HotspotFrequencySource,
    val security: HotspotSecurity,
) {
    override fun toString() =
        "LocalHotspot($localIp，$frequencyMhz MHz，网卡 $interfaceName，BSSID ${MacText.mask(bssid)}，" +
            "加密 ${security.label}，网络名与密码不显示)"
}

/** 开热点的结果，在热点自己的工作线程上回调，不要阻塞。[LocalHotspotHandle.stop] 之后不再回调。 */
interface LocalHotspotEvents {
    /** 热点开好了，网卡上也有了地址。 */
    fun onReady(hotspot: LocalHotspot)

    /** 没开成（说明给用户和日志看，不含网络名和密码）。 */
    fun onFailed(message: String)

    /** 开好以后热点没了（被系统收回）。 */
    fun onLost(message: String)
}

/** 一次开热点。[start] 开始申请，[stop] 关掉（不等手机离开）；两者都不阻塞，可以在任意线程调用。 */
interface LocalHotspotHandle {
    fun start()

    fun stop()
}

/**
 * 车机开本地热点（无线连接时给手机准备的 WiFi 网络）：系统公开的「只供本地通信」热点（安卓 8.0 起），名称和密码由系统生成、
 * 开好后读回来，手机按普通 WiFi 加入。进程里只有一个（[get]），每次连接用 [create] 要一次，用完 [LocalHotspotHandle.stop]。
 *
 * 安卓 5.0～7.x 没有官方接口（startLocalOnlyHotspot 是安卓 8.0 才公开的）：走 WifiManager 的隐藏接口开热点——
 * 应用自己预设网络名（OpenHC-XXXX）和 WPA2 口令，开了以后读回系统里真实生效的配置，后面的流程和官方腿一样；
 * 安卓 9.0 起系统封锁隐藏接口，这条反射腿只服务 8.0 以下。
 *
 * - 申请的先后、间隔、主人中途走了怎么办，都由 [HotspotRequestMachine] 决定（规则见它的说明）：两次申请之间至少隔 3.5 秒，
 *   热点被关时不等手机离开。
 * - **先开好留着**：无线开着时调 [keepWarm]，不等连接要就申请，连接之间不关，无线停了调 [coolDown]。
 * - 申请热点的系统调用前后各记一行（含用时）；调用卡住时另一条线程定时记下它卡在哪里。
 * - 开好之后：读系统给的网络名、密码、加密方式和频段，再在网卡上找热点地址（开热点前后各拍一次网卡快照，
 *   见 [LocalHotspotRules.pickInterface]），找到才交出 [LocalHotspot]。
 * - **频率**：普通应用读不到本地热点实际开在哪个信道（只有系统接口给得出），交出的多半是推测值
 *   （[LocalHotspotRules.estimateFrequency]，按系统配置里的频段，默认 2.4 GHz 第 6 信道）。
 * - **BSSID**：系统配置里一般没有；热点网卡的硬件地址在安卓 10 及以前读得到，安卓 11 起对高 targetSdk 的应用多半给不出，
 *   这时用占位值。
 * - 单射频的车机开热点时自己连着的 WiFi 会断开（系统行为）。每次申请前记下车机 WiFi 开没开（[stationWasOnBeforeHotspot]），
 *   给「退出后恢复车机 WiFi」用。
 *
 * 系统的要求：安卓 8～12 要精确位置权限、系统定位开关打开；安卓 13 起要「附近的 WiFi 设备」权限；应用要在前台。
 * 定位权限是「使用期间允许」时，只有界面在前台才算数，光有前台服务不算（2026-11-22 安卓 10 车机实测：界面在后台时
 * 系统拒绝「does not have Coarse/Fine Location permission」）。安卓 8.0 起，车机已经开着个人热点或在共享网络时开不了
 * （系统的限制）；8.0 以下的反射腿遇到车主自己开着的热点则直接复用：不动它的网络名和密码，结束后也不关（见 [requestLegacyHotspot]）。
 * 权限由调用方在开始前确认。
 */
// 权限由调用方在开始前确认（见类注释）；没有时系统拒绝申请或回失败，都按失败报。
@SuppressLint("MissingPermission")
class LocalHotspotOwner private constructor(context: Context) {
    /** 安卓 7.0 以前用界面 Context 取 WifiManager 会泄漏，统一从应用 Context 取；本对象不另外持有 Context。 */
    private val wifi: WifiManager? = (context.applicationContext ?: context).getSystemService(Context.WIFI_SERVICE) as? WifiManager

    /**
     * 设备号（ANDROID_ID）末四位十六进制，反射腿的热点名后缀用：设备不变名字就不变，手机记过一次网络就行。
     * 构造时取一次，本对象照旧不持有 Context；个别 ROM 读不到时为 null，那时退到本进程随机生成的后缀（见 [freshTag]）。
     */
    private val deviceTag: String? = try {
        Settings.Secure.getString((context.applicationContext ?: context).contentResolver, Settings.Secure.ANDROID_ID)
            ?.let { if (it.length > 4) it.takeLast(4) else it }
            ?.takeIf { it.isNotEmpty() }
            ?.uppercase()
    } catch (e: RuntimeException) {
        null
    }
    private val thread = HandlerThread("OpenHC-Hotspot").also { it.start() }
    private val handler = Handler(thread.looper)

    /** 看申请热点的系统调用有没有卡住：卡住时工作线程动不了，只能在另一条线程上看。 */
    private val watchThread = HandlerThread("OpenHC-HotspotWatch").also { it.start() }
    private val watchHandler = Handler(watchThread.looper)

    /** 安卓 13 起尝试读系统回报的热点实际频率和 BSSID（读不到就推测）；回报在工作线程上交付。 */
    private val infoWatcher: HotspotInfoWatcher? = wifi
        ?.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU }
        ?.let { manager -> HotspotInfoWatcher(manager, { handler.post(it) }) { note(it) } }

    // ---------------------------------------------------------------- 以下只在工作线程上访问

    private val machine = HotspotRequestMachine()
    private val runs = HashMap<Int, Run>()

    /** 已经停了、还在等收尾（让会话的最后几个包先发出去）的连接：id → 收尾任务。 */
    private val draining = HashMap<Int, Runnable>()

    /** 系统给的热点：安卓 8.0 起是 LocalOnlyHotspotReservation；5.0～7.x 反射腿是读回的 WifiConfiguration。 */
    private var reservation: Any? = null

    /**
     * 反射腿（安卓 5.0～7.x）的热点名后缀和 WPA2 口令，本进程里不变（名字稳定可读：手机记过一次网络就行）；
     * 跨进程重启重新生成也不碍事——开了热点后读回的都是系统里真实生效的配置（见 [requestLegacyHotspot]）。
     */
    private var legacySuffix: String? = null
    private var legacyPassphrase: String? = null

    /**
     * 现在手里的热点是车主自己在系统里开着的（反射腿复用，见 [requestLegacyHotspot]）：只借不关。
     * 复用时不预设网络名和口令，结束时 [closeQuietly] 也不调 setWifiApEnabled(false)。只在工作线程上访问。
     */
    private var borrowedHotspot = false

    /** 最近一次申请前的网卡快照：网卡名 → IPv4。 */
    private var before: Map<String, Set<String>> = emptyMap()

    /** 最近一次失败的具体原因（系统回话或调用被拒）。 */
    private var lastFailure: String? = null

    /** 先开好留着时给的日志：没有连接用着热点时，申请和系统回话写到这里。 */
    private var standbyLog: ((String) -> Unit)? = null
    private val wake = Runnable { drive { machine.onTick(now()) } }

    // ---------------------------------------------------------------- 任意线程可读

    @Volatile
    private var stationNote = false

    @Volatile
    private var busyNow = false

    private val idLock = Any()
    private var nextId = 1

    /** 从上次 [clearStationNote] 起，有过一次申请热点时车机 WiFi 是开着的（之后被热点顶掉的不算关）。 */
    val stationWasOnBeforeHotspot: Boolean get() = stationNote

    fun clearStationNote() {
        stationNote = false
    }

    /** 有连接在用（或在等）热点，或者有申请还没回话。 */
    val busy: Boolean get() = busyNow

    /** 准备开一次热点：[LocalHotspotHandle.start] 才开始申请。[log] 在工作线程上调用。 */
    fun create(events: LocalHotspotEvents, log: (String) -> Unit = {}): LocalHotspotHandle {
        val id = synchronized(idLock) { nextId++ }
        return Run(id, events, log)
    }

    /**
     * 先把热点开好留着（无线开着时调用）：现在就申请，连接结束也不关，下一次连接直接接手，直到 [coolDown]。
     * 系统要求申请时应用在前台；在后台申请会失败，回到前台时再调一次即可（已经开着或在申请时什么都不做）。
     * [log] 在工作线程上调用。
     */
    fun keepWarm(log: (String) -> Unit) {
        handler.post {
            standbyLog = log
            if (!machine.held) log("无线开着：先把热点开好留着，不等手机认证（连接之间不关）")
            drive { machine.hold(now()) }
        }
    }

    /** 不用再留着热点了（无线停了）：没有连接在用就关掉。 */
    fun coolDown() {
        handler.post {
            if (!machine.held) return@post
            note("无线停了：不再留着热点")
            drive { machine.unhold(now()) }
        }
    }

    private enum class Stage { NEW, WAITING_SYSTEM, WAITING_ADDRESS, READY, ENDED }

    private inner class Run(val id: Int, private val events: LocalHotspotEvents, val log: (String) -> Unit) : LocalHotspotHandle {
        @Volatile
        private var stopped = false

        // 以下只在工作线程上访问。
        private var stage = Stage.NEW
        private var info: ReservationInfo? = null
        private var addressDeadline = 0L
        private var infoDeadline = 0L
        private var wlanFallbackAt = 0L
        private val poll = Runnable { pollAddress() }

        override fun start() {
            handler.post {
                if (stopped || stage != Stage.NEW) return@post
                // 上一次连接还在收尾：先把它的热点关掉，不然这次会被当成「另一次连接正在用热点」。
                flushDraining()
                stage = Stage.WAITING_SYSTEM
                runs[id] = this
                drive { machine.start(id, now()) }
            }
        }

        /** 立即不再回调；稍等一下再关热点，让会话收尾时最后几个包（例如投屏的 TEARDOWN）先发出去。 */
        override fun stop() {
            stopped = true
            handler.post {
                if (stage == Stage.ENDED && !runs.containsKey(id)) return@post
                stage = Stage.ENDED
                handler.removeCallbacks(poll)
                runs.remove(id)
                val finish = Runnable {
                    draining.remove(id)
                    drive { machine.stop(id, now()) }
                }
                draining[id] = finish
                handler.postDelayed(finish, STOP_DRAIN_MS)
            }
        }

        /** 系统开好了热点：读配置，再等网卡上出现地址。 */
        fun onSystemStarted(reservation: Any) {
            if (stopped || stage != Stage.WAITING_SYSTEM) return
            val read = readReservation(reservation)
            info = read
            log("系统开好了热点：加密 ${read.security.label}，${read.hint ?: "读不到频段"}")
            when {
                LocalHotspotRules.networkName(read.ssid) == null -> return fail("读不到热点的网络名")
                read.security == HotspotSecurity.NO_PASSPHRASE || read.passphrase.isNullOrEmpty() ->
                    return fail("系统开的热点没有口令（开放热点），这种热点手机按这套连接方式连不上")
                read.security == HotspotSecurity.WPA3_ONLY -> log("热点只开了 WPA3：手机按 WPA2 口令可能连不上，连不上时先查这个")
            }
            stage = Stage.WAITING_ADDRESS
            addressDeadline = now() + ADDRESS_WAIT_MS
            infoDeadline = now() + INFO_WAIT_MS
            wlanFallbackAt = now() + WLAN_FALLBACK_DELAY_MS
            pollAddress()
        }

        private fun pollAddress() {
            if (stopped || stage != Stage.WAITING_ADDRESS) return
            // 车机自己的 WiFi 还开着（能一边连 WiFi 一边开热点）时，「只有一个 wlan 地址就用它」的兜底推迟一会儿再用，
            // 免得热点网卡的地址还没出现就选了车机 WiFi 的地址；开热点时 WiFi 会断开的车机不用等。
            val wlanFallback = !stationEnabled() || now() >= wlanFallbackAt
            val picked = LocalHotspotRules.pickInterface(before, snapshot(), stationIpv4(), wlanFallback)
            // 挂上了热点信息回调、回报还没到：再等一会儿（最多 INFO_WAIT_MS），拿到实际频率和 BSSID 再交出去。
            val waitInfo = infoWatcher?.let { it.registered && it.frequencyMhz == 0 } == true && now() < infoDeadline
            if (picked != null && !waitInfo) return ready(picked.first, picked.second)
            if (picked != null) {
                handler.postDelayed(poll, ADDRESS_POLL_MS)
                return
            }
            if (now() < addressDeadline) {
                handler.postDelayed(poll, ADDRESS_POLL_MS)
                return
            }
            // 失败时把网卡快照和车机 WiFi 状态记下：分得清"地址没出现"和"被车机自己的 WiFi 地址排除了"。
            val now = snapshot()
            log(
                "[快照] 开热点前网卡=${before.keys.sorted()}；现在网卡=${now.keys.sorted()}；" +
                    "车机 WiFi 开着=${stationEnabled()}，车机 WiFi 地址=${stationIpv4() ?: "无"}，" +
                    "车机 WiFi 频率=${stationFrequency()} MHz；新出现的地址=${now.keys.filter { it !in before.keys }.ifEmpty { listOf("无") }}",
            )
            fail("热点开好了，但 ${ADDRESS_WAIT_MS / 1000} 秒内没在网卡上找到它的地址")
        }

        private fun ready(interfaceName: String, ip: String) {
            val read = info ?: return fail("热点信息丢了")
            val name = LocalHotspotRules.networkName(read.ssid) ?: return fail("读不到热点的网络名")
            val passphrase = read.passphrase ?: return fail("读不到热点的口令")
            val (bssid, macSource) = LocalHotspotRules.pickBssid(read.bssid, hardwareAddress(interfaceName), infoWatcher?.bssid)
            val (frequency, frequencySource) = LocalHotspotRules.estimateFrequency(read.hint, infoWatcher?.frequencyMhz ?: 0)
            val hotspot = LocalHotspot(ip, name, bssid, passphrase, frequency, interfaceName, macSource, frequencySource, read.security)
            stage = Stage.READY
            log(
                "热点就绪：$hotspot；频率：${frequencySource.label}；BSSID：${macSource.label}；" +
                    "车机 WiFi 现在${if (stationEnabled()) "开着" else "关着"}（当前连接 ${stationFrequency()} MHz）",
            )
            if (!stopped) events.onReady(hotspot)
        }

        fun fail(message: String) {
            if (stage == Stage.ENDED) return
            stage = Stage.ENDED
            handler.removeCallbacks(poll)
            log("热点没开成：$message")
            if (!stopped) events.onFailed(message)
        }

        fun lose(message: String) {
            if (stage != Stage.READY) return fail(message)
            stage = Stage.ENDED
            log(message)
            if (!stopped) events.onLost(message)
        }
    }

    // ---------------------------------------------------------------- 状态机的输出

    /**
     * 调状态机，按顺序做它要做的事；结束后按它的下一次时间重排定时。只在工作线程上调用（可以嵌套）。
     *
     * 别改名叫 apply：在内部类 [Run] 里写 `apply { … }` 会被编译成标准库的作用域函数，
     * 状态机要做的事（申请热点、把热点交给连接）被悄悄丢掉，什么日志都没有。
     */
    private fun drive(step: () -> List<HotspotEffect>) {
        for (effect in step()) {
            when (effect) {
                HotspotEffect.Request -> requestNow()
                HotspotEffect.Release -> releaseNow()
                is HotspotEffect.Started -> {
                    val held = reservation
                    if (held == null) {
                        lastFailure = "系统回话里没有热点"
                        drive { machine.onFailed(now()) }
                    } else {
                        runs[effect.owner]?.onSystemStarted(held)
                    }
                }
                is HotspotEffect.Failed -> runs[effect.owner]?.fail(failureText(effect.failure))
                is HotspotEffect.Lost -> runs[effect.owner]?.lose("热点被系统收回了（例如车机 WiFi 被关、开了个人热点）")
                is HotspotEffect.Deferred ->
                    runs[effect.owner]?.log("刚关掉热点 ${MIN_GAP_TEXT}内，${effect.delayMillis} 毫秒后再申请（太快重新申请系统会卡住不回话）")
                is HotspotEffect.WakeAt -> Unit
            }
        }
        busyNow = machine.busy
        handler.removeCallbacks(wake)
        machine.nextWakeAt?.let { at -> handler.postDelayed(wake, (at - now()).coerceAtLeast(0)) }
    }

    private fun failureText(failure: HotspotFailure): String = when (failure) {
        HotspotFailure.SYSTEM -> "${failure.label}：${lastFailure ?: "原因不明"}"
        HotspotFailure.NO_ANSWER -> "申请开热点后系统 ${HotspotRequestMachine.ANSWER_TIMEOUT_MILLIS / 1000} 秒没有回应"
        HotspotFailure.BUSY -> failure.label
    }

    /** 申请热点。申请前拍网卡快照（认热点网卡用）、记下车机 WiFi 开没开（恢复 WiFi 用）。 */
    private fun requestNow() {
        lastFailure = null
        borrowedHotspot = false
        // 门控：安卓 8.0 起走官方接口；4.4～7.x 没有官方接口，走反射隐藏接口（见 [requestLegacyHotspot]）。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return rejected("安卓 4.4 以下没有本地热点（需要安卓 4.4 及以上）")
        val manager = wifi ?: return rejected("拿不到系统的 WiFi 服务")
        // 安卓 8.0 以下（到 5.0 为止）走反射腿：申请、开、读回都在这一条调用里同步完成。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return requestLegacyHotspot(manager)
        before = snapshot()
        infoWatcher?.reset()
        infoWatcher?.register()
        val stationOn = stationEnabled()
        if (stationOn) stationNote = true
        note("向系统申请本地热点（车机 WiFi ${if (stationOn) "开着，当前连接 ${stationFrequency()} MHz" else "关着"}）")
        // 看门狗在另一条线程上跑：写到哪个日志在这里先取好，调用返回后它不再记、也不再排下一次。
        val log = currentLog()
        val returned = AtomicBoolean(false)
        val calledAt = now()
        val caller = Thread.currentThread()
        val watchdog = object : Runnable {
            override fun run() {
                if (returned.get()) return
                log("申请热点的系统调用 ${now() - calledAt} 毫秒还没返回（线程状态 ${caller.state}），卡在：${stackText(caller)}")
                if (!returned.get()) watchHandler.postDelayed(this, CALL_WATCH_REPEAT_MS)
            }
        }
        watchHandler.postDelayed(watchdog, CALL_WATCH_FIRST_MS)
        try {
            // 系统的回话都在工作线程上（给了它这个 Handler）。
            manager.startLocalOnlyHotspot(
                object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) = onSystemStarted(reservation)

                    override fun onStopped() = onSystemStopped()

                    override fun onFailed(reason: Int) = onSystemFailed(reason)
                },
                handler,
            )
            note("系统收下了申请（调用用时 ${now() - calledAt} 毫秒），等系统回话")
        } catch (e: SecurityException) {
            rejected("系统不让开热点（${e.message}）：安卓 8～12 要精确位置权限、系统定位开关要打开，安卓 13 起要「附近的 WiFi 设备」权限")
        } catch (e: IllegalStateException) {
            // 系统按进程记申请：上一次申请还没了结时再申请会被拒。
            rejected("系统说本应用上一次开热点的申请还没了结（${e.message}），稍后再试")
        } catch (e: RuntimeException) {
            rejected("开热点出错：${e.javaClass.simpleName}：${e.message}")
        } finally {
            returned.set(true)
            watchHandler.removeCallbacks(watchdog)
        }
    }

    // ---------------------------------------------------------------- 安卓 5.0～7.x 的反射腿

    /**
     * 安卓 5.0～7.x 的申请腿：5.0 没有官方开本地热点的接口（startLocalOnlyHotspot 是安卓 8.0 才公开的），只能反射
     * WifiManager 的隐藏接口；9.0 起系统封锁隐藏接口，这条腿只服务 8.0 以下。流程：预设配置（部分 ROM 不让应用预设，
     * 失败不致命，接着用系统当前的热点配置开）→ setWifiApEnabled(true) 开热点 → getWifiApConfiguration() 读回真实
     * 生效的配置（部分 ROM 会改应用预设的网络名和口令，必读回），当回话交给 [onSystemStarted]；之后读配置、等网卡
     * 地址的流程和官方腿完全一样。只在工作线程上调用。
     */
    @Suppress("DEPRECATION")
    private fun requestLegacyHotspot(manager: WifiManager) {
        // 车主自己在系统里开着热点：直接复用它，不动网络名和密码，结束后也不关（那是车主开的热点，不是我们的）。
        // 开放热点（没设密码）协议用不了、配置读不回时没法给手机连接串，都报清楚让车主选，不悄悄改掉他的热点。
        if (apEnabled(manager)) {
            before = emptyMap()
            val config = callHidden(manager, "getWifiApConfiguration", arrayOf()) as? WifiConfiguration
                ?: return rejected(
                    "车机已经开着热点，但读不回它的配置（网络名和密码拿不到），手机没法加入；" +
                        "给热点设一个 WPA2 密码再关开一次，或关掉它让我们自己开",
                )
            if (unquoted(config.preSharedKey).isNullOrEmpty()) {
                return rejected(
                    "车机已经开着的热点没有密码（开放热点），手机按这套连接方式进不了网；" +
                        "给热点设一个 WPA2 密码，或关掉它让我们自己开",
                )
            }
            borrowedHotspot = true
            note("车机已经开着热点：直接用它（不动网络名和密码，连接结束后也不关）")
            onSystemStarted(config)
            return
        }
        before = snapshot()
        val stationOn = stationEnabled()
        if (stationOn) stationNote = true
        note(
            "用系统的隐藏接口申请热点（安卓 8.0 以下没有官方本地热点接口；车机 WiFi " +
                "${if (stationOn) "开着，当前连接 ${stationFrequency()} MHz" else "关着"}）",
        )
        val calledAt = now()
        // 先预设网络名（OpenHC-XXXX）和 WPA2 口令。失败不致命：部分 ROM 不让应用预设，接着用系统当前的热点配置开。
        val wanted = makeLegacyConfig()
        if (callHidden(manager, "setWifiApConfiguration", arrayOf(WifiConfiguration::class.java), wanted) != true) {
            note("预设热点配置没成（这台机器的系统不让应用改热点配置），用系统当前的热点配置开")
        }
        // 开热点。部分 ROM 会拦（SecurityException）或删接口（NoSuchMethod），都按申请被拒报。
        val enabled = watchingCall("开热点的隐藏调用") {
            callHidden(
                manager, "setWifiApEnabled",
                arrayOf(WifiConfiguration::class.java, Boolean::class.javaPrimitiveType!!), wanted, true,
            )
        }
        if (enabled != true) return rejected("这台机器的系统不让应用开热点（安卓 8.0 以下靠系统的隐藏接口，它被拦或没有）")
        // 开好了：读回真实生效的配置——部分 ROM 会改应用预设的网络名和口令，必读回；后面交给和官方腿一样的流程。
        val effective = callHidden(manager, "getWifiApConfiguration", arrayOf()) as? WifiConfiguration
            ?: return rejected("热点开了，但读不回系统的热点配置（网络名和口令给不出）")
        note("隐藏接口开好了热点（用时 ${now() - calledAt} 毫秒），读回生效配置")
        onSystemStarted(effective)
    }

    /**
     * 热点是不是已经在跑。三个信号任一成立就算（2026-11-25 车机实测：只信 getWifiApState 会漏判，把正在广播的热点
     * 当成没开、改掉配置，发给手机的密码对不上）：
     * - getWifiApState / isWifiApEnabled：AOSP 用 3 或 13 表示开着，正在开是 2/12/14（4.4 的 14 就是 enabling，
     *   之前漏了这个值，车主刚打开热点、应用马上来申请时会被当成没开，于是改掉他的热点配置另开一个）；
     * - 网卡上已经出现热点网关地址，且不是车机作为终端连着的那个 WiFi 地址——不依赖 ROM 的取值编号；
     * - 处在「正在开」的过渡态时先等一小会儿（最多 [AP_SETTLE_WAIT_MS]）再判断，别在系统还没开好时抢着改配置。
     */
    private fun apEnabled(manager: WifiManager): Boolean {
        val state = apState(manager)
        val byBool = (callHidden(manager, "isWifiApEnabled", arrayOf()) as? Boolean) == true
        var byState = byBool || (state != null && state in AP_ENABLED_STATES)
        var byAddress = !byState && hasHotspotAddress()
        note(
            "系统热点状态：getWifiApState=${state ?: "读不到"}，isWifiApEnabled=$byBool，" +
                "按状态=$byState，按网卡地址=$byAddress，有地址的网卡=${snapshot().keys.sorted()}",
        )
        if (byState || byAddress) return true
        if (state == null || state !in AP_TRANSITION_STATES) return false
        // 正在开/关热点的过渡态：等它稳定，再决定是复用还是自己开。
        val deadline = now() + AP_SETTLE_WAIT_MS
        while (now() < deadline) {
            Thread.sleep(AP_SETTLE_POLL_MS)
            val next = apState(manager)
            byState = (callHidden(manager, "isWifiApEnabled", arrayOf()) as? Boolean) == true ||
                (next != null && next in AP_ENABLED_STATES)
            byAddress = !byState && hasHotspotAddress()
            if (byState || byAddress) {
                note("系统热点稳定了：getWifiApState=$next，按状态=$byState，按网卡地址=$byAddress")
                return true
            }
            if (next == null || next !in AP_TRANSITION_STATES) return false
        }
        // 一直没稳定：按车主的热点正在开处理，走复用；配置读不回时会明确报错，不会去改掉他的热点。
        note("热点一直处在过渡态（getWifiApState=$state），按已开着处理")
        return true
    }

    /** 读系统热点状态；4.4 起方法名有 getWifiApState，个别 ROM 是 getWifiApEnabledState。 */
    private fun apState(manager: WifiManager): Int? {
        (callHidden(manager, "getWifiApState", arrayOf()) as? Int)?.let { return it }
        return callHidden(manager, "getWifiApEnabledState", arrayOf()) as? Int
    }

    /** wlan/ap 类网卡上已经有一个地址，而且不是车机作为终端连着的 WiFi 地址（那就是热点网关）。 */
    private fun hasHotspotAddress(): Boolean {
        val station = stationIpv4()
        for ((name, addresses) in snapshot()) {
            if (LocalHotspotRules.rankOf(name) == null) continue
            if (addresses.any { it != station }) return true
        }
        return false
    }

    /**
     * 反射腿的热点配置：网络名 OpenHC-XXXX、WPA2 口令。
     * 热点配置（setWifiApConfiguration）存的是原始字符串，不带引号；带引号是 wpa_supplicant 客户端配置的写法。
     * 之前误按后者加了引号，车机真的广播出 "OpenHC-B54E"（连引号一起），手机按连接串里去引号的名字找网，找不到。
     */
    @Suppress("DEPRECATION")
    private fun makeLegacyConfig(): WifiConfiguration {
        // 名字要稳定可读：后缀优先设备号（设备不变名字就不变，手机记过一次网络就行），读不到时用本进程随机生成的四位。
        val suffix = legacySuffix ?: deviceTag ?: freshTag().also { legacySuffix = it }
        // 口令不从设备号推——名字后缀已经露了设备号，口令别跟着可猜；本进程里生成一次不再变。
        val passphrase = legacyPassphrase ?: freshLegacyPassphrase().also { legacyPassphrase = it }
        val config = WifiConfiguration()
        config.SSID = "OpenHC-$suffix"
        config.preSharedKey = passphrase
        config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA2_PSK)
        return config
    }

    /** 随机四位的热点名后缀（读不到设备号时用），去掉了容易看错的 0、1、O、I；本进程里不再变。 */
    private fun freshTag(): String {
        val alphabet = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
        return buildString { repeat(LEGACY_TAG_CHARS) { append(alphabet[Random.nextInt(alphabet.length)]) } }
    }

    /** 反射腿给热点的 WPA2 口令：12 位可读 ASCII（小写字母加数字，去掉了容易看错的字符），在 8～63 位的限制内。 */
    private fun freshLegacyPassphrase(): String {
        val alphabet = "abcdefghijkmnpqrstuvwxyz23456789"
        return buildString { repeat(LEGACY_PASSPHRASE_CHARS) { append(alphabet[Random.nextInt(alphabet.length)]) } }
    }

    /**
     * 反射调 WifiManager 的隐藏方法（5.0 开热点用的接口不在公开 API 里）。失败记一行日志、返回 null，调用方按各自的
     * 失败路径处理：找不到方法多半是 ROM 删了接口，抛异常多半是系统拦截（SecurityException 等）。只在工作线程上调用。
     */
    private fun callHidden(manager: WifiManager, name: String, types: Array<Class<*>>, vararg args: Any?): Any? = try {
        manager.javaClass.getMethod(name, *types).invoke(manager, *args)
    } catch (e: InvocationTargetException) {
        // 方法本体抛出来的（例如部分 ROM 在这里拦 SecurityException）：把里面那层原因记下来。
        note("反射调 $name 失败：${e.targetException?.javaClass?.simpleName}：${e.targetException?.message}")
        null
    } catch (e: ReflectiveOperationException) {
        // 找不到方法、访问不了（ROM 删了接口；9.0 起的隐藏接口封锁也落在这里，这条腿只服务 8.0 以下）。
        note("反射调 $name 失败：${e.javaClass.simpleName}：${e.message}")
        null
    } catch (e: RuntimeException) {
        note("反射调 $name 失败：${e.javaClass.simpleName}：${e.message}")
        null
    }

    /** 反射腿的系统调用也套看门狗（同官方腿）：调用卡住时另一条线程定时记下它卡在哪里。 */
    private fun <T> watchingCall(what: String, call: () -> T): T {
        // 看门狗在另一条线程上跑：写到哪个日志在这里先取好，调用返回后它不再记、也不再排下一次。
        val log = currentLog()
        val returned = AtomicBoolean(false)
        val calledAt = now()
        val caller = Thread.currentThread()
        val watchdog = object : Runnable {
            override fun run() {
                if (returned.get()) return
                log("$what ${now() - calledAt} 毫秒还没返回（线程状态 ${caller.state}），卡在：${stackText(caller)}")
                if (!returned.get()) watchHandler.postDelayed(this, CALL_WATCH_REPEAT_MS)
            }
        }
        watchHandler.postDelayed(watchdog, CALL_WATCH_FIRST_MS)
        return try {
            call()
        } finally {
            returned.set(true)
            watchHandler.removeCallbacks(watchdog)
        }
    }

    /** 线程现在的调用栈（只取最上面几层），诊断用。 */
    private fun stackText(thread: Thread): String = try {
        thread.stackTrace.take(STACK_FRAMES).joinToString(" ← ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
    } catch (e: SecurityException) {
        "读不到调用栈"
    }

    /** 申请和系统回话写到谁的日志里：有连接在用热点写它的，没有写先开好留着时给的。只在工作线程上调用。 */
    private fun note(message: String) = currentLog()(message)

    /** 见 [note]。只在工作线程上调用。 */
    private fun currentLog(): (String) -> Unit = machine.owner?.let { runs[it]?.log } ?: standbyLog ?: {}

    private fun rejected(message: String) {
        lastFailure = message
        drive { machine.onRequestRejected(now()) }
    }

    /** 系统回话：热点开好了（[held] 是系统给的热点）。 */
    private fun onSystemStarted(held: Any?) {
        note(
            when {
                borrowedHotspot -> "用车主开着的热点（不关，手机直接加入）"
                machine.owner == null && machine.held -> "系统回话：热点开好了，留着等手机"
                else -> "系统回话：热点开好了"
            },
        )
        if (held == null) {
            lastFailure = "系统回话里没有热点"
            drive { machine.onFailed(now()) }
            return
        }
        // 系统按进程只给一个热点；万一手里还有旧的，先关掉。
        val old = reservation
        if (old != null && old !== held) closeQuietly(old)
        reservation = held
        drive { machine.onStarted(now()) }
    }

    /** 系统回话：热点被收回了。系统已经收回，手里的这个不用再关。 */
    private fun onSystemStopped() {
        note("系统回话：热点被收回了")
        reservation = null
        drive { machine.onStopped(now()) }
    }

    private fun onSystemFailed(reason: Int) {
        lastFailure = reasonText(reason)
        note("系统回话：没开成（$lastFailure）")
        drive { machine.onFailed(now()) }
    }

    private fun releaseNow() {
        infoWatcher?.unregister()
        val held = reservation ?: return
        reservation = null
        closeQuietly(held)
    }

    private fun closeQuietly(held: Any) {
        // 车主自己开着的热点只借不关：关掉等于替车主关了他的个人热点。
        if (borrowedHotspot) {
            note("热点是车主自己开的，不关")
            return
        }
        // 安卓 8.0 起系统给的是 LocalOnlyHotspotReservation；5.0～7.x 反射腿给的是读回的 WifiConfiguration，用隐藏接口关。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return closeLegacyHotspot(held)
        try {
            (held as? WifiManager.LocalOnlyHotspotReservation)?.close()
        } catch (e: RuntimeException) {
            // 关不掉也无妨：系统在本进程退出时会收回。
        }
    }

    /**
     * 关 5.0～7.x 反射腿开的热点：反射 setWifiApEnabled(false)。关不掉最多是热点还开着（车机上像开了个人热点），
     * 不该让关热点的路径抛出去——异常一律在 [callHidden] 里吞掉记日志。
     */
    @Suppress("DEPRECATION")
    private fun closeLegacyHotspot(held: Any) {
        val manager = wifi ?: return
        // 关热点时系统不看这个参数：传读回的配置，读不回时传 null 也一样。
        val config = held as? WifiConfiguration
        val off = callHidden(
            manager, "setWifiApEnabled",
            arrayOf(WifiConfiguration::class.java, Boolean::class.javaPrimitiveType!!), config, false,
        )
        if (off == true) note("反射关掉了热点") else note("热点没关掉（隐藏接口没回话或被拦），车机上的热点可能还开着")
    }

    /** 还在收尾的连接马上收完（新的连接要开热点了）。 */
    private fun flushDraining() {
        if (draining.isEmpty()) return
        for ((_, task) in draining.toList()) {
            handler.removeCallbacks(task)
            task.run()
        }
    }

    // ---------------------------------------------------------------- 读系统给的热点

    private class ReservationInfo(
        val ssid: String?,
        val passphrase: String?,
        val bssid: String?,
        val security: HotspotSecurity,
        val hint: HotspotBandHint?,
    )

    private fun readReservation(held: Any): ReservationInfo {
        // 安卓 8.0 起系统给的是 LocalOnlyHotspotReservation；5.0～7.x 反射腿给的是读回的 WifiConfiguration。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return readLegacyReservation(held)
        val unknown = ReservationInfo(null, null, null, HotspotSecurity.UNKNOWN, null)
        val reservation = held as? WifiManager.LocalOnlyHotspotReservation ?: return unknown
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val config = reservation.softApConfiguration
            @Suppress("DEPRECATION")
            val ssid = config.ssid
            return ReservationInfo(
                ssid = ssid,
                passphrase = config.passphrase,
                bssid = config.bssid?.toString(),
                security = LocalHotspotRules.securityOf(config.securityType),
                // 频段、信道没有公开的取值接口，从系统给的文字描述里读（读不出就按推测）。
                hint = LocalHotspotRules.bandHintFromDescription(config.toString()),
            )
        }
        @Suppress("DEPRECATION")
        val config = reservation.wifiConfiguration
            ?: return ReservationInfo(null, null, null, HotspotSecurity.UNKNOWN, null)
        val passphrase = config.preSharedKey
        return ReservationInfo(
            ssid = config.SSID,
            passphrase = passphrase,
            bssid = config.BSSID,
            security = if (passphrase.isNullOrEmpty()) HotspotSecurity.NO_PASSPHRASE else HotspotSecurity.OK,
            // 老系统的热点配置有频段、信道两个字段（不在公开接口里，读不到就按推测）。
            hint = LocalHotspotRules.bandHintFromLegacy(intField(config, "apBand"), intField(config, "apChannel")),
        )
    }

    /**
     * 5.0～7.x 反射腿开的热点：[held] 是从系统读回的 WifiConfiguration，网络名和口令就在公开字段里（wpa_supplicant
     * 的存法带一对引号，读时去掉，读出的形状和官方腿一致）。BSSID 一般没有（热点配置里通常不存）；频段、信道不在
     * 公开接口里，反射读隐藏 int 字段（apBand/apChannel 要 7.x 的新系统才有；读不到按 2.4 GHz 推测，与 8.0 的
     * 老配置走同一条推测路径）。
     */
    @Suppress("DEPRECATION")
    private fun readLegacyReservation(held: Any): ReservationInfo {
        val config = held as? WifiConfiguration
            ?: return ReservationInfo(null, null, null, HotspotSecurity.UNKNOWN, null)
        val passphrase = unquoted(config.preSharedKey)
        return ReservationInfo(
            ssid = unquoted(config.SSID),
            passphrase = passphrase,
            bssid = config.BSSID,
            security = if (passphrase.isNullOrEmpty()) HotspotSecurity.NO_PASSPHRASE else HotspotSecurity.OK,
            hint = LocalHotspotRules.bandHintFromLegacy(intField(config, "apBand"), intField(config, "apChannel")),
        )
    }

    /** WifiConfiguration 里 SSID 和口令带一对引号（wpa_supplicant 的存法），去掉；没带引号原样返回。 */
    private fun unquoted(text: String?): String? =
        if (text != null && text.length >= 2 && text.startsWith("\"") && text.endsWith("\"")) text.substring(1, text.length - 1) else text

    private fun intField(target: Any, name: String): Int? = try {
        target.javaClass.getField(name).getInt(target)
    } catch (e: NoSuchFieldException) {
        null
    } catch (e: IllegalAccessException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    private fun reasonText(reason: Int): String = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) "原因 $reason" else when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "没有可用的信道"
        WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC ->
            "系统内部错误（刚关掉热点又马上申请、或安卓 8～12 系统定位开关没开时常见）"
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE ->
            "当前状态开不了：车机可能开着个人热点或在共享网络，或者本应用不在前台"
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "系统策略不允许开热点"
        else -> "原因 $reason"
    }

    // ---------------------------------------------------------------- 工具

    private fun stationEnabled(): Boolean = try {
        wifi?.isWifiEnabled == true
    } catch (e: RuntimeException) {
        false
    }

    /** 车机作为终端正连着的 WiFi 的频率（日志用），没连为 0。 */
    @Suppress("DEPRECATION")
    private fun stationFrequency(): Int = try {
        val info = wifi?.connectionInfo
        if (info == null || info.networkId == -1) 0 else info.frequency.coerceAtLeast(0)
    } catch (e: RuntimeException) {
        0
    }

    /**
     * 车机作为终端正连着的 WiFi 上的 IPv4，没连为 null。选热点网卡时排除它：能一边连 WiFi 一边开热点的车机上，
     * 它可能被误当成热点地址。
     */
    @Suppress("DEPRECATION")
    private fun stationIpv4(): String? = try {
        val ip = wifi?.connectionInfo?.ipAddress ?: 0
        if (ip == 0) null else "${ip and 0xFF}.${ip ushr 8 and 0xFF}.${ip ushr 16 and 0xFF}.${ip ushr 24 and 0xFF}"
    } catch (e: RuntimeException) {
        null
    }

    /** 开着的网卡和上面的 IPv4（不含回环、链路本地）。 */
    private fun snapshot(): Map<String, Set<String>> {
        val out = HashMap<String, Set<String>>()
        try {
            val all = NetworkInterface.getNetworkInterfaces() ?: return out
            for (nif in Collections.list(all)) {
                val up = try {
                    nif.isUp && !nif.isLoopback
                } catch (e: SocketException) {
                    false
                }
                if (!up) continue
                val addresses = Collections.list(nif.inetAddresses)
                    .filter { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress }
                    .mapNotNull { it.hostAddress }
                    .toSet()
                if (addresses.isNotEmpty()) out[nif.name] = addresses
            }
        } catch (e: SocketException) {
            // 读不到就当没有，找不到地址时会报出来。
        } catch (e: RuntimeException) {
            // 同上。
        }
        return out
    }

    /** 安卓 11 起以高 targetSdk 运行时一般返回 null（系统不给普通应用网卡 MAC），由 [LocalHotspotRules.pickBssid] 退到占位值。 */
    private fun hardwareAddress(name: String): ByteArray? = try {
        NetworkInterface.getByName(name)?.hardwareAddress
    } catch (e: SocketException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    private fun now() = SystemClock.elapsedRealtime()

    companion object {
        /** 热点开好后等网卡上出现地址的上限。 */
        const val ADDRESS_WAIT_MS = 3_000L
        const val ADDRESS_POLL_MS = 100L

        /** 挂上了热点信息回调时，系统开好热点后等它回报实际频率和 BSSID 的上限（到点按推测）。 */
        const val INFO_WAIT_MS = 1_500L

        /** 车机 WiFi 还开着时，「只有一个 wlan 地址就用它」的兜底在系统开好热点这么久以后才用（车主实测过 1.5 秒够）。 */
        const val WLAN_FALLBACK_DELAY_MS = 1_500L

        /** 停掉后过这么久才关热点：让会话收尾时最后几个包先发出去。 */
        const val STOP_DRAIN_MS = 200L

        private const val MIN_GAP_TEXT = "3.5 秒"

        /** 申请热点的系统调用多久没返回就记下它卡在哪里，之后每隔多久再记一次。 */
        private const val CALL_WATCH_FIRST_MS = 3_000L
        private const val CALL_WATCH_REPEAT_MS = 5_000L
        private const val STACK_FRAMES = 12

        /** 反射腿（安卓 5.0～7.x）随机热点名后缀的位数、WPA2 口令的位数（WPA2 口令要求 8～63 位）。 */
        private const val LEGACY_TAG_CHARS = 4
        private const val LEGACY_PASSPHRASE_CHARS = 12

        /**
         * getWifiApState 的取值。AOSP 与部分 ROM 编号不同，两套都认：
         * AOSP：11 关着 / 12 正在关 / 13 开着 / 14 正在开 / 15 失败；
         * 另一套（部分 ROM）：1 关着 / 2 正在开 / 3 开着 / 4 失败。
         * 14 是安卓 4.4 的「正在开」——之前漏了它，车主刚打开热点、应用马上来申请时会被当成没开。
         */
        private val AP_ENABLED_STATES = setOf(3, 13)
        private val AP_TRANSITION_STATES = setOf(2, 12, 14)

        /** 检测到热点处于「正在开/正在关」的过渡态时，最多等这么久再下结论，每 [AP_SETTLE_POLL_MS] 看一次。 */
        private const val AP_SETTLE_WAIT_MS = 2_500L
        private const val AP_SETTLE_POLL_MS = 250L

        @Volatile
        private var instance: LocalHotspotOwner? = null

        /** 进程里唯一的一个（系统按进程记热点申请）。 */
        fun get(context: Context): LocalHotspotOwner =
            instance ?: synchronized(this) { instance ?: LocalHotspotOwner(context).also { instance = it } }

        /** 本进程用过热点才有；没用过返回 null（不为此建工作线程）。 */
        fun existing(): LocalHotspotOwner? = instance
    }
}
