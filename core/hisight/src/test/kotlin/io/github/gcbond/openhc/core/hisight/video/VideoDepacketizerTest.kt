package io.github.gcbond.openhc.core.hisight.video

import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.common.toHex
import io.github.gcbond.openhc.core.hisight.AccessUnit
import io.github.gcbond.openhc.core.hisight.FixedRandom
import io.github.gcbond.openhc.core.hisight.Vectors
import io.github.gcbond.openhc.core.hisight.VideoGap
import io.github.gcbond.openhc.core.hisight.arr
import io.github.gcbond.openhc.core.hisight.crypto.AesCtr
import io.github.gcbond.openhc.core.hisight.hex
import io.github.gcbond.openhc.core.hisight.int
import io.github.gcbond.openhc.core.hisight.isNull
import io.github.gcbond.openhc.core.hisight.long
import io.github.gcbond.openhc.core.hisight.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VideoDepacketizerTest {
    private class Frame(val es: ByteArray, val ts: Long, val first: Int, val last: Int, val pts: Long, val nal: List<Int>)

    private fun collector(into: MutableList<Frame>) = { unit: AccessUnit ->
        into += Frame(unit.copyBytes(), unit.rtpTimestamp, unit.firstSequence, unit.lastSequence, unit.pts90k, unit.nalTypes().toList())
    }

    /**
     * 每个样例的每种切分都与参考实现逐项对照：交付的 ES、时间戳、序号、PTS、NAL 类型。
     * 出错时两边都必须失败；本实现是流式解析，可能比参考实现（整包到齐才检查）更早一个分片发现错误，但不会更晚。
     * 参考实现把序号跳变判为失败，本实现不判（手机发送端拥塞时会整批丢帧，2026-11-22 实车），
     * 那几组（[GAP_CASES]）不对照，改由下面的断档用例覆盖。
     */
    @Test
    fun casesMatchPrototype() {
        val cases = Vectors.section("video") as JsonArray
        assertTrue(cases.size > 30)
        assertEquals(GAP_CASES, cases.map { it.jsonObject.str("name") }.filter { it in GAP_CASES }.toSet(), "断档样例的名字")
        for (element in cases) {
            val case = element.jsonObject
            if (case.str("name") in GAP_CASES) continue
            val stream = case.getValue("stream").hex()
            val expectedFrames = case.arr("frames").map { it.jsonObject }
            val finishError = (case["finishError"] as? JsonPrimitive)?.booleanOrNull
            for (splitElement in case.arr("splits")) {
                val split = splitElement.jsonObject
                val label = "${case.str("name")} 切分 ${split["split"]}"
                val chunks = chunksOf(stream, split)
                val frames = ArrayList<Frame>()
                val video = VideoDepacketizer(Vectors.key, collector(frames))
                var errorAt = -1
                for ((i, chunk) in chunks.withIndex()) {
                    try {
                        video.feed(chunk, 0, chunk.size)
                    } catch (e: MalformedMessageException) {
                        errorAt = i
                        break
                    }
                }
                val referenceErrorAt = split.int("errorAt")
                if (referenceErrorAt >= 0) {
                    assertTrue(errorAt in 0..referenceErrorAt, "$label：参考实现在第 $referenceErrorAt 片报错，本实现在 $errorAt")
                    assertFailsWith<MalformedMessageException> { video.feed(ByteArray(1), 0, 1) }
                } else if (errorAt >= 0) {
                    // 参考实现分片都收下了，只在结束检查时失败（例如包长不足 12 字节但数据没到齐）。
                    assertEquals(true, finishError, "$label：本实现在第 $errorAt 片报错，参考实现没有")
                } else {
                    val finished = try {
                        video.finish()
                        true
                    } catch (e: MalformedMessageException) {
                        false
                    }
                    assertEquals(finishError == false, finished, "$label：结束检查")
                }
                assertEquals(expectedFrames.size, frames.size, "$label：帧数")
                for ((expected, actual) in expectedFrames.zip(frames)) compareFrame(expected, actual, label)
                if (referenceErrorAt < 0 && errorAt < 0) assertEquals(case.long("packets"), video.packetsReceived, label)
            }
        }
    }

    private fun chunksOf(stream: ByteArray, split: JsonObject): List<ByteArray> {
        val spec = split.getValue("split")
        if (spec is JsonPrimitive) return stream.map { byteArrayOf(it) }
        var pos = 0
        return (spec as JsonArray).map { size ->
            val n = size.jsonPrimitive.int
            stream.copyOfRange(pos, pos + n).also { pos += n }
        }
    }

    private fun compareFrame(expected: JsonObject, actual: Frame, label: String) {
        assertEquals(expected.int("esLength"), actual.es.size, label)
        if (!expected.isNull("es")) assertEquals(expected.str("es"), actual.es.toHex(), label)
        assertEquals(expected.str("esSha256"), MessageDigest.getInstance("SHA-256").digest(actual.es).toHex(), label)
        assertEquals(expected.long("ts"), actual.ts, label)
        assertEquals(expected.int("seqStart"), actual.first, label)
        assertEquals(expected.int("seqEnd"), actual.last, label)
        assertEquals(expected.long("pts"), actual.pts, label)
        assertEquals(expected.arr("nal").map { it.jsonPrimitive.int }, actual.nal, label)
    }

    /** 超过初始缓冲的大帧：缓冲按需扩大，回调里的数据与原文一致，且只在回调期间有效。 */
    @Test
    fun largeFrameGrowsBuffersAndReusesUnit() {
        val key = ByteArray(16) { it.toByte() }
        val es = ByteArray(700_000) { (it * 7 + 1).toByte() }
        es[0] = 0; es[1] = 0; es[2] = 0; es[3] = 1; es[4] = 0x65
        val iv = ByteArray(16) { (0xA0 + it).toByte() }
        val sealed = AesCtr(key).seal(es, FixedRandom(listOf(iv)))
        val pes = byteArrayOf(0, 0, 1, 0xE0.toByte(), 0, 0, 0x84.toByte(), 0x80.toByte(), 5, 0x21, 0, 1, 0, 1) + sealed
        val stream = java.io.ByteArrayOutputStream()
        var seq = 64990
        var pos = 0
        while (pos < pes.size) {
            val n = minOf(60_000, pes.size - pos)
            val last = pos + n == pes.size
            val rtp = byteArrayOf(0x80.toByte(), (96 or (if (last) 0x80 else 0)).toByte(), (seq ushr 8).toByte(), seq.toByte(),
                0, 0, 0x0B, 0xB8.toByte(), 1, 2, 3, 4) + pes.copyOfRange(pos, pos + n)
            stream.write(byteArrayOf((rtp.size ushr 24).toByte(), (rtp.size ushr 16).toByte(), (rtp.size ushr 8).toByte(), rtp.size.toByte()))
            stream.write(rtp)
            seq = if (seq == 64999) 0 else seq + 1
            pos += n
        }
        val bytes = stream.toByteArray()
        var seen: AccessUnit? = null
        var delivered: ByteArray? = null
        val video = VideoDepacketizer(key) { unit ->
            seen = unit
            delivered = unit.copyBytes()
            assertEquals(listOf(5), unit.nalTypes().toList())
        }
        var offset = 0
        while (offset < bytes.size) {
            val n = minOf(65536, bytes.size - offset)
            video.feed(bytes, offset, n)
            offset += n
        }
        video.finish()
        assertContentEquals(es, delivered)
        assertEquals(1L, video.framesDelivered)
        assertEquals(3000L, seen!!.rtpTimestamp)
        assertEquals(0L, seen!!.pts90k)
    }

    @Test
    fun accessUnitHelpers() {
        val unit = AccessUnit()
        unit.data = byteArrayOf(9, 0, 0, 1, 0x67, 0, 0, 0, 0, 1, 0x68, 0, 0, 1)
        unit.offset = 1
        unit.length = 13
        assertEquals(listOf(7, 8), unit.nalTypes().toList())
        unit.pts90k = 90_000
        assertEquals(1_000_000L, unit.presentationTimeUs)
        assertContentEquals(unit.data.copyOfRange(1, 14), unit.copyBytes())
    }

    // ---------------------------------------------------------------- 断档（RTP 序号跳变）

    private val key = ByteArray(16) { it.toByte() }

    /**
     * 一帧的 RTP 包（每个都带 4 字节包长）：ES 以起始码和 NAL 头 [nal] 开头，加密后加 PES 头，切成 [count] 个包，
     * 序号从 [firstSeq] 起（64999 之后回到 0），最后一个带 marker。
     */
    private fun frame(nal: Int, firstSeq: Int, ts: Long, count: Int = 3, esSize: Int = 120): List<ByteArray> {
        // 相邻字节差 13，不会出现两个连续的 0，ES 里只有开头一个起始码。
        val es = ByteArray(esSize) { (it * 13 + nal).toByte() }
        es[0] = 0; es[1] = 0; es[2] = 0; es[3] = 1; es[4] = nal.toByte()
        val iv = ByteArray(16) { (ts.toInt() + it).toByte() }
        val pes = byteArrayOf(0, 0, 1, 0xE0.toByte(), 0, 0, 0x84.toByte(), 0x80.toByte(), 5, 0x21, 0, 1, 0, 1) +
            AesCtr(key).seal(es, FixedRandom(listOf(iv)))
        val size = (pes.size + count - 1) / count
        var seq = firstSeq
        return (0 until count).map { i ->
            val body = pes.copyOfRange(i * size, minOf(pes.size, (i + 1) * size))
            val marker = if (i == count - 1) 0x80 else 0
            val rtp = byteArrayOf(0x80.toByte(), (96 or marker).toByte(), (seq ushr 8).toByte(), seq.toByte(),
                (ts ushr 24).toByte(), (ts ushr 16).toByte(), (ts ushr 8).toByte(), ts.toByte(), 1, 2, 3, 4) + body
            seq = if (seq == 64999) 0 else seq + 1
            byteArrayOf((rtp.size ushr 24).toByte(), (rtp.size ushr 16).toByte(), (rtp.size ushr 8).toByte(), rtp.size.toByte()) + rtp
        }
    }

    private class Got(val nal: List<Int>, val first: Int, val gap: VideoGap?)

    private fun run(packets: List<ByteArray>): Pair<VideoDepacketizer, List<Got>> {
        val got = ArrayList<Got>()
        val video = VideoDepacketizer(key) { got += Got(it.nalTypes().toList(), it.firstSequence, it.gapBefore) }
        for (p in packets) video.feed(p, 0, p.size)
        video.finish()
        return video to got
    }

    @Test
    fun senderDroppingWholeFramesResumesAtTheNextFrame() {
        // 手机拥塞时把排队的普通帧整批丢掉、直接发关键帧：车机这边序号从 103 跳到 106。
        val (video, got) = run(frame(5, 100, 3000) + frame(5, 106, 9000) + frame(1, 109, 12000))
        assertEquals(listOf(listOf(5), listOf(5), listOf(1)), got.map { it.nal })
        assertEquals(null, got[0].gap)
        val gap = got[1].gap!!
        assertEquals(103, gap.expectedSequence)
        assertEquals(106, gap.receivedSequence)
        assertEquals(1, gap.jumps)
        assertEquals(3L, gap.packetsLost)
        assertEquals(0L, gap.packetsDiscarded)
        assertEquals(null, got[2].gap, "断档只记在接上后的第一帧上")
        assertEquals(1L, video.sequenceGaps)
        assertEquals(3L, video.packetsLost)
        assertEquals(3L, video.framesDelivered)
    }

    @Test
    fun gapInsideAFrameDropsThePartialFrameAndMidFramePackets() {
        val middle = frame(1, 103, 6000)
        // 第二帧中间的包（104）丢了：拼了一半的 103 丢掉，105 在帧中间（带 marker 也不交），从下一帧开头接上。
        val (video, got) = run(frame(5, 100, 3000) + listOf(middle[0], middle[2]) + frame(1, 106, 9000))
        assertEquals(listOf(100, 106), got.map { it.first })
        val gap = got[1].gap!!
        assertEquals(104, gap.expectedSequence)
        assertEquals(105, gap.receivedSequence)
        assertEquals(1L, gap.packetsLost)
        assertEquals(2L, gap.packetsDiscarded)
        assertEquals(2L, video.packetsDiscarded)
    }

    @Test
    fun duplicatePacketIsAGapButNotALoss() {
        val first = frame(5, 100, 3000)
        val (video, got) = run(first + listOf(first[2]) + frame(1, 103, 6000))
        assertEquals(listOf(100, 103), got.map { it.first })
        val gap = got[1].gap!!
        assertEquals(103, gap.expectedSequence)
        assertEquals(102, gap.receivedSequence)
        assertEquals(0L, gap.packetsLost)
        assertEquals(1L, gap.packetsDiscarded)
        assertEquals(0L, video.packetsLost)
    }

    @Test
    fun sequenceWrapsAfter64999WithoutAGap() {
        val (video, got) = run(frame(5, 64998, 3000) + frame(1, 1, 6000))
        assertEquals(listOf(64998, 1), got.map { it.first })
        assertEquals(listOf(null, null), got.map { it.gap })
        assertEquals(0L, video.sequenceGaps)
    }

    @Test
    fun wrapAt65535IsAGapNotAFailure() {
        // 协议在 64999 之后回到 0；真碰上 65535 之后回到 0，只当断档，后面的帧照收。
        val (_, got) = run(frame(5, 65534, 3000, count = 2) + frame(1, 0, 6000))
        assertEquals(listOf(65534, 0), got.map { it.first })
        assertEquals(0L, got[1].gap!!.packetsLost)
    }

    @Test
    fun otherErrorsAreStillFatal() {
        // 序号接着、同一帧里时间戳却变了：不是断档，照旧判失败。
        val a = frame(5, 100, 3000)
        val b = frame(5, 100, 6000)
        val video = VideoDepacketizer(key) {}
        video.feed(a[0], 0, a[0].size)
        assertFailsWith<MalformedMessageException> { video.feed(b[1], 0, b[1].size) }
    }

    private companion object {
        /** 参考实现判为失败、本实现当断档处理的样例。 */
        val GAP_CASES = setOf("loss", "duplicate", "wrap-65536", "loss-between-frames")
    }
}
