package io.github.gcbond.openhc.platform.net

import io.github.gcbond.openhc.platform.net.EthernetEndpoint.Inbound
import io.github.gcbond.openhc.platform.net.EthernetEndpoint.Outbound
import io.github.gcbond.openhc.platform.net.TestFrames.CAR_IP
import io.github.gcbond.openhc.platform.net.TestFrames.OTHER_IP
import io.github.gcbond.openhc.platform.net.TestFrames.OTHER_MAC
import io.github.gcbond.openhc.platform.net.TestFrames.PHONE_IP
import io.github.gcbond.openhc.platform.net.TestFrames.PHONE_MAC
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EthernetEndpointTest {

    private class Recorder : EthernetEndpoint.Output {
        val toPhone = ArrayList<ByteArray>()
        val toTunnel = ArrayList<ByteArray>()

        override fun sendToPhone(frame: ByteArray, offset: Int, length: Int) {
            toPhone.add(frame.copyOfRange(offset, offset + length))
        }

        override fun deliverToTunnel(packet: ByteArray, offset: Int, length: Int) {
            toTunnel.add(packet.copyOfRange(offset, offset + length))
        }
    }

    private val carMac = EthernetEndpoint.DEFAULT_CAR_MAC
    private val endpoint = EthernetEndpoint(CAR_IP)
    private val out = Recorder()

    private fun handle(frame: ByteArray): Inbound = endpoint.handleFromPhone(frame, 0, frame.size, out)

    @Test
    fun answersArpForCarAddressAndLearnsPhone() {
        assertFalse(endpoint.knowsPhone)
        assertEquals(Inbound.ARP_REPLIED, handle(TestFrames.arpRequest(PHONE_MAC, PHONE_IP, CAR_IP)))
        val reply = out.toPhone.single()
        assertEquals(42, reply.size)
        assertContentEquals(PHONE_MAC, reply.copyOfRange(0, 6))
        assertContentEquals(carMac, reply.copyOfRange(6, 12))
        assertContentEquals(CAR_IP, reply.copyOfRange(28, 32))
        assertTrue(endpoint.knowsPhone)
        assertContentEquals(PHONE_IP, endpoint.phoneAddress)
    }

    @Test
    fun answersArpFoundInsideNtbBuffer() {
        // 帧在 NTB 缓冲中间（偏移 28），应答照样正确。
        val frame = TestFrames.arpRequest(PHONE_MAC, PHONE_IP, CAR_IP)
        val buffer = ByteArray(28 + frame.size + 18)
        System.arraycopy(frame, 0, buffer, 28, frame.size)
        assertEquals(Inbound.ARP_REPLIED, endpoint.handleFromPhone(buffer, 28, frame.size + 18, out))
        assertContentEquals(PHONE_IP, out.toPhone.single().copyOfRange(38, 42))
    }

    @Test
    fun ignoresArpThatShouldNotBeAnswered() {
        assertEquals(Inbound.ARP_FOR_OTHER_ADDRESS, handle(TestFrames.arpRequest(PHONE_MAC, PHONE_IP, OTHER_IP)))
        assertEquals(Inbound.ARP_IGNORED, handle(TestFrames.arpRequest(PHONE_MAC, ByteArray(4), CAR_IP)), "探测")
        assertEquals(Inbound.ARP_IGNORED, handle(TestFrames.arpRequest(PHONE_MAC, CAR_IP, CAR_IP)), "宣告")
        val reply = TestFrames.arp(Arp.OP_REPLY, PHONE_MAC, PHONE_IP, carMac, CAR_IP)
        assertEquals(Inbound.ARP_IGNORED, handle(reply))
        val multicastSender = byteArrayOf(0x01, 0x00, 0x5E, 0x00, 0x00, 0x01)
        assertEquals(Inbound.MALFORMED, handle(TestFrames.arpRequest(multicastSender, PHONE_IP, CAR_IP)))
        assertEquals(Inbound.MALFORMED, handle(TestFrames.arpRequest(PHONE_MAC, PHONE_IP, CAR_IP).copyOf(30)))
        assertTrue(out.toPhone.isEmpty())
        assertFalse(endpoint.knowsPhone)
    }

    @Test
    fun deliversIpv4ForCarWithoutEthernetPadding() {
        val packet = TestFrames.ipv4(PHONE_IP, CAR_IP, Ipv4.PROTOCOL_UDP, ByteArray(4) { 7 })
        val frame = TestFrames.ethernet(carMac, PHONE_MAC, Ethernet.TYPE_IPV4, packet, padTo60 = true)
        assertEquals(60, frame.size)
        assertEquals(Inbound.DELIVERED, handle(frame))
        assertContentEquals(packet, out.toTunnel.single(), "按 IPv4 总长交出，以太网补齐的 0 不进 TUN")
        assertTrue(endpoint.knowsPhone, "IPv4 包也能学到手机地址")
        assertContentEquals(PHONE_IP, endpoint.phoneAddress)
    }

    @Test
    fun dropsIpv4NotForCarAndBrokenPackets() {
        val other = TestFrames.ipv4(PHONE_IP, OTHER_IP, Ipv4.PROTOCOL_UDP, ByteArray(8))
        assertEquals(Inbound.NOT_FOR_US, handle(TestFrames.ethernet(carMac, PHONE_MAC, Ethernet.TYPE_IPV4, other)))
        val badChecksum = TestFrames.ipv4(PHONE_IP, CAR_IP, Ipv4.PROTOCOL_UDP, ByteArray(8)).also { it[8] = 1 }
        assertEquals(Inbound.MALFORMED, handle(TestFrames.ethernet(carMac, PHONE_MAC, Ethernet.TYPE_IPV4, badChecksum)))
        val truncated = TestFrames.ipv4(PHONE_IP, CAR_IP, Ipv4.PROTOCOL_UDP, ByteArray(30)).copyOf(40)
        assertEquals(Inbound.MALFORMED, handle(TestFrames.ethernet(carMac, PHONE_MAC, Ethernet.TYPE_IPV4, truncated)))
        assertEquals(Inbound.UNSUPPORTED_TYPE, handle(TestFrames.ethernet(carMac, PHONE_MAC, Ethernet.TYPE_IPV6, ByteArray(40))))
        assertEquals(Inbound.MALFORMED, endpoint.handleFromPhone(ByteArray(13), 0, 13, out))
        assertTrue(out.toTunnel.isEmpty())
        assertFalse(endpoint.knowsPhone)
    }

    @Test
    fun wrapsTunnelPacketOnlyOnceThePhoneIsKnown() {
        val packet = TestFrames.tcpSyn(CAR_IP, PHONE_IP, 30000, 40000)
        val buffer = ByteArray(28 + 14 + packet.size)
        System.arraycopy(packet, 0, buffer, 42, packet.size)
        assertEquals(Outbound.NO_PHONE_YET, endpoint.wrapForPhone(buffer, 42, packet.size))

        handle(TestFrames.arpRequest(PHONE_MAC, PHONE_IP, CAR_IP))
        assertEquals(Outbound.WRAPPED, endpoint.wrapForPhone(buffer, 42, packet.size))
        assertEquals(14 + packet.size, endpoint.frameLength(buffer, 42))
        assertContentEquals(PHONE_MAC, buffer.copyOfRange(28, 34))
        assertContentEquals(carMac, buffer.copyOfRange(34, 40))
        assertEquals(Ethernet.TYPE_IPV4, buffer.u16be(40))
        assertContentEquals(packet, buffer.copyOfRange(42, 42 + packet.size), "IP 包不被改写")
    }

    @Test
    fun dropsNonIpv4FromTunnel() {
        handle(TestFrames.arpRequest(PHONE_MAC, PHONE_IP, CAR_IP))
        val buffer = ByteArray(100)
        buffer[42] = 0x60 // IPv6
        assertEquals(Outbound.DROPPED, endpoint.wrapForPhone(buffer, 42, 40))
        assertFailsWith<IllegalArgumentException> { endpoint.wrapForPhone(buffer, 10, 40) }
    }

    @Test
    fun followsPhoneWhenItsMacOrAddressChanges() {
        handle(TestFrames.arpRequest(PHONE_MAC, PHONE_IP, CAR_IP))
        val newIp = TestFrames.ip(192, 168, 66, 46)
        handle(TestFrames.arpRequest(OTHER_MAC, newIp, CAR_IP))
        assertContentEquals(newIp, endpoint.phoneAddress)
        val packet = TestFrames.ipv4(CAR_IP, newIp, Ipv4.PROTOCOL_UDP, ByteArray(8))
        val buffer = ByteArray(14 + packet.size)
        System.arraycopy(packet, 0, buffer, 14, packet.size)
        endpoint.wrapForPhone(buffer, 14, packet.size)
        assertContentEquals(OTHER_MAC, buffer.copyOfRange(0, 6))
    }

    @Test
    fun rejectsBadConfiguration() {
        assertFailsWith<IllegalArgumentException> { EthernetEndpoint(ByteArray(16)) }
        assertFailsWith<IllegalArgumentException> { EthernetEndpoint(CAR_IP, byteArrayOf(0x01, 0, 0, 0, 0, 1)) }
        assertNull(EthernetEndpoint(CAR_IP).phoneAddress)
    }
}
