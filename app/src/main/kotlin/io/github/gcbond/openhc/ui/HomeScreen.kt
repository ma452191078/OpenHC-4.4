package io.github.gcbond.openhc.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.gcbond.openhc.BuildConfig
import io.github.gcbond.openhc.IdentityLoader
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.connection.ConnectionUiState
import io.github.gcbond.openhc.connection.EndCause
import io.github.gcbond.openhc.connection.Notice
import io.github.gcbond.openhc.connection.Phase
import io.github.gcbond.openhc.connection.Step
import io.github.gcbond.openhc.settings.AppSettings
import io.github.gcbond.openhc.settings.WirelessPreferences
import io.github.gcbond.openhc.wireless.WirelessEndCause
import io.github.gcbond.openhc.wireless.WirelessNotice
import io.github.gcbond.openhc.wireless.WirelessPhase
import io.github.gcbond.openhc.wireless.WirelessStep
import io.github.gcbond.openhc.wireless.WirelessUiState

/**
 * 首页上和连接有关的操作（有线 + 无线）。
 */
internal class HomeActions(
    val onStart: () -> Unit,
    /** 投屏中的「停止」：停下来回二维码页，之后不自动重连。 */
    val onStop: () -> Unit,
    val onRetryNow: () -> Unit,
    val onGrantVpn: () -> Unit,
    val onResetTrustStore: () -> Unit,
    val onWirelessStart: () -> Unit,
    /** 二维码页上的「开始连接」：主动拨最近连成功过的手机。 */
    val onWirelessReconnect: () -> Unit,
    /** 投屏中的「停止」：停下来回二维码页，之后不自动重连。 */
    val onWirelessStop: () -> Unit,
    /** 进入有线首页：自动走权限、VPN 和有线连接启动链。 */
    val onWiredStart: () -> Unit,
    /** 首页切换了有线 / 无线：通知无线调度按「只用数据线连接」的新值让出或恢复。 */
    val onModeChanged: () -> Unit,
)

/** 内容宽度不小于这个值就排两栏（左模式栏 + 右主面板），否则卡片横排、整页滚动。按宽度判定，不看横竖屏。 */
private const val TWO_COLUMN_MIN_DP = 560f

/** 主面板内边距（dp）。 */
private const val PANEL_PADDING_DP = 16f

/**
 * 一个按钮的描述：语义与旧 pillButton 的 text / primary / enabled / onClick 一一对应，
 * 摆进按钮区时才换成真正的 View（样式换成新设计的整宽按钮）。
 */
private class HomeButton(
    val text: CharSequence,
    val onClick: () -> Unit,
    val primary: Boolean = true,
    val enabled: Boolean = true,
)

/** 首页状态卡的语义色。只用实色淡底和描边，避免老车机上的渐变与阴影开销。 */
private enum class StateTone(val color: Int, val background: Int, val border: Int) {
    INFO(Palette.Accent, Palette.AccSoft, Palette.AccLine2),
    PROGRESS(Palette.Accent, Palette.AccSoft, Palette.AccLine2),
    SUCCESS(Palette.Ok, Palette.OkSoft, Palette.OkLine),
    WARNING(Palette.Warn, Palette.WarnTint, Palette.WarnLine),
    ERROR(Palette.Danger, Palette.DangerSoft, Palette.DangerLine),
}

/**
 * 不依赖矢量库的状态图标，API 19 可直接绘制。
 * 图形保持简单：信息 i、进行中三个点、成功对勾、提醒/错误感叹号。
 */
private class StateIconView(context: Context, private val tone: StateTone) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) * 0.36f
        val stroke = minOf(width, height) * 0.065f
        paint.color = tone.color
        paint.strokeWidth = stroke
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.style = Paint.Style.STROKE
        canvas.drawCircle(cx, cy, radius, paint)

        when (tone) {
            StateTone.PROGRESS -> {
                paint.style = Paint.Style.FILL
                val dot = stroke * 0.65f
                canvas.drawCircle(cx - radius * 0.43f, cy, dot, paint)
                canvas.drawCircle(cx, cy, dot, paint)
                canvas.drawCircle(cx + radius * 0.43f, cy, dot, paint)
            }
            StateTone.SUCCESS -> {
                canvas.drawLines(
                    floatArrayOf(
                        cx - radius * 0.45f, cy, cx - radius * 0.10f, cy + radius * 0.34f,
                        cx - radius * 0.10f, cy + radius * 0.34f, cx + radius * 0.50f, cy - radius * 0.34f,
                    ),
                    paint,
                )
            }
            StateTone.INFO -> {
                canvas.drawLine(cx, cy - radius * 0.05f, cx, cy + radius * 0.43f, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy - radius * 0.40f, stroke * 0.62f, paint)
            }
            StateTone.WARNING, StateTone.ERROR -> {
                canvas.drawLine(cx, cy - radius * 0.43f, cx, cy + radius * 0.12f, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy + radius * 0.43f, stroke * 0.62f, paint)
            }
        }
    }
}

/** API 19 可用的旋转加载圆弧；视图移除后立即停动画，不在后台空转。 */
private class LoadingIconView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.Accent
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** drawArc 的 float 重载是 API 21 才有，4.4 只能用 RectF 版（API 1）。 */
    private val arcRect = RectF()
    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 900L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            rotation = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animator.isStarted) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        paint.strokeWidth = minOf(width, height) * 0.075f
        val inset = paint.strokeWidth
        arcRect.set(inset, inset, width - inset, height - inset)
        canvas.drawArc(arcRect, -90f, 275f, false, paint)
    }
}

/** 圆角 + 可选描边的纯色背景。GradientDrawable 的构造/setCornerRadius/setStroke 都是 API 1 就有的。 */
private fun roundedBg(context: Context, radiusDp: Float, color: Int, strokeDp: Float = 0f, strokeColor: Int = 0): GradientDrawable {
    val drawable = GradientDrawable()
    drawable.cornerRadius = context.dp(radiusDp).toFloat()
    drawable.setColor(color)
    if (strokeDp > 0f) drawable.setStroke(context.dp(strokeDp), strokeColor)
    return drawable
}

/**
 * 主按钮：整宽、圆角 12dp、acc 实色底 + accInk 深色粗字 17sp（去渐变去辉光）；
 * 次级按钮：panel2 实色底 + lineStrong 1dp 描边 + 主文字色。禁用时 alpha 0.4（同旧 pillButton 的处理）。
 */
private fun buttonView(context: Context, spec: HomeButton): TextView {
    val view = TextView(context)
    view.text = spec.text
    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (spec.primary) 17f else 14f)
    view.setTextColor(if (spec.primary) Palette.AccInk else Palette.Text)
    view.gravity = Gravity.CENTER
    view.maxLines = 1
    view.ellipsize = TextUtils.TruncateAt.END
    // 宽度按文字走：左右各留一点内边距，看起来比文字稍宽即可。
    val padH = context.dp(24f)
    view.setPadding(padH, 0, padH, 0)
    if (spec.primary) view.setTypeface(view.typeface, Typeface.BOLD)
    view.background = if (spec.primary) {
        roundedBg(context, 12f, Palette.Accent)
    } else {
        roundedBg(context, 12f, Palette.Panel2, 1f, Palette.LineStrong)
    }
    view.alpha = if (spec.enabled) 1f else 0.4f
    view.isClickable = spec.enabled
    view.isFocusable = spec.enabled
    view.setOnClickListener { if (spec.enabled) spec.onClick() }
    return view
}

