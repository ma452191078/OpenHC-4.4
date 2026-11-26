package io.github.gcbond.openhc.core.dmsdp

import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.common.codec.Utf8
import java.nio.charset.CharacterCodingException
import io.github.gcbond.openhc.core.common.codec.Ea as EaCodec

/**
 * EA 变长整数：用在业务 20 TCP 的外层帧头，也用在设备消息里的命令号和端口。
 * 格式与编解码在 core:common 的 [EaCodec]；这里只保留本层拆帧用的接口和报错说法。
 */
internal object Ea {
    const val MAX_VALUE = EaCodec.MAX_VALUE
    const val MAX_BYTES = EaCodec.MAX_BYTES

    class Decoded(val value: Int, val next: Int)

    fun encode(value: Int): ByteArray {
        require(value in 0..MAX_VALUE) { "EA 超出范围: $value" }
        return EaCodec.encode(value)
    }

    /**
     * 从 `data[offset, limit)` 解一个 EA。字节还不够（已有字节都没有结束位、且不足 4 个）返回 null，
     * 方便流式拆帧；连续 4 个字节都没有结束位时抛 [MalformedMessageException]。
     */
    fun decode(data: ByteArray, offset: Int, limit: Int): Decoded? {
        val packed = EaCodec.decode(data, offset, limit)
        return when (packed) {
            EaCodec.NEED_MORE -> null
            EaCodec.UNTERMINATED -> throw MalformedMessageException("EA 连续 4 字节都没有结束位")
            else -> Decoded(EaCodec.valueOf(packed), offset + EaCodec.sizeOf(packed))
        }
    }

    /** 在一段完整数据里解 EA：缺结束位一律算格式错误。 */
    fun decodeComplete(data: ByteArray, offset: Int, what: String): Decoded =
        decode(data, offset, data.size) ?: throw MalformedMessageException("$what 的 EA 不完整")
}

/** CRC-16/ARC（"123456789" 的校验值为 0xBB3D），实现在 core:common。 */
internal typealias Crc16Arc = io.github.gcbond.openhc.core.common.codec.Crc16Arc

/** 严格的 UTF-8 解码（实现在 core:common 的 [Utf8]）：非法字节序列抛 [MalformedMessageException]，不做替换。 */
internal fun strictUtf8(data: ByteArray, offset: Int = 0, length: Int = data.size - offset, what: String): String =
    try {
        Utf8.decodeStrict(data, offset, length)
    } catch (e: CharacterCodingException) {
        throw MalformedMessageException("$what 不是合法的 UTF-8", e)
    }

/** 非空且全部是 ASCII 数字 0-9。 */
internal fun isAsciiDecimal(text: String): Boolean = text.isNotEmpty() && text.all { it in '0'..'9' }

/** 大端写一个有符号 32 位整数（原样保留位模式）。 */
internal fun putS32Be(out: ByteArray, offset: Int, value: Int) {
    out[offset] = (value ushr 24).toByte()
    out[offset + 1] = (value ushr 16).toByte()
    out[offset + 2] = (value ushr 8).toByte()
    out[offset + 3] = value.toByte()
}

internal fun readS32Be(data: ByteArray, offset: Int): Int =
    ((data[offset].toInt() and 0xFF) shl 24) or ((data[offset + 1].toInt() and 0xFF) shl 16) or
        ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)

/**
 * TCP 收包缓冲：先攒字节，再由各拆帧器按自己的格式取完整帧。一次 recv 不等于一帧，
 * 半包、粘包都靠它处理。实现在 core:common（初始 256 字节）。
 */
internal typealias StreamBuffer = io.github.gcbond.openhc.core.common.codec.ReceiveBuffer

internal fun checkRange(data: ByteArray, offset: Int, length: Int) {
    require(offset >= 0 && length >= 0 && offset <= data.size - length) {
        "范围非法: offset=$offset length=$length size=${data.size}"
    }
}
