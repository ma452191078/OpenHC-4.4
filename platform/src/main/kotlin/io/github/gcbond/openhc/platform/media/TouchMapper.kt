package io.github.gcbond.openhc.platform.media

import io.github.gcbond.openhc.core.hisight.HiSightInput

/**
 * 把视图上的触摸换算成发给手机的 [HiSightInput.Touch]。纯逻辑，不引用安卓类；安卓上用
 * [onMotionEvent][io.github.gcbond.openhc.platform.media.onMotionEvent] 接 MotionEvent。只能在一个线程里用。
 *
 * **坐标**：按 [VideoViewport]（等比缩放、居中、黑边）换算成视频像素。按下点在画面外、离画面边缘超过
 * [edgeSlopPx] 的手指整根忽略（直到它抬起）；按下点在画面里或边缘容差内的手指，之后滑到画面外就钳到边缘像素，
 * 这样拖到边上、从边缘滑入的手势都不会断。
 *
 * **多指**：手机把每个触摸包当成「当前全部触点的快照」，和它记着的按下触点比较：新出现的编号当作这根手指按下，
 * 不见了的编号当作这根手指抬起，其余是移动（只有一根手指、还没出现过多点时按包里的动作原样处理）。所以：
 * - 第一根有效手指按下：马上发 DOWN（只带它）。
 * - 之后手指增减：马上发 MOVE，带剩下的全部有效手指。**不先抬起**——一组手势中途发 UP 会被手机当成真的抬起，
 *   紧接着再按下就成了一次点击（地图上是双击放大）。
 * - 最后一根有效手指抬起：发 UP，只带它自己。手机只在 UP 带 1 个点时才结束这组手势；带多个点的 UP 会被当成移动，
 *   手指在手机上就一直按着。
 * - 中止（CANCEL、[cancel]、画面几何变化）：按上次发出的快照，取编号最小的那个点在原位置发 1 点的 UP，
 *   手机会把这组里其余的点一并抬起。协议里没有「取消」动作。
 *
 * 安卓的指针编号（0～31）直接用作协议的触点编号；一组最多 10 个触点，多出来的手指忽略。
 * 发出的序列满足 [HiSightInput.Touch] 的约束：DOWN 只在手机那边没有按着的触点时发，UP 只带 1 个当前按着的点，
 * 坐标都在画面内。发送端把某次输入丢了（例如回控连接还没就绪时静默丢弃）会和这里的状态对不上，之后再发会被拒；
 * 这时调用 [reset]（只清状态、不发事件），下一次按下重新开始。
 */
