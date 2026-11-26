package io.github.gcbond.openhc.platform.wifi

import android.content.Context
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings

/** 车机 WiFi 的现状：开始无线连接时记进日志，排查开热点的问题用。只读，调用很快，不阻塞。 */
object WifiStatus {

    class Snapshot(
        val wifiEnabled: Boolean,
        /** 车机正连着的 WiFi 的频率（MHz），没连为 0。 */
        val stationFrequencyMhz: Int,
        val supports5g: Boolean,
        /** 系统定位开关；读不到为 null。安卓 8～12 开热点要它打开。 */
        val locationEnabled: Boolean?,
    ) {
        override fun toString() =
            "WiFi=${if (wifiEnabled) "开" else "关"}，" +
                "当前连接 $stationFrequencyMhz MHz，5G=${if (supports5g) "支持" else "不支持"}，定位开关=${locationEnabled ?: "未知"}"
    }

    fun read(context: Context): Snapshot {
        val wifi = wifiManager(context)
        val enabled = try {
            wifi?.isWifiEnabled == true
        } catch (e: RuntimeException) {
            false
        }
        // is5GHzBandSupported 是安卓 5.0 起的接口，4.4 上直接查会 NoSuchMethodError（Error，catch 不住），必须先判版本。
        val fiveG = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                wifi?.is5GHzBandSupported == true
            } catch (e: RuntimeException) {
                false
            }
        } else {
            false
        }
        return Snapshot(
            wifiEnabled = enabled,
            stationFrequencyMhz = stationFrequency(context),
            supports5g = fiveG,
            locationEnabled = locationEnabled(context),
        )
    }

    /**
     * 车机作为终端正连着的 WiFi 的频率，没连或读不到为 0。安卓 12 起这个接口标为过时（新接口要注册网络回调），
     * 但仍然返回当前连接的信息，频率不受隐私限制。
     */
    @Suppress("DEPRECATION")
    fun stationFrequency(context: Context): Int = try {
        val info = wifiManager(context)?.connectionInfo
        if (info == null || info.networkId == -1) 0 else info.frequency.coerceAtLeast(0)
    } catch (e: RuntimeException) {
        0
    }

    private fun locationEnabled(context: Context): Boolean? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE) != Settings.Secure.LOCATION_MODE_OFF
        }
    } catch (e: Settings.SettingNotFoundException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    /** 安卓 7.0 以前用界面 Context 取 WifiManager 会泄漏，统一从应用 Context 取。 */
    private fun wifiManager(context: Context): WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
}
