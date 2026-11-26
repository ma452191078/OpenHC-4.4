package io.github.gcbond.openhc.core.nearby

import io.github.gcbond.openhc.core.common.ByteWriter
import io.github.gcbond.openhc.core.common.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 车机一端在控制通道上的完整收发流程。 */
class NearbySessionTest {

    /** 4 个随机字节 00 00 5B A0 = 23456，对应 connectId 100000 + 23456 = 123456。 */
    private val connectIdBytes = hex("00005ba0")
    private val cipher = NearbyFrameCipher(Vectors.KEYS_16)

    private fun session(random: FixedSecureRandom, bt: String? = Vectors.BT_1) =
        NearbySession(Vectors.HOST_1, Vectors.KEYS_16, bt, random)

    private fun sends(actions: List<NearbyAction>) =
        actions.filterIsInstance<NearbyAction.Send>().map { it.frame.toHex() }

    private fun kinds(actions: List<NearbyAction>) = actions.map { it::class.simpleName }

    private fun phonePlain(outerType: Int, payload: ByteArray) = NearbyFrame.plain(outerType, payload).encode()

    private fun phoneEncrypted(outerType: Int, payload: ByteArray, seed: ByteArray) =
        cipher.seal(outerType, payload, seed).encode()

    /** 拆开车机发出的一帧，返回（帧，明文载荷）。 */
    private fun open(frame: ByteArray): Pair<NearbyFrame, ByteArray> {
        val decoder = NearbyFrameDecoder()
        decoder.feed(frame)
        val f = assertNotNull(decoder.next())
        assertNull(decoder.next())
        return f to cipher.open(f)
    }

    private fun socketRequest(businessId: Int, tag: String, protocol: Int) =
        encodeTlv(
            3,
            SocketMessages.encodeRequest(SocketMessages.Request(6, businessId, tag.toByteArray(), protocol, null)),
        )

    /** 跑完一次加密握手，消耗随机序列里的 connectId、种子 A、种子 B。 */
    private fun handshaken(random: FixedSecureRandom): NearbySession {
        val s = session(random)
        s.start(0)
        assertIs<NearbyEvent.HandshakeCompleted>(s.feed(hex(Vectors.PHONE_FIRST)).last())
        return s
    }

    @Test
    fun wiredSessionMatchesReference() {
        val random = FixedSecureRandom(connectIdBytes, Vectors.SEED_A, Vectors.SEED_B, Vectors.SEED_C, Vectors.SEED_F)
        val s = session(random)
        assertEquals(Vectors.DEVICE_ID_1, s.deviceId)
        assertNull(s.nextTickAtMillis)

        // 连接建立：先发明文 connectId，开始 5 秒握手计时。
        val opened = s.start(1_000)
        assertEquals(listOf(Vectors.CONNECT_ID_123456), sends(opened))
        assertEquals(6_000L, s.nextTickAtMillis)

        // 手机首包（版本 5、摘要、加密请求 2、16、18）：先加密发 TLV7，再加密回 TLV2/4/6，握手完成。
        val first = s.feed(hex(Vectors.PHONE_FIRST))
        assertEquals(listOf("Unhandled", "Unhandled", "Send", "Send", "HandshakeCompleted"), kinds(first))
        assertEquals(listOf(Vectors.CAR_TLV7_SEED_A, Vectors.CAR_REPLY_SEED_B), sends(first))
        val unknown = first.filterIsInstance<NearbyEvent.Unhandled>()
        assertEquals(listOf(0, 0), unknown.map { it.outerType })
        assertEquals(listOf(16, 18), unknown.map { it.tag })
        assertEquals(listOf(91, 39), unknown.map { it.value.size })
        val done = first.last() as NearbyEvent.HandshakeCompleted
        assertEquals(5L, done.peerVersion)
        assertEquals("0123456789AB", done.peerSummary)
        assertTrue(done.encrypted)
        assertNull(s.nextTickAtMillis)

        // 手机回明文 TLV8（它的蓝牙地址）。
        val tlv8 = s.feed(hex(Vectors.PHONE_TLV8))
        assertEquals(Vectors.PHONE_BT, assertIs<NearbyEvent.PeerBluetoothAddress>(tlv8.single()).address)

        // 手机请求认证 socket（加密的资源层请求）；调用方开好监听后接受。
        val authRequest = assertIs<NearbyEvent.SocketRequested>(s.feed(hex(Vectors.PHONE_AUTH_REQ_SEED_D)).single())
        assertEquals(NearbySession.CHANNEL_USB, authRequest.channel)
        assertEquals(NearbySession.BUSINESS_AUTH, authRequest.businessId)
        assertEquals(NearbySession.TAG_AUTH, authRequest.tag)
        assertEquals(1, authRequest.protocol)
        assertNull(authRequest.uuid)
        val accepted = s.acceptSocket(authRequest, 40001)
        assertEquals(listOf(Vectors.CAR_AUTH_RESP_SEED_C), sends(accepted))

        // DMSDP 请求先留着，认证完成后再接受（应答可以晚回）。
        val dmsdpRequest = assertIs<NearbyEvent.SocketRequested>(s.feed(hex(Vectors.PHONE_DMSDP_REQ_SEED_E)).single())
        assertEquals(NearbySession.BUSINESS_DMSDP, dmsdpRequest.businessId)
        assertEquals(NearbySession.TAG_DMSDP, dmsdpRequest.tag)
        assertEquals(listOf(Vectors.CAR_DMSDP_RESP_SEED_F), sends(s.acceptSocket(dmsdpRequest, 40002)))
        assertEquals(0, random.remaining)
    }

