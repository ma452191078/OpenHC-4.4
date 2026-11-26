package io.github.gcbond.openhc.platform.media

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.io.Closeable
import java.util.EnumMap

/**
 * 按 [AudioFocusPolicy] 的决定向系统申请、放弃音频焦点，把系统的焦点变化交回给它。
 *
 * - 线程：所有方法任意线程调用、不阻塞；决策和安卓调用都在自己的线程上串行做，[Listener] 也在那个线程上回调。
 * - 安卓 8.0 起每个用途一份 `AudioFocusRequest`（带音轨同样的 AudioAttributes；媒体允许「稍后给」，
 *   压低交给系统做）；更低的版本用老接口按流类型申请，压低由我们自己做。
 * - [close] 放掉全部焦点、停线程；之后的调用都不再生效。
 */
class AudioFocusController(context: Context, private val listener: Listener) : Closeable {

    interface Listener {
        /** 这个用途的播放器该用的音量（0～1）。 */
        fun onVolume(usage: AudioUsage, volume: Float)

        /** 要告诉手机的焦点变化。 */
        fun onPhoneChange(change: AudioFocusPolicy.PhoneChange)

        /** 一行中文日志。 */
        fun onLog(message: String)

        /**
         * 媒体焦点是不是由我们替手机拿着（见 [AudioFocusPolicy.holds]），变化时回调一次；[close] 时报 false。
         * 例如媒体按钮会话只在拿着时存在，免得车机收音机在放时方向盘键被我们拿走。
         */
        fun onMediaFocus(held: Boolean) {}
    }

    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val thread = HandlerThread("OpenHC-AudioFocus").also { it.start() }
    private val handler = Handler(thread.looper)
    private val policy = AudioFocusPolicy(systemDucks = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)

    // 以下只在自己的线程上访问。
    private val listeners = EnumMap<AudioUsage, AudioManager.OnAudioFocusChangeListener>(AudioUsage::class.java)
    private val requests = EnumMap<AudioUsage, Any>(AudioUsage::class.java)
    private var closed = false
    private var mediaHeld = false
    private val tick = Runnable { step { policy.onTick(now()) } }

    /** 手机要这个用途的焦点；[gain] 是它要的类型（null 按用途的默认）。 */
    fun phoneRequested(usage: AudioUsage, gain: AudioFocusPolicy.Gain? = null) = post { policy.phoneRequested(usage, now(), gain) }

    fun phoneAbandoned(usage: AudioUsage) = post { policy.phoneAbandoned(usage, now()) }

    fun phoneAbandonedAll() = post { policy.phoneAbandonedAll(now()) }

    fun soundStarted(usage: AudioUsage) = post { policy.soundStarted(usage, now()) }

    fun soundStopped(usage: AudioUsage) = post { policy.soundStopped(usage, now()) }

    /** 一次数据会话被手机拆掉：放掉焦点（通话中钉住的通话焦点除外，手机重新出声时会再要）。 */
    fun dataSessionEnded() = post { policy.dataSessionEnded() }

    /** 手机报通话状态（来电、通话中、挂断）。 */
    fun callStateChanged(phase: AudioFocusPolicy.CallPhase) = post { policy.callStateChanged(phase, now()) }

    /** 手机报语音助手在用 / 空闲。 */
    fun assistantStateChanged(active: Boolean) = post { policy.assistantStateChanged(active, now()) }

    override fun close() {
        handler.post {
            if (!closed) {
                execute(policy.releaseAll())
                reportMediaFocus()
                closed = true
            }
            thread.quitSafely()
        }
    }

    // ---------------- 自己的线程 ----------------

    private fun post(block: () -> List<AudioFocusPolicy.Command>) {
        handler.post { step(block) }
    }

    /** 跑一步决策、执行它给出的动作，再按下一次到期时间排好定时。 */
    private fun step(block: () -> List<AudioFocusPolicy.Command>) {
        if (closed) return
        execute(block())
        reportMediaFocus()
        handler.removeCallbacks(tick)
        policy.nextDeadlineMillis?.let { at -> handler.postDelayed(tick, (at - now()).coerceAtLeast(0)) }
    }

    private fun reportMediaFocus() {
        val held = policy.holds(AudioUsage.MEDIA)
        if (held == mediaHeld) return
        mediaHeld = held
        listener.onMediaFocus(held)
    }

