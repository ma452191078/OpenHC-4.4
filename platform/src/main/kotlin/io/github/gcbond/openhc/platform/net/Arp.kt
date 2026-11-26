package io.github.gcbond.openhc.platform.net

import io.github.gcbond.openhc.core.common.MalformedMessageException

/**
 * 以太网上的 IPv4 ARP（RFC 826），紧跟在以太网头之后，28 字节，大端：
 * ```
 * 硬件类型 2 (=1) | 协议类型 2 (=0x0800) | 硬件地址长 1 (=6) | 协议地址长 1 (=4) | 操作 2
 * 发送方 MAC 6 | 发送方 IP 4 | 目标 MAC 6 | 目标 IP 4
 * ```
 * TUN 是三层设备，内核不会替我们应答 ARP，所以车机这一端由 [EthernetEndpoint] 用它自己应答。
 */
object Arp {
    const val LENGTH = 28
    const val HARDWARE_ETHERNET = 1
    const val OP_REQUEST = 1
    const val OP_REPLY = 2

    /** 解析出的一条 ARP。地址都是新数组，可以保存。 */
    class Packet(
        val operation: Int,
        val senderMac: ByteArray,
        val senderIp: ByteArray,
        val targetMac: ByteArray,
        val targetIp: ByteArray,
    ) {
        /** 地址冲突探测（发送方 IP 为 0.0.0.0）：对方在查自己的地址有没有人占用，不该应答。 */
        val isProbe: Boolean get() = isAllZero(senderIp, 0, 4)

        /** 地址宣告（发送方 IP 等于目标 IP）：对方在通告自己的地址，不该应答。 */
        val isAnnouncement: Boolean get() = senderIp.contentEquals(targetIp)
    }

    /**
     * 解析 `frame[offset, offset + length)` 这个以太网帧里的 ARP。帧必须是 ARP 类型；
     * 不是「以太网 + IPv4」这一种 ARP、或长度不够时抛 [MalformedMessageException]。
     */
    fun parse(frame: ByteArray, offset: Int, length: Int): Packet {
        if (length < Ethernet.HEADER_LENGTH + LENGTH) throw MalformedMessageException("ARP 帧太短: $length 字节")
        val arp = offset + Ethernet.HEADER_LENGTH
        if (frame.u16be(arp) != HARDWARE_ETHERNET || frame.u16be(arp + 2) != Ethernet.TYPE_IPV4 ||
            frame.u8(arp + 4) != Ethernet.MAC_LENGTH || frame.u8(arp + 5) != 4
        ) {
            throw MalformedMessageException("不是以太网上的 IPv4 ARP")
        }
        return Packet(
            operation = frame.u16be(arp + 6),
            senderMac = frame.copyOfRange(arp + 8, arp + 14),
            senderIp = frame.copyOfRange(arp + 14, arp + 18),
            targetMac = frame.copyOfRange(arp + 18, arp + 24),
            targetIp = frame.copyOfRange(arp + 24, arp + 28),
        )
    }

    /**
     * 对 [request] 写一个应答帧到 `out[outOffset]`，返回帧长（42，不含补齐）：
     * 以太网目的地址与 ARP 目标都是请求方；「目标 IP 在 [ourMac] 这里」。
     */
    fun writeReply(request: Packet, ourMac: ByteArray, out: ByteArray, outOffset: Int): Int {
        Ethernet.writeHeader(out, outOffset, request.senderMac, ourMac, Ethernet.TYPE_ARP)
        val arp = outOffset + Ethernet.HEADER_LENGTH
        out.putU16be(arp, HARDWARE_ETHERNET)
        out.putU16be(arp + 2, Ethernet.TYPE_IPV4)
        out[arp + 4] = Ethernet.MAC_LENGTH.toByte()
        out[arp + 5] = 4
        out.putU16be(arp + 6, OP_REPLY)
        System.arraycopy(ourMac, 0, out, arp + 8, Ethernet.MAC_LENGTH)
        System.arraycopy(request.targetIp, 0, out, arp + 14, 4)
        System.arraycopy(request.senderMac, 0, out, arp + 18, Ethernet.MAC_LENGTH)
        System.arraycopy(request.senderIp, 0, out, arp + 24, 4)
        return Ethernet.HEADER_LENGTH + LENGTH
    }
}
