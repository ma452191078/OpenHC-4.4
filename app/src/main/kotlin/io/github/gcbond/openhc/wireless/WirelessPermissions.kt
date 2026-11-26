package io.github.gcbond.openhc.wireless

/**
 * 无线连接要的运行时权限，按系统版本定。纯逻辑，由 JVM 单元测试覆盖。
 *
 * | 系统 | 必须 | 用途 |
 * |---|---|---|
 * | 安卓 12（31）起 | 附近的设备（BLUETOOTH_CONNECT） | 开 RFCOMM 监听、读对端地址 |
 * | 安卓 13（33）起 | 附近的 WiFi 设备（NEARBY_WIFI_DEVICES，清单里声明 neverForLocation） | 开本地热点 |
 * | 安卓 6～12L | 精确位置（ACCESS_FINE_LOCATION） | 同上（这些版本开本地热点要位置权限，安卓 8 以下没有本地热点、用不了无线） |
 *
 * 安卓 12、12L 上精确位置要和大致位置一起申请，系统框里才能选「精确」；只给大致位置不够用。
 * 通知权限（安卓 13 起）只是让前台服务的通知显示出来，拒绝了照样连接，不算必须。
 *
 * 「无线自动回连」开着时，安卓 12 起还要「附近的设备」里的广播权限（BLUETOOTH_ADVERTISE，见 [advertise]）。它不算必须：
 * 用户点开始时一起申请，拒绝了照样能扫码连接，只是不发广播、不能自动回连。更早的版本发广播用清单里的普通蓝牙权限。
 */
object WirelessPermissions {
    // 直接写权限名：新版本才有的常量引用在老版本上会被 lint 当成新接口提示，值本身只是字符串。
    const val BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"
    const val BLUETOOTH_ADVERTISE = "android.permission.BLUETOOTH_ADVERTISE"
    const val NEARBY_WIFI_DEVICES = "android.permission.NEARBY_WIFI_DEVICES"
    const val FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
    const val COARSE_LOCATION = "android.permission.ACCESS_COARSE_LOCATION"
    const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

    private const val S = 31
    private const val TIRAMISU = 33

    /** 必须有的运行时权限。 */
    fun required(sdkInt: Int): List<String> {
        val list = ArrayList<String>(2)
        if (sdkInt >= S) list += BLUETOOTH_CONNECT
        list += if (sdkInt >= TIRAMISU) NEARBY_WIFI_DEVICES else FINE_LOCATION
        return list
    }

    /** 还缺的必须权限。 */
    fun missing(sdkInt: Int, granted: (String) -> Boolean): List<String> = required(sdkInt).filterNot(granted)

    /** 发 BLE 广播要的运行时权限（安卓 12 起才有，更早为空）。 */
    fun advertise(sdkInt: Int): List<String> = if (sdkInt >= S) listOf(BLUETOOTH_ADVERTISE) else emptyList()

    /**
     * 一次系统框里要申请的：必须的、安卓 12/12L 上配套的大致位置，以及（还没问过时）通知权限；
     * [advertise] 为 true（开着无线自动回连）时再加上广播权限。
     */
    fun toRequest(sdkInt: Int, askNotifications: Boolean, advertise: Boolean = false): List<String> {
        val list = ArrayList(required(sdkInt))
        if (sdkInt in S until TIRAMISU) list += COARSE_LOCATION
        if (advertise) list += advertise(sdkInt)
        if (askNotifications && sdkInt >= TIRAMISU) list += POST_NOTIFICATIONS
        return list
    }

    /**
     * 申请之后的结论：都有了返回 null；还缺就返回要提示的问题。[canAskAgain] 是系统的「是否该解释原因」：
     * 刚被拒绝之后它还是 false，说明用户选了不再询问（或被策略禁止），只能去系统设置里开。
     */
    fun afterRequest(
        sdkInt: Int,
        granted: (String) -> Boolean,
        canAskAgain: (String) -> Boolean,
    ): WirelessNotice.PermissionsDenied? {
        val missing = missing(sdkInt, granted)
        if (missing.isEmpty()) return null
        return WirelessNotice.PermissionsDenied(
            missing = missing,
            permanently = missing.any { !canAskAgain(it) },
            approximateOnly = sdkInt in S until TIRAMISU && FINE_LOCATION in missing && granted(COARSE_LOCATION),
        )
    }
}
