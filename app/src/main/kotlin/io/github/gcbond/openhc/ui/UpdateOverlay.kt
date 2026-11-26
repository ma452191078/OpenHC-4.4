package io.github.gcbond.openhc.ui

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.update.ReleaseInfo

/**
 * 更新相关的前层浮层（盖在 MainActivity 根布局上）：发现新版本的提示、下载进度、失败/完成提示。
 * 布局全部走自定义 View，和其余界面一致；点空白处不关闭，必须点按钮，避免误触打断。
 */
internal class UpdateOverlay private constructor(context: Context) : LinearLayout(context) {

    private val card: LinearLayout
    private var progressText: TextView? = null

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(0xE6070B12.toInt())
        isClickable = true
        isFocusable = true
        val width = minOf(
            resources.displayMetrics.widthPixels - context.dp(48f),
            context.dp(480f),
        )
        card = cardBox(context).apply {
            orientation = VERTICAL
            setPadding(context.dp(20f), context.dp(20f), context.dp(20f), context.dp(18f))
        }
        addView(
            card,
            LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            },
        )
    }

    /** 下载进度刷新（0..100）。 */
    fun setProgress(percent: Int) {
        progressText?.text = context.getString(R.string.update_progress_percent, percent.coerceIn(0, 100))
    }

    private fun title(text: CharSequence) {
        card.addView(
            TextView(context).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(Palette.Text)
                setTypeface(typeface, Typeface.BOLD)
            },
        )
    }

    private fun line(text: CharSequence, color: Int = Palette.Muted, sizeSp: Float = 13f) {
        card.addView(
            TextView(context).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
                setTextColor(color)
                setPadding(0, context.dp(6f), 0, 0)
            },
        )
    }

    private fun buttons(vararg specs: Triple<String, Boolean, () -> Unit>) {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        specs.forEachIndexed { index, (label, primary, action) ->
            row.addView(
                pillButton(context, label, primary = primary) { action() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    weight = 1f
                    if (index > 0) marginStart = context.dp(12f)
                },
            )
        }
        card.addView(
            row,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(18f)
            },
        )
    }

    companion object {
        /** 发现新版本：版本对照 + 更新说明 + 稍后/立即更新。 */
        fun prompt(
            context: Context,
            release: ReleaseInfo,
            currentVersion: String,
            onUpdate: () -> Unit,
            onLater: () -> Unit,
        ): UpdateOverlay {
            val overlay = UpdateOverlay(context)
            overlay.title(context.getString(R.string.update_prompt_title, release.version))
            overlay.line(
                context.getString(R.string.update_prompt_versions, currentVersion, release.version),
                Palette.Accent,
            )
            if (release.sizeBytes > 0) {
                overlay.line(context.getString(R.string.update_prompt_size, formatApkSize(release.sizeBytes)))
            }
            overlay.card.addView(
                notes(context, release.notes),
                LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(160f)).apply {
                    topMargin = context.dp(12f)
                },
            )
            overlay.buttons(
                Triple(context.getString(R.string.update_prompt_later), false, onLater),
                Triple(context.getString(R.string.update_prompt_now), true, onUpdate),
            )
            return overlay
        }

        /** 下载中：百分比 + 取消。 */
        fun progress(context: Context, percent: Int, onCancel: () -> Unit): UpdateOverlay {
            val overlay = UpdateOverlay(context)
            overlay.title(context.getString(R.string.update_progress_title))
            overlay.progressText = TextView(context).apply {
                text = context.getString(R.string.update_progress_percent, percent.coerceIn(0, 100))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(Palette.Accent)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, context.dp(10f), 0, 0)
            }
            overlay.card.addView(overlay.progressText)
            overlay.buttons(
                Triple(context.getString(R.string.update_cancel_download), false, onCancel),
            )
            return overlay
        }

        /** 失败/完成提示：文案 + 可选主操作（如去授权）+ 关闭。 */
        fun notice(
            context: Context,
            message: CharSequence,
            actionLabel: String? = null,
            onAction: (() -> Unit)? = null,
            onClose: () -> Unit,
        ): UpdateOverlay {
            val overlay = UpdateOverlay(context)
            overlay.title(message)
            if (actionLabel != null && onAction != null) {
                overlay.buttons(
                    Triple(context.getString(R.string.update_close), false, onClose),
                    Triple(actionLabel, true, onAction),
                )
            } else {
                overlay.buttons(
                    Triple(context.getString(R.string.update_close), true, onClose),
                )
            }
            return overlay
        }

        /** 更新说明：最多显示一小屏，太长的正文截断（完整内容在发行页）。 */
        private fun notes(context: Context, raw: String): View {
            val text = raw.trim().ifEmpty { context.getString(R.string.update_prompt_notes_empty) }
            val view = TextView(context).apply {
                this.text = if (text.length > MAX_NOTES) text.take(MAX_NOTES) + "…" else text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(Palette.Muted)
                setLineSpacing(context.dp(3f).toFloat(), 1f)
            }
            return ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                background = solidRound(Palette.Inset, context.dp(10f).toFloat(), context.dp(1f).toFloat(), Palette.Line)
                setPadding(context.dp(12f), context.dp(10f), context.dp(12f), context.dp(10f))
                addView(
                    view,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
            }
        }

        private const val MAX_NOTES = 1200
    }
}

/** 安装包大小（MB，两位小数）。 */
internal fun formatApkSize(bytes: Long): String =
    String.format(java.util.Locale.US, "%.2f MB", bytes / 1024.0 / 1024.0)
