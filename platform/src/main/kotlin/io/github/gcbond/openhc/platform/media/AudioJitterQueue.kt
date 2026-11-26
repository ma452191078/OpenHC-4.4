package io.github.gcbond.openhc.platform.media

import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 播放前的缓冲（纯逻辑，不引用安卓类，可在电脑上测）：生产者（协议线程）[offer]，播放线程 [take]。
 *
 * - **起播**：攒到 [startFrames] 才开始放出数据，之后来多少放多少；
 * - **积压（突发）**：放进来后超过 [maxFrames] 就从最旧的块丢起，丢到不超过 [targetFrames]；
 * - **起播突发**：起播（含放空后重新起播）后 [startWindowMillis] 之内，每次取数时队列高于 [targetFrames] 就直接
 *   从最旧的丢起压回目标（丢到不低于目标为止），不等下面的持续时间：手机开播时常一口气补发一批；
 * - **积压（稳态削峰）**：每次取走一块后看队列，仍高于 [targetFrames] 且已经持续 [trimHoldMillis]，就从最旧的丢起，
 *   每次最多丢约 [trimStepFrames]（至少一块），两次之间至少隔 [trimSpacingMillis]，一点点压回目标，免得听起来
 *   断一大截。看取走之后的低点，正常的一进一出不会触发；
 * - **欠载**：播放线程发现输出快放空了调 [onStarved]，回到攒数据的状态，重新攒够 [startFrames] 再放。
 *   手机暂停播放、网络卡顿都会走到这里。
 *
 * 帧（frame）指一个采样时刻的全部声道。线程安全。时间取自 [clockNanos]（单调时钟，测试里可以换）。
 */
