package io.github.gcbond.openhc.platform.media

/**
 * 认出解码器交出的帧里哪些是重放的、哪一帧是重放的最后一帧（要上屏的那一帧）。
 *
 * 重放帧送进解码器时记下时间戳，解码器交出帧时带回同一个时间戳，按它对。每次重放都在 flush 之后或者新解码器上开始
 * （之前送进去的帧不会再交出来），重放的帧连着送（第一次送的帧排在它们后面），时间戳单调递增，
 * 所以只记一个区间 [first, last] 和最后一帧的时间戳。
 *
 * 解码器改了时间戳就对不上：这些帧当普通帧照常上屏，重放过程中会闪过几帧旧画面，最后停在的仍是最后一帧。
 * 送帧、出帧在不同线程，方法都加锁。纯逻辑，不引用安卓类。
 */
internal class ReplayTracker {
    private var first = NONE
    private var last = NONE
    private var shown = NONE

    /** 开始新的一次重放（flush 之后、新解码器上）：之前记的作废。 */
    @Synchronized
    fun reset() {
        first = NONE
        last = NONE
        shown = NONE
    }

    /** 送进去一个重放帧（时间戳要比之前记的都大）；[isLast] 表示它是重放的最后一帧。 */
    @Synchronized
    fun onQueued(ptsUs: Long, isLast: Boolean) {
        if (first == NONE) first = ptsUs
        last = ptsUs
        if (isLast) shown = ptsUs
    }

    /** 解码器交出一帧：返回 [FRESH]（不是重放的帧）、[REPLAY] 或 [REPLAY_LAST]。 */
    @Synchronized
    fun classify(ptsUs: Long): Int = when {
        first == NONE || ptsUs < first || ptsUs > last -> FRESH
        ptsUs == shown -> REPLAY_LAST
        else -> REPLAY
    }

    companion object {
        const val FRESH = 0
        const val REPLAY = 1
        const val REPLAY_LAST = 2
        private const val NONE = -1L
    }
}
