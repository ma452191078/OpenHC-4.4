package io.github.gcbond.openhc.platform.net

import io.github.gcbond.openhc.core.common.MalformedMessageException

/**
 * 车机在 USB 网卡上的最小以太网主机：一个 IPv4 地址加一个 MAC。纯逻辑，不依赖安卓。
 *
 * TUN 是三层设备，没有以太网头，内核也不会应答 ARP。这里补上这两样：
 * - 手机在网卡上用 ARP 找车机地址（实测每秒一次），由这里应答；
 * - 发给车机地址的 IPv4 包去掉以太网头交给 TUN；
 * - TUN 读出的 IPv4 包补上以太网头发给手机。
 *
 * 手机是链路上唯一的对端：它的 MAC 从它发来的帧里学，之后所有出向包都发给它。
 *
 * 线程：[handleFromPhone] 只在收方向的线程上调，[wrapForPhone] 只在发方向的线程上调，
 * 两者只共享 volatile 的手机地址。
 */
class EthernetEndpoint(carAddress: ByteArray, carMac: ByteArray = DEFAULT_CAR_MAC) {

    /** 处理一个手机发来的帧的结果。 */
    enum class Inbound {
        /** 手机问车机地址，已应答。 */
        ARP_REPLIED,

        /** 不需要应答的 ARP：应答、探测、宣告。 */
        ARP_IGNORED,

        /** 手机问的不是车机地址：多半是手机按别的 carInfo 在找另一个车机地址。 */
        ARP_FOR_OTHER_ADDRESS,

        /** 发给车机地址的 IPv4 包，已交给 TUN。 */
        DELIVERED,

        /** IPv4 包，但目的地址不是车机。 */
        NOT_FOR_US,

        /** 既不是 IPv4 也不是 ARP（例如手机发的 IPv6 组播侦听报告）。 */
        UNSUPPORTED_TYPE,

        /** 帧或包格式不对。 */
        MALFORMED,
    }

    /** 处理一个 TUN 读出的包的结果。 */
    enum class Outbound {
        /** 已补好以太网头。 */
        WRAPPED,

        /** 还不知道手机的 MAC（手机还没发过 ARP 或 IPv4）。 */
        NO_PHONE_YET,

        /** 不是 IPv4，或格式不对。 */
        DROPPED,
    }

    /** 收方向处理结果的去处。两个方法都要在返回前用完传入的缓冲，缓冲之后会被复用。 */
    interface Output {
        /** 把以太网帧发给手机。 */
        fun sendToPhone(frame: ByteArray, offset: Int, length: Int)

        /** 把 IPv4 包写进 TUN。 */
        fun deliverToTunnel(packet: ByteArray, offset: Int, length: Int)
    }

    private val carAddress = carAddress.copyOf()
    private val carMac = carMac.copyOf()
    private val arpReply = ByteArray(Ethernet.HEADER_LENGTH + Arp.LENGTH)

    @Volatile
    private var phoneMac: ByteArray? = null

    @Volatile
    private var phoneIp: ByteArray? = null

    init {
        require(carAddress.size == Ipv4.ADDRESS_LENGTH) { "车机地址必须是 4 字节 IPv4 地址" }
        require(carMac.size == Ethernet.MAC_LENGTH && Ethernet.isUnicast(carMac)) { "车机 MAC 必须是 6 字节单播地址" }
    }

    /** 学到的手机 IPv4 地址（副本），还没学到时为 null。手机连不上时会自己换地址重试。 */
    val phoneAddress: ByteArray? get() = phoneIp?.copyOf()

    /** 是否已经知道手机的 MAC。 */
    val knowsPhone: Boolean get() = phoneMac != null

    /** 处理手机发来的一个以太网帧 `frame[offset, offset + length)`。 */
    fun handleFromPhone(frame: ByteArray, offset: Int, length: Int, output: Output): Inbound {
        if (length < Ethernet.HEADER_LENGTH) return Inbound.MALFORMED
        return when (Ethernet.etherType(frame, offset)) {
            Ethernet.TYPE_ARP -> handleArp(frame, offset, length, output)
            Ethernet.TYPE_IPV4 -> handleIpv4(frame, offset, length, output)
            else -> Inbound.UNSUPPORTED_TYPE
        }
    }

