package io.github.gcbond.openhc.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import java.io.InputStreamReader
import kotlin.math.max
import kotlin.math.min

/**
 * SVG 子集运行时渲染器（首页插图用）。
 *
 * 4.4 兼容：只用 API 1 就有的 Path / Canvas / Paint / Matrix 能力，不碰 RippleDrawable、
 * Canvas.drawRoundRect、ViewOutlineProvider 这些 21+ 的东西。
 *
 * 支持的语法子集（与 app/src/main/assets/icons/ 下两张图对齐）：
 * - 根 `svg` 的 width/height 当 viewBox（顺带认 viewBox 属性）；
 * - 元素 circle（cx/cy/r）、rect（x/y/width/height/rx/ry；只给 rx 时按 SVG 规则 ry=rx）、path（d）；
 * - `transform="matrix(a b c d e f)"`（空格或逗号分隔；多段连写也认，另外顺带支持
 *   translate(tx[,ty]) / scale(s[,sy]) / rotate(deg)，将来换图不用改代码）；
 * - fill / stroke：#RGB、#RRGGBB、#RRGGBBAA、none；stroke-width（缺省按 1）；
 * - path 命令 M m L l H h V v Q q C c Z z（大写绝对、小写相对；数字空格或逗号分隔，
 *   支持前导小数点、科学计数法，以及 M 后省略 L 的隐式续画）。
 *
 * 解析不了的元素跳过；整段解析抛异常时吞掉，画一张空白图，不崩。
 */
internal class SvgIconDrawable(private val source: String) : Drawable() {

    /** 一个图形元素：标签名 + 属性表 + 自己的变换（没有变换时为 null）。 */
    private class Node(val tag: String, val attrs: Map<String, String>, val transform: Matrix?)

    private val nodes = ArrayList<Node>()

    /** viewBox 左上角与尺寸（用户坐标）。宽或高不为正时整张图不画。 */
    private var viewMinX = 0f
    private var viewMinY = 0f
    private var viewWidth = 0f
    private var viewHeight = 0f

