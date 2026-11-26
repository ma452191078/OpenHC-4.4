package io.github.gcbond.openhc.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 手写 View 控件库（4.4 fork：View 体系版，全部程序化构建，不用 XML）。
 *
 * 对照已删除的 Compose 版 Widgets.kt：
 * - PageTitle / TopBarButton / CardBox / PillButton / ActionButton / PinBoxes；
 * - SettingsGroup + GroupRow / SwitchRow / ChoiceRow / RowTitle / RowDescription / RowNote / PillSwitch。
 * 观感（配色、字号、圆角）照旧，只是载体从 Composable 换成普通 View。
 */

/** 组内一行的水平内边距。 */
private fun Context.rowPaddingHorizontal(): Int = dp(16f)

/** 组内一行的垂直内边距：紧凑排布时缩一档。 */
private fun Context.rowPaddingVertical(): Int = dp(if (compactLayout()) 9f else 12f)

/** 选择器（分段按钮、声道格、无线方式等）的垂直内边距：全设置页统一用这一个值，改高度只改这里。 */
internal fun Context.selectorVerticalPad(): Int = dp(12f)

/** 按下有高亮反馈的系统水波纹背景（API 11+ 就有，4.4 可用）。 */
internal fun selectableBackground(context: Context): Int {
    val value = TypedValue()
    return if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)) value.resourceId else 0
}

/** 圆角纯色背景，可选 1px 级描边（setStroke 是 API 1 就有的，4.4 可用）。 */
internal fun solidRound(color: Int, radiusPx: Float, strokePx: Float = 0f, strokeColor: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        cornerRadius = radiusPx
        setColor(color)
        if (strokePx > 0f) setStroke(strokePx.toInt().coerceAtLeast(1), strokeColor)
    }

/** 页面大标题：矮屏 26sp，常规 34sp（上游同款）。 */
internal fun pageTitle(context: Context, text: CharSequence): TextView =
    TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (context.compactLayout()) 26f else 34f)
        setTextColor(Palette.Text)
        setTypeface(typeface, Typeface.BOLD)
    }

/** 顶栏按钮：ghost 风格——panel2 实色底 + lineStrong 边 + 主文字色。 */
internal fun topBarButton(context: Context, text: CharSequence, onClick: (View) -> Unit): TextView =
    TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(Palette.Text)
        gravity = Gravity.CENTER
        background = solidRound(Palette.Panel2, context.dp(11f).toFloat(), context.dp(1f).toFloat(), Palette.LineStrong)
        val horizontal = context.dp(16f)
        val vertical = context.dp(8f)
        setPadding(horizontal, vertical, horizontal, vertical)
        isClickable = true
        setOnClickListener(onClick)
    }

/** 卡片容器：圆角 12dp，panel 实色底 + line 边（不用半透明叠色，降 overdraw），内边距 14dp。 */
internal fun cardBox(context: Context, tint: Int = Palette.Card): LinearLayout =
    LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = solidRound(tint, context.dp(Dimens.CARD_RADIUS).toFloat(), context.dp(1f).toFloat(), Palette.Line)
        val pad = context.dp(Dimens.CARD_PADDING)
        setPadding(pad, pad, pad, pad)
    }

/** 胶囊大按钮：主操作 acc 实心 + accInk 深字（去渐变去辉光）；次要 ghost；不可用时整体 40% 透明。 */
internal fun pillButton(
    context: Context,
    text: CharSequence,
    primary: Boolean = true,
    enabled: Boolean = true,
    onClick: () -> Unit,
): TextView =
    TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(if (primary) Palette.AccInk else Palette.Text)
        if (primary) setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        background = if (primary) {
            solidRound(Palette.Accent, context.dp(Dimens.RADIUS_MD).toFloat())
        } else {
            solidRound(Palette.Panel2, context.dp(Dimens.RADIUS_MD).toFloat(), context.dp(1f).toFloat(), Palette.LineStrong)
        }
        val horizontal = context.dp(28f)
        setPadding(horizontal, 0, horizontal, 0)
        minHeight = context.dp(44f)
        minWidth = context.dp(if (context.compactLayout()) 150f else 200f)
        alpha = if (enabled) 1f else 0.4f
        isClickable = enabled
        setOnClickListener { if (enabled) onClick() }
    }

