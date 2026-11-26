package io.github.gcbond.openhc.platform.media

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 试听用的提示音（纯逻辑，可在电脑上测）：单声道 16 位，两声短音（低一高），每声首尾淡入淡出免得「咔」一下，
 * 最后补一段静音：放完到释放音轨之间就算掐早了，掐掉的也只是静音。
 */
object PreviewTone {
    const val FIRST_HZ = 784.0
    const val SECOND_HZ = 1046.5
    const val NOTE_MILLIS = 180
    const val GAP_MILLIS = 80
    const val FADE_MILLIS = 10
    const val TAIL_MILLIS = 150

    /** 峰值约为满幅的三分之一，别吓人。 */
    const val PEAK = 11_000

    /** 整段时长（毫秒），含最后的静音。 */
    const val TOTAL_MILLIS = NOTE_MILLIS + GAP_MILLIS + NOTE_MILLIS + TAIL_MILLIS

    fun pcm(sampleRate: Int): ShortArray {
        require(sampleRate in 8_000..192_000) { "采样率超出范围: $sampleRate" }
        fun frames(millis: Int) = (sampleRate.toLong() * millis / 1000).toInt()
        val note = frames(NOTE_MILLIS)
        val gap = frames(GAP_MILLIS)
        val out = ShortArray(note + gap + note + frames(TAIL_MILLIS))
        writeNote(out, 0, note, FIRST_HZ, sampleRate, frames(FADE_MILLIS))
        writeNote(out, note + gap, note, SECOND_HZ, sampleRate, frames(FADE_MILLIS))
        return out
    }

    private fun writeNote(out: ShortArray, start: Int, length: Int, hz: Double, sampleRate: Int, fade: Int) {
        for (i in 0 until length) {
            // 首尾线性淡入淡出：第一帧和最后一帧都是 0。
            val edge = minOf(i, length - 1 - i)
            val gain = if (fade > 0 && edge < fade) edge.toDouble() / fade else 1.0
            out[start + i] = (sin(2 * PI * hz * i / sampleRate) * PEAK * gain).roundToInt().toShort()
        }
    }
}