    /** 根节点上的 fill/stroke/stroke-width：SVG 里这些是可继承属性，元素没写时落到根上。 */
    private var rootFill: String? = null
    private var rootStroke: String? = null
    private var rootStrokeWidth: String? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }

    /** 反复复用的临时对象：避免每帧建 Path/RectF。 */
    private val scratch = Path()
    private val rect = RectF()
    private val fit = Matrix()
    private val temp = Matrix()

    /** Drawable.setAlpha 的系数，画的时候乘到颜色 alpha 上。 */
    private var alphaFactor = 1f
    private var colorFilter: android.graphics.ColorFilter? = null

    init {
        parse()
    }

    // ---------- 解析 ----------

    private fun parse() {
        try {
            val rootText = Regex("<svg\\b[^>]*>").find(source)?.value ?: return
            val root = attrsOf(rootText)
            rootFill = root["fill"]
            rootStroke = root["stroke"]
            rootStrokeWidth = root["stroke-width"]
            val box = numbers(root["viewBox"])
            if (box.size >= 4) {
                viewMinX = box[0]
                viewMinY = box[1]
                viewWidth = box[2]
                viewHeight = box[3]
            }
            if (viewWidth <= 0f) viewWidth = number(root["width"], 0f)
            if (viewHeight <= 0f) viewHeight = number(root["height"], 0f)

            for (match in Regex("<(circle|rect|path)\\b([^>]*)>").findAll(source)) {
                val attrs = attrsOf(match.groupValues[2])
                nodes.add(Node(match.groupValues[1], attrs, transformOf(attrs["transform"])))
            }
        } catch (_: Exception) {
            // 子集之外的写法（渐变、剪裁、嵌套 g 等）一律不认：留已解析的部分，没有就画空白。
        }
    }

    /** 从一段标签文本里取出 name="value" 属性。 */
    private fun attrsOf(text: String): Map<String, String> {
        val map = HashMap<String, String>()
        for (m in Regex("([A-Za-z_:.-]+)\\s*=\\s*\"([^\"]*)\"").findAll(text)) {
            map[m.groupValues[1]] = m.groupValues[2]
        }
        return map
    }

    /** 颜色：#RGB / #RRGGBB / #RRGGBBAA / none（null 表示不画这一层）。 */
    private fun colorOf(raw: String?): Int? {
        val value = raw?.trim() ?: return null
        if (value.isEmpty() || value.equals("none", ignoreCase = true)) return null
        return try {
            when {
                value.startsWith("#") -> {
                    val hex = value.substring(1)
                    when (hex.length) {
                        3 -> {
                            val r = Integer.parseInt("${hex[0]}${hex[0]}", 16)
                            val g = Integer.parseInt("${hex[1]}${hex[1]}", 16)
                            val b = Integer.parseInt("${hex[2]}${hex[2]}", 16)
                            Color.argb(255, r, g, b)
                        }
                        6 -> Color.argb(255, Integer.parseInt(hex.substring(0, 2), 16), Integer.parseInt(hex.substring(2, 4), 16), Integer.parseInt(hex.substring(4, 6), 16))
                        // SVG 的 8 位是 RRGGBBAA，和安卓的 AARRGGBB 顺序不同，自己拆。
                        8 -> Color.argb(
                            Integer.parseInt(hex.substring(6, 8), 16),
                            Integer.parseInt(hex.substring(0, 2), 16),
                            Integer.parseInt(hex.substring(2, 4), 16),
                            Integer.parseInt(hex.substring(4, 6), 16),
                        )
                        else -> null
                    }
                }
                value.equals("white", ignoreCase = true) -> Color.WHITE
                value.equals("black", ignoreCase = true) -> Color.BLACK
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 变换串 → Matrix（暂不支持 skew；没有可用变换返回 null）。 */
    private fun transformOf(raw: String?): Matrix? {
        val text = raw?.trim()
        if (text.isNullOrEmpty()) return null
        return try {
            val out = Matrix()
            var any = false
            for (m in Regex("(matrix|translate|scale|rotate)\\s*\\(([^)]*)\\)").findAll(text)) {
                val args = numbers(m.groupValues[2])
                when (m.groupValues[1]) {
                    "matrix" -> if (args.size >= 6) {
                        // SVG matrix(a b c d e f)：x' = a·x + c·y + e；y' = b·x + d·y + f。
                        // 用 setPolyToPoly 三点定仿射（各版本语义都一致；setValues 的 16 元布局
                        // 在不同安卓版本上解释不同，4.4 上实测会把平移/缩放放错位）。
                        val a = args[0]
                        val b = args[1]
                        val c = args[2]
                        val d = args[3]
                        val e = args[4]
                        val f = args[5]
                        temp.reset()
                        temp.setPolyToPoly(
                            floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), 0,
                            floatArrayOf(e, f, a + e, b + f, c + e, d + f), 0,
                            3,
                        )
                        out.postConcat(temp)
                        any = true
                    }
                    "translate" -> if (args.isNotEmpty()) {
                        out.postTranslate(args[0], if (args.size >= 2) args[1] else 0f)
                        any = true
                    }
                    "scale" -> if (args.isNotEmpty()) {
                        out.postScale(args[0], if (args.size >= 2) args[1] else args[0])
                        any = true
                    }
                    "rotate" -> if (args.isNotEmpty()) {
                        out.postRotate(args[0])
                        any = true
                    }
                }
            }
            if (any) out else null
        } catch (_: Exception) {
            null
        }
    }

    /** 按空格/逗号切一串数字（viewBox、transform 参数用）。 */
    private fun numbers(text: String?): FloatArray {
        if (text.isNullOrEmpty()) return FloatArray(0)
        val parts = text.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        val out = FloatArray(parts.size)
        var n = 0
        for (part in parts) {
            val value = part.toFloatOrNull() ?: continue
            out[n++] = value
        }
        return if (n == out.size) out else out.copyOf(n)
    }

    /** 属性里的单个数值：带单位（"260px"）时取前面的数字，读不到用 default。 */
    private fun number(raw: String?, default: Float): Float {
        val value = raw?.trim() ?: return default
        if (value.isEmpty()) return default
        value.toFloatOrNull()?.let { return it }
        val lead = NumberReader(value).number()
        return if (lead != 0f) lead else default
    }

    // ---------- 绘制 ----------

    /** 等比缩放居中：按本 Drawable 的 bounds 把 viewBox 映射进去（线宽跟着一起缩放）。 */
    override fun draw(canvas: Canvas) {
        if (nodes.isEmpty() || viewWidth <= 0f || viewHeight <= 0f) return
        val box = bounds
        if (box.width() <= 0 || box.height() <= 0) return
        val scale = min(box.width() / viewWidth, box.height() / viewHeight)
        val dx = box.left + (box.width() - viewWidth * scale) / 2f - viewMinX * scale
        val dy = box.top + (box.height() - viewHeight * scale) / 2f - viewMinY * scale

        fit.reset()
        fit.postScale(scale, scale)
        fit.postTranslate(dx, dy)

        val save = canvas.save()
        canvas.concat(fit)
        for (node in nodes) {
            try {
                drawNode(canvas, node)
            } catch (t: Throwable) {
                // 单个节点画失败不拖垮整幅图（真机 ROM 的 Path API 行为可能有差异）。
                android.util.Log.e("OpenHC-Svg", "node draw failed tag=" + node.tag, t)
            }
        }
        canvas.restoreToCount(save)
    }

    private fun drawNode(canvas: Canvas, node: Node) {
        scratch.reset()
        val attrs = node.attrs
        when (node.tag) {
            "circle" -> {
                val r = number(attrs["r"], 0f)
                if (r <= 0f) return
                scratch.addCircle(number(attrs["cx"], 0f), number(attrs["cy"], 0f), r, Path.Direction.CW)
            }
            "rect" -> {
                val w = number(attrs["width"], 0f)
                val h = number(attrs["height"], 0f)
                if (w <= 0f || h <= 0f) return
                val x = number(attrs["x"], 0f)
                val y = number(attrs["y"], 0f)
                // rx/ry：只给一个时另一个跟着它（SVG 规则），并且都不能超过边长的一半。
                var rx = number(attrs["rx"], -1f)
                var ry = number(attrs["ry"], -1f)
                if (rx < 0f && ry >= 0f) rx = ry
                if (ry < 0f && rx >= 0f) ry = rx
                if (rx < 0f) rx = 0f
                if (ry < 0f) ry = 0f
                rx = min(rx, w / 2f)
                ry = min(ry, h / 2f)
                rect.set(x, y, x + w, y + h)
                if (rx > 0f && ry > 0f) {
                    scratch.addRoundRect(rect, rx, ry, Path.Direction.CW)
                } else {
                    scratch.addRect(rect, Path.Direction.CW)
                }
            }
            "path" -> {
                val d = attrs["d"] ?: return
                if (!buildPath(d, scratch)) return
            }
            else -> return
        }

        node.transform?.let { scratch.transform(it) }

        // fill：元素没写时继承根节点，根也没写才按 SVG 默认（黑色）；fill="none" 时 colorOf 返回 null，只描边。
        val fillRaw = attrs["fill"] ?: rootFill
        val fill = if (fillRaw == null) Color.BLACK else colorOf(fillRaw)
        val stroke = colorOf(attrs["stroke"] ?: rootStroke)
        val strokeWidth = number(attrs["stroke-width"] ?: rootStrokeWidth, if (stroke != null) 1f else 0f)

        if (fill != null) {
            fillPaint.color = tint(fill)
            fillPaint.colorFilter = colorFilter
            canvas.drawPath(scratch, fillPaint)
        }
        if (stroke != null && strokeWidth > 0f) {
            strokePaint.color = tint(stroke)
            strokePaint.colorFilter = colorFilter
            strokePaint.strokeWidth = max(strokeWidth, 0.01f)
            canvas.drawPath(scratch, strokePaint)
        }
    }

    /** 把 Drawable 的 alpha 乘进颜色（默认 alpha=255 时原样返回）。 */
    private fun tint(color: Int): Int {
        if (alphaFactor >= 1f) return color
        return Color.argb(
            (Color.alpha(color) * alphaFactor).toInt().coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color),
        )
    }

    /**
     * path 的 d → Path。支持 M m L l H h V v Q q C c Z z；
     * 子集之外的命令（A/S/T 等）整段放弃（返回 false，这个元素不画），免得画出错位的形状。
     */
    private fun buildPath(data: String, out: Path): Boolean {
        val t = NumberReader(data)
        var x = 0f
        var y = 0f
        var startX = 0f
        var startY = 0f
        while (true) {
            val c = t.nextCommand() ?: return true
            when (c) {
                'M' -> {
                    var first = true
                    while (t.hasNumber()) {
                        x = t.number(); y = t.number()
                        if (first) {
                            out.moveTo(x, y)
                            startX = x; startY = y; first = false
                        } else {
                            out.lineTo(x, y)
                        }
                    }
                    if (first) return false
                }
                'm' -> {
                    var first = true
                    while (t.hasNumber()) {
                        val dx = t.number(); val dy = t.number()
                        x += dx; y += dy
                        if (first) {
                            out.moveTo(x, y)
                            startX = x; startY = y; first = false
                        } else {
                            out.lineTo(x, y)
                        }
                    }
                    if (first) return false
                }
                'L' -> while (t.hasNumber()) {
                    x = t.number(); y = t.number(); out.lineTo(x, y)
                }
                'l' -> while (t.hasNumber()) {
                    x += t.number(); y += t.number(); out.lineTo(x, y)
                }
                'H' -> while (t.hasNumber()) {
                    x = t.number(); out.lineTo(x, y)
                }
                'h' -> while (t.hasNumber()) {
                    x += t.number(); out.lineTo(x, y)
                }
                'V' -> while (t.hasNumber()) {
                    y = t.number(); out.lineTo(x, y)
                }
                'v' -> while (t.hasNumber()) {
                    y += t.number(); out.lineTo(x, y)
                }
                'Q' -> while (t.hasNumber(4)) {
                    val x1 = t.number(); val y1 = t.number(); val x2 = t.number(); val y2 = t.number()
                    out.quadTo(x1, y1, x2, y2)
                    x = x2; y = y2
                }
                'q' -> while (t.hasNumber(4)) {
                    // 相对写法：控制点和终点都是相对当前点( x,y )的增量。
                    val x1 = x + t.number(); val y1 = y + t.number()
                    val x2 = x + t.number(); val y2 = y + t.number()
                    out.quadTo(x1, y1, x2, y2)
                    x = x2; y = y2
                }
                'C' -> while (t.hasNumber(6)) {
                    val x1 = t.number(); val y1 = t.number(); val x2 = t.number(); val y2 = t.number()
                    val x3 = t.number(); val y3 = t.number()
                    out.cubicTo(x1, y1, x2, y2, x3, y3)
                    x = x3; y = y3
                }
                'c' -> while (t.hasNumber(6)) {
                    val x1 = x + t.number(); val y1 = y + t.number()
                    val x2 = x + t.number(); val y2 = y + t.number()
                    val x3 = x + t.number(); val y3 = y + t.number()
                    out.cubicTo(x1, y1, x2, y2, x3, y3)
                    x = x3; y = y3
                }
                'Z', 'z' -> {
                    out.close()
                    x = startX; y = startY
                }
                else -> return false
            }
        }
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    /**
     * 共享已解析节点、但画笔独立的副本：节点解析后只读，可以安全共用；
     * colorFilter 存在各自的 Paint 上，多个 ImageView 各自上色互不影响（防串色）。
     */
    fun newCopy(): SvgIconDrawable {
        val copy = SvgIconDrawable("")
        copy.nodes.addAll(nodes)
        copy.viewMinX = viewMinX
        copy.viewMinY = viewMinY
        copy.viewWidth = viewWidth
        copy.viewHeight = viewHeight
        copy.rootFill = rootFill
        copy.rootStroke = rootStroke
        copy.rootStrokeWidth = rootStrokeWidth
        return copy
    }

    override fun setAlpha(alpha: Int) {
        alphaFactor = alpha.coerceIn(0, 255) / 255f
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        this.colorFilter = colorFilter
        invalidateSelf()
    }

    /** 原始 viewBox 尺寸做内在尺寸，调用方按 wrap_content 摆也能保持比例。 */
    override fun getIntrinsicWidth(): Int = if (viewWidth > 0f) viewWidth.toInt() else -1

    override fun getIntrinsicHeight(): Int = if (viewHeight > 0f) viewHeight.toInt() else -1

    companion object {
        /**
         * 从 assets 读一张 SVG 子集插图（例如 "icons/wireless.svg"）。
         * 读不到、解析不了都返回空白图，不抛异常。
         */
        fun fromAssets(context: Context, assetPath: String): SvgIconDrawable =
            try {
                context.assets.open(assetPath).use { stream ->
                    SvgIconDrawable(InputStreamReader(stream, Charsets.UTF_8).readText())
                }
            } catch (_: Exception) {
                SvgIconDrawable("")
            }
    }
}

/**
 * 图标缓存：按 asset 路径缓存解析好的基础 Drawable，避免重复读 asset / 重复解析。
 * [get] 返回共享节点、画笔独立的副本，调用方直接 setColorFilter 上色即可，不会串色。
 */
internal object IconCache {
    private val base = mutableMapOf<String, SvgIconDrawable>()

    fun get(context: Context, path: String): SvgIconDrawable =
        base.getOrPut(path) { SvgIconDrawable.fromAssets(context, path) }.newCopy()
}

/**
 * path 的 d 属性读数器：命令字母 + 数字（空格/逗号分隔，负号可以当分隔符用，
 * 也认前导小数点「.5」和科学计数法「1e-3」）。
 */
private class NumberReader(private val text: String) {

    private var i = 0

    private fun skipSeparators() {
        while (i < text.length) {
            val c = text[i]
            if (c == ' ' || c == ',' || c == '\t' || c == '\n' || c == '\r') i++ else break
        }
    }

    /** 下一个命令字母；位置上是数字或到末尾时返回 null（调用方按隐式续画处理）。 */
    fun nextCommand(): Char? {
        skipSeparators()
        if (i >= text.length) return null
        val c = text[i]
        return if (c.isLetter()) {
            i++
            c
        } else {
            null
        }
    }

    /** 当前位置往后是不是一个数（缺参数时用来收尾，避免死循环）。 */
    fun hasNumber(count: Int = 1): Boolean {
        val save = i
        var found = 0
        var ok = true
        repeat(count) {
            skipSeparators()
            if (i < text.length && (text[i] == '-' || text[i] == '+' || text[i] == '.' || text[i].isDigit())) {
                readNumberRaw()
                found++
            } else {
                ok = false
            }
        }
        i = save
        return ok && found == count
    }

    /** 读一个数（读不到给 0，不让界面崩）。 */
    fun number(): Float {
        skipSeparators()
        return readNumberRaw()
    }

    private fun readNumberRaw(): Float {
        val start = i
        if (i < text.length && (text[i] == '+' || text[i] == '-')) i++
        var seenDot = false
        var seenExp = false
        while (i < text.length) {
            val c = text[i]
            when {
                c.isDigit() -> i++
                c == '.' && !seenDot && !seenExp -> {
                    seenDot = true
                    i++
                }
                (c == 'e' || c == 'E') && !seenExp && i > start -> {
                    seenExp = true
                    i++
                    if (i < text.length && (text[i] == '+' || text[i] == '-')) i++
                }
                else -> return text.substring(start, i).toFloatOrNull() ?: 0f
            }
        }
        return text.substring(start, i).toFloatOrNull() ?: 0f
    }
}
