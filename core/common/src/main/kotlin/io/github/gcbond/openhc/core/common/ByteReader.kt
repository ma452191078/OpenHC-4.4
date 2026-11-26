package io.github.gcbond.openhc.core.common

import io.github.gcbond.openhc.core.common.codec.Ea

/**
 * 按顺序从字节数组里读字段，带越界检查。越界抛 [MalformedMessageException]，
 * 方便在收包处统一当作「对端数据不合法」处理。
 *
 * 多字节整数分大端（`Be`）和小端（`Le`）两套：协议里两种都有，调用处要写清楚用哪一种。变长整数见 [ea]。
 */
class ByteReader(
    private val data: ByteArray,
    offset: Int = 0,
    private val limit: Int = data.size,
) {
    init {
        require(offset in 0..limit && limit <= data.size) { "范围非法: offset=$offset limit=$limit size=${data.size}" }
    }

    var position: Int = offset
        private set

    val remaining: Int get() = limit - position

    fun u8(): Int {
        need(1)
        return data[position++].toInt() and 0xFF
    }

    fun u16Be(): Int {
        need(2)
        val v = ((data[position].toInt() and 0xFF) shl 8) or (data[position + 1].toInt() and 0xFF)
        position += 2
        return v
    }

    fun u16Le(): Int {
        need(2)
        val v = (data[position].toInt() and 0xFF) or ((data[position + 1].toInt() and 0xFF) shl 8)
        position += 2
        return v
    }

    fun u32Be(): Long {
        need(4)
        var v = 0L
        for (i in 0 until 4) v = (v shl 8) or (data[position + i].toLong() and 0xFF)
        position += 4
        return v
    }

    fun u32Le(): Long {
        need(4)
        var v = 0L
        for (i in 3 downTo 0) v = (v shl 8) or (data[position + i].toLong() and 0xFF)
        position += 4
        return v
    }

    /**
     * 读一个 EA 变长整数（格式见 [Ea]），接受非最短形式。终止字节之前数据就到头、
     * 或连续 [Ea.MAX_BYTES] 字节都没有终止位时抛 [MalformedMessageException]，读位置不变。
     */
    fun ea(): Int {
        val packed = Ea.decode(data, position, limit)
        if (packed == Ea.NEED_MORE) throw MalformedMessageException("EA 变长整数被截断（位置 $limit）")
        if (packed == Ea.UNTERMINATED) {
            throw MalformedMessageException(
                "EA 变长整数连续 ${Ea.MAX_BYTES} 字节都没有终止位（位置 ${position + Ea.MAX_BYTES}）",
            )
        }
        position += Ea.sizeOf(packed)
        return Ea.valueOf(packed)
    }

    fun bytes(count: Int): ByteArray {
        require(count >= 0) { "长度不能为负: $count" }
        need(count)
        val out = data.copyOfRange(position, position + count)
        position += count
        return out
    }

    fun skip(count: Int) {
        require(count >= 0) { "长度不能为负: $count" }
        need(count)
        position += count
    }

    /** 读完剩下的全部字节。 */
    fun rest(): ByteArray = bytes(remaining)

    private fun need(count: Int) {
        if (count > remaining) {
            throw MalformedMessageException("数据不够: 需要 $count 字节，只剩 $remaining 字节（位置 $position）")
        }
    }
}
