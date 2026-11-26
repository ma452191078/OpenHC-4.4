package io.github.gcbond.openhc.platform.net

import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.common.hexToBytes
import io.github.gcbond.openhc.platform.net.TestFrames.CAR_IP
import io.github.gcbond.openhc.platform.net.TestFrames.PHONE_IP
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChecksumAndIpv4Test {

    /** RFC 1071 第 3 节的数值例子：0001 f203 f4f5 f6f7 的一补数和是 ddf2。 */
    @Test
    fun rfc1071Example() {
        val data = "0001f203f4f5f6f7".hexToBytes()
        assertEquals(0xDDF2, InternetChecksum.fold(InternetChecksum.sum(data, 0, data.size)))
        assertEquals(0x220D, InternetChecksum.compute(data, 0, data.size))
    }

    /** 常见的 IPv4 头校验和示例：192.168.0.1 → 192.168.0.199 的 UDP 包头，校验和 b861。 */
    @Test
    fun ipv4HeaderExample() {
        val header = "450000730000400040110000c0a80001c0a800c7".hexToBytes()
        Ipv4.writeHeaderChecksum(header, 0)
        assertEquals(0xB861, header.u16be(10))
        assertTrue(InternetChecksum.isValid(header, 0, 20))
        header[15] = 0x02
        assertFalse(InternetChecksum.isValid(header, 0, 20))
    }

    @Test
    fun oddLengthPadsLowByteWithZero() {
        val odd = byteArrayOf(0x12, 0x34, 0x56)
        assertEquals(0x1234 + 0x5600, InternetChecksum.fold(InternetChecksum.sum(odd, 0, 3)))
    }

    @Test
    fun foldCarriesRepeatedly() {
        // 0x2FFFE → 0xFFFE + 2 = 0x10000 → 0x0000 + 1：进位要折两轮。
        assertEquals(0x0001, InternetChecksum.fold(0x2FFFEL))
        assertEquals(0xFFFF, InternetChecksum.fold(0xFFFFL))
        val manyOnes = ByteArray(200_000) { 0xFF.toByte() }
        assertEquals(0xFFFF, InternetChecksum.fold(InternetChecksum.sum(manyOnes, 0, manyOnes.size)), "长数据不溢出")
    }

    /**
     * 手算的 UDP 校验和：192.168.66.45:0x1234 → 192.168.66.123:0x5678，数据 "abc"（奇数长度）。
     * 伪首部 c0a8+422d+c0a8+427b+0011+000b，UDP 头 1234+5678+000b，数据 6162+6300，折叠后 3330，取反 cccf。
     */
    @Test
    fun udpChecksumWithPseudoHeader() {
        val packet = TestFrames.udp(PHONE_IP, CAR_IP, 0x1234, 0x5678, "abc".toByteArray())
        assertEquals(0xCCCF, packet.u16be(20 + 6))
        assertEquals(0xCCCF, InternetChecksum.transport(packet, 0))
    }

    @Test
    fun tcpChecksumIsSelfConsistent() {
        val syn = TestFrames.tcpSyn(PHONE_IP, CAR_IP, 40000, 30000)
        // 连同校验和字段一起累加伪首部和 TCP 段，结果应为 0xFFFF。
        var sum = InternetChecksum.sum(syn, 12, 8)
        sum += Ipv4.PROTOCOL_TCP + (syn.size - 20).toLong()
        sum = InternetChecksum.sum(syn, 20, syn.size - 20, sum)
        assertEquals(0xFFFF, InternetChecksum.fold(sum))
        assertFailsWith<IllegalArgumentException> {
            InternetChecksum.transport(TestFrames.ipv4(PHONE_IP, CAR_IP, Ipv4.PROTOCOL_ICMP, ByteArray(8)), 0)
        }
    }

    @Test
    fun checkedTotalLengthTrimsEthernetPadding() {
        val packet = TestFrames.ipv4(PHONE_IP, CAR_IP, Ipv4.PROTOCOL_UDP, ByteArray(10))
        val padded = packet.copyOf(46)
        assertEquals(30, Ipv4.checkedTotalLength(padded, 0, padded.size))
    }

    @Test
    fun checkedTotalLengthRejectsBrokenHeaders() {
        val good = TestFrames.ipv4(PHONE_IP, CAR_IP, Ipv4.PROTOCOL_UDP, ByteArray(10))
        assertFailsWith<MalformedMessageException> { Ipv4.checkedTotalLength(good, 0, 19) }
        val v6 = good.copyOf().also { it[0] = 0x65 }
        assertFailsWith<MalformedMessageException> { Ipv4.checkedTotalLength(v6, 0, v6.size) }
        val shortHeader = good.copyOf().also { it[0] = 0x44 }
        assertFailsWith<MalformedMessageException> { Ipv4.checkedTotalLength(shortHeader, 0, shortHeader.size) }
        val tooLong = good.copyOf().also { it.putU16be(2, 31) }
        assertFailsWith<MalformedMessageException> { Ipv4.checkedTotalLength(tooLong, 0, tooLong.size, verifyChecksum = false) }
        val shorterThanHeader = good.copyOf().also { it.putU16be(2, 19) }
        assertFailsWith<MalformedMessageException> {
            Ipv4.checkedTotalLength(shorterThanHeader, 0, shorterThanHeader.size, verifyChecksum = false)
        }
        val badChecksum = good.copyOf().also { it[8] = 1 }
        assertFailsWith<MalformedMessageException> { Ipv4.checkedTotalLength(badChecksum, 0, badChecksum.size) }
        assertEquals(30, Ipv4.checkedTotalLength(badChecksum, 0, badChecksum.size, verifyChecksum = false))
    }

    @Test
    fun formatsAddresses() {
        assertEquals("192.168.66.123", Ipv4.format(CAR_IP))
        assertEquals("xx:xx:xx:xx:00:01", Ethernet.maskedMac(EthernetEndpoint.DEFAULT_CAR_MAC))
    }
}
