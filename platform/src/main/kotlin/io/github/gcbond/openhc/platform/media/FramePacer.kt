package io.github.gcbond.openhc.platform.media

import kotlin.math.abs

/**
 * 上屏节拍：合成器每取一次帧最多拿到我们一帧新画面；同一次取帧之前到了第二帧，就把它顺延到下一次取帧。
 * 纯逻辑，不引用安卓类，只在解码线程上用；时间都是 System.nanoTime 的纳秒。
 *
 * ## 为什么要管
 * 系统合成器每次刷新前取一次帧：两次取帧之间交给 Surface 的帧只显示最后一帧，前面的直接丢掉（不显示，
 * 解码器的上屏回调里也没有它们）。出帧立即送显时，手机发帧、传输、解码各环节的抖动会让两帧偶尔落进同一次取帧
 * （前一帧被丢），前后某一次取帧又没有新帧（画面停一拍）：看起来就是跳帧，帧率越接近刷新率越明显。
 *
 * ## 怎么做
 * 取帧时刻 = 刷新时刻 − 送显提前量，按 [VsyncGrid] 推算，只用它对刷新周期取余的相位（见 [DisplayTiming]）。
 * 每一帧分到一次取帧（下称「拍」）：它最早能赶上的那一拍，和上一帧那一拍的下一拍，取较晚的。
 * 送出时刻取「分到的那一拍之前半个周期」（两次取帧的正中）和出帧时刻里较晚的；前一拍没有帧时直接送。
 * - 分到最早那一拍：显示时刻和立即送显一样。出帧在前半个周期里、前一拍又有帧时，在我们手里多扣一会儿再送，
 *   免得合成器实际取帧比推算晚一点时，把它和上一帧一起拿走（上一帧就被挤掉了）；
 * - 分到下一拍（晚一个周期）：扣着，到那一拍之前半个周期再送。离前后两次取帧各有半个周期的余量，
 *   定时器晚一点、合成器取帧早晚一点都不要紧；
 * - 要晚两拍以上才轮得到：不再往后排，顶替最后排上、还扣着的那一帧（那一帧不再显示）。
 *
 * 所以每帧最多比立即送显晚一个刷新周期，同一时刻最多扣着两帧（前一帧刚要送、这一帧排在下一拍）。
 * 帧率等于刷新率、每帧到达时刻的抖动幅度（最早和最晚之差）不到一个周期时，一帧都不用顶替；发帧均匀时一帧都不顺延。
 * 代价：顺延过一次之后，显示就跟着晚到的那一拍走，之后按时到的帧都晚一拍（晚到的帧不晚），直到手机停发一阵、
 * 或者手机出帧比屏幕刷新慢、自然空出一拍，才回到早的那一拍。要回得更早，只能再丢一帧或者让画面停一拍，
 * 那正是要避免的。所以平均多出的延迟在 0 到一个周期之间：晚到的帧越少见，越接近一个周期。
 * 统计里的「节拍多等」就是它。
 *
 * 用法：要上屏的帧先 [onFrame]；扣下的帧按 [nextReleaseAtNs] 定时，到点调 [onHeadDue] 后送掉队首那一帧；
 * 每次送出一帧之后，`shown*` 字段描述那一帧，给统计用。
 */
internal class FramePacer {

    /** 最后分到的那一拍（不管送没送出去）；没有为 [NONE]。 */
    private var lastSlotNs = NONE

    /** 上一帧出帧时落在哪次取帧，统计「同一次取帧来了两帧」用。 */
    private var lastArrivalLatchNs = NONE

    // 扣着的帧，按送出顺序：分到的那一拍、送出时刻、最早能赶上的那一拍、出帧时刻。
    private val queuedSlotNs = LongArray(MAX_QUEUED)
    private val queuedReleaseNs = LongArray(MAX_QUEUED)
    private val queuedEarliestNs = LongArray(MAX_QUEUED)
    private val queuedOutputNs = LongArray(MAX_QUEUED)

    /** 扣着几帧。 */
    var queued = 0
        private set

    /** 队首那一帧什么时候送；没扣着为 [NONE]。 */
    val nextReleaseAtNs: Long get() = if (queued > 0) queuedReleaseNs[0] else NONE

    /** 这一帧和上一帧出帧时落在同一次取帧：立即送显的话，上一帧会被合成器丢掉。 */
    var sameLatch = false
        private set

    /** 刚送出的那一帧的出帧时刻。 */
    var shownOutputNs = NONE
        private set

    /** 刚送出的那一帧会被哪次取帧取走（推算）。 */
    var shownLatchNs = NONE
        private set

    /** 刚送出的那一帧因为节拍多等的时间：0，或者一个刷新周期（定时器晚了才会更多）。 */
    var shownExtraNs = 0L
        private set

