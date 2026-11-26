package io.github.gcbond.openhc.core.dmsdp

import io.github.gcbond.openhc.core.common.MalformedMessageException

/**
 * 业务 20 TCP 的外层帧：
 *
 * ```
 * 0x7F ‖ EA(module) ‖ EA(载荷长度) ‖ 载荷 ‖ CRC16（小端）
 * ```
 *
 * CRC 是 CRC-16/ARC，覆盖从 0x7F 到载荷最后一个字节。DMSDP 会话数据的 module 是 1。
 */
internal object SessionFrames {
    const val HEAD = 0x7F
    const val MODULE_SESSION = 1

    /** 本机对单帧载荷的上限（不是协议规定的值）：首轮协商只有 16 字节。 */
    const val MAX_PAYLOAD = 10_240

    /** 首轮协商的载荷长度（实测）。 */
    const val NEGOTIATION_BYTES = 16

    fun encode(module: Int, payload: ByteArray): ByteArray {
        val moduleEa = Ea.encode(module)
        val lengthEa = Ea.encode(payload.size)
        val body = ByteArray(1 + moduleEa.size + lengthEa.size + payload.size + 2)
        body[0] = HEAD.toByte()
        var pos = 1
        System.arraycopy(moduleEa, 0, body, pos, moduleEa.size)
        pos += moduleEa.size
        System.arraycopy(lengthEa, 0, body, pos, lengthEa.size)
        pos += lengthEa.size
        System.arraycopy(payload, 0, body, pos, payload.size)
        pos += payload.size
        val crc = Crc16Arc.compute(body, 0, pos)
        body[pos] = crc.toByte()
        body[pos + 1] = (crc ushr 8).toByte()
        return body
    }

    /**
     * 首轮协商的应答载荷：`结果(1) ‖ 控制端口(BE16) ‖ 13 个 0`，结果 0 表示成功。
     * 端口是另开的控制连接监听端口，不是业务 20 自己的端口。
     */
    fun negotiationReply(controlPort: Int): ByteArray {
        require(controlPort in 1..0xFFFF) { "端口超出范围: $controlPort" }
        val payload = ByteArray(NEGOTIATION_BYTES)
        payload[1] = (controlPort ushr 8).toByte()
        payload[2] = controlPort.toByte()
        return encode(MODULE_SESSION, payload)
    }
}

internal class SessionFrame(val module: Int, val payload: ByteArray)

/** 业务 20 TCP 的流式拆帧，处理半包与粘包。 */
internal class SessionFrameDecoder {
    private val buffer = StreamBuffer()

    val hasPartial: Boolean get() = buffer.size > 0

    fun append(data: ByteArray, offset: Int, length: Int) = buffer.append(data, offset, length)

    /** 取下一个完整帧；字节不够返回 null；格式或校验错误抛 [MalformedMessageException]。 */
    fun next(): SessionFrame? {
        if (buffer.size == 0) return null
        if (buffer.byteAt(0) != SessionFrames.HEAD) throw MalformedMessageException("会话帧首字节不是 0x7F")
        val data = buffer.array
        val module = Ea.decode(data, buffer.start + 1, buffer.end) ?: return null
        val length = Ea.decode(data, module.next, buffer.end) ?: return null
        if (length.value > SessionFrames.MAX_PAYLOAD) {
            throw MalformedMessageException("会话帧载荷 ${length.value} 字节，超过上限 ${SessionFrames.MAX_PAYLOAD}")
        }
        val payloadEnd = length.next + length.value
        if (buffer.end < payloadEnd + 2) return null
        val expected = (data[payloadEnd].toInt() and 0xFF) or ((data[payloadEnd + 1].toInt() and 0xFF) shl 8)
        if (Crc16Arc.compute(data, buffer.start, payloadEnd - buffer.start) != expected) {
            throw MalformedMessageException("会话帧 CRC16 校验失败")
        }
        val frame = SessionFrame(module.value, data.copyOfRange(length.next, payloadEnd))
        buffer.consume(payloadEnd + 2 - buffer.start)
        return frame
    }
}

/**
 * 控制连接的外层帧：`标志(1) ‖ 长度(BE16) ‖ 载荷`。标志 0x00 为明文；0xAB 为加密，
 * 载荷是 `IV(16) ‖ AES-128-CTR 密文`。长度不能为 0。
 */
