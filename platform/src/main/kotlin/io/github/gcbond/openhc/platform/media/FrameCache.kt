package io.github.gcbond.openhc.platform.media

import java.nio.ByteBuffer

/**
 * 解码链缓存：从最近一个能开始解码的帧（关键帧）起，按顺序存下要送进解码器的每个访问单元
 * （含中途单独发来的参数集）。两个用处：
 * - **等输入缓冲的队列**：解码器暂时没有空闲输入缓冲时，帧留在这里，拿到缓冲再按顺序补送，不丢；
 * - **重放**：画面换到新的 Surface 后（新 Surface 上什么都没有，手机画面不变时又不发新帧），
 *   把已经送过的帧从段首起再送一遍，恢复出最后一帧。
 *
 * 纯逻辑，不引用安卓类，不是线程安全的（调用方加锁）。
 *
 * ## 存放
 * 数据按顺序紧挨着存进一块大数组，不够时翻倍扩容（最多 [maxBytes]），整个对象用多久就复用多久，新的一段从数组开头写起。
 * 前面的条目丢掉后空出来的地方，写到数组末尾时挪一次（只挪还留着的条目）。每个条目的位置、长度等存在
 * [maxFrames] 个槽的环里。追加只复制数据，不分配。
 *
 * ## 序号与几个位置
 * 条目按追加顺序编号，开始新的一段时序号接着往上数，不回头。
 * - `[firstSeq, cursor)`：这一遍已经送进解码器的；
 * - `[cursor, endSeq)`：还要送的，[nextToFeed] 依次给出，送完调 [markFed]；
 * - 序号小于 [deliveredEnd] 的条目以前送过，再送就是重放（[isReplay]）。
 *
 * ## 上限
 * 条目数超过 [maxFrames] 或字节数超过 [maxBytes] 时，先丢掉已经送过的条目：到下一段之前都不能重放
 * （[historyComplete] 为 false），之后送过的条目随即丢掉，只留还要送的。只剩还要送的条目仍然放不下时，
 * [append] 返回 [REJECTED]。
 */
