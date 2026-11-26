package io.github.gcbond.openhc.core.nearby

import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.common.identity.HostIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 信道与频率、手机能不能用某个频率、连接串、二维码。全部用合成值。 */
class WifiSetupTest {

    private fun phone(
        supports2g: Boolean? = true,
        supports5g: Boolean? = true,
        channels: Collection<Int>? = WifiChannels.CHANNELS_5G,
    ) = WifiCapability(
        canBeGroupOwner = true,
        canBeGroupClient = true,
        supports2g = supports2g,
        supports5g = supports5g,
        channelBitmap = channels?.let { WifiChannels.bitmapOf(it) },
        connectedFrequencyMhz = 0,
    )

    @Test
    fun channelFrequencyConversion() {
        val pairs = mapOf(1 to 2412, 6 to 2437, 13 to 2472, 14 to 2484, 36 to 5180, 48 to 5240, 149 to 5745, 165 to 5825)
        for ((channel, frequency) in pairs) {
            assertEquals(frequency, WifiChannels.frequencyOf(channel), "信道 $channel")
            assertEquals(channel, WifiChannels.channelOf(frequency), "频率 $frequency")
        }
        assertEquals(null, WifiChannels.channelOf(5746))
        assertEquals(null, WifiChannels.channelOf(2400))
        assertEquals(null, WifiChannels.channelOf(5955))
        assertFailsWith<IllegalArgumentException> { WifiChannels.frequencyOf(0) }
        assertFailsWith<IllegalArgumentException> { WifiChannels.frequencyOf(15) }
        assertTrue(WifiChannels.is2g(2437) && !WifiChannels.is5g(2437))
        assertTrue(WifiChannels.is5g(5745) && !WifiChannels.is2g(5745))
        assertEquals(setOf(36, 149, 165), WifiChannels.channelsOf(WifiChannels.bitmapOf(listOf(165, 36, 149))))
        assertFailsWith<IllegalArgumentException> { WifiChannels.bitmapOf(listOf(52)) }
    }

    @Test
    fun phoneCanUse() {
        assertTrue(phone().canUse(5745))
        assertFalse(phone(channels = listOf(36)).canUse(5745))
        assertTrue(phone(channels = null).canUse(5745))
        assertFalse(phone(supports5g = null).canUse(5745))
        assertTrue(phone(supports2g = null).canUse(2437))
        assertFalse(phone(supports2g = false).canUse(2437))
        assertFalse(phone().canUse(5955))
        assertFalse(WifiCapability().canUse(5745), "什么都没报：5 GHz 不算支持")
        assertTrue(WifiCapability().canUse(2437), "什么都没报：2.4 GHz 照样能用")
    }

    private fun hotspot(ssid: String = "OpenHC-1234", passphrase: String = "12345678") = WifiConnectionInfo(
        WifiMode.HOTSPOT, "192.168.88.2", ssid, "0A:1B:2C:3D:4E:5F", passphrase, 5745, 41000, "192.168.88.1",
    )

    private fun direct() = WifiConnectionInfo(
        WifiMode.DIRECT, "192.168.49.2", "DIRECT-HC-ABCD", "02:11:22:33:44:55", "abcdefgh2345", 5745, 41000, "192.168.49.1",
    )

    @Test
    fun connectionInfoFormats() {
        assertEquals(
            "192.168.88.2##OpenHC-1234\n0a:1b:2c:3d:4e:5f\n12345678\n5745\n1##version:3##41000##149##hicarApIp:192.168.88.1",
            hotspot().encode(),
        )
        assertEquals(
            "192.168.49.2##DIRECT-HC-ABCD\n02:11:22:33:44:55\nabcdefgh2345\n5745##version:3##41000##hicarApIp:192.168.49.1",
            direct().encode(),
        )
        for (info in listOf(hotspot(), direct())) {
            val back = WifiConnectionInfo.parse(info.encode())
            assertEquals(info.encode(), back.encode())
            assertEquals(info.mode, back.mode)
            assertEquals(info.port, back.port)
        }
        // toString 不含网络名和密码。
        assertFalse(hotspot().toString().contains("OpenHC-1234") || hotspot().toString().contains("12345678"))
    }

    @Test
    fun hotspotFormatCarriesTheChannelNumberOfItsFrequency() {
        // 端口后那一段是热点所在信道：2.4 GHz (频率 − 2407) / 5（2484 是 14），5 GHz (频率 − 5000) / 5。
        val expected = mapOf(2412 to 1, 2437 to 6, 2462 to 11, 2472 to 13, 2484 to 14, 5180 to 36, 5745 to 149, 5825 to 165)
        for ((mhz, channel) in expected) {
            val info = WifiConnectionInfo(WifiMode.HOTSPOT, "192.168.43.2", "OpenHC-Test", "02:12:34:56:78:9A", "abcd1234", mhz, 40100, "192.168.43.1")
            assertEquals(channel, info.hotspotChannel, "$mhz MHz")
            assertTrue(info.encode().endsWith("\n$mhz\n1##version:3##40100##$channel##hicarApIp:192.168.43.1"), info.encode())
            assertEquals(channel, WifiConnectionInfo.parse(info.encode()).hotspotChannel)
        }
        // 直连格式没有这一段。
        assertNull(direct().hotspotChannel)
        assertFalse(direct().encode().contains("##149##"))
    }

