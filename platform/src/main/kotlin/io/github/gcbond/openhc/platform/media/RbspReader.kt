package io.github.gcbond.openhc.platform.media

import io.github.gcbond.openhc.core.common.MalformedMessageException

/**
 * 按位读 NAL 载荷（u(n)、ue(v)、se(v)），读的同时去掉防竞争字节，不另外拷贝。
 * 读过头抛 [MalformedMessageException]。可以用 [reset] 复用同一个对象。
 */
internal class RbspReader(data: ByteArray = EMPTY, start: Int = 0, end: Int = 0) {
    private var data = data
    private var pos = start
    private var end = end
    private var zeros = 0
    private var current = 0
    private var bitsLeft = 0

    fun reset(data: ByteArray, start: Int, end: Int): RbspReader {
        require(start >= 0 && end >= start && end <= data.size) { "范围非法" }
        this.data = data
        pos = start
        this.end = end
        zeros = 0
        current = 0
        bitsLeft = 0
        return this
    }

    init {
        reset(data, start, end)
    }

    private fun loadByte() {
        if (pos >= end) throw MalformedMessageException("码流数据不完整")
        var b = data[pos++].toInt() and 0xFF
        if (zeros >= 2 && b == 3) {
            zeros = 0
            if (pos >= end) throw MalformedMessageException("码流数据不完整")
            b = data[pos++].toInt() and 0xFF
        }
        zeros = if (b == 0) zeros + 1 else 0
        current = b
        bitsLeft = 8
    }

    fun bit(): Int {
        if (bitsLeft == 0) loadByte()
        bitsLeft--
        return (current ushr bitsLeft) and 1
    }

    fun flag(): Boolean = bit() == 1

    /** u(n)，n ≤ 32。 */
    fun bits(n: Int): Long {
        require(n in 0..32) { "一次最多读 32 位" }
        var v = 0L
        repeat(n) { v = (v shl 1) or bit().toLong() }
        return v
    }

    fun u(n: Int): Int {
        require(n in 0..31) { "u(n) 按 Int 读最多 31 位" }
        return bits(n).toInt()
    }

    /** ue(v)，最大 2^32 - 2。 */
    fun ueLong(): Long {
        var leadingZeros = 0
        while (bit() == 0) {
            leadingZeros++
            if (leadingZeros > 31) throw MalformedMessageException("指数哥伦布码过长")
        }
        if (leadingZeros == 0) return 0
        return (1L shl leadingZeros) - 1 + bits(leadingZeros)
    }

    /** ue(v)，超过 [max] 判为格式错误。 */
    fun ue(max: Int = Int.MAX_VALUE): Int {
        val v = ueLong()
        if (v > max) throw MalformedMessageException("字段取值超出范围")
        return v.toInt()
    }

    fun se(): Int {
        val k = ueLong()
        val magnitude = (k + 1) / 2
        if (magnitude > Int.MAX_VALUE) throw MalformedMessageException("字段取值超出范围")
        return if (k and 1L == 1L) magnitude.toInt() else -magnitude.toInt()
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
