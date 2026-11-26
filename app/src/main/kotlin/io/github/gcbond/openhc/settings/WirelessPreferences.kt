package io.github.gcbond.openhc.settings

import android.content.Context
import android.os.Build
import androidx.core.content.edit

/**
 * 无线连接方式与自动连接手机的设置（单独一个 SharedPreferences 文件）。开关切换立即生效。
 * 任意线程可读写。
 */
class WirelessPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * 只用数据线连接（默认关）：开着时完全不做无线——不发 BLE 广播、不等手机回连、不理会手机蓝牙连上，
     * 「开始无线连接」也开始不了。只影响无线，有线照常。
     */
    var wiredOnly: Boolean
        get() = prefs.getBoolean(KEY_WIRED_ONLY, false)
        set(value) = prefs.edit { putBoolean(KEY_WIRED_ONLY, value) }

    /**
     * 自动连接手机（默认开；存储的键沿用旧名「蓝牙连上自动连接」）：手机和车机的经典蓝牙一连上（打电话、听歌用的那种连接），
     * 或者打开本应用时（含开机自动打开），车机主动去拨用无线连成功过的手机（[knownPhones]）。本应用没在运行时由系统拉起来。
     */
    var connectOnBluetooth: Boolean
        get() = prefs.getBoolean(KEY_CONNECT_ON_BLUETOOTH, true)
        set(value) = prefs.edit { putBoolean(KEY_CONNECT_ON_BLUETOOTH, value) }

    /**
     * 无线方式选「WiFi 直连」（默认关，即车机开热点；试验）：手机认证通过后车机建 WiFi 直连组让手机加入、不开本地热点，
     * 优先 5 GHz。下一次无线连接生效；系统低于安卓 5.0 时不起作用（见 [usesWifiDirect]）。
     */
    var wifiDirect: Boolean
        get() = prefs.getBoolean(KEY_WIFI_DIRECT, false)
        set(value) = prefs.edit { putBoolean(KEY_WIFI_DIRECT, value) }

    /** 下一次无线连接真的走 WiFi 直连：选了直连，而且系统支持（[wifiDirectSupported]）。 */
    val usesWifiDirect: Boolean get() = wifiDirect && wifiDirectSupported

    /** 用无线连成功过（投屏开始过）的手机的蓝牙地址（大写），最近连成功的在最后。「自动连接手机」只拨这些手机。 */
    val knownPhoneList: List<String>
        get() = prefs.getString(KEY_KNOWN_PHONES, null)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    val knownPhones: Set<String> get() = knownPhoneList.toSet()

    /** 最近一次用无线连成功的手机（大写的蓝牙地址）：打开本应用时拨它。 */
    val lastPhone: String? get() = knownPhoneList.lastOrNull()

    /**
     * 上一次无线投屏是手机端主动断开的（手机点了断开/结束投屏）。开着时打开应用只显示二维码、不主动拨手机，
     * 等车主点「开始连接」或扫码；手机蓝牙重新连上车机时仍会拨（那是车主在手机上的操作）。
     */
    var lastEndByPeer: Boolean
        get() = prefs.getBoolean(KEY_LAST_END_BY_PEER, false)
        set(value) = prefs.edit { putBoolean(KEY_LAST_END_BY_PEER, value) }

    /** 记下一台连成功的手机，当作最近那台；最多记 [MAX_KNOWN_PHONES] 台，多了丢掉最早的。返回这台手机是不是第一次记下。 */
    @Synchronized
    fun rememberPhone(address: String): Boolean {
        val key = address.trim().uppercase()
        val known = knownPhoneList
        val next = (known - key + key).takeLast(MAX_KNOWN_PHONES)
        prefs.edit { putString(KEY_KNOWN_PHONES, next.joinToString(",")) }
        return key !in known
    }

    companion object {
        private const val FILE = "openhc-wireless"
        private const val KEY_WIRED_ONLY = "wired_only"
        private const val KEY_WIFI_DIRECT = "wifi_direct"
        private const val KEY_CONNECT_ON_BLUETOOTH = "connect_on_bluetooth"
        private const val KEY_KNOWN_PHONES = "known_phones"
        private const val KEY_LAST_END_BY_PEER = "last_end_by_peer"

        /** 「连成功过的手机」最多记几台。 */
        const val MAX_KNOWN_PHONES = 5

        /**
         * 系统支持「WiFi 直连」这种无线方式：安卓 10（API 29）起——组的真实频率要到 29 才读得回
         * （WifiP2pGroup.getFrequency），老系统建组可以，但读不回频率就给不出连接串，直连腿走不完，
         * 所以低版本上这一方式不开放（设置界面里压暗说明）。建组代码本身按 21 兼容（见 WifiDirectGroup），
         * 真要往前放得先解决频率读回，或按估值填连接串（双边契约改动，见 WifiConnectionInfo）。
         */
        val wifiDirectSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }
}