/** 行内小按钮的三种观感：普通 ghost、主操作青淡底、危险红淡底。 */
internal enum class ActionKind { NORMAL, PRIMARY, DANGER }

/** 行内小按钮：高 38dp，圆角 9dp，13sp 粗字；淡底 + 同色系描边（原型 btn-mini）。 */
internal fun actionButton(
    context: Context,
    text: CharSequence,
    kind: ActionKind = ActionKind.NORMAL,
    enabled: Boolean = true,
    onClick: () -> Unit,
): TextView =
    TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(
            when (kind) {
                ActionKind.NORMAL -> Palette.Text
                ActionKind.PRIMARY -> Palette.Accent
                ActionKind.DANGER -> Palette.Danger
            },
        )
        gravity = Gravity.CENTER
        background = when (kind) {
            ActionKind.NORMAL -> solidRound(Palette.Panel2, context.dp(9f).toFloat(), context.dp(1f).toFloat(), Palette.LineStrong)
            ActionKind.PRIMARY -> solidRound(Palette.AccSoft, context.dp(9f).toFloat(), context.dp(1f).toFloat(), Palette.AccLine2)
            ActionKind.DANGER -> solidRound(Palette.DangerSoft, context.dp(9f).toFloat(), context.dp(1f).toFloat(), Palette.DangerLine)
        }
        val horizontal = context.dp(16f)
        setPadding(horizontal, 0, horizontal, 0)
        minHeight = context.dp(38f)
        alpha = if (enabled) 1f else 0.4f
        isClickable = enabled
        setOnClickListener { if (enabled) onClick() }
    }

/** 行标题：14.5sp（紧凑 14sp）主文字色。 */
internal fun rowTitle(context: Context, text: CharSequence): TextView =
    TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (context.compactLayout()) 14f else 14.5f)
        setTextColor(Palette.Text)
    }

/** 行说明：12sp dim 灰。 */
internal fun rowDescription(context: Context, text: CharSequence): TextView =
    TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(Palette.Dim)
    }

/**
 * 行提示：加粗；warn 时琥珀色提醒要处理的事，否则当普通说明用。
 * 带 onClick 时整段文字可点（例如去系统设置补权限）。
 */
internal fun rowNote(
    context: Context,
    text: CharSequence,
    warn: Boolean = false,
    onClick: (() -> Unit)? = null,
): TextView =
    TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (context.compactLayout()) 12f else 13f)
        setTextColor(if (warn) Palette.Warn else Palette.Muted)
        setTypeface(typeface, Typeface.BOLD)
        if (onClick != null) {
            isClickable = true
            val backgroundRes = selectableBackground(context)
            if (backgroundRes != 0) setBackgroundResource(backgroundRes)
            setOnClickListener { onClick() }
        }
    }

/** 行头（标题 + 可空说明），行内左列用。 */
internal fun rowHeader(context: Context, title: CharSequence, description: CharSequence? = null): LinearLayout =
    LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(rowTitle(context, title))
        if (description != null) {
            addView(rowDescription(context, description))
        }
    }

/**
 * 设置分组：panel 实色底 + line 边，圆角 16dp（原型 set-card）。
 * 组名横排：acc 色 13sp 粗字 + 尾部一条 line 色分隔线（weight 1，高 1px）。
 * 组内行用 [row] 添加，自动画分隔线（第一行不画）。
 */
internal class SettingsGroup(context: Context, title: CharSequence) : LinearLayout(context) {
    private var rowCount = 0

