package io.github.gcbond.openhc.core.session

import io.github.gcbond.openhc.core.auth.InMemoryTrustStore
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 媒体键开关从会话配置原样带进投屏层配置（有线、无线的 WiFi 阶段都一样），默认关。测试数据都是合成值。 */
class MediaKeyConfigTest {

    private fun wireless(mediaKeyInput: Boolean) = WirelessReceiverConfig(
        identity = Synthetic.IDENTITY,
        bluetoothAddress = Synthetic.BLUETOOTH,
        display = DisplaySettings(1280, 720, 30, dpi = 160, density = 1.0),
        trustStore = InMemoryTrustStore(),
        touchInput = true,
        mediaKeyInput = mediaKeyInput,
    )

    @Test
    fun mediaKeysReachTheProjectionLayer() {
        val on = wireless(true).wifiPhaseConfig(WirelessSynthetic.HOTSPOT_IP)
        assertTrue(on.mediaKeyInput)
        assertTrue(on.hisightConfig().mediaKeyInput)
        assertFalse(on.hisightConfig().keyInput, "媒体键不打开严格的按键能力")

        val off = wireless(false).wifiPhaseConfig(WirelessSynthetic.HOTSPOT_IP)
        assertFalse(off.hisightConfig().mediaKeyInput)
    }
}