    /**
     * TUN 读出的 IPv4 包放在 `buffer[packetOffset, packetOffset + packetLength)`，且 `packetOffset ≥ 14`：
     * 在它前面写好以太网头。返回 [Outbound.WRAPPED] 时，帧从 `packetOffset - 14` 开始，
     * 长 `14 + IPv4 总长`（用 [frameLength] 取）。
     */
    fun wrapForPhone(buffer: ByteArray, packetOffset: Int, packetLength: Int): Outbound {
        require(packetOffset >= Ethernet.HEADER_LENGTH) { "包前面要留出以太网头的位置" }
        try {
            // 内核交出的包头校验和总是对的，不再重算一遍。
            Ipv4.checkedTotalLength(buffer, packetOffset, packetLength, verifyChecksum = false)
        } catch (e: MalformedMessageException) {
            return Outbound.DROPPED
        }
        val mac = phoneMac ?: return Outbound.NO_PHONE_YET
        Ethernet.writeHeader(buffer, packetOffset - Ethernet.HEADER_LENGTH, mac, carMac, Ethernet.TYPE_IPV4)
        return Outbound.WRAPPED
    }

    /** [wrapForPhone] 成功后整帧的长度。 */
    fun frameLength(buffer: ByteArray, packetOffset: Int): Int =
        Ethernet.HEADER_LENGTH + Ipv4.totalLength(buffer, packetOffset)

    private fun handleArp(frame: ByteArray, offset: Int, length: Int, output: Output): Inbound {
        val arp = try {
            Arp.parse(frame, offset, length)
        } catch (e: MalformedMessageException) {
            return Inbound.MALFORMED
        }
        if (arp.operation != Arp.OP_REQUEST || arp.isProbe || arp.isAnnouncement) return Inbound.ARP_IGNORED
        if (!Ethernet.isUnicast(arp.senderMac)) return Inbound.MALFORMED
        if (!arp.targetIp.contentEquals(carAddress)) return Inbound.ARP_FOR_OTHER_ADDRESS
        learnPhone(arp.senderMac, 0, arp.senderIp, 0)
        val n = Arp.writeReply(arp, carMac, arpReply, 0)
        output.sendToPhone(arpReply, 0, n)
        return Inbound.ARP_REPLIED
    }

    private fun handleIpv4(frame: ByteArray, offset: Int, length: Int, output: Output): Inbound {
        val ip = offset + Ethernet.HEADER_LENGTH
        val total = try {
            Ipv4.checkedTotalLength(frame, ip, length - Ethernet.HEADER_LENGTH)
        } catch (e: MalformedMessageException) {
            return Inbound.MALFORMED
        }
        if (!regionEquals(frame, Ipv4.destinationAt(ip), carAddress, 0, Ipv4.ADDRESS_LENGTH)) return Inbound.NOT_FOR_US
        if (Ethernet.isUnicast(frame, offset + Ethernet.MAC_LENGTH)) {
            learnPhone(frame, offset + Ethernet.MAC_LENGTH, frame, Ipv4.sourceAt(ip))
        }
        output.deliverToTunnel(frame, ip, total)
        return Inbound.DELIVERED
    }

    /** 记下手机的 MAC 和 IPv4 地址；没变时不分配新数组。 */
    private fun learnPhone(mac: ByteArray, macOffset: Int, ip: ByteArray, ipOffset: Int) {
        val knownMac = phoneMac
        if (knownMac == null || !regionEquals(knownMac, 0, mac, macOffset, Ethernet.MAC_LENGTH)) {
            phoneMac = mac.copyOfRange(macOffset, macOffset + Ethernet.MAC_LENGTH)
        }
        val knownIp = phoneIp
        if (knownIp == null || !regionEquals(knownIp, 0, ip, ipOffset, Ipv4.ADDRESS_LENGTH)) {
            phoneIp = ip.copyOfRange(ipOffset, ipOffset + Ipv4.ADDRESS_LENGTH)
        }
    }

    companion object {
        /**
         * 车机这一端默认用的 MAC：本地管理（第一字节 bit1=1）的单播地址，不会与厂商分配的真实网卡地址重复。
         * 手机每次连接都会用 ARP 重新学，所以固定一个值即可。
         */
        val DEFAULT_CAR_MAC: ByteArray get() = byteArrayOf(0x02, 0x4F, 0x48, 0x43, 0x00, 0x01)
    }
}
