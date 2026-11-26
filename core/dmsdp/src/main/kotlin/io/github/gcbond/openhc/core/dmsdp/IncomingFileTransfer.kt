package io.github.gcbond.openhc.core.dmsdp

import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.common.codec.JsonCodec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.text.Normalizer
import java.util.Locale

/**
 * 手机发文件到车机（文件分享）。
 *
 * **管理消息**走加密控制连接上的设备记录 module 1 / type 17，内容是 UTF-8 JSON（`cmdId` 可为字符串或整数），
 * 也可能是单字节 ASCII 的旧格式 `1` / `2` / `9`：
 *
 * | cmdId | 方向 | 含义 |
 * |---|---|---|
 * | 1 | 手机→车机 | 请车机通告文件能力；车机回 `{"cmdId":"9"}`（只回一次） |
 * | 2 | 手机→车机 | 请网络信息；车机回 `{"cmdId":"2","version":"4.0","info":{"carIp":…,"phoneIp":…}}`（只回一次） |
 * | 9 | 手机→车机 | 手机支持文件分享 |
 * | 7 / 8 | 手机→车机 | `port`（整数或十进制字符串）：文件控制 / 数据 TCP 的端口，由车机去连 |
 *
 * **文件控制 TCP**：每条消息是 `LE32(长度) ‖ UTF-8 JSON {"cmdType": 整数, "cmdContent": 字符串}`，
 * 车机发的 cmdContent 都是空串。手机发：101 请求（cmdContent 是清单 JSON 数组的字符串）、105 请求超时、
 * 106 开始发送、107 取消、111 发送完成、112 发送失败；车机发：102 接受、103 空间不足、104 拒绝、
 * 108 取消、111 全部收齐。两条文件 TCP 都不加密。
 *
 * **文件数据 TCP**：按清单逐个发送 `LE32(文件名字节数) ‖ UTF-8 文件名 ‖ 文件内容`，内容长度以清单为准。
 * 两条 TCP 之间没有先后保证：106 可能晚于全部数据到达。
 *
 * 文件内容以事件交出，不在这里落盘。出错只结束文件分享（两条文件连接都关闭），不影响 DMSDP 会话。
 */
