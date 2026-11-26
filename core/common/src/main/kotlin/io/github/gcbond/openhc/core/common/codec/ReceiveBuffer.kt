package io.github.gcbond.openhc.core.common.codec

/**
 * TCP 收包缓冲：一次读到的字节可能是半帧，也可能粘着好几帧。先 [append] 攒起来，拆帧器按自己的格式
 * 判断够不够一帧，够了就 [copy] 出来再 [consume] 掉，不够就等下一次 [append]。
 *
 * - 有效数据在 [array] 的 `[start, end)`。拆帧器可以直接在 [array] 上读（算校验、解变长整数时免拷贝），
 *   但 [append] 可能换成更大的数组或把数据搬到开头，之后要重新取 [array] 和 [start]。
 * - 下标参数（[byteAt]、[copy] 等）都相对于 [start]，即第 0 个是最早收到、还没取走的字节。
 * - 空间不够时先把剩余数据搬到开头，还不够就按两倍扩容；不会自动缩小。取空时读写位置回到开头。
 *
 * 不是线程安全的：同一实例只能在一个线程里用。
 */
class ReceiveBuffer(initialCapacity: Int = DEFAULT_CAPACITY) {
    private var buf: ByteArray

    init {
        require(initialCapacity > 0) { "初始容量必须为正: $initialCapacity" }
        buf = ByteArray(initialCapacity)
    }

    /** 底层数组，有效数据在 `[start, end)`。只读，下一次 [append] 之后可能换成别的数组。 */
    val array: ByteArray get() = buf

    /** 第一个未取走字节在 [array] 里的位置。 */
    var start: Int = 0
        private set

    /** 最后一个有效字节之后的位置。 */
    var end: Int = 0
        private set

    /** 还没取走的字节数。 */
    val size: Int get() = end - start

    /** 追加 `data[offset, offset + length)`。范围越界抛 [IllegalArgumentException]。 */
    fun append(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        require(offset >= 0 && length >= 0 && offset <= data.size - length) {
            "范围非法: offset=$offset length=$length size=${data.size}"
        }
        if (length == 0) return
        if (length > buf.size - end) makeRoom(length)
        System.arraycopy(data, offset, buf, end, length)
        end += length
    }

    /** 第 [index] 个未取走的字节（0..255）。[index] 不在 0 until [size] 时抛 [IndexOutOfBoundsException]。 */
    fun byteAt(index: Int): Int {
        checkIndex(index, 1)
        return buf[start + index].toInt() and 0xFF
    }

    /** 从第 [index] 个字节起的小端 32 位无符号整数。不足 4 字节时抛 [IndexOutOfBoundsException]。 */
    fun u32Le(index: Int): Long {
        checkIndex(index, 4)
        var v = 0L
        for (i in 3 downTo 0) v = (v shl 8) or (buf[start + index + i].toLong() and 0xFF)
        return v
    }

    /** 从第 [index] 个字节起的大端 32 位无符号整数。不足 4 字节时抛 [IndexOutOfBoundsException]。 */
    fun u32Be(index: Int): Long {
        checkIndex(index, 4)
        var v = 0L
        for (i in 0 until 4) v = (v shl 8) or (buf[start + index + i].toLong() and 0xFF)
        return v
    }

    /**
     * 复制出从第 [index] 个字节起的 [length] 个字节，不取走。[length] 为负抛 [IllegalArgumentException]，
     * 超出已有数据抛 [IndexOutOfBoundsException]。
     */
    fun copy(index: Int, length: Int): ByteArray {
        require(length >= 0) { "长度不能为负: $length" }
        checkIndex(index, length)
        return buf.copyOfRange(start + index, start + index + length)
    }

    /** 取走最前面的 [count] 个字节。超过 [size] 抛 [IllegalArgumentException]。 */
    fun consume(count: Int) {
        require(count in 0..size) { "取走的字节数非法: $count（缓冲里有 $size 字节）" }
        start += count
        if (start == end) clear()
    }

    /** 丢掉全部未取走的字节。 */
    fun clear() {
        start = 0
        end = 0
    }

    private fun makeRoom(length: Int) {
        val live = size
        val needed = live + length
        check(needed >= 0) { "接收缓冲超过上限" }
        if (needed <= buf.size) {
            System.arraycopy(buf, start, buf, 0, live)
        } else {
            var cap = buf.size
            while (cap < needed) cap = if (cap > Int.MAX_VALUE / 2) needed else cap * 2
            val grown = ByteArray(cap)
            System.arraycopy(buf, start, grown, 0, live)
            buf = grown
        }
        start = 0
        end = live
    }

    private fun checkIndex(index: Int, length: Int) {
        if (index < 0 || length < 0 || index > size - length) {
            throw IndexOutOfBoundsException("下标越界: index=$index length=$length（缓冲里有 $size 字节）")
        }
    }

    companion object {
        /** 默认初始容量（字节）。 */
        const val DEFAULT_CAPACITY = 256
    }
}
