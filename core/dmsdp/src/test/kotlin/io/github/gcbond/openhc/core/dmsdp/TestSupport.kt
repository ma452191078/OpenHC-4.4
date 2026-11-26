package io.github.gcbond.openhc.core.dmsdp

import io.github.gcbond.openhc.core.common.hexToBytes
import io.github.gcbond.openhc.core.common.identity.HostIdentity
import io.github.gcbond.openhc.core.dmsdp.audio.AudioStreamListener
import java.security.SecureRandom
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** 固定序列的「随机数」：第 i 个字节 = (i × 37 + 11) mod 256。只用于测试。 */
internal class SequenceRandom : SecureRandom() {
    var index = 0
        private set

    override fun nextBytes(bytes: ByteArray) {
        for (k in bytes.indices) bytes[k] = (((index + k) * 37 + 11) and 0xFF).toByte()
        index += bytes.size
    }
}

/** 测试里用的合成值：密钥、身份、地址都是一眼能看出来的假值。 */
internal object Synthetic {
    val KEY = ByteArray(16) { it.toByte() }
    const val SELF_ID = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF"
    const val PEER_ID = "FEDCBA9876543210FEDCBA9876543210FEDCBA9876543210FEDCBA9876543210"
    const val MAC = "0A:1B:2C:3D:4E:5F"
    const val LOCAL_IP = "192.0.2.10"
    const val PHONE_IP = "192.0.2.20"

    fun host(name: String = "测试车机 Ünï") = HostIdentity("ABCDEF", "01", "OpenHC", name)

    val DISPLAY = DmsdpConfig.Display(
        width = 1920, height = 720, density = 1.5, dpi = 240, fps = 30,
        videoWidth = 1280, videoHeight = 480, gop = 60, bitrate = 8_000_000,
    )

    fun config(
        display: DmsdpConfig.Display? = DISPLAY,
        name: String = "测试车机 Ünï",
        additionalServices: List<DmsdpConfig.Service> = emptyList(),
        speaker: DmsdpConfig.Speaker? = null,
    ) = DmsdpConfig(
        host = host(name),
        deviceId = SELF_ID,
        bluetoothMac = MAC,
        localIp = LOCAL_IP,
        phoneIp = PHONE_IP,
        display = display,
        speaker = speaker,
        additionalServices = additionalServices,
    )
}

/** 模拟手机：用测试自己的 IV 加密，不消耗会话的随机数序列。 */
internal class Phone(key: ByteArray = Synthetic.KEY) {
    private val cipher = AesCtr(key)
    private var ivCounter = 0

    fun nextIv(): ByteArray {
        ivCounter++
        return ByteArray(16) { (0xC0 + ivCounter + it).toByte() }
    }

    fun encrypt(plain: ByteArray, iv: ByteArray = nextIv()): ByteArray {
        val out = ByteArray(16 + plain.size)
        System.arraycopy(iv, 0, out, 0, 16)
        cipher.transform(iv, plain, 0, plain.size, out, 16)
        return out
    }

    fun decrypt(payload: ByteArray): ByteArray = cipher.open(payload)

    fun plainFrame(text: String): ByteArray = ControlFrames.encode(ControlFrames.FLAG_PLAIN, text.toByteArray())

    fun encryptedFrame(plain: ByteArray): ByteArray = ControlFrames.encode(ControlFrames.FLAG_ENCRYPTED, encrypt(plain))

    fun encryptedText(text: String): ByteArray = encryptedFrame(text.toByteArray())

    fun record(module: Long, type: Int, data: ByteArray): ByteArray = DeviceRecords.encode(module, type, data)

    fun encryptedRecord(module: Long, type: Int, data: ByteArray): ByteArray = encryptedFrame(record(module, type, data))

    fun datagram(plain: ByteArray): ByteArray = ControlFrames.encode(ControlFrames.FLAG_ENCRYPTED, encrypt(plain))

    /** 解开车机发来的一个控制帧，返回明文载荷。 */
    fun openControlFrame(frame: ByteArray): ByteArray {
        val length = ((frame[1].toInt() and 0xFF) shl 8) or (frame[2].toInt() and 0xFF)
        assertEquals(frame.size - 3, length, "控制帧长度字段")
        val payload = frame.copyOfRange(3, frame.size)
        return if (frame[0].toInt() and 0xFF == ControlFrames.FLAG_ENCRYPTED) decrypt(payload) else payload
    }
}

internal const val CRYPTO_SELECT =
    "SET_PARAMETER * DMSDP/1.0\r\nCSeq: 1\r\nCryptoVer: 1\r\nCryptoSwitch: 222\r\nCryptoAlg: aes128-ctr\r\n"

internal fun hex(text: String): ByteArray = text.hexToBytes()

internal fun DmsdpSession.feed(channel: DmsdpChannel, data: ByteArray) = feed(channel, data, 0L)

internal inline fun <reified T : DmsdpOutput> List<DmsdpOutput>.only(): T {
    assertEquals(1, size, "期望只有一个输出: $this")
    return assertIs(this[0])
}

internal fun List<DmsdpOutput>.sends(channel: DmsdpChannel): List<ByteArray> =
    filterIsInstance<DmsdpAction.Send>().filter { it.channel == channel }.map { it.bytes }

internal fun List<DmsdpOutput>.events(): List<DmsdpEvent> = filterIsInstance<DmsdpEvent>()

/**
 * 走完业务 20 协商、OPTIONS 和加密选择，返回已加密的会话。[select] 是加密选择请求里 CSeq 之后、
 * 空行之前的额外头部。
 */
internal fun encryptedSession(
    config: DmsdpConfig = Synthetic.config(),
    random: SecureRandom = SequenceRandom(),
    nowMillis: Long = 100_000,
    select: String = "",
    audio: AudioStreamListener = AudioStreamListener.NONE,
): Pair<DmsdpSession, List<DmsdpOutput>> {
    val session = DmsdpSession(config, Synthetic.KEY, random, audio)
    session.feed(DmsdpChannel.SESSION, hex(CrossVectors.SESSION_NEG_REQUEST))
    session.controlListenerReady(40001)
    session.onConnected(DmsdpChannel.CONTROL, nowMillis)
    val outputs = session.feed(DmsdpChannel.CONTROL, Phone().plainFrame(CRYPTO_SELECT + select + "\r\n"), nowMillis)
    check(session.isEncrypted)
    return session to outputs
}