internal class FrameCache(
    val maxBytes: Int,
    val maxFrames: Int,
    private val initialBytes: Int = DEFAULT_INITIAL_BYTES,
) {
    init {
        require(maxBytes > 0 && maxFrames > 0) { "缓存上限必须是正数" }
        require(initialBytes > 0) { "初始大小必须是正数" }
    }

    private var storage = EMPTY
    private val offsets = IntArray(maxFrames)
    private val lengths = IntArray(maxFrames)
    private val configFlags = BooleanArray(maxFrames)
    private val arrivals = LongArray(maxFrames)
    private val streamTimestamps = LongArray(maxFrames)

    /** 正在缓存一段：从 [startChain] 起，到 [clear] 为止。 */
    var active = false
        private set

    /** 留着的条目从段首起一个没丢，可以重放。 */
    var historyComplete = false
        private set

    var firstSeq = 0L
        private set
    var cursor = 0L
        private set
    var deliveredEnd = 0L
        private set
    var endSeq = 0L
        private set

    /** 最近一次 [rewind] 时已经送过的最后一帧（不算参数集），重放时上屏的就是它；没有为 -1。 */
    var replayLastSeq = NONE
        private set

    private var startOffset = 0
    private var writeOffset = 0
    private var pendingSinceMs = 0L

    val keptEntries: Int get() = (endSeq - firstSeq).toInt()
    val keptBytes: Int get() = writeOffset - startOffset

    /** 还要送的条目数（含要重放的）。 */
    val pendingEntries: Int get() = (endSeq - cursor).toInt()

    /** 存数据的数组现在多大。 */
    val capacity: Int get() = storage.size

    /** 能重放的帧数（已经送过的，不算参数集）；不能重放（没在缓存、或者超过上限丢过）为 -1。 */
    val replayableFrames: Int
        get() = if (active && historyComplete) countFrames(firstSeq, deliveredEnd) else -1

    /** 开始新的一段（收到能开始解码的帧时）：之前的条目全部作废。返回作废的条目里从没送过的帧数（算丢帧）。 */
    fun startChain(): Int {
        val lost = undeliveredFrames()
        dropAll()
        active = true
        historyComplete = true
        return lost
    }

    /**
     * 追加一个条目，复制 `data[offset, offset + length)`，调用返回后 [data] 可以随便改。
     * [config] 表示只有参数集（送进解码器时要标成配置数据）。[delivered] 为 true 时直接记为已经送过
     * （解码器手里已经有这份数据，只为重放留着），只能在没有待送条目时用。
     * 返回 [APPENDED]、[APPENDED_HISTORY_DROPPED]（这次超了上限，丢掉了已经送过的部分）或 [REJECTED]（放不下，没追加）。
     */
    fun append(
        data: ByteArray,
        offset: Int,
        length: Int,
        config: Boolean,
        nowMs: Long,
        streamPtsUs: Long = -1,
        delivered: Boolean = false,
    ): Int {
        check(active) { "还没开始缓存一段" }
        require(offset >= 0 && length >= 0 && offset <= data.size - length) { "范围非法" }
        require(!delivered || cursor == endSeq) { "前面还有没送的条目，不能直接记为已送" }
        var result = APPENDED
        if (!fits(length)) {
            if (cursor > firstSeq) {
                dropBefore(cursor)
                if (historyComplete) {
                    historyComplete = false
                    result = APPENDED_HISTORY_DROPPED
                }
            }
            if (!fits(length)) return REJECTED
        }
        ensureRoom(length)
        val slot = slotFor(endSeq)
        data.copyInto(storage, writeOffset, offset, offset + length)
        offsets[slot] = writeOffset
        lengths[slot] = length
        configFlags[slot] = config
        arrivals[slot] = nowMs
        streamTimestamps[slot] = streamPtsUs
        writeOffset += length
        if (cursor == endSeq && !delivered) pendingSinceMs = nowMs
        endSeq++
        if (delivered) {
            cursor = endSeq
            if (deliveredEnd < cursor) deliveredEnd = cursor
            if (!historyComplete) dropBefore(cursor)
        }
        return result
    }

    /** 下一个要送的条目的序号，没有为 -1。 */
    fun nextToFeed(): Long = if (cursor < endSeq) cursor else NONE

    /** [nextToFeed] 给出的条目已经送进解码器。 */
    fun markFed(nowMs: Long) {
        check(cursor < endSeq) { "没有要送的条目" }
        cursor++
        if (deliveredEnd < cursor) deliveredEnd = cursor
        // 解码器收了一个，下一个从现在开始等。
        if (cursor < endSeq) pendingSinceMs = nowMs
        if (!historyComplete) dropBefore(cursor)
    }

    /**
     * 从段首起重送（重放）。返回会重放的帧数（不算参数集），不能重放（没在缓存、或者超过上限丢过）返回 -1，
     * 这时什么都不变。重放完接着送还没送过的条目。
     */
    fun rewind(nowMs: Long): Int {
        if (!active || !historyComplete) return -1
        cursor = firstSeq
        if (cursor < endSeq) pendingSinceMs = nowMs
        replayLastSeq = lastFrameBefore(deliveredEnd)
        return countFrames(firstSeq, deliveredEnd)
    }

    /** 作废全部条目，停止缓存（解码器释放、要等关键帧时）。返回其中从没送过的帧数（算丢帧）。 */
    fun clear(): Int {
        val lost = undeliveredFrames()
        dropAll()
        active = false
        historyComplete = false
        return lost
    }

    /** [clear] 并放掉存数据的数组。 */
    fun release() {
        clear()
        storage = EMPTY
    }

    /** 有条目在等着送时，解码器已经多久没收（毫秒）：从上次收下一个、或者队首条目到达起算；没有在等的为 0。 */
    fun inputWaitMs(nowMs: Long): Long = if (cursor < endSeq) (nowMs - pendingSinceMs).coerceAtLeast(0) else 0

    fun isReplay(seq: Long): Boolean {
        slotOf(seq)
        return seq < deliveredEnd
    }

    fun length(seq: Long): Int = lengths[slotOf(seq)]

    fun isConfig(seq: Long): Boolean = configFlags[slotOf(seq)]

    fun arrivalMs(seq: Long): Long = arrivals[slotOf(seq)]

    /** 追加时给的码流时间戳，没给为负数。 */
    fun streamPtsUs(seq: Long): Long = streamTimestamps[slotOf(seq)]

    /** 把条目的数据写进 [dest] 的当前位置。 */
    fun copyTo(seq: Long, dest: ByteBuffer) {
        val slot = slotOf(seq)
        dest.put(storage, offsets[slot], lengths[slot])
    }

    private fun slotOf(seq: Long): Int {
        require(seq in firstSeq until endSeq) { "序号 $seq 不在缓存里" }
        return slotFor(seq)
    }

    private fun slotFor(seq: Long): Int = (seq % maxFrames).toInt()

    private fun fits(length: Int): Boolean = keptEntries < maxFrames && keptBytes.toLong() + length <= maxBytes

    private fun countFrames(from: Long, to: Long): Int {
        var count = 0
        var seq = maxOf(from, firstSeq)
        val end = minOf(to, endSeq)
        while (seq < end) {
            if (!configFlags[slotFor(seq)]) count++
            seq++
        }
        return count
    }

    private fun undeliveredFrames(): Int = countFrames(deliveredEnd, endSeq)

    private fun lastFrameBefore(end: Long): Long {
        var seq = minOf(end, endSeq) - 1
        while (seq >= firstSeq) {
            if (!configFlags[slotFor(seq)]) return seq
            seq--
        }
        return NONE
    }

    private fun dropAll() {
        firstSeq = endSeq
        cursor = endSeq
        deliveredEnd = endSeq
        startOffset = 0
        writeOffset = 0
        replayLastSeq = NONE
    }

    private fun dropBefore(seq: Long) {
        firstSeq = seq
        if (firstSeq == endSeq) {
            startOffset = 0
            writeOffset = 0
        } else {
            startOffset = offsets[slotFor(firstSeq)]
        }
    }

    /** 保证数组末尾还能写下 [length] 字节（调用前已经确认不超过上限）：先挪，挪了也不够再翻倍扩容。 */
    private fun ensureRoom(length: Int) {
        if (writeOffset.toLong() + length <= storage.size) return
        val needed = keptBytes + length
        if (needed <= storage.size) {
            compactInto(storage)
            return
        }
        var size = maxOf(storage.size, minOf(initialBytes, maxBytes))
        while (size < needed) size = if (size > maxBytes / 2) maxBytes else size * 2
        val grown = ByteArray(size)
        compactInto(grown)
        storage = grown
    }

    /** 把留着的条目挪到 [target] 开头（[target] 可以就是现在的数组）。 */
    private fun compactInto(target: ByteArray) {
        val shift = startOffset
        if (writeOffset > startOffset) storage.copyInto(target, 0, startOffset, writeOffset)
        var seq = firstSeq
        while (seq < endSeq) {
            offsets[slotFor(seq)] -= shift
            seq++
        }
        writeOffset -= shift
        startOffset = 0
    }

    companion object {
        const val APPENDED = 0
        const val APPENDED_HISTORY_DROPPED = 1
        const val REJECTED = 2

        const val NONE = -1L

        /** 第一次写入时数组的大小，之后按需翻倍。 */
        const val DEFAULT_INITIAL_BYTES = 1 shl 20

        private val EMPTY = ByteArray(0)
    }
}