/** 头部右侧的状态胶囊：暗底圆角 12dp、13sp 灰字（文案用现成的 phase 文案）。 */
private fun statusPill(context: Context): TextView =
    TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(Palette.SubText)
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        background = solidRound(Palette.PillBg, context.dp(12f).toFloat())
        val px = context.dp(12f)
        val py = context.dp(6f)
        setPadding(px, py, px, py)
    }

/**
 * 首页（按新设计图重做的深色三段式布局）：
 * 顶栏（logo + 名称 / 自动连接状态 + 设置）、连接方式选择（仅启用有线时）、连接主面板、底部页脚；
 * 宽度不够两栏时，整页纵向堆叠、可滚动。
 *
 * 「启用有线连接」关着（默认）时只构建/显示无线面板，与之前一致；打开后出现「无线 / 有线」选择条，
 * 有线面板沿用旧版有线卡的状态、连接码和按钮分支。开关在设置页，改动返回首页时生效。
 */
@SuppressLint("ViewConstructor")
internal class HomeScreen(
    context: Context,
    private val identity: IdentityLoader.Result,
    private val actions: HomeActions,
    private val onShowProjection: () -> Unit,
    private val onOpenSettings: () -> Unit,
) : FrameLayout(context) {

    /** 一块连接面板：头部状态胶囊 + 内容区 + 按钮区 + 按钮下方说明。 */
    private class PanelParts(
        val root: LinearLayout,
        val body: LinearLayout,
        val buttons: LinearLayout,
        val note: TextView,
    )

    /** 两栏与否：按内容宽判定（旋转后重建 Activity，init 重新算，不用监听配置）。 */
    private val twoColumn: Boolean

    private val wirelessPreferences by lazy { WirelessPreferences(context) }
    private val appSettings by lazy { AppSettings(context) }

    // 下面这些在 init 里由各自的构建函数赋值（lateinit 避开「val 在函数里赋值不算初始化」的 Kotlin 限制）。
    private lateinit var wirelessParts: PanelParts
    private lateinit var wiredParts: PanelParts
    private lateinit var wirelessPanel: View
    private lateinit var wiredPanel: View
    private lateinit var modeSelector: LinearLayout
    private lateinit var modeWireless: TextView
    private lateinit var modeWired: TextView
    private lateinit var footerMode: TextView
    private lateinit var footerVersion: TextView
    private lateinit var footerBt: TextView
    private lateinit var footerScreen: TextView

    /** 「启用有线连接」的当前值；设置页改过之后由 [refreshConnectionModes] 重读。 */
    private var wiredEnabled = false

    /** 状态签名：内容没变就不重建子树（老弱车机省 CPU/内存的关键）。 */
    private var lastWirelessSig: String? = null
    private var lastWiredSig: String? = null

    /** 最后一次收到的状态：切换有线 / 无线时用它在面板显示出来之前补一次渲染。 */
    private var lastWirelessState: WirelessUiState? = null
    private var lastWiredState: ConnectionUiState? = null

    /** 二维码位图缓存：内容或尺寸不变复用同一张；变了先 recycle 旧的再编码。 */
    private var lastQrContent: String? = null
    private var lastQrSize = 0
    private var lastQrBitmap: Bitmap? = null

    init {
        val side = pageSidePx(context)
        val metrics = context.resources.displayMetrics
        twoColumn = (metrics.widthPixels - side * 2) >= context.dp(TWO_COLUMN_MIN_DP)
        // MainActivity 给根布局铺的是旧底色 Background，这里自己盖成新首页底色。
        setBackgroundColor(Palette.Bg2)
        wiredEnabled = appSettings.wiredEnabled

        modeSelector = buildModeSelector()
        val topBar = buildTopBar()
        wirelessPanel = buildPanel()
        wiredPanel = buildWiredPanel()
        val footer = buildFooter()

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, context.dp(12f), side, context.dp(14f))
        }
        if (twoColumn) {
            // 宽屏：整页不滚动，主面板自己滚（fillViewport，内容不够高也撑满）。
            addView(column, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } else {
            // 窄屏：整页滚动。
            val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
            scroll.addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        column.addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(64f)))

        // 两块面板放进同一个容器，只显示选中的那块（GONE 的不占空间）。
        val panelHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val panelParams = if (twoColumn) {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        } else {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        panelHost.addView(wirelessPanel, panelParams)
        panelHost.addView(wiredPanel, LinearLayout.LayoutParams(panelParams))
        if (twoColumn) {
            column.addView(panelScroll(panelHost), weightParams(12f))
        } else {
            column.addView(panelHost, matchWrap(12f))
        }

        column.addView(footer, matchWrap(if (twoColumn) 12f else 18f))

        refreshFooter()
        applyConnectionMode()
    }

    /** 设置页改了「启用有线连接」后由 MainActivity 调用：重读开关并切换显示。 */
    fun refreshConnectionModes() {
        wiredEnabled = appSettings.wiredEnabled
        applyConnectionMode()
    }

    /**
     * 从投屏 / 设置回到首页时调用：清掉渲染签名，按最后一次状态重画一遍。
     * 投屏中首页不在窗口里，那期间的渲染可能带着旧的测量结果；回到首页时重画一次，按钮等子视图才会正常出现。
     */
    fun refreshPanel() {
        lastWirelessSig = null
        lastWiredSig = null
        lastWirelessState?.let { renderWireless(it) }
        lastWiredState?.let { renderWired(it) }
    }

    /** 右侧面板宽屏时各自一个 fillViewport 的 ScrollView，与原无线面板一致。 */
    private fun panelScroll(panel: View): ScrollView = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        isFillViewport = true
        addView(panel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun weightParams(topDp: Float): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = context.dp(topDp) }

    /** 顶栏里的「无线 / 有线」切换：inset 底 + line 边，选中格 acc 底 + accInk 粗字，宽度按文字自适应。 */
    private fun buildModeSelector(): LinearLayout {
        val strip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = solidRound(Palette.Inset, context.dp(10f).toFloat(), context.dp(1f).toFloat(), Palette.Line)
            setPadding(context.dp(3f), context.dp(3f), context.dp(3f), context.dp(3f))
        }
        fun addSegment(labelRes: Int): TextView {
            val view = TextView(context).apply {
                text = context.getString(labelRes)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER
                val padH = context.dp(18f)
                val padV = context.dp(8f)
                setPadding(padH, padV, padH, padV)
                isClickable = true
                isFocusable = true
            }
            strip.addView(
                view,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            return view
        }
        modeWireless = addSegment(R.string.home2_mode_wireless)
        modeWired = addSegment(R.string.home2_mode_wired)
        modeWireless.setOnClickListener { selectMode(wired = false) }
        modeWired.setOnClickListener { selectMode(wired = true) }
        return strip
    }

    /**
     * 选有线 / 无线：沿用「只用数据线连接」偏好（开=有线），切换后通知无线调度让出或恢复。
     * 有线没启用时这条选择条不显示，也点不到。
     */
    private fun selectMode(wired: Boolean) {
        if (!wiredEnabled || wirelessPreferences.wiredOnly == wired) return
        wirelessPreferences.wiredOnly = wired
        actions.onModeChanged()
        applyConnectionMode()
        if (wired) actions.onWiredStart()
    }

    /** 按「启用有线 + 当前选择」决定显示哪块面板；切过去之前用最后一次状态补渲染。 */
    private fun applyConnectionMode() {
        modeSelector.visibility = if (wiredEnabled) View.VISIBLE else View.GONE
        val wired = wiredEnabled && wirelessPreferences.wiredOnly
        wirelessPanel.visibility = if (wired) View.GONE else View.VISIBLE
        wiredPanel.visibility = if (wired) View.VISIBLE else View.GONE
        styleModeSegment(modeWireless, selected = !wired)
        styleModeSegment(modeWired, selected = wired)
        refreshFooter()
        if (wired) {
            lastWiredState?.let { renderWired(it) }
        } else {
            lastWirelessState?.let { renderWireless(it) }
        }
    }

    private fun styleModeSegment(view: TextView, selected: Boolean) {
        if (selected) {
            view.setTextColor(Palette.AccInk)
            view.setTypeface(view.typeface, Typeface.BOLD)
            view.background = solidRound(Palette.Accent, context.dp(7f).toFloat())
        } else {
            view.setTextColor(Palette.Muted)
            view.setTypeface(view.typeface, Typeface.NORMAL)
            view.background = null
        }
    }

    /** 通栏、高度自适应，带顶部间距。 */
    private fun matchWrap(topDp: Float): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(topDp)
        }

    /** 顶栏：左 acc 圆角方块 brand-mark + 名称/标语；右自动连接状态胶囊 + 齿轮图标按钮。 */
    private fun buildTopBar(): LinearLayout {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // brand-mark：34dp acc 实心圆角方块（r9）+ 链环图标。车标只在设置页选、回传给手机，不换这里。
        val mark = FrameLayout(context).apply {
            background = roundedBg(context, 9f, Palette.Accent)
            addView(
                ImageView(context).apply {
                    setImageDrawable(
                        IconCache.get(context, "icons/link.svg").apply {
                            setColorFilter(PorterDuffColorFilter(Palette.AccInk, PorterDuff.Mode.SRC_IN))
                        },
                    )
                    scaleType = ImageView.ScaleType.FIT_CENTER
                },
                FrameLayout.LayoutParams(context.dp(20f), context.dp(20f), Gravity.CENTER),
            )
        }
        bar.addView(mark, LinearLayout.LayoutParams(context.dp(34f), context.dp(34f)))
        val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(
            TextView(context).apply {
                text = context.getString(R.string.app_name)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
                setTextColor(Palette.TitleText)
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 1
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        titles.addView(
            TextView(context).apply {
                text = context.getString(R.string.home2_subtitle)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(Palette.Dim)
                maxLines = 1
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        bar.addView(
            titles,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = context.dp(12f)
            },
        )

        // 有线 / 无线切换：只在「启用有线连接」时显示，紧挨设置按钮左侧。
        bar.addView(
            modeSelector,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = context.dp(12f)
            },
        )
        // 设置：46dp 图标按钮（r12，panel2 底 + line 边 + muted 齿轮）。
        val settingsBtn = FrameLayout(context).apply {
            background = roundedBg(context, 12f, Palette.Panel2, 1f, Palette.Line)
            addView(
                ImageView(context).apply {
                    setImageDrawable(
                        IconCache.get(context, "icons/settings.svg").apply {
                            setColorFilter(PorterDuffColorFilter(Palette.Muted, PorterDuff.Mode.SRC_IN))
                        },
                    )
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = context.getString(R.string.action_settings)
                },
                FrameLayout.LayoutParams(context.dp(22f), context.dp(22f), Gravity.CENTER),
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onOpenSettings() }
        }
        bar.addView(settingsBtn, LinearLayout.LayoutParams(context.dp(46f), context.dp(46f)))
        return bar
    }

    /**
     * 右栏主面板：缺 identity 的提示行放面板顶部（判定与文案沿用旧版 identityHomeNotice），
     * 下面是无线连接面板。
     */
    private fun buildPanel(): LinearLayout {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = context.dp(PANEL_PADDING_DP)
            setPadding(pad, pad, pad, pad)
            background = roundedBg(context, 16f, Palette.PanelBg, 1f, Palette.PanelStroke)
        }
        identityHomeNotice(context, identity)?.let {
            box.addView(
                it,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = context.dp(12f)
                },
            )
        }
        val parts = buildPanelParts()
        wirelessParts = parts
        // 面板被拉高时，内容区吃掉剩余高度。
        box.addView(
            parts.root,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        return box
    }

    /** 有线面板：和无线面板同一套骨架，只是标题与内容按有线状态填（旧版有线卡的结构）。 */
    private fun buildWiredPanel(): LinearLayout {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = context.dp(PANEL_PADDING_DP)
            setPadding(pad, pad, pad, pad)
            background = roundedBg(context, 16f, Palette.PanelBg, 1f, Palette.PanelStroke)
        }
        identityHomeNotice(context, identity)?.let {
            box.addView(
                it,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = context.dp(12f)
                },
            )
        }
        val parts = buildPanelParts()
        wiredParts = parts
        box.addView(
            parts.root,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        return box
    }

    /** 一块面板的骨架：居中内容区、按钮区、底部说明；不再放固定标题和状态胶囊。 */
    private fun buildPanelParts(): PanelParts {
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // 面板拉高时内容区吃剩余空间；纯文字状态（无权重子项）整体垂直居中。
            gravity = Gravity.CENTER_VERTICAL
        }
        box.addView(
            body,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                topMargin = context.dp(16f)
            },
        )
        val buttons = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        box.addView(
            buttons,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(16f)
            },
        )
        val note = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Palette.Dim)
            gravity = Gravity.CENTER
            visibility = GONE
        }
        box.addView(
            note,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(10f)
            },
        )
        return PanelParts(box, body, buttons, note)
    }

    /**
     * 顶栏右侧的「自动连接已开启/已关闭」状态胶囊：开关本身在「设置 → 无线」里，首页只做状态提示。
     */

    /**
     * 页脚遥测条（42dp，顶部 1px line 分隔，等宽 11.5sp dim）：
     * 左「OpenHC 版本」；右「蓝牙 ON/OFF」+「屏幕 WxH@Hz」+ 当前模式（acc 色）。
     * 只在 render/refreshFooter 时刷一次，不做持续刷新。
     */
    private fun buildFooter(): LinearLayout {
        val wrap = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(
            View(context).apply { setBackgroundColor(Palette.Line) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1),
        )
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(42f)
            val py = context.dp(8f)
            setPadding(0, py, 0, py)
        }
        footerVersion = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(Palette.Dim)
            typeface = Typeface.MONOSPACE
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        row.addView(footerVersion, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        footerBt = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(Palette.Dim)
            typeface = Typeface.MONOSPACE
            maxLines = 1
        }
        row.addView(
            footerBt,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = context.dp(18f)
            },
        )
        footerScreen = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(Palette.Dim)
            typeface = Typeface.MONOSPACE
            maxLines = 1
        }
        row.addView(
            footerScreen,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = context.dp(18f)
            },
        )
        footerMode = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(Palette.Accent)
            typeface = Typeface.MONOSPACE
            maxLines = 1
        }
        row.addView(footerMode, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        wrap.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return wrap
    }

    /** 遥测条真实数据：版本 / 蓝牙开关 / 屏幕分辨率与刷新率 / 当前模式（render 时顺手刷，不持续刷新）。 */
    private fun refreshFooter() {
        footerVersion.text = context.getString(R.string.home2_footer_version, BuildConfig.VERSION_NAME)
        val btOn = BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
        footerBt.text = context.getString(
            R.string.home2_telemetry_bt,
            context.getString(if (btOn) R.string.home2_bt_on else R.string.home2_bt_off),
        )
        val display = (context as? Activity)?.windowManager?.defaultDisplay
        val metrics = DisplayMetrics()
        if (display != null) {
            // 物理屏尺寸（含系统栏），API 17+ 可用，minSdk 19 满足。
            display.getRealMetrics(metrics)
        } else {
            metrics.setTo(resources.displayMetrics)
        }
        val hz = display?.refreshRate ?: 60f
        footerScreen.text = context.getString(
            R.string.home2_telemetry_screen,
            "${metrics.widthPixels}×${metrics.heightPixels}@${hz.toInt()}Hz",
        )
        footerMode.text = context.getString(R.string.home2_telemetry_mode, modeSummary(context))
    }

    /** 无线面板内容签名（含「只用数据线」偏好，它决定空闲时要不要显示「已关闭」）。 */
    private fun wirelessSignature(state: WirelessUiState, wirelessOff: Boolean): String = buildString {
        append(state.phase).append('|').append(state.step).append('|').append(state.pinCode)
        append('|').append(state.qrCode).append('|').append(state.notice)
        append('|').append(state.direct).append('|').append(state.active).append('|').append(state.autoReconnect)
        append('|').append(state.addressMismatch).append('|').append(state.autoHold)
        append('|').append(state.attempt).append('|').append(wirelessOff)
        append('|').append(state.phoneConnected).append('|').append(wirelessPreferences.knownPhoneList.size)
        val video = state.projection.video
        append('|').append(video?.width).append('x').append(video?.height).append('@').append(video?.fps)
        append('|').append(video?.touchInput).append('|').append(state.projection.firstFrame)
    }

    /**
     * 按无线状态刷新无线面板。任意时候都可在主线程调用。
     */
    fun renderWireless(state: WirelessUiState) {
        lastWirelessState = state
        refreshFooter()
        // 有线面板显示时无线面板是 GONE，先不重建；切回无线时 applyConnectionMode 会用最后一次状态补渲染。
        if (wirelessPanel.visibility != View.VISIBLE) return
        val parts = wirelessParts

        val phase = state.phase
        val wirelessOff = phase == WirelessPhase.IDLE && wirelessPreferences.wiredOnly

        // 状态没变：跳过重建，只保留上面刷过的页脚/顶栏状态。
        val sig = wirelessSignature(state, wirelessOff)
        if (sig == lastWirelessSig) return
        lastWirelessSig = sig

        parts.body.removeAllViews()
        when {
            // 等扫码时二维码优先：提示（上一步失败的原因等）作为卡片附在二维码下方，不遮挡扫码入口。
            phase == WirelessPhase.WAITING_FOR_SCAN && state.qrCode != null -> addScanContent(parts, state)
            !wirelessOff && state.notice != null -> {
                addWirelessNoticeCard(parts.body, state.notice!!, 0f)
                setNote(parts, "")
            }
            phase == WirelessPhase.IDLE && !wirelessOff -> addWirelessIdle(parts)
            else -> addWirelessStatus(parts, state, wirelessOff)
        }

        fillButtons(parts.buttons, wirelessButtonList(state, wirelessOff))
    }

    /** 有线面板内容签名：没变就不重建。 */
    private fun wiredSignature(state: ConnectionUiState): String = buildString {
        append(state.phase).append('|').append(state.step).append('|').append(state.pinCode)
        append('|').append(state.notice).append('|').append(state.attempt).append('|').append(state.active)
        val video = state.video
        append('|').append(video?.width).append('x').append(video?.height).append('@').append(video?.fps)
        append('|').append(state.firstFrame).append('|').append(video?.touchInput)
    }

    /**
     * 按有线状态刷新有线面板：空闲时给接线三步骤，其余给阶段大字、说明、首次配对的连接码、
     * 上次结束的原因和对应按钮（沿用旧版有线卡的分支）。有线没启用或面板没显示时只记状态。
     */
    fun renderWired(state: ConnectionUiState) {
        lastWiredState = state
        if (!wiredEnabled || wiredPanel.visibility != View.VISIBLE) return
        val sig = wiredSignature(state)
        if (sig == lastWiredSig) return
        lastWiredSig = sig

        val parts = wiredParts
        val warning = state.phase == Phase.USB_DEBUGGING_ON || state.phase == Phase.REPLUG_REQUIRED ||
            state.step == Step.PHONE_SWITCH_SLOW

        parts.body.removeAllViews()
        when {
            state.notice != null -> {
                addWiredNoticeCard(parts.body, state.notice!!, 0f)
                setNote(parts, "")
            }
            state.phase == Phase.IDLE || state.phase == Phase.PREPARING || state.phase == Phase.WAITING_FOR_PHONE ->
                addWiredIdle(parts)
            else -> addWiredStatus(parts, state, warning)
        }
        fillButtons(parts.buttons, wiredButtonList(state))
    }

    /** 有线空闲与等插线：进入该模式后已自动开始，无需再点按钮。 */
    private fun addWiredIdle(parts: PanelParts) {
        addCenteredStatus(
            parts,
            context.getString(R.string.home2_wired_connect_phone),
            "",
            StateTone.INFO,
            iconAsset = "icons/connection_wired.svg",
        )
        setNote(parts, "")
    }

    /** 有线实际连接过程：加载图标与主文本居中，连接码和手机操作提示继续保留。 */
    private fun addWiredStatus(parts: PanelParts, state: ConnectionUiState, warning: Boolean) {
        val slot = parts.body
        val projecting = state.phase == Phase.PROJECTING
        val title = when {
            projecting -> context.getString(R.string.home2_wireless_projecting)
            warning -> phaseTitle(context, state.phase)
            else -> context.getString(R.string.home2_connecting)
        }
        addCenteredStatus(
            parts,
            title,
            phaseHint(context, state),
            when {
                warning -> StateTone.WARNING
                projecting -> StateTone.SUCCESS
                else -> StateTone.PROGRESS
            },
            loading = !warning && !projecting,
        )
        state.pinCode?.let { pin ->
            val (box, gap) = pinSizes(context, panelInnerWidthPx(parts), pin.length)
            slot.addView(
                pinBoxes(context, pin, box, gap),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = context.dp(16f)
                },
            )
        }
        setNote(parts, "")
    }

    /** 有线各阶段的按钮（授权 / 重置配对 / 开始 / 回投屏 / 立即重试；投屏中才给停止）。 */
    private fun wiredButtonList(state: ConnectionUiState): List<HomeButton> {
        val startEnabled = identity is IdentityLoader.Result.Loaded
        return when {
            state.phase != Phase.IDLE -> buildList {
                if (state.phase == Phase.PROJECTING) {
                    add(button(R.string.action_show_projection) { onShowProjection() })
                    add(button(R.string.action_stop, primary = false) { actions.onStop() })
                }
                if (state.phase == Phase.REPLUG_REQUIRED) {
                    add(button(R.string.action_retry_now, primary = false) { actions.onRetryNow() })
                }
            }
            else -> when (state.notice) {
                null -> emptyList()
                Notice.VpnConsentRequired -> listOf(button(R.string.action_grant_vpn) { actions.onGrantVpn() })
                is Notice.TrustStoreCorrupt -> listOf(button(R.string.action_reset_trust) { actions.onResetTrustStore() })
                else -> listOf(button(R.string.home2_wired_primary, enabled = startEnabled) { actions.onStart() })
            }
        }
    }

    /** 空闲：给无线图标和提示，点下方按钮会进入二维码页。 */
    private fun addWirelessIdle(parts: PanelParts) {
        addCenteredStatus(
            parts,
            context.getString(R.string.home2_mode_wireless),
            context.getString(R.string.home2_wireless_idle_hint),
            StateTone.INFO,
            iconAsset = "icons/connection_wireless.svg",
        )
        setNote(parts, "")
    }

    /** 等扫码：精确配对标题 + 白底二维码 + 必要连接码。 */
    private fun addScanContent(parts: PanelParts, state: WirelessUiState) {
        val slot = parts.body
        slot.addView(
            TextView(context).apply {
                text = context.getString(R.string.home2_pairing_scan)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (context.compactLayout()) 19f else 23f)
                setTextColor(Palette.TitleText)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = context.dp(12f)
            },
        )
        // 二维码 + 连接码一起放进可用空间（宽度按面板实际/估算，高度按面板可用高度倒推）。
        val density = context.resources.displayMetrics.density
        val widthDp = panelInnerWidthPx(parts).toFloat() / density
        val heightDp = scanHeightBudgetDp(parts)
        val pin = state.pinCode
        val size = QrPinLayout.plan(widthDp, heightDp, pin?.length ?: 0, context.compactLayout())
        val qrPx = context.dp(size.qr)

        val center = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, context.dp(QrPinLayout.QR_MARGIN), 0, context.dp(QrPinLayout.QR_MARGIN))
        }
        // 白底圆角板 + 内边距：二维码留出安静区，扫码更稳；编码失败（内容超限等）就只给手输连接码。
        // 位图缓存：内容或尺寸没变复用同一张（RGB_565）；变了先 recycle 旧的再编码，避免反复分配。
        val qrContent = state.qrCode ?: ""
        val qrBitmap = if (qrContent == lastQrContent && qrPx == lastQrSize) {
            lastQrBitmap
        } else {
            lastQrBitmap?.recycle()
            val encoded = QrBitmap.encode(qrContent, qrPx)
            lastQrContent = qrContent
            lastQrSize = qrPx
            lastQrBitmap = encoded
            encoded
        }
        qrBitmap?.let { bitmap ->
            val plate = FrameLayout(context).apply {
                background = solidRound(Palette.QrPanel, context.dp(12f).toFloat())
                // 安静区由底板白边提供（约 4 模块），位图本身不含安静区。
                val pad = context.dp(12f)
                setPadding(pad, pad, pad, pad)
                addView(
                    ImageView(context).apply {
                        setImageBitmap(bitmap)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        contentDescription = context.getString(R.string.wireless_qr_description)
                    },
                    FrameLayout.LayoutParams(qrPx, qrPx, Gravity.CENTER),
                )
            }
            center.addView(
                plate,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        if (pin != null && size.pinBox > 0f) {
            center.addView(
                pinBoxes(context, pin, context.dp(size.pinBox), context.dp(size.pinGap)),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = context.dp(size.pinTop)
                },
            )
        }
        slot.addView(center, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 二维码保留；地址或连接有问题时，在下方补充带图标的处理建议。
        if (state.addressMismatch) {
            addStateCard(
                slot,
                context.getString(R.string.home2_problem_manual_address_title),
                context.getString(R.string.wireless_warning_manual_address),
                StateTone.WARNING,
                10f,
            )
        }
        state.notice?.let { addWirelessNoticeCard(slot, it, 10f) }
        setNote(parts, "")
    }

    /** 无线连接过程：加载/投屏图标在上，主文本与必要操作提示在下。 */
    private fun addWirelessStatus(parts: PanelParts, state: WirelessUiState, wirelessOff: Boolean) {
        val slot = parts.body
        val phase = state.phase
        if (wirelessOff) {
            addCenteredStatus(
                parts,
                context.getString(R.string.home2_status_off_title),
                context.getString(R.string.home_wireless_off_note),
                StateTone.INFO,
                iconAsset = "icons/connection_wireless.svg",
            )
        } else {
            val projecting = phase == WirelessPhase.PROJECTING
            addCenteredStatus(
                parts,
                context.getString(if (projecting) R.string.home2_wireless_projecting else R.string.home2_connecting),
                wirelessHintText(context, state),
                if (projecting) StateTone.SUCCESS else StateTone.PROGRESS,
                loading = !projecting,
            )
        }

        // 连接码：手机已经经蓝牙连上、认证还没完时还留着。
        state.pinCode?.let { pin ->
            val (box, gap) = pinSizes(context, panelInnerWidthPx(parts), pin.length)
            slot.addView(
                pinBoxes(context, pin, box, gap),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = context.dp(16f)
                },
            )
        }
        if (state.addressMismatch && phase != WirelessPhase.IDLE) {
            addStateCard(
                slot,
                context.getString(R.string.home2_problem_manual_address_title),
                context.getString(R.string.wireless_warning_manual_address),
                StateTone.WARNING,
                10f,
            )
        }
        setNote(parts, "")
    }

    /**
     * 横排三步骤（原型 hero-steps）：数字等宽 26sp dim（当前步骤 acc）+ 名称 14.5sp 粗 + 副标题 12sp muted；
     * 步骤间一条 lineStrong 连接线。去掉步骤卡片底色与插图，降 overdraw、省内存。
     */
    private fun heroRow(steps: List<Pair<Int, Int>>): LinearLayout {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        steps.forEachIndexed { index, pair ->
            // 步骤列按内容取宽（名称/副标题自然宽度），数字左对齐。
            row.addView(
                stepItem(index + 1, context.getString(pair.first), context.getString(pair.second), current = index == 0),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            if (index < steps.size - 1) {
                // 弹性连接线：吃掉上一步右侧剩余空间，从当前数字延伸到下一步数字；对齐数字中部。
                row.addView(
                    View(context).apply { setBackgroundColor(Palette.LineStrong) },
                    LinearLayout.LayoutParams(0, 1, 1f).apply {
                        topMargin = context.dp(13f)
                        marginStart = context.dp(8f)
                        marginEnd = context.dp(8f)
                    },
                )
            }
        }
        return row
    }

    /** 一个步骤：数字（等宽）+ 名称 + 副标题，竖排；无底色。 */
    private fun stepItem(number: Int, title: CharSequence, subtitle: CharSequence, current: Boolean): LinearLayout {
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(
            TextView(context).apply {
                text = number.toString()
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                setTextColor(if (current) Palette.Accent else Palette.Dim)
                typeface = Typeface.MONOSPACE
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        column.addView(
            TextView(context).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                setTextColor(Palette.TitleText)
                setTypeface(typeface, Typeface.BOLD)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(6f)
            },
        )
        column.addView(
            TextView(context).apply {
                text = subtitle
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Palette.Muted)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(2f)
            },
        )
        return column
    }

    /** 主状态统一为图标在上、文本在下并居中；辅助说明为空时不占空间。 */
    private fun addCenteredStatus(
        parts: PanelParts,
        title: String,
        message: String,
        tone: StateTone,
        iconAsset: String? = null,
        loading: Boolean = false,
    ) {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val vertical = context.dp(20f)
            setPadding(0, vertical, 0, vertical)
        }
        val icon: View = when {
            loading -> LoadingIconView(context)
            iconAsset != null -> ImageView(context).apply {
                // 多色插图自带配色，不做单色染色（SRC_IN 会把整张图染平）；states 的语义色由文字承担。
                setImageDrawable(IconCache.get(context, iconAsset))
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = title
            }
            else -> StateIconView(context, tone)
        }
        // 插图是 260:140 的宽幅（手机—车机），比加载小图标给更宽的展示框，FIT_CENTER 等比居中。
        column.addView(
            icon,
            LinearLayout.LayoutParams(
                if (loading) context.dp(76f) else context.dp(160f),
                if (loading) context.dp(76f) else context.dp(86f),
            ),
        )
        column.addView(
            TextView(context).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setTextColor(if (tone == StateTone.INFO || tone == StateTone.PROGRESS) Palette.TitleText else tone.color)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(18f)
            },
        )
        if (message.isNotEmpty()) {
            column.addView(
                TextView(context).apply {
                    text = message
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTextColor(Palette.SubText)
                    gravity = Gravity.CENTER
                    setLineSpacing(0f, 1.15f)
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = context.dp(10f)
                },
            )
        }
        parts.body.addView(
            column,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
    }

    /** 错误与提醒也采用图标上、文本下的居中结构；卡片按内容收窄居中，不占满面板。 */
    private fun addStateCard(
        slot: LinearLayout,
        title: String,
        message: String,
        tone: StateTone,
        topDp: Float = 0f,
    ) {
        if (message.isEmpty()) return
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val horizontal = context.dp(22f)
            val vertical = context.dp(20f)
            setPadding(horizontal, vertical, horizontal, vertical)
            background = solidRound(
                tone.background,
                context.dp(12f).toFloat(),
                context.dp(1f).toFloat(),
                tone.border,
            )
        }
        // 文本最大 320dp：短提示自然收窄，长文本在限宽内换行居中，避免卡片被撑满或逐字断行。
        val textWidth = context.dp(320f)
        card.addView(StateIconView(context, tone), LinearLayout.LayoutParams(context.dp(62f), context.dp(62f)))
        card.addView(
            TextView(context).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTextColor(tone.color)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                maxWidth = textWidth
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(12f)
            },
        )
        card.addView(
            TextView(context).apply {
                text = message
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(Palette.Text)
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.12f)
                maxWidth = textWidth
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(8f)
            },
        )
        slot.addView(
            card,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                // 卡片按内容自适应、不占满面板；在居中内容区里水平居中。
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = context.dp(topDp)
            },
        )
    }

    private fun addWirelessNoticeCard(slot: LinearLayout, notice: WirelessNotice, topDp: Float) {
        val message = wirelessNoticeText(context, notice)
        if (message.isEmpty()) return
        val title = when (notice) {
            WirelessNotice.IdentityMissing, is WirelessNotice.IdentityInvalid -> R.string.home2_problem_reinstall_title
            WirelessNotice.BluetoothAddressMissing -> R.string.home2_problem_bt_address_title
            WirelessNotice.BluetoothUnsupported -> R.string.home2_problem_bt_unsupported_title
            WirelessNotice.BluetoothOff -> R.string.home2_problem_bt_off_title
            WirelessNotice.HotspotUnsupported -> R.string.home2_problem_hotspot_title
            is WirelessNotice.PermissionsDenied -> R.string.home2_problem_permission_title
            WirelessNotice.WiredActive -> R.string.home2_problem_wired_active_title
            WirelessNotice.WiredOnly -> R.string.home2_status_off_title
            is WirelessNotice.TrustStoreCorrupt, is WirelessNotice.TrustStoreUnavailable ->
                R.string.home2_problem_trust_title
            is WirelessNotice.StartFailed -> R.string.home2_problem_start_title
            is WirelessNotice.Ended -> if (notice.cause == WirelessEndCause.UserStop) {
                R.string.home2_problem_stopped_title
            } else {
                R.string.home2_problem_ended_title
            }
        }
        addStateCard(slot, context.getString(title), message, wirelessNoticeTone(notice), topDp)
    }

    private fun wirelessNoticeTone(notice: WirelessNotice): StateTone = when (notice) {
        WirelessNotice.WiredActive, WirelessNotice.WiredOnly -> StateTone.INFO
        is WirelessNotice.Ended -> if (notice.cause == WirelessEndCause.UserStop) StateTone.INFO else StateTone.WARNING
        WirelessNotice.IdentityMissing, is WirelessNotice.IdentityInvalid,
        WirelessNotice.BluetoothUnsupported, is WirelessNotice.TrustStoreUnavailable,
        is WirelessNotice.StartFailed,
        -> StateTone.ERROR
        else -> StateTone.WARNING
    }

    private fun addWiredNoticeCard(slot: LinearLayout, notice: Notice, topDp: Float) {
        val title = when (notice) {
            Notice.IdentityMissing, is Notice.IdentityInvalid -> R.string.home2_problem_reinstall_title
            Notice.VpnConsentRequired -> R.string.home2_problem_vpn_title
            is Notice.VpnUnavailable -> R.string.home2_problem_vpn_unavailable_title
            is Notice.TrustStoreCorrupt, is Notice.TrustStoreUnavailable -> R.string.home2_problem_trust_title
            is Notice.StartFailed -> R.string.home2_problem_start_title
            is Notice.Ended -> if (notice.cause == EndCause.UserStop) {
                R.string.home2_problem_stopped_title
            } else {
                R.string.home2_problem_ended_title
            }
        }
        addStateCard(slot, context.getString(title), noticeText(context, notice), wiredNoticeTone(notice), topDp)
    }

    private fun wiredNoticeTone(notice: Notice): StateTone = when (notice) {
        is Notice.Ended -> if (notice.cause == EndCause.UserStop) StateTone.INFO else StateTone.WARNING
        Notice.IdentityMissing, is Notice.IdentityInvalid, is Notice.VpnUnavailable,
        is Notice.TrustStoreUnavailable, is Notice.StartFailed,
        -> StateTone.ERROR
        else -> StateTone.WARNING
    }

    /** 按钮下方的说明行（设计图里只有无线空闲时有内容）。 */
    private fun setNote(parts: PanelParts, text: CharSequence) {
        parts.note.text = text
        parts.note.visibility = if (text.isEmpty()) GONE else VISIBLE
    }

    /**
     * 按钮区：多个按钮横向排列，单个按钮居中；间距 10dp。主按钮 58dp（紧凑 50dp），次级 44dp。
     * 宽度按文字自适应（左右各留 24dp 内边距），不铺满整栏。
     */
    private fun fillButtons(container: LinearLayout, list: List<HomeButton>) {
        container.removeAllViews()
        if (list.isEmpty()) {
            container.visibility = GONE
            return
        }
        val horizontal = list.size > 1
        container.orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        container.gravity = Gravity.CENTER_HORIZONTAL
        list.forEachIndexed { index, spec ->
            val height = context.dp(if (spec.primary) (if (context.compactLayout()) 50f else 58f) else 44f)
            val view = buttonView(context, spec)
            // 宽度按文字量出来（左右内边距各 24dp）；不依赖布局阶段的 wrap_content。
            val textWidth = view.paint.measureText(spec.text.toString()) + context.dp(48f)
            val available = if (container.width > 0) container.width else Int.MAX_VALUE
            val width = minOf(textWidth.toInt() + 1, available)
            container.addView(
                view,
                LinearLayout.LayoutParams(width, height).apply {
                    if (horizontal) {
                        gravity = Gravity.CENTER_VERTICAL
                        if (index > 0) marginStart = context.dp(10f)
                    } else {
                        gravity = Gravity.CENTER_HORIZONTAL
                        if (index > 0) topMargin = context.dp(10f)
                    }
                },
            )
        }
        container.visibility = VISIBLE
    }

    /** 造一个按钮描述（文案取资源 id；primary/enabled 语义同 [HomeButton]）。 */
    private fun button(
        textRes: Int,
        primary: Boolean = true,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ): HomeButton = HomeButton(context.getString(textRes), onClick, primary, enabled)

    /** 无线各阶段的按钮（上游同款分支，与原 wirelessButtonList 一一对应）。 */
    private fun wirelessButtonList(state: WirelessUiState, wirelessOff: Boolean): List<HomeButton> {
        val phase = state.phase
        val startEnabled = identity is IdentityLoader.Result.Loaded
        return when {
            wirelessOff -> listOf(
                button(R.string.wireless_action_open_settings) { onOpenSettings() },
            )
            // 二维码页：连成功过手机时给一个「自动重连」，车主不用扫码也能让车机主动拨它。
            phase == WirelessPhase.WAITING_FOR_SCAN && wirelessPreferences.knownPhoneList.isNotEmpty() -> listOf(
                button(R.string.wireless_action_reconnect) { actions.onWirelessReconnect() },
            )
            phase != WirelessPhase.IDLE -> buildList {
                if (phase == WirelessPhase.PROJECTING) {
                    add(button(R.string.action_show_projection) { onShowProjection() })
                    add(button(R.string.action_stop, primary = false) { actions.onWirelessStop() })
                }
            }
            else -> when (val notice = state.notice) {
                // 4.4/5.1 权限装包时已授予，到这只是异常兜底：给「授权」再试一次。
                is WirelessNotice.PermissionsDenied -> listOf(
                    button(R.string.wireless_action_grant) { actions.onWirelessStart() },
                )
                WirelessNotice.BluetoothOff -> listOf(
                    button(R.string.wireless_action_enable_bt) { enableBluetooth() },
                    button(R.string.wireless_action_start, primary = false) { actions.onWirelessStart() },
                )
                WirelessNotice.BluetoothAddressMissing -> listOf(
                    button(R.string.wireless_action_fill_bt) { onOpenSettings() },
                )
                WirelessNotice.WiredOnly -> listOf(
                    button(R.string.wireless_action_open_settings) { onOpenSettings() },
                )
                is WirelessNotice.TrustStoreCorrupt -> listOf(
                    button(R.string.action_reset_trust) { actions.onResetTrustStore() },
                )
                else -> listOf(
                    button(R.string.wireless_action_start, enabled = startEnabled) {
                        actions.onWirelessStart()
                    },
                )
            }
        }
    }

    /** 4.4/5.1：应用还能直接打开系统蓝牙（新系统才要用户自己去开）。 */
    @SuppressLint("MissingPermission")
    private fun enableBluetooth() {
        @Suppress("DEPRECATION")
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            Toast.makeText(context, R.string.wireless_notice_bt_unsupported, Toast.LENGTH_SHORT).show()
        } else {
            adapter.enable()
        }
    }

    /** 主面板可用内容宽：优先用已测量宽度，布局前按整页宽度估算（面板现在占满整宽）。 */
    private fun panelInnerWidthPx(parts: PanelParts): Int {
        if (parts.root.width > 0) return parts.root.width - context.dp(PANEL_PADDING_DP) * 2
        val metrics = context.resources.displayMetrics
        val contentWidth = metrics.widthPixels - pageSidePx(context) * 2
        return contentWidth - context.dp(PANEL_PADDING_DP) * 2
    }

    /**
     * 扫码内容（二维码+连接码）的高度预算：按面板实际可用高度倒推，
     * 扣掉头部/按钮/说明/提示行等固定开销，保证底部提示不被挤出面板；
     * 布局前（高度还没测量）按整页结构估算。
     */
    private fun scanHeightBudgetDp(parts: PanelParts): Float {
        val density = context.resources.displayMetrics.density
        // 用实际内容高度（扣掉状态栏/导航栏），全屏 heightPixels 会高估导致内容溢出面板。
        val contentPx = if (height > 0) height else context.resources.displayMetrics.heightPixels
        val contentH = contentPx / density
        val panelH = if (parts.root.height > 0) {
            parts.root.height / density
        } else if (twoColumn) {
            // 整页：上下留白 26 + 顶栏 64 + 中部上距 12 + 页脚(42+12)。
            contentH - 156f
        } else {
            contentH * 0.62f
        }
        // 面板内边距 32 + 头部约 45 + 内容上距 16 + 按钮区(16+44) + 说明(10+18) + 底部提示行约 36。
        return (panelH - 217f).coerceAtLeast(120f)
    }
}

