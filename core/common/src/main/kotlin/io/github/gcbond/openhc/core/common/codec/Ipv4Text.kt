package io.github.gcbond.openhc.core.common.codec

/** IPv4 点分十进制文本。无状态，线程安全。 */
object Ipv4Text {
    /**
     * 是否是规范写法：正好 4 段，每段 1～3 个 ASCII 数字、值 0～255、除了 `0` 本身不带前导 0。
     * 例如 `192.168.66.100` 是，`192.168.066.100`、`１92.168.66.100`（全角数字）、`1.2.3` 都不是。
     */
    fun isCanonical(text: String): Boolean {
        val parts = text.split('.')
        if (parts.size != 4) return false
        return parts.all { p ->
            p.length in 1..3 && p.all { it in '0'..'9' } && (p.length == 1 || p[0] != '0') && p.toInt() <= 255
        }
    }
}
