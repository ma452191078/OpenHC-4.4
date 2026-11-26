package io.github.gcbond.openhc.platform.net

import io.github.gcbond.openhc.core.common.MalformedMessageException

/**
 * IPv4 头（RFC 791，大端）的最小处理：
 * ```
 * 版本 4 位 | 头长 4 位(×4 字节) | 服务类型 1 | 总长 2 | 标识 2 | 标志与片偏移 2
 * 生存时间 1 | 协议 1 | 头校验和 2 | 源地址 4 | 目的地址 4 | 选项 …
 * ```
 * 车机这一端不改写 IP 包，只做转发前的合法性检查和取地址。
 */
object Ipv4 {
    const val MIN_HEADER_LENGTH = 20
    const val ADDRESS_LENGTH = 4

    const val PROTOCOL_ICMP = 1
    const val PROTOCOL_TCP = 6
    const val PROTOCOL_UDP = 17

    fun version(packet: ByteArray, offset: Int): Int = packet.u8(offset) ushr 4

    fun headerLength(packet: ByteArray, offset: Int): Int = (packet.u8(offset) and 0x0F) * 4

    fun totalLength(packet: ByteArray, offset: Int): Int = packet.u16be(offset + 2)

    fun protocol(packet: ByteArray, offset: Int): Int = packet.u8(offset + 9)

    fun sourceAt(offset: Int): Int = offset + 12

    fun destinationAt(offset: Int): Int = offset + 16

    /**
     * 检查 `packet[offset, offset + available)` 开头是一个完整的 IPv4 包，返回它的总长。
     * 以太网帧可能带补齐字节，所以包长以头里的总长为准，`available` 只是上限。
     * 版本、头长、总长、头校验和任何一项不对都抛 [MalformedMessageException]。
     */
    fun checkedTotalLength(packet: ByteArray, offset: Int, available: Int, verifyChecksum: Boolean = true): Int {
        if (available < MIN_HEADER_LENGTH) throw MalformedMessageException("IPv4 包太短: $available 字节")
        if (version(packet, offset) != 4) throw MalformedMessageException("不是 IPv4: 版本 ${version(packet, offset)}")
        val headerLength = headerLength(packet, offset)
        if (headerLength < MIN_HEADER_LENGTH) throw MalformedMessageException("IPv4 头长非法: $headerLength")
        val total = totalLength(packet, offset)
        if (total < headerLength || total > available) {
            throw MalformedMessageException("IPv4 总长非法: $total（头长 $headerLength，可用 $available）")
        }
        if (verifyChecksum && !InternetChecksum.isValid(packet, offset, headerLength)) {
            throw MalformedMessageException("IPv4 头校验和不对")
        }
        return total
    }

    /** 重算并写入头校验和。 */
    fun writeHeaderChecksum(packet: ByteArray, offset: Int) {
        val headerLength = headerLength(packet, offset)
        packet.putU16be(offset + 10, 0)
        packet.putU16be(offset + 10, InternetChecksum.compute(packet, offset, headerLength))
    }

    /** 四点分十进制，日志用。 */
    fun format(address: ByteArray, offset: Int = 0): String =
        "${address.u8(offset)}.${address.u8(offset + 1)}.${address.u8(offset + 2)}.${address.u8(offset + 3)}"
}