/**
 * 当前模式一句话。有线没启用时恒为「无线」；启用后按「只用数据线连接」和自动连接偏好来。
 */
internal fun modeSummary(context: Context): String {
    if (!AppSettings(context).wiredEnabled) return context.getString(R.string.settings_nav_wireless)
    if (Build.VERSION.SDK_INT < 19) return context.getString(R.string.home_mode_wired_only)
    val preferences = WirelessPreferences(context)
    return when {
        preferences.wiredOnly -> context.getString(R.string.home_mode_wired_only)
        preferences.connectOnBluetooth -> context.getString(R.string.home_mode_connect_on_bt)
        else -> context.getString(R.string.home_mode_both)
    }
}

// ---------- 5.0 无线恢复：无线状态 → 中文文案 ----------
// WirelessService.kt 里那份 phase→通知文案映射只服务通知栏；界面这里放一份对应的。
// 优先复用 strings_wireless.xml 里现成的上游文案，个别资源里没有的用字面量补。

/** 无线大状态一行字。 */
internal fun wirelessPhaseText(context: Context, phase: WirelessPhase): String = when (phase) {
    WirelessPhase.IDLE -> context.getString(R.string.wireless_phase_idle)
    WirelessPhase.PREPARING -> context.getString(R.string.wireless_phase_preparing)
    WirelessPhase.WAITING_FOR_SCAN -> context.getString(R.string.wireless_phase_scan)
    WirelessPhase.CONNECTING -> context.getString(R.string.wireless_phase_connecting)
    WirelessPhase.PROJECTING -> context.getString(R.string.wireless_phase_projecting)
    WirelessPhase.STOPPING -> context.getString(R.string.wireless_phase_stopping)
}

