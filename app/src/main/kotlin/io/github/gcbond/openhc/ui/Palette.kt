package io.github.gcbond.openhc.ui

import android.content.Context

/**
 * 界面配色、尺寸、字号（4.4 fork：View 体系版）。
 *
 * 色值对齐已确认的 HTML 原型（openhc-ui-prototype.html）：深座舱底 + 信号青强调。
 * 性能取向：卡片一律实色底（不用半透明叠色/渐变/阴影），降低老弱车机的 overdraw。
 * 旧常量名全部保留（语义映射到新令牌），避免破坏现有引用。
 */
internal object Palette {
    /** 页面底色（最深）= 原型 --bg。 */
    val Background = 0xFF070B12.toInt()

    /** 卡片底色 = 原型 --panel（实色，替代旧的半透明白）。 */
    val Card = 0xFF0E1520.toInt()

    /** 主文字 = 原型 --text。 */
    val Text = 0xFFE9F1F9.toInt()

    /** 次要说明文字 = 原型 --muted。 */
    val Muted = 0xFF8B99AC.toInt()

    /** 强调色（信号青）= 原型 --acc：组名、选中态、主操作。 */
    val Accent = 0xFF37E6DC.toInt()

    /** 要用户动手处理的事用的琥珀色 = 原型 --warn。 */
    val Warn = 0xFFFFB454.toInt()

    /** 选中态 = 原型 --acc。 */
    val On = 0xFF37E6DC.toInt()

    /** 开关关闭时的轨道灰 = 原型 --switch-off。 */
    val SwitchOff = 0xFF1B2634.toInt()

    /** 普通行内按钮的底色 = 原型 --panel-2。 */
    val Button = 0xFF121B28.toInt()

    /** 分段选择的整体底色 = 原型 --inset（凹陷实色）。 */
    val SegmentBackground = 0xFF0A111B.toInt()

    /** 行分隔线 = 原型 --line（淡蓝 14%）。 */
    val Divider = 0x248CAFD7

    /** 首页卡片底色 = 原型 --panel（实色）。 */
    val Surface = 0xFF0E1520.toInt()

    /** 首页卡片描边 = 原型 --line。 */
    val Stroke = 0x248CAFD7

    /** 二维码底板 = 原型 --qr-paper（近白，扫码需要高对比）。 */
    val QrPanel = 0xFFEEF4FA.toInt()

    /** 连接码方框底色 = 原型 --inset。 */
    val PinBox = 0xFF0A111B.toInt()

    /** 连接码数字 = 原型 --text。 */
    val PinText = 0xFFE9F1F9.toInt()

    /** 主按钮不再用渐变：起点/终点都映射到实色 acc。 */
    val PrimaryStart = 0xFF37E6DC.toInt()
    val PrimaryEnd = 0xFF37E6DC.toInt()

    /** 次要按钮的底色 = 原型 --panel-2。 */
    val Secondary = 0xFF121B28.toInt()

    /** 提醒卡片底色 = 原型 --warn-soft（琥珀 12%）。 */
    val WarnTint = 0x1FFFB454

    // ---------- 新设计令牌（与原型 CSS 变量一一对应） ----------

    /** 首页页面底色 = 原型 --bg-2。 */
    val Bg2 = 0xFF0A1019.toInt()

    /** 右栏主面板底色 / 描边 = 原型 --panel / --line。 */
    val PanelBg = 0xFF0E1520.toInt()
    val PanelStroke = 0x248CAFD7

    /** 未选中模式卡的底色 / 描边 = 原型 --panel / --line。 */
    val CardBg2 = 0xFF0E1520.toInt()
    val CardStroke = 0x248CAFD7

    /** 选中模式卡的深青底 / 青边 = 原型 --sel-bg / --acc-line5。 */
    val SelBg = 0xFF122A33.toInt()
    val SelStroke = 0x8C37E6DC.toInt()

    /** 步骤区底色、数字徽章底色 = 原型 --inset；徽章字色 = acc。 */
    val StepBg = 0xFF0A111B.toInt()
    val BadgeBg = 0xFF0A111B.toInt()
    val BadgeText = 0xFF37E6DC.toInt()

    /** 状态胶囊、顶栏按钮、次级按钮的共用底色 = 原型 --panel-2。 */
    val PillBg = 0xFF121B28.toInt()
    val TopBg = 0xFF121B28.toInt()

    /** 顶栏按钮描边 = 原型 --line-strong。 */
    val TopStroke = 0x428CAFD7

