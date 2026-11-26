package io.github.gcbond.openhc.platform.media

import org.junit.Test
import java.nio.ByteBuffer
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 解码链缓存：段首清空、追加、按顺序补送、重放、超限作废、复制隔离、扩容。数据都是合成的。 */
class FrameCacheTest {

    /** 一个「帧」：[size] 字节，内容由 [tag] 决定，方便比对。 */
    private fun frame(tag: Int, size: Int = 8): ByteArray = ByteArray(size) { (tag * 31 + it).toByte() }

    private fun FrameCache.bytes(seq: Long): ByteArray {
        val out = ByteBuffer.allocate(length(seq))
        copyTo(seq, out)
        return out.array()
    }

    private fun FrameCache.add(data: ByteArray, nowMs: Long = 0, config: Boolean = false): Long {
        val seq = endSeq
        assertEquals(FrameCache.APPENDED, append(data, 0, data.size, config, nowMs))
        return seq
    }

    /** 依次送掉所有待送条目，返回送的顺序。 */
    private fun FrameCache.feedAll(nowMs: Long = 0): List<Long> {
        val fed = ArrayList<Long>()
        while (true) {
            val seq = nextToFeed()
            if (seq < 0) return fed
            fed += seq
            markFed(nowMs)
        }
    }

    @Test
    fun keyFrameWaitingForInputIsKeptAndFedFirstThenFollowingFramesInOrder() {
        // 刚建好的解码器还没给输入缓冲：关键帧和紧跟着的帧都留着，不丢，拿到缓冲后按到达顺序补送。
        val cache = FrameCache(maxBytes = 1024, maxFrames = 16)
        cache.startChain()
        val key = cache.add(frame(1, 40), nowMs = 0)
        val p1 = cache.add(frame(2), nowMs = 9)
        val p2 = cache.add(frame(3), nowMs = 25)
        assertEquals(3, cache.pendingEntries)
        assertEquals(key, cache.nextToFeed())
        assertFalse(cache.isReplay(key), "第一次送，不是重放")
        assertEquals(500, cache.inputWaitMs(500), "从关键帧到达起算")

        assertEquals(listOf(key, p1, p2), cache.feedAll(nowMs = 600))
        assertEquals(0, cache.inputWaitMs(700), "都送完了就不算在等")
        assertContentEquals(frame(1, 40), cache.bytes(key))
        assertContentEquals(frame(3), cache.bytes(p2))
    }

    @Test
    fun inputWaitRestartsWhenTheDecoderTakesOne() {
        val cache = FrameCache(maxBytes = 1024, maxFrames = 16)
        cache.startChain()
        cache.add(frame(1), nowMs = 100)
        cache.add(frame(2), nowMs = 150)
        assertEquals(1_900, cache.inputWaitMs(2_000))
        cache.markFed(2_000)
        // 第二帧早就到了，但解码器刚收了一个，没卡住：从现在重新算。
        assertEquals(50, cache.inputWaitMs(2_050))
    }

    @Test
    fun newKeyFrameClearsTheChainAndCountsUnsentFramesAsLost() {
        val cache = FrameCache(maxBytes = 1024, maxFrames = 16)
        cache.startChain()
        val oldKey = cache.add(frame(1))
        cache.markFed(0)
        cache.add(frame(2))
        cache.add(frame(3), config = true)
        cache.add(frame(4))
        // 两帧没送（参数集不算），作废后算丢帧。
        assertEquals(2, cache.startChain())
        assertEquals(0, cache.keptEntries)
        assertEquals(-1, cache.nextToFeed())
        val newKey = cache.add(frame(5))
        assertTrue(newKey > oldKey, "序号接着往上数")
        assertFailsWith<IllegalArgumentException> { cache.length(oldKey) }
        assertTrue(cache.historyComplete)
        assertContentEquals(frame(5), cache.bytes(newKey))
    }

    @Test
    fun copiesAreIsolatedFromTheCallersBuffer() {
        val cache = FrameCache(maxBytes = 1024, maxFrames = 16)
        cache.startChain()
        val reused = frame(7, 32)
        val seq = cache.endSeq
        cache.append(reused, 4, 20, config = false, nowMs = 0)
        reused.fill(0) // 调用方复用缓冲，覆盖掉原来的内容
        assertContentEquals(frame(7, 32).copyOfRange(4, 24), cache.bytes(seq))
    }

