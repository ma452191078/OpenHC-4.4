package io.github.gcbond.openhc.platform.usb

import io.github.gcbond.openhc.core.common.ByteWriter
import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.common.hexToBytes
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CdcNcmTest {

    /** 合成一份 GET_NTB_PARAMETERS 应答，取值与实测手机上报的一致。 */
    private fun parametersBytes(): ByteArray = ByteWriter(28)
        .u16Le(28) // wLength
        .u16Le(0x0003) // NTB16 + NTB32
        .u32Le(16384) // dwNtbInMaxSize
        .u16Le(4).u16Le(0).u16Le(4).u16Le(0) // 入向对齐与保留字段
        .u32Le(3568) // dwNtbOutMaxSize
        .u16Le(4).u16Le(0).u16Le(4) // 出向对齐
        .u16Le(0) // wNtbOutMaxDatagrams
        .toByteArray()

    @Test
    fun parsesNtbParameters() {
        val p = NtbParameters.parse(parametersBytes())
        assertEquals(3, p.formatsSupported)
        assertTrue(p.supportsNtb16)
        assertEquals(16384, p.ntbInMaxSize)
        assertEquals(3568, p.ntbOutMaxSize)
        assertEquals(4, p.outDivisor)
        assertEquals(0, p.outPayloadRemainder)
        assertEquals(4, p.outAlignment)
        assertEquals(0, p.outMaxDatagrams)
        assertFailsWith<MalformedMessageException> { NtbParameters.parse(parametersBytes(), 27) }
        assertFalse(NtbParameters.parse(parametersBytes().also { it[2] = 2 }).supportsNtb16)
    }

    @Test
    fun receivePlanRespectsOldSystemTransferLimit() {
        // 实测手机的入向上限 16384，正好等于安卓 9 以前的单次上限：不用压。
        assertEquals(CdcNcm.ReceivePlan(16384, null), CdcNcm.receivePlan(16384, 23))
        assertEquals(CdcNcm.ReceivePlan(16384, null), CdcNcm.receivePlan(16384, 34))
        // 老系统上手机上限更大：压到 16384。
        assertEquals(CdcNcm.ReceivePlan(16384, 16384), CdcNcm.receivePlan(32768, 27))
        assertEquals(CdcNcm.ReceivePlan(32768, null), CdcNcm.receivePlan(32768, 28))
        // NTB16 一块最多 65535 字节，缓冲不必更大。
        assertEquals(CdcNcm.ReceivePlan(65536, null), CdcNcm.receivePlan(1_000_000, 30))
        // 不合理的小值按规范下限处理。
        assertEquals(CdcNcm.ReceivePlan(2048, null), CdcNcm.receivePlan(100, 30))
        // 读不到参数：用系统上限，不发设置请求。
        assertEquals(CdcNcm.ReceivePlan(16384, null), CdcNcm.receivePlan(null, 23))
        assertEquals(CdcNcm.ReceivePlan(65536, null), CdcNcm.receivePlan(null, 33))
    }

    @Test
    fun encodesNtbInputSize() {
        assertContentEquals("00400000".hexToBytes(), CdcNcm.encodeNtbInputSize(16384))
        assertFailsWith<IllegalArgumentException> { CdcNcm.encodeNtbInputSize(1024) }
    }

    @Test
    fun carInfoControlRequest() {
        val r = UsbControlRequest.CAR_INFO
        assertEquals(0x40, r.requestType)
        assertEquals(0x34, r.request)
        assertEquals(0, r.value)
        assertEquals(0xA0, r.index)
        assertTrue(r.isHostToDevice)
        assertFalse(UsbControlRequest(0xC0, 1, 0, 0).isHostToDevice)
        assertFailsWith<IllegalArgumentException> { UsbControlRequest(0x100, 0, 0, 0) }
        assertFailsWith<IllegalArgumentException> { UsbControlRequest(0x40, 0, 0x10000, 0) }
        assertFailsWith<IllegalArgumentException> { UsbControlRequest(0x40, 0, 0, 0, timeoutMs = 0) }
    }

    @Test
    fun sessionConfigValidatesAddressing() {
        val car = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 66, 123)) as Inet4Address
        val config = WiredSessionConfig(carInfo = ByteArray(91), carAddress = car)
        assertEquals("192.168.66.0", config.networkAddress.hostAddress)
        assertEquals(24, config.prefixLength)
        assertContentEquals(io.github.gcbond.openhc.platform.net.EthernetEndpoint.DEFAULT_CAR_MAC, config.carMac)

        val wide = WiredSessionConfig(carInfo = ByteArray(1), carAddress = car, prefixLength = 16)
        assertEquals("192.168.0.0", wide.networkAddress.hostAddress)

        val network = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 66, 0)) as Inet4Address
        val broadcast = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 66, 255.toByte())) as Inet4Address
        assertFailsWith<IllegalArgumentException> { WiredSessionConfig(ByteArray(91), network) }
        assertFailsWith<IllegalArgumentException> { WiredSessionConfig(ByteArray(91), broadcast) }
        assertFailsWith<IllegalArgumentException> { WiredSessionConfig(ByteArray(0), car) }
        assertFailsWith<IllegalArgumentException> { WiredSessionConfig(ByteArray(4097), car) }
        assertFailsWith<IllegalArgumentException> { WiredSessionConfig(ByteArray(91), car, prefixLength = 31) }
        assertFailsWith<IllegalArgumentException> {
            WiredSessionConfig(ByteArray(91), car, carInfoRequest = UsbControlRequest(0xC0, 0x34, 0, 0xA0))
        }

        val original = byteArrayOf(1, 2, 3)
        val copied = WiredSessionConfig(original, car)
        original[0] = 9
        assertEquals(1, copied.carInfo[0].toInt(), "carInfo 保存的是副本")
    }

    @Test
    fun timeoutsMustBePositive() {
        assertFailsWith<IllegalArgumentException> { WiredTimeouts(phoneSwitchMs = 0) }
        assertFailsWith<IllegalArgumentException> { WiredTimeouts(phoneWaitMs = -1) }
        WiredTimeouts(phoneWaitMs = 0, permissionGraceMs = 0)
    }
}
