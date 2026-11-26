package io.github.gcbond.openhc.platform.media

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import java.io.Closeable

/**
 * 车机麦克风采集：AudioRecord 单声道 16 位。音源优先 `VOICE_COMMUNICATION`（系统按通话处理，常自带回声消除），
 * 开不了退到 `MIC`；系统有回声消除（AcousticEchoCanceler）、降噪（NoiseSuppressor）就打开。
 *
 * 每 20ms 经 [Sink] 交出一块 [Format] 格式的 PCM（16 位小端交错）。设备开不了要的采样率时，按 [CAPTURE_RATES]
 * 依次试，用能开的采、重采样到要的（[PcmResampler]）；要双声道时把单声道复制成两个声道。
 *
 * 线程：[start] 在调用线程上开 AudioRecord（要几十毫秒，别在协议线程上调），之后在自己的线程上阻塞读；
 * [Sink] 和 [Listener] 都在采集线程上回调，交出的数组会复用，要留就复制。[close] 可以在任意线程调用，等采集线程退出。
 * 要有录音权限（调用方先检查）；没有时 [start] 返回 false。
 */
class MicCapture(
    val format: Format,
    private val sink: Sink,
    private val listener: Listener = Listener { },
) : Closeable {

    /** 交出的格式：采样率和声道（16 位）。 */
    class Format(val sampleRate: Int, val channels: Int) {
        init {
            require(sampleRate in 8_000..48_000) { "麦克风采样率超出范围: $sampleRate" }
            require(channels in 1..2) { "只支持单声道和双声道: $channels" }
        }

        /** 20ms 一块的帧数和字节数。 */
        val chunkFrames: Int get() = sampleRate / CHUNKS_PER_SECOND
        val chunkBytes: Int get() = chunkFrames * channels * BYTES_PER_SAMPLE

        override fun toString() = "${sampleRate}Hz/${channels}声道/16位"
    }

    /** 一块 20ms 的 PCM：`data[0, length)`，数组会复用。 */
    fun interface Sink {
        fun onPcm(data: ByteArray, length: Int)
    }

    sealed class Event {
        /**
         * 开好了：实际音源、实际采样率（与 [Format] 不同时要重采样）、AudioRecord 缓冲；
         * 回声消除、降噪：null 表示系统没有，true 已打开，false 有但没打开。
         */
        class Opened(
            val source: String,
            val captureRate: Int,
            val bufferBytes: Int,
            val echoCanceler: Boolean?,
            val noiseSuppressor: Boolean?,
        ) : Event() {
            override fun toString() = "麦克风开好：音源 $source，采 ${captureRate}Hz 单声道，缓冲 $bufferBytes 字节，" +
                "回声消除 ${effect(echoCanceler)}，降噪 ${effect(noiseSuppressor)}"
        }

        class OpenFailed(val reason: String) : Event() {
            override fun toString() = "麦克风开不了：$reason"
        }

        class ReadFailed(val code: Int) : Event() {
            override fun toString() = "读麦克风出错（$code）"
        }

        /**
         * 一段时间里的电平：最大的采样绝对值（0～32767）和全零的块数。开头 1 秒报一次，之后每 10 秒一次。
         * 一直是全零多半是被系统静音（例如应用不在前台）或麦克风被别的应用占着。
         */
        class Level(val chunks: Long, val peak: Int, val silentChunks: Long) : Event() {
            override fun toString() = "麦克风电平：最近 ${chunks * 1000 / CHUNKS_PER_SECOND}ms 峰值 ${describePeak(peak)}，" +
                "全零的块 $silentChunks/$chunks"
        }

        class Stopped(
            val chunks: Long,
            val readErrors: Long,
            val sinkErrors: Long,
            val peak: Int,
            val silentChunks: Long,
        ) : Event() {
            override fun toString() = "麦克风停止：交出 $chunks 块（${chunks * 1000 / CHUNKS_PER_SECOND}ms），读错 $readErrors 次，" +
                "下游出错 $sinkErrors 次；全程峰值 ${describePeak(peak)}，全零的块 $silentChunks"
        }

        internal fun effect(state: Boolean?) = when (state) {
            null -> "系统没有"
            true -> "已开"
            false -> "没开成"
        }

        internal fun describePeak(peak: Int): String =
            if (peak == 0) "0（全是静音）" else "$peak（${"%.1f".format(java.util.Locale.ROOT, 20 * Math.log10(peak / 32768.0))} dBFS）"
    }

    fun interface Listener {
        fun onEvent(event: Event)
    }

    private class Opened(val record: AudioRecord, val source: String, val rate: Int, val bufferBytes: Int)

    private val lock = Any()

    @Volatile
    private var running = false
    private var record: AudioRecord? = null
    private var echo: AcousticEchoCanceler? = null
    private var noise: NoiseSuppressor? = null
    private var thread: Thread? = null
    private var started = false

    /** 开 AudioRecord、开始采集。只能调用一次；开不了返回 false（原因经 [Listener] 报出）。 */
    fun start(): Boolean {
        synchronized(lock) {
            check(!started) { "start 只能调用一次" }
            started = true
        }
        val opened = open() ?: return false
        val rec = opened.record
        val echoState = enableEchoCanceler(rec.audioSessionId)
        val noiseState = enableNoiseSuppressor(rec.audioSessionId)
        try {
            rec.startRecording()
        } catch (e: RuntimeException) {
            releaseAll(rec)
            listener.onEvent(Event.OpenFailed("开始录音失败：${e.javaClass.simpleName}：${e.message}"))
            return false
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            releaseAll(rec)
            listener.onEvent(Event.OpenFailed("开始录音后状态不对（多半被别的应用占着）"))
            return false
        }
        synchronized(lock) {
            record = rec
            running = true
        }
        listener.onEvent(Event.Opened(opened.source, opened.rate, opened.bufferBytes, echoState, noiseState))
        val worker = Thread({ loop(rec, opened.rate) }, "OpenHC-MicCapture")
        thread = worker
        worker.start()
        return true
    }

    /** 停止采集、释放 AudioRecord 和音效，等采集线程退出（最多 [JOIN_MILLIS]）。可以重复调用。 */
    override fun close() {
        val rec: AudioRecord?
        synchronized(lock) {
            running = false
            rec = record
        }
        try {
            rec?.stop()
        } catch (e: IllegalStateException) {
            // 没在录音也没关系。
        }
        thread?.let { if (it !== Thread.currentThread()) it.join(JOIN_MILLIS) }
        synchronized(lock) {
            if (record != null) {
                releaseAll(record!!)
                record = null
            }
        }
    }

    // 权限由调用方事先检查；没有权限时构造或开始录音会抛异常，下面按开不了处理。
    @SuppressLint("MissingPermission")
    private fun open(): Opened? {
        val failures = ArrayList<String>()
        for ((source, sourceName) in SOURCES) {
            for (rate in (listOf(format.sampleRate) + CAPTURE_RATES).distinct()) {
                val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minimum <= 0) {
                    failures += "${rate}Hz 不支持"
                    continue
                }
                val bufferBytes = maxOf(minimum, rate / CHUNKS_PER_SECOND * BYTES_PER_SAMPLE * BUFFER_CHUNKS)
                val rec = try {
                    AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
                } catch (e: IllegalArgumentException) {
                    failures += "$sourceName ${rate}Hz：${e.message}"
                    continue
                } catch (e: SecurityException) {
                    listener.onEvent(Event.OpenFailed("没有录音权限"))
                    return null
                }
                if (rec.state == AudioRecord.STATE_INITIALIZED) return Opened(rec, sourceName, rate, bufferBytes)
                rec.release()
                failures += "$sourceName ${rate}Hz 初始化失败"
            }
        }
        listener.onEvent(Event.OpenFailed(failures.joinToString("；")))
        return null
    }

    private fun enableEchoCanceler(session: Int): Boolean? {
        if (!AcousticEchoCanceler.isAvailable()) return null
        return try {
            val effect = AcousticEchoCanceler.create(session) ?: return false
            echo = effect
            effect.setEnabled(true) == android.media.audiofx.AudioEffect.SUCCESS
        } catch (e: RuntimeException) {
            false
        }
    }

    private fun enableNoiseSuppressor(session: Int): Boolean? {
        if (!NoiseSuppressor.isAvailable()) return null
        return try {
            val effect = NoiseSuppressor.create(session) ?: return false
            noise = effect
            effect.setEnabled(true) == android.media.audiofx.AudioEffect.SUCCESS
        } catch (e: RuntimeException) {
            false
        }
    }

    private fun releaseAll(rec: AudioRecord) {
        echo?.release()
        echo = null
        noise?.release()
        noise = null
        rec.release()
    }

    private fun loop(rec: AudioRecord, captureRate: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val inputFrames = captureRate / CHUNKS_PER_SECOND
        val input = ShortArray(inputFrames)
        val resampler = if (captureRate != format.sampleRate) PcmResampler(captureRate, format.sampleRate) else null
        val converted = ShortArray(resampler?.maxOutputFrames(inputFrames) ?: inputFrames)
        val chunkFrames = format.chunkFrames
        // 重采样后每次产出的帧数不固定，攒够 20ms 再交出。
        val pending = ShortArray(chunkFrames + converted.size)
        var pendingFrames = 0
        val out = ByteArray(format.chunkBytes)
        var chunks = 0L
        var readErrors = 0L
        var sinkErrors = 0L
        var peak = 0
        var silent = 0L
        var windowChunks = 0L
        var windowPeak = 0
        var windowSilent = 0L
        while (running) {
            val n = rec.read(input, 0, inputFrames)
            if (n < 0) {
                readErrors++
                if (readErrors <= MAX_READ_ERROR_EVENTS) listener.onEvent(Event.ReadFailed(n))
                if (n == AudioRecord.ERROR_INVALID_OPERATION || n == ERROR_DEAD_OBJECT) break
                Thread.sleep(READ_ERROR_PAUSE_MILLIS)
                continue
            }
            if (n == 0) continue
            val produced = resampler?.process(input, n, converted) ?: n
            System.arraycopy(if (resampler != null) converted else input, 0, pending, pendingFrames, produced)
            pendingFrames += produced
            while (pendingFrames >= chunkFrames) {
                val chunkPeak = peakOf(pending, chunkFrames)
                if (chunkPeak == 0) {
                    silent++
                    windowSilent++
                }
                if (chunkPeak > peak) peak = chunkPeak
                if (chunkPeak > windowPeak) windowPeak = chunkPeak
                windowChunks++
                PcmResampler.toLittleEndian(pending, chunkFrames, format.channels, out, 0)
                try {
                    sink.onPcm(out, format.chunkBytes)
                } catch (e: RuntimeException) {
                    sinkErrors++
                }
                System.arraycopy(pending, chunkFrames, pending, 0, pendingFrames - chunkFrames)
                pendingFrames -= chunkFrames
                chunks++
                // 开头 1 秒报一次电平，之后每 10 秒一次：分得清「采到的是静音」和「送出去了对方没收」。
                if (chunks == LEVEL_FIRST_CHUNKS || windowChunks >= LEVEL_EVERY_CHUNKS) {
                    listener.onEvent(Event.Level(windowChunks, windowPeak, windowSilent))
                    windowChunks = 0
                    windowPeak = 0
                    windowSilent = 0
                }
            }
        }
        listener.onEvent(Event.Stopped(chunks, readErrors, sinkErrors, peak, silent))
    }

    private fun peakOf(samples: ShortArray, frames: Int): Int {
        var max = 0
        for (i in 0 until frames) {
            val v = samples[i].toInt()
            val a = if (v < 0) -v else v
            if (a > max) max = a
        }
        return minOf(max, Short.MAX_VALUE.toInt())
    }

    companion object {
        const val CHUNKS_PER_SECOND = 50
        private const val BYTES_PER_SAMPLE = 2

        /** 开不了要的采样率时依次试的采样率。 */
        val CAPTURE_RATES = listOf(48_000, 16_000, 44_100, 32_000, 8_000)

        private val SOURCES = listOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION",
            MediaRecorder.AudioSource.MIC to "MIC",
        )

        /** AudioRecord 缓冲至少放得下这么多块（20ms 一块）。 */
        private const val BUFFER_CHUNKS = 4
        private const val MAX_READ_ERROR_EVENTS = 3
        private const val READ_ERROR_PAUSE_MILLIS = 10L
        private const val JOIN_MILLIS = 1_000L

        /** 电平统计：第一次在 1 秒（50 块）时报，之后每 10 秒（500 块）。 */
        private const val LEVEL_FIRST_CHUNKS = 50L
        private const val LEVEL_EVERY_CHUNKS = 500L

        /** `AudioRecord.ERROR_DEAD_OBJECT`（安卓 7.0 才有这个名字，值在老系统上一样）：录音服务没了，不用再读。 */
        private const val ERROR_DEAD_OBJECT = -6
    }
}
