package io.github.gcbond.openhc.platform.media

import org.junit.Test
import java.util.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 上屏节拍：单帧的决定，以及「给定出帧时刻和刷新序列，哪帧在哪次取帧上屏」的整体模拟。
 *
 * 模拟里的合成器（[composite]）按系统公开的行为建模：每次取帧拿走上次取帧以后送来的帧里最后送的那一帧，
 * 其余挤掉；没有新帧就接着显示原来那一帧。时间全是合成值：刷新周期 [P]，送显提前量 [D]。
 */
class FramePacerTest {

    // ---------------------------------------------------------------- 单帧的决定

    @Test
    fun evenFramesAreShownRightAwayWithoutExtraDelay() {
        val grid = gridWithSample()
        val pacer = FramePacer()
        for (k in 0 until 5) {
            // 每帧在取帧前三分之一个周期到：前一拍已经过去一大半，马上送。
            val t = latch(20 + k) - P / 3
            assertEquals(FramePacer.SHOW_NOW, pacer.onFrame(t, grid, D, paced = true))
            assertEquals(latch(20 + k), pacer.shownLatchNs)
            assertEquals(0, pacer.shownExtraNs)
            assertFalse(pacer.sameLatch)
        }
        assertEquals(0, pacer.queued)
    }

    @Test
    fun secondFrameBeforeTheSameLatchWaitsOneRefresh() {
        val grid = gridWithSample()
        val pacer = FramePacer()
        assertEquals(FramePacer.SHOW_NOW, pacer.onFrame(latch(20) - P / 3, grid, D, paced = true))
        val second = latch(20) - P / 6
        assertEquals(FramePacer.HOLD, pacer.onFrame(second, grid, D, paced = true))
        assertTrue(pacer.sameLatch, "两帧赶上同一次取帧")
        assertEquals(latch(21) - P / 2, pacer.nextReleaseAtNs, "两次取帧的正中送")
        pacer.onHeadDue(pacer.nextReleaseAtNs, grid, D)
        assertEquals(latch(21), pacer.shownLatchNs)
        assertEquals(P, pacer.shownExtraNs, "晚一个刷新周期")
        assertEquals(second, pacer.shownOutputNs)
        assertEquals(0, pacer.queued)
    }

    @Test
    fun frameRightAfterTheLatchIsHeldUntilMidpointWithoutExtraDelay() {
        // 前一拍有帧、这一帧刚过取帧时刻就到：显示时刻不变，只是等到正中再送，防合成器实际取帧偏晚。
        val grid = gridWithSample()
        val pacer = FramePacer()
        pacer.onFrame(latch(20) - P / 3, grid, D, paced = true)
        val t = latch(20) + P / 10
        assertEquals(FramePacer.HOLD, pacer.onFrame(t, grid, D, paced = true))
        assertFalse(pacer.sameLatch)
        assertEquals(latch(21) - P / 2, pacer.nextReleaseAtNs)
        pacer.onHeadDue(pacer.nextReleaseAtNs, grid, D)
        assertEquals(latch(21), pacer.shownLatchNs)
        assertEquals(0, pacer.shownExtraNs)
    }

    @Test
    fun frameAfterAnIdleRefreshIsShownRightAway() {
        val grid = gridWithSample()
        val pacer = FramePacer()
        pacer.onFrame(latch(20) - P / 3, grid, D, paced = true)
        // 中间空了一拍：前一拍没有帧，不用等。
        assertEquals(FramePacer.SHOW_NOW, pacer.onFrame(latch(21) + P / 10, grid, D, paced = true))
        assertEquals(latch(22), pacer.shownLatchNs)
    }