    @Test
    fun rewindReplaysDeliveredEntriesThenContinuesWithNewOnes() {
        val cache = FrameCache(maxBytes = 1024, maxFrames = 16)
        cache.startChain()
        val config = cache.endSeq
        cache.append(frame(0, 4), 0, 4, config = true, nowMs = 0, delivered = true)
        val key = cache.add(frame(1))
        val p1 = cache.add(frame(2))
        val midConfig = cache.add(frame(3), config = true)
        cache.feedAll()
        val p2 = cache.add(frame(4)) // 重放之前到的、还没送的
        // 已经送过的帧：关键帧和 p1（参数集不算），最后一帧是 p1。
        assertEquals(2, cache.replayableFrames)
        assertEquals(2, cache.rewind(nowMs = 1_000))
        assertEquals(p1, cache.replayLastSeq)
        assertEquals(0, cache.inputWaitMs(1_000), "重放从现在开始等")

        val p3 = cache.add(frame(5)) // 重放期间到的新帧排在后面
        val order = ArrayList<Pair<Long, Boolean>>()
        while (cache.nextToFeed() >= 0) {
            val seq = cache.nextToFeed()
            order += seq to cache.isReplay(seq)
            cache.markFed(0)
        }
        assertEquals(
            listOf(config to true, key to true, p1 to true, midConfig to true, p2 to false, p3 to false),
            order,
            "先按原顺序重放送过的，再接着送没送过的",
        )
        // 送完以后 p2、p3 也送过一遍了，下次重放会带上它们。
        assertEquals(4, cache.replayableFrames)
    }

    @Test
    fun replayStartsWithTheSavedParameterSets() {
        val cache = FrameCache(maxBytes = 1024, maxFrames = 16)
        cache.startChain()
        val config = cache.endSeq
        cache.append(frame(0, 4), 0, 4, config = true, nowMs = 0, delivered = true)
        assertEquals(-1, cache.nextToFeed(), "记为已送的参数集平时不送")
        val key = cache.add(frame(1))
        assertEquals(0, cache.replayableFrames, "关键帧还没送，没有能重放的")
        assertEquals(key, cache.nextToFeed())
        cache.markFed(0)
        assertEquals(1, cache.rewind(0))
        assertEquals(key, cache.replayLastSeq)
        assertEquals(config, cache.nextToFeed(), "flush 后先送参数集")
        assertTrue(cache.isConfig(config))
    }

    @Test
    fun appendingAsDeliveredNeedsAnEmptyQueue() {
        val cache = FrameCache(maxBytes = 1024, maxFrames = 16)
        cache.startChain()
        cache.add(frame(1))
        assertFailsWith<IllegalArgumentException> {
            cache.append(frame(2), 0, 8, config = true, nowMs = 0, delivered = true)
        }
    }

    @Test
    fun overFrameLimitDropsHistoryButKeepsWhatIsStillToBeSent() {
        val cache = FrameCache(maxBytes = 1024, maxFrames = 4)
        cache.startChain()
        repeat(4) { cache.add(frame(it)) }
        cache.markFed(0)
        cache.markFed(0)
        // 第 5 个放不下：丢掉已经送过的两个，没送的两个和新来的留着。
        val fifth = cache.endSeq
        assertEquals(FrameCache.APPENDED_HISTORY_DROPPED, cache.append(frame(9), 0, 8, false, 0))
        assertFalse(cache.historyComplete)
        assertEquals(-1, cache.replayableFrames)
        assertEquals(-1, cache.rewind(0), "作废后不能重放")
        assertEquals(3, cache.keptEntries)
        assertEquals(3, cache.feedAll().size)
        assertEquals(0, cache.keptEntries, "作废以后送过的随即丢掉")
        assertFailsWith<IllegalArgumentException> { cache.length(fifth) }
        // 下一个关键帧重新开始，又能重放了。
        cache.startChain()
        cache.add(frame(10))
        cache.markFed(0)
        assertEquals(1, cache.replayableFrames)
    }

