package io.github.gcbond.openhc.platform.media

import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 刷新网格：周期怎么量、相位怎么推、什么时候不能用。时间全是合成值。 */
class VsyncGridTest {

    @Test
    fun nominalPeriodIsUsableFromTheFirstRefresh() {
        val grid = VsyncGrid(P60)
        assertFalse(grid.isUsable(T0), "还没有相位")
        assertFailsWith<IllegalStateException> { grid.refreshAtOrAfter(T0) }
        grid.onRefresh(T0)
        assertTrue(grid.isUsable(T0))
        assertEquals(P60, grid.periodNs)
        assertEquals(T0, grid.refreshAtOrAfter(T0), "正好在刷新时刻：就是这一次")
        assertEquals(T0 + P60, grid.refreshAtOrAfter(T0 + 1))
        assertEquals(T0, grid.refreshAtOrAfter(T0 - P60 + 1), "往前推也行")
        assertEquals(T0 - P60, grid.refreshAtOrAfter(T0 - P60))
        assertEquals(T0 + 3 * P60, grid.refreshAtOrAfter(T0 + 2 * P60 + 1))
    }

    @Test
    fun periodIsMeasuredOverTheWholeRunIncludingLateCallbacks() {
        // 屏幕实际约 59.94Hz，显示模式报 60Hz；回调时间有 ±50 微秒的抖动，每 7 次漏掉一次（回调晚了一拍）。
        val actual = 16_683_350L
        val grid = VsyncGrid(P60)
        for (i in 0 until 300) {
            if (i % 7 == 3) continue
            grid.onRefresh(T0 + i * actual + jitter(i, 50_000))
        }
        assertTrue(abs(grid.periodNs - actual) < 1_000, "量出来的周期 ${grid.periodNs}")
        // 往后推半秒（30 个周期），相位误差仍远小于一个周期。
        val last = grid.lastRefreshNs
        val predicted = grid.refreshAtOrAfter(last + 30 * actual - actual / 2)
        assertTrue(abs(predicted - (T0 + 329 * actual)) < 200_000, "推算 $predicted")
    }

    @Test
    fun withoutNominalPeriodTheFirstPlausibleIntervalIsUsed() {
        val grid = VsyncGrid()
        grid.onRefresh(T0)
        assertFalse(grid.isUsable(T0), "只有一次采样，还不知道周期")
        grid.onRefresh(T0 + P55)
        assertTrue(grid.isUsable(T0 + P55))
        assertEquals(P55, grid.periodNs)
    }

    @Test
    fun refreshRateChangeIsAdoptedAfterThreeMismatches() {
        val grid = VsyncGrid(P60)
        var t = T0
        repeat(20) {
            grid.onRefresh(t)
            t += P60
        }
        val p120 = 8_333_333L
        repeat(2) {
            grid.onRefresh(t)
            t += p120
        }
        assertTrue(abs(grid.periodNs - P60) < 1_000, "偶尔对不上不改周期")
        repeat(12) {
            grid.onRefresh(t)
            t += p120
        }
        assertTrue(abs(grid.periodNs - p120) < 1_000, "连着对不上就换：${grid.periodNs}")
    }

    @Test
    fun staleGridIsNotUsed() {
        val grid = VsyncGrid(P60)
        grid.onRefresh(T0)
        assertTrue(grid.isUsable(T0 + VsyncGrid.STALE_NS))
        assertFalse(grid.isUsable(T0 + VsyncGrid.STALE_NS + 1), "一秒没有新采样")
        // 很久以后再来一次刷新：周期不变，相位换成新的。
        grid.onRefresh(T0 + 600 * P60 + 123)
        assertEquals(P60, grid.periodNs)
        assertEquals(T0 + 600 * P60 + 123, grid.refreshAtOrAfter(T0 + 600 * P60))
    }

    @Test
    fun duplicateOrBackwardSamplesAreIgnored() {
        val grid = VsyncGrid(P60)
        grid.onRefresh(T0)
        grid.onRefresh(T0)
        grid.onRefresh(T0 - 5)
        assertEquals(T0, grid.lastRefreshNs)
    }

    @Test
    fun implausibleNominalPeriodIsIgnored() {
        assertEquals(0, VsyncGrid(1_000).periodNs)
        assertEquals(0, VsyncGrid(1_000_000_000).periodNs)
        val grid = VsyncGrid()
        grid.setNominalPeriod(P55)
        assertEquals(P55, grid.periodNs)
    }

    @Test
    fun ceilDivRoundsTowardPositiveInfinity() {
        assertEquals(2, VsyncGrid.ceilDiv(5, 3))
        assertEquals(2, VsyncGrid.ceilDiv(6, 3))
        assertEquals(-1, VsyncGrid.ceilDiv(-5, 3))
        assertEquals(-2, VsyncGrid.ceilDiv(-6, 3))
        assertEquals(0, VsyncGrid.ceilDiv(0, 3))
    }

    private companion object {
        const val T0 = 10_000_000_000L
        const val P60 = 16_666_667L
        const val P55 = 18_181_818L

        /** 确定性的伪随机抖动，范围 ±[amplitude]。 */
        fun jitter(i: Int, amplitude: Long): Long = ((i * 7919L) % 201 - 100) * amplitude / 100
    }
}