    init {
        orientation = VERTICAL
        background = solidRound(Palette.PanelBg, context.dp(Dimens.RADIUS_LG).toFloat(), context.dp(1f).toFloat(), Palette.Line)
        val top = context.dp(12f)
        val side = context.rowPaddingHorizontal()
        val bottom = context.dp(if (context.compactLayout()) 4f else 6f)
        val titleRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(side, top, side, bottom)
        }
        titleRow.addView(
            TextView(context).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(Palette.Accent)
                setTypeface(typeface, Typeface.BOLD)
            },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
        )
        titleRow.addView(
            View(context).apply { setBackgroundColor(Palette.Line) },
            LayoutParams(0, 1, 1f).apply { marginStart = context.dp(10f) },
        )
        addView(titleRow)
    }

    /**
     * 添加一行。行容器是竖排 LinearLayout，调用方往里摆内容；
     * 非第一行会在行顶画一条左缩进 16dp 的分隔线。
     */
    fun row(build: (LinearLayout.() -> Unit)? = null): LinearLayout {
        if (rowCount > 0) {
            addView(
                View(context).apply { setBackgroundColor(Palette.Divider) },
                LayoutParams(LayoutParams.MATCH_PARENT, 1).apply {
                    setMargins(context.rowPaddingHorizontal(), 0, 0, 0)
                },
            )
        }
        ++rowCount
        val row = LinearLayout(context).apply {
            orientation = VERTICAL
            val horizontal = context.rowPaddingHorizontal()
            val vertical = context.rowPaddingVertical()
            setPadding(horizontal, vertical, horizontal, vertical)
            build?.invoke(this)
        }
        addView(row)
        return row
    }

    /** 兼容外部直接塞 View 进组（同样享受分隔线规则）。 */
    fun addRowView(view: View) {
        if (rowCount > 0) {
            addView(
                View(context).apply { setBackgroundColor(Palette.Divider) },
                LayoutParams(LayoutParams.MATCH_PARENT, 1).apply {
                    setMargins(context.rowPaddingHorizontal(), 0, 0, 0)
                },
            )
        }
        ++rowCount
        addView(view)
    }
}

/**
 * 开关行：左边标题/说明/提示，右边胶囊开关，整行可点。
 * 点击时先让开关视觉翻转再把新值交给 [onChecked]；写进系统失败时由调用方重查并重建弹回。
 */
internal fun switchRow(
    context: Context,
    title: CharSequence,
    checked: Boolean,
    description: CharSequence? = null,
    enabled: Boolean = true,
    note: CharSequence? = null,
    noteWarn: Boolean = false,
    onNoteClick: (() -> Unit)? = null,
    onChecked: (Boolean) -> Unit,
): View {
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val horizontal = context.rowPaddingHorizontal()
        val vertical = context.rowPaddingVertical()
        setPadding(horizontal, vertical, horizontal, vertical)
    }
    val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    texts.addView(rowTitle(context, title))
    if (description != null) texts.addView(rowDescription(context, description))
    if (note != null) texts.addView(rowNote(context, note, noteWarn, onNoteClick))
    val switch = PillSwitch(context).apply { isOn = checked }
    row.apply {
        addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f })
        addView(switch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(12f) })
        alpha = if (enabled) 1f else 0.4f
        isClickable = enabled
        setOnClickListener {
            if (enabled) {
                switch.isOn = !switch.isOn
                onChecked(switch.isOn)
            }
        }
    }
    return row
}

/**
 * 分段选择行：标题 + 说明 + 一排等宽选项。
 * 容器 inset 实色底 + line 边 r10 内边距 3dp；选中格 acc 底 + accInk 粗字 r7；未选 muted（原型 seg）。
 */
internal fun choiceRow(
    context: Context,
    title: CharSequence,
    options: List<CharSequence>,
    selected: Int,
    description: CharSequence? = null,
    onSelect: (Int) -> Unit,
): View {
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val horizontal = context.rowPaddingHorizontal()
        val vertical = context.rowPaddingVertical()
        setPadding(horizontal, vertical, horizontal, vertical)
    }
    row.addView(rowHeader(context, title, description))
    val segments = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        background = solidRound(Palette.Inset, context.dp(10f).toFloat(), context.dp(1f).toFloat(), Palette.Line)
        setPadding(context.dp(3f), context.dp(3f), context.dp(3f), context.dp(3f))
    }
    options.forEachIndexed { index, option ->
        val segment = TextView(context).apply {
            text = option
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            val verticalPad = context.selectorVerticalPad()
            setPadding(0, verticalPad, 0, verticalPad)
            isClickable = true
            setOnClickListener { onSelect(index) }
        }
        if (index == selected) {
            segment.setTextColor(Palette.AccInk)
            segment.setTypeface(segment.typeface, Typeface.BOLD)
            segment.background = solidRound(Palette.Accent, context.dp(7f).toFloat())
        } else {
            segment.setTextColor(Palette.Muted)
        }
        segments.addView(
            segment,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f },
        )
    }
    row.addView(segments, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(8f) })
    return row
}

