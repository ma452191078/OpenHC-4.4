package io.github.gcbond.openhc.core.nearby

import io.github.gcbond.openhc.core.common.ByteReader
import io.github.gcbond.openhc.core.common.ByteWriter
import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.common.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** EA 变长整数、TLV、CRC-16/ARC。 */
class EncodingTest {

    @Test
    fun eaMatchesReferenceVectors() {
        for ((value, encoded) in Vectors.EA) {
            assertEquals(encoded, Ea.encode(value).toHex(), "编码 $value")
            assertEquals(encoded.length / 2, Ea.encodedSize(value))
            assertEquals(value, ByteReader(hex(encoded)).ea(), "解码 $encoded")
            val packed = Ea.tryDecode(hex(encoded), 0, encoded.length / 2)
            assertEquals(value.toLong(), packed ushr 8)
            assertEquals((encoded.length / 2).toLong(), packed and 0xFF)
        }
    }

    @Test
    fun eaRejectsOutOfRange() {
        assertFailsWith<IllegalArgumentException> { Ea.encode(-1) }
        assertFailsWith<IllegalArgumentException> { Ea.encode(Ea.MAX_VALUE + 1) }
    }

    @Test
    fun eaDistinguishesTruncatedFromUnterminated() {
        // 数据到头了还没见终止位：要等更多数据。
        assertEquals(-1L, Ea.tryDecode(hex("00"), 0, 1))
        assertEquals(-1L, Ea.tryDecode(hex("000000"), 0, 3))
        assertEquals(-1L, Ea.tryDecode(ByteArray(0), 0, 0))
        // 4 字节都没有终止位：格式不对。
        assertFailsWith<MalformedMessageException> { Ea.tryDecode(hex("00000000"), 0, 4) }
        assertFailsWith<MalformedMessageException> { ByteReader(hex("7f7f7f7f81")).ea() }
        assertFailsWith<MalformedMessageException> { ByteReader(hex("0000")).ea() }
        assertFailsWith<MalformedMessageException> { ByteReader(ByteArray(0)).ea() }
    }

    @Test
    fun eaAcceptsNonMinimalEncoding() {
        assertEquals(0, ByteReader(hex("0080")).ea())
        assertEquals(5, ByteReader(hex("050080")).ea())
    }

    @Test
    fun tlvMatchesReferenceVectors() {
        assertEquals("8f86313233343536", encodeTlv(15, "123456".toByteArray()).toHex())
        assertEquals("8680", encodeTlv(6).toHex())
        val long = encodeTlv(200, pattern(130))
        assertEquals("48810281", long.copyOfRange(0, 4).toHex())
        assertContentEquals(pattern(130), long.copyOfRange(4, long.size))
    }

    @Test
    fun tlvParseKeepsOrderAndDuplicates() {
        val data = ByteWriter()
            .tlv(5, byteArrayOf(2)).tlv(1, byteArrayOf(5)).tlv(5, byteArrayOf()).tlv(200, pattern(130))
            .toByteArray()
        val items = Tlv.parseAll(data)
        assertEquals(listOf(5, 1, 5, 200), items.map { it.tag })
        assertEquals(listOf(1, 1, 0, 130), items.map { it.length })
        assertContentEquals(pattern(130), items[3].value)
        assertEquals(emptyList(), Tlv.parseAll(ByteArray(0)).map { it.tag })
    }

    @Test
    fun tlvParseRejectsTruncation() {
        assertFailsWith<MalformedMessageException> { Tlv.parseAll(hex("8185")) }     // 声明 5 字节，没有值
        assertFailsWith<MalformedMessageException> { Tlv.parseAll(hex("818201")) }   // 声明 2 字节，只有 1 字节
        assertFailsWith<MalformedMessageException> { Tlv.parseAll(hex("81")) }       // 只有 tag
        assertFailsWith<MalformedMessageException> { Tlv.parseAll(hex("0000000081")) } // tag 的 EA 没有终止位
        assertFailsWith<MalformedMessageException> { Tlv.parseAll(hex("8181"), 1, 5) } // 范围越界
    }

    @Test
    fun tlvValueIsCopied() {
        val raw = byteArrayOf(1, 2, 3)
        val t = Tlv(1, raw)
        raw[0] = 9
        t.value[1] = 9
        assertContentEquals(byteArrayOf(1, 2, 3), t.value)
    }

    @Test
    fun crc16ArcStandardCheckValue() {
        // CRC-16/ARC 的公开校验值。
        assertEquals(0xBB3D, Crc16Arc.compute("123456789".toByteArray()))
        assertEquals(0, Crc16Arc.compute(ByteArray(0)))
        assertEquals(Vectors.CRC_PATTERN_64, Crc16Arc.compute(pattern(64)))
        val padded = byteArrayOf(9, 9) + "123456789".toByteArray() + byteArrayOf(9)
        assertEquals(0xBB3D, Crc16Arc.compute(padded, 2, 9))
    }
}