/** 面板状态胶囊里的状态词；自动回连的等待用专门的「等已配对的手机回连」。 */
private fun wirelessPhaseTitle(context: Context, state: WirelessUiState): String = when {
    state.autoReconnect && (state.phase == WirelessPhase.PREPARING || state.phase == WirelessPhase.WAITING_FOR_SCAN) ->
        context.getString(R.string.wireless_phase_auto)
    else -> wirelessPhaseText(context, state.phase)
}

/** 状态大字用的小字说明。 */
internal fun wirelessHintText(context: Context, state: WirelessUiState): String = when (state.phase) {
    WirelessPhase.IDLE -> context.getString(R.string.wireless_hint_idle)
    WirelessPhase.PREPARING -> context.getString(R.string.wireless_hint_preparing)
    WirelessPhase.WAITING_FOR_SCAN -> context.getString(R.string.wireless_hint_scan)
    WirelessPhase.CONNECTING -> when (state.step) {
        WirelessStep.BLUETOOTH_HANDSHAKE -> context.getString(R.string.wireless_hint_bt_handshake)
        WirelessStep.WAITING_AUTH -> context.getString(R.string.wireless_hint_waiting_auth)
        WirelessStep.CONFIRM_ON_PHONE -> context.getString(R.string.wireless_hint_confirm_on_phone)
        WirelessStep.AUTH_PAIRING -> context.getString(R.string.wireless_hint_auth_pairing)
        WirelessStep.AUTH_TRUSTED -> context.getString(R.string.wireless_hint_auth_trusted)
        // 开热点和 WiFi 直连两种方式的说法不同，按这次的方式选（strings_wireless_network.xml）。
        WirelessStep.NEGOTIATING_WIFI ->
            context.getString(
                if (state.direct) R.string.wireless_hint_negotiating_direct else R.string.wireless_hint_negotiating_hotspot,
            )
        WirelessStep.SENDING_NETWORK ->
            context.getString(
                if (state.direct) R.string.wireless_hint_sending_direct else R.string.wireless_hint_sending_hotspot,
            )
        WirelessStep.WAITING_PHONE_JOIN ->
            context.getString(
                if (state.direct) R.string.wireless_hint_waiting_join_direct else R.string.wireless_hint_waiting_join_hotspot,
            )
        WirelessStep.WIFI_HANDSHAKE -> context.getString(R.string.wireless_hint_wifi_handshake)
        WirelessStep.DEVICE_SESSION -> context.getString(R.string.wireless_hint_device_session)
        WirelessStep.WAITING_PROJECTION -> context.getString(R.string.wireless_hint_waiting_projection)
        WirelessStep.NEGOTIATING_PROJECTION -> context.getString(R.string.wireless_hint_negotiating_projection)
        // step 还没定下来时的兜底。
        null -> "正在连接手机…"
    }
    // 投屏中：WiFi 段与有线完全相同，直接复用有线的「分辨率 · 帧率」文案。
    WirelessPhase.PROJECTING -> phaseHint(context, state.projection)
    WirelessPhase.STOPPING -> context.getString(R.string.wireless_hint_stopping)
}

