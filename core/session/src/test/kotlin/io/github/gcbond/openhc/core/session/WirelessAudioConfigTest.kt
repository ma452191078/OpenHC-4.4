package io.github.gcbond.openhc.core.session

import io.github.gcbond.openhc.core.auth.InMemoryTrustStore
import io.github.gcbond.openhc.core.dmsdp.audio.AudioCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 无线的 WiFi 阶段也能声明扬声器：配置里的声音设置原样带进内嵌有线编排器的配置。测试数据都是合成值。 */
class WirelessAudioConfigTest {

    private fun config(audio: AudioSettings?) = WirelessReceiverConfig(
        identity = Synthetic.IDENTITY,
        bluetoothAddress = Synthetic.BLUETOOTH,
        display = DisplaySettings(1280, 720, 30, dpi = 160, density = 1.0),
        trustStore = InMemoryTrustStore(),
        audio = audio,
    )

    @Test
    fun speakerIsDeclaredInTheWifiPhaseOnlyWhenAsked() {
        val phase = config(AudioSettings(codec = AudioCodec.PCM)).wifiPhaseConfig(WirelessSynthetic.HOTSPOT_IP)
        assertEquals(AudioCodec.PCM, phase.audio?.codec)
        assertEquals(AudioCodec.PCM, phase.dmsdpConfig(WirelessSynthetic.PHONE_WIFI_IP).speaker?.codec)

        val silent = config(null).wifiPhaseConfig(WirelessSynthetic.HOTSPOT_IP)
        assertNull(silent.audio)
        assertNull(silent.dmsdpConfig(WirelessSynthetic.PHONE_WIFI_IP).speaker, "默认不声明，声音留在手机上")
    }
}
