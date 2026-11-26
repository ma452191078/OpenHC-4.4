package io.github.gcbond.openhc.core.dmsdp

import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.common.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 业务 20 TCP：0x7F 帧与首轮 16 字节协商。 */
class SessionChannelTest {

    private fun newSession() = DmsdpSession(Synthetic.config(), Synthetic.KEY, SequenceRandom())

    @Test
    fun framesMatchCrossVectors() {
        assertEquals(CrossVectors.SESSION_NEG_REQUEST, SessionFrames.encode(1, ByteArray(16)).toHex())
        assertEquals(CrossVectors.SESSION_NEG_REPLY_40001, SessionFrames.negotiationReply(40001).toHex())
        val payload = ByteArray(130) { it.toByte() }
        assertEquals(CrossVectors.SESSION_FRAME_130, SessionFrames.encode(1, payload).toHex())
        assertEquals("7f8190", CrossVectors.SESSION_NEG_REQUEST.substring(0, 6), "16 字节协商帧头")
    }

    @Test
    fun decoderHandlesByteByByteAndStickyFrames() {
        val decoder = SessionFrameDecoder()
        val stream = hex(CrossVectors.SESSION_NEG_REQUEST) + hex(CrossVectors.SESSION_FRAME_130)
        val frames = ArrayList<SessionFrame>()
        for (b in stream) {
            decoder.append(byteArrayOf(b), 0, 1)
            while (true) frames += decoder.next() ?: break
        }
        assertEquals(2, frames.size)
        assertContentEquals(ByteArray(16), frames[0].payload)
        assertContentEquals(ByteArray(130) { it.toByte() }, frames[1].payload)
        assertTrue(!decoder.hasPartial)
    }

    @Test
    fun decoderRejectsBadFrames() {
        val good = hex(CrossVectors.SESSION_NEG_REQUEST)
        val badCrc = good.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        for (bad in listOf(badCrc, hex("00"), hex("7f01010101"), hex("7f8101010101"))) {
            val decoder = SessionFrameDecoder()
            decoder.append(bad, 0, bad.size)
            assertFailsWith<MalformedMessageException>(bad.toHex()) { decoder.next() }
        }
        val tooLong = hex("7f81") + Ea.encode(SessionFrames.MAX_PAYLOAD + 1)
        val decoder = SessionFrameDecoder()
        decoder.append(tooLong, 0, tooLong.size)
        assertFailsWith<MalformedMessageException> { decoder.next() }
    }

    @Test
    fun negotiationAsksForListenerThenReplies() {
        val session = newSession()
        val request = hex(CrossVectors.SESSION_NEG_REQUEST)
        assertTrue(session.feed(DmsdpChannel.SESSION, request.copyOf(5)).isEmpty())
        assertIs<DmsdpAction.OpenControlListener>(
            session.feed(DmsdpChannel.SESSION, request.copyOfRange(5, request.size)).only<DmsdpAction>(),
        )
        // 端口就绪前又来一次协商：不重复要求开监听，就绪后两次都应答。
        assertTrue(session.feed(DmsdpChannel.SESSION, request).isEmpty())
        val replies = session.controlListenerReady(40001).sends(DmsdpChannel.SESSION)
        assertEquals(2, replies.size)
        replies.forEach { assertEquals(CrossVectors.SESSION_NEG_REPLY_40001, it.toHex()) }
        // 之后的重复协商直接用同一端口应答。
        val again = session.feed(DmsdpChannel.SESSION, request).only<DmsdpAction.Send>()
        assertEquals(CrossVectors.SESSION_NEG_REPLY_40001, again.bytes.toHex())
        assertFailsWith<IllegalStateException> { session.controlListenerReady(40002) }
    }

    @Test
    fun unknownNegotiationIsRejectedWithoutReply() {
        val cases = listOf(
            SessionFrames.encode(1, ByteArray(16).also { it[0] = 1 }),
            SessionFrames.encode(1, ByteArray(15)),
            SessionFrames.encode(2, ByteArray(16)),
        )
        for (frame in cases) {
            val session = newSession()
            assertFailsWith<MalformedMessageException> { session.feed(DmsdpChannel.SESSION, frame) }
            assertFailsWith<IllegalStateException>("出错后会话作废") {
                session.feed(DmsdpChannel.SESSION, hex(CrossVectors.SESSION_NEG_REQUEST))
            }
        }
    }

    @Test
    fun closingWithPartialFrameFails() {
        val session = newSession()
        session.feed(DmsdpChannel.SESSION, hex(CrossVectors.SESSION_NEG_REQUEST).copyOf(10))
        assertFailsWith<MalformedMessageException> { session.onClosed(DmsdpChannel.SESSION, 0) }

        val clean = newSession()
        clean.feed(DmsdpChannel.SESSION, hex(CrossVectors.SESSION_NEG_REQUEST))
        assertTrue(clean.onClosed(DmsdpChannel.SESSION, 0).isEmpty())
    }
}
