package io.github.gcbond.openhc.core.nearby

import io.github.gcbond.openhc.core.common.ByteReader
import io.github.gcbond.openhc.core.common.ByteWriter
import io.github.gcbond.openhc.core.common.MalformedMessageException

/**
 * 控制帧里的 TLV：`EA(tag) EA(长度) 值`，长度为 0 时没有值（例如 `80 80`）。
 * 通道管理、资源层、消息层的载荷，以及帧的标志段，都用这一种 TLV。
 */
internal class Tlv(val tag: Int, value: ByteArray) {
    private val bytes = value.copyOf()

    val value: ByteArray get() = bytes.copyOf()
    val length: Int get() = bytes.size

    /** 不复制，只给本模块内部读。 */
    internal fun valueView(): ByteArray = bytes

    override fun toString(): String = "Tlv(tag=$tag, ${bytes.size} 字节)"

    companion object {
        /** 把整段字节拆成 TLV 列表，保持原顺序、允许重复 tag。结构不完整抛 [MalformedMessageException]。 */
        fun parseAll(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): List<Tlv> {
            if (offset < 0 || length < 0 || offset + length > data.size) {
                throw MalformedMessageException("TLV 范围越界: offset=$offset length=$length size=${data.size}")
            }
            val reader = ByteReader(data, offset, offset + length)
            val out = ArrayList<Tlv>()
            while (reader.remaining > 0) {
                val tag = reader.ea()
                val len = reader.ea()
                if (len > reader.remaining) {
                    throw MalformedMessageException("TLV $tag 声明长度 $len，只剩 ${reader.remaining} 字节")
                }
                out.add(Tlv(tag, reader.bytes(len)))
            }
            return out
        }
    }
}

internal fun ByteWriter.tlv(tag: Int, value: ByteArray = EMPTY_BYTES): ByteWriter =
    ea(tag).ea(value.size).bytes(value)

internal fun encodeTlv(tag: Int, value: ByteArray = EMPTY_BYTES): ByteArray =
    ByteWriter(value.size + 2 * Ea.MAX_BYTES).tlv(tag, value).toByteArray()

internal val EMPTY_BYTES = ByteArray(0)
