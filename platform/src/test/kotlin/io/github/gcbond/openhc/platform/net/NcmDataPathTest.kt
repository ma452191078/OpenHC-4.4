package io.github.gcbond.openhc.platform.net

import io.github.gcbond.openhc.platform.net.TestFrames.CAR_IP
import io.github.gcbond.openhc.platform.net.TestFrames.PHONE_IP
import io.github.gcbond.openhc.platform.net.TestFrames.PHONE_MAC
import org.junit.After
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NcmDataPathTest {

    /** 假的 USB 端：收到的块从队列里取，发出的块记下来。 */
    private class FakePort(override val maxOutBlockLength: Int = 3568) : NtbPort {
        override val receiveSize = 16384
        override val outLayout = NtbOutLayout.DEFAULT
        override val zlpPacketSize = 512
        val incoming = LinkedBlockingQueue<ByteArray>()
        val sent = LinkedBlockingQueue<ByteArray>()

        @Volatile
        var broken = false

        @Volatile
        var receiving = 0

        override fun receive(buffer: ByteArray, timeoutMs: Int): Int {
            receiving++
            if (broken) return -1
            val block = incoming.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return 0
            System.arraycopy(block, 0, buffer, 0, block.size)
            return block.size
        }

        override fun send(buffer: ByteArray, length: Int, timeoutMs: Int): Boolean {
            sent.add(buffer.copyOf(length))
            return true
        }
    }

    /** 假的 TUN：要读的包从队列里取，写进来的包记下来。 */
    private class FakeTunnel : PacketTunnel {
        val toRead = LinkedBlockingQueue<ByteArray>()
        val written = LinkedBlockingQueue<ByteArray>()

        @Volatile
        var woken = false

        @Volatile
        var closedByPeer = false

        override fun read(buffer: ByteArray, offset: Int, maxLength: Int): Int {
            while (!woken) {
                if (closedByPeer) return -1
                val packet = toRead.poll(10, TimeUnit.MILLISECONDS) ?: continue
                System.arraycopy(packet, 0, buffer, offset, packet.size)
                return packet.size
            }
            return -1
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int): Boolean {
            written.add(buffer.copyOfRange(offset, offset + length))
            return true
        }

        override fun wakeup() {
            woken = true
        }
    }

    private class Events : NcmDataPath.Listener {
        val linkDown = LinkedBlockingQueue<NcmDataPath.LinkDown>()
        val milestones = LinkedBlockingQueue<NcmDataPath.Milestone>()

        override fun onLinkDown(reason: NcmDataPath.LinkDown) {
            linkDown.add(reason)
        }

        override fun onMilestone(milestone: NcmDataPath.Milestone, detail: String) {
            milestones.add(milestone)
        }
    }

    private val timing = NcmDataPath.Timing(
        receiveTimeoutMs = 20,
        sendTimeoutMs = 100,
        errorBackoffMs = 1,
        maxConsecutiveErrors = 5,
        joinTimeoutMs = 2000,
    )
    private val carMac = EthernetEndpoint.DEFAULT_CAR_MAC
    private val port = FakePort()
    private val tunnel = FakeTunnel()
    private val events = Events()
    private var path = NcmDataPath(port, tunnel, EthernetEndpoint(CAR_IP), events, timing)

    @After
    fun tearDown() {
        path.stop()
    }

    private fun ntbOf(frame: ByteArray, sequence: Int = 0): ByteArray =
        Ntb16.frame(frame, 0, frame.size, NtbOutLayout.DEFAULT, sequence, 512)

    private fun datagramsOf(ntb: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        Ntb16.parse(ntb, 0, ntb.size) { b, o, l -> out.add(b.copyOfRange(o, o + l)) }
        return out
    }

    private fun <T> LinkedBlockingQueue<T>.next(): T = assertNotNull(poll(2, TimeUnit.SECONDS), "等了 2 秒没有等到")

    @Test
    fun answersArpAndForwardsBothWays() {
        path.start()

        // 手机问车机地址 → 应答。
        port.incoming.add(ntbOf(TestFrames.arpRequest(PHONE_MAC, PHONE_IP, CAR_IP)))
        val replyNtb = port.sent.next()
        assertEquals(0, replyNtb.u16le(6), "第一块 NTB 序号为 0")
        val reply = datagramsOf(replyNtb).single()
        assertEquals(60, reply.size, "ARP 应答补齐到以太网最短帧")
        assertContentEquals(PHONE_MAC, reply.copyOfRange(0, 6))
        assertEquals(Arp.OP_REPLY, reply.u16be(14 + 6))
        assertContentEquals(CAR_IP, reply.copyOfRange(14 + 14, 14 + 18))

        // 手机发来的 SYN → TUN。
        val syn = TestFrames.tcpSyn(PHONE_IP, CAR_IP, 40000, 30000)
        port.incoming.add(ntbOf(TestFrames.ethernet(carMac, PHONE_MAC, Ethernet.TYPE_IPV4, syn), sequence = 1))
        assertContentEquals(syn, tunnel.written.next())

        // TUN 里的回包 → 手机。
        val synAck = TestFrames.tcpSyn(CAR_IP, PHONE_IP, 30000, 40000)
        tunnel.toRead.add(synAck)
        val outNtb = port.sent.next()
        assertEquals(1, outNtb.u16le(6), "序号递增")
        val frame = datagramsOf(outNtb).single()
        assertContentEquals(PHONE_MAC, frame.copyOfRange(0, 6))
        assertContentEquals(carMac, frame.copyOfRange(6, 12))
        assertEquals(Ethernet.TYPE_IPV4, Ethernet.etherType(frame, 0))
        assertContentEquals(synAck, frame.copyOfRange(14, 14 + synAck.size))

        assertTrue(path.stop())
        val stats = path.stats()
        assertEquals(2, stats.ntbFromPhone)
        assertEquals(1, stats.arpReplies)
        assertEquals(1, stats.packetsToTunnel)
        assertEquals(1, stats.packetsToPhone)
        assertNull(events.linkDown.poll(), "主动停止不报链路断开")
        assertTrue(events.milestones.contains(NcmDataPath.Milestone.FIRST_ARP_REPLY))
        assertTrue(events.milestones.contains(NcmDataPath.Milestone.FIRST_PACKET_TO_PHONE))
    }

    @Test
    fun badBlockIsSkippedAndReceivingContinues() {
        path.start()
        port.incoming.add(ByteArray(64) { 0x5A })
        port.incoming.add(ntbOf(TestFrames.arpRequest(PHONE_MAC, PHONE_IP, CAR_IP)))
        port.sent.next()
        assertEquals(1, path.stats().malformedNtb)
        assertTrue(events.milestones.contains(NcmDataPath.Milestone.FIRST_MALFORMED_NTB))
    }

    @Test
    fun reportsWhenPhoneAsksForAnotherAddress() {
        path.start()
        port.incoming.add(ntbOf(TestFrames.arpRequest(PHONE_MAC, PHONE_IP, TestFrames.OTHER_IP)))
        waitUntil { events.milestones.contains(NcmDataPath.Milestone.ARP_FOR_OTHER_ADDRESS) }
        assertEquals(1, path.stats().arpForOtherAddress)
        assertTrue(port.sent.isEmpty())
    }

    @Test
    fun packetsForPhoneAreDroppedUntilPhoneIsKnown() {
        path.start()
        tunnel.toRead.add(TestFrames.tcpSyn(CAR_IP, PHONE_IP, 30000, 40000))
        waitUntil { path.stats().droppedToPhone == 1L }
        assertTrue(port.sent.isEmpty())
    }

    @Test
    fun framesOverPhoneLimitAreDropped() {
        val smallPort = FakePort(maxOutBlockLength = 100)
        path = NcmDataPath(smallPort, tunnel, EthernetEndpoint(CAR_IP), events, timing)
        path.start()
        smallPort.incoming.add(ntbOf(TestFrames.arpRequest(PHONE_MAC, PHONE_IP, CAR_IP)))
        assertEquals(88, smallPort.sent.next().size, "ARP 应答的块（88 字节）在上限内")
        tunnel.toRead.add(TestFrames.udp(CAR_IP, PHONE_IP, 1, 2, ByteArray(200)))
        waitUntil { path.stats().droppedToPhone == 1L }
        assertTrue(smallPort.sent.isEmpty())
    }

    @Test
    fun persistentUsbErrorsBringTheLinkDown() {
        path.start()
        port.broken = true
        assertEquals(NcmDataPath.LinkDown.USB_LINK_LOST, events.linkDown.next())
        waitUntil { !path.isRunning }
        assertTrue(path.stop())
        assertNull(events.linkDown.poll(100, TimeUnit.MILLISECONDS), "只报一次")
    }

    @Test
    fun closedTunnelBringsTheLinkDown() {
        path.start()
        tunnel.closedByPeer = true
        assertEquals(NcmDataPath.LinkDown.TUNNEL_CLOSED, events.linkDown.next())
        assertTrue(path.stop(), "收线程看到停止标志后退出")
    }

    @Test
    fun stopWakesTunnelAndJoinsBothThreads() {
        path.start()
        waitUntil { port.receiving > 0 }
        assertTrue(path.stop())
        assertTrue(tunnel.woken)
        assertFalse(path.isRunning)
        val receivesAfterStop = port.receiving
        port.incoming.add(ntbOf(TestFrames.arpRequest(PHONE_MAC, PHONE_IP, CAR_IP)))
        Thread.sleep(100)
        assertEquals(receivesAfterStop, port.receiving, "停止后不再碰 USB")
        assertTrue(port.sent.isEmpty())
        assertTrue(path.stop(), "可以重复调用")
    }

    @Test
    fun linkDownListenerMayCallStop() {
        val selfStopping = object : NcmDataPath.Listener {
            lateinit var target: NcmDataPath
            val stopped = LinkedBlockingQueue<Boolean>()
            override fun onLinkDown(reason: NcmDataPath.LinkDown) {
                stopped.add(target.stop())
            }
        }
        path = NcmDataPath(port, tunnel, EthernetEndpoint(CAR_IP), selfStopping, timing)
        selfStopping.target = path
        path.start()
        port.broken = true
        // 在搬运线程上调 stop：不等自己，只等另一个线程。
        assertTrue(selfStopping.stopped.next())
    }

    @Test
    fun startsOnlyOnce() {
        path.start()
        assertFailsWith<IllegalStateException> { path.start() }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition()) {
            assertTrue(System.nanoTime() < deadline, "等了 2 秒条件仍不成立")
            Thread.sleep(5)
        }
    }
}
