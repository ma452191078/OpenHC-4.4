package io.github.gcbond.openhc.platform.net

/**
 * 测试用的合成帧。地址都是合成值：网段取协议规定的 192.168.66.0/24，主机号随手取；
 * MAC 用本地管理位置 1 的递增序列。
 */
internal object TestFrames {
    val CAR_IP: ByteArray get() = ip(192, 168, 66, 123)
    val PHONE_IP: ByteArray get() = ip(192, 168, 66, 45)
    val OTHER_IP: ByteArray get() = ip(192, 168, 66, 200)
    val PHONE_MAC: ByteArray get() = byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x00, 0x02)
    val OTHER_MAC: ByteArray get() = byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x00, 0x03)
    val BROADCAST_MAC: ByteArray get() = ByteArray(6) { 0xFF.toByte() }

    fun ip(a: Int, b: Int, c: Int, d: Int): ByteArray = byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())

    /** 以太网帧；[padTo60] 时按以太网最短帧补零。 */
    fun ethernet(destination: ByteArray, source: ByteArray, type: Int, payload: ByteArray, padTo60: Boolean = false): ByteArray {
        val length = maxOf(Ethernet.HEADER_LENGTH + payload.size, if (padTo60) 60 else 0)
        val frame = ByteArray(length)
        Ethernet.writeHeader(frame, 0, destination, source, type)
        System.arraycopy(payload, 0, frame, Ethernet.HEADER_LENGTH, payload.size)
        return frame
    }

    /** 以太网 + ARP（42 字节）。 */
    fun arp(
        operation: Int,
        senderMac: ByteArray,
        senderIp: ByteArray,
        targetMac: ByteArray,
        targetIp: ByteArray,
        destination: ByteArray = BROADCAST_MAC,
    ): ByteArray {
        val arp = ByteArray(Arp.LENGTH)
        arp.putU16be(0, Arp.HARDWARE_ETHERNET)
        arp.putU16be(2, Ethernet.TYPE_IPV4)
        arp[4] = 6
        arp[5] = 4
        arp.putU16be(6, operation)
        System.arraycopy(senderMac, 0, arp, 8, 6)
        System.arraycopy(senderIp, 0, arp, 14, 4)
        System.arraycopy(targetMac, 0, arp, 18, 6)
        System.arraycopy(targetIp, 0, arp, 24, 4)
        return ethernet(destination, senderMac, Ethernet.TYPE_ARP, arp)
    }

    fun arpRequest(senderMac: ByteArray, senderIp: ByteArray, targetIp: ByteArray): ByteArray =
        arp(Arp.OP_REQUEST, senderMac, senderIp, ByteArray(6), targetIp)

    /** IPv4 包，头校验和已填好。 */
    fun ipv4(source: ByteArray, destination: ByteArray, protocol: Int, payload: ByteArray): ByteArray {
        val packet = ByteArray(Ipv4.MIN_HEADER_LENGTH + payload.size)
        packet[0] = 0x45
        packet.putU16be(2, packet.size)
        packet.putU16be(4, 0x0101)
        packet.putU16be(6, 0x4000)
        packet[8] = 64
        packet[9] = protocol.toByte()
        System.arraycopy(source, 0, packet, 12, 4)
        System.arraycopy(destination, 0, packet, 16, 4)
        System.arraycopy(payload, 0, packet, Ipv4.MIN_HEADER_LENGTH, payload.size)
        Ipv4.writeHeaderChecksum(packet, 0)
        return packet
    }

    /** IPv4 + UDP，UDP 校验和已填好。 */
    fun udp(source: ByteArray, destination: ByteArray, sourcePort: Int, destinationPort: Int, data: ByteArray): ByteArray {
        val udp = ByteArray(8 + data.size)
        udp.putU16be(0, sourcePort)
        udp.putU16be(2, destinationPort)
        udp.putU16be(4, udp.size)
        System.arraycopy(data, 0, udp, 8, data.size)
        val packet = ipv4(source, destination, Ipv4.PROTOCOL_UDP, udp)
        packet.putU16be(Ipv4.MIN_HEADER_LENGTH + 6, InternetChecksum.transport(packet, 0))
        return packet
    }

    /** 手机 → 车机的 TCP SYN（带一个 MSS 选项），校验和已填好。 */
    fun tcpSyn(source: ByteArray, destination: ByteArray, sourcePort: Int, destinationPort: Int): ByteArray {
        val tcp = ByteArray(24)
        tcp.putU16be(0, sourcePort)
        tcp.putU16be(2, destinationPort)
        tcp.putU16be(4, 0x1000)
        tcp[12] = 0x60 // 头长 24 字节
        tcp[13] = 0x02 // SYN
        tcp.putU16be(14, 0xFFFF)
        tcp[20] = 2 // MSS 选项
        tcp[21] = 4
        tcp.putU16be(22, 1460)
        val packet = ipv4(source, destination, Ipv4.PROTOCOL_TCP, tcp)
        packet.putU16be(Ipv4.MIN_HEADER_LENGTH + 16, InternetChecksum.transport(packet, 0))
        return packet
    }

    /**
     * 手工拼一块 NTB16，用来构造本端不会发出的形状（多个数据报、多个 NDP16）。
     * [ndps] 每项是一个 NDP16：放在哪、它的数据报表（偏移, 长度）、下一个 NDP16 的偏移。
     */
    class NtbBuilder(size: Int) {
        val bytes = ByteArray(size)

        init {
            bytes.putU32le(0, Ntb16.NTH16_SIGNATURE)
            bytes.putU16le(4, Ntb16.NTH16_LENGTH)
            bytes.putU16le(6, 1)
            bytes.putU16le(8, size)
        }

        fun firstNdp(index: Int) = apply { bytes.putU16le(10, index) }

        fun blockLength(length: Int) = apply { bytes.putU16le(8, length) }

        /** 写一个 NDP16，表项后自动补终止项。 */
        fun ndp(at: Int, entries: List<Pair<Int, Int>>, next: Int = 0, signature: Int = Ntb16.NDP16_SIGNATURE) = apply {
            bytes.putU32le(at, signature)
            bytes.putU16le(at + 4, Ntb16.NDP16_HEADER_LENGTH + (entries.size + 1) * Ntb16.NDP16_ENTRY_LENGTH)
            bytes.putU16le(at + 6, next)
            entries.forEachIndexed { i, (index, length) ->
                bytes.putU16le(at + 8 + i * 4, index)
                bytes.putU16le(at + 10 + i * 4, length)
            }
        }

        fun datagram(at: Int, data: ByteArray) = apply { System.arraycopy(data, 0, bytes, at, data.size) }
    }

    /** 递增字节，便于核对内容是否原样搬运。 */
    fun pattern(length: Int, start: Int = 0): ByteArray = ByteArray(length) { (start + it).toByte() }
}
