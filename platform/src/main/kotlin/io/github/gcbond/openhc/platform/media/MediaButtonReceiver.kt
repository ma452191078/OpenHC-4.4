package io.github.gcbond.openhc.platform.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.KeyEvent

/**
 * 4.4 fork：旧媒体键路径的清单组件。5.0（API 21）前没有 framework 的 MediaSession，系统按
 * 「注册了媒体按钮接收器」派键：AudioManager 把方向盘、耳机线控的媒体按钮包成
 * [Intent.ACTION_MEDIA_BUTTON] 广播发给这里，再转交给当前的 [MediaKeySession]
 * （[MediaKeySession.dispatchLegacyMediaKey]）。21+ 上不会用到本类。
 *
 * 必须在清单里声明（platform 的库清单声明即可，合并进应用）：
 * ```
 * <receiver
 *     android:name=".media.MediaButtonReceiver"
 *     android:exported="true">
 *     <intent-filter>
 *         <action android:name="android.intent.action.MEDIA_BUTTON" />
 *     </intent-filter>
 * </receiver>
 * ```
 * 没声明的话 [MediaKeySession] 在 5.0 前构造时就抛 [IllegalStateException]，调用方降级成
 * 「只在投屏页前台时转键」。
 */
class MediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        val event = keyEventOf(intent) ?: return
        // 广播既然派到了这里，系统已经选了我们；转给会话，没人处理也就没人处理了。
        MediaKeySession.dispatchLegacyMediaKey(event)
    }

    private companion object {
        /** 同 [MediaKeySession] 里的取键逻辑：13 起用类型安全的新接口。 */
        private fun keyEventOf(intent: Intent): KeyEvent? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
            }
    }
}
