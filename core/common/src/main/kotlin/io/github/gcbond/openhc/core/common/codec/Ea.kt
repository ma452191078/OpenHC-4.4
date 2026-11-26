package io.github.gcbond.openhc.core.common.codec

/**
 * EA 变长整数：nearby 控制帧（外层类型、长度、TLV）和 DMSDP 业务 20 帧头、设备消息命令号都用它。
 *
 * 每字节低 7 位承载数值，**低位组在前**；**最高位为 1 的字节是最后一个字节**（终止位），
 * 与 protobuf varint「最高位表示还有后续」正好相反。最多 [MAX_BYTES] 字节，取值 0..[MAX_VALUE]（2^28 − 1）。
 *
 * ```
 * 0 → 80    1 → 81    127 → FF    128 → 00 81    155 → 1B 81    0x0FFFFFFF → 7F 7F 7F FF
 * ```
 *
 * 编码总是最短形式；解码也接受非最短形式（例如 `00 80` 是 0）。
 * 写进 [io.github.gcbond.openhc.core.common.ByteWriter]、从 [io.github.gcbond.openhc.core.common.ByteReader]
 * 读出用它们的 `ea` 方法。
 *
 * 无状态，线程安全。
 */
object Ea {
    /** 一个 EA 最多占的字节数。 */
    const val MAX_BYTES = 4

    /** 能表示的最大值（2^28 − 1）。 */
    const val MAX_VALUE = 0x0FFF_FFFF

    /** [decode] 的返回值：在终止字节之前数据就到头了，要等更多数据。 */
    const val NEED_MORE = -1L

    /** [decode] 的返回值：连续 [MAX_BYTES] 字节都没有终止位，格式非法。 */
    const val UNTERMINATED = -2L

    /** [value] 编码后占的字节数（1..4）。超出 0..[MAX_VALUE] 抛 [IllegalArgumentException]。 */
    fun encodedSize(value: Int): Int {
        require(value in 0..MAX_VALUE) { "EA 取值越界: $value" }
        var size = 1
        var rest = value ushr 7
        while (rest != 0) {
            size++
            rest = rest ushr 7
        }
        return size
    }

    /** 编码成最短形式。超出 0..[MAX_VALUE] 抛 [IllegalArgumentException]。 */
    fun encode(value: Int): ByteArray {
        val out = ByteArray(encodedSize(value))
        var rest = value
        for (i in out.indices) {
            val group = rest and 0x7F
            rest = rest ushr 7
            out[i] = (if (i == out.size - 1) group or 0x80 else group).toByte()
        }
        return out
    }

    /**
     * 从 `data[offset, limit)` 开头解一个 EA。不因数据格式抛异常，方便流式拆帧的调用方自己决定怎么报错：
     *
     * - 成功：返回 `值 shl 8 or 占用字节数`，用 [valueOf]、[sizeOf] 拆开；
     * - 终止字节之前数据就到头了（含 `offset >= limit`）：返回 [NEED_MORE]；
     * - 连续 [MAX_BYTES] 字节都没有终止位：返回 [UNTERMINATED]。
     *
     * @throws IllegalArgumentException [offset] 为负或 [limit] 超出数组长度。
     */
    fun decode(data: ByteArray, offset: Int, limit: Int): Long {
        require(offset >= 0 && limit <= data.size) { "范围非法: offset=$offset limit=$limit size=${data.size}" }
        var value = 0
        for (i in 0 until MAX_BYTES) {
            val pos = offset + i
            if (pos >= limit) return NEED_MORE
            val b = data[pos].toInt() and 0xFF
            value = value or ((b and 0x7F) shl (7 * i))
            if (b and 0x80 != 0) return (value.toLong() shl 8) or (i + 1).toLong()
        }
        return UNTERMINATED
    }

    /** [decode] 成功时的值。 */
    fun valueOf(packed: Long): Int = (packed ushr 8).toInt()

    /** [decode] 成功时占用的字节数。 */
    fun sizeOf(packed: Long): Int = (packed and 0xFF).toInt()
}