    @Test
    fun byteByByteFeedingGivesSameOutput() {
        val s = session(FixedSecureRandom(connectIdBytes, Vectors.SEED_A, Vectors.SEED_B))
        s.start(0)
        val out = ArrayList<NearbyAction>()
        val bytes = hex(Vectors.PHONE_FIRST + Vectors.PHONE_TLV8)
        for (i in bytes.indices) out.addAll(s.feed(bytes, i, 1))
        assertEquals(listOf(Vectors.CAR_TLV7_SEED_A, Vectors.CAR_REPLY_SEED_B), sends(out))
        assertEquals(
            listOf("Unhandled", "Unhandled", "Send", "Send", "HandshakeCompleted", "PeerBluetoothAddress"),
            kinds(out),
        )
    }

    @Test
    fun plainSessionWhenPhoneDeclinesEncryption() {
        val random = FixedSecureRandom(connectIdBytes)
        val s = session(random)
        s.start(0)
        val payload = ByteWriter()
            .tlv(1, byteArrayOf(5)).tlv(3, "0123456789AB\u0000".toByteArray()).tlv(5, byteArrayOf(0))
            .toByteArray()
        val out = s.feed(phonePlain(0, payload))
        // 不加密：不发 TLV7，应答明文且 TLV6 = 00。
        assertEquals(listOf(Vectors.CAR_REPLY_PLAIN), sends(out))
        assertFalse(assertIs<NearbyEvent.HandshakeCompleted>(out.last()).encrypted)

        val requestFrame = phonePlain(4, hex(Vectors.PAYLOAD_AUTH_REQ))
        val request = assertIs<NearbyEvent.SocketRequested>(s.feed(requestFrame).single())
        val accepted = s.acceptSocket(request, 40001)
        assertEquals(listOf(phonePlain(4, hex(Vectors.PAYLOAD_AUTH_RESP)).toHex()), sends(accepted))
        assertEquals(0, random.remaining)
    }

    @Test
    fun rejectMatchesReference() {
        val random = FixedSecureRandom(connectIdBytes, Vectors.SEED_A, Vectors.SEED_B, Vectors.SEED_G)
        val s = handshaken(random)
        val request = assertIs<NearbyEvent.SocketRequested>(
            s.feed(phoneEncrypted(4, socketRequest(21, "other", 1), Vectors.SEED_D)).single(),
        )
        assertEquals(21, request.businessId)
        assertEquals(listOf(Vectors.CAR_REJECT_SEED_G), sends(s.rejectSocket(request)))
        assertEquals(Vectors.PAYLOAD_REJECT, open(hex(Vectors.CAR_REJECT_SEED_G)).second.toHex())
    }

