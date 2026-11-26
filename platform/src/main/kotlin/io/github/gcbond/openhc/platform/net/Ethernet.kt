package io.github.gcbond.openhc.platform.net

/**
 * 以太网 II 帧头（14 字节，大端）：目的 MAC 6 | 源 MAC 6 | 类型 2。帧尾的 FCS 不经过 NCM。
 */
object Ethernet {
    const val HEADER_LENGTH = 14
    const val MAC_LENGTH = 6

    const val TYPE_IPV4 = 0x0800
    const val TYPE_ARP = 0x0806
    const val TYPE_IPV6 = 0x86DD

    fun etherType(frame: ByteArray, offset: Int): Int = frame.u16be(offset + 12)

    /** 在 `buffer[offset]` 写一个以太网头。 */
    fun writeHeader(buffer: ByteArray, offset: Int, destination: ByteArray, source: ByteArray, etherType: Int) {
        System.arraycopy(destination, 0, buffer, offset, MAC_LENGTH)
        System.arraycopy(source, 0, buffer, offset + MAC_LENGTH, MAC_LENGTH)
        buffer.putU16be(offset + 12, etherType)
    }

    /** 最低位为 0 的是单播地址；组播、广播地址不能当作某台设备的地址记下来。 */
    fun isUnicast(mac: ByteArray, offset: Int = 0): Boolean = (mac[offset].toInt() and 0x01) == 0

    /** 日志用：只显示末两段，避免完整设备标识进日志。 */
    fun maskedMac(mac: ByteArray, offset: Int = 0): String =
        "xx:xx:xx:xx:" + hexByte(mac.u8(offset + 4)) + ":" + hexByte(mac.u8(offset + 5))

    private fun hexByte(value: Int): String {
        val digits = "0123456789abcdef"
        return "" + digits[value ushr 4] + digits[value and 0x0F]
    }
}
