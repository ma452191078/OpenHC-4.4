package io.github.gcbond.openhc.platform.wifi

import io.github.gcbond.openhc.core.common.toHex

/**
 * MAC 地址的文本处理：整理成统一写法、认出系统给的占位值、日志里打码。WiFi 网卡地址和蓝牙地址都用它。不引用安卓类。
 */
object MacText {
    /** 安卓给没有权限的应用看的「本机 MAC」。 */
    const val PLACEHOLDER = "02:00:00:00:00:00"

    private val MAC = Regex("[0-9a-f]{2}([:-][0-9a-f]{2}){5}")

    /**
     * 整理成小写、冒号分隔；接受 `-` 分隔和大写。格式不对、全 0、全 F、组播地址或占位值返回 null。
     */
    fun normalize(text: String?): String? {
        val t = text?.trim()?.lowercase() ?: return null
        if (!MAC.matches(t)) return null
        val mac = t.replace('-', ':')
        if (mac == PLACEHOLDER || mac == "00:00:00:00:00:00" || mac == "ff:ff:ff:ff:ff:ff") return null
        // 第一个字节最低位是 1 的是组播地址，不会是网卡地址。
        if (mac.substring(0, 2).toInt(16) and 1 == 1) return null
        return mac
    }

    /** 6 字节硬件地址转成文本并按 [normalize] 过滤；长度不对返回 null。 */
    fun of(bytes: ByteArray?): String? {
        if (bytes == null || bytes.size != 6) return null
        return normalize(bytes.toHex().chunked(2).joinToString(":"))
    }

    /** 日志里的 MAC 只留第一段和最后两段：`aa:**:**:**:ee:ff`；格式不对的整个打码，占位值原样。 */
    fun mask(mac: String?): String {
        val parts = mac?.split(':', '-') ?: return "(无)"
        if (parts.size != 6) return "**"
        if (mac.equals(PLACEHOLDER, ignoreCase = true)) return PLACEHOLDER
        return parts[0] + ":**:**:**:" + parts[4] + ":" + parts[5]
    }
}
