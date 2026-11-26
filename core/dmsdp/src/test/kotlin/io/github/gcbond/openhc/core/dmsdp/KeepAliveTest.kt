package io.github.gcbond.openhc.core.dmsdp

import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.common.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** TCP / UDP 保活的周期、计数和切换规则。时间全部用参数推进，不真的等待。 */
class KeepAliveTest {

    private val phone = Phone()
    private val legacy = hex("00000000000a00000000000000000000")
    private val extended = hex("00000000000a0ade0000001e00000000")

    private fun get(cseq: Int) = phone.encryptedText("GET_PARAMETER * DMSDP/1.0\r\nCSeq: $cseq\r\n\r\n")

    private fun DmsdpSession.sentGet(outputs: List<DmsdpOutput>): String =
        String(phone.openControlFrame(outputs.only<DmsdpAction.Send>().bytes))

    // ---------------- TCP ----------------

    @Test
    fun tcpRunsEverySecondAndAnswersNothing() {
        val (session, _) = encryptedSession(nowMillis = 100_000)
        assertEquals(101_000, session.nextTickAtMillis)
        assertTrue(session.onTick(100_900).isEmpty(), "未到时间")
        for (i in 0 until 180) {
            assertTrue(session.feed(DmsdpChannel.CONTROL, get(i + 1)).isEmpty(), "手机的 GET 不回 200")
            assertEquals(
                "GET_PARAMETER * DMSDP/1.0\r\nCSeq: ${i + 2}\r\n\r\n",
                session.sentGet(session.onTick(101_000L + i * 1000)),
            )
        }
        // 主循环停了很久：只补一轮，不追补积压的轮次；这一轮没收到 GET，计一次失败但不超时。
        assertEquals("GET_PARAMETER * DMSDP/1.0\r\nCSeq: 182\r\n\r\n", session.sentGet(session.onTick(500_000)))
        assertEquals(501_000, session.nextTickAtMillis)
    }

    @Test
    fun tcpCountsMissesBeforeFirstGetAndTimesOutAtTen() {
        val (session, _) = encryptedSession(nowMillis = 100_000)
        for (now in 101_000L..109_000L step 1000) assertTrue(session.onTick(now).isEmpty(), "首个 GET 前不发")
        // 半个 GET 帧不能续命。
        session.feed(DmsdpChannel.CONTROL, get(9).copyOf(10))
        val timeout = session.onTick(110_000)
        assertEquals(KeepAliveMode.TCP, (timeout.single() as DmsdpEvent.KeepAliveTimeout).mode)
        assertNull(session.nextTickAtMillis)
        assertTrue(session.onTick(200_000).isEmpty())
    }

    @Test
    fun tcpResumesWhenCompleteGetArrives() {
        val (session, _) = encryptedSession(nowMillis = 100_000)
        val frame = get(2)
        session.feed(DmsdpChannel.CONTROL, frame.copyOf(frame.size - 1))
        assertTrue(session.onTick(101_000).isEmpty())
        session.feed(DmsdpChannel.CONTROL, frame.copyOfRange(frame.size - 1, frame.size))
        assertEquals("GET_PARAMETER * DMSDP/1.0\r\nCSeq: 2\r\n\r\n", session.sentGet(session.onTick(102_000)))
    }

    @Test
    fun businessTrafficDoesNotCountAsKeepAlive() {
        val (session, _) = encryptedSession(nowMillis = 100_000)
        for (now in 101_000L..110_000L step 1000) {
            val traffic = if (now % 2000 == 0L) {
                phone.encryptedText("DMSDP/1.0 200 OK\r\nCSeq: 7\r\n\r\n")
            } else {
                phone.encryptedRecord(3, 110, hex("58"))
            }
            session.feed(DmsdpChannel.CONTROL, traffic)
            val out = session.onTick(now)
            if (now < 110_000) assertTrue(out.isEmpty()) else assertIs<DmsdpEvent.KeepAliveTimeout>(out.single())
        }
    }

    @Test
    fun malformedGetIsNotCountedButDoesNotBreakSession() {
        val invalid = listOf(
            "GET_PARAMETER * DMSDP/2.0\r\nCSeq: 1\r\n\r\n",
            "GET_PARAMETER rtsp://example.test DMSDP/1.0\r\nCSeq: 1\r\n\r\n",
            "GET_PARAMETER * DMSDP/1.0\r\n\r\n",
            "GET_PARAMETER * DMSDP/1.0\r\nCSeq: 0\r\n\r\n",
            "GET_PARAMETER * DMSDP/1.0\r\nCSeq: -1\r\n\r\n",
            "GET_PARAMETER * DMSDP/1.0\r\nCSeq: +1\r\n\r\n",
            "GET_PARAMETER * DMSDP/1.0\r\nCSeq: 1\r\nContent-Length: 1\r\n\r\nx",
            "GET_PARAMETER * DMSDP/1.0\r\nCSeq: 1\r\nCSeq: 2\r\n\r\n",
        )
        for (text in invalid) {
            val (session, _) = encryptedSession(nowMillis = 100_000)
            assertIs<DmsdpEvent.UnhandledControlMessage>(session.feed(DmsdpChannel.CONTROL, phone.encryptedText(text)).single(), text)
            assertTrue(session.onTick(101_000).isEmpty(), "不算存活，也不开始主动发 GET")
        }
    }

    // ---------------- UDP ----------------

    private fun udpSession(nowMillis: Long = 100_000): DmsdpSession {
        val (session, outputs) = encryptedSession(nowMillis = nowMillis, select = "UdpKA: 1\r\nUdpKAport: 52000\r\n")
        assertEquals(52000, outputs.filterIsInstance<DmsdpAction.OpenKeepAliveSocket>().single().remotePort)
        return session
    }

