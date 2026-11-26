package io.github.gcbond.openhc.core.common.codec

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * 严格的 UTF-8 解码：遇到非法字节序列（非最短编码、代理项码位、截断的多字节字符、0xC0、0xC1、0xF5..0xFF 等）
 * 直接报错，不替换成 U+FFFD。解析对端发来的文本时用它，免得把坏数据悄悄变成另一段文字。
 *
 * 每次调用新建解码器，线程安全。
 */
object Utf8 {
    /**
     * 解码 `data[offset, offset + length)`。
     *
     * @throws CharacterCodingException 不是合法的 UTF-8。调用方按自己的语境转成
     *   [io.github.gcbond.openhc.core.common.MalformedMessageException]（要不要挂原异常由调用方决定）。
     * @throws IllegalArgumentException 范围越界。
     */
    @Throws(CharacterCodingException::class)
    fun decodeStrict(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): String {
        require(offset >= 0 && length >= 0 && offset <= data.size - length) {
            "范围非法: offset=$offset length=$length size=${data.size}"
        }
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(data, offset, length))
            .toString()
    }
}
