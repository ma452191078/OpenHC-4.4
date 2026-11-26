package io.github.gcbond.openhc.btprobe

import io.github.gcbond.openhc.btprobe.ProbeText.AddressKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 地址一律用合成值（递增序列、AA:BB:…），不出现真实设备地址。
class ProbeTextTest {

    @Test
    fun parsesChannelFromToString() {
        assertEquals(-1, ProbeText.channelFromToString("ServerSocket: Type: TYPE_RFCOMM Channel: -1"))
        assertEquals(12, ProbeText.channelFromToString("ServerSocket: Type: TYPE_RFCOMM Channel: 12"))
        assertNull(ProbeText.channelFromToString("android.bluetooth.BluetoothServerSocket@1a2b3c"))
        assertNull(ProbeText.channelFromToString(null))
    }

    @Test
    fun validChannelsAreOneToThirty() {
        assertFalse(ProbeText.isValidChannel(null))
        assertFalse(ProbeText.isValidChannel(-1))
        assertFalse(ProbeText.isValidChannel(0))
        assertTrue(ProbeText.isValidChannel(1))
        assertTrue(ProbeText.isValidChannel(30))
        assertFalse(ProbeText.isValidChannel(31))
    }

    @Test
    fun normalizesCommonAddressForms() {
        assertEquals("00:11:22:33:44:55", ProbeText.normalizeAddress("00:11:22:33:44:55"))
        assertEquals("AA:BB:CC:DD:EE:FF", ProbeText.normalizeAddress(" aa-bb-cc-dd-ee-ff\n"))
        assertEquals("00:11:22:33:44:55", ProbeText.normalizeAddress("001122334455"))
        assertNull(ProbeText.normalizeAddress("00:11-22:33:44:55"))
        assertNull(ProbeText.normalizeAddress("00:11:22:33:44"))
        assertNull(ProbeText.normalizeAddress("not an address"))
        assertNull(ProbeText.normalizeAddress(null))
    }

    @Test
    fun classifiesAddresses() {
        assertEquals(AddressKind.MISSING, ProbeText.classifyAddress(null))
        assertEquals(AddressKind.MISSING, ProbeText.classifyAddress("  "))
        assertEquals(AddressKind.MALFORMED, ProbeText.classifyAddress("/persist/bluetooth/addr"))
        assertEquals(AddressKind.PLACEHOLDER, ProbeText.classifyAddress("02:00:00:00:00:00"))
        assertEquals(AddressKind.ALL_ZERO, ProbeText.classifyAddress("000000000000"))
        assertEquals(AddressKind.PLAUSIBLE, ProbeText.classifyAddress("AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun masksMiddleOfRealLookingAddresses() {
        assertEquals("AA:**:**:**:EE:FF", ProbeText.maskAddress("AA:BB:CC:DD:EE:FF"))
        assertEquals("00-**-**-**-44-55", ProbeText.maskAddress("00-11-22-33-44-55"))
        assertEquals("00******4455", ProbeText.maskAddress("001122334455"))
        // 占位值、全零和非地址留着原样，才看得出是哪种失败。
        assertEquals("02:00:00:00:00:00", ProbeText.maskAddress("02:00:00:00:00:00"))
        assertEquals("00:00:00:00:00:00", ProbeText.maskAddress("00:00:00:00:00:00"))
        assertEquals("（空）", ProbeText.maskAddress("（空）"))
    }

    @Test
    fun masksAddressesInsideFreeText() {
        assertEquals(
            "对端 AA:**:**:**:EE:FF，占位 02:00:00:00:00:00",
            ProbeText.maskAddresses("对端 AA:BB:CC:DD:EE:FF，占位 02:00:00:00:00:00"),
        )
        // 七段的串不是地址，不能截一段去打码；时间也不能被误伤。
        assertEquals("00:11:22:33:44:55:66", ProbeText.maskAddresses("00:11:22:33:44:55:66"))
        assertEquals("11-18 17:00:00.123", ProbeText.maskAddresses("11-18 17:00:00.123"))
    }

    @Test
    fun reportGroupsBySectionAndMasks() {
        val items = listOf(
            ProbeItem("本机蓝牙地址", "读法甲", Status.OK, "AA:BB:CC:DD:EE:FF", "AA:**:**:**:EE:FF"),
            ProbeItem("本机蓝牙地址", "读法乙", Status.FAIL, "02:00:00:00:00:00"),
            ProbeItem("监听 A", "结论", Status.OK, "通道号 5\n第二行"),
        )
        val text = ProbeText.formatReport(listOf("标题"), items)
        val expected = """
            标题

            == 本机蓝牙地址 ==
            [成功] 读法甲：AA:**:**:**:EE:FF
            [失败] 读法乙：02:00:00:00:00:00

            == 监听 A ==
            [成功] 结论：通道号 5
                第二行

        """.trimIndent()
        assertEquals(expected, text)
    }

    @Test
    fun exportLineMasksEvenWhenExportValueForgotTo() {
        val item = ProbeItem("接受连接", "有设备连入", Status.OK, "对端 00:11:22:33:44:55")
        assertEquals("[成功] 有设备连入：对端 00:**:**:**:44:55", ProbeText.exportLine(item))
    }
}
