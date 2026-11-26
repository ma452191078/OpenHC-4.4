package io.github.gcbond.openhc.wireless

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import io.github.gcbond.openhc.OpenHcApp
import io.github.gcbond.openhc.settings.WirelessPreferences

/**
 * 「蓝牙连上自动连接」的入口：手机的经典蓝牙连上 / 断开车机时（系统广播 ACL_CONNECTED / ACL_DISCONNECTED），
 * 交给 [WirelessController.onPhoneBluetooth]。清单里静态登记，本应用没在运行时系统也会把它拉起来
 * （车机系统要允许本应用在后台被拉起）。
 *
 * 清单里默认不启用，只在开关开着、且没开「只用数据线连接」时启用（[sync]），免得每连一个蓝牙设备都拉起本应用。
 * 只认蓝牙设备类是「手机」的（[BluetoothTrigger.isPhone]），耳机、音箱、手表不算。
 */
class BluetoothConnectionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val connected = when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> true
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> false
            else -> return
        }
        // 4.4 fork：androidx.core 固定在 1.12（IntentCompat 要 1.13），改用各版本都有的老接口。
        @Suppress("DEPRECATION")
        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
        // 读设备类在安卓 12 起要「附近的设备」权限；没有时读不到，当作不是手机（没这个权限无线本来也连不了）。
        val major = try {
            device.bluetoothClass?.majorDeviceClass
        } catch (e: SecurityException) {
            null
        }
        val address = try {
            device.address
        } catch (e: RuntimeException) {
            null
        } ?: return
        // 设备类只是「不认识这台设备时」的过滤条件：本机记过的手机（用无线投屏成功过）不管它报什么类都算数——
        // 部分手机/车机系统把手机报成音频类，按类过滤会把该拨的手机挡掉。
        if (!BluetoothTrigger.isPhone(major)) {
            val known = WirelessPreferences(context).knownPhones.any { it.equals(address, ignoreCase = true) }
            if (!known) return
        }
        val app = context.applicationContext as? OpenHcApp ?: return
        app.wireless.onPhoneBluetooth(connected, address)
    }

    companion object {
        private const val TAG = "OpenHC-Wireless"

        /** 按设置启用或停用清单里的这个接收器。立即生效，不影响正在运行的进程。 */
        fun sync(context: Context, enabled: Boolean) {
            val component = ComponentName(context, BluetoothConnectionReceiver::class.java)
            val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            try {
                val pm = context.packageManager
                if (pm.getComponentEnabledSetting(component) == state) return
                pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
                Log.i(TAG, "蓝牙连上自动连接的系统广播入口：${if (enabled) "启用" else "停用"}")
            } catch (e: RuntimeException) {
                Log.w(TAG, "改不了蓝牙广播入口的启用状态：${e.message}")
            }
        }
    }
}

/** 「蓝牙连上自动连接」的判断（纯逻辑）。 */
object BluetoothTrigger {
    /** 蓝牙设备大类「手机」（蓝牙规范的设备类取值，与 BluetoothClass.Device.Major.PHONE 相同）。 */
    const val MAJOR_PHONE = 0x0200

    /** 连上的是不是手机；读不到设备类时不算。 */
    fun isPhone(majorDeviceClass: Int?): Boolean = majorDeviceClass == MAJOR_PHONE

    /** 要不要听手机蓝牙连上：开关开着、没开「只用数据线连接」。 */
    fun listens(connectOnBluetooth: Boolean, wiredOnly: Boolean): Boolean = connectOnBluetooth && !wiredOnly
}

/**
 * 兜底：不用 ACL_CONNECTED 广播也能知道手机蓝牙是不是连着。
 *
 * 部分车机 ROM 不投递这个系统广播（或被权限管家拦下），「蓝牙连上自动连接」就失效了。
 * 这里用系统隐藏接口 `BluetoothDevice.isConnected()` 定时读经典蓝牙链路状态：安卓 9 起它属于
 * 非 SDK 接口，可能被反射限制拒绝，读不到返回 null（调用方跳过，不影响其他逻辑）。
 */
object BluetoothLinkState {
    /** 链路连着返回 true，断着 false，读不到（没有接口、反射被拒、异常）返回 null。 */
    fun isConnected(device: BluetoothDevice): Boolean? = try {
        val method = BluetoothDevice::class.java.getMethod("isConnected")
        method.invoke(device) as? Boolean
    } catch (e: Throwable) {
        // NoSuchMethod / SecurityException / 非 SDK 接口限制都按「读不到」处理。
        null
    }
}
