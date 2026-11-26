package io.github.gcbond.openhc.projection

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.Window
import android.widget.FrameLayout
import io.github.gcbond.openhc.connection.DisplayArea
import io.github.gcbond.openhc.core.hisight.HiSightInput
import io.github.gcbond.openhc.platform.media.DisplayRefreshRate
import io.github.gcbond.openhc.platform.media.TouchMapper
import io.github.gcbond.openhc.platform.media.VideoDecoder
import io.github.gcbond.openhc.platform.media.VideoViewport
import io.github.gcbond.openhc.platform.media.onMotionEvent

/**
 * 投屏视图：按视频宽高比显示解码出来的手机画面（居中、黑边），把触摸换算成发给手机的 [HiSightInput]。
 * 显示期间保持屏幕常亮，挂到窗口上时（[immersive] 为 true）藏起状态栏和底部导航栏。
 *
 * 画面用 [SurfaceView] 显示，不用 TextureView：
 * - 解码器的输出直接作为独立图层交给系统合成，不经过本应用的界面绘制，少一次 GPU 拷贝、少一到两帧延迟，
 *   界面线程卡顿也不影响画面；
 * - [VideoDecoder] 按屏幕刷新的节拍送显（同一次刷新最多一帧新画面），时间戳总在过去，帧不会在合成器里越积越多；
 *   节拍按视图所在屏幕的刷新时序算，Surface 交给解码器时一并给它（[DisplayRefreshRate.timingOf]）；
 * - 性能弱的车机上更省电、更不容易掉帧。
 * 代价是 Surface 在切后台时会被销毁，[VideoDecoder] 为此会把输出临时切到占位 Surface、继续解码，回来时再切回。
 *
 * 用法：
 * ```
 * view.attachDecoder(decoder)                 // Surface 变化时同步交给解码器
 * view.onInput = { input -> 发给手机 }         // 主线程回调
 * // 解码器的 onVideoSizeChanged 里：
 * view.setVideoSize(width, height)            // 任意线程
 * ```
 */
class ProjectionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** 输出 Surface 的接收方，在界面线程上同步回调。传 null 时必须在返回前停止往原来的 Surface 上画。 */
    fun interface SurfaceListener {
        fun onSurfaceChanged(surface: Surface?)
    }

    /** 换算好的触摸事件，在主线程上依次回调；没设置时触摸照常跟踪但不发出。 */
    var onInput: ((HiSightInput) -> Unit)? = null

    /** 挂到窗口上时是否藏起状态栏和导航栏（离开窗口时恢复）。 */
    var immersive: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (!isAttachedToWindow) return
            if (value) hideSystemBars() else restoreSystemBars()
        }

    private val surfaceView = SurfaceView(context)
    private val touchMapper = TouchMapper(edgeSlopPx = EDGE_SLOP_DP * resources.displayMetrics.density)
    private var surface: Surface? = null
    private var surfaceListener: SurfaceListener? = null
    private var attachedDecoder: VideoDecoder? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var barsHidden = false

    init {
        setBackgroundColor(Color.BLACK)
        keepScreenOn = true
        addView(surfaceView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = updateSurface(holder.surface)

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
                updateSurface(holder.surface)

            override fun surfaceDestroyed(holder: SurfaceHolder) = updateSurface(null)
        })
    }

    /** 把画面交给 [decoder]（null 解绑）。换解码器时旧的先收到 null。主线程调用，重复传同一个解码器没有影响。 */
    fun attachDecoder(decoder: VideoDecoder?) {
        if (decoder === attachedDecoder) return
        replaceSurfaceListener(
            decoder?.let { d ->
                SurfaceListener { s ->
                    // 有 Surface 时视图一定挂在窗口上，拿得到所在的屏幕。
                    if (s != null) d.setDisplayTiming(DisplayRefreshRate.timingOf(display))
                    d.setSurface(s)
                }
            },
        )
        attachedDecoder = decoder
    }

    /** 不用 [VideoDecoder] 时自己接收 Surface。主线程调用。 */
    fun setSurfaceListener(listener: SurfaceListener?) {
        attachedDecoder = null
        replaceSurfaceListener(listener)
    }

    /** 视频画面尺寸（解码器 onVideoSizeChanged 给的），决定显示比例和触摸换算；0 表示还不知道。任意线程。 */
    fun setVideoSize(width: Int, height: Int) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post { setVideoSize(width, height) }
            return
        }
        if (width == videoWidth && height == videoHeight) return
        videoWidth = width
        videoHeight = height
        requestLayout()
    }

    /**
     * 只清本地的触摸状态，不再发任何事件。发送端报告触摸状态对不上（sendInput 抛 IllegalStateException，
     * 一般是回控连接就绪前的输入被静默丢弃了）时调用：这时补发抬起也会被拒。还按着的手指忽略到抬起为止，
     * 下一次按下重新开始。主线程调用。
     */
    fun resetTouches() {
        touchMapper.reset()
    }

    /** 失焦、离开窗口：手机上按着的一组按上次的位置抬起，免得手机那边一直按着。 */
    private fun releaseTouches() {
        deliver(touchMapper.cancel())
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val viewport = viewportFor(measuredWidth, measuredHeight) ?: return
        surfaceView.measure(
            MeasureSpec.makeMeasureSpec(viewport.width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(viewport.height, MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // 记下投屏真实可用区域：下次连接按它声明画布，画面才正好铺满（系统报的导航栏高度不准）。
        DisplayArea.recordContentSize(context, right - left, bottom - top)
        // 显示画面的 Surface 摆在等比缩放后的矩形里，触摸按同一个矩形换算。
        val viewport = viewportFor(right - left, bottom - top)
        if (viewport != null) surfaceView.layout(viewport.left, viewport.top, viewport.right, viewport.bottom)
        deliver(touchMapper.setViewport(viewport))
    }

    // 触摸原样转给手机，本地没有点击动作，无障碍的 performClick 没有意义。
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        deliver(touchMapper.onMotionEvent(event))
        return true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (immersive) hideSystemBars()
    }

    override fun onDetachedFromWindow() {
        releaseTouches()
        restoreSystemBars()
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) {
            // 弹框、下拉通知栏之后系统可能把栏放出来，拿回焦点时再藏一次。
            if (immersive) hideSystemBars()
        } else {
            releaseTouches()
        }
    }

    private fun updateSurface(newSurface: Surface?) {
        // SurfaceView 重建时给的是同一个 Surface 对象，中间一定先经过销毁（null），所以按引用比较就够了。
        if (newSurface === surface) return
        surface = newSurface
        surfaceListener?.onSurfaceChanged(newSurface)
    }

    private fun replaceSurfaceListener(listener: SurfaceListener?) {
        if (listener === surfaceListener) return
        surfaceListener?.onSurfaceChanged(null)
        surfaceListener = listener
        surface?.let { listener?.onSurfaceChanged(it) }
    }

    private fun viewportFor(width: Int, height: Int): VideoViewport? =
        if (width > 0 && height > 0 && videoWidth > 0 && videoHeight > 0) {
            VideoViewport(width, height, videoWidth, videoHeight)
        } else {
            null
        }

    private fun deliver(touches: List<HiSightInput.Touch>) {
        val listener = onInput ?: return
        for (touch in touches) listener(touch)
    }

    /** 「全屏显示」：false 时只藏状态栏、保留底部导航栏（见 [SystemBars.hide]）。在 [immersive] 之前设。 */
    var fullScreen: Boolean = true

    private fun hideSystemBars() {
        val window = hostWindow() ?: return
        SystemBars.hide(window, this, fullScreen)
        barsHidden = true
    }

    private fun restoreSystemBars() {
        if (!barsHidden) return
        barsHidden = false
        hostWindow()?.let { SystemBars.show(it, this) }
    }

    /** 所在 Activity 的窗口；不在 Activity 里（例如副屏 Presentation）时不处理系统栏。 */
    private fun hostWindow(): Window? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) return current.window
            current = current.baseContext
        }
        return null
    }

    private companion object {
        /** 按下点在黑边里、离画面边缘不超过这个距离时仍然接受（钳到边缘），方便从边缘滑入的手势。 */
        const val EDGE_SLOP_DP = 24f
    }
}
