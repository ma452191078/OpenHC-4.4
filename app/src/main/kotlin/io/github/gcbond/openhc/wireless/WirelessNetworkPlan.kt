package io.github.gcbond.openhc.wireless

import io.github.gcbond.openhc.core.session.WirelessTimeouts

/**
 * 无线连接里 WiFi 那一段：车机开本地热点，手机像连普通 WiFi 一样连上来。纯逻辑，不引用安卓类，由 JVM 单元测试覆盖；
 * 接线见 WirelessController。
 *
 * - 系统要求：安卓 5.0（API 21）起可开本地热点——26+ 走系统官方接口，21～25 靠反射调隐藏的热点接口；更早的车机
 *   用不了无线连接，只能插线。
 * - 开始前不要求车机 WiFi 开着：开热点时单射频车机自己连着的 WiFi 本来就会断。
 * - 手机认证通过以后才开热点：等扫码、等回连、没通过认证的连接都不动车机的 WiFi。
 * - 开热点的时限 15 秒：含两次开热点之间至少 3.5 秒的间隔、等热点网卡出现地址。
 */
object WirelessNetworkPlan {
    /** 官方本地热点接口（startLocalOnlyHotspot）从安卓 8.0（API 26）起才有；26+ 走官方腿时以它为地板。 */
    const val HOTSPOT_MIN_SDK = 26

    /** 反射腿的地板：4.4～25 没有官方接口，靠反射调隐藏的热点接口开本地热点（LocalHotspot 的反射分支）。 */
    const val LEGACY_HOTSPOT_MIN_SDK = 19

    /** 开热点的时限：间隔最多 3.5 秒 + 系统开热点（实测一两秒）+ 等网卡地址最多 3 秒，再留余量。 */
    const val HOTSPOT_NETWORK_START_MILLIS = 15_000L

    /** 这个系统版本能不能开本地热点；不能就用不了无线连接。安卓 5.0 起就算支持（21～25 走反射、26+ 走官方接口）。 */
    fun hotspotSupported(sdkInt: Int): Boolean = sdkInt >= LEGACY_HOTSPOT_MIN_SDK

    /** 各步时限：开热点的时限放宽到 [HOTSPOT_NETWORK_START_MILLIS]，其余用会话的默认值。 */
    fun timeouts(): WirelessTimeouts = WirelessTimeouts(networkStartMillis = HOTSPOT_NETWORK_START_MILLIS)
}
