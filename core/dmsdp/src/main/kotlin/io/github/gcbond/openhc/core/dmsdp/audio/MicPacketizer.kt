package io.github.gcbond.openhc.core.dmsdp.audio

import io.github.gcbond.openhc.core.dmsdp.AesCtr
import java.security.SecureRandom

/**
 * 车机麦克风的上行打包：一块声音 → 若干 RTP 包 → 每包加密成一帧。外壳与手机发来的媒体声音相同：
 *
 * ```
 * 帧    = 0xAB ‖ 长度 BE16 ‖ IV16 ‖ AES-128-CTR(会话密钥, IV)(RTP 包)     长度 = 16 + RTP 包长
 * RTP   = 0x80 ‖ PT（最高位是 marker）‖ 序号 BE16 ‖ 时间戳 BE32 ‖ SSRC BE32 ‖ 载荷
 * ```
 *
 * - **SSRC** 固定为 [SSRC]：低 12 位是流号 1100（录音流），高 16 位是用途，录音流不带用途写 0。
 * - **时间戳**是 90kHz，由调用方给（同一块声音的各片共用一个）；序号每包加一，16 位回绕。
 * - **PCM**（PT [PT_PCM]）：16 位小端交错样本，一块最多 [MAX_PCM_BUFFER] 字节。每片至多 [PCM_FRAGMENT] 字节数据，
 *   前面带 12 字节分片头：
 *
 *   | 偏移 | 长度 | 内容 |
 *   |---|---|---|
 *   | 0 | 1 | 0 |
 *   | 1 | 1 | 分片位置：1 第一片（或唯一一片）、2 中间、3 最后 |
 *   | 2 | 2 | 扩展标志，录音流没有扩展写 0 |
 *   | 4 | 2 | 分片序号 BE（从 0 起） |
 *   | 6 | 2 | 0 |
 *   | 8 | 2 | 扩展长度 BE，写 0 |
 *   | 10 | 2 | 本片数据字节数 BE |
 *
 *   最后一片（或唯一一片）置 marker，其余不置。
 * - **AAC**（PT [PT_AAC]）：一个编码帧（带 ADTS 头）一个包，置 marker。
 *
 * IV 每帧从 [random] 取 16 字节。不是线程安全的，只在协议线程上用。
 */
internal class MicPacketizer(private val cipher: AesCtr, private val random: SecureRandom) {
    private var sequence = 0
    private val iv = ByteArray(AesCtr.IV_BYTES)
    private val plain = ByteArray(RTP_HEADER + PCM_HEADER + PCM_FRAGMENT)

    /** 送出的包数。 */
    var packets = 0L
        private set

    /** 送出的线上字节数（含 3 字节帧头和 IV）。 */
    var bytes = 0L
        private set

    /**
     * 一块 PCM（1 ～ [MAX_PCM_BUFFER] 字节）打成若干帧，按顺序写到连接上即可。
     * [timestamp] 是这块第一个采样的 90kHz 时间戳（取低 32 位）。
     */
    fun pcm(data: ByteArray, offset: Int, length: Int, timestamp: Long): List<ByteArray> {
        require(offset >= 0 && length >= 0 && offset <= data.size - length) { "范围非法" }
        require(length in 1..MAX_PCM_BUFFER) { "一块 PCM 应为 1～$MAX_PCM_BUFFER 字节: $length" }
        val count = (length + PCM_FRAGMENT - 1) / PCM_FRAGMENT
        val frames = ArrayList<ByteArray>(count)
        for (index in 0 until count) {
            val start = index * PCM_FRAGMENT
            val n = minOf(PCM_FRAGMENT, length - start)
            val last = index == count - 1
            val position = when {
                index == 0 -> POSITION_FIRST
                last -> POSITION_LAST
                else -> POSITION_MIDDLE
            }
            writeRtpHeader(PT_PCM, marker = last, timestamp = timestamp)
            val h = RTP_HEADER
            plain.fill(0, h, h + PCM_HEADER)
            plain[h + 1] = position.toByte()
            putU16(plain, h + 4, index)
            putU16(plain, h + 10, n)
            System.arraycopy(data, offset + start, plain, h + PCM_HEADER, n)
            frames += seal(RTP_HEADER + PCM_HEADER + n)
        }
        return frames
    }

