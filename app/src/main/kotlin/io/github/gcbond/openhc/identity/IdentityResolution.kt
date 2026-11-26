package io.github.gcbond.openhc.identity

import io.github.gcbond.openhc.IdentityLoader
import io.github.gcbond.openhc.core.common.identity.IdentityAssets

// 把安装包里身份参数文件的读取结果变成加载结果。纯逻辑，不引用安卓类。

/** 身份参数文件的读取结果。 */
sealed class StoredText {
    object Absent : StoredText() {
        override fun toString() = "Absent"
    }

    class Present(val text: String) : StoredText() {
        override fun toString() = "Present(${text.length} 字符，内容不显示)"
    }

    /** 文件在，但读不出来（读出错、太大）。[reason] 不含文件内容。 */
    class Unreadable(val reason: String) : StoredText() {
        override fun toString() = "Unreadable($reason)"
    }
}

object IdentityResolution {
    /** 安装包里那份（仓库里固定的 `openhc-identity.json`）：能用、没有、或者不能用。 */
    fun resolve(bundled: StoredText): IdentityLoader.Result = when (bundled) {
        is StoredText.Present -> parse(bundled.text)
        is StoredText.Unreadable -> IdentityLoader.Result.Invalid(bundled.reason)
        StoredText.Absent -> IdentityLoader.Result.Missing
    }

    private fun parse(text: String): IdentityLoader.Result {
        val assets: IdentityAssets = try {
            IdentityJson.parseText(text)
        } catch (e: IllegalArgumentException) {
            // 只取 message：JSON 语法错误的 cause 里可能带一段原文。
            return IdentityLoader.Result.Invalid(e.message ?: "格式不对")
        }
        return IdentityLoader.Result.Loaded(assets)
    }
}
