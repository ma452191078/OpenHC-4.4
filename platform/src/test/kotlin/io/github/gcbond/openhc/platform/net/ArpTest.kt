package io.github.gcbond.openhc.platform.net

import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.platform.net.TestFrames.CAR_IP
import io.github.gcbond.openhc.platform.net.TestFrames.PHONE_IP
import io.github.gcbond.openhc.platform.net.TestFrames.PHONE_MAC
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArpTest {

    private val carMac = EthernetEndpoint.DEFAULT_CAR_MAC

    @Test
    fun parsesRequest() {
        val arp = Arp.parse(TestFrames.arpRequest(PHONE_MAC, PHONE_IP, CAR_IP), 0, 42)
        assertEquals(Arp.OP_REQUEST, arp.operation)
        assertContentEquals(PHONE_MAC, arp.senderMac)
        assertContentEquals(PHONE_IP, arp.senderIp)
        assertContentEquals(CAR_IP, arp.targetIp)
        assertFalse(arp.isProbe)
        assertFalse(arp.isAnnouncement)
    }

    @Test
    fun replySwapsRolesAndAnswersWithOurMac() {
        val request = Arp.parse(TestFrames.arpRequest(PHONE_MAC, PHONE_IP, CAR_IP), 0, 42)
        val out = ByteArray(50)
        val length = Arp.writeReply(request, carMac, out, 3)
        assertEquals(42, length)
        val reply = out.copyOfRange(3, 3 + length)

        assertContentEquals(PHONE_MAC, reply.copyOfRange(0, 6), "以太网目的地址是请求方")
        assertContentEquals(carMac, reply.copyOfRange(6, 12), "以太网源地址是车机")
        assertEquals(Ethernet.TYPE_ARP, Ethernet.etherType(reply, 0))
        val arp = 14
        assertEquals(1, reply.u16be(arp))
        assertEquals(0x0800, reply.u16be(arp + 2))
        assertEquals(6, reply.u8(arp + 4))
        assertEquals(4, reply.u8(arp + 5))
        assertEquals(Arp.OP_REPLY, reply.u16be(arp + 6))
        assertContentEquals(carMac, reply.copyOfRange(arp + 8, arp + 14), "发送方 MAC：车机")
        assertContentEquals(CAR_IP, reply.copyOfRange(arp + 14, arp + 18), "发送方 IP：被问的车机地址")
        assertContentEquals(PHONE_MAC, reply.copyOfRange(arp + 18, arp + 24), "目标 MAC：手机")
        assertContentEquals(PHONE_IP, reply.copyOfRange(arp + 24, arp + 28), "目标 IP：手机")
        assertEquals(0, out[0] + out[1] + out[2], "不写到偏移之前")
    }

    @Test
    fun parsesRequestInsideLargerBuffer() {
        // 真实收到的帧在 NTB 缓冲里，偏移不为 0：读请求和写应答的基准必须分开。
        val frame = TestFrames.arpRequest(PHONE_MAC, PHONE_IP, CAR_IP)
        val buffer = ByteArray(28 + frame.size + 18)
        System.arraycopy(frame, 0, buffer, 28, frame.size)
        val arp = Arp.parse(buffer, 28, frame.size + 18)
        assertContentEquals(CAR_IP, arp.targetIp)
        assertContentEquals(PHONE_IP, arp.senderIp)
    }

    @Test
    fun recognisesProbeAndAnnouncement() {
        val probe = Arp.parse(TestFrames.arpRequest(PHONE_MAC, ByteArray(4), PHONE_IP), 0, 42)
        assertTrue(probe.isProbe, "发送方 IP 为 0.0.0.0 是地址冲突探测")
        val announcement = Arp.parse(TestFrames.arpRequest(PHONE_MAC, PHONE_IP, PHONE_IP), 0, 42)
        assertTrue(announcement.isAnnouncement)
    }

    @Test
    fun rejectsOtherArpFlavoursAndShortFrames() {
        val good = TestFrames.arpRequest(PHONE_MAC, PHONE_IP, CAR_IP)
        assertFailsWith<MalformedMessageException> { Arp.parse(good, 0, 41) }
        val badHardware = good.copyOf().also { it.putU16be(14, 6) }
        assertFailsWith<MalformedMessageException> { Arp.parse(badHardware, 0, 42) }
        val badProtocol = good.copyOf().also { it.putU16be(16, Ethernet.TYPE_IPV6) }
        assertFailsWith<MalformedMessageException> { Arp.parse(badProtocol, 0, 42) }
        val badLengths = good.copyOf().also { it[19] = 16 }
        assertFailsWith<MalformedMessageException> { Arp.parse(badLengths, 0, 42) }
    }
}
