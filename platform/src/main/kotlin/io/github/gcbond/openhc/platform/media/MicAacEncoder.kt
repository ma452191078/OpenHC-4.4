package io.github.gcbond.openhc.platform.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import java.io.Closeable

/**
 * 车机麦克风的 AAC-LC 编码（手机这次要 AAC 时用）：MediaCodec 把 20ms 一块的 PCM 编成 AAC 帧，
 * 每帧加上 ADTS 头（[AdtsWriter]）经 [FrameSink] 交出。
 *
 * 在调用 [encode] 的线程上同步送入、取出（不另开线程，一般就是采集线程）；编码器的输入队列满时这一块丢掉、计数。
 * 一个对象对应一次采集，用完 [close]。建不了编码器时构造函数抛异常（例如设备没有 AAC 编码器）。
 */
class MicAacEncoder(
    val sampleRate: Int,
    val channels: Int,
    private val sink: FrameSink,
) : Closeable {

    /** 一个带 ADTS 头的 AAC 帧（新数组，可以直接留着用）。 */
    fun interface FrameSink {
        fun onFrame(frame: ByteArray)
    }

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MIME)
    private val info = MediaCodec.BufferInfo()
    private var presentationUs = 0L
    private var closed = false

    /** 送进编码器的块数、因输入队列满丢掉的块数、交出的帧数。 */
    var chunks = 0L
        private set
    var dropped = 0L
        private set
    var frames = 0L
        private set

    /** 码率：16kHz 及以下每声道 32kbps，更高的每声道 64kbps。 */
    val bitrate: Int = channels * (if (sampleRate <= LOW_RATE_LIMIT) LOW_BITRATE else HIGH_BITRATE)

    init {
        try {
            val format = MediaFormat.createAudioFormat(MIME, sampleRate, channels)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: RuntimeException) {
            codec.release()
            throw e
        }
    }

    /** 送一块 16 位小端交错 PCM（`pcm[0, length)`），顺带取出已经编好的帧。 */
    fun encode(pcm: ByteArray, length: Int) {
        if (closed) return
        val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index >= 0) {
            // 4.4 fork：getInputBuffer 是 21+ 的，更早的系统用弃用的 inputBuffers 数组（每次现取）。
            val buffer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                codec.getInputBuffer(index)
            } else {
                @Suppress("DEPRECATION")
                codec.inputBuffers[index]
            }
            if (buffer == null) {
                dropped++
            } else {
                buffer.clear()
                val n = minOf(length, buffer.remaining())
                buffer.put(pcm, 0, n)
                codec.queueInputBuffer(index, 0, n, presentationUs, 0)
                presentationUs += n.toLong() / (channels * 2) * 1_000_000L / sampleRate
                chunks++
            }
        } else {
            dropped++
        }
        drain()
    }

    private fun drain() {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return
            // 输出格式变化等通知：不带数据，接着取。
            if (index < 0) continue
            // 4.4 fork：getOutputBuffer 是 21+ 的，更早的系统用弃用的 outputBuffers 数组（每次现取，
            // 编码器报过缓冲变化后旧数组失效，所以不能缓存）。
            val buffer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                codec.getOutputBuffer(index)
            } else {
                @Suppress("DEPRECATION")
                codec.outputBuffers[index]
            }
            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            if (buffer != null && !isConfig && info.size > 0 && info.size + AdtsWriter.HEADER_BYTES <= AdtsWriter.MAX_FRAME_BYTES) {
                val frame = ByteArray(AdtsWriter.HEADER_BYTES + info.size)
                AdtsWriter.write(frame, 0, sampleRate, channels, info.size)
                buffer.position(info.offset)
                buffer.get(frame, AdtsWriter.HEADER_BYTES, info.size)
                frames++
                sink.onFrame(frame)
            }
            codec.releaseOutputBuffer(index, false)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            codec.stop()
        } catch (e: IllegalStateException) {
            // 已经出错停了。
        }
        codec.release()
    }

    override fun toString() = "MicAacEncoder(${sampleRate}Hz/${channels}声道，${bitrate / 1000}kbps)"

    private companion object {
        const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
        const val INPUT_TIMEOUT_US = 10_000L
        const val MAX_INPUT_BYTES = 16_384
        const val LOW_RATE_LIMIT = 16_000
        const val LOW_BITRATE = 32_000
        const val HIGH_BITRATE = 64_000
    }
}