class TouchMapper(
    private val edgeSlopPx: Float = 0f,
) {
    enum class Action { DOWN, POINTER_DOWN, MOVE, POINTER_UP, UP, CANCEL }

    private class Finger(val id: Int, var x: Float, var y: Float) {
        /** 是否转给手机。按下时决定，之后不变（中止手势时全部改为忽略）。 */
        var accepted = false
    }

    private var viewport: VideoViewport? = null
    private val fingers = LinkedHashMap<Int, Finger>()

    /** 手机那边按着的触点：上次发出的 DOWN 或 MOVE 快照（按编号升序）；null 表示没有按着的触点。 */
    private var lastSent: List<HiSightInput.Point>? = null

    /**
     * 画面几何变了（视图尺寸或视频尺寸，null 表示还不知道）。正在进行的手势按上次的位置结束，
     * 还按着的手指都忽略到抬起为止。
     */
    fun setViewport(viewport: VideoViewport?): List<HiSightInput.Touch> {
        if (viewport == this.viewport) return emptyList()
        val out = ArrayList<HiSightInput.Touch>(1)
        endGesture(out)
        this.viewport = viewport
        return out
    }

    /**
     * 处理一次指针事件。[ids]、[xs]、[ys] 的前 [count] 项是事件里全部指针的编号和视图坐标；
     * [changedId] 是这次按下或抬起的指针（MOVE、CANCEL 时不用）。返回要依次发给手机的触摸事件。
     */
    fun onEvent(
        action: Action,
        changedId: Int,
        count: Int,
        ids: IntArray,
        xs: FloatArray,
        ys: FloatArray,
    ): List<HiSightInput.Touch> {
        require(count in 0..minOf(ids.size, xs.size, ys.size)) { "指针个数非法" }
        val out = ArrayList<HiSightInput.Touch>(1)
        when (action) {
            Action.DOWN -> {
                // 新手势的第一根手指：之前的状态如果没收尾（漏了 UP），先收尾。
                endGesture(out)
                fingers.clear()
                pointerDown(changedId, count, ids, xs, ys, out)
            }
            Action.POINTER_DOWN -> {
                updatePositions(count, ids, xs, ys)
                pointerDown(changedId, count, ids, xs, ys, out)
            }
            Action.MOVE -> {
                updatePositions(count, ids, xs, ys)
                if (lastSent != null) sendSnapshot(out)
            }
            Action.POINTER_UP -> {
                updatePositions(count, ids, xs, ys)
                pointerUp(changedId, out)
            }
            Action.UP -> {
                updatePositions(count, ids, xs, ys)
                pointerUp(changedId, out)
                // 最后一根手指抬起：不管还记着什么都清掉。
                endGesture(out)
                fingers.clear()
            }
            Action.CANCEL -> {
                endGesture(out)
                fingers.clear()
            }
        }
        return out
    }

    /** 中止当前手势（失去焦点、离开窗口）：手机上按着的一组按上次的位置抬起，还按着的手指忽略到抬起为止。 */
    fun cancel(): List<HiSightInput.Touch> {
        val out = ArrayList<HiSightInput.Touch>(1)
        endGesture(out)
        return out
    }

    /**
     * 只清状态、不发任何事件：发送端的触摸状态已经和这里对不上（之前的某次输入被丢了），补发的抬起也会被拒，
     * 所以直接清掉。还按着的手指忽略到抬起为止。
     */
    fun reset() {
        endGesture(null)
    }

    private fun pointerDown(
        id: Int,
        count: Int,
        ids: IntArray,
        xs: FloatArray,
        ys: FloatArray,
        out: MutableList<HiSightInput.Touch>,
    ) {
        val index = indexOf(id, count, ids)
        if (index < 0) return
        val finger = Finger(id, xs[index], ys[index])
        fingers[id] = finger
        val vp = viewport ?: return
        if (id !in 0..MAX_POINTER_ID || vp.distanceOutside(finger.x, finger.y) > edgeSlopPx) return
        if (acceptedCount() >= MAX_POINTS) return
        finger.accepted = true
        // 手机那边没有按着的触点就按下，否则把新手指放进快照里移动。
        sendSnapshot(out)
    }

    private fun pointerUp(id: Int, out: MutableList<HiSightInput.Touch>) {
        val finger = fingers.remove(id) ?: return
        if (!finger.accepted || lastSent == null) return
        if (acceptedCount() > 0) {
            // 还有别的手指：快照里少了这根，手机按差集抬起它，其余的继续按着。
            sendSnapshot(out)
        } else {
            // 最后一根：只带它自己抬起，用这次事件里的位置。
            out += touch(HiSightInput.Touch.Action.UP, points(listOf(finger)))
            lastSent = null
        }
    }

    /** 收尾：手机那边按着的一组按上次发出的位置结束（[out] 为 null 时不发）；所有手指改为忽略。 */
    private fun endGesture(out: MutableList<HiSightInput.Touch>?) {
        val held = lastSent
        if (held != null && out != null) out += touch(HiSightInput.Touch.Action.UP, listOf(held[0]))
        lastSent = null
        for (finger in fingers.values) finger.accepted = false
    }

    /** 按当前有效手指发快照：手机那边没按着就发 DOWN，按着时集合或位置变了才发 MOVE。 */
    private fun sendSnapshot(out: MutableList<HiSightInput.Touch>) {
        val points = points(fingers.values.filter { it.accepted })
        if (points.isEmpty()) return
        val previous = lastSent
        if (previous == null) {
            out += touch(HiSightInput.Touch.Action.DOWN, points)
        } else {
            if (samePoints(previous, points)) return
            out += touch(HiSightInput.Touch.Action.MOVE, points)
        }
        lastSent = points
    }

    private fun points(group: List<Finger>): List<HiSightInput.Point> {
        val vp = viewport ?: return emptyList()
        return group.sortedBy { it.id }.map { HiSightInput.Point(it.id, vp.videoX(it.x), vp.videoY(it.y)) }
    }

    private fun touch(action: HiSightInput.Touch.Action, points: List<HiSightInput.Point>): HiSightInput.Touch {
        val vp = checkNotNull(viewport)
        return HiSightInput.Touch(action, points, vp.videoWidth, vp.videoHeight)
    }

    private fun updatePositions(count: Int, ids: IntArray, xs: FloatArray, ys: FloatArray) {
        for (i in 0 until count) {
            val finger = fingers[ids[i]] ?: continue
            finger.x = xs[i]
            finger.y = ys[i]
        }
    }

    private fun acceptedCount(): Int = fingers.values.count { it.accepted }

    private fun indexOf(id: Int, count: Int, ids: IntArray): Int {
        for (i in 0 until count) if (ids[i] == id) return i
        return -1
    }

    private fun samePoints(a: List<HiSightInput.Point>, b: List<HiSightInput.Point>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            if (a[i].id != b[i].id || a[i].x != b[i].x || a[i].y != b[i].y) return false
        }
        return true
    }

    companion object {
        /** 协议一个触摸事件最多带的触点数。 */
        const val MAX_POINTS = 10

        /** 协议触点编号的上限。 */
        const val MAX_POINTER_ID = 31
    }
}