    @Test
    fun deferredRunKeepsTwoFramesQueuedWithoutDroppingAny() {
        val grid = gridWithSample()
        val pacer = FramePacer()
        pacer.onFrame(latch(20) - P / 3, grid, D, paced = true) // 第 20 拍
        assertEquals(FramePacer.HOLD, pacer.onFrame(latch(20) - P / 6, grid, D, paced = true)) // 顺延到第 21 拍
        // 第 21 拍之前又到一帧（它最早就是第 21 拍，已经被占了）：排到第 22 拍，同时扣着两帧。
        assertEquals(FramePacer.HOLD, pacer.onFrame(latch(20) + P / 10, grid, D, paced = true))
        assertEquals(2, pacer.queued)
        pacer.onHeadDue(pacer.nextReleaseAtNs, grid, D)
        assertEquals(latch(21), pacer.shownLatchNs)
        assertEquals(latch(22) - P / 2, pacer.nextReleaseAtNs)
        pacer.onHeadDue(pacer.nextReleaseAtNs, grid, D)
        assertEquals(latch(22), pacer.shownLatchNs)
        assertEquals(P, pacer.shownExtraNs)
    }

    @Test
    fun thirdFrameBeforeTheSameLatchReplacesTheQueuedOne() {
        val grid = gridWithSample()
        val pacer = FramePacer()
        pacer.onFrame(latch(20) - P / 2, grid, D, paced = true)
        assertEquals(FramePacer.HOLD, pacer.onFrame(latch(20) - P / 3, grid, D, paced = true))
        // 再排就要晚两拍：顶替扣着的那一帧，接替它的第 21 拍。
        val third = latch(20) - P / 6
        assertEquals(FramePacer.REPLACE, pacer.onFrame(third, grid, D, paced = true))
        assertTrue(pacer.sameLatch)
        assertEquals(1, pacer.queued)
        pacer.onHeadDue(pacer.nextReleaseAtNs, grid, D)
        assertEquals(third, pacer.shownOutputNs, "送出的是顶替上来的那一帧")
        assertEquals(latch(21), pacer.shownLatchNs)
    }

    @Test
    fun lateTimerCountsTheRefreshActuallyMade() {
        val grid = gridWithSample()
        val pacer = FramePacer()
        pacer.onFrame(latch(20) - P / 3, grid, D, paced = true)
        pacer.onFrame(latch(20) - P / 6, grid, D, paced = true)
        // 定时器晚到第 21 拍之后才送：只能赶上第 22 拍。
        pacer.onHeadDue(latch(21) + P / 4, grid, D)
        assertEquals(latch(22), pacer.shownLatchNs)
        assertEquals(2 * P, pacer.shownExtraNs)
    }

    @Test
    fun withPacingOffEveryFrameGoesRightAwayButSameLatchIsStillCounted() {
        val grid = gridWithSample()
        val pacer = FramePacer()
        assertEquals(FramePacer.SHOW_NOW, pacer.onFrame(latch(20) - P / 3, grid, D, paced = false))
        assertEquals(FramePacer.SHOW_NOW, pacer.onFrame(latch(20) - P / 6, grid, D, paced = false))
        assertTrue(pacer.sameLatch, "立即送显时前一帧会被挤掉")
        assertEquals(0, pacer.queued)
    }

    @Test
    fun withoutAGridFramesGoRightAway() {
        val pacer = FramePacer()
        assertEquals(FramePacer.NO_GRID, pacer.onFrame(T0, VsyncGrid(P), D, paced = true), "还没有刷新采样")
        val grid = gridWithSample()
        assertEquals(FramePacer.NO_GRID, pacer.onFrame(T0 + 2 * VsyncGrid.STALE_NS, grid, D, paced = true), "采样太旧")
    }

    @Test
    fun resetForgetsQueuedFramesAndTheLastSlot() {
        val grid = gridWithSample()
        val pacer = FramePacer()
        pacer.onFrame(latch(20) - P / 3, grid, D, paced = true)
        pacer.onFrame(latch(20) - P / 6, grid, D, paced = true)
        assertEquals(1, pacer.queued)
        pacer.reset()
        assertEquals(0, pacer.queued)
        assertEquals(FramePacer.NONE, pacer.nextReleaseAtNs)
        assertEquals(FramePacer.SHOW_NOW, pacer.onFrame(latch(20) - P / 8, grid, D, paced = true), "从头来：不再顺延")
    }

