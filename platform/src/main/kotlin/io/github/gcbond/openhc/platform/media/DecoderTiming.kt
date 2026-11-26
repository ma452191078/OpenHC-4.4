package io.github.gcbond.openhc.platform.media

/** [VideoDecoder] 里几个按时间做的判断。纯逻辑，不引用安卓类。 */
internal object DecoderTiming {
    /** 上次出帧后又送进去这么多帧还不出帧，才可能判卡死（解码器正常也会扣住一两帧）。 */
    const val STALL_MIN_INPUTS = 8

    /** 切到新 Surface 前这么久（毫秒）之内手机还发过帧，就先等 [REPLAY_GRACE_MS]，等来新画面就不用重放。 */
    const val ACTIVE_WINDOW_MS = 500L
    const val REPLAY_GRACE_MS = 100L

    /** 扣着的重放帧这么久（毫秒）没等来下一个，就把它上屏，见 [ReplayPresenter]。 */
    const val REPLAY_HOLD_MS = 100L

    enum class Stall {
        NONE,

        /** 送进去的帧不出帧。 */
        NO_OUTPUT,

        /** 有帧要送，解码器不给输入缓冲。 */
        NO_INPUT,
    }

    /**
     * 卡死判断：送进去至少 [STALL_MIN_INPUTS] 帧、从其中第一帧送进去起超过 [timeoutMs] 没出帧；
     * 或者有帧在等输入缓冲、解码器超过 [timeoutMs] 一个都没收（[inputWaitMs]，见 [FrameCache.inputWaitMs]）。
     * 帧不会因为等输入缓冲被丢，所以刚建好的解码器第一个输入缓冲来得晚、关键帧解得慢，都等得起，直到这里判卡死。
     * [outputWaitStartMs] 从上次出帧后第一次送帧算起，不从上次出帧算：手机画面长时间不变之后再送帧
     * （例如重放一大批），不会刚送进去就被当成卡死。
     */
    fun stall(nowMs: Long, inputsSinceOutput: Int, outputWaitStartMs: Long, inputWaitMs: Long, timeoutMs: Long): Stall =
        when {
            inputsSinceOutput >= STALL_MIN_INPUTS && nowMs - outputWaitStartMs > timeoutMs -> Stall.NO_OUTPUT
            inputWaitMs > timeoutMs -> Stall.NO_INPUT
            else -> Stall.NONE
        }

    /**
     * 输出切到新的视图 Surface 后隔多久（毫秒）重放。手机刚才还在发帧时，新画面多半马上就到，先等一会儿；
     * 一直没发（画面不变）就马上重放。[lastArrivalMs] 是最近一帧到达的时间，还没收到过为负数。
     */
    fun replayDelayMs(nowMs: Long, lastArrivalMs: Long): Long =
        if (lastArrivalMs >= 0 && nowMs - lastArrivalMs < ACTIVE_WINDOW_MS) REPLAY_GRACE_MS else 0L
}
