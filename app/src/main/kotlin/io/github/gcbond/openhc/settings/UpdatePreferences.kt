package io.github.gcbond.openhc.settings

import android.content.Context
import androidx.core.content.edit

/**
 * 版本更新相关的设置（单独一个 SharedPreferences 文件）。任意线程可读写。
 */
class UpdatePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 自动检查更新（默认开）：打开应用时在后台问一次 GitHub，有新版本才提示。 */
    var autoCheck: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CHECK, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_CHECK, value) }

    /** 发现新版本时弹窗提醒（默认开）：关掉后只在设置「更新」页显示，不主动打扰。 */
    var notifyDialog: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_DIALOG, true)
        set(value) = prefs.edit { putBoolean(KEY_NOTIFY_DIALOG, value) }

    private companion object {
        const val FILE = "openhc-update"
        const val KEY_AUTO_CHECK = "auto_check"
        const val KEY_NOTIFY_DIALOG = "notify_dialog"
    }
}