internal class IncomingFileTransfer(
    private val config: DmsdpConfig,
    private val options: DmsdpConfig.FileShare,
) {
    private enum class Link { NONE, CONNECTING, CONNECTED }
    private enum class Stage { OFFERED, RECEIVING, COMPLETING }

    private class Offer(val id: Long, val files: LinkedHashMap<String, Long>, val offeredAt: Long) {
        var stage = Stage.OFFERED
        var lastActivity = offeredAt
        val finished = HashSet<String>()
        var current: String? = null
        var remaining = 0L
    }

    private var capabilityAnnounced = false
    private var networkReplied = false
    private var supported = false

    private var controlLink = Link.NONE
    private var dataLink = Link.NONE
    private val controlBuffer = StreamBuffer()
    private val dataBuffer = StreamBuffer()

    private var nextOfferId = 1L
    private var offer: Offer? = null

    /**
     * 接受请求后等手机的一次 106。两条 TCP 没有先后，106 可能晚于全部数据、甚至晚于请求结束才到，
     * 所以这个标记不随请求结束清掉，只在收到 106 时消费。
     */
    private var expectStart = false

    val nextDeadlineMillis: Long?
        get() {
            val current = offer ?: return null
            return when (current.stage) {
                Stage.OFFERED -> current.offeredAt + options.offerTimeoutMillis
                Stage.RECEIVING -> current.lastActivity + options.idleTimeoutMillis
                Stage.COMPLETING -> null
            }
        }

    // ---------------- 管理消息 ----------------

    /**
     * 处理一条管理消息。返回 false 表示不认识（交给调用方按未处理消息交出）；[sendManagement] 发回复。
     */
    fun onManagement(
        payload: ByteArray,
        sendManagement: (ByteArray) -> Unit,
        out: DmsdpSession.Outputs,
    ): Boolean {
        val command: String = if (payload.size == 1) {
            when (payload[0].toInt().toChar()) {
                '1' -> "1"
                '2' -> "2"
                '9' -> "9"
                else -> return false
            }
        } else {
            val message = try {
                parseJson(payload, "文件管理消息") as? JsonObject ?: throw MalformedMessageException("文件管理消息不是 JSON 对象")
            } catch (e: MalformedMessageException) {
                channelError(e.message ?: "文件管理消息格式不对", out)
                return true
            }
            val cmd = message["cmdId"] as? JsonPrimitive ?: return false
            val text = when {
                cmd.isString -> cmd.content
                else -> cmd.longOrNull?.toString() ?: return false
            }
            if (text !in KNOWN_COMMANDS) return false
            if (text == "7" || text == "8") {
                val port = parsePort(message["port"])
                if (port == null) {
                    channelError("文件分享手机端口无效", out)
                } else {
                    requestConnect(if (text == "7") DmsdpChannel.FILE_CONTROL else DmsdpChannel.FILE_DATA, port, out)
                }
                return true
            }
            text
        }
        when (command) {
            "1" -> if (!capabilityAnnounced) {
                sendManagement(JsonCodec.encodeToUtf8(buildJsonObject { put("cmdId", "9") }))
                capabilityAnnounced = true
            }
            "2" -> if (!networkReplied) {
                val reply = buildJsonObject {
                    put("cmdId", "2")
                    put("version", NETWORK_INFO_VERSION)
                    put("info", buildJsonObject {
                        put("carIp", config.localIp)
                        put("phoneIp", config.phoneIp)
                    })
                }
                sendManagement(JsonCodec.encodeToUtf8(reply))
                networkReplied = true
            }
            "9" -> if (!supported) {
                supported = true
                out.event(DmsdpEvent.FileShareSupported)
            }
        }
        return true
    }

    private fun requestConnect(channel: DmsdpChannel, port: Int, out: DmsdpSession.Outputs) {
        // 已有连接（或正在连）时，重复的端口通知直接忽略，不替换现有连接。
        if (linkOf(channel) != Link.NONE) return
        setLink(channel, Link.CONNECTING)
        out.add(DmsdpAction.Connect(channel, port))
    }

    // ---------------- 连接 ----------------

    /** 连接建立。这条连接已经被放弃（中途出错或请求结束）时，要求调用方把它关掉。 */
    fun onConnected(channel: DmsdpChannel, out: DmsdpSession.Outputs) {
        when (linkOf(channel)) {
            Link.CONNECTING -> setLink(channel, Link.CONNECTED)
            Link.NONE -> out.add(DmsdpAction.Close(channel))
            Link.CONNECTED -> throw IllegalStateException("$channel 已经连上了")
        }
    }

    fun onClosed(channel: DmsdpChannel, out: DmsdpSession.Outputs) {
        if (linkOf(channel) == Link.NONE) return
        setLink(channel, Link.NONE)
        if (channel == DmsdpChannel.FILE_CONTROL) {
            controlBuffer.clear()
            channelError("文件分享控制连接已断开", out)
            return
        }
        val partial = dataBuffer.size > 0
        dataBuffer.clear()
        val stage = offer?.stage
        if (partial || stage == Stage.OFFERED || stage == Stage.RECEIVING) {
            channelError("文件数据未完整接收就断开了", out)
        }
    }

    fun feed(channel: DmsdpChannel, data: ByteArray, offset: Int, length: Int, nowMillis: Long, out: DmsdpSession.Outputs) {
        // 连接已被放弃（例如刚输出了 Close）后迟到的数据直接丢弃。
        if (linkOf(channel) != Link.CONNECTED) return
        try {
            if (channel == DmsdpChannel.FILE_CONTROL) {
                controlBuffer.append(data, offset, length)
                readControl(nowMillis, out)
            } else {
                dataBuffer.append(data, offset, length)
                readData(nowMillis, out)
            }
        } catch (e: MalformedMessageException) {
            channelError(e.message ?: "文件分享数据格式不对", out)
        }
    }

    // ---------------- 文件控制 TCP ----------------

    private fun readControl(nowMillis: Long, out: DmsdpSession.Outputs) {
        while (controlLink == Link.CONNECTED && controlBuffer.size >= 4) {
            val length = controlBuffer.u32Le(0)
            if (length < 1 || length > MAX_CONTROL_BYTES) throw MalformedMessageException("文件控制消息长度非法")
            if (controlBuffer.size < 4 + length) return
            val text = strictUtf8(controlBuffer.array, controlBuffer.start + 4, length.toInt(), "文件控制消息")
            controlBuffer.consume(4 + length.toInt())
            val message = JsonCodec.parse(text, "文件控制消息") as? JsonObject
                ?: throw MalformedMessageException("文件控制消息不是 JSON 对象")
            val code = (message["cmdType"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            val content = (message["cmdContent"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (code == null || content == null) throw MalformedMessageException("文件控制消息缺少 cmdType 或 cmdContent")
            when (code.toInt()) {
                CMD_OFFER -> onOffer(content, nowMillis, out)
                CMD_PEER_STARTED -> {
                    if (!expectStart) throw MalformedMessageException("文件分享开始通知来得不对")
                    expectStart = false
                    offer?.takeIf { it.stage == Stage.RECEIVING }?.lastActivity = nowMillis
                }
                CMD_PEER_TIMEOUT -> offer?.let { endOffer(DmsdpEvent.FileOfferEnded.Reason.PEER_TIMEOUT, out) }
                CMD_PEER_CANCELLED -> offer?.let { endOffer(DmsdpEvent.FileOfferEnded.Reason.PEER_CANCELLED, out) }
                CMD_PEER_FAILED -> offer?.let { endOffer(DmsdpEvent.FileOfferEnded.Reason.PEER_FAILED, out) }
                // 手机说发完了：只代表它那边发完，本机是否收齐看数据连接，这里不改状态。
                CMD_COMPLETE -> Unit
                else -> throw MalformedMessageException("文件控制命令 $code 没有实现")
            }
        }
    }

    private fun onOffer(content: String, nowMillis: Long, out: DmsdpSession.Outputs) {
        if (offer != null) throw MalformedMessageException("上一个文件请求还没结束")
        val list = JsonCodec.parse(content, "文件清单") as? JsonArray ?: throw MalformedMessageException("文件清单不是数组")
        if (list.size !in 1..MAX_FILES) throw MalformedMessageException("文件清单要有 1～$MAX_FILES 个文件")
        val files = LinkedHashMap<String, Long>()
        val folded = HashSet<String>()
        var total = 0L
        for (item in list) {
            val entry = item as? JsonObject ?: throw MalformedMessageException("文件清单条目不是对象")
            val name = (entry["fileName"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw MalformedMessageException("文件清单缺少 fileName")
            checkFileName(name)
            val size = (entry["fileSize"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            if (size == null || size < 0 || size >= MAX_TOTAL_BYTES) throw MalformedMessageException("文件长度非法")
            // 数据连接只靠文件名对应清单；规范化、忽略大小写后重名的也拒绝，免得保存时互相覆盖。
            if (!folded.add(foldName(name))) throw MalformedMessageException("文件清单里有重名")
            files[name] = size
            total += size
        }
        if (total <= 0 || total >= MAX_TOTAL_BYTES) throw MalformedMessageException("文件总长度必须大于 0 且小于 10 GiB")
        val created = Offer(nextOfferId++, files, nowMillis)
        offer = created
        out.event(
            DmsdpEvent.FileOffered(
                created.id,
                files.map { (name, size) -> DmsdpEvent.FileOffered.Entry(name, size) },
                total,
            ),
        )
    }

    // ---------------- 文件数据 TCP ----------------

    private fun readData(nowMillis: Long, out: DmsdpSession.Outputs) {
        val current = offer
        if (current == null || current.stage != Stage.RECEIVING) {
            throw MalformedMessageException("用户接受之前或收齐之后又收到文件数据")
        }
        while (dataBuffer.size > 0 || (current.current != null && current.remaining == 0L)) {
            var name = current.current
            if (name == null) {
                if (dataBuffer.size < 4) return
                val length = dataBuffer.u32Le(0)
                if (length < 1 || length > MAX_NAME_BYTES) throw MalformedMessageException("文件名长度非法")
                if (dataBuffer.size < 4 + length) return
                name = strictUtf8(dataBuffer.array, dataBuffer.start + 4, length.toInt(), "文件名")
                dataBuffer.consume(4 + length.toInt())
                checkFileName(name)
                val size = current.files[name]
                if (size == null || name in current.finished) throw MalformedMessageException("收到清单里没有或重复的文件")
                current.current = name
                current.remaining = size
                current.lastActivity = nowMillis
                out.event(DmsdpEvent.FileStarted(current.id, name, size))
            }
            if (current.remaining > 0) {
                if (dataBuffer.size == 0) return
                val count = minOf(current.remaining, dataBuffer.size.toLong()).toInt()
                val chunk = dataBuffer.copy(0, count)
                dataBuffer.consume(count)
                current.remaining -= count
                current.lastActivity = nowMillis
                out.event(DmsdpEvent.FileChunk(current.id, name, chunk))
                if (current.remaining > 0) return
            }
            current.finished += name
            current.current = null
            out.event(DmsdpEvent.FileFinished(current.id, name))
            if (current.finished.size == current.files.size) {
                if (dataBuffer.size > 0) throw MalformedMessageException("清单里的文件都收齐了，后面还有多余数据")
                current.stage = Stage.COMPLETING
                out.event(DmsdpEvent.FileOfferReceived(current.id))
                return
            }
        }
    }

    // ---------------- 用户操作 ----------------

    fun accept(offerId: Long, nowMillis: Long, out: DmsdpSession.Outputs) {
        val current = offer?.takeIf { it.id == offerId && it.stage == Stage.OFFERED } ?: return
        sendControl(CMD_ACCEPT, out)
        current.stage = Stage.RECEIVING
        current.lastActivity = nowMillis
        expectStart = true
    }

    fun reject(offerId: Long, insufficientSpace: Boolean, out: DmsdpSession.Outputs) {
        offer?.takeIf { it.id == offerId && it.stage == Stage.OFFERED } ?: return
        sendControl(if (insufficientSpace) CMD_NO_SPACE else CMD_REJECT, out)
        endOffer(DmsdpEvent.FileOfferEnded.Reason.REJECTED, out)
    }

    fun cancel(offerId: Long, out: DmsdpSession.Outputs) {
        val current = offer?.takeIf { it.id == offerId } ?: return
        sendControl(if (current.stage == Stage.OFFERED) CMD_REJECT else CMD_CANCEL, out)
        endOffer(DmsdpEvent.FileOfferEnded.Reason.CANCELLED, out)
    }

    fun confirmSaved(offerId: Long, out: DmsdpSession.Outputs) {
        offer?.takeIf { it.id == offerId && it.stage == Stage.COMPLETING } ?: return
        sendControl(CMD_COMPLETE, out)
        offer = null
        closeData(out)
    }

    fun tick(nowMillis: Long, out: DmsdpSession.Outputs) {
        val current = offer ?: return
        val deadline = nextDeadlineMillis ?: return
        if (nowMillis < deadline) return
        sendControl(if (current.stage == Stage.OFFERED) CMD_REJECT else CMD_CANCEL, out)
        endOffer(DmsdpEvent.FileOfferEnded.Reason.LOCAL_TIMEOUT, out)
    }

    /** 会话结束：有进行中的请求就尽量通知手机取消。 */
    fun close(out: DmsdpSession.Outputs) {
        val current = offer ?: return
        sendControl(if (current.stage == Stage.OFFERED) CMD_REJECT else CMD_CANCEL, out)
        endOffer(DmsdpEvent.FileOfferEnded.Reason.CANCELLED, out)
    }

    // ---------------- 内部 ----------------

    private fun sendControl(code: Int, out: DmsdpSession.Outputs) {
        if (controlLink != Link.CONNECTED) return
        out.send(DmsdpChannel.FILE_CONTROL, controlFrame(code))
    }

    private fun endOffer(reason: DmsdpEvent.FileOfferEnded.Reason, out: DmsdpSession.Outputs) {
        val current = offer ?: return
        offer = null
        closeData(out)
        out.event(DmsdpEvent.FileOfferEnded(current.id, reason))
    }

    private fun closeData(out: DmsdpSession.Outputs) {
        dataBuffer.clear()
        if (dataLink != Link.NONE) {
            dataLink = Link.NONE
            out.add(DmsdpAction.Close(DmsdpChannel.FILE_DATA))
        }
    }

    /** 文件分享出错：作废进行中的请求，两条文件连接都关闭，DMSDP 会话不受影响。 */
    private fun channelError(reason: String, out: DmsdpSession.Outputs) {
        val id = offer?.id
        offer = null
        controlBuffer.clear()
        dataBuffer.clear()
        if (controlLink != Link.NONE) {
            controlLink = Link.NONE
            out.add(DmsdpAction.Close(DmsdpChannel.FILE_CONTROL))
        }
        if (dataLink != Link.NONE) {
            dataLink = Link.NONE
            out.add(DmsdpAction.Close(DmsdpChannel.FILE_DATA))
        }
        out.event(DmsdpEvent.FileShareFailed(id, reason))
    }

    private fun linkOf(channel: DmsdpChannel): Link = when (channel) {
        DmsdpChannel.FILE_CONTROL -> controlLink
        DmsdpChannel.FILE_DATA -> dataLink
        else -> throw IllegalArgumentException("不是文件分享的连接: $channel")
    }

    private fun setLink(channel: DmsdpChannel, link: Link) {
        when (channel) {
            DmsdpChannel.FILE_CONTROL -> controlLink = link
            DmsdpChannel.FILE_DATA -> dataLink = link
            else -> throw IllegalArgumentException("不是文件分享的连接: $channel")
        }
    }

    companion object {
        const val CMD_OFFER = 101
        const val CMD_ACCEPT = 102
        const val CMD_NO_SPACE = 103
        const val CMD_REJECT = 104
        const val CMD_PEER_TIMEOUT = 105
        const val CMD_PEER_STARTED = 106
        const val CMD_PEER_CANCELLED = 107
        const val CMD_CANCEL = 108
        const val CMD_COMPLETE = 111
        const val CMD_PEER_FAILED = 112
        const val NETWORK_INFO_VERSION = "4.0"
        private val KNOWN_COMMANDS = setOf("1", "2", "7", "8", "9")

        /** 本机限制（不是协议规定）：控制消息长度、单个文件名、清单文件数、总长度。 */
        const val MAX_CONTROL_BYTES = 5_242_879L
        const val MAX_NAME_BYTES = 255L
        const val MAX_FILES = 20
        const val MAX_TOTAL_BYTES = 10L * 1024 * 1024 * 1024

        fun controlFrame(code: Int): ByteArray {
            val json = JsonCodec.encodeToUtf8(buildJsonObject {
                put("cmdType", code)
                put("cmdContent", "")
            })
            val out = ByteArray(4 + json.size)
            out[0] = json.size.toByte()
            out[1] = (json.size ushr 8).toByte()
            out[2] = (json.size ushr 16).toByte()
            out[3] = (json.size ushr 24).toByte()
            System.arraycopy(json, 0, out, 4, json.size)
            return out
        }

        /** 严格 UTF-8 解码后解析 JSON；报错不带原文（文件名等），见 [JsonCodec]。 */
        private fun parseJson(bytes: ByteArray, what: String): JsonElement =
            JsonCodec.parse(strictUtf8(bytes, what = what), what)

        /** 整数，或 1～5 位 ASCII 数字的字符串；值在 1～65535。 */
        fun parsePort(value: JsonElement?): Int? {
            val primitive = value as? JsonPrimitive ?: return null
            val number = if (primitive.isString) {
                val text = primitive.content
                if (text.length !in 1..5 || !isAsciiDecimal(text)) return null
                text.toLong()
            } else {
                primitive.longOrNull ?: return null
            }
            return number.takeIf { it in 1..0xFFFF }?.toInt()
        }

        /**
         * 文件名只能是单层名字：不能为空、不能是 `.` 或 `..`，不含 `/`、`\`、`:`，不含控制、格式、
         * 代理项和私用区字符，UTF-8 不超过 255 字节。不合格就拒绝，不悄悄截断。
         * 「未分配」的码位不拒绝：老系统的字符表旧，新 emoji 在那里是未分配。
         */
        fun checkFileName(name: String) {
            if (name.isEmpty() || name == "." || name == "..") throw MalformedMessageException("文件名无效")
            if (name.any { it == '/' || it == '\\' || it == ':' }) throw MalformedMessageException("文件名含路径字符")
            var i = 0
            while (i < name.length) {
                val cp = name.codePointAt(i)
                when (Character.getType(cp).toByte()) {
                    Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE ->
                        throw MalformedMessageException("文件名含控制类字符")
                }
                i += Character.charCount(cp)
            }
            if (name.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES) throw MalformedMessageException("文件名超过 255 字节")
        }

        private fun foldName(name: String): String =
            Normalizer.normalize(name, Normalizer.Form.NFC).uppercase(Locale.ROOT).lowercase(Locale.ROOT)
    }
}
