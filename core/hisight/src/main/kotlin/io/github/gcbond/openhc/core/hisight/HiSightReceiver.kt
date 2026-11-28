package io.github.gcbond.openhc.core.hisight

import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.hisight.control.Capabilities
import io.github.gcbond.openhc.core.hisight.control.ControlFrameDecoder
import io.github.gcbond.openhc.core.hisight.control.ControlFraming
import io.github.gcbond.openhc.core.hisight.control.MediaSetup
import io.github.gcbond.openhc.core.hisight.control.ParameterText
import io.github.gcbond.openhc.core.hisight.control.RtspMessage
import io.github.gcbond.openhc.core.hisight.control.RtspParser
import io.github.gcbond.openhc.core.hisight.control.RtspTemplates
import io.github.gcbond.openhc.core.hisight.control.parseTransport
import io.github.gcbond.openhc.core.hisight.crypto.AesCtr
import io.github.gcbond.openhc.core.hisight.input.UibcEncoder
import io.github.gcbond.openhc.core.hisight.video.VideoDepacketizer
import java.security.SecureRandom
import java.util.TimeZone

/**
 * 一次 HiSight 投屏会话的纯协议逻辑：不持有连接、线程或定时器。调用方负责建连和收发，
 * 把收到的字节喂进来，并按返回的 [HiSightOutput] 依次执行。
 *
 * 流程（三条连接都由车机主动连到手机）：
 * 1. 设备会话层告知 HiSight 端口后，连上控制连接，调用 [start]，把返回的明文 ANNOUNCE 发出去。
 * 2. 控制连接上收到的字节交给 [feedControl]。加密由手机的 ANNOUNCE 选定；也兼容不发加密选择、收到车机
 *    ANNOUNCE 的 200 后直接加密的版本（按密文自动识别）。OPTIONS、能力查询（GET_PARAMETER）、能力选择和
 *    SETUP 触发（SET_PARAMETER）都在这里应答。这些请求的先后顺序不固定（各手机版本发法不同），
 *    OPTIONS 可以没有、可以晚到、可以在加密之前，能力请求也不要求先完成 OPTIONS 与加密协商，
 *    都按收到即处理并写一条诊断说明；只有内容不合法（正文类型、能力取值、CSeq 对不上）才按协议错误结束。
 * 3. SETUP 应答到达时返回 [HiSightOutput.StreamNegotiated] 和 [HiSightOutput.Connect]：连视频端口，
 *    手机给了回控端口的话也要连。各自连上后调用 [onVideoConnected]、[onRemoteControlConnected]，
 *    需要的连接都就绪后会话才发 PLAY。
 * 4. 视频连接收到的字节交给 [feedVideo]，每解出一帧就同步回调 [AccessUnitSink]。
 * 5. 触摸、按键用 [sendInput] 编码，返回的字节写到回控连接。
 * 6. 结束：本机主动结束调用 [shutdown]；手机要求结束、某条连接被关闭时，对应方法会返回收尾动作。
 *
 * 手机发来的数据不合法时抛 [MalformedMessageException]，会话进入失败状态，之后应调用 [shutdown]
 * 做收尾（会尽量发出 TEARDOWN）。同一次 [feedControl] 里、出错之前已经生成的应答不会丢，
 * 会排在 [shutdown] 返回列表的最前面，保证线上顺序与逐帧发送时一致。
 *
 * 线程：控制连接相关的方法（[start]、[feedControl]、各 `on…` 通知、[shutdown]）、视频方法
 * （[feedVideo]）、输入方法（[sendInput]、[releaseInputs]）可以分别在不同线程调用，内部各有锁；
 * 同一组方法要串行调用。视频回调在 [feedVideo] 的线程上执行，回调里不要阻塞等待其他会话方法。
 *
 * @param sessionKey 认证得到的 16 字节会话密钥，三条连接共用。
 * @param random IV 来源，每个加密单元取 16 字节。
 * @param videoSink 接收解密后的 H.264 访问单元，缓冲区复用规则见 [AccessUnit]。
 * @param timeZone 控制消息 Date 头使用的时区，默认系统时区。
 */
