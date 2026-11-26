package io.github.gcbond.openhc.core.common.codec

import io.github.gcbond.openhc.core.common.hexToBytes
import java.nio.charset.CharacterCodingException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Utf8Test {

    @Test
    fun decodesValidText() {
        assertEquals("", Utf8.decodeStrict(ByteArray(0)))
        assertEquals("OpenHC", Utf8.decodeStrict("OpenHC".toByteArray(Charsets.US_ASCII)))
        assertEquals("测试", Utf8.decodeStrict("测试".toByteArray(Charsets.UTF_8)))
        // 四字节字符（U+1F600）变成一对代理项。
        assertEquals("😀", Utf8.decodeStrict("f09f9880".hexToBytes()))
    }

    @Test
    fun honoursOffsetAndLength() {
        val data = byteArrayOf(0xFF.toByte()) + "车机".toByteArray(Charsets.UTF_8) + byteArrayOf(0xFF.toByte())
        assertEquals("车机", Utf8.decodeStrict(data, 1, data.size - 2))
        assertEquals("", Utf8.decodeStrict(data, 1, 0))
    }

    @Test
    fun rejectsMalformedSequences() {
        val bad = listOf(
            "ff",       // 不可能出现的字节
            "80",       // 孤立的后续字节
            "c0af",     // 非最短编码
            "eda080",   // 代理项码位 U+D800
            "e6b5",     // 截断的三字节字符
            "f4908080", // 超过 U+10FFFF
            "41ff42",   // 中间夹着坏字节
        )
        for (hex in bad) {
            assertFailsWith<CharacterCodingException>(hex) { Utf8.decodeStrict(hex.hexToBytes()) }
        }
    }

    @Test
    fun rejectsBadRange() {
        assertFailsWith<IllegalArgumentException> { Utf8.decodeStrict(ByteArray(3), 2, 2) }
        assertFailsWith<IllegalArgumentException> { Utf8.decodeStrict(ByteArray(3), -1, 1) }
    }
}
