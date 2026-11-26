package io.github.gcbond.openhc.platform.media

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 重放帧的认定、上屏选择，以及卡死、重放时机的判断。 */
class ReplayTest {

    @Test
    fun trackerRecognizesReplayFramesAndTheLastOne() {
        val tracker = ReplayTracker()
        assertEquals(ReplayTracker.FRESH, tracker.classify(100), "没有重放时都是普通帧")
        for (pts in 100L..104L) tracker.onQueued(pts, isLast = pts == 104L)
        for (pts in 100L..103L) assertEquals(ReplayTracker.REPLAY, tracker.classify(pts))
        assertEquals(ReplayTracker.REPLAY_LAST, tracker.classify(104))
        assertEquals(ReplayTracker.FRESH, tracker.classify(99), "重放之前送进去的")
        assertEquals(ReplayTracker.FRESH, tracker.classify(105), "重放之后接着送的新帧")
        tracker.reset()
        assertEquals(ReplayTracker.FRESH, tracker.classify(102), "flush 之后重新开始")
    }

    @Test
    fun trackerWithoutLastFrameQueuedHidesEverything() {
        // 重放到一半来了新的关键帧，剩下的（含最后一帧）没送：送过的都不上屏。
        val tracker = ReplayTracker()
        tracker.onQueued(10, isLast = false)
        tracker.onQueued(11, isLast = false)
        assertEquals(ReplayTracker.REPLAY, tracker.classify(11))
        assertEquals(ReplayTracker.FRESH, tracker.classify(12))
    }

    @Test
    fun trackerWithRewrittenTimestampsFallsBackToFresh() {
        // 解码器把时间戳改了（比如换成自己的计数），对不上就当普通帧照常上屏：最后停在的仍是最新的画面。
        val tracker = ReplayTracker()
        for (pts in 1_000L..1_003L) tracker.onQueued(pts, isLast = pts == 1_003L)
        assertEquals(ReplayTracker.FRESH, tracker.classify(0))
        assertEquals(ReplayTracker.FRESH, tracker.classify(33_333))
    }

    /** 记录还给解码器的输出：(序号, 是否上屏)。 */
    private class Released {
        val calls = ArrayList<Pair<Int, Boolean>>()
        val presenter = ReplayPresenter { index, render -> calls += index to render }
    }

    @Test
    fun presenterShowsOnlyTheLastReplayFrame() {
        val r = Released()
        assertTrue(r.presenter.onOutput(1, ReplayTracker.REPLAY, visible = true), "扣住，要计时")
        assertTrue(r.calls.isEmpty())
        assertTrue(r.presenter.onOutput(2, ReplayTracker.REPLAY, visible = true))
        assertFalse(r.presenter.onOutput(3, ReplayTracker.REPLAY_LAST, visible = true))
        assertEquals(listOf(1 to false, 2 to false, 3 to true), r.calls)
        assertFalse(r.presenter.holding)
    }

    @Test
    fun presenterShowsTheNewestReplayFrameWhenTheLastOneNeverComesOut() {
        // 解码器扣着最后一帧（要等后面的帧才交出）：计时到了，把最近交出的那一帧上屏，不至于一直黑着。
        val r = Released()
        r.presenter.onOutput(1, ReplayTracker.REPLAY, visible = true)
        r.presenter.onOutput(2, ReplayTracker.REPLAY, visible = true)
        r.presenter.onIdle(visible = true)
        assertEquals(listOf(1 to false, 2 to true), r.calls)
        r.presenter.onIdle(visible = true)
        assertEquals(2, r.calls.size, "手里没有扣着的就什么都不做")
    }

    @Test
    fun presenterLetsFreshFramesThroughAndDropsTheHeldReplayFrame() {
        val r = Released()
        r.presenter.onOutput(1, ReplayTracker.REPLAY, visible = true)
        assertFalse(r.presenter.onOutput(2, ReplayTracker.FRESH, visible = true))
        assertFalse(r.presenter.onOutput(3, ReplayTracker.FRESH, visible = true))
        assertEquals(listOf(1 to false, 2 to true, 3 to true), r.calls, "新画面比重放的新，扣着的不上屏")
    }

    @Test
    fun presenterNeverRendersOrHoldsWhileInvisible() {
        val r = Released()
        assertFalse(r.presenter.onOutput(1, ReplayTracker.REPLAY, visible = false))
        r.presenter.onOutput(2, ReplayTracker.REPLAY_LAST, visible = false)
        r.presenter.onOutput(3, ReplayTracker.FRESH, visible = false)
        assertEquals(listOf(1 to false, 2 to false, 3 to false), r.calls)
        r.presenter.onOutput(4, ReplayTracker.REPLAY, visible = true)
        r.presenter.onIdle(visible = false)
        assertEquals(4 to false, r.calls.last(), "计时到时已经看不见：不上屏")
    }

    @Test
    fun presenterForgetsHeldIndexAfterFlush() {
        val r = Released()
        r.presenter.onOutput(1, ReplayTracker.REPLAY, visible = true)
        r.presenter.drop()
        assertFalse(r.presenter.holding)
        r.presenter.onIdle(visible = true)
        r.presenter.onOutput(2, ReplayTracker.REPLAY_LAST, visible = true)
        assertEquals(listOf(2 to true), r.calls, "flush 以后旧序号失效，不能再还")
    }

    @Test
    fun stallNeedsTheTimeoutEvenForKeyFramesWaitingForInput() {
        val timeout = 3_000L
        // 关键帧在等输入缓冲 2.5 秒：还在等，不丢、不判卡死。
        assertEquals(DecoderTiming.Stall.NONE, DecoderTiming.stall(10_000, 0, 7_500, inputWaitMs = 2_500, timeoutMs = timeout))
        assertEquals(DecoderTiming.Stall.NO_INPUT, DecoderTiming.stall(10_000, 0, 6_000, inputWaitMs = 3_001, timeoutMs = timeout))
        // 送进去 8 帧、从第一帧起 3 秒多没出帧。
        assertEquals(DecoderTiming.Stall.NO_OUTPUT, DecoderTiming.stall(10_000, 8, 6_900, 0, timeout))
        assertEquals(DecoderTiming.Stall.NONE, DecoderTiming.stall(10_000, 7, 6_900, 0, timeout), "解码器正常也会扣住几帧")
        // 画面很久没变、刚重放了一大批：计时从这批第一帧算，不会马上判卡死。
        assertEquals(DecoderTiming.Stall.NONE, DecoderTiming.stall(100_000, 60, 99_990, 0, timeout))
    }

    @Test
    fun replayWaitsBrieflyOnlyWhilePhoneIsSendingFrames() {
        assertEquals(DecoderTiming.REPLAY_GRACE_MS, DecoderTiming.replayDelayMs(10_000, 9_800), "刚收到过帧：先等新画面")
        assertEquals(0L, DecoderTiming.replayDelayMs(10_000, 9_000), "画面不变已经一阵子了：马上重放")
        assertEquals(0L, DecoderTiming.replayDelayMs(10_000, -1), "还没收到过帧")
    }
}
