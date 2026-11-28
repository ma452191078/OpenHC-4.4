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
     * 只用数据线连接（默认关）：开着时完全不做无线——不出二维码、不等手机回连、不理会手机蓝牙连上，
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
     * 配对成功过的设备，按配对（最近一次连成功）时间倒序，最近那台在最前，即 [lastPhone] 那台。
     * 「设置 → 设备」用它列出来给车主看、删。
     */
    val pairedDevices: List<PairedDevice>
        get() = knownPhoneList.asReversed().map { address ->
            PairedDevice(
                address = address,
                name = prefs.getString(nameKey(address), null)?.takeIf { it.isNotBlank() },
                pairedAtMillis = prefs.getLong(timeKey(address), 0L),
            )
        }

    /**
     * 记下一台连成功的手机，当作最近那台；最多记 [MAX_KNOWN_PHONES] 台，多了丢掉最早的。
     * [name] 是这台手机读得到的蓝牙名称（可为空），[nowMillis] 是这次配对（连成功）的时间。
     * 返回这台手机是不是第一次记下。
     */
    @Synchronized
    fun rememberPhone(
        address: String,
        name: String? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        val key = address.trim().uppercase()
        val known = knownPhoneList
        val next = (known.filterNot { it.equals(key, ignoreCase = true) } + key).takeLast(MAX_KNOWN_PHONES)
        val dropped = known.filter { kept -> next.none { it.equals(kept, ignoreCase = true) } }
        prefs.edit {
            putString(KEY_KNOWN_PHONES, next.joinToString(","))
            putLong(timeKey(key), nowMillis)
            if (!name.isNullOrBlank()) putString(nameKey(key), name.trim())
            dropped.forEach {
                remove(nameKey(it))
                remove(timeKey(it))
            }
        }
        return key !in known.map { it.uppercase() }
    }

    /**
     * 删除一台配对过的手机（设置里的「设备管理」）：不再自动连它，需要时重新扫码配对。
     * 返回是否真的删掉了一台。
     */
    @Synchronized
    fun forgetPhone(address: String): Boolean {
        val key = address.trim().uppercase()
        val known = knownPhoneList
        val next = known.filterNot { it.equals(key, ignoreCase = true) }
        if (next.size == known.size) return false
        prefs.edit {
            putString(KEY_KNOWN_PHONES, next.joinToString(","))
            remove(nameKey(key))
            remove(timeKey(key))
        }
        return true
    }

    /** 配对成功过的一台设备。 */
    data class PairedDevice(
        /** 大写的蓝牙地址，删除时用。 */
        val address: String,
        /** 配对时读到的蓝牙名称；老记录或读不到时为 null。 */
        val name: String?,
        /** 最近一次连成功的时间（毫秒）；老记录没有时为 0。 */
        val pairedAtMillis: Long,
    )

    companion object {
        private const val FILE = "openhc-wireless"
        private const val KEY_WIRED_ONLY = "wired_only"
        private const val KEY_WIFI_DIRECT = "wifi_direct"
        private const val KEY_CONNECT_ON_BLUETOOTH = "connect_on_bluetooth"
        private const val KEY_KNOWN_PHONES = "known_phones"

        /** 每台设备另存的名称、配对时间（按蓝牙地址做键，避免把名字、时间挤进地址列表里要转义）。 */
        private const val KEY_DEVICE_NAME_PREFIX = "device_name_"
        private const val KEY_DEVICE_PAIRED_AT_PREFIX = "device_paired_at_"

        private fun nameKey(address: String): String = KEY_DEVICE_NAME_PREFIX + address.trim().uppercase()

        private fun timeKey(address: String): String = KEY_DEVICE_PAIRED_AT_PREFIX + address.trim().uppercase()

        /** 「连成功过的手机」最多记几台。 */
        const val MAX_KNOWN_PHONES = 20

        /**
         * 系统支持「WiFi 直连」这种无线方式：安卓 10（API 29）起——组的真实频率要到 29 才读得回
         * （WifiP2pGroup.getFrequency），老系统建组可以，但读不回频率就给不出连接串，直连腿走不完，
         * 所以低版本上这一方式不开放（设置界面里压暗说明）。建组代码本身按 21 兼容（见 WifiDirectGroup），
         * 真要往前放得先解决频率读回，或按估值填连接串（双边契约改动，见 WifiConnectionInfo）。
         */
        val wifiDirectSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }
}
