package io.github.gcbond.openhc.core.common

/**
 * 标准 Base64（RFC 4648 第 4 节，带 '=' 补齐）。
 *
 * 自己实现是因为 `java.util.Base64` 在安卓 8.0 以前不存在，而协议核心要跑在安卓 6.0 上。
 */
object Base64 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val DECODE = IntArray(128) { -1 }.also { table ->
        for (i in ALPHABET.indices) table[ALPHABET[i].code] = i
    }

    fun encode(data: ByteArray): String {
        val out = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i + 3 <= data.size) {
            val n = (data[i].u() shl 16) or (data[i + 1].u() shl 8) or data[i + 2].u()
            out.append(ALPHABET[n ushr 18]).append(ALPHABET[(n ushr 12) and 63])
                .append(ALPHABET[(n ushr 6) and 63]).append(ALPHABET[n and 63])
            i += 3
        }
        when (data.size - i) {
            1 -> {
                val n = data[i].u() shl 16
                out.append(ALPHABET[n ushr 18]).append(ALPHABET[(n ushr 12) and 63]).append("==")
            }
            2 -> {
                val n = (data[i].u() shl 16) or (data[i + 1].u() shl 8)
                out.append(ALPHABET[n ushr 18]).append(ALPHABET[(n ushr 12) and 63])
                    .append(ALPHABET[(n ushr 6) and 63]).append('=')
            }
        }
        return out.toString()
    }

    /** 解码；忽略空白字符，长度或字符非法时抛 [IllegalArgumentException]。 */
    fun decode(text: String): ByteArray {
        val s = text.filterNot { it.isWhitespace() }
        require(s.length % 4 == 0) { "Base64 长度必须是 4 的倍数: ${s.length}" }
        if (s.isEmpty()) return ByteArray(0)
        val pad = when {
            s.endsWith("==") -> 2
            s.endsWith("=") -> 1
            else -> 0
        }
        val out = ByteArray(s.length / 4 * 3 - pad)
        var o = 0
        for (q in 0 until s.length / 4) {
            var n = 0
            for (k in 0 until 4) {
                val c = s[q * 4 + k]
                val isPad = c == '=' && q == s.length / 4 - 1 && k >= 4 - pad
                val v = if (isPad) 0 else c.code.let { if (it < 128) DECODE[it] else -1 }
                require(v >= 0) { "不是 Base64 字符: 位置 ${q * 4 + k}" }
                n = (n shl 6) or v
            }
            if (o < out.size) out[o++] = (n ushr 16).toByte()
            if (o < out.size) out[o++] = (n ushr 8).toByte()
            if (o < out.size) out[o++] = n.toByte()
        }
        return out
    }

    private fun Byte.u() = toInt() and 0xFF
}
