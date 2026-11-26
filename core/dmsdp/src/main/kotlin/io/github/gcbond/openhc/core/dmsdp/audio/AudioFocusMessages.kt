package io.github.gcbond.openhc.core.dmsdp.audio

import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.common.codec.JsonCodec
import io.github.gcbond.openhc.core.common.toHex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * 出声通道：车机按用途分开放的几路声音，各自一个播放器、各自申请音频焦点。
 *
 * 通话和铃声与媒体在同一个扬声器数据会话里，按 SSRC 分路（SSRC 高 16 位是用途，见 [AudioBus.usageOf]）。
 */
enum class AudioRoute(val label: String) {
    MEDIA("媒体"),
    NAVIGATION("导航"),

    /** 提示音、语音助手、通知等短促的声音。 */
    VOICE("提示与语音"),
    CALL("通话"),
    RINGTONE("铃声"),
    ;

    companion object {
        /**
         * 用途号对应的出声通道。用途号与安卓 `AudioAttributes.USAGE_*` 的取值相同：1 媒体、14 游戏 → 媒体；12 导航 → 导航；
         * 2、3 通话 → 通话；6 铃声 → 铃声；4、5、7～11、13、16～18（闹钟、通知、提示音、语音助手等）→ 提示与语音
         * （本机的归类）。0（未知）和其他值返回 null。
         */
        fun ofUsage(usage: Int): AudioRoute? = when (usage) {
            USAGE_MEDIA, USAGE_GAME -> MEDIA
            USAGE_NAVIGATION -> NAVIGATION
            USAGE_CALL, USAGE_CALL_SIGNALLING -> CALL
            USAGE_RINGTONE -> RINGTONE
            4, 5, in 7..11, 13, 16, 17, 18 -> VOICE
            else -> null
        }

        const val USAGE_MEDIA = 1
        const val USAGE_CALL = 2
        const val USAGE_CALL_SIGNALLING = 3
        const val USAGE_RINGTONE = 6
        const val USAGE_NAVIGATION = 12
        const val USAGE_GAME = 14
    }
}

/**
 * 手机发来的一条音频焦点消息（设备消息 module 4）。type 决定含义：
 *
 * | type | 数据 | 含义 |
 * |---|---|---|
 * | 1～4 | 用途号的十进制 ASCII（例如 `"1"`） | 手机要为这个用途出声；type 就是要的焦点类型（1 长期、2 短暂、3 短暂并压低别人、4 短暂独占） |
 * | 5 | 同上 | 手机这个用途放完了，放弃焦点 |
 * | 14、16 | UTF-8 JSON `{"contentType":…,"focusType":…,"usage":…}` | 分流后单独一路的焦点：focusType 1～4 要、5 放弃 |
 * | 15 | UTF-8 JSON | 手机的多路音频策略（带非空的 `multiPolicy` 表示手机在分流） |
 * | 57 | 不看 | 放弃全部焦点 |
 * | 其他（如 11～13 音量） | — | 本机不解释，原样记录 |
 *
 * 数据对不上格式时不抛异常：[action] 记为 [Action.OTHER]、[problem] 写明原因（声音的问题不能连累设备会话）。
 * toString 是一行中文，不含设备标识。
 */
