package io.github.gcbond.openhc.platform.media

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.RemoteControlClient
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import java.io.Closeable

/**
 * 媒体按钮会话：本应用不在前台时，系统按「谁在放媒体声音」把方向盘、媒体按钮派给它。
 *
 * - 建好就是活动的，播放状态固定为「正在播放」，声明播放、暂停、播放暂停、上一首、下一首、停止。
 *   安卓 8.0 起系统主要看哪个应用最近在放媒体声音（我们正放着手机的音乐），8.0 以下看活动会话和播放状态。
 * - 按键原样交给 [Listener.onMediaKey]（按下、抬起都有）；它返回 false 的才按系统默认方式转成播放控制命令。
 *   播放控制命令（来自车机界面上的媒体控件等）交给 [Listener.onCommand]。
 * - 回调都在主线程上。[close] 后不再回调。
 * - 不申请音频焦点，也不出声；什么时候建、什么时候关由调用方决定（例如只在替手机拿着媒体焦点时）。
 *
 * ## 安卓版本（4.4 fork）
 * 5.0（API 21）起走 framework 的 MediaSession（[ModernSession]，行为同原来）。更早的系统连 MediaSession
 * 这个类都没有，退回旧媒体键路径（[LegacySession]）：清单里声明广播接收器 [MediaButtonReceiver]
 * （在 platform 的清单里声明即可，库清单会合并进应用；没声明的话构造抛 [IllegalStateException]，
 * 调用方捕获后降级成「只在投屏页前台时转键」），AudioManager 把媒体按钮广播派给它，
 * 再用 RemoteControlClient（已弃用，4.4 唯一正路）声明播放状态和放哪些播放控制键。两个实现各自放在独立的
 * 内部类里：4.4 上不会加载 [ModernSession]，21+ 上不会加载 [LegacySession]，互不牵连。
 */
class MediaKeySession(context: Context, tag: String, private val listener: Listener) : Closeable {

    interface Listener {
        /** 一个媒体按钮事件；返回 true 表示处理了。 */
        fun onMediaKey(event: KeyEvent): Boolean

        /** 一个播放控制命令。 */
        fun onCommand(command: MediaKeyForwarder.Command)
    }

    private val impl: Closeable
    private var closed = false

    init {
        // 4.4 fork：分支放在构造里，两个实现类按需加载（Dalvik 只在用到时才解析方法体里引用的类）。
        val appContext = context.applicationContext ?: context
        impl = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ModernSession(appContext, tag)
        } else {
            LegacySession(appContext)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        impl.close()
    }

    /**
     * 21+ 的实现：framework 的 MediaSession。原实现原样搬进来，一条逻辑没改。
     */
    private inner class ModernSession(context: Context, tag: String) : Closeable {
        private val session = MediaSession(context, tag)

        init {
            session.setCallback(Callback(), Handler(Looper.getMainLooper()))
            // 安卓 8.0 起这两个标志总是生效、接口已弃用；更低的版本要显式声明才收得到媒体按钮。
            @Suppress("DEPRECATION")
            session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(ACTIONS)
                    .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                    .build(),
            )
            session.isActive = true
        }

        override fun close() {
            session.isActive = false
            session.release()
        }

