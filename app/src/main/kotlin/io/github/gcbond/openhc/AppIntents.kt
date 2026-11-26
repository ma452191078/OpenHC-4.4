package io.github.gcbond.openhc

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

internal object AppIntents {
    /** 打开首页（通知、系统 VPN 设置里点本连接时用）。 */
    fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
