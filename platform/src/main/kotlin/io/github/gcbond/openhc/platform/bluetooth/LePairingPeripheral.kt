package io.github.gcbond.openhc.platform.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import java.util.UUID

/**
 * 让手机和车机补一次 LE 配对的入口：一个读取要求加密的 GATT 特征，加一路带设备名的可连接广播。
 *
 * 为什么要配：车机系统蓝牙开了 LE 隐私时，BLE 广播的发送地址是可解析的随机地址。手机要先和车机做过 LE 配对、
 * 拿到车机的身份解析密钥，才能把随机地址解回车机的身份地址；只做过经典蓝牙配对的手机解不出来，按随机地址去连
 * 车机的经典蓝牙必然失败。车机发一路手机蓝牙设置页能列出来的广播，车主在手机上点它配对，就补上了这一步。
 *
 * 本类只开入口，不参与配对本身：配对由两边的系统蓝牙完成（车机上可能弹系统的配对确认），结果看系统的配对状态广播，
 * 由调用方处理。
 *
 * - GATT：主服务 0x180F（蓝牙规范里的电池服务）下一个特征 0x2A19（电池电量，1 字节，取值 0～100），只读、读要加密链路。
 *   没加密的手机来读，系统蓝牙栈直接回「加密不足」，手机一般会就此发起配对；读请求能到这里，说明链路已经加密
 *   （[Events.onEncryptedRead]）。服务加好以后才开广播，免得手机连上来时服务还没挂上。
 * - 广播：传统广播、可连接（因而可扫描）、低延迟档、高发射功率、不超时；数据里带完整设备名（系统当前的蓝牙名称）和
 *   16 位服务 UUID 0x180F，不带功率字段，扫描响应为空。传统广播数据最多 31 字节：Flags 3 + 服务 UUID 4 + 名称 2 +
 *   名称本身，所以名称最多 22 字节（UTF-8），长了系统报数据太长。名称由调用方事先改好。
 * - 发送地址和 [BleBeacon] 一样由系统蓝牙栈决定，应用改不了。
 *
 * 线程：[open]、[close] 任意线程；GATT 回调在系统的线程上，广播回调在主线程上，[Events] 就在那里回调，里面不要阻塞。
 * [close] 可重复调用，之后不再回调；广播还在启动中时先记下，等系统回了「已开始」再真正停掉。
 *
 * 权限：安卓 12 起要「附近的设备」（BLUETOOTH_CONNECT、BLUETOOTH_ADVERTISE），更早是清单里的普通蓝牙权限；没有时报 onFailed。
 */
