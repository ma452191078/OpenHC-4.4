package io.github.gcbond.openhc.platform.media

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 麦克风上行里不依赖安卓的部分：流式重采样、单声道转交错字节、ADTS 头。 */
class MicPcmTest {

    private fun ramp(from: Int, frames: Int, step: Int) = ShortArray(frames) { ((from + it) * step).toShort() }

    /** 把若干块依次送进重采样器，拼起全部输出。 */
    private fun resampleAll(resampler: PcmResampler, blocks: List<ShortArray>): List<Short> {
        val all = ArrayList<Short>()
        for (block in blocks) {
            val out = ShortArray(resampler.maxOutputFrames(block.size))
            val n = resampler.process(block, block.size, out)
            assertTrue(n <= out.size, "输出不超过预估的上限")
            for (i in 0 until n) all += out[i]
        }
        return all
    }

    @Test
    fun sameRateIsPassedThrough() {
        val resampler = PcmResampler(16_000, 16_000)
        val block = ramp(0, 320, 7)
        val out = ShortArray(resampler.maxOutputFrames(320))
        assertEquals(320, resampler.process(block, 320, out))
        assertContentEquals(block, out.copyOf(320))
    }

    /** 48kHz → 16kHz：每块 960 帧正好出 320 帧，取每 3 个采样里的第一个，跨块不丢不重。 */
    @Test
    fun downsamplingByThreeKeepsEveryThirdSampleAcrossBlocks() {
        val resampler = PcmResampler(48_000, 16_000)
        val blocks = (0 until 5).map { ramp(it * 960, 960, 3) }
        val out = resampleAll(resampler, blocks)
        assertEquals(5 * 320, out.size)
        out.forEachIndexed { k, v -> assertEquals((k * 3 * 3).toShort(), v, "第 $k 个输出") }
    }

    /** 16kHz → 48kHz：线性插值，跨块用上一块最后一个采样接上，整条斜坡连续。 */
    @Test
    fun upsamplingInterpolatesContinuouslyAcrossBlocks() {
        val resampler = PcmResampler(16_000, 48_000)
        val blocks = (0 until 4).map { ramp(it * 320, 320, 3) }
        val out = resampleAll(resampler, blocks)
        // 输入第 i 帧是 3i，输出第 k 个落在 k/3 帧处，值正好是 k。
        out.forEachIndexed { k, v -> assertEquals(k.toShort(), v, "第 $k 个输出") }
        assertEquals(3 * 4 * 320 - 2, out.size, "最后一帧之后的两个点要等下一块")
    }

    @Test
    fun oddRatioProducesTheExactAverageCount() {
        val resampler = PcmResampler(44_100, 16_000)
        val blocks = (0 until 50).map { ShortArray(882) }
        val out = resampleAll(resampler, blocks)
        val exact = 50L * 882 * 16_000 / 44_100
        assertTrue(out.size.toLong() in exact - 1..exact + 1, "1 秒的输入出约 16000 帧，实际 ${out.size}")
        assertFailsWith<IllegalArgumentException> { PcmResampler(1_000, 16_000) }
    }

    @Test
    fun monoIsWrittenAsLittleEndianAndDuplicatedForStereo() {
        val mono = shortArrayOf(0x0102, -2)
        val one = ByteArray(4)
        assertEquals(4, PcmResampler.toLittleEndian(mono, 2, 1, one, 0))
        assertContentEquals(byteArrayOf(0x02, 0x01, 0xFE.toByte(), 0xFF.toByte()), one)
        val two = ByteArray(10)
        assertEquals(8, PcmResampler.toLittleEndian(mono, 2, 2, two, 2))
        assertContentEquals(
            byteArrayOf(0, 0, 0x02, 0x01, 0x02, 0x01, 0xFE.toByte(), 0xFF.toByte(), 0xFE.toByte(), 0xFF.toByte()),
            two,
        )
    }

    /** 48kHz 立体声、100 字节载荷：与手机发来的 ADTS 头同一形状（缓冲满度 0x7FF、单个原始数据块）。 */
    @Test
    fun adtsHeaderMatchesTheStandardLayout() {
        val frame = AdtsWriter.wrap(ByteArray(100) { 0x33 }, 0, 100, 48_000, 2)
        assertContentEquals(
            byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x4C, 0x80.toByte(), 0x0D, 0x7F, 0xFC.toByte()),
            frame.copyOf(7),
        )
        val parsed = assertNotNull(AacConfig.parseAdts(frame, 0, frame.size))
        assertEquals(AacConfig.OBJECT_TYPE_LC, parsed.objectType)
        assertEquals(48_000, parsed.sampleRate)
        assertEquals(2, parsed.channelConfig)
        assertEquals(107, parsed.frameLength)
        assertEquals(7, parsed.headerLength)

        val mono16k = AdtsWriter.wrap(ByteArray(300), 0, 300, 16_000, 1)
        val parsedMono = assertNotNull(AacConfig.parseAdts(mono16k, 0, mono16k.size))
        assertEquals(16_000, parsedMono.sampleRate)
        assertEquals(1, parsedMono.channelConfig)
        assertEquals(307, parsedMono.frameLength)
        assertFailsWith<IllegalArgumentException> { AdtsWriter.wrap(ByteArray(10), 0, 10, 12_345, 1) }
        assertFailsWith<IllegalArgumentException> { AdtsWriter.wrap(ByteArray(8186), 0, 8186, 48_000, 2) }
    }
}