    @Test
    fun overByteLimitDropsHistory() {
        val cache = FrameCache(maxBytes = 100, maxFrames = 16, initialBytes = 16)
        cache.startChain()
        cache.add(frame(1, 60))
        cache.markFed(0)
        assertEquals(FrameCache.APPENDED_HISTORY_DROPPED, cache.append(frame(2, 50), 0, 50, false, 0))
        assertEquals(50, cache.keptBytes)
        assertEquals(1, cache.pendingEntries)
        assertContentEquals(frame(2, 50), cache.bytes(cache.nextToFeed()))
    }

    @Test
    fun rejectsWhenUnsentEntriesAloneExceedTheLimit() {
        val cache = FrameCache(maxBytes = 1024, maxFrames = 2)
        cache.startChain()
        cache.add(frame(1))
        cache.add(frame(2))
        assertEquals(FrameCache.REJECTED, cache.append(frame(3), 0, 8, false, 0))
        assertEquals(2, cache.pendingEntries, "放不下的不追加，已有的不动")
        assertTrue(cache.historyComplete)
        val big = FrameCache(maxBytes = 10, maxFrames = 8)
        big.startChain()
        assertEquals(FrameCache.REJECTED, big.append(frame(1, 11), 0, 11, false, 0), "单个条目超过字节上限")
    }

    @Test
    fun storageGrowsOnDemandAndIsReusedAcrossChains() {
        val cache = FrameCache(maxBytes = 1 shl 16, maxFrames = 64, initialBytes = 16)
        assertEquals(0, cache.capacity, "第一次写入才分配")
        cache.startChain()
        val seqs = (0 until 10).map { cache.add(frame(it, 100)) }
        assertTrue(cache.capacity >= 1000)
        assertTrue(cache.capacity <= 2048, "按需翻倍")
        seqs.forEachIndexed { i, seq -> assertContentEquals(frame(i, 100), cache.bytes(seq)) }
        val grown = cache.capacity
        cache.startChain()
        repeat(10) { cache.add(frame(it, 100)) }
        assertEquals(grown, cache.capacity, "新的一段复用原来的数组")
        cache.release()
        assertEquals(0, cache.capacity)
    }

    @Test
    fun withoutHistoryTheQueueCompactsAndKeepsPendingBytesIntact() {
        // 超限以后只当队列用：前面送掉的空出来，写到数组末尾时把剩下的挪到开头。
        val cache = FrameCache(maxBytes = 64, maxFrames = 8, initialBytes = 64)
        cache.startChain()
        repeat(6) { cache.add(frame(it, 10)) }
        cache.markFed(0)
        assertEquals(FrameCache.APPENDED_HISTORY_DROPPED, cache.append(frame(6, 10), 0, 10, false, 0))
        var tag = 7
        repeat(20) {
            val next = cache.nextToFeed()
            cache.markFed(0)
            assertTrue(next >= 0)
            assertEquals(FrameCache.APPENDED, cache.append(frame(tag, 10), 0, 10, false, 0))
            tag++
        }
        assertEquals(64, cache.capacity, "只挪不扩")
        val remaining = ArrayList<ByteArray>()
        while (cache.nextToFeed() >= 0) {
            remaining += cache.bytes(cache.nextToFeed())
            cache.markFed(0)
        }
        assertEquals(6, remaining.size)
        remaining.forEachIndexed { i, bytes -> assertContentEquals(frame(tag - 6 + i, 10), bytes) }
    }

    @Test
    fun clearStopsCachingAndReportsUnsentFrames() {
        val cache = FrameCache(maxBytes = 1024, maxFrames = 16)
        cache.startChain()
        cache.add(frame(1))
        cache.markFed(0)
        cache.add(frame(2))
        cache.add(frame(3))
        assertEquals(2, cache.clear())
        assertFalse(cache.active)
        assertEquals(-1, cache.replayableFrames)
        assertEquals(-1, cache.rewind(0))
        assertFailsWith<IllegalStateException> { cache.append(frame(4), 0, 8, false, 0) }
    }
}
