package io.github.gcbond.openhc.platform.media

/**
 * 屏幕的刷新时序：安卓 5.0 起 `Display` 公开的三项，从视图所在的屏幕读（[DisplayRefreshRate.timingOf]），
 * 交给 [VideoDecoder.setDisplayTiming] 算上屏节拍。纯数据，不引用安卓类。读不到的项为 0。
 */
data class DisplayTiming(
    /** 刷新周期（纳秒）。 */
    val refreshPeriodNs: Long,
    /** Choreographer 报的帧时间比实际刷新晚多少（纳秒）：实际刷新时刻 = 帧时间 − 它。 */
    val appVsyncOffsetNs: Long,
    /**
     * 一帧至少要比它的显示时刻早这么多交给 Surface（纳秒），系统已经留了约 1 毫秒余量。
     * 节拍只用它对刷新周期取余的部分（合成器取帧的相位），不用它推算哪一次刷新显示：按开源框架的实现看，
     * 按「工作时长」安排合成的系统版本报出来的值可能少整数个周期（推断，没有在真机上核对）。
     */
    val presentationDeadlineNs: Long,
) {
    override fun toString(): String =
        "周期 ${refreshPeriodNs}ns，帧时间偏移 ${appVsyncOffsetNs}ns，送显提前量 ${presentationDeadlineNs}ns"
}