        private inner class Callback : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                if (closed) return false
                val event = keyEventOf(mediaButtonIntent) ?: return super.onMediaButtonEvent(mediaButtonIntent)
                return listener.onMediaKey(event) || super.onMediaButtonEvent(mediaButtonIntent)
            }

            override fun onPlay() = command(MediaKeyForwarder.Command.PLAY)

            override fun onPause() = command(MediaKeyForwarder.Command.PAUSE)

            override fun onSkipToNext() = command(MediaKeyForwarder.Command.NEXT)

            override fun onSkipToPrevious() = command(MediaKeyForwarder.Command.PREVIOUS)

            override fun onStop() = command(MediaKeyForwarder.Command.STOP)

            private fun command(command: MediaKeyForwarder.Command) {
                if (!closed) listener.onCommand(command)
            }
        }
    }

    /**
     * 4.4 fork：5.0 前的旧媒体键路径。媒体按钮广播派给清单里声明的 [MediaButtonReceiver]，
     * 它转交给当前会话（[dispatchLegacyMediaKey]）；播放控制用 RemoteControlClient 声明
     * （方向盘上的播放控制键、车机界面上的媒体控件走它）。清单里没声明接收器就抛 [IllegalStateException]，
     * 调用方捕获后降级为只在投屏页前台时转键。广播天然在主线程上送达，回调线程同 [ModernSession]。
     */
    private inner class LegacySession(context: Context) : Closeable {
        private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        private val componentName = ComponentName(context, MediaButtonReceiver::class.java)
        private var remote: RemoteControlClient? = null

        init {
            try {
                context.packageManager.getReceiverInfo(componentName, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                throw IllegalStateException("清单里没声明媒体按钮接收器 $componentName，旧媒体键路径走不了", e)
            }
            @Suppress("DEPRECATION")
            audioManager.registerMediaButtonEventReceiver(componentName)
            val pending = PendingIntent.getBroadcast(
                context,
                0,
                Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(componentName),
                PendingIntent.FLAG_UPDATE_CURRENT,
            )
            @Suppress("DEPRECATION")
            val rcc = RemoteControlClient(pending)
            remote = rcc
            @Suppress("DEPRECATION")
            audioManager.registerRemoteControlClient(rcc)
            @Suppress("DEPRECATION")
            rcc.setTransportControlFlags(TRANSPORT_FLAGS)
            @Suppress("DEPRECATION")
            rcc.setPlaybackState(RemoteControlClient.PLAYSTATE_PLAYING)
            legacyHook = ::onMediaKey
        }

        /** 一个媒体按钮事件：先给消费方；没被消费的按系统默认方式转成播放控制命令。 */
        private fun onMediaKey(event: KeyEvent): Boolean {
            if (closed) return false
            if (listener.onMediaKey(event)) return true
            // 按下只记账（消耗掉事件），抬起才发命令——同 MediaSession 的默认行为；自动重复也不发。
            val command = commandOf(event.keyCode)
            if (command == null) {
                // 媒体键但不在声明的控制里（快退、快进）：吃掉、什么都不做，别让系统拿去叫起别的应用。
                return event.keyCode in MediaKeyForwarder.KEY_CODES
            }
            if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) listener.onCommand(command)
            return true
        }

        /** 按键码找对应的播放控制命令；不在声明的控制里（快退、快进）返回 null。 */
        private fun commandOf(keyCode: Int): MediaKeyForwarder.Command? =
            MediaKeyForwarder.Command.values().firstOrNull { it.keyCode == keyCode }

        override fun close() {
            legacyHook = null
            val rcc = remote
            remote = null
            if (rcc != null) {
                try {
                    @Suppress("DEPRECATION")
                    audioManager.unregisterRemoteControlClient(rcc)
                } catch (e: RuntimeException) {
                    // 注销不掉就放着（会话结束后系统不再往这里派键）。
                }
            }
            try {
                @Suppress("DEPRECATION")
                audioManager.unregisterMediaButtonEventReceiver(componentName)
            } catch (e: RuntimeException) {
                // 同上。
            }
        }
    }

    companion object {
        private const val ACTIONS = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP

        /** 4.4 fork：旧路径声明支持的控制键，同 [ACTIONS] 对齐（常量编译期内联，4.4 上不加载 PlaybackState）。 */
        private const val TRANSPORT_FLAGS = RemoteControlClient.FLAG_KEY_MEDIA_PLAY or
            RemoteControlClient.FLAG_KEY_MEDIA_PAUSE or RemoteControlClient.FLAG_KEY_MEDIA_PLAY_PAUSE or
            RemoteControlClient.FLAG_KEY_MEDIA_NEXT or RemoteControlClient.FLAG_KEY_MEDIA_PREVIOUS or
            RemoteControlClient.FLAG_KEY_MEDIA_STOP

        /** 4.4 fork：当前活动旧路径会话的媒体键入口（主线程广播回调）。 */
        @Volatile
        private var legacyHook: ((KeyEvent) -> Boolean)? = null

        /** 4.4 fork：[MediaButtonReceiver] 收到媒体按钮广播时调；没有活动会话或没人处理返回 false。 */
        fun dispatchLegacyMediaKey(event: KeyEvent): Boolean = legacyHook?.invoke(event) ?: false

        private fun keyEventOf(intent: Intent): KeyEvent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }
    }
}