/** 无线要用户处理的问题 / 上次结束的原因。 */
internal fun wirelessNoticeText(context: Context, notice: WirelessNotice): String = when (notice) {
    WirelessNotice.IdentityMissing -> context.getString(R.string.notice_identity_missing)
    is WirelessNotice.IdentityInvalid -> context.getString(R.string.notice_identity_invalid, notice.reason)
    WirelessNotice.BluetoothAddressMissing -> context.getString(R.string.wireless_notice_bt_address)
    WirelessNotice.BluetoothUnsupported -> context.getString(R.string.wireless_notice_bt_unsupported)
    WirelessNotice.BluetoothOff -> context.getString(R.string.wireless_notice_bt_off)
    WirelessNotice.HotspotUnsupported -> context.getString(R.string.wireless_notice_hotspot_unsupported)
    is WirelessNotice.PermissionsDenied -> when {
        // 21 上危险权限安装时已授予，正常不会走到这；空列表就不显示。
        notice.missing.isEmpty() -> ""
        notice.permanently -> context.getString(R.string.wireless_notice_permissions_permanent)
        notice.approximateOnly -> context.getString(R.string.wireless_notice_precise_location)
        else -> context.getString(
            R.string.wireless_notice_permissions,
            notice.missing.joinToString(context.getString(R.string.wireless_perm_list_separator)) { permission ->
                wirelessPermissionName(context, permission)
            },
        )
    }
    WirelessNotice.WiredActive -> context.getString(R.string.wireless_notice_wired_active)
    // 设置里开着「只用数据线连接」：面板自身会显示「已关闭」，这行只是兜底。
    WirelessNotice.WiredOnly -> "无线已关闭。要用无线，请在「设置」里关掉「只用数据线连接」。"
    is WirelessNotice.TrustStoreCorrupt -> context.getString(R.string.notice_trust_corrupt, notice.reason)
    is WirelessNotice.TrustStoreUnavailable -> context.getString(R.string.notice_trust_unavailable, notice.reason)
    is WirelessNotice.StartFailed -> context.getString(R.string.notice_start_failed, notice.reason)
    is WirelessNotice.Ended ->
        context.getString(R.string.wireless_notice_ended, wirelessEndCauseText(context, notice.cause))
}