    @Test
    fun requestBeforeHandshakeIsRejectedAutomatically() {
        val s = session(FixedSecureRandom(connectIdBytes))
        s.start(0)
        val out = s.feed(phonePlain(4, hex(Vectors.PAYLOAD_AUTH_REQ)))
        assertEquals(listOf("Send", "SocketRequestRejected"), kinds(out))
        val (frame, payload) = open((out[0] as NearbyAction.Send).frame)
        assertEquals(4, frame.outerType)
        assertFalse(frame.isEncrypted)
        val resp = SocketMessages.parseResponse(Tlv.parseAll(payload).single().value)
        assertEquals(
            listOf(1, 0, 6, 19, 0),
            listOf(resp.status, resp.port, resp.channel, resp.businessId, resp.protocol),
        )
        assertEquals("hicarAuth", String(resp.tag))
        val rejected = out[1] as NearbyEvent.SocketRequestRejected
        assertEquals(NearbySession.BUSINESS_AUTH, rejected.businessId)
        assertEquals("hicarAuth", rejected.tag)
    }

    @Test
    fun requestWithoutTcpIsRejectedAutomatically() {
        val s = handshaken(FixedSecureRandom(connectIdBytes, Vectors.SEED_A, Vectors.SEED_B, Vectors.SEED_C))
        val out = s.feed(phoneEncrypted(4, socketRequest(19, "hicarAuth", 2), Vectors.SEED_D))
        assertEquals(listOf("Send", "SocketRequestRejected"), kinds(out))
        val (frame, payload) = open((out[0] as NearbyAction.Send).frame)
        assertTrue(frame.isEncrypted)
        assertContentEquals(Vectors.SEED_C, frame.seed)
        val resp = SocketMessages.parseResponse(Tlv.parseAll(payload).single().value)
        assertEquals(listOf(1, 0, 0), listOf(resp.status, resp.port, resp.protocol))
    }

    @Test
    fun phoneAskingForCarAddressGetsTlv8AndNoTlv7() {
        val s = session(FixedSecureRandom(connectIdBytes, Vectors.SEED_B))
        s.start(0)
        val payload = ByteWriter()
            .tlv(1, byteArrayOf(5)).tlv(3, "0123456789AB\u0000".toByteArray()).tlv(5, byteArrayOf(2))
            .tlv(7, Vectors.PHONE_BT.toByteArray()).toByteArray()
        val out = s.feed(phonePlain(0, payload))
        // 对端地址已知，不再发 TLV7；应答按请求顺序 2、4、6、8 拼成一帧。
        assertEquals(listOf("PeerBluetoothAddress", "Send", "HandshakeCompleted"), kinds(out))
        val (frame, reply) = open((out[1] as NearbyAction.Send).frame)
        assertTrue(frame.isEncrypted)
        val items = Tlv.parseAll(reply)
        assertEquals(listOf(2, 4, 6, 8), items.map { it.tag })
        assertEquals("02", items[0].value.toHex())
        assertEquals(Vectors.DEVICE_ID_1, String(items[1].value))
        assertEquals("01", items[2].value.toHex())
        assertEquals(Vectors.BT_1, String(items[3].value))
    }

    @Test
    fun unavailableAddressUsesFallback() {
        val s = session(FixedSecureRandom(connectIdBytes, Vectors.SEED_A, Vectors.SEED_B), bt = null)
        assertEquals(Vectors.DEVICE_ID_1_UNAVAILABLE, s.deviceId)
        s.start(0)
        val out = sends(s.feed(hex(Vectors.PHONE_FIRST)))
        assertEquals("02:00:00:00:00:00", String(Tlv.parseAll(open(hex(out[0])).second).single().value))
        val reply = Tlv.parseAll(open(hex(out[1])).second)
        assertEquals(Vectors.DEVICE_ID_1_UNAVAILABLE, String(reply[1].value))
    }

