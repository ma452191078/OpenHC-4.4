package io.github.gcbond.openhc.core.dmsdp.audio

/**
 * 麦克风数据会话的编码。数字是手机在触发消息 `ParaAudio`、设参消息 `AudioParameter` 里写的 `Codec` 取值：
 * 0 是 AAC，1 是 PCM。注意它和车机发的 `Codec` 头不是一套编号（那边 1 表示「压缩」，见 [compressedFlag]）。
 */
enum class MicCodec(val paraValue: Int, val label: String) {
    AAC(0, "AAC"),
    PCM(1, "PCM"),
    ;

    /** 车机在 OPTIONS 里写的 `Codec` 头：压缩编码写 1，PCM 写 0。 */
    val compressedFlag: Int get() = if (this == AAC) 1 else 0

    companion object {
        fun fromPara(value: Int): MicCodec? = entries.firstOrNull { it.paraValue == value }
    }
}

/**
 * 车机向手机声明的麦克风能力：服务属性 2102 的内容。手机拿它和自己录音要的参数协商出这次会话的采样参数
 * （声明的 profile 里挑最接近的一个）；协商出的「采样率 × 声道 × 位深」不超过 520000 时手机自动改用 PCM，
 * 所以声明 16kHz 单声道应当拿到 PCM（还没在真机上验证，实际编码以触发消息里的 `Codec` 为准）。
 * 手机侧会把车机给的声音重采样成录音应用要的格式。
 */
class MicProfile(
    val sampleRate: Int,
    val channels: Int,
    /** 在 `codecs` 里只列 PCM（编号 1）；false 时 `codecs` 为空数组，由手机按默认（AAC）和比特率决定。 */
    val pcmOnly: Boolean = true,
) {
    init {
        require(sampleRate in 8_000..48_000) { "麦克风采样率超出范围: $sampleRate" }
        require(channels in 1..2) { "麦克风只支持单声道或双声道: $channels" }
    }

    /**
     * 2102 的值：一段 JSON 文本（作为字符串放进属性）。形状与扬声器的能力相同：`codecs` 是编码号数组，
     * `profiles` 里 `channel` 16 表示单声道、12 表示立体声，`format` 1 表示 16 位。其余键原样带上。
     */
    val json: String
        get() {
            val codecs = if (pcmOnly) "[${MicCodec.PCM.paraValue}]" else "[]"
            val channel = if (channels == 1) CHANNEL_MONO else CHANNEL_STEREO
            return "{\"codecs\":$codecs,\"currentVolume\":{\"a\":false},\"extendInfo\":{}," +
                "\"profiles\":[{\"sampleRate\":$sampleRate,\"channel\":$channel,\"format\":$FORMAT_16_BIT}],\"soundEffect\":0}"
        }

    override fun toString() = "MicProfile(${sampleRate}Hz/${channels}声道${if (pcmOnly) "，只给 PCM" else ""})"

    companion object {
        private const val CHANNEL_MONO = 16
        private const val CHANNEL_STEREO = 12
        private const val FORMAT_16_BIT = 1

        /** 16kHz 单声道 16 位、只给 PCM：通话和语音识别够用，手机一定落到 PCM。 */
        @JvmField
        val PCM_16K_MONO = MicProfile(16_000, 1)
    }
}

/** 一次麦克风数据会话的参数：手机在触发消息 `ParaAudio`（或之后的 `AudioParameter`）里给的。 */
class MicParameters(
    /** 手机给的 `DataSessionID`，原样文本。 */
    val dataSessionId: String,
    val sampleRate: Int,
    val channels: Int,
    /** 位深。本机只送 16 位。 */
    val bitsPerSample: Int,
    val codec: MicCodec,
    /** `ParaAudio` 原文（只有采样参数，不含标识）。 */
    val parameters: String,
) {
    /** 一个采样帧（所有声道）的字节数。 */
    val frameBytes: Int get() = channels * bitsPerSample / 8

    /** 20ms 一块 PCM 的字节数（车机每 20ms 送一次）。 */
    val chunkBytes: Int get() = sampleRate / CHUNKS_PER_SECOND * frameBytes

    /** 采样参数（不看数据会话号）是否相同。 */
    fun sameFormat(other: MicParameters): Boolean =
        sampleRate == other.sampleRate && channels == other.channels && bitsPerSample == other.bitsPerSample && codec == other.codec

    override fun toString() =
        "麦克风会话 $dataSessionId（${sampleRate}Hz / $channels 声道 / $bitsPerSample 位，${codec.label}）"

    companion object {
        const val CHUNKS_PER_SECOND = 50
    }
}

