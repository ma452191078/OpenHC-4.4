package io.github.gcbond.openhc.core.nearby

import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.common.codec.Ipv4Text

/** 车机给手机准备 WiFi 网络的两种方式，连接串格式不同。 */
enum class WifiMode {
    /** 车机开热点（WPA2-PSK），手机当普通终端加入。 */
    HOTSPOT,

    /** 车机建 WiFi 直连组、自己当组主，手机作为组成员加入。 */
    DIRECT,
}

/**
 * 连接串：车机在 WiFi 组网层（外层类型 33）tag 3 里发给手机的文本，手机据此加入车机的网络、再连车机的 TCP 端口。
 * 用 `##` 分段，第 2 段内部用换行（0x0A）分行：
 *
 * ```
 * 热点：<手机IP>##<SSID>\n<BSSID>\n<密码>\n<频率MHz>\n1##version:3##<TCP端口>##<信道号>##hicarApIp:<车机IP>
 * 直连：<手机IP>##<组名>\n<组主MAC>\n<密码>\n<频率MHz>##version:3##<TCP端口>##hicarApIp:<车机IP>
 * ```
 *
 * - 手机 IP：与车机同网段、不和别的终端冲突的地址，见 [pickPhoneAddress]。直连时手机很可能直接把它配成自己的地址。
 * - SSID / 组名：去掉首尾的引号，反斜杠写成两个；不能含换行、`##` 和其他控制字符。
 * - BSSID / 组主 MAC：小写、冒号分隔（热点填热点网卡的 MAC，直连填直连网卡的 MAC）。
 * - 密码：WPA2-PSK 明文（8～63 个可打印 ASCII 字符，或 64 位十六进制）。
 * - 频率：网络的真实中心频率（十进制 MHz），手机按它找网，填错就连不上。
 * - `\n1` 和端口后的 `##<信道号>` 只在热点格式里出现：带 `\n1` 时手机按普通 WiFi 方式加入，不带时按直连组成员方式加入。
 * - 信道号：热点所在信道，由上面的频率换算（2.4 GHz 是 (频率 − 2407) / 5，2484 是 14；5 GHz 是 (频率 − 5000) / 5），
 *   例如 2412 → 1、2437 → 6、5745 → 149，见 [hotspotChannel]。频率是推测值时，信道号也跟着推测值。
 * - `version:3` 固定；TCP 端口是车机已经在监听的端口（不能是 0）；`hicarApIp` 是车机在这个网络上的地址，手机连它。
 */
