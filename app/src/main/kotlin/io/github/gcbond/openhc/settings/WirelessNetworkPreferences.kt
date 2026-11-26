package io.github.gcbond.openhc.settings

import android.content.Context
import androidx.core.content.edit

/**
 * 无线连接里 WiFi 那一段的设置（单独一个 SharedPreferences 文件）：退出后恢复车机 WiFi。
 * 在连接结束时读。任意线程可读写。
 */
class WirelessNetworkPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * 退出后恢复车机 WiFi（默认关）：无线连接结束、热点关掉以后，如果车机 WiFi 是被本应用的热点顶掉的，就把它打开
     * （安卓 10 起应用不能自己打开 WiFi，改为提示车主去系统里打开）。
     */
    var restoreWifiOnExit: Boolean
        get() = prefs.getBoolean(KEY_RESTORE_WIFI, false)
        set(value) = prefs.edit { putBoolean(KEY_RESTORE_WIFI, value) }

    private companion object {
        const val FILE = "openhc-wireless-network"
        const val KEY_RESTORE_WIFI = "restore_wifi_on_exit"
    }
}
