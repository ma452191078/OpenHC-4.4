package io.github.gcbond.openhc.core.nearby

import io.github.gcbond.openhc.core.common.ByteWriter
import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.common.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 通道管理条目与建 socket 请求 / 应答的编解码。 */
class MessagesTest {

    @Test
    fun channelManagementItems() {
        val payload = ByteWriter()
            .tlv(1, byteArrayOf(5))
            .tlv(2, byteArrayOf(0x05, 0x01))
            .tlv(3, "0123456789AB\u0000".toByteArray())
            .tlv(5, byteArrayOf(2))
            .tlv(6, byteArrayOf(0))
            .tlv(8, "02:66:77:88:99:AA".toByteArray())
            .tlv(15, "123456".toByteArray())
            .tlv(16, byteArrayOf(1))
            .toByteArray()
        val items = ChannelManagement.decode(payload)
        assertEquals(8, items.size)
        assertEquals(5L, assertIs<ChannelManagement.Item.Version>(items[0]).version)
        assertTrue((items[0] as ChannelManagement.Item.Version).request)
        // 版本是小端无符号整数。
        assertEquals(0x0105L, assertIs<ChannelManagement.Item.Version>(items[1]).version)
        assertEquals("0123456789AB", assertIs<ChannelManagement.Item.Summary>(items[2]).text)
        assertTrue(assertIs<ChannelManagement.Item.Encryption>(items[3]).enabled)
        assertFalse(assertIs<ChannelManagement.Item.Encryption>(items[4]).enabled)
        assertFalse((items[4] as ChannelManagement.Item.Encryption).request)
        assertEquals("02:66:77:88:99:AA", assertIs<ChannelManagement.Item.BluetoothAddress>(items[5]).text)
        assertIs<ChannelManagement.Item.ConnectId>(items[6])
        assertEquals(16, assertIs<ChannelManagement.Item.Unknown>(items[7]).tlv.tag)
    }

    @Test
    fun channelManagementRejectsBadValues() {
        assertFailsWith<MalformedMessageException> { ChannelManagement.decode(encodeTlv(1)) }
        assertFailsWith<MalformedMessageException> { ChannelManagement.decode(encodeTlv(2, ByteArray(5))) }
        assertFailsWith<MalformedMessageException> { ChannelManagement.decode(encodeTlv(5)) }
        assertFailsWith<MalformedMessageException> { ChannelManagement.decode(encodeTlv(6)) }
        assertFailsWith<MalformedMessageException> { ChannelManagement.decode(hex("8185")) }
    }

    @Test
    fun connectIdRange() {
        assertEquals("100000", String(ChannelManagement.connectIdValue(100_000)))
        assertEquals("999999", String(ChannelManagement.connectIdValue(999_999)))
        assertFailsWith<IllegalArgumentException> { ChannelManagement.connectIdValue(99_999) }
        assertFailsWith<IllegalArgumentException> { ChannelManagement.connectIdValue(1_000_000) }
    }

    @Test
    fun socketRequestMatchesReference() {
        val item = Tlv.parseAll(hex(Vectors.PAYLOAD_AUTH_REQ)).single()
        assertEquals(SocketMessages.CMD_CREATE_REQUEST, item.tag)
        val req = SocketMessages.parseRequest(item.value)
        assertEquals(listOf(6, 19, 1), listOf(req.channel, req.businessId, req.protocol))
        assertEquals("hicarAuth", String(req.tag))
        assertNull(req.uuid)
        assertContentEquals(item.value, SocketMessages.encodeRequest(req))
    }

    @Test
    fun socketResponseMatchesReference() {
        val accept = SocketMessages.responsePayload(0, 40001, 6, 19, "hicarAuth".toByteArray(), 1)
        assertEquals(Vectors.PAYLOAD_AUTH_RESP, accept.toHex())
        val reject = SocketMessages.responsePayload(1, 0, 6, 21, "other".toByteArray(), 0)
        assertEquals(Vectors.PAYLOAD_REJECT, reject.toHex())
        val parsed = SocketMessages.parseResponse(Tlv.parseAll(accept).single().value)
        assertEquals(
            listOf(0, 40001, 6, 19, 1),
            listOf(parsed.status, parsed.port, parsed.channel, parsed.businessId, parsed.protocol),
        )
    }

    @Test
    fun socketRequestBoundsChecks() {
        val withUuid = SocketMessages.Request(6, 19, "hicarAuth".toByteArray(), 1, byteArrayOf(7, 7))
        val valid = SocketMessages.encodeRequest(withUuid)
        assertContentEquals(byteArrayOf(7, 7), SocketMessages.parseRequest(valid).uuid)
        val cases = listOf(
            valid.copyOf(valid.size - 1),               // uuid 截断
            valid + byteArrayOf(0x81.toByte()),          // 末尾多出字节
            ByteWriter().ea(6).ea(19).ea(20).bytes("hicarAuth".toByteArray()).toByteArray(), // 标签长度越界
            ByteWriter().ea(6).ea(19).toByteArray(),     // 缺字段
            ByteArray(0),
        )
        for ((i, bytes) in cases.withIndex()) {
            assertFailsWith<MalformedMessageException>("第 $i 种") { SocketMessages.parseRequest(bytes) }
        }
    }

    @Test
    fun responseEchoesRawTagBytes() {
        // 标签不是合法 UTF-8 时也原样回显，不经过字符串转换。
        val oddTag = byteArrayOf(0x68, 0xFF.toByte(), 0x69)
        val payload = SocketMessages.responsePayload(0, 1, 6, 19, oddTag, 1)
        val resp = SocketMessages.parseResponse(Tlv.parseAll(payload).single().value)
        assertContentEquals(oddTag, resp.tag)
    }
}