    /**
     * 解码器在 [nowNs] 交出一帧要上屏。[deadlineNs] 是送显提前量，只用它的相位。返回：
     * - [SHOW_NOW]：马上送；
     * - [HOLD]：扣下，排在队尾；
     * - [REPLACE]：顶替队尾那一帧（调用方把那一帧还给解码器、不显示），这一帧接替它的位置和送出时刻；
     * - [NO_GRID]：没有可用的刷新网格，马上送（扣着的帧调用方先送掉），不计节拍统计。
     *
     * [paced] 为 false 时总是 [SHOW_NOW]，只做统计；调用方要先把扣着的帧送掉。到点的队首也要先送掉再调用。
     */
    fun onFrame(nowNs: Long, grid: VsyncGrid, deadlineNs: Long, paced: Boolean): Int {
        sameLatch = false
        if (!grid.isUsable(nowNs)) {
            lastSlotNs = NONE
            lastArrivalLatchNs = NONE
            return NO_GRID
        }
        val period = grid.periodNs
        val earliest = latchAtOrAfter(grid, deadlineNs, nowNs)
        sameLatch = lastArrivalLatchNs != NONE && abs(earliest - lastArrivalLatchNs) < period / 2
        lastArrivalLatchNs = earliest
        val last = lastSlotNs
        if (!paced || last == NONE) {
            lastSlotNs = earliest
            markShown(nowNs, earliest, earliest)
            return SHOW_NOW
        }
        // 分到的那一拍：最早能赶上的，和上一帧那一拍的下一拍，取较晚的。
        val slot = if (earliest - last >= period / 2) earliest else latchAtOrAfter(grid, deadlineNs, last + period / 2)
        if (slot - earliest >= period + period / 2 || queued == MAX_QUEUED) {
            if (queued > 0) {
                // 要晚两拍以上（或者已经扣满）：顶替队尾，接替它的那一拍和送出时刻。
                val tail = queued - 1
                queuedEarliestNs[tail] = earliest
                queuedOutputNs[tail] = nowNs
                return REPLACE
            }
            // 要顶替的帧已经送出去了（只会在网格突变时出现）：退回马上送。
            lastSlotNs = earliest
            markShown(nowNs, earliest, earliest)
            return SHOW_NOW
        }
        // 前一拍有帧（上一帧就在紧挨着的那一拍）时，最早在两次取帧的正中送：合成器实际取帧比推算晚一点、
        // 定时器晚一点，都不会让这一帧和上一帧被同一次取帧拿走。显示时刻不变，只是在我们手里多扣一会儿。
        val releaseAt = if (slot - last < period + period / 2) slot - period / 2 else nowNs
        lastSlotNs = slot
        if (releaseAt <= nowNs && queued == 0) {
            markShown(nowNs, slot, earliest)
            return SHOW_NOW
        }
        push(slot, maxOf(releaseAt, nowNs), earliest, nowNs)
        return HOLD
    }

    /** 队首那一帧到点了（或者要提前送掉）：出队，调用方随即把它送显。 */
    fun onHeadDue(nowNs: Long, grid: VsyncGrid, deadlineNs: Long) {
        check(queued > 0) { "没有扣着的帧" }
        val planned = queuedSlotNs[0]
        val earliest = queuedEarliestNs[0]
        val output = queuedOutputNs[0]
        for (i in 1 until queued) {
            queuedSlotNs[i - 1] = queuedSlotNs[i]
            queuedReleaseNs[i - 1] = queuedReleaseNs[i]
            queuedEarliestNs[i - 1] = queuedEarliestNs[i]
            queuedOutputNs[i - 1] = queuedOutputNs[i]
        }
        queued--
        // 定时器晚了、送出时已经赶不上分到的那一拍，就按实际赶上的那一拍算。
        val latch = if (grid.isUsable(nowNs)) maxOf(planned, latchAtOrAfter(grid, deadlineNs, nowNs)) else planned
        if (latch > lastSlotNs || lastSlotNs == NONE) lastSlotNs = latch
        markShown(output, latch, earliest)
    }

    /** 扣着的帧全部作废（解码器 flush 了、换了，输出切到看不见的地方）：忘掉它们，节拍从头来。 */
    fun reset() {
        queued = 0
        lastSlotNs = NONE
        lastArrivalLatchNs = NONE
        sameLatch = false
    }

    private fun push(slotNs: Long, releaseNs: Long, earliestNs: Long, outputNs: Long) {
        queuedSlotNs[queued] = slotNs
        queuedReleaseNs[queued] = releaseNs
        queuedEarliestNs[queued] = earliestNs
        queuedOutputNs[queued] = outputNs
        queued++
    }

    private fun markShown(outputNs: Long, latchNs: Long, earliestLatchNs: Long) {
        shownOutputNs = outputNs
        shownLatchNs = latchNs
        shownExtraNs = (latchNs - earliestLatchNs).coerceAtLeast(0)
    }

    companion object {
        /** 马上送显。 */
        const val SHOW_NOW = 0

        /** 扣下，排在队尾，到 [nextReleaseAtNs] 轮到它时再送。 */
        const val HOLD = 1

        /** 顶替队尾那一帧：那一帧作废（不显示），这一帧接替它的位置和送出时刻。 */
        const val REPLACE = 2

        /** 还没有可用的刷新网格：马上送显，不计节拍统计。 */
        const val NO_GRID = 3

        /** 同一时刻最多扣着几帧：每帧最多晚一拍，所以排着的帧最多占两拍。 */
        const val MAX_QUEUED = 2

        const val NONE = Long.MIN_VALUE

        /** [timeNs] 以后（含）合成器第一次取帧的时刻：刷新时刻减去送显提前量。 */
        fun latchAtOrAfter(grid: VsyncGrid, deadlineNs: Long, timeNs: Long): Long =
            grid.refreshAtOrAfter(timeNs + deadlineNs) - deadlineNs
    }
}