class AudioJitterQueue(
    val startFrames: Int,
    val targetFrames: Int,
    val maxFrames: Int,
    val trimHoldMillis: Long = 2_000,
    val trimSpacingMillis: Long = 200,
    val trimStepFrames: Int = 1,
    val startWindowMillis: Long = 0,
    private val clockNanos: () -> Long = System::nanoTime,
) {
    class Chunk(val data: ByteArray, val frames: Int)

    private val lock = ReentrantLock()
    private val ready = lock.newCondition()
    private val chunks = ArrayDeque<Chunk>()
    private var buffered = 0L
    private var releasing = false
    private var closed = false
    private var dropped = 0L
    private var starved = 0
    private var trimmed = 0L
    private var trims = 0
    private var startTrimmed = 0L

    /** 最近一次起播（开始往外放）的时刻；起播窗口过了置 null。 */
    private var releasedAt: Long? = null

    /** 取走之后仍高于目标是从什么时候开始的；不高于目标时为 null。 */
    private var aboveSince: Long? = null
    private var lastTrim: Long? = null

    init {
        require(startFrames > 0) { "起播门槛必须为正" }
        require(startFrames <= targetFrames && targetFrames <= maxFrames) { "要求 起播门槛 ≤ 目标 ≤ 上限" }
        require(trimHoldMillis >= 0 && trimSpacingMillis >= 0 && startWindowMillis >= 0) { "削峰的时间不能为负" }
        require(trimStepFrames > 0) { "每次削峰的帧数必须为正" }
    }

    /** 还没被取走的帧数。 */
    val bufferedFrames: Long get() = lock.withLock { buffered }

    /** 已经攒够、正在往外放（false 表示在攒数据）。 */
    val isReleasing: Boolean get() = lock.withLock { releasing }

    /** 因为积压丢掉的帧数（累计）。 */
    val droppedFrames: Long get() = lock.withLock { dropped }

    /** 放空、重新攒数据的次数（累计）。 */
    val starvations: Int get() = lock.withLock { starved }

    /** 稳态削峰丢掉的帧数和块数（累计）。 */
    val trimmedFrames: Long get() = lock.withLock { trimmed }
    val trimmedChunks: Int get() = lock.withLock { trims }

    /** 起播窗口里压回目标丢掉的帧数（累计）。 */
    val startTrimmedFrames: Long get() = lock.withLock { startTrimmed }

    /** 放进一块（调用方已经复制好，之后不要再改它）。返回这一次因积压丢掉的帧数。关闭后什么都不做。 */
    fun offer(data: ByteArray, frames: Int): Long {
        require(frames > 0) { "帧数必须为正" }
        lock.withLock {
            if (closed) return 0L
            return add(Chunk(data, frames))
        }
    }

    private fun add(chunk: Chunk): Long {
        chunks.addLast(chunk)
        val frames = chunk.frames
        buffered += frames
        var droppedNow = 0L
        if (buffered > maxFrames) {
            // 从最旧的丢起，至少留下刚放进来的这一块。
            while (buffered > targetFrames && chunks.size > 1) {
                val old = chunks.removeFirst()
                buffered -= old.frames
                droppedNow += old.frames
            }
            dropped += droppedNow
        }
        if (!releasing && buffered >= startFrames) {
            releasing = true
            releasedAt = clockNanos()
        }
        if (releasing) ready.signalAll()
        return droppedNow
    }

    /**
     * 取下一块：还在攒数据或暂时没有数据时最多等 [timeoutMillis]，等不到返回 null。
     * [timeoutMillis] 为 0 时不等。关闭后返回 null。
     */
    fun take(timeoutMillis: Long): Chunk? {
        lock.withLock {
            var waitNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis.coerceAtLeast(0))
            while (!closed) {
                if (releasing && chunks.isNotEmpty()) {
                    trimStartBurst()
                    val chunk = chunks.removeFirst()
                    buffered -= chunk.frames
                    trimIfStuckHigh()
                    return chunk
                }
                if (waitNanos <= 0) return null
                waitNanos = ready.awaitNanos(waitNanos)
            }
            return null
        }
    }

    /** 输出放空了：停止往外放，重新攒够 [startFrames]。已经攒着的数据保留。 */
    fun onStarved() {
        lock.withLock {
            if (!releasing) return
            starved++
            releasing = buffered >= startFrames
            aboveSince = null
        }
    }

    /** 起播窗口里：高于目标就从最旧的丢起，丢到不低于目标为止（至少留一块）。只在持锁时调用。 */
    private fun trimStartBurst() {
        val since = releasedAt ?: return
        if (clockNanos() - since >= TimeUnit.MILLISECONDS.toNanos(startWindowMillis)) {
            releasedAt = null
            return
        }
        while (chunks.size > 1 && buffered - chunks.first.frames >= targetFrames) {
            val oldest = chunks.removeFirst()
            buffered -= oldest.frames
            startTrimmed += oldest.frames
        }
    }

    /** 取走一块之后：长期高于目标就从最旧的丢起，每次最多约 [trimStepFrames]（至少留一块）。只在持锁时调用。 */
    private fun trimIfStuckHigh() {
        if (buffered <= targetFrames) {
            aboveSince = null
            return
        }
        val now = clockNanos()
        val since = aboveSince ?: now.also { aboveSince = it }
        if (now - since < TimeUnit.MILLISECONDS.toNanos(trimHoldMillis)) return
        val last = lastTrim
        if (last != null && now - last < TimeUnit.MILLISECONDS.toNanos(trimSpacingMillis)) return
        if (chunks.size <= 1) return
        var droppedNow = 0L
        // 至少丢一块；之后再丢会超过每次的上限就停。
        while (chunks.size > 1 && buffered > targetFrames &&
            (droppedNow == 0L || droppedNow + chunks.first.frames <= trimStepFrames)
        ) {
            val oldest = chunks.removeFirst()
            buffered -= oldest.frames
            trimmed += oldest.frames
            trims++
            droppedNow += oldest.frames
        }
        lastTrim = now
    }

    /** 关闭：丢掉全部数据，唤醒等待的播放线程。 */
    fun close() {
        lock.withLock {
            closed = true
            chunks.clear()
            buffered = 0
            ready.signalAll()
        }
    }
}

/** 16 位 PCM 的字节处理（纯逻辑）。 */
object PcmBytes {
    /**
     * 复制 `data[offset, offset + length)` 里完整的帧（[frameBytes] 的整数倍，尾巴不足一帧的丢掉），
     * 按需把大端的 16 位采样换成小端（AudioTrack 要小端）。不足一帧返回空数组。
     */
    fun copyLittleEndian(data: ByteArray, offset: Int, length: Int, frameBytes: Int, bigEndian: Boolean): ByteArray {
        require(offset >= 0 && length >= 0 && offset <= data.size - length) { "范围非法" }
        require(frameBytes > 0 && frameBytes % 2 == 0) { "帧长必须是正的偶数" }
        val usable = length - length % frameBytes
        val copy = ByteArray(usable)
        if (!bigEndian) {
            System.arraycopy(data, offset, copy, 0, usable)
            return copy
        }
        var i = 0
        while (i < usable) {
            copy[i] = data[offset + i + 1]
            copy[i + 1] = data[offset + i]
            i += 2
        }
        return copy
    }
}
