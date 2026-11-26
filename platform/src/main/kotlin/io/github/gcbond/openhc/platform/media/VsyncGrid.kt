package io.github.gcbond.openhc.platform.media

import kotlin.math.abs

/**
 * 屏幕刷新的时间网格（周期 + 相位），由 Choreographer 每次刷新回调给的时刻推出来（调用方先换算成实际刷新时刻），
 * 用来算「某个时刻以后的第一次刷新」。纯逻辑，不引用安卓类，只在一个线程上用；时间都是 System.nanoTime 的纳秒。
 *
 * 周期取一段连续采样的总时长除以其中的刷新次数，比逐个间隔平均准得多：停了一阵之后，要接着用老相位往后推。
 * 回调晚了、中途漏掉几次刷新照样算进去（按整数个周期）；间隔对不上整数个周期（刷新率变了，或者采样本身不对）
 * 就从这一次重新开始一段，连着 [MISMATCH_LIMIT] 次都对不上，改用最新的间隔当周期。
 */
internal class VsyncGrid(nominalPeriodNs: Long = 0) {

    private var nominalNs = plausibleOrZero(nominalPeriodNs)
    private var measuredNs = 0L
    private var anchorNs = 0L
    private var hasAnchor = false
    private var runStartNs = 0L
    private var runRefreshes = 0L
    private var mismatches = 0

    /** 当前用的刷新周期（纳秒）：量出来的优先，其次是显示模式给的；都没有为 0。 */
    val periodNs: Long get() = if (measuredNs > 0) measuredNs else nominalNs

    /** 最近一次刷新的时刻；还没有采样为 [Long.MIN_VALUE]。 */
    val lastRefreshNs: Long get() = if (hasAnchor) anchorNs else Long.MIN_VALUE

    /** 显示模式给的周期（0 表示不知道）：还没量出周期时先用它。 */
    fun setNominalPeriod(periodNs: Long) {
        nominalNs = plausibleOrZero(periodNs)
    }

    /** 一次刷新发生在 [refreshNs]。 */
    fun onRefresh(refreshNs: Long) {
        if (!hasAnchor) {
            hasAnchor = true
            restartRun(refreshNs)
            return
        }
        val delta = refreshNs - anchorNs
        // 同一次刷新报了两次，或者时间倒退：不可能是新的一次，忽略。
        if (delta <= 0) return
        val seed = periodNs
        if (seed <= 0) {
            if (delta in MIN_PERIOD_NS..MAX_PERIOD_NS) measuredNs = delta
            restartRun(refreshNs)
            return
        }
        val n = (delta + seed / 2) / seed
        when {
            n > MAX_SKIPPED_REFRESHES -> {
                // 停了很久：按整数个周期推算的误差可能已经超过半个周期，从这里重新开始一段，周期不变。
                mismatches = 0
                restartRun(refreshNs)
            }
            n >= 1 && abs(delta - n * seed) <= seed / 4 -> {
                mismatches = 0
                runRefreshes += n
                anchorNs = refreshNs
                if (runRefreshes >= MIN_RUN_REFRESHES) {
                    measuredNs = (refreshNs - runStartNs + runRefreshes / 2) / runRefreshes
                }
            }
            else -> {
                if (++mismatches >= MISMATCH_LIMIT && delta in MIN_PERIOD_NS..MAX_PERIOD_NS) {
                    measuredNs = delta
                    mismatches = 0
                }
                restartRun(refreshNs)
            }
        }
    }

    /** 能不能拿来推算 [nowNs] 前后的刷新时刻：有周期、有相位，且最近一次采样不太旧。 */
    fun isUsable(nowNs: Long): Boolean = periodNs > 0 && hasAnchor && nowNs - anchorNs <= STALE_NS

    /** [timeNs] 以后（含）的第一次刷新。先用 [isUsable] 确认能用。 */
    fun refreshAtOrAfter(timeNs: Long): Long {
        val period = periodNs
        check(period > 0 && hasAnchor) { "还没有刷新网格" }
        return anchorNs + ceilDiv(timeNs - anchorNs, period) * period
    }

    private fun restartRun(refreshNs: Long) {
        anchorNs = refreshNs
        runStartNs = refreshNs
        runRefreshes = 0
    }

    companion object {
        /** 刷新周期合理范围：20～500Hz。 */
        const val MIN_PERIOD_NS = 2_000_000L
        const val MAX_PERIOD_NS = 50_000_000L

        /** 最近一次采样超过这么久（1 秒）就不再往后推。 */
        const val STALE_NS = 1_000_000_000L

        /** 一段连续采样里至少有这么多次刷新，才用它算周期。 */
        const val MIN_RUN_REFRESHES = 8L

        /** 两次采样之间隔了这么多个周期以上，就重新开始一段。 */
        const val MAX_SKIPPED_REFRESHES = 120L

        /** 连着这么多次间隔对不上整数个周期，就改用新的间隔当周期（刷新率变了）。 */
        const val MISMATCH_LIMIT = 3

        private fun plausibleOrZero(periodNs: Long): Long = if (periodNs in MIN_PERIOD_NS..MAX_PERIOD_NS) periodNs else 0L

        /** 向上取整的整除，[divisor] 为正数，被除数可以是负数。 */
        fun ceilDiv(value: Long, divisor: Long): Long {
            val q = value / divisor
            return if (value % divisor > 0) q + 1 else q
        }
    }
}
