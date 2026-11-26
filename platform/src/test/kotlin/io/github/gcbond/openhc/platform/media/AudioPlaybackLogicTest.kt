package io.github.gcbond.openhc.platform.media

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 播放器里不依赖安卓的部分：起播门槛、积压丢旧、欠载重攒、关闭；字节序转换；AAC 的两段配置。 */
class AudioPlaybackLogicTest {

    private fun chunk(frames: Int) = ByteArray(frames * 4)

    @Test
    fun queueHoldsDataUntilTheStartThreshold() {
        val queue = AudioJitterQueue(startFrames = 300, targetFrames = 500, maxFrames = 800)
        val first = chunk(100)
        queue.offer(first, 100)
        queue.offer(chunk(100), 100)
        assertNull(queue.take(0), "攒够 300 帧之前不放")
        assertFalse(queue.isReleasing)
        queue.offer(chunk(100), 100)
        assertTrue(queue.isReleasing)
        assertSame(first, queue.take(0)?.data, "按顺序放出")
        assertEquals(200, queue.bufferedFrames)
        assertNotNull(queue.take(0))
        assertNotNull(queue.take(0))
        assertNull(queue.take(0), "取空了")
        assertTrue(queue.isReleasing, "取空不等于放空：要等播放端报告")
    }

    @Test
    fun backlogBeyondTheLimitDropsTheOldestDownToTarget() {
        val queue = AudioJitterQueue(startFrames = 100, targetFrames = 400, maxFrames = 600)
        val chunks = (0 until 6).map { chunk(100) }
        for (c in chunks) assertEquals(0, queue.offer(c, 100))
        val dropped = queue.offer(chunk(100), 100)
        assertEquals(300, dropped, "700 > 600：丢到 400")
        assertEquals(400, queue.bufferedFrames)
        assertEquals(300, queue.droppedFrames)
        assertSame(chunks[3], queue.take(0)?.data, "最旧的三块被丢掉")
    }

    @Test
    fun oversizedChunkIsKeptEvenAboveTheLimit() {
        val queue = AudioJitterQueue(startFrames = 10, targetFrames = 20, maxFrames = 30)
        assertEquals(0, queue.offer(chunk(50), 50), "只有一块时不丢")
        assertEquals(50, queue.offer(chunk(5), 5))
        assertEquals(5, queue.bufferedFrames)
    }

    @Test
    fun starvationRefillsBeforePlayingAgain() {
        val queue = AudioJitterQueue(startFrames = 200, targetFrames = 300, maxFrames = 400)
        queue.offer(chunk(200), 200)
        assertNotNull(queue.take(0))
        queue.onStarved()
        assertEquals(1, queue.starvations)
        assertFalse(queue.isReleasing)
        queue.offer(chunk(100), 100)
        assertNull(queue.take(0), "重新攒够之前不放")
        queue.offer(chunk(100), 100)
        assertNotNull(queue.take(0))
        queue.onStarved()
        queue.onStarved()
        assertEquals(2, queue.starvations, "已经在攒数据时再报放空不重复计数")
    }

    @Test
    fun takeWaitsForDataAndCloseWakesIt() {
        val queue = AudioJitterQueue(startFrames = 1, targetFrames = 1, maxFrames = 2)
        val producer = Thread {
            Thread.sleep(50)
            queue.offer(chunk(1), 1)
        }
        producer.start()
        assertNotNull(queue.take(5_000), "等到了生产者的数据")
        producer.join()
        val closer = Thread {
            Thread.sleep(50)
            queue.close()
        }
        closer.start()
        val started = System.nanoTime()
        assertNull(queue.take(5_000))
        assertTrue(System.nanoTime() - started < 4_000_000_000L, "关闭会唤醒等待")
        closer.join()
        assertEquals(0, queue.offer(chunk(1), 1), "关闭后不再收")
        assertEquals(0, queue.bufferedFrames)
    }

    @Test
    fun queueRejectsBadThresholds() {
        assertFailsWith<IllegalArgumentException> { AudioJitterQueue(0, 10, 20) }
        assertFailsWith<IllegalArgumentException> { AudioJitterQueue(30, 20, 40) }
        assertFailsWith<IllegalArgumentException> { AudioJitterQueue(10, 50, 40) }
    }

    @Test
    fun pcmCopyKeepsWholeFramesAndSwapsBigEndian() {
        val data = byteArrayOf(9, 0x12, 0x34, 0x56, 0x78, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06)
        assertContentEquals(byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x01, 0x02, 0x03, 0x04), PcmBytes.copyLittleEndian(data, 1, 10, 4, bigEndian = false))
        assertContentEquals(byteArrayOf(0x34, 0x12, 0x78, 0x56, 0x02, 0x01, 0x04, 0x03), PcmBytes.copyLittleEndian(data, 1, 10, 4, bigEndian = true))
        assertEquals(0, PcmBytes.copyLittleEndian(data, 0, 3, 4, bigEndian = false).size, "不足一帧")
        assertFailsWith<IllegalArgumentException> { PcmBytes.copyLittleEndian(data, 8, 4, 4, bigEndian = false) }
    }

    @Test
    fun audioSpecificConfigMatchesTheStandardLayout() {
        assertContentEquals(byteArrayOf(0x11, 0x90.toByte()), AacConfig.audioSpecificConfig(48_000, 2))
        assertContentEquals(byteArrayOf(0x12, 0x10), AacConfig.audioSpecificConfig(44_100, 2))
        assertContentEquals(byteArrayOf(0x11, 0x88.toByte()), AacConfig.audioSpecificConfig(48_000, 1))
        // 不在表里的采样率：索引 15 + 24 位采样率（32001 = 0x007D01）。
        assertContentEquals(
            byteArrayOf(0x17, 0x80.toByte(), 0x3E, 0x80.toByte(), 0x90.toByte()),
            AacConfig.audioSpecificConfig(32_001, 2),
        )
    }

    @Test
    fun adtsHeaderIsParsed() {
        // AAC-LC、48kHz、双声道、无 CRC、帧长 7 + 3。
        val unit = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x4C, 0x80.toByte(), 0x01, 0x5F, 0xFC.toByte(), 1, 2, 3)
        val adts = assertNotNull(AacConfig.parseAdts(unit, 0, unit.size))
        assertEquals(2, adts.objectType)
        assertEquals(48_000, adts.sampleRate)
        assertEquals(2, adts.channelConfig)
        assertEquals(10, adts.frameLength)
        assertEquals(7, adts.headerLength)
        assertContentEquals(byteArrayOf(0x11, 0x90.toByte()), adts.audioSpecificConfig())
        assertNull(AacConfig.parseAdts(unit, 0, 9), "声明的帧长超出数据")
        assertNull(AacConfig.parseAdts(byteArrayOf(1, 2, 3, 4, 5, 6, 7), 0, 7), "没有同步字")
        val withCrc = unit.copyOf().also { it[1] = 0xF0.toByte() }
        assertEquals(9, AacConfig.parseAdts(withCrc, 0, withCrc.size)?.headerLength)
    }
}