class LePairingPeripheral(
    context: Context,
    private val adapter: BluetoothAdapter,
    private val events: Events,
    private val log: (String) -> Unit,
) {
    interface Events {
        /** GATT 服务加好、系统确认开始广播。 */
        fun onAdvertising()

        /** 开不了或中途失败（不是本机关的）。之后不再回调。 */
        fun onFailed(failure: Failure)

        /** 有手机经 LE 连上或断开（地址原文，调用方自己决定怎么记）。 */
        fun onClientConnection(address: String, connected: Boolean)

        /** 有手机在加密链路上读了特征：两边已经完成（或早就有）LE 加密配对。 */
        fun onEncryptedRead(address: String)
    }

    enum class Stage { GATT, ADVERTISING }

    enum class Reason {
        /** 车机不支持 BLE 广播。 */
        UNSUPPORTED,

        /** 广播实例用完了（别的应用、或本应用等手机回连的广播也在发）。 */
        TOO_MANY_ADVERTISERS,

        /** 广播数据太长（设备名太长）。 */
        DATA_TOO_LARGE,

        /** 没有蓝牙权限。 */
        PERMISSION,
        OTHER,
    }

    /** 失败的经过。[detail] 是给日志和界面看的说明。 */
    class Failure(val stage: Stage, val reason: Reason, val detail: String) {
        override fun toString() = "$stage/$reason：$detail"
    }

    private enum class AdvertiseState { NONE, STARTING, STOP_PENDING, STARTED, STOPPED }

    private val app = context.applicationContext ?: context
    private val lock = Any()
    private var started = false
    private var closed = false
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertiseState = AdvertiseState.NONE

    /** 开服务端、加服务，加好后开广播。只能调用一次；结果经 [Events] 回来（可能在本方法里同步回调）。 */
    // 权限由调用方在开始前确认；没有时系统抛 SecurityException，下面按失败上报。
    @SuppressLint("MissingPermission")
    fun open() {
        synchronized(lock) {
            check(!started) { "open 只能调用一次" }
            started = true
        }
        val manager = app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: return fail(Stage.GATT, Reason.UNSUPPORTED, "没有蓝牙服务")
        val opened = try {
            manager.openGattServer(app, gattCallback)
        } catch (e: SecurityException) {
            return fail(Stage.GATT, Reason.PERMISSION, "没有蓝牙权限：${describe(e)}")
        } catch (e: RuntimeException) {
            return fail(Stage.GATT, Reason.OTHER, "开不了 GATT 服务端：${describe(e)}")
        } ?: return fail(Stage.GATT, Reason.OTHER, "系统没有给 GATT 服务端（蓝牙没开？）")
        val keep = synchronized(lock) {
            if (!closed) server = opened
            !closed
        }
        if (!keep) return closeServer(opened)
        val added = try {
            opened.addService(buildService())
        } catch (e: RuntimeException) {
            log("LE 配对：加服务出错：${describe(e)}")
            false
        }
        if (!added) fail(Stage.GATT, Reason.OTHER, "加服务失败")
    }

    /** 停广播、关服务端（撤掉服务，系统里不再有本应用挂的这个服务）。可重复调用；之后不再回调。 */
    fun close() {
        val stopNow: Boolean
        val s: BluetoothGattServer?
        synchronized(lock) {
            if (closed) return
            closed = true
            stopNow = when (advertiseState) {
                AdvertiseState.STARTED -> true
                // 还在启动中：等系统回了结果再停（启动没完成就停，部分系统版本会漏停）。
                AdvertiseState.STARTING -> {
                    advertiseState = AdvertiseState.STOP_PENDING
                    false
                }
                else -> false
            }
            if (stopNow) advertiseState = AdvertiseState.STOPPED
            s = server
            server = null
        }
        if (stopNow) stopAdvertising()
        if (s != null) closeServer(s)
    }

    private fun buildService(): BluetoothGattService {
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                LEVEL_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
            ),
        )
        return service
    }

    // ---------------------------------------------------------------- 广播

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        val le = try {
            adapter.bluetoothLeAdvertiser
        } catch (e: RuntimeException) {
            null
        } ?: return fail(Stage.ADVERTISING, Reason.UNSUPPORTED, "系统没有提供广播接口（蓝牙没开，或车机不支持 BLE 广播）")
        synchronized(lock) {
            if (closed) return
            advertiser = le
            advertiseState = AdvertiseState.STARTING
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        try {
            le.startAdvertising(settings, data, advertiseCallback)
        } catch (e: SecurityException) {
            advertiseFailed(Reason.PERMISSION, "没有广播权限（安卓 12 起要「附近的设备」权限）：${describe(e)}")
        } catch (e: RuntimeException) {
            advertiseFailed(Reason.OTHER, "开不了广播：${describe(e)}")
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            val previous = synchronized(lock) {
                val s = advertiseState
                if (s == AdvertiseState.STARTING) advertiseState = AdvertiseState.STARTED
                if (s == AdvertiseState.STOP_PENDING) advertiseState = AdvertiseState.STOPPED
                s
            }
            when (previous) {
                AdvertiseState.STARTING -> {
                    log("LE 配对：广播已开始")
                    events.onAdvertising()
                }
                // 启动中被要求停：现在才真正停掉。
                AdvertiseState.STOP_PENDING -> stopAdvertising()
                else -> Unit
            }
        }

        override fun onStartFailure(errorCode: Int) {
            advertiseFailed(reasonOf(errorCode), describe(errorCode))
        }
    }

    /** 广播没开起来：只在还在启动时上报。 */
    private fun advertiseFailed(reason: Reason, detail: String) {
        val report = synchronized(lock) {
            val starting = advertiseState == AdvertiseState.STARTING
            if (starting || advertiseState == AdvertiseState.STOP_PENDING) advertiseState = AdvertiseState.STOPPED
            starting
        }
        if (report) fail(Stage.ADVERTISING, reason, detail)
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        val le = synchronized(lock) { advertiser } ?: return
        try {
            le.stopAdvertising(advertiseCallback)
        } catch (e: RuntimeException) {
            // 蓝牙已经关了、权限被收回等：广播本来也停了。
        }
    }

    // ---------------------------------------------------------------- GATT 回调

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            if (isClosed()) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("LE 配对：服务已加上")
                startAdvertising()
            } else {
                fail(Stage.GATT, Reason.OTHER, "加服务失败（状态 $status）")
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            if (isClosed()) return
            events.onClientConnection(addressOf(device), newState == BluetoothProfile.STATE_CONNECTED)
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic?,
        ) {
            if (device == null || isClosed()) return
            // 特征的值只有 1 字节：偏移 0 回整个值，偏移 1 回空，再往后越界。
            val (status, value) = when {
                characteristic?.uuid != LEVEL_UUID -> BluetoothGatt.GATT_READ_NOT_PERMITTED to null
                offset == 0 -> BluetoothGatt.GATT_SUCCESS to byteArrayOf(LEVEL_VALUE)
                offset == 1 -> BluetoothGatt.GATT_SUCCESS to ByteArray(0)
                else -> BluetoothGatt.GATT_INVALID_OFFSET to null
            }
            respond(device, requestId, status, offset, value)
            if (status == BluetoothGatt.GATT_SUCCESS) events.onEncryptedRead(addressOf(device))
        }
    }

    @SuppressLint("MissingPermission")
    private fun respond(device: BluetoothDevice, requestId: Int, status: Int, offset: Int, value: ByteArray?) {
        val s = synchronized(lock) { server } ?: return
        try {
            if (!s.sendResponse(device, requestId, status, offset, value)) log("LE 配对：回应读请求失败")
        } catch (e: RuntimeException) {
            log("LE 配对：回应读请求出错：${describe(e)}")
        }
    }

    // ---------------------------------------------------------------- 工具

    private fun fail(stage: Stage, reason: Reason, detail: String) {
        val failure = Failure(stage, reason, detail)
        val report = !isClosed()
        log("LE 配对：$failure")
        close()
        if (report) events.onFailed(failure)
    }

    private fun isClosed() = synchronized(lock) { closed }

    companion object {
        /** 蓝牙规范里的电池服务、电池电量特征（16 位 UUID 按蓝牙基础 UUID 展开）。 */
        val SERVICE_UUID: UUID = UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB")
        val LEVEL_UUID: UUID = UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB")

        /** 电池电量特征回的值（百分比）。 */
        private const val LEVEL_VALUE: Byte = 100

        @SuppressLint("MissingPermission")
        private fun closeServer(s: BluetoothGattServer) {
            try {
                s.clearServices()
            } catch (e: RuntimeException) {
                // 蓝牙已经关了等：服务端本来也没了。
            }
            try {
                s.close()
            } catch (e: RuntimeException) {
                // 同上。
            }
        }

        private fun addressOf(device: BluetoothDevice?): String = try {
            device?.address
        } catch (e: RuntimeException) {
            null
        } ?: ""

        private fun reasonOf(errorCode: Int): Reason = when (errorCode) {
            AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> Reason.UNSUPPORTED
            AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> Reason.TOO_MANY_ADVERTISERS
            AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> Reason.DATA_TOO_LARGE
            else -> Reason.OTHER
        }

        private fun describe(errorCode: Int): String = when (errorCode) {
            AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "广播数据太长"
            AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "系统的广播实例用完了"
            AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "已经在广播"
            AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "系统蓝牙内部错误"
            AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "车机不支持 BLE 广播"
            else -> "错误码 $errorCode"
        }

        private fun describe(e: Throwable): String = e.javaClass.simpleName + (e.message?.let { "：$it" } ?: "")
    }
}
