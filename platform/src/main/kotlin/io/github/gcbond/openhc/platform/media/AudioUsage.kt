package io.github.gcbond.openhc.platform.media

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build

/**
 * 一路声音的用途：决定播放器音轨的 AudioAttributes（车机按它选音量档和输出路径），也是申请音频焦点的单位
 * （见 [AudioFocusPolicy]）。枚举本身不碰安卓类，纯逻辑里也能用。
 */
enum class AudioUsage(val label: String) {
    MEDIA("媒体"),
    NAVIGATION("导航"),

    /** 提示音、语音助手、通知等短促的声音。 */
    ASSISTANT("提示与语音"),

    /** 通话：音轨属性按语音通话设，车机的回声消除要拿这一路当参考信号。 */
    CALL("通话"),
    RINGTONE("铃声"),
}

/**
 * 部分车机系统给「导航」扩展的内容类型：用途填媒体、内容类型填它，声音走车机独立的导航声道、跟车机的导航音量。
 * 标准安卓不认这个值：安卓 11 起 `setContentType` 抛 IllegalArgumentException；8～10 不抛、悄悄改掉
 * （8、9 把用途清成未知，10 把内容类型清成未知），只有把建好的属性读回来才看得出。
 */
const val CAR_NAVIGATION_CONTENT_TYPE = 6

/** 播放器音轨用的属性，以及给日志看的说明（普通情况为空）。 */
class TrackAttributes(val attributes: AudioAttributes, val note: String)

/**
 * 播放器音轨用的属性。导航那一路先试车机扩展的导航属性（用途媒体 + 内容类型 [CAR_NAVIGATION_CONTENT_TYPE]），
 * 系统抛异常（安卓 11 起）就退回标准导航用途（标准导航用途在很多车机上仍跟媒体音量走）。8～10 的系统不抛、
 * 悄悄改掉，这时照样用建出来的属性，只在说明里写明读回的值，别再写成「车机导航声道」。申请音频焦点仍用
 * [attributes]，播报时压低音乐靠的是焦点，不受这里影响。
 */
// 故意传标准以外的内容类型：车机系统扩展的值，标准系统会抛异常并退回。
@SuppressLint("WrongConstant")
fun AudioUsage.trackAttributes(): TrackAttributes {
    if (this != AudioUsage.NAVIGATION) return TrackAttributes(attributes(), "")
    return try {
        val car = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(CAR_NAVIGATION_CONTENT_TYPE)
            .build()
        val kept = car.usage == AudioAttributes.USAGE_MEDIA && car.contentType == CAR_NAVIGATION_CONTENT_TYPE
        val note = if (kept) {
            "车机导航声道（用途媒体 + 内容类型 $CAR_NAVIGATION_CONTENT_TYPE）"
        } else {
            "系统没认下车机扩展的导航内容类型（读回用途 ${car.usage}、内容类型 ${car.contentType}），多半跟媒体音量走"
        }
        TrackAttributes(car, note)
    } catch (e: IllegalArgumentException) {
        TrackAttributes(attributes(), "标准导航用途（系统不认车机扩展的导航内容类型）")
    }
}

/**
 * 音轨用的 AudioAttributes。提示与语音在安卓 8.0 起用 `USAGE_ASSISTANT`；更低的版本没有它，用
 * `USAGE_ASSISTANCE_SONIFICATION`。
 */
fun AudioUsage.attributes(): AudioAttributes {
    val builder = AudioAttributes.Builder()
    when (this) {
        AudioUsage.MEDIA -> builder.setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        AudioUsage.NAVIGATION -> builder.setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        AudioUsage.ASSISTANT -> builder.setUsage(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioAttributes.USAGE_ASSISTANT
            } else {
                AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
            },
        ).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        AudioUsage.CALL -> builder.setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        AudioUsage.RINGTONE -> builder.setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
    }
    return builder.build()
}

/**
 * 4.4 fork：安卓 5.0（API 21）前的系统没有 AudioAttributes，音轨和音频焦点都还按旧的 stream type 走。
 * 把用途映射过去：媒体、导航、提示与语音都跟媒体音量走，通话用通话流（车机的回声消除认这一路），铃声用铃声流。
 * 常量都是编译期内联的整数，4.4 上不会因为引用这个函数加载 [AudioAttributes] 一类的类。
 */
fun AudioUsage.legacyStreamType(): Int = when (this) {
    AudioUsage.CALL -> AudioManager.STREAM_VOICE_CALL
    AudioUsage.RINGTONE -> AudioManager.STREAM_RING
    else -> AudioManager.STREAM_MUSIC
}