    // ---------------------------------------------------------------- 整体模拟

    @Test
    fun periodicLateFrameDropsAFrameWhenImmediateButNotWhenPaced() {
        // 每帧在取帧前 0.3 个周期到，每第 4 帧晚到半个周期（越过了那次取帧）。
        val outputs = LongArray(120) { k -> latch(10 + k) + P * 7 / 10 + if (k % 4 == 3) P / 2 else 0 }
        val immediate = simulate(outputs, paced = false)
        // 每个晚到的帧和下一帧挤进同一次取帧，晚到的那一帧被挤掉（最后一个晚到的帧后面没有帧了）。
        assertEquals(29, immediate.dropped)
        assertEquals(immediate.dropped, immediate.sameLatch, "推算的「两帧同刷新」就是被挤掉的帧数")
        assertEquals(30, immediate.repeats, "每个晚到的帧错过的那一拍都没有新画面")

        val paced = simulate(outputs, paced = true)
        assertEquals(0, paced.dropped)
        assertEquals(0, paced.replaced)
        assertEquals(1, paced.maxLateLatches, "每帧最多晚一拍")
        assertEquals(1, paced.repeats, "只有第一个晚到的帧那一拍停了一下，之后每拍都有新画面")
    }

    @Test
    fun jitterWithinOnePeriodNeverDropsWhenPaced() {
        // 60 帧对 60Hz，名义上紧贴着取帧时刻到，每帧在 ±0.45 个周期内随机抖（最早和最晚相差不到一个周期），
        // 所以常常一帧刚好赶上、下一帧刚好错过。
        val random = Random(20261119)
        val outputs = LongArray(600) { k -> latch(10 + k) + P / 20 + ((random.nextDouble() * 2 - 1) * 0.45 * P).toLong() }
        val immediate = simulate(outputs, paced = false)
        assertTrue(immediate.dropped > 60, "立即送显时抖动挤掉不少帧：${immediate.dropped}")
        assertEquals(immediate.dropped, immediate.sameLatch)

        val paced = simulate(outputs, paced = true)
        assertEquals(0, paced.dropped)
        assertEquals(0, paced.replaced)
        assertEquals(1, paced.maxLateLatches)
        assertTrue(paced.repeats < immediate.repeats, "停拍也少了：${paced.repeats} < ${immediate.repeats}")
    }

    @Test
    fun pacingToleratesTheCompositorLatchingLaterThanEstimated() {
        // 合成器实际取帧比推算晚 0.3 个周期（负载高、合成器醒得晚），送出的帧仍然一拍一帧。
        val random = Random(7)
        val outputs = LongArray(600) { k -> latch(10 + k) + P / 20 + ((random.nextDouble() * 2 - 1) * 0.45 * P).toLong() }
        val paced = simulate(outputs, paced = true, trueLatchShiftNs = 3 * P / 10)
        assertEquals(0, paced.dropped)
        // 扣着的帧晚送 0.3 个周期也一样。
        assertEquals(0, simulate(outputs, paced = true, timerLateNs = 3 * P / 10).dropped)
    }

    @Test
    fun aBurstOfThreeFramesReplacesOneInsteadOfFallingTwoRefreshesBehind() {
        // 均匀出帧，中间第 50～52 帧挤在同一个取帧周期里到（例如网络卡了一下，一次收到三帧），后面接着均匀。
        val outputs = LongArray(100) { k ->
            when (k) {
                50 -> latch(61) - P / 2
                51 -> latch(61) - P / 3
                52 -> latch(61) - P / 6
                else -> latch(10 + k) - P / 3
            }
        }
        val paced = simulate(outputs, paced = true)
        assertEquals(1, paced.replaced)
        assertEquals(1, paced.dropped, "只丢被顶替的那一帧")
        assertEquals(1, paced.maxLateLatches)
        val immediate = simulate(outputs, paced = false)
        assertEquals(2, immediate.dropped, "立即送显时三帧里只剩最后一帧")
    }

