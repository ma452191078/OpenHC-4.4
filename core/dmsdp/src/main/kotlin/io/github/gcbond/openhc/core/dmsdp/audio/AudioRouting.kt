package io.github.gcbond.openhc.core.dmsdp.audio

/**
 * RTP 头里 SSRC 那 4 个字节不是随机数：低 16 位是 1000 + 路号（第 0 路是 1000、第 8 路是 1008），高 16 位是用途号
 * （安卓 `USAGE_*` 的取值，例如通话 2、铃声 6；为 0 表示没带用途）。低 16 位不在约定范围的路号按未知（-1）处理，不猜。
 */
object AudioBus {
    const val UNKNOWN = -1

    /** 多音频总线下音乐固定走的路号。 */
    const val MUSIC = 8

    /** 多音频总线下导航语音单独走的路号（只在播报时有包）。 */
    const val SPLIT = 0

    private const val BASE = 1000L
    private const val MAX_BUS = 63

    /** 路号：SSRC 低 16 位减 1000。 */
    fun of(ssrc: Long): Int {
        val low = ssrc and LOW_MASK
        return if (low in BASE..BASE + MAX_BUS) (low - BASE).toInt() else UNKNOWN
    }

    /** 用途号：SSRC 高 16 位（0 表示没带）。 */
    fun usageOf(ssrc: Long): Int = ((ssrc ushr USAGE_SHIFT) and LOW_MASK).toInt()

    fun label(bus: Int): String = if (bus == UNKNOWN) "未知路号" else "第 $bus 路"

    private const val LOW_MASK = 0xFFFFL
    private const val USAGE_SHIFT = 16
}

/**
 * 一次设备会话里，每个音频包放到哪个出声通道（纯逻辑，只在协议线程上用）。
 *
 * 车机在扬声器能力（属性 2102）的 extendInfo 里声明多音频总线后，手机会分流：音乐固定在第 8 路、一直在发；
 * 导航语音单独在第 0 路、只在播报时有包；每次播报前手机发一条分流焦点消息（type 14，例如
 * `{"contentType":1,"focusType":3,"usage":12}`），播完发 focusType 5。
 *
 * 判定规则：
 * - **还没看出手机在分流**：全部当媒体。手机不分流时导航混在媒体那一路里，不能整路搬去导航声道。
 * - **看出分流的依据**（这次设备会话里出现过任意一条）：分流焦点消息（type 14、16）；带非空 multiPolicy 的
 *   策略消息（type 15）；第 8 路的包。
 * - **分流之后**：第 8 路是媒体；第 0 路跟最近一条「要焦点」的分流消息的用途走（导航 → 导航，提示音、语音助手等
 *   → 提示与语音，通话 → 通话；媒体用途的那条说的是音乐那一路，不改第 0 路），还没收到时按导航。
 *   放弃焦点的消息不改第 0 路的去向：播报的尾巴可能晚于这条消息才到。
 *   其他路号（没见过）当媒体，宁可不分开，也不往导航声道里放音乐。
 * - 车机没声明多音频总线（[multiBus] 为 false）时一律当媒体，不看上面这些依据。
 *
 * SSRC 高 16 位带了用途时（[AudioBus.usageOf]）先看用途：通话、铃声一律放到各自的通道（回声消除要拿通话那一路当参考，
 * 属性必须对）；导航、提示与语音在声明了多音频总线时放到各自的通道；媒体用途、没带用途、用途不认识的再按上面的路号规则
 * （不认识的用途最后都当媒体，由调用方记日志）。
 */
internal class AudioRouter(private val multiBus: Boolean = true) {
    var splitting = false
        private set

    /** 第 0 路现在放到哪个通道。 */
    var splitRoute = AudioRoute.NAVIGATION
        private set

    /** 收到一条手机焦点消息；状态变了返回一句说明（给日志），没变返回 null。 */
    fun onFocus(message: PhoneAudioFocus): String? {
        if (!multiBus) return null
        val notes = ArrayList<String>(2)
        val evidence = message.split || (message.action == PhoneAudioFocus.Action.POLICY && message.multiPolicy)
        if (evidence && !splitting) {
            splitting = true
            notes += "看出手机在分流（${if (message.split) "收到分流焦点消息" else "策略消息带 multiPolicy"}）：" +
                "第 ${AudioBus.MUSIC} 路放媒体，第 ${AudioBus.SPLIT} 路单独放"
        }
        if (message.split && message.action == PhoneAudioFocus.Action.REQUEST) {
            val route = message.route
            if (route != null && route != AudioRoute.MEDIA && route != splitRoute) {
                splitRoute = route
                notes += "第 ${AudioBus.SPLIT} 路之后放到${route.label}通道（分流焦点消息的用途 ${message.usage}）"
            }
        }
        return if (notes.isEmpty()) null else notes.joinToString("；")
    }

    /** 收到一个音频包之前调用：第 8 路的包本身就说明手机在分流。状态变了返回一句说明。 */
    fun onBus(bus: Int): String? {
        if (!multiBus || bus != AudioBus.MUSIC || splitting) return null
        splitting = true
        return "收到第 ${AudioBus.MUSIC} 路的包，看出手机在分流：第 ${AudioBus.MUSIC} 路放媒体，第 ${AudioBus.SPLIT} 路单独放"
    }

    fun route(bus: Int, usage: Int = 0): AudioRoute {
        when (val byUsage = AudioRoute.ofUsage(usage)) {
            AudioRoute.CALL, AudioRoute.RINGTONE -> return byUsage
            AudioRoute.NAVIGATION, AudioRoute.VOICE -> if (multiBus) return byUsage
            AudioRoute.MEDIA, null -> Unit
        }
        return when {
            !multiBus || !splitting -> AudioRoute.MEDIA
            bus == AudioBus.SPLIT -> splitRoute
            else -> AudioRoute.MEDIA
        }
    }
}