internal object ControlFrames {
    const val FLAG_PLAIN = 0x00
    const val FLAG_ENCRYPTED = 0xAB
    const val MAX_PAYLOAD = 0xFFFF

    fun encode(flag: Int, payload: ByteArray): ByteArray {
        require(payload.isNotEmpty() && payload.size <= MAX_PAYLOAD) { "控制帧载荷长度非法: ${payload.size}" }
        val out = ByteArray(3 + payload.size)
        out[0] = flag.toByte()
        out[1] = (payload.size ushr 8).toByte()
        out[2] = payload.size.toByte()
        System.arraycopy(payload, 0, out, 3, payload.size)
        return out
    }
}

internal class ControlFrame(val encrypted: Boolean, val payload: ByteArray)

/** 控制连接的流式拆帧。加密状态由会话逐帧判断，这里只拆。 */
internal class ControlFrameDecoder {
    private val buffer = StreamBuffer()

    val hasPartial: Boolean get() = buffer.size > 0

    fun append(data: ByteArray, offset: Int, length: Int) = buffer.append(data, offset, length)

    fun next(): ControlFrame? {
        if (buffer.size < 3) return null
        val flag = buffer.byteAt(0)
        val length = (buffer.byteAt(1) shl 8) or buffer.byteAt(2)
        if (flag != ControlFrames.FLAG_PLAIN && flag != ControlFrames.FLAG_ENCRYPTED) {
            throw MalformedMessageException("控制帧标志 0x${Integer.toHexString(flag)} 不认识")
        }
        if (length == 0) throw MalformedMessageException("控制帧长度为 0")
        if (buffer.size < 3 + length) return null
        val frame = ControlFrame(flag == ControlFrames.FLAG_ENCRYPTED, buffer.copy(3, length))
        buffer.consume(3 + length)
        return frame
    }
}

/**
 * 控制载荷里的设备二进制记录（解密之后）：
 *
 * ```
 * '$'(0x24) ‖ module(BE32，无符号) ‖ type(BE32，有符号) ‖ 数据长度(BE16) ‖ 数据
 * ```
 *
 * 一个载荷里可以连着多条记录。type 是数据类型，设备、显示类消息用 -1（线上 ff ff ff ff），
 * 不是后面数据里的命令号。
 */
internal class DeviceRecord(val module: Long, val type: Int, val data: ByteArray)

internal object DeviceRecords {
    const val MARKER = 0x24
    const val HEADER_BYTES = 11

    /** 发送时数据的上限：加密后整帧 `IV(16) + 记录头(11) + 数据` 不能超过两字节长度。 */
    const val MAX_SEND_DATA = ControlFrames.MAX_PAYLOAD - AesCtr.IV_BYTES - HEADER_BYTES

    fun isRecord(payload: ByteArray): Boolean = payload.isNotEmpty() && payload[0].toInt() == MARKER

    fun parseAll(payload: ByteArray): List<DeviceRecord> {
        val records = ArrayList<DeviceRecord>(1)
        var pos = 0
        while (pos < payload.size) {
            if (payload.size - pos < HEADER_BYTES || payload[pos].toInt() != MARKER) {
                throw MalformedMessageException("设备记录头不完整或标记不是 '$'")
            }
            val module = readS32Be(payload, pos + 1).toLong() and 0xFFFF_FFFFL
            val type = readS32Be(payload, pos + 5)
            val length = ((payload[pos + 9].toInt() and 0xFF) shl 8) or (payload[pos + 10].toInt() and 0xFF)
            val dataStart = pos + HEADER_BYTES
            if (dataStart + length > payload.size) throw MalformedMessageException("设备记录数据被截断")
            records += DeviceRecord(module, type, payload.copyOfRange(dataStart, dataStart + length))
            pos = dataStart + length
        }
        return records
    }

    fun encode(module: Long, type: Int, data: ByteArray): ByteArray {
        require(module in 0..0xFFFF_FFFFL) { "module 超出范围: $module" }
        require(data.isNotEmpty() && data.size <= MAX_SEND_DATA) { "设备记录数据长度非法: ${data.size}" }
        val out = ByteArray(HEADER_BYTES + data.size)
        out[0] = MARKER.toByte()
        putS32Be(out, 1, module.toInt())
        putS32Be(out, 5, type)
        out[9] = (data.size ushr 8).toByte()
        out[10] = data.size.toByte()
        System.arraycopy(data, 0, out, HEADER_BYTES, data.size)
        return out
    }
}