    /**
     * 一个 AAC 帧（1 ～ [MAX_AAC_FRAME] 字节，带 ADTS 头）打成一帧。
     */
    fun aac(data: ByteArray, offset: Int, length: Int, timestamp: Long): ByteArray {
        require(offset >= 0 && length >= 0 && offset <= data.size - length) { "范围非法" }
        require(length in 1..MAX_AAC_FRAME) { "一个 AAC 帧应为 1～$MAX_AAC_FRAME 字节: $length" }
        val buffer = ByteArray(RTP_HEADER + length)
        writeRtpHeader(PT_AAC, marker = true, timestamp = timestamp, target = buffer)
        System.arraycopy(data, offset, buffer, RTP_HEADER, length)
        return seal(buffer, buffer.size)
    }

    private fun writeRtpHeader(payloadType: Int, marker: Boolean, timestamp: Long, target: ByteArray = plain) {
        target[0] = RTP_VERSION_BYTE.toByte()
        target[1] = (payloadType or (if (marker) MARKER else 0)).toByte()
        putU16(target, 2, sequence)
        putU32(target, 4, timestamp)
        putU32(target, 8, SSRC.toLong())
        sequence = (sequence + 1) and 0xFFFF
    }

    private fun seal(length: Int): ByteArray = seal(plain, length)

    /** `0xAB ‖ 长度 ‖ IV ‖ 密文`：直接加密进最终的帧，不另外复制。 */
    private fun seal(source: ByteArray, length: Int): ByteArray {
        random.nextBytes(iv)
        val sealedLength = AesCtr.IV_BYTES + length
        val frame = ByteArray(FRAME_HEADER + sealedLength)
        frame[0] = FRAME_ENCRYPTED.toByte()
        putU16(frame, 1, sealedLength)
        System.arraycopy(iv, 0, frame, FRAME_HEADER, AesCtr.IV_BYTES)
        cipher.transform(iv, source, 0, length, frame, FRAME_HEADER + AesCtr.IV_BYTES)
        packets++
        bytes += frame.size
        return frame
    }

    internal companion object {
        /** 录音流的 SSRC：流号 1100（0x44C），用途 0。 */
        const val SSRC = 0x0000044C

        const val PT_PCM = 0
        const val PT_AAC = 96

        const val RTP_HEADER = 12
        const val PCM_HEADER = 12

        /** PCM 每片至多这么多字节数据。 */
        const val PCM_FRAGMENT = 1024

        /** 一块 PCM 的上限。 */
        const val MAX_PCM_BUFFER = 4096

        /** 手机收端每帧至多读 1500 字节（含 IV），AAC 帧要能整个放进一个包。 */
        const val MAX_SEALED = 1500
        const val MAX_AAC_FRAME = MAX_SEALED - AesCtr.IV_BYTES - RTP_HEADER

        const val FRAME_HEADER = 3
        const val FRAME_ENCRYPTED = 0xAB

        const val POSITION_FIRST = 1
        const val POSITION_MIDDLE = 2
        const val POSITION_LAST = 3

        private const val RTP_VERSION_BYTE = 0x80
        private const val MARKER = 0x80

        private fun putU16(out: ByteArray, at: Int, value: Int) {
            out[at] = (value ushr 8).toByte()
            out[at + 1] = value.toByte()
        }

        private fun putU32(out: ByteArray, at: Int, value: Long) {
            out[at] = (value ushr 24).toByte()
            out[at + 1] = (value ushr 16).toByte()
            out[at + 2] = (value ushr 8).toByte()
            out[at + 3] = value.toByte()
        }
    }
}
