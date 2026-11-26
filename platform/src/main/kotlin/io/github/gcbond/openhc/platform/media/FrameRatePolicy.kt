package io.github.gcbond.openhc.platform.media

import kotlin.math.roundToInt

/**
 * 向手机声明的投屏帧率怎么定。纯逻辑，不引用安卓类；读刷新率见 [DisplayRefreshRate]。
 *
 * 按车机屏幕的刷新率声明：手机按声明的帧率出帧，帧率高于屏幕刷新率时，多出来的帧在显示时被挤掉，
 * 画面会隔几秒跳一下（实测 55Hz 的屏声明 60fps 就是这样，改成按刷新率声明就正常）。
 */
object FrameRatePolicy {
    const val DEFAULT_MAX_FPS = 60

    /** 刷新率读不到或明显不对时用的帧率。 */
    const val FALLBACK_FPS = 60

    /** 刷新率在这个范围以外就当读错了。 */
    private const val PLAUSIBLE_MIN_HZ = 20f
    private const val PLAUSIBLE_MAX_HZ = 500f

    /**
     * 按刷新率四舍五入取整，不超过 [maxFps]（高刷屏也不必让手机出更多帧）。
     * [refreshRateHz] 为 null、不是数或不在 20～500Hz 内时返回 [FALLBACK_FPS]（同样受 [maxFps] 限制）。
     */
    fun declaredFps(refreshRateHz: Float?, maxFps: Int = DEFAULT_MAX_FPS): Int {
        require(maxFps > 0) { "maxFps 必须是正数" }
        if (refreshRateHz == null || refreshRateHz.isNaN() || refreshRateHz !in PLAUSIBLE_MIN_HZ..PLAUSIBLE_MAX_HZ) {
            return minOf(FALLBACK_FPS, maxFps)
        }
        return refreshRateHz.roundToInt().coerceIn(1, maxFps)
    }
}
