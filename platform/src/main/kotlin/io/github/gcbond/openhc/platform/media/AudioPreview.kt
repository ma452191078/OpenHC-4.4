package io.github.gcbond.openhc.platform.media

import android.media.AudioFormat
import android.media.AudioTrack

/**
 * 设置页的「试听」：用正式播放同一个建音轨函数（[PcmTrack.build]）放一段提示音（[PreviewTone]），
 * 好让车主听出这个声道跟的是哪个音量、和音乐分没分开。不申请音频焦点。
 * 在自己的线程上放，放完在那个线程上回调。
 */
object AudioPreview {

    class Result(
        /** 试听时选的声道（[AudioStreamChoice] 的值）。 */
        val stream: Int,
        /** 指定的声道号被系统拒绝，按用途放的。 */
        val streamRejected: Boolean,
        /** 实际走了哪条路（同 [PcmTrack.Built.route]）。 */
        val route: String,
        /** 放不出来时的原因；放了为 null。 */
        val error: String?,
    )

    fun play(usage: AudioUsage, stream: Int, lowLatency: Boolean, onDone: (Result) -> Unit) {
        Thread({ onDone(run(usage, stream, lowLatency)) }, "OpenHC-AudioPreview").start()
    }

    private fun run(usage: AudioUsage, stream: Int, lowLatency: Boolean): Result {
        val mask = AudioFormat.CHANNEL_OUT_MONO
        val minimum = AudioTrack.getMinBufferSize(SAMPLE_RATE, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) return Result(stream, false, "", "系统不支持 ${SAMPLE_RATE}Hz 单声道 16 位 PCM")
        val bufferBytes = maxOf(minimum, AudioTuning.framesFor(SAMPLE_RATE, BUFFER_MILLIS) * 2)
        val built = try {
            PcmTrack.build(usage, stream, SAMPLE_RATE, mask, bufferBytes, lowLatency)
        } catch (e: Exception) {
            return Result(stream, false, "", "${e.javaClass.simpleName}：${e.message}")
        }
        val track = built.track
        try {
            val pcm = PreviewTone.pcm(SAMPLE_RATE)
            track.play()
            var offset = 0
            while (offset < pcm.size) {
                val written = track.write(pcm, offset, pcm.size - offset)
                if (written <= 0) return Result(stream, built.streamRejected, built.route, "写音轨失败（$written）")
                offset += written
            }
            // 流模式下 stop 会把已写的放完再停，但不告诉我们什么时候放完：等缓冲里那点放完再释放。
            track.stop()
            Thread.sleep(AudioTuning.millisFor(SAMPLE_RATE, (bufferBytes / 2).toLong()) + DRAIN_MARGIN_MILLIS)
        } catch (e: Exception) {
            return Result(stream, built.streamRejected, built.route, "${e.javaClass.simpleName}：${e.message}")
        } finally {
            track.release()
        }
        return Result(stream, built.streamRejected, built.route, null)
    }

    private const val SAMPLE_RATE = 48_000
    private const val BUFFER_MILLIS = 100
    private const val DRAIN_MARGIN_MILLIS = 300L
}