class PhoneAudioFocus internal constructor(
    val type: Int,
    val action: Action,
    /** 分流焦点消息（type 14、16）。 */
    val split: Boolean,
    /** 要的焦点类型 1～4（[Action.REQUEST] 才有）。 */
    val focusType: Int?,
    /** 用途号（安卓 USAGE_* 取值）；没有或解析不出来为 null。 */
    val usage: Int?,
    val contentType: Int?,
    /** 策略消息（type 15）里带了非空的 multiPolicy。 */
    val multiPolicy: Boolean,
    /** 格式不对时的原因；正常为 null。 */
    val problem: String?,
    data: ByteArray,
) {
    enum class Action(val label: String) {
        REQUEST("要焦点"),
        ABANDON("放弃焦点"),
        ABANDON_ALL("放弃全部焦点"),
        POLICY("多路策略"),
        OTHER("未解释"),
    }

    private val head = data.copyOf(minOf(data.size, MAX_SHOWN_BYTES))
    private val size = data.size

    /** 用途对应的出声通道；用途未知时为 null。 */
    val route: AudioRoute? get() = usage?.let { AudioRoute.ofUsage(it) }

    override fun toString(): String {
        val what = buildString {
            append("手机音频焦点（module 4 / type ").append(type)
            if (split) append("，分流")
            append("）：").append(action.label)
            focusType?.let { append("，类型 ").append(it).append("（").append(focusLabel(it)).append("）") }
            usage?.let { u -> append("，用途 ").append(u).append(route?.let { "（${it.label}）" } ?: "（未知）") }
            contentType?.let { append("，内容类型 ").append(it) }
            if (action == Action.POLICY) append(if (multiPolicy) "，带 multiPolicy（手机在分流）" else "，没带 multiPolicy")
            problem?.let { append("；").append(it) }
        }
        val bytes = if (action == Action.POLICY || split) "" else "（$size 字节：${head.toHex()}${if (size > head.size) "…" else ""}）"
        return what + bytes
    }

    companion object {
        const val MODULE_AUDIO = 4L
        const val TYPE_ABANDON = 5
        const val TYPE_SPLIT = 14
        const val TYPE_SPLIT_2 = 16
        const val TYPE_POLICY = 15
        const val TYPE_ABANDON_ALL = 57
        private const val MAX_SHOWN_BYTES = 16
        private const val MAX_USAGE_DIGITS = 4

        /** 解析一条 module 4 设备消息的数据。不抛异常。 */
        fun parse(type: Int, data: ByteArray): PhoneAudioFocus = when (type) {
            in 1..4 -> legacy(type, Action.REQUEST, type, data)
            TYPE_ABANDON -> legacy(type, Action.ABANDON, null, data)
            TYPE_SPLIT, TYPE_SPLIT_2 -> split(type, data)
            TYPE_POLICY -> policy(data)
            TYPE_ABANDON_ALL -> PhoneAudioFocus(type, Action.ABANDON_ALL, false, null, null, null, false, null, data)
            else -> PhoneAudioFocus(type, Action.OTHER, false, null, null, null, false, null, data)
        }

        fun focusLabel(focusType: Int): String = when (focusType) {
            1 -> "长期"
            2 -> "短暂"
            3 -> "短暂，压低别人"
            4 -> "短暂，独占"
            else -> "未知"
        }

        /** type 1～5：数据是用途号的十进制 ASCII。 */
        private fun legacy(type: Int, action: Action, focusType: Int?, data: ByteArray): PhoneAudioFocus {
            val text = String(data, Charsets.ISO_8859_1)
            val usage = text.takeIf { it.length in 1..MAX_USAGE_DIGITS && it.all { c -> c in '0'..'9' } }?.toInt()
            val problem = if (usage == null) "数据不是十进制用途号，用途按未知处理" else null
            return PhoneAudioFocus(type, action, false, focusType, usage, null, false, problem, data)
        }

        /** type 14、16：`{"contentType":…,"focusType":…,"usage":…}`，数字也接受写成字符串。 */
        private fun split(type: Int, data: ByteArray): PhoneAudioFocus {
            val json = parseJson(data)
                ?: return PhoneAudioFocus(type, Action.OTHER, true, null, null, null, false, "数据不是 JSON 对象", data)
            val focusType = intField(json, "focusType")
            val usage = intField(json, "usage")
            val contentType = intField(json, "contentType")
            fun other(problem: String) = PhoneAudioFocus(type, Action.OTHER, true, null, usage, contentType, false, problem, data)
            if (usage == null) return other("缺少整数 usage")
            return when (focusType) {
                in 1..4 -> PhoneAudioFocus(type, Action.REQUEST, true, focusType, usage, contentType, false, null, data)
                TYPE_ABANDON -> PhoneAudioFocus(type, Action.ABANDON, true, null, usage, contentType, false, null, data)
                else -> other("focusType ${focusType ?: "缺失"} 不认识")
            }
        }

        /** type 15：JSON 对象，`multiPolicy` 非空（字符串、对象或数组都算）表示手机在按多路策略分流。 */
        private fun policy(data: ByteArray): PhoneAudioFocus {
            val json = parseJson(data)
                ?: return PhoneAudioFocus(TYPE_POLICY, Action.OTHER, false, null, null, null, false, "策略不是 JSON 对象", data)
            val value = json["multiPolicy"]
            val present = when (value) {
                null, JsonNull -> false
                is JsonPrimitive -> value.content.isNotEmpty()
                is JsonObject -> value.isNotEmpty()
                is JsonArray -> value.isNotEmpty()
            }
            return PhoneAudioFocus(TYPE_POLICY, Action.POLICY, false, null, null, null, present, null, data)
        }

        private fun parseJson(data: ByteArray): JsonObject? = try {
            JsonCodec.parseObject(String(data, Charsets.UTF_8), "音频焦点消息")
        } catch (e: MalformedMessageException) {
            null
        }

        private fun intField(json: JsonObject, name: String): Int? {
            val value = json[name] as? JsonPrimitive ?: return null
            return value.intOrNull ?: value.content.takeIf { it.length in 1..9 && it.all { c -> c in '0'..'9' } }?.toInt()
        }
    }
}

