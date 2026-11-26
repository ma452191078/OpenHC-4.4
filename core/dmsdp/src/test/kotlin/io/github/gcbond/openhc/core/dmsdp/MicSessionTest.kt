package io.github.gcbond.openhc.core.dmsdp

import io.github.gcbond.openhc.core.dmsdp.audio.AudioStreamListener
import io.github.gcbond.openhc.core.dmsdp.audio.MicCodec
import io.github.gcbond.openhc.core.dmsdp.audio.MicEndReason
import io.github.gcbond.openhc.core.dmsdp.audio.MicEvent
import io.github.gcbond.openhc.core.dmsdp.audio.MicProfile
import io.github.gcbond.openhc.core.dmsdp.audio.MicStreamListener
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 麦克风服务：声明、触发后的 200 与能力协商、手机 SETUP → 开端口 → 200、连上后送声音、设参、拆除、替换、
 * 连接被关、格式不支持，以及各条消息逐字节核对。
 */
class MicSessionTest {

    private val phone = Phone()
    private val now = 100_000L

    private class MicRecorder : MicStreamListener {
        val events = ArrayList<MicEvent>()
        var throwOnEvent = false

        override fun onMicEvent(event: MicEvent) {
            events += event
            if (throwOnEvent) throw IllegalStateException("测试抛出")
        }

        inline fun <reified T : MicEvent> only(): T = events.filterIsInstance<T>().single()
        fun notes() = events.filterIsInstance<MicEvent.Note>().map { it.message }
    }

    private fun config(mic: DmsdpConfig.Mic? = DmsdpConfig.Mic()) = DmsdpConfig(
        host = Synthetic.host(),
        deviceId = Synthetic.SELF_ID,
        bluetoothMac = Synthetic.MAC,
        localIp = Synthetic.LOCAL_IP,
        phoneIp = Synthetic.PHONE_IP,
        display = Synthetic.DISPLAY,
        mic = mic,
    )

    /** 走完业务 20 协商、OPTIONS（CSeq 1）和加密选择。 */
    private fun session(recorder: MicRecorder = MicRecorder(), mic: DmsdpConfig.Mic? = DmsdpConfig.Mic()): DmsdpSession {
        val session = DmsdpSession(config(mic), Synthetic.KEY, SequenceRandom(), AudioStreamListener.NONE, recorder)
        session.feed(DmsdpChannel.SESSION, hex(CrossVectors.SESSION_NEG_REQUEST))
        session.controlListenerReady(40001)
        session.onConnected(DmsdpChannel.CONTROL, now)
        session.feed(DmsdpChannel.CONTROL, phone.plainFrame(CRYPTO_SELECT + "\r\n"), now)
        check(session.isEncrypted)
        return session
    }

    private fun trigger(
        method: String,
        sessionId: String = "-42",
        cseq: String = "7",
        para: String = "SampleRate=16000;Format=16;ChannelNum=1;Codec=1",
        simple: String = "1",
    ): ByteArray {
        val body = "msdp_trigger_method: $method\r\n"
        return phone.encryptedText(
            "SET_PARAMETER * DMSDP/1.0\r\nCSeq: $cseq\r\nServiceID: mic1\r\nServiceType: 2\r\nDataSessionID: $sessionId\r\n" +
                "Simple: $simple\r\nParaAudio: $para\r\nContent-Length: ${body.length}\r\n\r\n$body",
        )
    }

    private fun setup(sessionId: String = "-42", cseq: String = "8", transport: String = "RTP/AVP/UDP;unicast;client_port=50000--1") =
        phone.encryptedText(
            "SETUP * DMSDP/1.0\r\nCSeq: $cseq\r\nServiceID: mic1\r\nServiceType: 2\r\nDataSessionID: $sessionId\r\n" +
                "Simple: 1\r\nTransport: $transport\r\n\r\n",
        )

    private fun texts(outputs: List<DmsdpOutput>): List<String> =
        outputs.sends(DmsdpChannel.CONTROL).map { String(phone.openControlFrame(it), Charsets.US_ASCII) }