    private fun execute(commands: List<AudioFocusPolicy.Command>) {
        for (command in commands) {
            when (command) {
                is AudioFocusPolicy.Command.Request -> {
                    val result = request(command.usage, command.gain)
                    listener.onLog("音频焦点：$command → ${result.label()}")
                    execute(policy.requestResult(command.usage, result, now()))
                }
                is AudioFocusPolicy.Command.Abandon -> {
                    abandon(command.usage)
                    listener.onLog("音频焦点：$command")
                }
                is AudioFocusPolicy.Command.Volume -> {
                    listener.onLog("音频焦点：$command")
                    listener.onVolume(command.usage, command.level.volume)
                }
                is AudioFocusPolicy.Command.NotifyPhone -> {
                    listener.onLog("音频焦点：$command")
                    listener.onPhoneChange(command.change)
                }
            }
        }
    }

    private fun request(usage: AudioUsage, gain: AudioFocusPolicy.Gain): AudioFocusPolicy.Result {
        val manager = audioManager ?: return AudioFocusPolicy.Result.FAILED
        val androidGain = when (gain) {
            AudioFocusPolicy.Gain.GAIN -> AudioManager.AUDIOFOCUS_GAIN
            AudioFocusPolicy.Gain.TRANSIENT -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            AudioFocusPolicy.Gain.TRANSIENT_EXCLUSIVE -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            AudioFocusPolicy.Gain.TRANSIENT_MAY_DUCK -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        }
        val code = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(androidGain)
                    .setAudioAttributes(usage.attributes())
                    .setAcceptsDelayedFocusGain(usage == AudioUsage.MEDIA)
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener(listenerFor(usage), handler)
                    .build()
                requests[usage] = request
                manager.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                manager.requestAudioFocus(listenerFor(usage), streamOf(usage), androidGain)
            }
        } catch (e: RuntimeException) {
            listener.onLog("音频焦点：申请${usage.label}出错（${e.javaClass.simpleName}：${e.message}），按被拒绝处理")
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
        return when (code) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> AudioFocusPolicy.Result.GRANTED
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> AudioFocusPolicy.Result.DELAYED
            else -> AudioFocusPolicy.Result.FAILED
        }
    }

    private fun abandon(usage: AudioUsage) {
        val manager = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                (requests.remove(usage) as? AudioFocusRequest)?.let { manager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                listeners[usage]?.let { manager.abandonAudioFocus(it) }
            }
        } catch (e: RuntimeException) {
            listener.onLog("音频焦点：放弃${usage.label}出错（${e.javaClass.simpleName}：${e.message}）")
        }
    }

    /** 每个用途一个回调对象：系统按它区分是谁的焦点。回调转到自己的线程上交给决策。 */
    private fun listenerFor(usage: AudioUsage): AudioManager.OnAudioFocusChangeListener = listeners.getOrPut(usage) {
        AudioManager.OnAudioFocusChangeListener { change ->
            handler.post {
                val mapped = changeOf(change)
                listener.onLog("音频焦点：系统通知${usage.label} ${describe(change)}")
                if (mapped != null) step { policy.focusChanged(usage, mapped, now()) }
            }
        }
    }

    private fun now(): Long = SystemClock.elapsedRealtime()

    private companion object {
        fun streamOf(usage: AudioUsage): Int = when (usage) {
            AudioUsage.CALL -> AudioManager.STREAM_VOICE_CALL
            AudioUsage.RINGTONE -> AudioManager.STREAM_RING
            else -> AudioManager.STREAM_MUSIC
        }

        fun changeOf(change: Int): AudioFocusPolicy.Change? = when (change) {
            AudioManager.AUDIOFOCUS_GAIN, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
            -> AudioFocusPolicy.Change.GAIN
            AudioManager.AUDIOFOCUS_LOSS -> AudioFocusPolicy.Change.LOSS
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> AudioFocusPolicy.Change.LOSS_TRANSIENT
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> AudioFocusPolicy.Change.LOSS_TRANSIENT_CAN_DUCK
            else -> null
        }

        fun describe(change: Int): String = when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> "长期失去焦点"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "暂时失去焦点"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "暂时失去焦点（可压低）"
            else -> if (change > 0) "拿回焦点" else "焦点变化 $change"
        }

        fun AudioFocusPolicy.Result.label(): String = when (this) {
            AudioFocusPolicy.Result.GRANTED -> "拿到"
            AudioFocusPolicy.Result.DELAYED -> "要等（稍后给）"
            AudioFocusPolicy.Result.FAILED -> "被拒绝"
        }
    }
}