class HiSightReceiver(
    sessionKey: ByteArray,
    private val config: HiSightConfig,
    private val random: SecureRandom,
    videoSink: AccessUnitSink,
    private val timeZone: TimeZone = TimeZone.getDefault(),
) {
    private val key: ByteArray

    init {
        require(sessionKey.size == AesCtr.KEY_BYTES) { "sessionKey 必须是 16 字节" }
        key = sessionKey.copyOf()
    }

    // 控制连接（controlLock）
    private val controlLock = Any()
    private val controlCipher = AesCtr(key)
    private val decoder = ControlFrameDecoder()
    private val capabilities = Capabilities(config)
    private val pending = HashMap<Int, String>()
    private val carryOver = ArrayList<HiSightOutput>()
    private var started = false
    private var cryptoReady = false
    /** 加密是从手机的密文自动识别出来的：手机没发加密选择（ANNOUNCE）就直接开始 AES-CTR。 */
    private var cryptoAutoDetected = false
    private var optionsReceived = false
    /** 已经写过诊断说明的兼容点；同一个点只写一次，避免保活包重复刷日志。 */
    private val noted = HashSet<String>()
    private var lastCseq = 0
    private var setup: MediaSetup? = null
    private var videoConnected = false
    private var remoteConnected = false
    private var playRequested = false
    private var controlEof = false
    private var failed = false

    @Volatile
    private var ended = false

    // 视频连接（videoLock）
    private val videoLock = Any()
    private val video = VideoDepacketizer(key, videoSink)

    // 回控连接（inputLock；加锁顺序只能是 controlLock → inputLock）
    private val inputLock = Any()
    private var encoder: UibcEncoder? = null
    private var remoteOpen = false

    /** 控制连接已连上：返回首发的明文 ANNOUNCE。只能调用一次。 */
    fun start(): List<HiSightOutput> {
        synchronized(controlLock) {
            check(!started) { "start() 只能调用一次" }
            started = true
            if (ended) return emptyList()
            lastCseq = 1
            pending[1] = ANNOUNCE
            return listOf(sendControl(RtspTemplates.ANNOUNCE))
        }
    }

    /**
     * 控制连接收到的字节。[wallClockMillis] 是当前墙上时间（`System.currentTimeMillis()`），
     * 只用来生成 Date 头。会话结束或失败后再喂的数据会被忽略。
     */
    fun feedControl(data: ByteArray, offset: Int, length: Int, wallClockMillis: Long): List<HiSightOutput> {
        checkRange(data, offset, length)
        synchronized(controlLock) {
            check(started) { "先调用 start()" }
            if (ended || failed) return emptyList()
            val out = ArrayList<HiSightOutput>()
            try {
                decoder.append(data, offset, length)
                while (!ended) {
                    val payload = decoder.next() ?: break
                    handle(decodeControl(payload, out), out, wallClockMillis)
                }
            } catch (e: Exception) {
                failed = true
                carryOver.addAll(out)
                throw e
            }
            return out
        }
    }

    /** 控制连接被对端关闭。残留半帧时抛异常（之后调用 [shutdown]）；否则结束会话，不再发 TEARDOWN。 */
    fun onControlClosed(): List<HiSightOutput> {
        synchronized(controlLock) {
            if (ended) return emptyList()
            if (decoder.hasPartial && !failed) {
                failed = true
                throw MalformedMessageException("控制连接关闭时仍有未收完的帧")
            }
            controlEof = true
            val out = takeCarryOver()
            terminate(HiSightEndReason.CONTROL_CLOSED, out, sendTeardown = false, releaseInputs = true)
            return out
        }
    }

    /** 视频连接已连上。若回控连接不需要或也已就绪，返回 PLAY。 */
    fun onVideoConnected(): List<HiSightOutput> {
        synchronized(controlLock) {
            if (ended || failed) return emptyList()
            checkNotNull(setup) { "还没有收到 SETUP 应答" }
            check(!videoConnected) { "重复通知视频已连接" }
            videoConnected = true
            return maybePlay()
        }
    }

    /** 回控连接已连上。若视频连接也已就绪，返回 PLAY。 */
    fun onRemoteControlConnected(): List<HiSightOutput> {
        synchronized(controlLock) {
            if (ended || failed) return emptyList()
            val media = checkNotNull(setup) { "还没有收到 SETUP 应答" }
            check(media.remoteControlPort != NO_PORT) { "手机没有给回控端口" }
            check(!remoteConnected) { "重复通知回控已连接" }
            remoteConnected = true
            synchronized(inputLock) { remoteOpen = true }
            return maybePlay()
        }
    }

    /**
     * 视频连接收到的字节。解出的每一帧同步交给 [AccessUnitSink]。
     * 数据不合法（含丢包、重复、SSRC 变化）时抛 [MalformedMessageException]，视频流不可再用。
     */
    fun feedVideo(data: ByteArray, offset: Int, length: Int) {
        checkRange(data, offset, length)
        if (ended) return
        synchronized(videoLock) { video.feed(data, offset, length) }
    }

    /** 视频连接被对端关闭：检查没有残帧后正常结束会话（释放按下的输入、发 TEARDOWN）。 */
    fun onVideoClosed(): List<HiSightOutput> {
        if (ended) return emptyList()
        synchronized(videoLock) { video.finish() }
        synchronized(controlLock) {
            if (ended) return emptyList()
            val out = takeCarryOver()
            terminate(HiSightEndReason.VIDEO_CLOSED, out, sendTeardown = true, releaseInputs = true)
            return out
        }
    }

    /** 回控连接收到的字节。协议没有定义这个方向的数据，收到即抛异常。 */
    fun feedRemoteControl(data: ByteArray, offset: Int, length: Int) {
        checkRange(data, offset, length)
        if (length == 0 || ended) return
        throw MalformedMessageException("回控连接收到未定义的入站数据（$length 字节）")
    }

    /** 回控连接被关闭（对端关闭或本机发送失败）：结束会话并发 TEARDOWN。 */
    fun onRemoteControlClosed(): List<HiSightOutput> {
        synchronized(controlLock) {
            if (ended) return emptyList()
            synchronized(inputLock) {
                encoder?.clear()
                remoteOpen = false
            }
            val out = takeCarryOver()
            terminate(HiSightEndReason.REMOTE_CONTROL_CLOSED, out, sendTeardown = true, releaseInputs = false)
            return out
        }
    }

    /**
     * 编码一次输入，返回要写到回控连接的包。回控连接还没就绪或会话已结束时返回空列表（输入被丢弃）。
     * 事件不合法或未经本次协商时抛 [IllegalArgumentException]，动作顺序错误时抛 [IllegalStateException]，
     * 两者都不改变已按下的状态。返回的包必须按顺序发出；发送失败时调用 [onRemoteControlClosed]。
     */
    fun sendInput(input: HiSightInput): List<HiSightOutput> {
        check(config.touchInput || config.keyInput || config.mediaKeyInput || config.textInput) { "配置没有启用任何输入" }
        synchronized(inputLock) {
            val enc = encoder
            if (enc == null || !remoteOpen || ended) return emptyList()
            return listOf(HiSightOutput.Send(HiSightChannel.REMOTE_CONTROL, enc.encode(input)))
        }
    }

    /** 抬起所有仍按下的触点和按键（例如窗口失焦）。正常结束时 [shutdown] 会自动做这一步。 */
    fun releaseInputs(): List<HiSightOutput> {
        synchronized(inputLock) {
            val enc = encoder
            if (enc == null || !remoteOpen || ended) return emptyList()
            return enc.release().map { HiSightOutput.Send(HiSightChannel.REMOTE_CONTROL, it) }
        }
    }

    /**
     * 本机结束会话：先补发出错前未交出的应答，再释放按下的输入，加密已协商且控制连接未断时发一次
     * TEARDOWN（不等手机确认），最后关闭全部连接。可重复调用，之后返回空列表。
     */
    fun shutdown(): List<HiSightOutput> {
        synchronized(controlLock) {
            val out = takeCarryOver()
            terminate(HiSightEndReason.LOCAL_SHUTDOWN, out, sendTeardown = true, releaseInputs = true)
            return out
        }
    }

    private fun handle(message: RtspMessage, out: MutableList<HiSightOutput>, wallClockMillis: Long) {
        if (message.isResponse) {
            handleResponse(message, out)
            return
        }
        when (RtspParser.requestMethod(message.firstLine)) {
            "ANNOUNCE" -> handleAnnounce(message, out, wallClockMillis)
            "OPTIONS" -> handleOptions(message, out, wallClockMillis)
            "GET_PARAMETER" -> handleGetParameter(message, out)
            "SET_PARAMETER" -> handleSetParameter(message, out, wallClockMillis)
            // PAUSE、手机直接发的 TEARDOWN 等请求不在已知的交互里，不猜应答。
            else -> throw MalformedMessageException("收到未实现的控制请求")
        }
    }

    private fun handleResponse(message: RtspMessage, out: MutableList<HiSightOutput>) {
        if (message.firstLine != "RTSP/1.0 200 OK" || message.body.isNotEmpty()) {
            throw MalformedMessageException("手机应答不是空正文的 200")
        }
        when (pending.remove(message.cseq) ?: throw MalformedMessageException("手机应答的 CSeq 对不上待应答的请求")) {
            SETUP -> handleSetupResponse(message, out)
            PLAY -> out += HiSightOutput.PlayAccepted
        }
    }

    /**
     * 解一帧控制载荷。已协商加密时只按 AES-CTR 解；协商之前先按明文解析，解析不通再按密文解一次：
     * 有的手机版本不回加密选择，收到车机 ANNOUNCE 的 200 后直接开始加密。密文必须解出合法的 RTSP
     * 才认，认得之后从这里开始双向都按 AES-CTR；否则保留原来明文解析的错误说法。
     */
    private fun decodeControl(payload: ByteArray, out: MutableList<HiSightOutput>): RtspMessage {
        if (cryptoReady) return RtspParser.parse(controlCipher.open(payload))
        val plainError: MalformedMessageException
        try {
            return RtspParser.parse(payload)
        } catch (e: MalformedMessageException) {
            plainError = e
        }
        val decrypted = try {
            RtspParser.parse(controlCipher.open(payload))
        } catch (e: MalformedMessageException) {
            throw plainError
        }
        cryptoReady = true
        cryptoAutoDetected = true
        note(out, "crypto-auto", "手机没有加密选择就直接开始 AES-CTR，已按密文识别，此后全程加密")
        return decrypted
    }

    /** 兼容点的诊断说明，同一类只写一次。 */
    private fun note(out: MutableList<HiSightOutput>, kind: String, text: String) {
        if (noted.add(kind)) out += HiSightOutput.Note(text)
    }

    /**
     * 手机的 ANNOUNCE 选加密方式：正文里 `encrypt_description` 那一行，`encrypt_list` 里要有 aes128ctr，`version` 写了就得是 1；
     * 明文回 200 之后两个方向都切到 AES-CTR。
     *
     * 纯血鸿蒙发的正好一行：`encrypt_description: encrypt_list=aes128ctr;version=1` 加 CRLF。鸿蒙 4.x 的手机按「正好一行」
     * 的旧规则会被拒（2026-11 多份报告，原文没拿到），所以行数、换行符、结尾换行、多出来的字段和 `encrypt_list` 写成列表都放宽，
     * 不是标准格式时把正文交出来写日志（[HiSightOutput.Note]）；仍然拒绝时把正文带进报错（屏幕上的「协议错误」里能看到）。
     *
     * 加密已经按密文自动识别时（手机先加密、后补选加密方式），这一条只当确认，不再切换状态。
     */
    private fun handleAnnounce(message: RtspMessage, out: MutableList<HiSightOutput>, wallClockMillis: Long) {
        if ((cryptoReady && !cryptoAutoDetected) || !message.isTextParameters) {
            throw MalformedMessageException("ANNOUNCE 重复或类型不是 text/parameters")
        }
        if (message.body.any { it < 0 }) throw MalformedMessageException("加密选择正文含非 ASCII 字节（${message.body.size} 字节）")
        val text = String(message.body, Charsets.US_ASCII)
        val lines = text.split('\n').map { ParameterText.trimSpaceTab(it.removeSuffix("\r")) }.filter { it.isNotEmpty() }
        val line = lines.firstOrNull { ParameterText.trimSpaceTab(it.substringBefore(':', "")) == "encrypt_description" }
            ?: throw MalformedMessageException("加密选择缺少 encrypt_description：${visible(text)}")
        val fields = LinkedHashMap<String, String>()
        for (item in line.substringAfter(':').split(';')) {
            val eq = item.indexOf('=')
            if (eq < 0) continue
            val name = ParameterText.trimSpaceTab(item.substring(0, eq))
            // 不用 putIfAbsent：低版本安卓没有。同名字段取第一个。
            if (name !in fields) fields[name] = ParameterText.trimSpaceTab(item.substring(eq + 1))
        }
        val ciphers = fields["encrypt_list"].orEmpty().split(',').map { ParameterText.trimSpaceTab(it).lowercase() }
        val version = fields["version"]
        if (AES128CTR !in ciphers || (version != null && version != "1")) {
            throw MalformedMessageException("只支持 aes128ctr / version 1 的加密选择：${visible(text)}")
        }
        val standard = lines.size == 1 && text.endsWith("\r\n") && countCrlf(text) == 1 &&
            fields.keys == setOf("encrypt_list", "version") && ciphers == listOf(AES128CTR)
        if (!standard) out += HiSightOutput.Note("手机的加密选择不是标准的一行格式，已按宽松规则接受：${visible(text)}")
        out += sendControl(RtspTemplates.ok(message.cseq, date(wallClockMillis)))
        if (cryptoAutoDetected) note(out, "crypto-auto-confirmed", "加密已经生效，之后补到的加密选择只作确认")
        cryptoAutoDetected = false
        cryptoReady = true
    }

    /**
     * 手机的 OPTIONS：只在收到时回 200 并发出本机的一次 OPTIONS（用来告诉手机本机支持的方法）。
     * 加密协商之前的明文 OPTIONS 也接受，按顺序无关处理。
     */
    private fun handleOptions(message: RtspMessage, out: MutableList<HiSightOutput>, wallClockMillis: Long) {
        if (message.body.isNotEmpty() || optionsReceived) {
            throw MalformedMessageException("OPTIONS 带正文或重复")
        }
        if (!cryptoReady) note(out, "options-plaintext", "手机在加密协商前发了明文 OPTIONS，已按顺序无关接受")
        val date = date(wallClockMillis)
        out += sendControl(RtspTemplates.ok(message.cseq, date, withPublic = true))
        val cseq = nextCseq()
        out += sendControl(RtspTemplates.options(cseq, date))
        pending[cseq] = OPTIONS
        optionsReceived = true
    }

    /** 能力查询；空正文的 GET 是手机的保活，同样回 200。 */
    private fun handleGetParameter(message: RtspMessage, out: MutableList<HiSightOutput>) {
        requireNegotiationReady(message, out)
        val body = capabilities.responseBody(message.body)
        out += sendControl(RtspTemplates.getParameterOk(message.cseq, body))
    }

    private fun handleSetParameter(message: RtspMessage, out: MutableList<HiSightOutput>, wallClockMillis: Long) {
        val fields = ParameterText.parameters(message.body)
        val trigger = fields[ParameterText.TRIGGER]
        if (trigger == "TEARDOWN") {
            if (!message.isTextParameters || fields.size != 1) {
                throw MalformedMessageException("结束触发必须是单独的 text/parameters 正文")
            }
            if (!cryptoReady) note(out, "teardown-before-crypto", "手机在加密协商前要求结束投屏，已按顺序无关接受")
            out += sendControl(RtspTemplates.ok(message.cseq, date(wallClockMillis)))
            terminate(HiSightEndReason.PEER_TEARDOWN, out, sendTeardown = true, releaseInputs = true)
            return
        }
        requireNegotiationReady(message, out)
        if (trigger == null) {
            if (setup != null || SETUP in pending.values) throw MalformedMessageException("SETUP 之后不支持重新协商能力")
            capabilities.select(fields)
            out += sendControl(RtspTemplates.ok(message.cseq, date(wallClockMillis)))
            return
        }
        if (trigger != "SETUP" || fields.size != 1) throw MalformedMessageException("只支持单独的 SETUP 触发")
        if (setup != null || SETUP in pending.values) throw MalformedMessageException("重复的 SETUP 触发")
        if (capabilities.selection == null) throw MalformedMessageException("SETUP 前缺少视频参数选择")
        out += sendControl(RtspTemplates.ok(message.cseq, date(wallClockMillis)))
        val cseq = nextCseq()
        out += sendControl(RtspTemplates.setup(cseq))
        pending[cseq] = SETUP
    }

    private fun handleSetupResponse(message: RtspMessage, out: MutableList<HiSightOutput>) {
        val ports = parseTransport(message.header("transport") ?: "")
        val media = capabilities.mediaSetup(ports.serverPort, ports.remoteControlPort)
        setup = media
        if (media.inputEvents.isNotEmpty() || media.inputVendors.isNotEmpty()) {
            synchronized(inputLock) {
                encoder = UibcEncoder(
                    key, random, media.inputEvents.toSet(), media.inputVendors.toSet(), media.sourceDeviceType,
                )
            }
        }
        out += HiSightOutput.StreamNegotiated(
            width = media.selection.width,
            height = media.selection.height,
            fps = media.selection.fps,
            touchInput = media.inputEvents.containsAll(Capabilities.TOUCH_EVENTS),
            keyInput = media.inputEvents.containsAll(Capabilities.KEY_EVENTS),
            textInput = Capabilities.TEXT_VENDOR in media.inputVendors,
        )
        out += HiSightOutput.Connect(HiSightChannel.VIDEO, media.videoPort)
        if (media.remoteControlPort != NO_PORT) {
            out += HiSightOutput.Connect(HiSightChannel.REMOTE_CONTROL, media.remoteControlPort)
        }
    }

    private fun maybePlay(): List<HiSightOutput> {
        val media = setup ?: return emptyList()
        val remoteReady = media.remoteControlPort == NO_PORT || remoteConnected
        if (!videoConnected || !remoteReady || playRequested || controlEof) return emptyList()
        playRequested = true
        val cseq = nextCseq()
        pending[cseq] = PLAY
        return listOf(sendControl(RtspTemplates.play(cseq)))
    }

    private fun terminate(
        reason: HiSightEndReason,
        out: MutableList<HiSightOutput>,
        sendTeardown: Boolean,
        releaseInputs: Boolean,
    ) {
        if (ended) return
        ended = true
        synchronized(inputLock) {
            val enc = encoder
            if (enc != null && releaseInputs && remoteOpen) {
                for (packet in enc.release()) out += HiSightOutput.Send(HiSightChannel.REMOTE_CONTROL, packet)
            }
            enc?.clear()
            remoteOpen = false
        }
        if (sendTeardown && cryptoReady && !controlEof) {
            out += sendControl(RtspTemplates.teardown(nextCseq()))
        }
        out += HiSightOutput.CloseAll
        out += HiSightOutput.Ended(reason)
    }

    /**
     * 能力请求（GET_PARAMETER、能力选择）不强制先完成加密与 OPTIONS 协商：不同手机版本的发法不一样，
     * 有的先 OPTIONS 再查能力，有的（鸿蒙 4.2 一类）直接查能力、不发或晚发 OPTIONS，有的连加密选择
     * 都不单独发。这里按「收到什么就处理什么」接受，只写一条诊断说明；对端发的是明文（否则解不出来），
     * 应答也走当前状态，之后的 ANNOUNCE 再切加密。能力正文本身的类型仍然严格校验。
     */
    private fun requireNegotiationReady(message: RtspMessage, out: MutableList<HiSightOutput>) {
        if (!cryptoReady) {
            note(out, "caps-before-crypto", "手机在加密协商前发了明文能力请求，已按顺序无关接受")
        } else if (!optionsReceived) {
            note(out, "caps-before-options", "手机的能力请求早于 OPTIONS，已按顺序无关接受")
        }
        if (message.body.isNotEmpty() && !message.isTextParameters) {
            throw MalformedMessageException("能力正文缺少 text/parameters 类型")
        }
    }

    private fun sendControl(text: String): HiSightOutput.Send {
        val plain = text.toByteArray(Charsets.US_ASCII)
        val payload = if (cryptoReady) controlCipher.seal(plain, random) else plain
        return HiSightOutput.Send(HiSightChannel.CONTROL, ControlFraming.encode(payload))
    }

    private fun takeCarryOver(): MutableList<HiSightOutput> {
        val out = ArrayList<HiSightOutput>(carryOver)
        carryOver.clear()
        return out
    }

    private fun nextCseq(): Int = ++lastCseq

    private fun date(wallClockMillis: Long) = RtspTemplates.date(wallClockMillis, timeZone)

    /** 写日志用：回车、换行写成可见的转义，过长截断（正文只是加密方式的说明，不含密钥）。 */
    /**
     * 消息原文的可读形式（进日志和报错说明）：换行写成 \r \n，太长截断；20 个以上连续字母数字的长串
     * （可能是密钥、随机数之类）只写长度。标准格式里只有算法名和版本号，没有长串。
     */
    private fun visible(text: String): String {
        val shown = text.replace(LONG_TOKEN) { "<隐去 ${it.value.length} 字符>" }.replace("\r", "\\r").replace("\n", "\\n")
        return if (shown.length <= NOTE_MAX_CHARS) shown else shown.take(NOTE_MAX_CHARS) + "…（共 ${text.length} 字符）"
    }

    private fun countCrlf(text: String): Int {
        var count = 0
        var i = text.indexOf("\r\n")
        while (i >= 0) {
            count++
            i = text.indexOf("\r\n", i + 2)
        }
        return count
    }

    private fun checkRange(data: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) { "范围非法" }
    }

    private companion object {
        const val AES128CTR = "aes128ctr"
        const val NOTE_MAX_CHARS = 200

        /** [visible] 里只写长度的长串。 */
        private val LONG_TOKEN = Regex("[0-9A-Za-z+/]{20,}")
        const val ANNOUNCE = "ANNOUNCE"
        const val OPTIONS = "OPTIONS"
        const val SETUP = "SETUP"
        const val PLAY = "PLAY"
        const val NO_PORT = -1
    }
}
