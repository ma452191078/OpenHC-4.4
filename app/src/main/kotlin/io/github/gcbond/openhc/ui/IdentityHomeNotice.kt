package io.github.gcbond.openhc.ui

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import io.github.gcbond.openhc.IdentityLoader
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.connection.ConnectionUiState
import io.github.gcbond.openhc.connection.Notice

/**
 * 首页顶上的兜底提示：安装包里的连接参数缺少或不能用时一张醒目的卡片，正常时不显示。
 * 连接参数不进仓库，构建时打进安装包（见 app/build.gradle.kts），软件里没有地方填写。
 * （4.4 fork：View 体系版，观感照 Compose 版——提醒色淡底卡片。）
 */
internal fun identityHomeNotice(context: Context, identity: IdentityLoader.Result): View? {
    val text = when (identity) {
        is IdentityLoader.Result.Loaded -> return null
        IdentityLoader.Result.Missing -> context.getString(R.string.home_identity_missing)
        is IdentityLoader.Result.Invalid -> context.getString(R.string.home_identity_invalid, identity.reason)
    }
    val card = cardBox(context, Palette.WarnTint)
    card.addView(
        TextView(context).apply {
            setText(R.string.home_identity_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(Palette.Warn)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        },
    )
    card.addView(
        TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Palette.Text)
        },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(4f)
        },
    )
    return card
}

/**
 * 身份参数的状态由 [identityHomeNotice] 按当前结果显示，状态卡里不再重复。
 */
internal fun ConnectionUiState.withoutIdentityNotice(): ConnectionUiState =
    if (notice == Notice.IdentityMissing || notice is Notice.IdentityInvalid) copy(notice = null) else this
