package io.github.gcbond.openhc.core.common.identity

import io.github.gcbond.openhc.core.common.toHex

/**
 * 蓝牙地址：6 字节原值（按书写顺序，左边第一段是第 0 个字节）与 `AA:BB:CC:DD:EE:FF` 文本互转。
 *
 * 无状态，线程安全。
 */
object BluetoothAddress {
    /** 原值长度（字节）。 */
    const val SIZE = 6

    /**
     * 安卓对普通应用隐藏真实蓝牙地址时返回的占位值。车机取不到自己的地址时就是它，
     * 设备 ID 遇到它按退化规则计算，见 [DeviceId]。
     */
    const val UNAVAILABLE = "02:00:00:00:00:00"

    /**
     * 解析 `AA:BB:CC:DD:EE:FF`：大小写都行，分隔符也可以是 `-`（全程一致），只认 ASCII 十六进制数字
     * （全角数字等不算）。
     *
     * @throws IllegalArgumentException 格式不对。
     */
    fun parse(text: String): ByteArray {
        require(text.length == 17) { FORMAT_ERROR }
        val sep = text[2]
        require(sep == ':' || sep == '-') { FORMAT_ERROR }
        val out = ByteArray(SIZE)
        for (i in 0 until SIZE) {
            if (i > 0) require(text[i * 3 - 1] == sep) { FORMAT_ERROR }
            val hi = hexDigit(text[i * 3])
            val lo = hexDigit(text[i * 3 + 1])
            require(hi >= 0 && lo >= 0) { FORMAT_ERROR }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /**
     * 格式化成大写、冒号分隔的文本，例如 `0A:1B:2C:3D:4E:5F`。
     *
     * @throws IllegalArgumentException [address] 不是 6 字节。
     */
    fun format(address: ByteArray): String {
        require(address.size == SIZE) { "蓝牙地址应为 $SIZE 字节" }
        val hex = address.toHex(upperCase = true)
        val sb = StringBuilder(17)
        for (i in 0 until SIZE) {
            if (i > 0) sb.append(':')
            sb.append(hex, i * 2, i * 2 + 2)
        }
        return sb.toString()
    }

    private const val FORMAT_ERROR = "蓝牙地址格式应为 AA:BB:CC:DD:EE:FF"

    /** 只认 ASCII 的 0-9、a-f、A-F。 */
    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