    @Test
    fun handshakeAcrossSeveralFrames() {
        val s = session(FixedSecureRandom(connectIdBytes, Vectors.SEED_A, Vectors.SEED_B))
        s.start(0)
        val a = s.feed(phonePlain(0, encodeTlv(1, byteArrayOf(5))))
        assertEquals(1, sends(a).size)   // 只回 TLV2，明文
        assertFalse(open(hex(sends(a)[0])).first.isEncrypted)
        val b = s.feed(phonePlain(0, encodeTlv(3, "0123456789AB\u0000".toByteArray())))
        assertEquals(1, sends(b).size)   // 只回 TLV4，明文
        val c = s.feed(phonePlain(0, encodeTlv(5, byteArrayOf(1))))
        // 加密一开：先发加密的 TLV7，再发加密的 TLV6，然后握手完成。
        assertEquals(listOf("Send", "Send", "HandshakeCompleted"), kinds(c))
        val (f7, p7) = open(hex(sends(c)[0]))
        assertTrue(f7.isEncrypted)
        assertEquals(listOf(7), Tlv.parseAll(p7).map { it.tag })
        val (f6, p6) = open(hex(sends(c)[1]))
        assertTrue(f6.isEncrypted)
        assertEquals("8681" + "01", p6.toHex())
    }

    @Test
    fun handshakeTimeout() {
        val s = session(FixedSecureRandom(connectIdBytes))
        s.start(1_000)
        assertEquals(emptyList(), s.onTick(5_999))
        assertEquals(listOf<NearbyAction>(NearbyEvent.HandshakeTimedOut), s.onTick(6_000))
        assertNull(s.nextTickAtMillis)
        assertEquals(emptyList(), s.onTick(7_000))
        // 作废后喂数据一律忽略，也不能再回应答。
        assertEquals(emptyList(), s.feed(hex(Vectors.PHONE_FIRST)))
        val request = NearbyEvent.SocketRequested(4, 6, 19, "hicarAuth".toByteArray(), 1, null)
        assertFailsWith<IllegalStateException> { s.acceptSocket(request, 40001) }
        assertFailsWith<IllegalStateException> { s.rejectSocket(request) }
    }

    @Test
    fun noTimeoutAfterHandshake() {
        val s = handshaken(FixedSecureRandom(connectIdBytes, Vectors.SEED_A, Vectors.SEED_B))
        assertNull(s.nextTickAtMillis)
        assertEquals(emptyList(), s.onTick(Long.MAX_VALUE))
    }

    @Test
    fun garbageIsDiscardedAndConnectionContinues() {
        val s = session(FixedSecureRandom(connectIdBytes, Vectors.SEED_A, Vectors.SEED_B))
        s.start(0)
        val bad = s.feed(hex("000102"))
        val discarded = assertIs<NearbyEvent.InputDiscarded>(bad.single())
        assertEquals(3, discarded.byteCount)
        // 之后的正常数据照常处理。
        assertIs<NearbyEvent.HandshakeCompleted>(s.feed(hex(Vectors.PHONE_FIRST)).last())
        // 好帧后面粘着坏数据：好帧先处理，坏数据连同缓冲一起丢掉。
        val mixed = s.feed(hex(Vectors.PHONE_TLV8 + "ff00"))
        assertEquals(listOf("PeerBluetoothAddress", "InputDiscarded"), kinds(mixed))
        assertEquals(2, (mixed[1] as NearbyEvent.InputDiscarded).byteCount)
        // 只是半帧时不算错，等后续数据。
        assertEquals(emptyList(), s.feed(hex("7f8080800000")))
    }

    @Test
    fun malformedPayloadDropsOnlyThatFrame() {
        val s = session(FixedSecureRandom(connectIdBytes, Vectors.SEED_A, Vectors.SEED_B))
        s.start(0)
        // 加密开关没有值：整帧作废，前面的版本请求也不处理（不回应答、不记状态）。
        val bad = ByteWriter().tlv(1, byteArrayOf(5)).tlv(5).toByteArray()
        val out = s.feed(phonePlain(0, bad) + hex(Vectors.PHONE_FIRST))
        assertEquals(
            listOf("InputDiscarded", "Unhandled", "Unhandled", "Send", "Send", "HandshakeCompleted"),
            kinds(out),
        )
        assertEquals(listOf(Vectors.CAR_TLV7_SEED_A, Vectors.CAR_REPLY_SEED_B), sends(out))
    }