    /** 次要文字（副标题、页脚、状态说明）= 原型 --muted。 */
    val SubText = 0xFF8B99AC.toInt()

    /** 更淡一档灰（遥测、提示）= 原型 --dim。 */
    val FaintText = 0xFF6B7A8F.toInt()

    /** 大标题的近白色 = 原型 --text。 */
    val TitleText = 0xFFE9F1F9.toInt()

    /** 主按钮的亮青色与按钮上的深色字 = 原型 --acc / --acc-ink。 */
    val Cyan = 0xFF37E6DC.toInt()
    val InkOnCyan = 0xFF04211F.toInt()

    /** logo、圆点、选中卡副标题用的青 = 原型 --acc。 */
    val Teal = 0xFF37E6DC.toInt()

    /** logo 圆底 = 原型 --acc（实心方块）；插图面板底 = --inset（插图已删，常量留着兼容）。 */
    val LogoBg = 0xFF37E6DC.toInt()
    val ArtBg = 0xFF0A111B.toInt()

    // ---------- 新增令牌（供新样式直接引用） ----------

    /** 强调色上的深色字。 */
    val AccInk = 0xFF04211F.toInt()

    /** 强调色淡底（12% / 10%）。 */
    val AccSoft = 0x1F37E6DC
    val AccSoft2 = 0x1A37E6DC

    /** 强调色描边（40% / 55%）。 */
    val AccLine2 = 0x6637E6DC
    val AccLine5 = 0x8C37E6DC.toInt()

    /** 常规描边（14%）与加强描边（26%）。 */
    val Line = 0x248CAFD7
    val LineStrong = 0x428CAFD7

    /** 成功绿及其淡底/描边。 */
    val Ok = 0xFF3DDC97.toInt()
    val OkSoft = 0x143DDC97
    val OkLine = 0x663DDC97

    /** 危险红及其淡底（8% / 16%）/描边（45%）。 */
    val Danger = 0xFFFF6262.toInt()
    val DangerSoft = 0x14FF6262
    val DangerSoft2 = 0x29FF6262
    val DangerLine = 0x73FF6262

    /** 琥珀描边（40%）。 */
    val WarnLine = 0x66FFB454

    /** 信息蓝。 */
    val Blue = 0xFF4DA3FF.toInt()

    /** 凹陷底（分段容器、输入框、步骤区）。 */
    val Inset = 0xFF0A111B.toInt()

    /** 次级面板底（ghost 按钮、未选图标底）。 */
    val Panel2 = 0xFF121B28.toInt()

    /** 开关关闭时的圆点灰。 */
    val SwitchKnob = 0xFF7D8CA0.toInt()

    /** 二维码纸底。 */
    val QrPaper = 0xFFEEF4FA.toInt()

    /** 投屏等待覆盖层底（92% 不透明，静态无动画）。 */
    val WaitBg = 0xEB070B12.toInt()

    /** 最淡一档文字（遥测条、nav-foot）。 */
    val Dim = 0xFF6B7A8F.toInt()
}

/** 布局尺寸（dp，代码里按密度换算）。 */
internal object Dimens {
    /** 页边距：内容足够宽时。 */
    const val PAGE_SIDE = 48f

    /** 页边距：窄屏。 */
    const val PAGE_SIDE_NARROW = 16f

    /** 内容宽度不小于这个值才用宽页边距。 */
    const val WIDE_PAGE_MIN = 600f

    /** 高度不足这个值算紧凑排布（字号、间距缩一档）。 */
    const val COMPACT_HEIGHT = 560f

    /** 卡片圆角。 */
    const val CARD_RADIUS = 12f

    /** 卡片内边距。 */
    const val CARD_PADDING = 14f

    /** 原型圆角三档：大卡片 16 / 中卡片 12 / 小件 8。 */
    const val RADIUS_LG = 16f
    const val RADIUS_MD = 12f
    const val RADIUS_SM = 8f

    /** 连接码方框的边长上限（正常 / 紧凑），与格间距上限、间距比例一起用。 */
    const val PIN_BOX = 100f
    const val PIN_BOX_COMPACT = 56f
    const val PIN_GAP = 16f
    const val PIN_GAP_RATIO = 0.16f
}

/** dp / sp 换算成 px。 */
internal fun Context.dp(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

/** 当前是否紧凑排布（屏幕高度不足，上游同款判定：高度 < 560dp）。 */
internal fun Context.compactLayout(): Boolean = resources.displayMetrics.heightPixels < dp(Dimens.COMPACT_HEIGHT)