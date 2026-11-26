package io.github.gcbond.openhc.core.hisight.control

import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.common.hexToBytes
import io.github.gcbond.openhc.core.common.toHex
import io.github.gcbond.openhc.core.hisight.FixedRandom
import io.github.gcbond.openhc.core.hisight.Vectors
import io.github.gcbond.openhc.core.hisight.arr
import io.github.gcbond.openhc.core.hisight.ascii
import io.github.gcbond.openhc.core.hisight.crypto.AesCtr
import io.github.gcbond.openhc.core.hisight.long
import io.github.gcbond.openhc.core.hisight.str
import kotlinx.serialization.json.jsonObject
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ControlBasicsTest {
    /** NIST SP 800-38A F.5.1 / F.5.2（CTR-AES128）公开向量。 */
    @Test
    fun aesCtrMatchesNistVectors() {
        val key = "2b7e151628aed2a6abf7158809cf4f3c".hexToBytes()
        val iv = "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff".hexToBytes()
        val plain = ("6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e51" +
            "30c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710").hexToBytes()
        val cipher = ("874d6191b620e3261bef6864990db6ce9806f66b7970fdff8617187bb9fffdff" +
            "5ae4df3edbd5d35e5b4f09020db03eab1e031dda2fbe03d1792170a0f3009cee").hexToBytes()
        val ctr = AesCtr(key)
        for (size in listOf(64, 17, 1, 0)) {
            val sealed = ctr.seal(plain.copyOf(size), FixedRandom(listOf(iv)))
            assertContentEquals(iv + cipher.copyOf(size), sealed, "长度 $size")
            assertContentEquals(plain.copyOf(size), ctr.open(sealed), "长度 $size")
        }
        assertFailsWith<MalformedMessageException> { ctr.open(ByteArray(15)) }
        assertFailsWith<IllegalArgumentException> { AesCtr(ByteArray(32)) }
    }

    @Test
    fun framingHandlesSplitsAndRejectsBadLengths() {
        assertContentEquals("0000000161".hexToBytes(), ControlFraming.encode(byteArrayOf(0x61)))
        assertFailsWith<IllegalArgumentException> { ControlFraming.encode(ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { ControlFraming.encode(ByteArray(65001)) }

        val stream = ControlFraming.encode(ascii("a")) + ControlFraming.encode(ascii("bc"))
        val decoder = ControlFrameDecoder()
        decoder.append(stream, 0, 9)
        assertContentEquals(ascii("a"), decoder.next())
        assertNull(decoder.next())
        assertTrue(decoder.hasPartial)
        decoder.append(stream, 9, 2)
        assertContentEquals(ascii("bc"), decoder.next())
        assertFalse(decoder.hasPartial)

        for (length in listOf(0L, 65001L, 0xFFFFFFFFL)) {
            val bad = ControlFrameDecoder()
            val header = byteArrayOf((length ushr 24).toByte(), (length ushr 16).toByte(), (length ushr 8).toByte(), length.toByte())
            bad.append(header, 0, 4)
            assertFailsWith<MalformedMessageException> { bad.next() }
        }
        // 最大长度和缓冲扩容。
        val big = ControlFraming.encode(ByteArray(65000) { it.toByte() })
        val decoder2 = ControlFrameDecoder()
        for (i in big.indices step 1000) decoder2.append(big, i, minOf(1000, big.size - i))
        assertEquals(65000, decoder2.next()!!.size)
    }

    @Test
    fun announceTemplateIsExact() {
        assertEquals(55, RtspTemplates.ANNOUNCE_BODY.length)
        assertEquals(138, RtspTemplates.ANNOUNCE.length)
        assertEquals("0000008a", ControlFraming.encode(ascii(RtspTemplates.ANNOUNCE)).copyOf(4).toHex())
        assertEquals("TEARDOWN  RTSP/1.0\r\nCSeq: 5\r\n\r\n", RtspTemplates.teardown(5))
    }

    @Test
    fun dateMatchesPrototype() {
        val utc = TimeZone.getTimeZone("UTC")
        for (element in Vectors.root.arr("dates")) {
            val v = element.jsonObject
            assertEquals(v.str("text"), RtspTemplates.date(v.long("utcMillis"), utc))
        }
        // 本地时区：同一时刻在东八区是另一天的 08 点。
        assertEquals("2026-01-02 08:00:00 AM Fri", RtspTemplates.date(1767312000000L, TimeZone.getTimeZone("GMT+08:00")))
    }

    @Test
    fun parameterLines() {
        assertEquals(listOf("a", "b"), ParameterText.lines(ascii("a\r\nb\r\n\r\n\r\n")))
        assertEquals(emptyList(), ParameterText.lines(ascii("\r\n")))
        assertFailsWith<MalformedMessageException> { ParameterText.lines(ascii("a\r\n\r\nb\r\n")) }
        assertFailsWith<MalformedMessageException> { ParameterText.lines(ascii("a\u000b\r\n")) }
        assertEquals(mapOf("codecs" to 1, "fps" to 30), ParameterText.integerFields("codecs 1; fps\t30;", setOf("codecs", "fps")))
        assertFailsWith<MalformedMessageException> { ParameterText.integerFields("codecs 1;;fps 30", setOf("codecs", "fps")) }
    }
}
