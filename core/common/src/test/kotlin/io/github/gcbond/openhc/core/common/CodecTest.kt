package io.github.gcbond.openhc.core.common

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CodecTest {

    @Test
    fun hexRoundTrip() {
        val data = byteArrayOf(0x00, 0x0F, 0x7F, 0x80.toByte(), 0xFF.toByte())
        assertEquals("000f7f80ff", data.toHex())
        assertEquals("000F7F80FF", data.toHex(upperCase = true))
        assertContentEquals(data, "000F7f80ff".hexToBytes())
        assertFailsWith<IllegalArgumentException> { "abc".hexToBytes() }
        assertFailsWith<IllegalArgumentException> { "zz".hexToBytes() }
    }

    /** RFC 4648 第 10 节的测试向量。 */
    @Test
    fun base64Rfc4648Vectors() {
        val vectors = listOf(
            "" to "", "f" to "Zg==", "fo" to "Zm8=", "foo" to "Zm9v",
            "foob" to "Zm9vYg==", "fooba" to "Zm9vYmE=", "foobar" to "Zm9vYmFy",
        )
        for ((plain, encoded) in vectors) {
            assertEquals(encoded, Base64.encode(plain.toByteArray()))
            assertContentEquals(plain.toByteArray(), Base64.decode(encoded))
        }
    }

    @Test
    fun base64RejectsMalformed() {
        assertFailsWith<IllegalArgumentException> { Base64.decode("Zg=") }
        assertFailsWith<IllegalArgumentException> { Base64.decode("Z=g=") }
        assertFailsWith<IllegalArgumentException> { Base64.decode("====") }
        assertFailsWith<IllegalArgumentException> { Base64.decode("Zm9*") }
    }

    @Test
    fun base64AllByteValues() {
        val data = ByteArray(256) { it.toByte() }
        assertContentEquals(data, Base64.decode(Base64.encode(data)))
    }

    @Test
    fun readerWriterRoundTrip() {
        val bytes = ByteWriter(4)
            .u8(0xAB).u16Be(0x1234).u16Le(0x1234).u32Be(0xDEADBEEFL).u32Le(0xDEADBEEFL)
            .bytes(byteArrayOf(1, 2, 3))
            .toByteArray()
        assertEquals("ab12343412deadbeefefbeadde010203", bytes.toHex())

        val r = ByteReader(bytes)
        assertEquals(0xAB, r.u8())
        assertEquals(0x1234, r.u16Be())
        assertEquals(0x1234, r.u16Le())
        assertEquals(0xDEADBEEFL, r.u32Be())
        assertEquals(0xDEADBEEFL, r.u32Le())
        assertContentEquals(byteArrayOf(1, 2, 3), r.rest())
        assertEquals(0, r.remaining)
    }

    @Test
    fun readerThrowsOnShortData() {
        val r = ByteReader(byteArrayOf(1, 2, 3), offset = 1)
        assertEquals(2, r.remaining)
        assertFailsWith<MalformedMessageException> { r.u32Be() }
        assertEquals(1, r.position, "越界时不应移动读位置")
        assertFailsWith<MalformedMessageException> { r.bytes(3) }
    }

    @Test
    fun writerRejectsOutOfRange() {
        assertFailsWith<IllegalArgumentException> { ByteWriter().u8(256) }
        assertFailsWith<IllegalArgumentException> { ByteWriter().u16Be(-1) }
        assertFailsWith<IllegalArgumentException> { ByteWriter().u32Le(0x1_0000_0000L) }
    }
}