    @Test
    fun sourceFasterThanTheDisplayStillLosesFramesButLatencyStaysBounded() {
        // 手机 61 帧对 60Hz 屏：屏幕一秒只能换 60 次画面，多出来的帧总要丢，节拍只保证不越积越多。
        val interval = 1_000_000_000L / 61
        val outputs = LongArray(610) { k -> latch(10) + P / 2 + k * interval }
        val paced = simulate(outputs, paced = true)
        val immediate = simulate(outputs, paced = false)
        assertTrue(paced.dropped in 9..11, "约每秒丢一帧：${paced.dropped}")
        assertTrue(immediate.dropped in 9..11, "立即送显也一样：${immediate.dropped}")
        assertEquals(1, paced.maxLateLatches)
    }

    @Test
    fun framesSlowerThanTheRefreshAreNeverHeldBack() {
        // 30 帧对 60Hz：每帧之间空一拍，就算有 ±0.4 个周期的抖动，也一帧不扣、一帧不丢。
        val random = Random(30)
        val outputs = LongArray(300) { k -> latch(10 + 2 * k) + P / 2 + ((random.nextDouble() * 2 - 1) * 0.4 * P).toLong() }
        val paced = simulate(outputs, paced = true)
        assertEquals(0, paced.dropped)
        assertEquals(0, paced.maxLateLatches)
    }

    @Test
    fun wrongPhaseEstimateIsNeverWorseThanImmediate() {
        // 推算的取帧相位和合成器实际的差 −0.45～+0.45 个周期：节拍丢的帧不会比立即送显多。
        // 实际取帧偏晚（合成器醒得晚、处理慢，最常见）时节拍照样一帧不丢；偏早时可能完全没起作用，退回立即送显的样子。
        val scenarios = listOf(P / 20 to 0.45, P / 2 to 0.45, P / 4 to 0.3)
        for ((phase, amplitude) in scenarios) {
            val random = Random(phase)
            val outputs = LongArray(600) { k -> latch(10 + k) + phase + ((random.nextDouble() * 2 - 1) * amplitude * P).toLong() }
            for (step in -9..9) {
                val shift = step * P / 20
                val immediate = simulate(outputs, paced = false, trueLatchShiftNs = shift).dropped
                val paced = simulate(outputs, paced = true, trueLatchShiftNs = shift).dropped
                assertTrue(paced <= immediate, "相位 $phase 抖动 $amplitude 偏差 ${step * 5}%：节拍 $paced > 立即 $immediate")
                if (step >= 0) assertEquals(0, paced, "相位 $phase 抖动 $amplitude 偏晚 ${step * 5}%")
            }
        }
    }

    // ---------------------------------------------------------------- 模拟用的工具

    private class Release(val atNs: Long, val frame: Int)

    private class Run(
        /** 每一帧在第几次取帧被拿去显示，被挤掉为 -1。 */
        val shown: IntArray,
        /** 每一帧最早能赶上第几次取帧（按出帧时刻、合成器实际取帧的时刻算）。 */
        val earliest: IntArray,
        /** 节拍推算的「和上一帧落在同一次取帧」的帧数。 */
        val sameLatch: Int,
        val replaced: Int,
    ) {
        val dropped: Int get() = shown.count { it < 0 }

        /** 显示出来的帧比它最早能赶上的那一次晚了几次取帧，最多是多少。 */
        val maxLateLatches: Int get() = shown.indices.filter { shown[it] >= 0 }.maxOf { shown[it] - earliest[it] }

        /** 第一帧和最后一帧显示之间，有几次取帧没有新画面（画面停一拍）。 */
        val repeats: Int
            get() {
                val latches = shown.filter { it >= 0 }.sorted()
                return latches.last() - latches.first() + 1 - latches.size
            }
    }

