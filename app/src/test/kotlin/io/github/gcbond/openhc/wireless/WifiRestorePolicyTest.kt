package io.github.gcbond.openhc.wireless

import io.github.gcbond.openhc.core.session.WirelessTimeouts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 「退出后恢复车机 WiFi」的判定，以及按 WiFi 方式定下来的几件事。 */
class WifiRestorePolicyTest {

    private val android9 = 28
    private val android10 = 29

    private fun input(
        setting: Boolean = true,
        takenDown: Boolean = true,
        busy: Boolean = false,
        wifiOn: Boolean = false,
        sdk: Int = android10,
        userStopped: Boolean = true,
        last: Boolean = false,
    ) = WifiRestorePolicy.Input(setting, takenDown, busy, wifiOn, sdk, userStopped, last)

    private fun decide(i: WifiRestorePolicy.Input) = WifiRestorePolicy.decide(i)

    @Test
    fun nothingToDoWhenTheSettingIsOffOrWifiWasNotOurs() {
        assertEquals(WifiRestorePolicy.Decision(WifiRestoreAction.NONE, done = true), decide(input(setting = false)))
        // 开热点时车机 WiFi 本来就是关的（用户自己关的）：不替用户打开。
        assertEquals(WifiRestorePolicy.Decision(WifiRestoreAction.NONE, done = true), decide(input(takenDown = false)))
        assertEquals(WifiRestorePolicy.Decision(WifiRestoreAction.NONE, done = true), decide(input(takenDown = false, sdk = android9)))
    }

    @Test
    fun wifiThatCameBackByItselfIsLeftAlone() {
        for (sdk in listOf(android9, android10)) {
            assertEquals(WifiRestorePolicy.Decision(WifiRestoreAction.NONE, done = true), decide(input(wifiOn = true, sdk = sdk)))
        }
    }

    @Test
    fun aHotspotInUseAgainPostponesWithoutForgetting() {
        // 又有连接在用热点：这次不管，记号留着（等那次连接结束再判断）。
        assertEquals(WifiRestorePolicy.Decision(WifiRestoreAction.CHECK_LATER, done = false), decide(input(busy = true)))
        assertEquals(WifiRestorePolicy.Decision(WifiRestoreAction.NONE, done = false), decide(input(busy = true, last = true)))
        assertEquals(WifiRestorePolicy.Decision(WifiRestoreAction.NONE, done = false), decide(input(busy = true, sdk = android9, last = true)))
    }

    @Test
    fun oldSystemsTurnWifiBackOnThemselves() {
        // 用户点了停止：第一次复查还关着就打开；最后一次复查前不算了结（被顶回去还会再开）。
        assertEquals(WifiRestorePolicy.Decision(WifiRestoreAction.ENABLE_WIFI, done = false), decide(input(sdk = android9)))
        assertEquals(WifiRestorePolicy.Decision(WifiRestoreAction.ENABLE_WIFI, done = true), decide(input(sdk = android9, last = true)))
        // 连接自己结束的：等到最后一次复查还没回来再打开（自动回连可能马上开始下一次，不来回开关 WiFi）。
        assertEquals(WifiRestorePolicy.Decision(WifiRestoreAction.CHECK_LATER, done = false), decide(input(sdk = android9, userStopped = false)))
        assertEquals(
            WifiRestorePolicy.Decision(WifiRestoreAction.ENABLE_WIFI, done = true),
            decide(input(sdk = android9, userStopped = false, last = true)),
        )
    }

    @Test
    fun newSystemsAskTheUserOnlyAfterTheLastCheck() {
        // 安卓 10 起应用打不开 WiFi：给系统时间自己恢复，到最后一次复查还关着才提示。
        for (userStopped in listOf(true, false)) {
            assertEquals(
                WifiRestorePolicy.Decision(WifiRestoreAction.CHECK_LATER, done = false),
                decide(input(userStopped = userStopped)),
            )
            assertEquals(
                WifiRestorePolicy.Decision(WifiRestoreAction.ASK_USER, done = true),
                decide(input(userStopped = userStopped, last = true)),
            )
        }
        assertEquals(WifiRestorePolicy.Decision(WifiRestoreAction.ASK_USER, done = true), decide(input(sdk = 36, last = true)))
    }

    @Test
    fun checksSpreadOverSeveralSeconds() {
        val delays = WifiRestorePolicy.CHECK_DELAYS_MILLIS
        assertEquals(3, delays.size)
        assertEquals(delays.sorted(), delays.toList())
        assertEquals(9_000, delays.last())
    }

    @Test
    fun hotspotSupportedFromKitKat() {
        assertEquals(true, WirelessNetworkPlan.hotspotSupported(26))
        assertEquals(true, WirelessNetworkPlan.hotspotSupported(36))
        // 安卓 4.4（API 19）起可开本地热点：19～25 靠反射腿、26+ 走官方接口；更早的系统用不了无线连接。
        assertEquals(true, WirelessNetworkPlan.hotspotSupported(25))
        assertEquals(true, WirelessNetworkPlan.hotspotSupported(23))
        assertEquals(true, WirelessNetworkPlan.hotspotSupported(21))
        assertEquals(true, WirelessNetworkPlan.hotspotSupported(20))
        assertEquals(true, WirelessNetworkPlan.hotspotSupported(19))
        assertEquals(false, WirelessNetworkPlan.hotspotSupported(18))
    }

    @Test
    fun hotspotGetsMoreTimeToStart() {
        val hotspot = WirelessNetworkPlan.timeouts()
        val defaults = WirelessTimeouts()
        assertEquals(WirelessNetworkPlan.HOTSPOT_NETWORK_START_MILLIS, hotspot.networkStartMillis)
        assertTrue(hotspot.networkStartMillis > defaults.networkStartMillis)
        // 其余时限照会话的默认值。
        assertEquals(defaults.phoneJoinMillis, hotspot.phoneJoinMillis)
        assertEquals(defaults.authMillis, hotspot.authMillis)
        assertEquals(defaults.authChannelAcceptMillis, hotspot.authChannelAcceptMillis)
    }
}