/** 一次麦克风数据会话为什么结束。 */
enum class MicEndReason(val label: String) {
    PEER_TEARDOWN("手机拆除"),
    REPLACED("手机开了新的麦克风会话"),
    PEER_CLOSED("手机关掉了麦克风连接"),
    LISTEN_FAILED("车机开不了端口"),
    SESSION_CLOSED("整个连接结束"),
}

/** 一次麦克风数据会话的统计，结束时随 [MicEvent.Stopped] 给出，toString 是一行汇总。 */
class MicSummary internal constructor(
    val dataSessionId: String,
    /** 回给手机的数据端口；0 表示没回过 200。 */
    val serverPort: Int,
    val connected: Boolean,
    /** 送出的 RTP 包数和线上字节数（含帧头、IV）。 */
    val packets: Long,
    val bytes: Long,
    /** 送出的声音时长（毫秒，按采样数算）。 */
    val audioMillis: Long,
    /** 连接还没建立或格式对不上而丢掉的声音块数。 */
    val dropped: Long,
) {
    override fun toString() = "麦克风汇总：会话 $dataSessionId｜" +
        (if (serverPort == 0) "没回 SETUP" else "数据端口 $serverPort") + "｜手机连入：${if (connected) "是" else "否"}｜" +
        "送出 $packets 包 $bytes 字节、约 ${audioMillis}ms 声音｜丢弃 $dropped 块"
}

/** 麦克风数据会话的状态变化和诊断。toString 是一行中文，可以直接写日志；不含密钥和设备标识。 */
sealed class MicEvent {

    /** 手机要用车机麦克风（触发 OPTIONS），已回 200 并发出能力协商。 */
    class Requested(val parameters: MicParameters) : MicEvent() {
        override fun toString() = "手机要用车机麦克风：$parameters，ParaAudio=${parameters.parameters}"
    }

    /** 手机发来 SETUP（它的收端端口是 [clientPort]），车机正在开数据端口。[headers] 是 SETUP 的全部头部，原样列出。 */
    class SetupReceived(
        val parameters: MicParameters,
        val clientPort: Int,
        val headers: List<Pair<String, String>>,
    ) : MicEvent() {
        override fun toString() = "手机发来麦克风 SETUP（client_port=$clientPort），开数据端口；头部 ${describeHeaders(headers)}"
    }

    /** 数据端口开好、200 已回，等手机连进来。 */
    class SetupAnswered(val parameters: MicParameters, val serverPort: Int) : MicEvent() {
        override fun toString() = "已回麦克风 SETUP 的 200（server_port=$serverPort），等手机连入"
    }

    /** 手机连上了数据端口：开始采集、推流。 */
    class Started(val parameters: MicParameters) : MicEvent() {
        override fun toString() = "手机连上麦克风数据端口，开始推流：$parameters"
    }

    /** 手机改了采样参数（`AudioParameter`）：之后按新参数送。 */
    class ParametersChanged(val parameters: MicParameters) : MicEvent() {
        override fun toString() = "手机改了麦克风参数：$parameters，ParaAudio=${parameters.parameters}"
    }

    /** 数据会话结束：停止采集。 */
    class Stopped(val parameters: MicParameters, val reason: MicEndReason, val summary: MicSummary) : MicEvent() {
        override fun toString() = "麦克风会话结束（${reason.label}）；$summary"
    }

    /** 不影响流程的信息：格式不支持、没实现的请求、丢弃的数据等。 */
    class Note(val parameters: MicParameters?, val message: String) : MicEvent() {
        override fun toString() = message
    }
}

/** 把头部列成一行（每个值至多 200 字符），给诊断用。头部里只有会话号、端口、加密能力这类协商参数。 */
internal fun describeHeaders(headers: List<Pair<String, String>>): String =
    headers.joinToString("；") { (name, value) -> "$name: ${value.take(200)}" }

/**
 * 麦克风数据会话交给上层的出口：在协议线程上同步调用，回调里不要阻塞（采集要在自己的线程上开）；
 * 抛出的异常会被接住、记成诊断，不影响投屏。
 */
interface MicStreamListener {
    fun onMicEvent(event: MicEvent) {}

    companion object {
        /** 什么都不做。 */
        @JvmField
        val NONE: MicStreamListener = object : MicStreamListener {}
    }
}