    @Test
    fun malformedSocketLayerFrameHasNoPartialEffect() {
        val s = handshaken(FixedSecureRandom(connectIdBytes, Vectors.SEED_A, Vectors.SEED_B))
        val good = socketRequest(19, "hicarAuth", 1)
        val truncated = encodeTlv(3, ByteWriter().ea(6).ea(19).ea(9).bytes("hicar".toByteArray()).toByteArray())
        val out = s.feed(phoneEncrypted(4, good + truncated, Vectors.SEED_D))
        assertEquals(listOf("InputDiscarded"), kinds(out))
    }

    @Test
    fun unknownCommandsAndLayersAreExposed() {
        val s = handshaken(FixedSecureRandom(connectIdBytes, Vectors.SEED_A, Vectors.SEED_B, Vectors.SEED_C))
        // 资源层：数据通道协商（命令 1）不处理，建 socket 请求照常。
        val negotiation = encodeTlv(1, byteArrayOf(9, 8))
        val mixed = s.feed(phoneEncrypted(4, negotiation + socketRequest(19, "hicarAuth", 1), Vectors.SEED_D))
        assertEquals(listOf("Unhandled", "SocketRequested"), kinds(mixed))
        val cmd1 = mixed[0] as NearbyEvent.Unhandled
        assertEquals(listOf(4, 1), listOf(cmd1.outerType, cmd1.tag))
        assertContentEquals(byteArrayOf(9, 8), cmd1.value)
        // 外层类型 1：能拆成 TLV 就逐条交出，拆不开就整段交出。
        val type1 = s.feed(phonePlain(1, encodeTlv(2, byteArrayOf(1))))
        assertEquals(2, assertIs<NearbyEvent.Unhandled>(type1.single()).tag)
        val raw = assertIs<NearbyEvent.Unhandled>(s.feed(phonePlain(1, byteArrayOf(0x81.toByte()))).single())
        assertNull(raw.tag)
        assertContentEquals(byteArrayOf(0x81.toByte()), raw.value)
        // 消息层（外层类型 2）的建 socket 请求同样处理，应答回到消息层。
        val viaMessage = assertIs<NearbyEvent.SocketRequested>(
            s.feed(phonePlain(2, socketRequest(19, "hicarAuth", 1))).single(),
        )
        val (frame, _) = open((s.acceptSocket(viaMessage, 40001).single() as NearbyAction.Send).frame)
        assertEquals(2, frame.outerType)
    }

    @Test
    fun uuidIsExposed() {
        val s = handshaken(FixedSecureRandom(connectIdBytes, Vectors.SEED_A, Vectors.SEED_B))
        val req = SocketMessages.Request(6, 19, "hicarAuth".toByteArray(), 1, byteArrayOf(1, 2, 3))
        val out = s.feed(phonePlain(4, encodeTlv(3, SocketMessages.encodeRequest(req))))
        assertContentEquals(byteArrayOf(1, 2, 3), assertIs<NearbyEvent.SocketRequested>(out.single()).uuid)
    }

    @Test
    fun misuseIsRejected() {
        val s = session(FixedSecureRandom(connectIdBytes))
        assertFailsWith<IllegalStateException> { s.feed(hex(Vectors.PHONE_FIRST)) }
        s.start(0)
        assertFailsWith<IllegalStateException> { s.start(0) }
        val request = NearbyEvent.SocketRequested(4, 6, 19, "hicarAuth".toByteArray(), 1, null)
        assertFailsWith<IllegalArgumentException> { s.acceptSocket(request, 0) }
        assertFailsWith<IllegalArgumentException> { s.acceptSocket(request, 65536) }
    }

    @Test
    fun eventsDoNotPrintIdentifiers() {
        val s = session(FixedSecureRandom(connectIdBytes, Vectors.SEED_A, Vectors.SEED_B))
        s.start(0)
        val all = s.feed(hex(Vectors.PHONE_FIRST)) + s.feed(hex(Vectors.PHONE_TLV8))
        val text = all.joinToString(" ") + " " + s
        assertFalse(text.contains("0123456789AB"), text)
        assertFalse(text.contains("77:88"), text)
        assertFalse(text.contains(Vectors.DEVICE_ID_1.take(8)), text)
    }
}
