package io.github.gcbond.openhc.platform.media

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VideoStatsAndPolicyTest {

    @Test
    fun statsWindowComputesRatesAndResets() {
        val counter = VideoStatsCounter()
        counter.start(10_000)
        repeat(60) { counter.onInput() }
        repeat(58) { counter.onQueued(nowMs = 10_000L + it * 17) }
        repeat(2) { counter.onDropped() }
        // 出帧 55 帧，延迟 10～20 毫秒；还有 3 帧在解码器里。
        for (i in 0 until 55) counter.onDecoded(latencyUs = 10_000L + (i % 11) * 1_000L)
        repeat(50) { counter.onRendered() }

        val first = counter.snapshot(11_000, 1920, 1080, "c2.test.avc.decoder", waitingForKeyFrame = false)
        assertEquals(1000, first.windowMs)
        assertEquals(60f, first.inputFps)
        assertEquals(55f, first.decodedFps)
        assertEquals(50f, first.renderedFps)
        assertEquals(2, first.dropped)
        assertEquals(15f, first.avgDecodeLatencyMs)
        assertEquals(20f, first.maxDecodeLatencyMs)
        assertEquals(3, first.pendingFrames)
        assertEquals(1920, first.width)

        // 第二个窗口 2 秒、只有输入：帧率按实际时长折算，累计值保留。
        repeat(30) { counter.onInput() }
        val second = counter.snapshot(13_000, 1920, 1080, null, waitingForKeyFrame = true)
        assertEquals(15f, second.inputFps)
        assertEquals(0f, second.decodedFps)
        assertEquals(0f, second.renderedFps, "系统回报过上屏，这个窗口没上屏就是 0")
        assertEquals(-1f, second.avgDecodeLatencyMs)
        assertEquals(-1f, second.maxDecodeLatencyMs)
        assertEquals(90L, second.totalInput)
        assertEquals(55L, second.totalDecoded)
        assertEquals(2L, second.totalDropped)
        assertTrue(second.toString().contains("等关键帧"))
    }

    @Test
    fun renderedFpsIsUnknownUntilSystemReports() {
        val counter = VideoStatsCounter()
        counter.start(0)
        counter.onQueued(nowMs = 5)
        counter.onDecoded(5_000)
        assertEquals(-1f, counter.snapshot(1000, 0, 0, null, false).renderedFps)
    }

    @Test
    fun implausibleLatencyIsNotAveraged() {
        val counter = VideoStatsCounter()
        counter.start(0)
        counter.onDecoded(latencyUs = -5)
        counter.onDecoded(latencyUs = 60_000_000)
        counter.onDecoded(latencyUs = 8_000)
        val stats = counter.snapshot(1000, 0, 0, null, false)
        assertEquals(8f, stats.avgDecodeLatencyMs)
        assertEquals(3f, stats.decodedFps)
    }

    @Test
    fun stallBookkeeping() {
        val counter = VideoStatsCounter()
        counter.start(0)
        repeat(9) { counter.onQueued(nowMs = 100L + it) }
        assertEquals(9, counter.inputsSinceOutput)
        assertEquals(100, counter.outputWaitStartMs, "从出帧后第一次送帧算起")
        counter.onDecoded(1_000)
        assertEquals(0, counter.inputsSinceOutput)
        // 很久没送帧（画面不变）之后再送：从这一次送帧重新算，不从上次出帧算。
        counter.onQueued(nowMs = 60_000)
        assertEquals(60_000, counter.outputWaitStartMs)
        counter.onQueued(nowMs = 60_010)
        assertEquals(60_000, counter.outputWaitStartMs)
        counter.onDecoderReset(70_000)
        assertEquals(0, counter.inputsSinceOutput, "换了解码器重新计")
        assertEquals(70_000, counter.outputWaitStartMs)
        assertEquals(0, counter.snapshot(71_000, 0, 0, null, false).pendingFrames)
    }

    @Test
    fun replayedFramesAreCountedSeparately() {
        val counter = VideoStatsCounter()
        counter.start(0)
        repeat(30) { counter.onInput() }
        repeat(30) { counter.onQueued(nowMs = 10) }
        repeat(30) { counter.onDecoded(5_000) }
        // 切回后重放 40 帧：不算输入、解码、积压，但算卡死判断里的「送进去了」。
        repeat(40) { counter.onReplayQueued(nowMs = 500) }
        assertEquals(40, counter.inputsSinceOutput)
        assertEquals(500, counter.outputWaitStartMs)
        repeat(40) { counter.onReplayDecoded() }
        assertEquals(0, counter.inputsSinceOutput)
        counter.onDropped(3)
        val stats = counter.snapshot(1000, 1920, 1080, "c2.test.avc.decoder", false, waitingForInput = 2)
        assertEquals(30f, stats.inputFps)
        assertEquals(30f, stats.decodedFps)
        assertEquals(40, stats.replayed)
        assertEquals(0, stats.pendingFrames)
        assertEquals(3, stats.dropped)
        assertEquals(2, stats.waitingForInput)
        assertTrue(stats.toString().contains("重放 40"))
        assertTrue(stats.toString().contains("待送 2"))
        assertEquals(0, counter.snapshot(2000, 0, 0, null, false).replayed, "新窗口清零")
    }

    @Test
    fun pacingAndArrivalJitterGoOnTheSecondLine() {
        val counter = VideoStatsCounter()
        counter.start(0)
        // 手机时间戳每 16.667ms 一帧，到达间隔依次是 15、18、16.667ms。
        counter.onArrival(nowNs = 1_000_000_000, phonePtsUs = 100_000)
        counter.onArrival(nowNs = 1_015_000_000, phonePtsUs = 116_667)
        counter.onArrival(nowNs = 1_033_000_000, phonePtsUs = 133_334)
        counter.onArrival(nowNs = 1_049_667_000, phonePtsUs = 150_001)
        counter.onArrival(nowNs = 1_060_000_000, phonePtsUs = 150_001) // 时间戳没往前走：不算
        counter.onArrival(nowNs = 1_070_000_000, phonePtsUs = -1) // 没有时间戳：不算
        // 三帧送显（一帧顺延了一个周期），两次「两帧同刷新」，一次顶替，一帧没有刷新网格直接送。
        counter.onPacing(sameLatch = false, replaced = false, unpaced = false)
        counter.onShown(latchDelayNs = 10_000_000, extraNs = 0)
        counter.onPacing(sameLatch = true, replaced = false, unpaced = false)
        counter.onShown(latchDelayNs = 26_000_000, extraNs = 16_666_667)
        counter.onPacing(sameLatch = true, replaced = true, unpaced = false)
        counter.onShown(latchDelayNs = 4_000_000, extraNs = 0)
        counter.onPacing(sameLatch = false, replaced = false, unpaced = true)

        val stats = counter.snapshot(1000, 1920, 1080, "c2.test.avc.decoder", false, framePacing = true, refreshPeriodNs = 16_666_667)
        assertEquals(2, stats.sameLatch)
        assertEquals(1, stats.deferred)
        assertEquals(1, stats.replaced)
        assertEquals(1, stats.unpaced)
        assertEquals(13.333f, stats.avgLatchDelayMs, 0.01f)
        assertEquals(26f, stats.maxLatchDelayMs, 0.01f)
        assertEquals(5.556f, stats.avgExtraDelayMs, 0.01f, "按送显的三帧平均")
        assertEquals(1f, stats.avgArrivalJitterMs, 0.01f)
        assertEquals(1.667f, stats.maxArrivalJitterMs, 0.01f)
        assertEquals(16.667f, stats.minPhoneIntervalMs, 0.01f)
        assertEquals(16.667f, stats.maxPhoneIntervalMs, 0.01f)
        val line = stats.toString().lines()[1]
        assertEquals(
            "上屏节拍 开（刷新 16.67 ms）：两帧同刷新 2 顺延 1 顶替 1 未对齐 1；出帧到合成 均 13.3 最大 26.0 ms，" +
                "节拍多等 均 5.6 ms；到达抖动 均 1.0 最大 1.7 ms，手机间隔 16.7～16.7 ms",
            line,
        )

        // 新窗口清零；节拍关、还没有刷新周期时这样写。
        val next = counter.snapshot(2000, 0, 0, null, false)
        assertEquals(0, next.sameLatch)
        assertEquals(-1f, next.avgLatchDelayMs)
        assertEquals(-1f, next.avgArrivalJitterMs)
        assertEquals("上屏节拍 关（还没拿到刷新时刻）：两帧同刷新 0", next.toString().lines()[1])
    }

    @Test
    fun arrivalAfterALongPauseIsNotCountedAsJitter() {
        val counter = VideoStatsCounter()
        counter.start(0)
        counter.onArrival(nowNs = 1_000_000_000, phonePtsUs = 0)
        // 画面不动时手机停发 3 秒，再发：这一对不算抖动。
        counter.onArrival(nowNs = 4_000_000_000, phonePtsUs = 3_000_000)
        assertEquals(-1f, counter.snapshot(1000, 0, 0, null, false).avgArrivalJitterMs)
        // 之后照常算。
        counter.onArrival(nowNs = 4_020_000_000, phonePtsUs = 3_016_667)
        assertEquals(3.333f, counter.snapshot(2000, 0, 0, null, false).maxArrivalJitterMs, 0.01f)
    }

    @Test
    fun declaredFpsFollowsRefreshRate() {
        assertEquals(55, FrameRatePolicy.declaredFps(55.0f), "55Hz 的屏声明 55")
        assertEquals(55, FrameRatePolicy.declaredFps(54.6f))
        assertEquals(60, FrameRatePolicy.declaredFps(59.94f))
        assertEquals(30, FrameRatePolicy.declaredFps(30f))
        assertEquals(48, FrameRatePolicy.declaredFps(47.95f))
        assertEquals(60, FrameRatePolicy.declaredFps(120f), "高刷屏不超过上限")
        assertEquals(90, FrameRatePolicy.declaredFps(90f, maxFps = 120))
        assertEquals(60, FrameRatePolicy.declaredFps(null), "读不到用默认值")
        assertEquals(60, FrameRatePolicy.declaredFps(Float.NaN))
        assertEquals(60, FrameRatePolicy.declaredFps(0f))
        assertEquals(60, FrameRatePolicy.declaredFps(5000f))
        assertEquals(30, FrameRatePolicy.declaredFps(null, maxFps = 30), "默认值也受上限约束")
        assertFailsWith<IllegalArgumentException> { FrameRatePolicy.declaredFps(60f, maxFps = 0) }
    }

    @Test
    fun vendorKeysByCodecName() {
        assertEquals(
            listOf("vendor.qti-ext-dec-low-latency.enable" to 1, "vendor.qti-ext-dec-picture-order.enable" to 1),
            VendorLowLatency.keys("c2.qti.avc.decoder", decodeOrderAllowed = true),
        )
        assertEquals(
            listOf("vendor.qti-ext-dec-low-latency.enable" to 1),
            VendorLowLatency.keys("OMX.qcom.video.decoder.avc", decodeOrderAllowed = false),
            "有 B 帧时不按解码顺序输出",
        )
        assertEquals(2, VendorLowLatency.keys("OMX.hisi.video.decoder.avc", true).size)
        assertEquals(listOf("vendor.rtc-ext-dec-low-latency.enable" to 1), VendorLowLatency.keys("c2.exynos.h264.decoder", true))
        assertEquals(listOf("vendor.low-latency.enable" to 1), VendorLowLatency.keys("OMX.amlogic.avc.decoder.awesome", true))
        assertEquals(emptyList(), VendorLowLatency.keys("OMX.MTK.VIDEO.DECODER.AVC", true))
        assertEquals(emptyList(), VendorLowLatency.keys("c2.android.avc.decoder", true))
    }
}
