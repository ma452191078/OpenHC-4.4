package io.github.gcbond.openhc.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.connection.ConnectionUiState
import io.github.gcbond.openhc.connection.DisplayArea
import io.github.gcbond.openhc.core.hisight.HiSightInput
import io.github.gcbond.openhc.platform.media.VideoDecoder
import io.github.gcbond.openhc.projection.ProjectionView

/*
 * 投屏页（4.4 fork：View 体系版，对照原型 projection 布局）。
 *
 * 画面本体在 projection/ProjectionView.kt（无 Compose 依赖，保留）：
 * SurfaceView 按 DisplayPlan 等比居中、Surface 变化时转接给解码器、触控经 TouchMapper 回传。
 * 这一屏只叠两个轻量层（全部静态、无持续动画，小面积降 overdraw）：
 * 等第一帧的全屏 waitBg 覆盖层（静态 acc 环 + 标题 + 等宽副标题）、
 * 解码器报错（danger 淡底）——不显示分辨率信息和返回按钮，退出用返回键回首页。
 */
internal class ProjectionScreen(
    context: Context,
    onInput: (HiSightInput) -> Unit,
) : FrameLayout(context) {

    private val projection = ProjectionView(context).apply {
        this.onInput = onInput
    }

    /** 等第一帧：全屏 waitBg 覆盖层 + 静态 acc 环 + 标题 + 等宽副标题（分辨率 fps）。 */
    private val waitingOverlay: LinearLayout
    private val waitingSub: TextView

    /** 解码器报错：左上角，dangerSoft 底 + danger 字 + dangerLine 边。 */
    private val problemText = TextView(context).apply {
        setTextColor(Palette.Danger)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        background = solidRound(Palette.DangerSoft, context.dp(Dimens.RADIUS_MD).toFloat(), context.dp(1f).toFloat(), Palette.DangerLine)
        setPadding(context.dp(12f), context.dp(8f), context.dp(12f), context.dp(8f))
        visibility = GONE
    }

    /** 上次见过的触控重置信号：变了才让 ProjectionView 重置触控状态。 */
    private var lastTouchResets = 0

    init {
        setBackgroundColor(Color.BLACK)
        addView(projection, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        waitingSub = TextView(context).apply {
            setTextColor(Palette.Muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }
        waitingOverlay = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Palette.WaitBg)
            isClickable = true // 盖住下面的画面，等待期间不响应触控
            addView(
                WaitRing(context),
                LinearLayout.LayoutParams(context.dp(76f), context.dp(76f)),
            )
            addView(
                TextView(context).apply {
                    setText(R.string.projection_waiting_frame)
                    setTextColor(Palette.Text)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    setTypeface(typeface, Typeface.BOLD)
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = context.dp(18f)
                },
            )
            addView(
                waitingSub,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = context.dp(8f)
                },
            )
        }
        addView(waitingOverlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(
            problemText,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                topMargin = context.dp(16f)
                marginStart = context.dp(16f)
            },
        )
    }

    /** 按当前状态刷新（主线程）。 */
    fun render(state: ConnectionUiState, decoder: VideoDecoder?, touchResets: Int) {
        // 全屏与否由连接建立时写进 DisplayArea（设置里「全屏显示」决定）。
        projection.fullScreen = DisplayArea.activeFullScreen
        projection.setVideoSize(state.videoWidth, state.videoHeight)
        projection.attachDecoder(decoder)

        if (touchResets != lastTouchResets) {
            lastTouchResets = touchResets
            projection.resetTouches()
        }

        waitingOverlay.visibility = if (state.firstFrame) GONE else VISIBLE
        val video = state.video
        waitingSub.text = if (video != null) {
            context.getString(R.string.projection_waiting_sub, video.width, video.height, video.fps)
        } else {
            context.getString(R.string.hint_negotiating)
        }
        val problem = state.decoderProblem
        if (problem == null) {
            problemText.visibility = GONE
        } else {
            problemText.text = context.getString(R.string.projection_decoder_problem, problem)
            problemText.visibility = VISIBLE
        }
    }

    /** 离开这一屏时把解码器从画面上摘下来（对应原版 onRelease）。 */
    fun detachDecoder() {
        projection.attachDecoder(null)
    }
}

/** 等待环：静态 acc 圆环，只画一次（onDraw 里不 invalidate，无扫描/脉冲动画）。 */
private class WaitRing(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(3f).toFloat()
        color = Palette.Accent
    }

    override fun onDraw(canvas: Canvas) {
        val radius = (minOf(width, height) - paint.strokeWidth) / 2f
        canvas.drawCircle(width / 2f, height / 2f, radius, paint)
    }
}