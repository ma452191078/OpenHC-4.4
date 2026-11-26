package io.github.gcbond.openhc.platform.media

import kotlin.math.floor

/**
 * 等比缩放：把 [videoWidth]×[videoHeight] 的画面放进 [viewWidth]×[viewHeight] 的视图，居中，多出来的地方是黑边。
 * 纯逻辑，不引用安卓类。
 *
 * 画面矩形（[left]、[top]、[width]、[height]）取整到像素：投屏视图就按这个矩形摆放显示画面的 Surface，
 * 触摸也按同一个矩形换算，两边不会差出半个像素。
 */
data class VideoViewport(val viewWidth: Int, val viewHeight: Int, val videoWidth: Int, val videoHeight: Int) {
    val left: Int
    val top: Int
    val width: Int
    val height: Int

    init {
        require(viewWidth > 0 && viewHeight > 0 && videoWidth > 0 && videoHeight > 0) { "尺寸必须是正数" }
        // 交叉相乘比较宽高比，避免浮点误差；用 Long 防溢出。
        if (videoWidth.toLong() * viewHeight >= viewWidth.toLong() * videoHeight) {
            width = viewWidth
            height = roundDiv(viewWidth.toLong() * videoHeight, videoWidth.toLong()).coerceIn(1, viewHeight)
        } else {
            height = viewHeight
            width = roundDiv(viewHeight.toLong() * videoWidth, videoHeight.toLong()).coerceIn(1, viewWidth)
        }
        left = (viewWidth - width) / 2
        top = (viewHeight - height) / 2
    }

    val right: Int get() = left + width
    val bottom: Int get() = top + height

    /** 视图坐标点到画面矩形的距离（视图像素），在画面里为 0。 */
    fun distanceOutside(x: Float, y: Float): Float {
        val dx = maxOf(left - x, 0f, x - right)
        val dy = maxOf(top - y, 0f, y - bottom)
        return if (dx == 0f) dy else if (dy == 0f) dx else kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /** 视图横坐标 → 视频像素列，超出画面的钳到边缘像素。 */
    fun videoX(x: Float): Int = toPixel((x - left) * videoWidth / width, videoWidth)

    /** 视图纵坐标 → 视频像素行，超出画面的钳到边缘像素。 */
    fun videoY(y: Float): Int = toPixel((y - top) * videoHeight / height, videoHeight)

    private fun toPixel(value: Float, size: Int): Int {
        if (value.isNaN()) return 0
        return floor(value).toInt().coerceIn(0, size - 1)
    }

    private fun roundDiv(a: Long, b: Long): Int = ((a * 2 + b) / (b * 2)).toInt()
}
