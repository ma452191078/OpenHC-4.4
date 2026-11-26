package io.github.gcbond.openhc.platform.media

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 声道选择的纯逻辑：编号范围、各路声音用哪个声道，以及试听提示音。 */
class AudioStreamChoiceTest {

    @Test
    fun onlyOneToTwentyAreStreamNumbersEverythingElseIsAuto() {
        assertEquals(AudioStreamChoice.AUTO, AudioStreamChoice.normalize(0))
        assertEquals(1, AudioStreamChoice.normalize(1))
        assertEquals(14, AudioStreamChoice.normalize(14))
        assertEquals(20, AudioStreamChoice.normalize(20))
        assertEquals(AudioStreamChoice.AUTO, AudioStreamChoice.normalize(21), "超出范围按自动")
        assertEquals(AudioStreamChoice.AUTO, AudioStreamChoice.normalize(-1), "负数按自动")
        assertEquals("自动", AudioStreamChoice.label(AudioStreamChoice.AUTO))
        assertEquals("14 号", AudioStreamChoice.label(14))
    }

    @Test
    fun defaultTuningLeavesEveryUsageOnAuto() {
        val tuning = AudioTuning()
        for (usage in AudioUsage.values()) assertEquals(AudioStreamChoice.AUTO, tuning.streamFor(usage), usage.label)
    }

    @Test
    fun onlyMediaAndNavigationTakeTheChosenStream() {
        val tuning = AudioTuning(mediaStream = 3, navigationStream = 14)
        assertEquals(3, tuning.streamFor(AudioUsage.MEDIA))
        assertEquals(14, tuning.streamFor(AudioUsage.NAVIGATION))
        assertEquals(AudioStreamChoice.AUTO, tuning.streamFor(AudioUsage.ASSISTANT))
        assertEquals(AudioStreamChoice.AUTO, tuning.streamFor(AudioUsage.CALL))
        assertEquals(AudioStreamChoice.AUTO, tuning.streamFor(AudioUsage.RINGTONE))
        assertTrue("音乐声道 3 号" in tuning.toString() && "导航声道 14 号" in tuning.toString(), tuning.toString())
    }

    @Test
    fun tuningRejectsStreamNumbersOutOfRange() {
        assertFailsWith<IllegalArgumentException> { AudioTuning(mediaStream = 21) }
        assertFailsWith<IllegalArgumentException> { AudioTuning(navigationStream = -1) }
    }

    @Test
    fun previewToneIsTwoFadedNotesThenSilence() {
        val rate = 48_000
        val pcm = PreviewTone.pcm(rate)
        fun frames(millis: Int) = rate * millis / 1000
        assertEquals(frames(PreviewTone.TOTAL_MILLIS), pcm.size, "两声 + 间隔 + 结尾静音")
        assertTrue(pcm.all { abs(it.toInt()) <= PreviewTone.PEAK }, "不超过峰值")
        val note = frames(PreviewTone.NOTE_MILLIS)
        val gap = frames(PreviewTone.GAP_MILLIS)
        assertEquals(0, pcm[0].toInt(), "第一声淡入从 0 开始")
        assertEquals(0, pcm[note - 1].toInt(), "第一声淡出到 0")
        assertEquals(0, pcm[note + gap].toInt(), "第二声淡入从 0 开始")
        assertTrue((note until note + gap).all { pcm[it].toInt() == 0 }, "两声之间是静音")
        assertTrue((note + gap + note until pcm.size).all { pcm[it].toInt() == 0 }, "结尾是静音")
        assertTrue((0 until note).maxOf { abs(pcm[it].toInt()) } > PreviewTone.PEAK * 9 / 10, "第一声有声音")
        assertTrue((note + gap until note + gap + note).maxOf { abs(pcm[it].toInt()) } > PreviewTone.PEAK * 9 / 10, "第二声有声音")
    }
}
