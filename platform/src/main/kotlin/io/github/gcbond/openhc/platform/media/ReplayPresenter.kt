package io.github.gcbond.openhc.platform.media

/**
 * 决定解码器交出的帧上不上屏、什么时候还给解码器。普通帧照常上屏；重放的帧原则上不上屏，只上屏最后一帧。
 *
 * 但解码器可能扣着最后一帧，要等后面再送帧才交出来；时间戳也可能被解码器改掉，认不出哪一帧是最后一帧。
 * 两种情况下只按「最后一帧才上屏」都会全部不上屏，画面照样黑着。所以手里总扣着最近交出的一个重放帧（先不还）：
 * - 又交出一个重放帧：扣着的不上屏还掉，扣住新的；
 * - 交出重放的最后一帧，或者普通帧：扣着的不上屏还掉，这一帧上屏；
 * - 调用方计时，一段时间没有新的重放帧（[onIdle]）：扣着的上屏。
 *
 * 看不见（输出在占位 Surface 上）时一律不上屏、不扣。只在一个线程（解码线程）上用。纯逻辑，不引用安卓类；
 * [release] 由调用方实现：把输出序号还给解码器，render 表示要不要上屏。
 */
internal class ReplayPresenter(private val release: (index: Int, render: Boolean) -> Unit) {
    private var held = NONE

    val holding: Boolean get() = held != NONE

    /**
     * 解码器交出输出序号 [index]，[kind] 是 [ReplayTracker.classify] 的结果，[visible] 表示输出在视图 Surface 上。
     * 返回 true 表示扣住了这一帧，调用方要（重新）开始计时，到时调 [onIdle]。
     */
    fun onOutput(index: Int, kind: Int, visible: Boolean): Boolean {
        releaseHeld(false)
        if (kind == ReplayTracker.REPLAY && visible) {
            held = index
            return true
        }
        release(index, visible && kind != ReplayTracker.REPLAY)
        return false
    }

    /** 一段时间没有新的重放帧：扣着的那一帧上屏（看不见时不上屏）。 */
    fun onIdle(visible: Boolean) {
        releaseHeld(visible)
    }

    /** 扣着的序号已经失效（解码器 flush 了或者换了）：忘掉，不再还。 */
    fun drop() {
        held = NONE
    }

    private fun releaseHeld(render: Boolean) {
        val index = held
        if (index == NONE) return
        held = NONE
        release(index, render)
    }

    private companion object {
        const val NONE = -1
    }
}