/**
 * 胶囊开关（自绘）：52×30dp（紧凑 42×24），圆点 22dp（紧凑 16），内缩 3dp。
 * 开 = acc 轨 + acc 边 + accInk 点；关 = switchOff 轨 + lineStrong 边 + switchKnob 点。
 * 只在 isOn 变化时 invalidate 一次，无持续动画。
 */
internal class PillSwitch(context: Context) : View(context) {
    var isOn: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val compact = context.compactLayout()
    private val trackWidth = context.dp(if (compact) 42f else 52f)
    private val trackHeight = context.dp(if (compact) 24f else 30f)
    private val knobDiameter = context.dp(if (compact) 16f else 22f)
    private val borderWidth = context.dp(1f).toFloat()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(trackWidth, trackHeight)
    }

    /** 4.4 兜底的圆角矩形缓存（drawRoundRect 是 API 21 才有，老系统走 Path 画）。 */
    private val rect = RectF()
    private val path = Path()

    /** 兼容 19 的圆角矩形：21+ 直接画，老系统用 Path.addRoundRect（API 1 就有）。 */
    private fun roundRect(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, rx: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            canvas.drawRoundRect(left, top, right, bottom, rx, rx, paint)
        } else {
            rect.set(left, top, right, bottom)
            path.reset()
            path.addRoundRect(rect, rx, rx, Path.Direction.CW)
            canvas.drawPath(path, paint)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val radius = height / 2f
        // 轨道实色底。
        paint.style = Paint.Style.FILL
        paint.color = if (isOn) Palette.Accent else Palette.SwitchOff
        roundRect(canvas, 0f, 0f, width.toFloat(), height.toFloat(), radius)
        // 轨道描边（向内缩半个边宽，避免裁切）。
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = borderWidth
        paint.color = if (isOn) Palette.Accent else Palette.LineStrong
        val half = borderWidth / 2f
        roundRect(canvas, half, half, width - half, height - half, radius - half)
        // 圆点。
        paint.style = Paint.Style.FILL
        paint.color = if (isOn) Palette.AccInk else Palette.SwitchKnob
        val inset = context.dp(3f).toFloat()
        val knobLeft = if (isOn) width - knobDiameter - inset else inset
        roundRect(canvas, knobLeft, inset, knobLeft + knobDiameter, inset + knobDiameter, knobDiameter / 2f)
    }
}

/**
 * 连接码（PIN）一行：每位一个圆角方框，字号随方框缩放。
 * 方框边长与间距的算法照搬已删的 QrPinLayout：方框上限 100dp（紧凑 56dp），
 * 间距取 16dp 与方框 16% 的较小者，宽度不够就按位均分。
 */
internal fun pinSizes(context: Context, widthPx: Int, digits: Int): Pair<Int, Int> {
    val maxBox = context.dp(if (context.compactLayout()) Dimens.PIN_BOX_COMPACT else Dimens.PIN_BOX)
    val gap = minOf(context.dp(Dimens.PIN_GAP), (maxBox * Dimens.PIN_GAP_RATIO).toInt())
    val box = minOf(maxBox, (widthPx - gap * (digits - 1)) / digits)
    return box to gap
}

/** 按算好的方框/间距摆一排 PIN 数字，附「连接码 %1$s」的朗读描述。 */
internal fun pinBoxes(context: Context, pin: String, boxPx: Int, gapPx: Int): LinearLayout =
    LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        contentDescription = context.getString(io.github.gcbond.openhc.R.string.home_pin_description, pin)
        pin.forEachIndexed { index, digit ->
            addView(
                TextView(context).apply {
                    text = digit.toString()
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, boxPx * 0.8f)
                    setTextColor(Palette.PinText)
                    // 数字用等宽字体，位位对齐。
                    setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    background = solidRound(Palette.PinBox, context.dp(12f).toFloat())
                },
                LinearLayout.LayoutParams(boxPx, boxPx).apply {
                    if (index < pin.length - 1) marginEnd = gapPx
                },
            )
        }
    }
