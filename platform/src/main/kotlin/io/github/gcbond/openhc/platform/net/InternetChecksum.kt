package io.github.gcbond.openhc.platform.net

/**
 * 互联网校验和（RFC 1071）：按大端 16 位字做一补数累加再取反；奇数长度时末字节当高 8 位、低位补 0。
 * IPv4 头、ICMP、TCP、UDP 都用它；TCP/UDP 还要把伪首部（源 IP、目的 IP、协议号、传输层长度）算进去。
 */
object InternetChecksum {

    /** 一补数累加，不取反。用 Long 累加，长数据也不会溢出；结果交给 [fold]。 */
    fun sum(data: ByteArray, offset: Int, length: Int, initial: Long = 0): Long {
        var sum = initial
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += data.u16be(i)
            i += 2
        }
        if (i < end) sum += data.u8(i) shl 8
        return sum
    }

    /** 把累加和折回 16 位。 */
    fun fold(sum: Long): Int {
        var s = sum
        while (s ushr 16 != 0L) s = (s and 0xFFFF) + (s ushr 16)
        return s.toInt()
    }

    /** 校验和本身（累加、折叠、取反）。 */
    fun compute(data: ByteArray, offset: Int, length: Int): Int = fold(sum(data, offset, length)).inv() and 0xFFFF

    /** 含校验和字段一起累加，结果为 0xFFFF 即正确。 */
    fun isValid(data: ByteArray, offset: Int, length: Int): Boolean = fold(sum(data, offset, length)) == 0xFFFF

    /**
     * TCP/UDP 校验和（含伪首部）。[ipOffset] 指向 IPv4 头；传输层从 IPv4 头之后开始，到 IPv4 总长为止。
     * 计算时把校验和字段当 0。调用方先用 [Ipv4.checkedTotalLength] 确认包完整。
     */
    fun transport(packet: ByteArray, ipOffset: Int): Int {
        val headerLength = Ipv4.headerLength(packet, ipOffset)
        val l4 = ipOffset + headerLength
        val l4Length = Ipv4.totalLength(packet, ipOffset) - headerLength
        val protocol = Ipv4.protocol(packet, ipOffset)
        val checksumAt = when (protocol) {
            Ipv4.PROTOCOL_TCP -> l4 + 16
            Ipv4.PROTOCOL_UDP -> l4 + 6
            else -> throw IllegalArgumentException("只有 TCP/UDP 有伪首部校验和: $protocol")
        }
        require(checksumAt + 2 <= l4 + l4Length) { "传输层头太短" }
        var s = sum(packet, ipOffset + 12, 8)
        s += protocol.toLong()
        s += l4Length.toLong()
        s = sum(packet, l4, checksumAt - l4, s)
        s = sum(packet, checksumAt + 2, l4 + l4Length - checksumAt - 2, s)
        return fold(s).inv() and 0xFFFF
    }
}