/** 无线结束原因一句话。各类型自带的 toString 已是中文（不含密钥和设备标识），直接用。 */
private fun wirelessEndCauseText(context: Context, cause: WirelessEndCause): String = when (cause) {
    WirelessEndCause.UserStop -> context.getString(R.string.end_user_stop)
    is WirelessEndCause.Session -> cause.reason.toString()
    is WirelessEndCause.Failure -> cause.message
}

/** 运行时权限 → 界面上的名字。21 上安装时已全授予,这行只在新系统上有意义。 */
private fun wirelessPermissionName(context: Context, permission: String): String = when (permission) {
    "android.permission.BLUETOOTH_CONNECT" -> context.getString(R.string.wireless_perm_bluetooth)
    "android.permission.NEARBY_WIFI_DEVICES" -> context.getString(R.string.wireless_perm_nearby_wifi)
    else -> context.getString(R.string.wireless_perm_location)
}

/** 页面左右留白：屏幕够宽用 PAGE_SIDE，否则用 PAGE_SIDE_NARROW。 */
internal fun pageSidePx(context: Context): Int =
    if (context.resources.displayMetrics.widthPixels >= context.dp(Dimens.WIDE_PAGE_MIN) * 2) {
        context.dp(Dimens.PAGE_SIDE)
    } else {
        context.dp(Dimens.PAGE_SIDE_NARROW)
    }
