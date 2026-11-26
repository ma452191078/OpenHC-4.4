package io.github.gcbond.openhc.core.nearby

import io.github.gcbond.openhc.core.common.ByteReader
import io.github.gcbond.openhc.core.common.ByteWriter
import io.github.gcbond.openhc.core.common.MalformedMessageException

/**
 * 「建 socket」请求与应答。资源层（外层类型 4，有线控制通道上用）和消息层（外层类型 2，
 * 蓝牙控制通道上用）的载荷都是 TLV，tag 是命令号，这两条命令在两层里格式相同：
 *
 * ```
 * 命令 3 请求 = EA(通道) EA(业务号) EA(标签字节数) 标签 EA(协议) [EA(uuid 字节数) uuid]
 * 命令 4 应答 = EA(状态) EA(端口) EA(通道) EA(业务号) EA(标签字节数) 标签 EA(协议) [EA(uuid 字节数) uuid]
 * ```
 *
 * 协议是能力位，bit0 = TCP。车机接受时回状态 0、自己新开的监听端口、协议 1；
 * 拒绝时回状态 1、端口 0、协议 0。通道、业务号、标签照抄请求。
 *
 * 蓝牙控制通道上的 RFCOMM 子通道请求（协议 0）：应答的端口位填 RFCOMM 通道号，协议照抄请求，
 * 末尾带 16 字节的服务 UUID；TCP 的应答不带 uuid。
 */
internal object SocketMessages {
    const val OUTER_TYPE_MESSAGE = 2
    const val OUTER_TYPE_RESOURCE = 4

    const val CMD_CREATE_REQUEST = 3
    const val CMD_CREATE_RESPONSE = 4

    const val PROTOCOL_TCP = 1
    const val STATUS_ACCEPTED = 0
    const val STATUS_REJECTED = 1

    class Request(val channel: Int, val businessId: Int, val tag: ByteArray, val protocol: Int, val uuid: ByteArray?)

    class Response(
        val status: Int,
        val port: Int,
        val channel: Int,
        val businessId: Int,
        val tag: ByteArray,
        val protocol: Int,
        val uuid: ByteArray? = null,
    )

    /** 解析命令 3 的值。每个字段都做越界检查；uuid 之后还有多余字节也算不合法。 */
    fun parseRequest(value: ByteArray): Request {
        val r = ByteReader(value)
        val channel = r.ea()
        val businessId = r.ea()
        val tag = r.bytes(lengthField(r, "标签"))
        val protocol = r.ea()
        val uuid = if (r.remaining > 0) r.bytes(lengthField(r, "uuid")) else null
        if (r.remaining > 0) throw MalformedMessageException("建 socket 请求末尾多出 ${r.remaining} 字节")
        return Request(channel, businessId, tag, protocol, uuid)
    }

    fun encodeRequest(req: Request): ByteArray {
        val w = ByteWriter().ea(req.channel).ea(req.businessId).ea(req.tag.size).bytes(req.tag).ea(req.protocol)
        req.uuid?.let { w.ea(it.size).bytes(it) }
        return w.toByteArray()
    }

    /** 组命令 4 应答的完整载荷（含外层 TLV）。[uuid] 只在 RFCOMM 子通道的应答里带。 */
    fun responsePayload(
        status: Int,
        port: Int,
        channel: Int,
        businessId: Int,
        tag: ByteArray,
        protocol: Int,
        uuid: ByteArray? = null,
    ): ByteArray {
        require(port in 0..65535) { "端口越界: $port" }
        val w = ByteWriter(tag.size + 24 + (uuid?.size ?: 0))
            .ea(status).ea(port).ea(channel).ea(businessId).ea(tag.size).bytes(tag).ea(protocol)
        uuid?.let { w.ea(it.size).bytes(it) }
        return encodeTlv(CMD_CREATE_RESPONSE, w.toByteArray())
    }

    fun parseResponse(value: ByteArray): Response {
        val r = ByteReader(value)
        val status = r.ea()
        val port = r.ea()
        val channel = r.ea()
        val businessId = r.ea()
        val tag = r.bytes(lengthField(r, "标签"))
        val protocol = r.ea()
        val uuid = if (r.remaining > 0) r.bytes(lengthField(r, "uuid")) else null
        if (r.remaining > 0) throw MalformedMessageException("建 socket 应答末尾多出 ${r.remaining} 字节")
        return Response(status, port, channel, businessId, tag, protocol, uuid)
    }

    private fun lengthField(r: ByteReader, name: String): Int {
        val len = r.ea()
        if (len > r.remaining) throw MalformedMessageException("$name 声明 $len 字节，只剩 ${r.remaining} 字节")
        return len
    }
}
