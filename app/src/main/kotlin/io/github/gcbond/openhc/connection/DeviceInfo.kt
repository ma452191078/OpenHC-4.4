package io.github.gcbond.openhc.connection

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import io.github.gcbond.openhc.platform.media.DisplayRefreshRate
import io.github.gcbond.openhc.platform.wifi.MacText

/** 从系统读车机的蓝牙地址属性和屏幕参数。 */
internal object DeviceInfo {
    private const val TAG = "OpenHC-Wireless"

    /** 设置表里蓝牙协议栈落库的本机地址键名。 */
    private const val SETTING_KEY = "bluetooth_address"

    /**
     * 依次从三处读车机自己的蓝牙地址，读不到返回 null：
     * 1. 系统属性 [BluetoothAddressPolicy.SYSTEM_PROPERTY_KEY]（部分车机写在属性里）；
     * 2. 设置表 `Settings.Secure` 的 `bluetooth_address`（安卓 6 起蓝牙协议栈把真实地址写在这里，
     *    `BluetoothAdapter.getAddress()` 只给占位值，这里绕过它拿真实地址；个别 ROM 拦第三方读取，就当没有）；
     * 3. 蓝牙适配器地址（安卓 6.0 以下直接可用）。
     */
    fun systemBluetoothAddress(context: Context): String? {
        val fromProperty = systemPropertyBluetoothAddress()
        if (fromProperty != null) {
            Log.i(TAG, "[蓝牙地址] 系统属性 ${BluetoothAddressPolicy.SYSTEM_PROPERTY_KEY} 读到 ${MacText.mask(fromProperty)}")
            return fromProperty
        }
        val fromSetting = settingBluetoothAddress(context)
        if (fromSetting != null) {
            Log.i(TAG, "[蓝牙地址] 设置表 bluetooth_address 读到 ${MacText.mask(fromSetting)}")
            return fromSetting
        }
        val fromAdapter = adapterBluetoothAddress()
        Log.i(
            TAG,
            "[蓝牙地址] 系统属性和设置表都没读到；蓝牙适配器（SDK ${Build.VERSION.SDK_INT}）" +
                if (fromAdapter != null) "读到 ${MacText.mask(fromAdapter)}" else "也没读到",
        )
        return fromAdapter
    }

    /**
     * 读设置表 `Settings.Secure` 的 `bluetooth_address`：这是蓝牙协议栈落库的真实地址（`adb shell settings get
     * secure bluetooth_address` 读的就是它）。大多数 ROM 允许第三方应用读；读不到或读到占位值交给上层校验，返回 null。
     */
    private fun settingBluetoothAddress(context: Context): String? = try {
        Settings.Secure.getString(context.contentResolver, SETTING_KEY)?.takeIf { it.isNotBlank() }
    } catch (e: RuntimeException) {
        Log.w(TAG, "[蓝牙地址] 读设置表 bluetooth_address 失败：${e.javaClass.simpleName}：${e.message}")
        null
    }

    private fun systemPropertyBluetoothAddress(): String? = try {
        val get = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        (get.invoke(null, BluetoothAddressPolicy.SYSTEM_PROPERTY_KEY) as? String)?.takeIf { it.isNotBlank() }
    } catch (e: ReflectiveOperationException) {
        Log.w(TAG, "[蓝牙地址] 读系统属性失败：${e.javaClass.simpleName}：${e.message}")
        null
    } catch (e: RuntimeException) {
        Log.w(TAG, "[蓝牙地址] 读系统属性异常：${e.javaClass.simpleName}：${e.message}")
        null
    }

    /**
     * 安卓 6.0 以下（含 5.0）普通应用能直接读蓝牙适配器的真实地址（BluetoothAdapter.getAddress）；
     * 6.0 起才只给占位值 02:00:00:00:00:00，由 [BluetoothAddressPolicy] 的占位判断挡掉。
     */
    @Suppress("DEPRECATION")
    // 已按 SDK 分流并捕获 SecurityException；LOCAL_MAC_ADDRESS 是系统权限，普通应用拿不到，这里只是尽力读取。
    @SuppressLint("MissingPermission")
    private fun adapterBluetoothAddress(): String? {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            Log.i(TAG, "[蓝牙地址] SDK ${android.os.Build.VERSION.SDK_INT} ≥ 23，不读适配器地址（系统只给占位值）")
            return null
        }
        return try {
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) {
                Log.w(TAG, "[蓝牙地址] getDefaultAdapter() 返回 null：这台机器没有蓝牙适配器")
                null
            } else {
                Log.i(TAG, "[蓝牙地址] 适配器状态 ${adapter.state}（12 开，10 关）")
                adapter.address?.takeIf { it.isNotBlank() }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "[蓝牙地址] 读适配器地址被拒：${e.message}")
            null
        } catch (e: RuntimeException) {
            Log.w(TAG, "[蓝牙地址] 读适配器地址异常：${e.javaClass.simpleName}：${e.message}")
            null
        }
    }

    /** 默认屏幕（车机主屏）的实际像素、DPI 和按刷新率算出的帧率。 */
    fun screen(context: Context): ScreenSpec {
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        val size = Point()
        if (display != null) {
            // getRealSize/getRealMetrics 在安卓 12 被标为过时（新接口要界面 Context），但仍然返回整块屏幕的实际像素，
            // 应用 Context 上也能用。
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
        }
        if (size.x <= 0 || size.y <= 0) {
            val fallback = context.resources.displayMetrics
            size.set(fallback.widthPixels, fallback.heightPixels)
            metrics.densityDpi = fallback.densityDpi
        }
        val dpi = if (metrics.densityDpi > 0) metrics.densityDpi else DisplayMetrics.DENSITY_DEFAULT
        return ScreenSpec(size.x, size.y, dpi, DisplayRefreshRate.declaredFps(context))
    }
}