    @Test
    fun networkNameIsUnquotedAndBackslashesDoubled() {
        val info = hotspot(ssid = "\"Car\\Net\"")
        assertEquals("Car\\Net", info.ssid)
        assertTrue(info.encode().startsWith("192.168.88.2##Car\\\\Net\n"))
        assertEquals("Car\\Net", WifiConnectionInfo.parse(info.encode()).ssid)
        assertFailsWith<MalformedMessageException> {
            WifiConnectionInfo.parse("192.168.49.2##A\\B\n02:11:22:33:44:55\nabcdefgh2345\n5745##version:3##41000##hicarApIp:192.168.49.1")
        }
    }

    @Test
    fun connectionInfoRejectsBadFields() {
        val cases = listOf<() -> Unit>(
            { hotspot(ssid = "a##b") },
            { hotspot(ssid = "a\nb") },
            { hotspot(ssid = "\"\"") },
            { hotspot(passphrase = "short") },
            { hotspot(passphrase = "12345678##") },
            { hotspot(passphrase = "密码密码密码密码") },
            { WifiConnectionInfo(WifiMode.DIRECT, "192.168.49.2", "n", "02:11:22:33:44", "abcdefgh", 5745, 41000, "192.168.49.1") },
            { WifiConnectionInfo(WifiMode.DIRECT, "192.168.49.2", "n", "02:11:22:33:44:55", "abcdefgh", 5746, 41000, "192.168.49.1") },
            { WifiConnectionInfo(WifiMode.DIRECT, "192.168.49.2", "n", "02:11:22:33:44:55", "abcdefgh", 5745, 0, "192.168.49.1") },
            { WifiConnectionInfo(WifiMode.DIRECT, "192.168.49.1", "n", "02:11:22:33:44:55", "abcdefgh", 5745, 1, "192.168.49.1") },
            { WifiConnectionInfo(WifiMode.DIRECT, "192.168.49.02", "n", "02:11:22:33:44:55", "abcdefgh", 5745, 1, "192.168.49.1") },
        )
        for ((i, case) in cases.withIndex()) assertFailsWith<IllegalArgumentException>("第 $i 种") { case() }
        // 64 位十六进制的预共享密钥也行。
        hotspot(passphrase = "0123456789abcdef".repeat(4))
    }

    @Test
    fun connectionInfoParseRejectsWrongShape() {
        val good = direct().encode()
        val cases = listOf(
            good.replace("##version:3", ""),
            good.replace("version:3", "version:2"),
            good.replace("5745##", "5745\n1##"),
            hotspot().encode().replace("\n1##", "##"),
            hotspot().encode().replace("##149##", "##148##"),
            hotspot().encode().replace("##149##", "##2##"),
            hotspot().encode().replace("##149##", "##1x9##"),
            hotspot().encode().replace("##149##", "####"),
            good.replace("hicarApIp:", "carIp:"),
            good.replace("41000", "4x000"),
            good.replace("41000", "70000"),
        )
        for ((i, text) in cases.withIndex()) assertFailsWith<MalformedMessageException>("第 $i 种") { WifiConnectionInfo.parse(text) }
    }

    @Test
    fun phoneAddressIsPickedInTheSameSubnet() {
        assertEquals("192.168.49.2", WifiConnectionInfo.pickPhoneAddress("192.168.49.1"))
        assertEquals("192.168.88.3", WifiConnectionInfo.pickPhoneAddress("192.168.88.2"))
        assertEquals("10.20.30.4", WifiConnectionInfo.pickPhoneAddress("10.20.30.1", listOf("10.20.30.2", "10.20.30.3")))
        val all = (2..100).map { "10.0.0.$it" }
        assertFailsWith<IllegalStateException> { WifiConnectionInfo.pickPhoneAddress("10.0.0.1", all) }
        assertFailsWith<IllegalArgumentException> { WifiConnectionInfo.pickPhoneAddress("10.0.0") }
    }

    private val host = HostIdentity(modelId = "ABCDEF", subModelId = "01", brand = "OpenHC", deviceName = "OpenHC Car")

    @Test
    fun qrCodeFormat() {
        assertEquals(
            "hicar://car/connect?devicename=OpenHC_Car&mac=0A:1B:2C:3D:4E:5F&moduleid=ABCDEF&submoduleid=01&pin=012345&ability=6",
            PairingQrCode.build(host, "0a:1b:2c:3d:4e:5f", "012345", supportsUsb = true),
        )
        assertEquals(
            "hicar://car/connect?devicename=Car&mac=0A:1B:2C:3D:4E:5F&moduleid=ABCDEF&submoduleid=01&pin=999999&ability=4",
            PairingQrCode.build(host, "0A-1B-2C-3D-4E-5F", "999999", supportsUsb = false, deviceName = "Car"),
        )
        assertEquals("A_B_C_D_E_F_G_H", PairingQrCode.displayName(" A&B=C#D%E+F?G\tH "))
        assertEquals("测试车机", PairingQrCode.displayName("测试车机"))
    }

    @Test
    fun qrCodeNeedsRealAddressAndPin() {
        assertFailsWith<IllegalArgumentException> { PairingQrCode.build(host, "02:00:00:00:00:00", "012345", true) }
        assertFailsWith<IllegalArgumentException> { PairingQrCode.build(host, "0A:1B:2C:3D:4E", "012345", true) }
        assertFailsWith<IllegalArgumentException> { PairingQrCode.build(host, "0A:1B:2C:3D:4E:5F", "12345", true) }
        assertFailsWith<IllegalArgumentException> { PairingQrCode.build(host, "0A:1B:2C:3D:4E:5F", "12345a", true) }
        assertFailsWith<IllegalArgumentException> { PairingQrCode.displayName("   ") }
    }
}
