package io.github.gcbond.openhc.core.common.codec

import io.github.gcbond.openhc.core.common.ByteReader
import io.github.gcbond.openhc.core.common.ByteWriter
import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.common.hexToBytes
import io.github.gcbond.openhc.core.common.toHex
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EaTest {

    /** 值 → 编码，覆盖每种长度的上下边界。 */
    private val vectors = mapOf(
        0 to "80", 1 to "81", 2 to "82", 101 to "e5", 105 to "e9", 127 to "ff",
        128 to "0081", 155 to "1b81", 300 to "2c82", 16383 to "7fff",
        16384 to "000081", 65535 to "7f7f83", 2097151 to "7f7fff",
        2097152 to "00000081", 268435455 to "7f7f7fff",
    )

    @Test
    fun encodeAndDecodeVectors() {
        for ((value, encoded) in vectors) {
            val bytes = encoded.hexToBytes()
            assertEquals(encoded, Ea.encode(value).toHex(), "编码 $value")
            assertEquals(bytes.size, Ea.encodedSize(value), "长度 $value")
            assertEquals(encoded, ByteWriter().ea(value).toByteArray().toHex(), "ByteWriter $value")

            val packed = Ea.decode(bytes, 0, bytes.size)
            assertEquals(value, Ea.valueOf(packed), "解码 $encoded")
            assertEquals(bytes.size, Ea.sizeOf(packed), "占用 $encoded")

            // 前后夹着别的字节：只读自己那几个。
            val padded = byteArrayOf(0x55) + bytes + byteArrayOf(0x81.toByte())
            val inner = Ea.decode(padded, 1, padded.size)
            assertEquals(value, Ea.valueOf(inner))
            assertEquals(bytes.size, Ea.sizeOf(inner))

            val reader = ByteReader(padded, offset = 1)
            assertEquals(value, reader.ea())
            assertEquals(1 + bytes.size, reader.position)
        }
    }

    @Test
    fun maxValueIsTwoToThe28MinusOne() {
        assertEquals((1 shl 28) - 1, Ea.MAX_VALUE)
        assertEquals(4, Ea.MAX_BYTES)
    }

    @Test
    fun decodeDistinguishesNeedMoreFromUnterminated() {
        assertEquals(Ea.NEED_MORE, Ea.decode(ByteArray(0), 0, 0))
        assertEquals(Ea.NEED_MORE, Ea.decode("00".hexToBytes(), 0, 1))
        assertEquals(Ea.NEED_MORE, Ea.decode("000000".hexToBytes(), 0, 3))
        // limit 截在终止字节之前，后面的数据不算。
        assertEquals(Ea.NEED_MORE, Ea.decode("0081".hexToBytes(), 0, 1))
        assertEquals(Ea.NEED_MORE, Ea.decode("81".hexToBytes(), 1, 1))
        assertEquals(Ea.UNTERMINATED, Ea.decode("00000000".hexToBytes(), 0, 4))
        assertEquals(Ea.UNTERMINATED, Ea.decode("7f7f7f7f81".hexToBytes(), 0, 5))
        assertEquals(Ea.UNTERMINATED, Ea.decode("aa7f7f7f7f".hexToBytes(), 1, 5))
    }

    @Test
    fun decodeRejectsBadRange() {
        assertFailsWith<IllegalArgumentException> { Ea.decode(ByteArray(2), -1, 2) }
        assertFailsWith<IllegalArgumentException> { Ea.decode(ByteArray(2), 0, 3) }
    }

    @Test
    fun nonMinimalEncodingsAreAccepted() {
        assertEquals(0, ByteReader("0080".hexToBytes()).ea())
        assertEquals(5, ByteReader("050080".hexToBytes()).ea())
        val packed = Ea.decode("00000080".hexToBytes(), 0, 4)
        assertEquals(0, Ea.valueOf(packed))
        assertEquals(4, Ea.sizeOf(packed))
    }

    @Test
    fun encodeRejectsOutOfRange() {
        for (bad in intArrayOf(-1, Ea.MAX_VALUE + 1, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertFailsWith<IllegalArgumentException>("$bad") { Ea.encode(bad) }
            assertFailsWith<IllegalArgumentException>("$bad") { Ea.encodedSize(bad) }
            assertFailsWith<IllegalArgumentException>("$bad") { ByteWriter().ea(bad) }
        }
    }

    /** 读不出来时：信息里的位置与原来各层的写法一致，读位置不动。 */
    @Test
    fun readerFailuresKeepPosition() {
        val truncated = ByteReader("aa0000".hexToBytes(), offset = 1)
        val cut = assertFailsWith<MalformedMessageException> { truncated.ea() }
        assertEquals("EA 变长整数被截断（位置 3）", cut.message)
        assertEquals(1, truncated.position)

        val unterminated = ByteReader("7f7f7f7f81".hexToBytes())
        val bad = assertFailsWith<MalformedMessageException> { unterminated.ea() }
        assertEquals("EA 变长整数连续 4 字节都没有终止位（位置 4）", bad.message)
        assertEquals(0, unterminated.position)

        assertFailsWith<MalformedMessageException> { ByteReader(ByteArray(0)).ea() }
    }

    @Test
    fun randomValuesRoundTrip() {
        val random = Random(20261118)
        val writer = ByteWriter()
        val values = IntArray(2000) { if (it < 4) intArrayOf(0, 127, 128, Ea.MAX_VALUE)[it] else random.nextInt(Ea.MAX_VALUE + 1) }
        for (v in values) writer.ea(v)
        val reader = ByteReader(writer.toByteArray())
        for (v in values) assertEquals(v, reader.ea())
        assertEquals(0, reader.remaining)
    }
}
