package io.github.gcbond.openhc.core.hisight.control

import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.common.codec.ReceiveBuffer

/**
 * 控制连接的分帧：`BE32 载荷长度 || 载荷`。长度只算载荷，取值 1..65000；
 * 没有明密文标志字节，是否解密由会话按当时的协商状态逐帧决定。
 */
internal object ControlFraming {
    const val MAX_PAYLOAD = 65000

    fun encode(payload: ByteArray): ByteArray {
        require(payload.isNotEmpty() && payload.size <= MAX_PAYLOAD) { "控制帧载荷必须是 1..65000 字节" }
        val out = ByteArray(4 + payload.size)
        val n = payload.size
        out[0] = (n ushr 24).toByte()
        out[1] = (n ushr 16).toByte()
        out[2] = (n ushr 8).toByte()
        out[3] = n.toByte()
        System.arraycopy(payload, 0, out, 4, n)
        return out
    }
}

/**
 * 增量拆控制帧，处理 TCP 的半包和粘包。一次只交出一帧，调用方处理完（可能切换了加解密状态）
 * 再取下一帧。长度头一凑齐就校验，非法立即报错，不等超长正文。
 */
internal class ControlFrameDecoder {
    private val buffer = ReceiveBuffer(4096)

    /** 缓冲里有未凑齐的字节（半个长度头或半个载荷）。 */
    val hasPartial: Boolean get() = buffer.size > 0

    fun append(data: ByteArray, offset: Int, length: Int) = buffer.append(data, offset, length)

    /** 取下一帧载荷；不够一帧时返回 null。 */
    fun next(): ByteArray? {
        if (buffer.size < 4) return null
        val length = buffer.u32Be(0)
        if (length < 1 || length > ControlFraming.MAX_PAYLOAD) {
            throw MalformedMessageException("控制帧长度 $length 不在 1..65000")
        }
        val n = length.toInt()
        if (buffer.size < 4 + n) return null
        val payload = buffer.copy(4, n)
        buffer.consume(4 + n)
        return payload
    }
}