/**
 * 手机的通话状态和语音助手状态：设备消息 type 504 `{"callState":N}`、type 505 `{"voiceState":N}`（UTF-8 JSON，
 * 数字也接受写成字符串）。这两种消息走的是 module 1（控制数据）；本机在 module 4（音频）上收到同样的 type 也认，
 * 日志里记下是哪个 module。
 */
object PhoneStateMessages {
    const val MODULE_CONTROL = 1L
    const val TYPE_CALL_STATE = 504
    const val TYPE_VOICE_STATE = 505

    /** 哪些 module 上的 504 / 505 归扬声器处理。 */
    fun accepts(module: Long, type: Int): Boolean =
        (module == MODULE_CONTROL || module == PhoneAudioFocus.MODULE_AUDIO) && (type == TYPE_CALL_STATE || type == TYPE_VOICE_STATE)

    /** 取出 `callState` / `voiceState` 的值；不是 JSON 对象、没有这个键或不是整数时为 null。 */
    fun parse(type: Int, data: ByteArray): Int? {
        val key = if (type == TYPE_CALL_STATE) "callState" else "voiceState"
        val json = try {
            JsonCodec.parseObject(String(data, Charsets.UTF_8), "通话或语音助手状态")
        } catch (e: MalformedMessageException) {
            return null
        }
        val value = json[key] as? JsonPrimitive ?: return null
        return value.intOrNull ?: value.content.takeIf { it.length in 1..9 && it.all { c -> c in '0'..'9' } }?.toInt()
    }
}

/** 手机的通话状态（type 504 的 callState）：0 挂断（没有通话）、1 来电、2 通话中。 */
enum class PhoneCallPhase(val code: Int, val label: String) {
    IDLE(0, "没有通话（挂断）"),
    RINGING(1, "来电"),
    ACTIVE(2, "通话中"),
    ;

    companion object {
        fun of(code: Int): PhoneCallPhase? = entries.firstOrNull { it.code == code }
    }
}

/**
 * 车机告诉手机的焦点变化：设备消息 module 4，type 是下面的取值，数据是车机自己的设备 ID（UTF-8）。
 * 车机别的应用拿走焦点时发，手机据此暂停、压低或恢复它的声音。
 */
enum class CarAudioFocus(val type: Int, val label: String) {
    /** 车机这边又拿回了焦点：手机可以接着放。 */
    GAINED(1, "车机这边拿回焦点，手机可以接着放"),

    /** 被车机别的应用长期拿走：手机应当停下。 */
    LOST(-1, "被车机别的应用长期占用，手机应当停下"),

    /** 被车机别的应用暂时拿走：手机应当暂停，之后会再收到 [GAINED]。 */
    LOST_TRANSIENT(-2, "被车机别的应用暂时占用，手机应当暂停"),

    /** 车机别的应用要短暂出声：手机可以压低音量继续放。 */
    DUCK(-3, "车机别的应用要短暂出声，手机可以压低"),
    ;

    override fun toString() = "$label（type $type）"
}