class WifiConnectionInfo(
    val mode: WifiMode,
    val phoneIp: String,
    ssid: String,
    bssid: String,
    val passphrase: String,
    val frequencyMhz: Int,
    val port: Int,
    val carIp: String,
) {
    /** 网络名（已去掉首尾引号，未转义）。 */
    val ssid: String = stripQuotes(ssid)

    /** 小写、冒号分隔。 */
    val bssid: String = bssid.lowercase()

    /** 热点格式端口后那一段的信道号（由 [frequencyMhz] 换算）；直连格式没有这一段，为 null。 */
    val hotspotChannel: Int?
        get() = if (mode == WifiMode.HOTSPOT) WifiChannels.channelOf(frequencyMhz) else null

    init {
        require(Ipv4Text.isCanonical(phoneIp)) { "手机 IP 不是 IPv4 地址" }
        require(Ipv4Text.isCanonical(carIp)) { "车机 IP 不是 IPv4 地址" }
        require(phoneIp != carIp) { "手机 IP 不能和车机 IP 相同" }
        require(this.ssid.isNotEmpty()) { "网络名不能为空" }
        require(this.ssid.none { it < ' ' || it == '\u007F' }) { "网络名不能含换行等控制字符" }
        require(!this.ssid.contains(SEPARATOR)) { "网络名不能含 $SEPARATOR" }
        require(MAC.matches(this.bssid)) { "BSSID 应为 aa:bb:cc:dd:ee:ff" }
        require(isWpa2Passphrase(passphrase)) { "密码应为 8～63 个可打印 ASCII 字符，或 64 位十六进制" }
        require(!passphrase.contains(SEPARATOR)) { "密码不能含 $SEPARATOR" }
        require(WifiChannels.channelOf(frequencyMhz) != null) { "频率不是 2.4 GHz 或 5 GHz 的信道: $frequencyMhz" }
        require(port in 1..65535) { "端口必须在 1～65535 之间: $port" }
    }

    /** 线上的连接串文本。 */
    fun encode(): String {
        val sb = StringBuilder(128)
        sb.append(phoneIp).append(SEPARATOR)
        sb.append(ssid.replace("\\", "\\\\")).append('\n').append(bssid).append('\n')
            .append(passphrase).append('\n').append(frequencyMhz)
        if (mode == WifiMode.HOTSPOT) sb.append('\n').append(HOTSPOT_JOIN_MARK)
        sb.append(SEPARATOR).append(VERSION_PREFIX).append(VERSION)
        sb.append(SEPARATOR).append(port)
        if (mode == WifiMode.HOTSPOT) sb.append(SEPARATOR).append(hotspotChannel)
        sb.append(SEPARATOR).append(CAR_IP_PREFIX).append(carIp)
        return sb.toString()
    }

    override fun toString() = "WifiConnectionInfo($mode, $frequencyMhz MHz, 车机 $carIp:$port，网络名与密码不显示)"

    companion object {
        const val VERSION = 3

        private const val SEPARATOR = "##"
        private const val VERSION_PREFIX = "version:"
        private const val CAR_IP_PREFIX = "hicarApIp:"
        private const val HOTSPOT_JOIN_MARK = "1"

        /** 自动挑手机地址时的主机号范围。 */
        private const val FIRST_PHONE_HOST = 2
        private const val LAST_PHONE_HOST = 100

        private val MAC = Regex("[0-9a-f]{2}(:[0-9a-f]{2}){5}")

        /**
         * 给手机挑一个地址：车机地址所在的 /24 网段里，主机号从 2 到 100 依次挑第一个不是车机自己、也不在 [taken] 里的。
         * 热点和直连组的网段都是 /24。
         *
         * @throws IllegalArgumentException [carIp] 不是 IPv4 地址。
         * @throws IllegalStateException 2～100 全被占了。
         */
        fun pickPhoneAddress(carIp: String, taken: Collection<String> = emptyList()): String {
            require(Ipv4Text.isCanonical(carIp)) { "车机 IP 不是 IPv4 地址" }
            val prefix = carIp.substring(0, carIp.lastIndexOf('.') + 1)
            for (host in FIRST_PHONE_HOST..LAST_PHONE_HOST) {
                val candidate = prefix + host
                if (candidate != carIp && candidate !in taken) return candidate
            }
            throw IllegalStateException("网段里 .$FIRST_PHONE_HOST～.$LAST_PHONE_HOST 都被占了")
        }

        /** 解析连接串（自测和诊断用）。格式不对抛 [MalformedMessageException]。 */
        fun parse(text: String): WifiConnectionInfo {
            val parts = text.split(SEPARATOR)
            val mode = when (parts.size) {
                6 -> WifiMode.HOTSPOT
                5 -> WifiMode.DIRECT
                else -> throw MalformedMessageException("连接串应有 5 段（直连）或 6 段（热点），实际 ${parts.size} 段")
            }
            val lines = parts[1].split('\n')
            val expectedLines = if (mode == WifiMode.HOTSPOT) 5 else 4
            if (lines.size != expectedLines) throw MalformedMessageException("网络段应有 $expectedLines 行，实际 ${lines.size} 行")
            if (mode == WifiMode.HOTSPOT && lines[4] != HOTSPOT_JOIN_MARK) throw MalformedMessageException("热点格式缺少加入标记")
            if (parts[2] != VERSION_PREFIX + VERSION) throw MalformedMessageException("不支持的连接串版本")
            val last = parts.last()
            if (!last.startsWith(CAR_IP_PREFIX)) throw MalformedMessageException("连接串末段不是车机地址")
            val info = try {
                WifiConnectionInfo(
                    mode = mode,
                    phoneIp = parts[0],
                    ssid = unescape(lines[0]),
                    bssid = lines[1],
                    passphrase = lines[2],
                    frequencyMhz = decimal(lines[3], "频率"),
                    port = decimal(parts[3], "端口"),
                    carIp = last.substring(CAR_IP_PREFIX.length),
                )
            } catch (e: IllegalArgumentException) {
                throw MalformedMessageException("连接串字段不合法：${e.message}")
            }
            if (mode == WifiMode.HOTSPOT) {
                val channel = decimal(parts[4], "热点格式的信道号")
                if (channel != info.hotspotChannel) {
                    throw MalformedMessageException("热点格式的信道号 $channel 和频率 ${info.frequencyMhz} MHz 对不上")
                }
            }
            return info
        }

        private fun stripQuotes(text: String): String =
            if (text.length >= 2 && text.startsWith('"') && text.endsWith('"')) text.substring(1, text.length - 1) else text

        private fun isWpa2Passphrase(text: String): Boolean =
            (text.length in 8..63 && text.all { it in ' '..'~' }) ||
                (text.length == 64 && text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })

        private fun decimal(text: String, name: String): Int {
            if (text.isEmpty() || text.length > 9 || text.any { it !in '0'..'9' }) {
                throw MalformedMessageException("$name 不是十进制数")
            }
            return text.toInt()
        }

        /** 反斜杠成对出现：`\\` 还原成 `\`，落单的反斜杠算格式错误。 */
        private fun unescape(text: String): String {
            val sb = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '\\') {
                    if (i + 1 >= text.length || text[i + 1] != '\\') throw MalformedMessageException("网络名里有落单的反斜杠")
                    i++
                }
                sb.append(c)
                i++
            }
            return sb.toString()
        }
    }
}

