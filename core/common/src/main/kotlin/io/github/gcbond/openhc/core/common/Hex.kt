package io.github.gcbond.openhc.core.common

private const val LOWER_DIGITS = "0123456789abcdef"
private const val UPPER_DIGITS = "0123456789ABCDEF"

/** 转成十六进制字符串（默认小写，无分隔符）。 */
fun ByteArray.toHex(upperCase: Boolean = false): String {
    val digits = if (upperCase) UPPER_DIGITS else LOWER_DIGITS
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        out[i * 2] = digits[v ushr 4]
        out[i * 2 + 1] = digits[v and 0x0F]
    }
    return String(out)
}

/** 解析十六进制字符串（大小写均可，不允许分隔符和奇数长度）。 */
fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "十六进制长度必须是偶数: $length" }
    return ByteArray(length / 2) { i ->
        val hi = Character.digit(this[i * 2], 16)
        val lo = Character.digit(this[i * 2 + 1], 16)
        require(hi >= 0 && lo >= 0) { "不是十六进制字符: 位置 ${i * 2}" }
        ((hi shl 4) or lo).toByte()
    }
}
