package io.github.gcbond.openhc.core.common.codec

/**
 * CRC-16/ARC：多项式 0x8005（按反射形式 0xA001 逐位右移计算），初值 0，输入输出都反射，无最终异或。
 * ASCII `"123456789"` 的校验值是 0xBB3D。
 *
 * 结果是 0..0xFFFF 的整数；写进帧时用什么字节序由协议决定（nearby 控制帧、DMSDP 业务 20 帧都是小端放在帧尾）。
 *
 * 无状态，线程安全。
 */
object Crc16Arc {
    private val TABLE = IntArray(256) { n ->
        var crc = n
        repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        crc
    }

    /** 计算 `data[offset, offset + length)` 的 CRC。范围越界抛 [IllegalArgumentException]。 */
    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        require(offset >= 0 && length >= 0 && offset <= data.size - length) { "范围非法" }
        var crc = 0
        for (i in offset until offset + length) {
            crc = (crc ushr 8) xor TABLE[(crc xor data[i].toInt()) and 0xFF]
        }
        return crc
    }
}
