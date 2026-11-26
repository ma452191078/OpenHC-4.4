package io.github.gcbond.openhc.platform.media

/**
 * H.264 Annex-B 字节流：NAL 单元之间用三字节 `00 00 01` 或四字节 `00 00 00 01` 起始码分隔。
 * 纯逻辑，不引用安卓类。
 */
object AnnexB {
    const val NAL_SLICE = 1
    const val NAL_IDR = 5
    const val NAL_SEI = 6
    const val NAL_SPS = 7
    const val NAL_PPS = 8
    const val NAL_AUD = 9

    /** 收到一个 NAL 单元：[start] 指向 NAL 头字节，[end] 不含。 */
    fun interface NalVisitor {
        fun onNal(data: ByteArray, start: Int, end: Int)
    }

    /** 一个 NAL 单元在原数组里的位置，[type] 是 NAL 头的低 5 位。 */
    data class NalUnit(val start: Int, val end: Int, val type: Int)

    /** NAL 头字节的低 5 位。 */
    fun nalType(header: Byte): Int = header.toInt() and 0x1F

    /**
     * 按顺序找出 `[offset, offset + length)` 里的 NAL 单元，返回个数。
     *
     * 第一个起始码之前的字节不算。每个 NAL 去掉末尾的 0：那是下一个四字节起始码的头一个字节或
     * trailing_zero_8bits（标准规定 NAL 的最后一个字节不能是 0），去掉之后为空的不算。
     */
    fun forEachNal(data: ByteArray, offset: Int, length: Int, visitor: NalVisitor): Int {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) { "范围非法" }
        val end = offset + length
        var startCode = findStartCode(data, offset, end)
        var count = 0
        while (startCode >= 0) {
            val nalStart = startCode + 3
            val next = findStartCode(data, nalStart, end)
            var nalEnd = if (next < 0) end else next
            while (nalEnd > nalStart && data[nalEnd - 1].toInt() == 0) nalEnd--
            if (nalEnd > nalStart) {
                visitor.onNal(data, nalStart, nalEnd)
                count++
            }
            startCode = next
        }
        return count
    }

    /** 列出各 NAL 单元，测试和一次性用途用；送帧的热路径用 [forEachNal]，不分配列表。 */
    fun split(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): List<NalUnit> {
        val out = ArrayList<NalUnit>()
        forEachNal(data, offset, length) { d, start, end -> out += NalUnit(start, end, nalType(d[start])) }
        return out
    }

    /**
     * 从 [from] 起找下一个 `00 00 01`，返回第一个 `00` 的位置，找不到返回 -1。
     *
     * 每次看候选位置的第三个字节：它既不是 0 也不是 1 时，以这三个位置开头的起始码都不可能成立，一次跳 3 个字节。
     */
    internal fun findStartCode(data: ByteArray, from: Int, end: Int): Int {
        var i = from
        while (i + 2 < end) {
            val third = data[i + 2].toInt()
            when (third) {
                1 -> {
                    if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) return i
                    i += 3
                }
                0 -> i++
                else -> i += 3
            }
        }
        return -1
    }

    /**
     * RBSP → NAL 载荷：`00 00` 后面出现 `00`～`03` 时插入防竞争字节 `03`；
     * RBSP 以 `00` 结尾时末尾再补一个 `03`。
     */
    fun escape(rbsp: ByteArray, offset: Int = 0, length: Int = rbsp.size - offset): ByteArray {
        require(offset >= 0 && length >= 0 && offset + length <= rbsp.size) { "范围非法" }
        val out = ByteArray(length + length / 2 + 1)
        var size = 0
        var zeros = 0
        for (i in offset until offset + length) {
            val b = rbsp[i].toInt() and 0xFF
            if (zeros >= 2 && b <= 3) {
                out[size++] = 3
                zeros = 0
            }
            out[size++] = b.toByte()
            zeros = if (b == 0) zeros + 1 else 0
        }
        if (length > 0 && rbsp[offset + length - 1].toInt() == 0) out[size++] = 3
        return out.copyOf(size)
    }

    /** NAL 载荷 → RBSP：去掉跟在 `00 00` 后面的防竞争字节 `03`。 */
    fun unescape(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): ByteArray {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) { "范围非法" }
        val out = ByteArray(length)
        var size = 0
        var zeros = 0
        for (i in offset until offset + length) {
            val b = data[i].toInt() and 0xFF
            if (zeros >= 2 && b == 3) {
                zeros = 0
                continue
            }
            out[size++] = b.toByte()
            zeros = if (b == 0) zeros + 1 else 0
        }
        return out.copyOf(size)
    }
}