    private fun datagrams(outputs: List<DmsdpOutput>) = outputs.map { assertIs<DmsdpAction.SendDatagram>(it).bytes }

    @Test
    fun udpLegacyDoubleSendsEveryOtherSecondFor180Rounds() {
        val session = udpSession()
        session.feed(DmsdpChannel.CONTROL, get(2))
        var sent = 0
        var received = 0
        for (i in 0 until 180) {
            val out = session.onTick(101_000L + i * 1000)
            if (out.isEmpty()) continue
            val pair = datagrams(out)
            assertEquals(2, pair.size)
            for (d in pair) {
                assertEquals(35, d.size)
                assertEquals("ab0020", d.copyOf(3).toHex())
                assertContentEquals(legacy, phone.decrypt(d.copyOfRange(3, d.size)))
            }
            assertNotEquals(pair[0].copyOfRange(3, 19).toHex(), pair[1].copyOfRange(3, 19).toHex(), "两个包各用各的 IV")
            sent += 2
            assertTrue(session.onDatagram(phone.datagram(legacy), 0).isEmpty(), "收到保活不自动回应")
            received++
        }
        assertEquals(178, sent)
        assertEquals(89, received)
    }

    @Test
    fun udpTimesOutOnRound162WithoutPeerPackets() {
        val session = udpSession()
        for (i in 0 until 161) {
            val out = session.onTick(101_000L + i * 1000)
            assertTrue(out.none { it is DmsdpEvent }, "第 ${i + 1} 轮还不能判定失活")
        }
        // TCP 上的 GET 不能冒充 UDP 存活。
        session.feed(DmsdpChannel.CONTROL, get(2))
        assertEquals(KeepAliveMode.UDP, (session.onTick(262_000).single() as DmsdpEvent.KeepAliveTimeout).mode)
        assertNull(session.nextTickAtMillis)
    }

    @Test
    fun udpExtendedPeriodIsAParameterNotASequence() {
        val session = udpSession()
        assertTrue((session.onDatagram(phone.datagram(extended), 0).single() as DmsdpEvent.KeepAlivePeriodChanged).extended)
        assertTrue(session.onTick(101_000).isEmpty())
        assertEquals(116_000, session.nextTickAtMillis)
        assertTrue(session.onTick(115_900).isEmpty())
        for (d in datagrams(session.onTick(116_000))) assertContentEquals(extended, phone.decrypt(d.copyOfRange(3, d.size)))
        // 周期参数不是 30 的扩展标记：仍算存活，但回到兼容周期。
        val other = hex("00000000000a0ade0000001f00000000")
        assertTrue(!(session.onDatagram(phone.datagram(other), 0).single() as DmsdpEvent.KeepAlivePeriodChanged).extended)
        session.onTick(131_000)
        assertEquals(132_000, session.nextTickAtMillis)
    }

    @Test
    fun udpExtendedWindowUsesPeriodTimesRetryOverTwo() {
        val session = udpSession()
        session.onDatagram(phone.datagram(extended), 0)
        var sent = 0
        for (i in 0 until 2402) {
            val out = session.onTick(101_000L + i * 15_000L)
            assertTrue(out.none { it is DmsdpEvent }, "第 ${i + 1} 轮：窗口内收到过一次，不失活")
            sent += out.size
        }
        assertEquals(2400, sent)
        // 新窗口里再没收到：再过 2401 轮后失活。
        var timedOut = false
        for (i in 2402 until 2402 + 2401) {
            if (session.onTick(101_000L + i * 15_000L).any { it is DmsdpEvent.KeepAliveTimeout }) timedOut = true
        }
        assertTrue(timedOut)
    }

    @Test
    fun udpRejectsAnythingButTheKnownDatagram() {
        val session = udpSession()
        val good = phone.datagram(legacy)
        val invalid = listOf(
            ByteArray(0),
            hex("000010") + legacy,
            good.copyOf(good.size - 1),
            good + hex("78"),
            phone.datagram(legacy.copyOf(15)),
            phone.datagram(hex("00000001") + legacy.copyOfRange(4, 16)),
        )
        for (d in invalid) assertFailsWith<MalformedMessageException>(d.toHex()) { session.onDatagram(d, 0) }
        // 出错不作废会话，之后合法包照常处理。
        assertTrue(session.onDatagram(good, 0).isEmpty())
        assertTrue(session.onTick(101_000).isEmpty())
    }

    @Test
    fun udpStopsAfterControlCloses() {
        val session = udpSession()
        session.onTick(101_000)
        session.onClosed(DmsdpChannel.CONTROL, 0)
        assertTrue(session.onTick(102_000).isEmpty())
        assertTrue(session.onDatagram(phone.datagram(legacy), 0).isEmpty())
    }

    @Test
    fun keepAlivePayloadsMatchCrossVectors() {
        val iv = ByteArray(16) { (0x10 + it).toByte() }
        assertEquals(CrossVectors.UDP_LEGACY_IV10, ControlFrames.encode(0xAB, phone.encrypt(legacy, iv)).toHex())
        assertEquals(CrossVectors.UDP_EXTENDED_IV10, ControlFrames.encode(0xAB, phone.encrypt(extended, iv)).toHex())
        val keepAlive = UdpKeepAlive(0, 160)
        assertContentEquals(legacy, keepAlive.payload())
        keepAlive.onPeerKeepAlive(extended)
        assertContentEquals(extended, keepAlive.payload())
    }
}