    /** 走到手机连上麦克风数据端口（41000）。 */
    private fun streaming(recorder: MicRecorder, para: String = "SampleRate=16000;Format=16;ChannelNum=1;Codec=1"): DmsdpSession {
        val session = session(recorder)
        session.feed(DmsdpChannel.CONTROL, trigger("OPTIONS", para = para), now)
        session.feed(DmsdpChannel.CONTROL, setup(), now)
        session.micListenerReady(41000)
        session.onConnected(DmsdpChannel.MIC, now + 10)
        return session
    }

    /** 用 JCE 解开一个麦克风帧，返回 RTP 包。 */
    private fun openMic(frame: ByteArray): ByteArray {
        assertEquals(0xAB, frame[0].toInt() and 0xFF)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(Synthetic.KEY, "AES"), IvParameterSpec(frame.copyOfRange(3, 19)))
        return cipher.doFinal(frame, 19, frame.size - 19)
    }

    private fun timestamp(rtp: ByteArray): Long =
        ((rtp[4].toLong() and 0xFF) shl 24) or ((rtp[5].toLong() and 0xFF) shl 16) or
            ((rtp[6].toLong() and 0xFF) shl 8) or (rtp[7].toLong() and 0xFF)

    // ---------------- 声明 ----------------

    @Test
    fun micIsDeclaredWithItsCapabilityWhenTheMaskAsksForIt() {
        val session = session()
        session.feed(DmsdpChannel.CONTROL, phone.encryptedRecord(3, -1, hex(CrossVectors.PHONE_101)), 0)
        val reply = session.feed(DmsdpChannel.CONTROL, phone.encryptedRecord(3, -1, hex("e8") + hex("0000031f")), 0)
        assertEquals(listOf("display1", "mic1"), (reply.events().single() as DmsdpEvent.ServicesRequested).answeredServiceIds)
        val record = phone.decrypt(reply.sends(DmsdpChannel.CONTROL)[1].let { it.copyOfRange(3, it.size) })
        assertEquals(
            "{\"deviceId\":\"${Synthetic.SELF_ID}\",\"deviceName\":\"测试车机 Ünï\",\"serviceType\":2,\"serviceId\":\"mic1\"," +
                "\"properties\":{\"2005\":\"\",\"2004\":\"\",\"2003\":\"\",\"2102\":\"{\\\"codecs\\\":[1],\\\"currentVolume\\\":" +
                "{\\\"a\\\":false},\\\"extendInfo\\\":{},\\\"profiles\\\":[{\\\"sampleRate\\\":16000,\\\"channel\\\":16," +
                "\\\"format\\\":1}],\\\"soundEffect\\\":0}\"},\"status\":{}}",
            String(record, 12, record.size - 12, Charsets.UTF_8),
        )
        val withoutMicBit = session.feed(DmsdpChannel.CONTROL, phone.encryptedRecord(3, -1, hex("e8") + hex("00000008")), 0)
        assertEquals(listOf("display1"), (withoutMicBit.events().single() as DmsdpEvent.ServicesRequested).answeredServiceIds)
    }

    @Test
    fun micWithoutProfileOmits2102AndNoMicDeclaresNothing() {
        val bare = session(mic = DmsdpConfig.Mic(profile = null))
        bare.feed(DmsdpChannel.CONTROL, phone.encryptedRecord(3, -1, hex(CrossVectors.PHONE_101)), 0)
        val reply = bare.feed(DmsdpChannel.CONTROL, phone.encryptedRecord(3, -1, hex("e8") + hex("00000002")), 0)
        val record = phone.decrypt(reply.sends(DmsdpChannel.CONTROL).single().let { it.copyOfRange(3, it.size) })
        assertTrue(String(record, 12, record.size - 12, Charsets.UTF_8).endsWith(
            "\"properties\":{\"2005\":\"\",\"2004\":\"\",\"2003\":\"\"},\"status\":{}}",
        ))
        val none = session(mic = null)
        none.feed(DmsdpChannel.CONTROL, phone.encryptedRecord(3, -1, hex(CrossVectors.PHONE_101)), 0)
        val nothing = none.feed(DmsdpChannel.CONTROL, phone.encryptedRecord(3, -1, hex("e8") + hex("00000002")), 0)
        assertEquals(emptyList<String>(), (nothing.events().single() as DmsdpEvent.ServicesRequested).answeredServiceIds)
        assertEquals(
            "{\"codecs\":[],\"currentVolume\":{\"a\":false},\"extendInfo\":{},\"profiles\":[{\"sampleRate\":48000,\"channel\":12," +
                "\"format\":1}],\"soundEffect\":0}",
            MicProfile(48_000, 2, pcmOnly = false).json,
        )
    }

    // ---------------- 触发、SETUP、200 ----------------

    @Test
    fun triggerIsAnsweredWith200ThenTheCarAsksForAbilityNegotiation() {
        val recorder = MicRecorder()
        val session = session(recorder)
        val outputs = session.feed(DmsdpChannel.CONTROL, trigger("OPTIONS"), now)
        assertEquals(
            listOf(
                "DMSDP/1.0 200 OK\r\nServiceID: mic1\r\nServiceType: 2\r\nDataSessionID: -42\r\nCSeq: 7\r\n\r\n",
                "OPTIONS * DMSDP/1.0\r\nServiceID: mic1\r\nServiceType: 2\r\nDataSessionID: -42\r\nCSeq: 2\r\n" +
                    "CryptoVer: 1\r\nCryptoAbility: 222\r\nCryptoAlg: aes128-ctr\r\nFillp: 0\r\nTCP: 1\r\nCodec: 0\r\nSimple: 1\r\n\r\n",
            ),
            texts(outputs),
        )
        val requested = recorder.only<MicEvent.Requested>().parameters
        assertEquals("-42", requested.dataSessionId)
        assertEquals(16_000, requested.sampleRate)
        assertEquals(1, requested.channels)
        assertEquals(MicCodec.PCM, requested.codec)
        assertEquals(640, requested.chunkBytes, "20ms 一块")
        assertTrue(outputs.none { it is DmsdpAction.OpenMicListener }, "等手机的 SETUP 再开端口")
    }

    @Test
    fun aacRequestSetsTheCompressedFlag() {
        val session = session()
        val outputs = session.feed(
            DmsdpChannel.CONTROL,
            trigger("OPTIONS", para = "SampleRate=48000;Format=16;ChannelNum=2;Codec=0"),
            now,
        )
        assertTrue("Fillp: 0\r\nTCP: 1\r\nCodec: 1\r\nSimple: 1\r\n" in texts(outputs)[1])
    }

    @Test
    fun phoneSetupOpensAListenerAndTheAnswerCarriesBothPorts() {
        val recorder = MicRecorder()
        val session = session(recorder)
        session.feed(DmsdpChannel.CONTROL, trigger("OPTIONS"), now)
        val setupOut = session.feed(DmsdpChannel.CONTROL, setup(), now)
        assertEquals(listOf<DmsdpOutput>(DmsdpAction.OpenMicListener), setupOut)
        val received = recorder.only<MicEvent.SetupReceived>()
        assertEquals(50000, received.clientPort)
        assertEquals("RTP/AVP/UDP;unicast;client_port=50000--1", received.headers.toMap()["Transport"], "SETUP 的头部原样记下")
        assertTrue("头部 CSeq: 8；ServiceID: mic1；" in received.toString(), received.toString())
        val ready = session.micListenerReady(41000)
        assertEquals(
            listOf(
                "DMSDP/1.0 200 OK\r\nServiceID: mic1\r\nServiceType: 2\r\nDataSessionID: -42\r\nCSeq: 8\r\n" +
                    "Transport: RTP/AVP/UDP;unicast;client_port=50000--1;server_port=41000--1\r\n\r\n",
            ),
            texts(ready),
        )
        assertEquals(41000, recorder.only<MicEvent.SetupAnswered>().serverPort)
        // 重复的 SETUP：按已开的端口再回一次 200，不再开端口。
        val again = session.feed(DmsdpChannel.CONTROL, setup(cseq = "9"), now)
        assertTrue(again.none { it is DmsdpAction.OpenMicListener })
        assertTrue("CSeq: 9\r\nTransport: RTP/AVP/UDP;unicast;client_port=50000--1;server_port=41000--1" in texts(again).single())
    }

    @Test
    fun setupForAnotherSessionOrWithoutPortIsNotAnswered() {
        val recorder = MicRecorder()
        val session = session(recorder)
        assertTrue(session.feed(DmsdpChannel.CONTROL, setup(), now).isEmpty(), "还没触发")
        session.feed(DmsdpChannel.CONTROL, trigger("OPTIONS"), now)
        assertTrue(session.feed(DmsdpChannel.CONTROL, setup(sessionId = "5"), now).isEmpty())
        assertTrue(session.feed(DmsdpChannel.CONTROL, setup(transport = "RTP/AVP/UDP;unicast"), now).isEmpty())
        assertEquals(3, recorder.notes().count { "没有应答" in it })
    }

    // ---------------- 推流 ----------------

    @Test
    fun connectedStreamPacketizesPcmWithAdvancingTimestamps() {
        val recorder = MicRecorder()
        val session = streaming(recorder)
        assertEquals(MicCodec.PCM, recorder.only<MicEvent.Started>().parameters.codec)
        val chunk = ByteArray(640) { it.toByte() }
        val first = session.sendMicAudio(chunk, 0, chunk.size, now + 30)
        val second = session.sendMicAudio(chunk, 0, chunk.size, now + 50)
        val rtp1 = openMic(first.sends(DmsdpChannel.MIC).single())
        val rtp2 = openMic(second.sends(DmsdpChannel.MIC).single())
        // 没做过时间同步：起点取本机时钟（毫秒 × 90）；之后按采样数走，320 个采样 = 1800。
        assertEquals((now + 10) * 90 and 0xFFFF_FFFFL, timestamp(rtp1))
        assertEquals(timestamp(rtp1) + 1800, timestamp(rtp2))
        assertEquals(0x00, rtp1[1].toInt() and 0x7F, "PT 0")
        assertContentEquals(chunk, rtp1.copyOfRange(24, rtp1.size))
        // 半个采样帧以外的零头不送；超过一块的上限就分块。
        val odd = session.sendMicAudio(ByteArray(641), 0, 641, now)
        assertEquals(640, openMic(odd.sends(DmsdpChannel.MIC).single()).size - 24)
    }

    @Test
    fun timestampsFollowThePhoneClockAfterTimeSync() {
        val recorder = MicRecorder()
        val session = session(recorder)
        // 手机时钟 = 高位 1、低位 -1（0xFFFFFFFF）微秒。
        val sync = session.feed(
            DmsdpChannel.CONTROL,
            phone.encryptedText("SET_PARAMETER * DMSDP/1.0\r\nCSeq: 3\r\nTimeServerPort: 9\r\nTimeUsHigh: 1\r\nTimeUsLow: -1\r\n\r\n"),
            2_000,
        )
        assertIs<DmsdpEvent.TimeSyncRequested>(sync.events().single())
        session.feed(DmsdpChannel.CONTROL, trigger("OPTIONS"), 2_100)
        session.feed(DmsdpChannel.CONTROL, setup(), 2_200)
        session.micListenerReady(41000)
        session.onConnected(DmsdpChannel.MIC, 2_500)
        val rtp = openMic(session.sendMicAudio(ByteArray(640), 0, 640, 2_510).sends(DmsdpChannel.MIC).single())
        val phoneUs = (1L shl 32) + 0xFFFF_FFFFL + 500 * 1000
        assertEquals(phoneUs * 9 / 100 and 0xFFFF_FFFFL, timestamp(rtp))
    }

    @Test
    fun aacFramesGoOutAsOnePacketEach() {
        val recorder = MicRecorder()
        val session = streaming(recorder, para = "SampleRate=48000;Format=16;ChannelNum=2;Codec=0")
        val frame = ByteArray(300) { 0x5A }
        val first = openMic(session.sendMicAudio(frame, 0, frame.size, now).sends(DmsdpChannel.MIC).single())
        val second = openMic(session.sendMicAudio(frame, 0, frame.size, now).sends(DmsdpChannel.MIC).single())
        assertEquals(0x80 or 96, first[1].toInt() and 0xFF)
        assertEquals(timestamp(first) + 1920, timestamp(second), "每帧 1024 个采样，48kHz 下 1920")
        assertContentEquals(frame, first.copyOfRange(12, first.size))
        assertTrue(session.sendMicAudio(ByteArray(2000), 0, 2000, now).isEmpty(), "放不进一个包的帧丢掉")
        assertTrue(recorder.notes().any { "放不进一个包" in it })
    }

    @Test
    fun audioBeforeTheConnectionIsDroppedAndCounted() {
        val recorder = MicRecorder()
        val session = session(recorder)
        assertTrue(session.sendMicAudio(ByteArray(640), 0, 640, now).isEmpty(), "没有会话")
        session.feed(DmsdpChannel.CONTROL, trigger("OPTIONS"), now)
        assertTrue(session.sendMicAudio(ByteArray(640), 0, 640, now).isEmpty(), "还没连上")
        val teardown = session.feed(DmsdpChannel.CONTROL, trigger("TEARDOWN", cseq = "11"), now)
        assertEquals(1, recorder.only<MicEvent.Stopped>().summary.dropped)
        assertTrue(teardown.contains(DmsdpAction.CloseMicTransport))
    }

    // ---------------- 设参、拆除、替换、断开 ----------------

    @Test
    fun audioParameterChangeIsAnsweredAndRestartsTheFormat() {
        val recorder = MicRecorder()
        val session = streaming(recorder)
        val before = openMic(session.sendMicAudio(ByteArray(640), 0, 640, now).sends(DmsdpChannel.MIC).single())
        val change = session.feed(
            DmsdpChannel.CONTROL,
            phone.encryptedText(
                "SET_PARAMETER * DMSDP/1.0\r\nCSeq: 12\r\nServiceID: mic1\r\nServiceType: 2\r\nDataSessionID: -42\r\n" +
                    "AudioParameter: SampleRate=48000;Format=16;ChannelNum=1;Codec=1\r\n\r\n",
            ),
            now,
        )
        assertEquals(
            listOf("DMSDP/1.0 200 OK\r\nServiceID: mic1\r\nServiceType: 2\r\nDataSessionID: -42\r\nCSeq: 12\r\n\r\n"),
            texts(change),
        )
        assertEquals(48_000, recorder.only<MicEvent.ParametersChanged>().parameters.sampleRate)
        val after = openMic(session.sendMicAudio(ByteArray(1920), 0, 1920, now).sends(DmsdpChannel.MIC).last())
        assertEquals(timestamp(before) + 1800, timestamp(after), "新参数从原来的时间戳接着数")
    }

    @Test
    fun teardownAnswersClosesAndSummarizes() {
        val recorder = MicRecorder()
        val session = streaming(recorder)
        session.sendMicAudio(ByteArray(3200), 0, 3200, now)
        val teardown = session.feed(DmsdpChannel.CONTROL, trigger("TEARDOWN", cseq = "13"), now)
        assertEquals(
            listOf("DMSDP/1.0 200 OK\r\nServiceID: mic1\r\nServiceType: 2\r\nDataSessionID: -42\r\nCSeq: 13\r\n\r\n"),
            texts(teardown),
        )
        assertTrue(teardown.contains(DmsdpAction.CloseMicTransport))
        assertIs<DmsdpEvent.MicSessionTornDown>(teardown.events().single())
        val stopped = recorder.only<MicEvent.Stopped>()
        assertEquals(MicEndReason.PEER_TEARDOWN, stopped.reason)
        assertEquals(41000, stopped.summary.serverPort)
        assertTrue(stopped.summary.connected)
        assertEquals(4, stopped.summary.packets, "3200 字节分 4 片")
        assertEquals(100, stopped.summary.audioMillis)
        assertTrue(session.sendMicAudio(ByteArray(640), 0, 640, now).isEmpty(), "拆除之后不再送")
    }

    @Test
    fun directTeardownRequestAlsoEndsTheStream() {
        val recorder = MicRecorder()
        val session = streaming(recorder)
        val out = session.feed(
            DmsdpChannel.CONTROL,
            phone.encryptedText("TEARDOWN * DMSDP/1.0\r\nCSeq: 14\r\nServiceID: mic1\r\nServiceType: 2\r\nDataSessionID: -42\r\n\r\n"),
            now,
        )
        assertTrue("CSeq: 14" in texts(out).single())
        assertEquals(MicEndReason.PEER_TEARDOWN, recorder.only<MicEvent.Stopped>().reason)
    }

    @Test
    fun newSessionIdReplacesTheOldOneAndPeerCloseEndsIt() {
        val recorder = MicRecorder()
        val session = streaming(recorder)
        val replaced = session.feed(DmsdpChannel.CONTROL, trigger("OPTIONS", sessionId = "43", cseq = "15"), now)
        assertTrue(replaced.contains(DmsdpAction.CloseMicTransport))
        assertEquals(MicEndReason.REPLACED, recorder.only<MicEvent.Stopped>().reason)
        assertEquals("43", recorder.events.filterIsInstance<MicEvent.Requested>().last().parameters.dataSessionId)

        session.feed(DmsdpChannel.CONTROL, setup(sessionId = "43", cseq = "16"), now)
        session.micListenerReady(41002)
        session.onConnected(DmsdpChannel.MIC, now)
        val closed = session.onClosed(DmsdpChannel.MIC, now)
        assertTrue(closed.contains(DmsdpAction.CloseMicTransport), "监听也要关")
        assertEquals(MicEndReason.PEER_CLOSED, recorder.events.filterIsInstance<MicEvent.Stopped>().last().reason)
    }

    @Test
    fun listenerFailureEndsTheStreamWithoutAnswering() {
        val recorder = MicRecorder()
        val session = session(recorder)
        session.feed(DmsdpChannel.CONTROL, trigger("OPTIONS"), now)
        session.feed(DmsdpChannel.CONTROL, setup(), now)
        val failed = session.micListenerFailed("端口被占")
        assertTrue(failed.sends(DmsdpChannel.CONTROL).isEmpty())
        assertTrue(failed.contains(DmsdpAction.CloseMicTransport))
        assertEquals(MicEndReason.LISTEN_FAILED, recorder.only<MicEvent.Stopped>().reason)
        assertEquals(listOf<DmsdpOutput>(DmsdpAction.CloseMicTransport), session.micListenerReady(41000), "晚到的端口直接关掉")
    }

    @Test
    fun repeatedTriggerResendsNegotiationOnlyBeforeSetup() {
        val session = session()
        session.feed(DmsdpChannel.CONTROL, trigger("OPTIONS"), now)
        val again = texts(session.feed(DmsdpChannel.CONTROL, trigger("OPTIONS", cseq = "17"), now))
        assertEquals(2, again.size)
        assertTrue(again[1].startsWith("OPTIONS * DMSDP/1.0\r\n") && "CSeq: 3\r\n" in again[1])
        session.feed(DmsdpChannel.CONTROL, setup(), now)
        assertEquals(1, texts(session.feed(DmsdpChannel.CONTROL, trigger("OPTIONS", cseq = "18"), now)).size, "SETUP 之后只回 200")
    }

    @Test
    fun unsupportedOrNonSimpleTriggersDoNotStartAStream() {
        val recorder = MicRecorder()
        val session = session(recorder)
        val wide = texts(session.feed(DmsdpChannel.CONTROL, trigger("OPTIONS", para = "SampleRate=16000;Format=24;ChannelNum=1;Codec=1"), now))
        assertEquals(1, wide.size, "回了 200，但不发能力协商")
        assertTrue(recorder.notes().any { "不支持" in it })
        assertTrue(session.feed(DmsdpChannel.CONTROL, trigger("OPTIONS", simple = "0"), now).sends(DmsdpChannel.CONTROL).isEmpty())
        assertTrue(recorder.events.none { it is MicEvent.Requested })
        val unknown = session.feed(DmsdpChannel.CONTROL, trigger("RESETUP"), now)
        assertIs<DmsdpEvent.UnhandledControlMessage>(unknown.events().single())
    }

    @Test
    fun phoneResponseToTheNegotiationIsConsumedAndListenerErrorsAreContained() {
        val recorder = MicRecorder()
        val session = session(recorder)
        session.feed(DmsdpChannel.CONTROL, trigger("OPTIONS"), now)
        val answer = session.feed(DmsdpChannel.CONTROL, phone.encryptedText("DMSDP/1.0 200 OK\r\nCSeq: 2\r\n\r\n"), now)
        assertTrue(answer.events().none { it is DmsdpEvent.UnhandledControlMessage }, "按 CSeq 认出是给麦克风的应答")
        assertTrue(recorder.notes().any { "能力协商：DMSDP/1.0 200 OK；头部 CSeq: 2" in it }, "应答的头部记进诊断")
        recorder.throwOnEvent = true
        val out = session.feed(DmsdpChannel.CONTROL, setup(), now)
        assertTrue(out.contains(DmsdpAction.OpenMicListener), "回调抛异常不影响流程")
        assertTrue(out.events().any { it is DmsdpEvent.AudioNote })
        assertTrue(session.isEncrypted)
    }

    @Test
    fun closingTheDeviceSessionEndsTheMicStream() {
        val recorder = MicRecorder()
        val session = streaming(recorder)
        val closing = session.close()
        assertFalse(closing.contains(DmsdpAction.CloseMicTransport), "整个连接结束时资源由调用方统一关")
        assertEquals(MicEndReason.SESSION_CLOSED, recorder.only<MicEvent.Stopped>().reason)
        assertTrue(session.sendMicAudio(ByteArray(640), 0, 640, now).isEmpty(), "关闭之后送声音不抛异常")
    }

    // ---------------- 解析 ----------------

    @Test
    fun clientPortParsing() {
        assertEquals(50000 to -1, MicSession.clientPorts("RTP/AVP/UDP;unicast;client_port=50000--1"))
        assertEquals(50000 to 50001, MicSession.clientPorts("RTP/AVP/UDP;unicast;client_port=50000-50001"))
        assertEquals(50000 to -1, MicSession.clientPorts("RTP/AVP/UDP;unicast;client_port=50000"))
        assertEquals(1 to 2, MicSession.clientPorts("client_port=1-2;server_port=3-4"))
        assertNull(MicSession.clientPorts("RTP/AVP/UDP;unicast;client_port=0--1"))
        assertNull(MicSession.clientPorts("RTP/AVP/UDP;unicast;client_port=70000--1"))
        assertNull(MicSession.clientPorts("RTP/AVP/UDP;unicast;client_port=abc"))
        assertNull(MicSession.clientPorts("RTP/AVP/UDP;unicast;client_port=5-"))
        assertNull(MicSession.clientPorts("RTP/AVP/UDP;unicast"))
        assertNull(MicSession.clientPorts(null))
    }

    @Test
    fun parameterParsingFollowsThePhoneRules() {
        val pcm = MicSession.parseParameters("1", "SampleRate=16000;Format=16;ChannelNum=1;Codec=1")!!
        assertEquals(MicCodec.PCM, pcm.codec)
        assertEquals(2, pcm.frameBytes)
        val aac = MicSession.parseParameters("1", "samplerate=48000, format=16, channelnum=2, codec=0")!!
        assertEquals(MicCodec.AAC, aac.codec)
        assertEquals(3840, aac.chunkBytes)
        assertEquals(MicCodec.PCM, MicSession.parseParameters("1", "SampleRate=16000;ChannelNum=1")!!.codec, "低比特率推断成 PCM")
        assertEquals(MicCodec.AAC, MicSession.parseParameters("1", "")!!.codec, "默认 48kHz 立体声推断成 AAC")
        assertNull(MicSession.parseParameters("1", "SampleRate=16000;Format=16;ChannelNum=1;Codec=2"))
        assertNull(MicSession.parseParameters("1", "SampleRate=96000;Format=16;ChannelNum=1;Codec=1"))
        assertNull(MicSession.parseParameters("1", "SampleRate=16000;Format=16;ChannelNum=6;Codec=1"))
        assertNull(MicSession.parseParameters("1", "SampleRate=16000;Format=16;ChannelNum=1;Codec=x"))
    }
}
