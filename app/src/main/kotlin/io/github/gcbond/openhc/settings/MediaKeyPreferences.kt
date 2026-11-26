package io.github.gcbond.openhc.settings

import android.content.Context
import androidx.core.content.edit

/**
 * 方向盘、媒体键切歌的开关（单独一个 SharedPreferences 文件），默认开。改动在下一次连接时生效。任意线程可读写。
 *
 * 开：投屏协商时声明按键（手机不选也不影响投屏），车机的媒体键转给手机。关：不声明，媒体键照车机原来的方式处理。
 */
class MediaKeyPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    private companion object {
        const val FILE = "openhc-media-keys"
        const val KEY_ENABLED = "enabled"
    }
}
