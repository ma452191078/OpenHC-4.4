package io.github.gcbond.openhc.core.nearby

import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.common.codec.Ea as EaCodec

/**
 * nearby 层的变长整数（下文称 EA），控制帧的类型、长度和 TLV 都用它。格式与编解码在 core:common 的
 * [EaCodec]；在 `ByteWriter` / `ByteReader` 里读写用它们的 `ea` 方法。
 *
 * 这里只保留拆帧器用的 [tryDecode]：格式不对时按本层的说法报错。
 */
internal object Ea {
    const val MAX_BYTES = EaCodec.MAX_BYTES
    const val MAX_VALUE = EaCodec.MAX_VALUE

    fun encodedSize(value: Int): Int = EaCodec.encodedSize(value)

    fun encode(value: Int): ByteArray = EaCodec.encode(value)

    /**
     * 从 [data] 的 [offset] 起尝试解一个 EA，最多读到 [limit]（不含）。
     *
     * 返回 `值 shl 8 or 占用字节数`；数据在终止位之前就到头了，返回 -1 表示还要等数据；
     * 连读 4 字节都没有终止位，说明格式不对，抛 [MalformedMessageException]。
     */
    fun tryDecode(data: ByteArray, offset: Int, limit: Int): Long {
        val packed = EaCodec.decode(data, offset, limit)
        if (packed == EaCodec.UNTERMINATED) {
            throw MalformedMessageException("EA 变长整数连续 $MAX_BYTES 字节都没有终止位（位置 $offset）")
        }
        return packed
    }
}