    /**
     * 按 [outputs]（每帧出帧时刻，递增）跑一遍节拍：Choreographer 的刷新采样随时间送进网格，扣着的帧在约定时刻
     * （晚 [timerLateNs]）送出。合成器实际取帧比推算晚 [trueLatchShiftNs]。
     */
    private fun simulate(
        outputs: LongArray,
        paced: Boolean,
        trueLatchShiftNs: Long = 0,
        timerLateNs: Long = 0,
    ): Run {
        val grid = VsyncGrid(P)
        var nextRefresh = 0L
        fun feedUntil(timeNs: Long) {
            while (T0 + nextRefresh * P <= timeNs) {
                grid.onRefresh(T0 + nextRefresh * P)
                nextRefresh++
            }
        }
        val pacer = FramePacer()
        val releases = ArrayList<Release>()
        val held = IntArray(FramePacer.MAX_QUEUED)
        var sameLatch = 0
        var replaced = 0
        fun releaseHead(atNs: Long) {
            feedUntil(atNs)
            val frame = held[0]
            for (i in 1 until pacer.queued) held[i - 1] = held[i]
            pacer.onHeadDue(atNs, grid, D)
            releases += Release(atNs, frame)
        }
        for (i in outputs.indices) {
            val t = outputs[i]
            while (pacer.queued > 0 && pacer.nextReleaseAtNs + timerLateNs <= t) {
                releaseHead(pacer.nextReleaseAtNs + timerLateNs)
            }
            feedUntil(t)
            when (pacer.onFrame(t, grid, D, paced)) {
                FramePacer.HOLD -> held[pacer.queued - 1] = i
                FramePacer.REPLACE -> {
                    held[pacer.queued - 1] = i
                    replaced++
                }
                else -> releases += Release(t, i)
            }
            if (pacer.sameLatch) sameLatch++
        }
        while (pacer.queued > 0) releaseHead(pacer.nextReleaseAtNs + timerLateNs)
        val firstLatch = T0 - D + trueLatchShiftNs
        val shown = composite(releases, outputs.size, firstLatch)
        val earliest = IntArray(outputs.size) { VsyncGrid.ceilDiv(outputs[it] - firstLatch, P).toInt() }
        return Run(shown, earliest, sameLatch, replaced)
    }

    /**
     * 合成器：第 m 次取帧在 [firstLatchNs] + m·[P]，拿走上次取帧以后（含这一刻）送来的帧里最后送的那一帧，其余挤掉。
     * [releases] 按送出顺序排列。返回每一帧在第几次取帧被拿去显示，被挤掉为 -1。
     */
    private fun composite(releases: List<Release>, frames: Int, firstLatchNs: Long): IntArray {
        val winner = HashMap<Long, Int>()
        for (release in releases) winner[VsyncGrid.ceilDiv(release.atNs - firstLatchNs, P)] = release.frame
        val shown = IntArray(frames) { -1 }
        for ((latchIndex, frame) in winner) shown[frame] = latchIndex.toInt()
        return shown
    }

    private companion object {
        /** 刷新周期（60Hz）。 */
        const val P = 16_666_667L

        /** 送显提前量：一个周期再加 1 毫秒（系统报的值一般就是这个样子）。 */
        const val D = P + 1_000_000L

        /** 第 0 次刷新的时刻。 */
        const val T0 = 10_000_000_000L

        /** 第 [m] 次刷新对应的取帧时刻（按推算）。 */
        fun latch(m: Int): Long = T0 + m * P - D

        /** 只有一次刷新采样、周期用标称值的网格。 */
        fun gridWithSample(): VsyncGrid = VsyncGrid(P).also { it.onRefresh(T0) }
    }
}