/**
 * WiFi 组网层（外层类型 33）：蓝牙控制通道上车机向手机要 MAC、下发连接串；WiFi 通道上双方请求断开。
 * 载荷是一串 `EA(tag) EA(长度) 值`，值是 UTF-8 文本：
 *
 * | tag | 方向 | 值 |
 * |---|---|---|
 * | 1 | 车机 → 手机 | 空：要手机的 WiFi MAC |
 * | 2 | 手机 → 车机 | 手机 MAC（17 个字符、小写、冒号分隔），或 `busy`（本次失败） |
 * | 3 | 车机 → 手机 | 连接串，见 [WifiConnectionInfo] |
 * | 4 | 车机 → 手机 | 车机自己的 MAC，只在车机当组成员时用（本实现不走） |
 * | 5 | 双向 | 空：请求断开（在 WiFi 通道上发） |
 * | 6 | 双向 | 空：断开应答 |
 */
internal object WifiLinkMessages {
    const val OUTER_TYPE = 33

    const val TAG_GET_PEER_MAC = 1
    const val TAG_PEER_MAC = 2
    const val TAG_CONNECTION_INFO = 3
    const val TAG_DISCONNECT = 5
    const val TAG_DISCONNECT_ACK = 6

    const val BUSY = "busy"

    private val MAC = Regex("[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}")

    /** 手机的 tag 2：`busy` 返回 null，MAC 统一成小写；别的内容抛 [MalformedMessageException]。 */
    fun parsePeerMac(value: ByteArray): String? {
        if (value.any { it < 0x20 || it > 0x7E }) throw MalformedMessageException("手机 MAC 不是可打印 ASCII")
        val text = String(value, Charsets.US_ASCII)
        if (text == BUSY) return null
        if (!MAC.matches(text)) throw MalformedMessageException("手机 MAC 格式不对（${text.length} 个字符）")
        return text.lowercase()
    }
}
