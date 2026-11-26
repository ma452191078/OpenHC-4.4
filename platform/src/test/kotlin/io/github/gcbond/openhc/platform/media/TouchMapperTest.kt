package io.github.gcbond.openhc.platform.media

import io.github.gcbond.openhc.core.hisight.HiSightInput
import io.github.gcbond.openhc.core.hisight.HiSightInput.Touch.Action.DOWN
import io.github.gcbond.openhc.core.hisight.HiSightInput.Touch.Action.MOVE
import io.github.gcbond.openhc.core.hisight.HiSightInput.Touch.Action.UP
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TouchMapperTest {

    /** 一个指针在视图上的位置。 */
    private class P(val id: Int, val x: Float, val y: Float)

    /**
     * 按协议检查发出的序列（与回控封包层的约束一致）：DOWN 只在没有按下的触点时发；MOVE 在按下期间发，触点集合可以变；
     * UP 只带 1 个当前按着的点并结束整组。触点 1～10 个、编号 0～31 升序且不重复、坐标在画面内。
     */
    private class ProtocolChecker {
        var down: List<Int>? = null
            private set

        fun check(touches: List<HiSightInput.Touch>): List<HiSightInput.Touch> {
            for (t in touches) {
                assertTrue(t.points.size in 1..10, "触点数 ${t.points.size}")
                val ids = t.points.map { it.id }
                assertEquals(ids.sorted(), ids, "触点按编号排序")
                assertEquals(ids.size, ids.toSet().size, "触点编号重复")
                for (p in t.points) {
                    assertTrue(p.id in 0..31 && p.x in 0 until t.width && p.y in 0 until t.height, "触点超出范围")
                }
                when (t.action) {
                    DOWN -> {
                        assertNull(down, "还有按下的触点时又发了 DOWN")
                        down = ids
                    }
                    MOVE -> {
                        assertTrue(down != null, "没有按下就发了 MOVE")
                        down = ids
                    }
                    UP -> {
                        assertEquals(1, ids.size, "UP 只能带 1 个点")
                        assertTrue(down?.contains(ids.single()) == true, "UP 的点不在当前按着的触点里")
                        down = null
                    }
                }
            }
            return touches
        }
    }

    /**
     * 手机对触摸包的处理（协议行为）：从没出现过多个触点时按包里的动作原样处理；一旦某包带了 2 个及以上触点，
     * 就按快照做差——新编号按下、已按下的移动、这包里没有的抬起；只有「1 个点的 UP」是真抬起并回到原样处理。
     * [log] 记录手机实际注入的逐点按下（↓）和抬起（↑），移动不记。
     */
    private class PhoneModel {
        private val pressed = LinkedHashSet<Int>()
        private var multi = false
        val log = ArrayList<String>()

        /** 手机上还按着的触点编号。 */
        val held: Set<Int> get() = pressed

        fun feed(touches: List<HiSightInput.Touch>) {
            for (t in touches) feed(t)
        }

        private fun feed(t: HiSightInput.Touch) {
            if (t.points.size >= 2) multi = true
            if (!multi) {
                val id = t.points.single().id
                when (t.action) {
                    DOWN -> if (pressed.add(id)) log += "$id↓"
                    UP -> if (pressed.remove(id)) log += "$id↑"
                    MOVE -> Unit
                }
                return
            }
            val lastUp = t.points.size == 1 && t.action == UP
            val seen = HashSet<Int>()
            for (p in t.points) {
                if (p.id !in pressed) {
                    if (t.action == UP) continue
                    pressed += p.id
                    log += "${p.id}↓"
                } else if (lastUp) {
                    pressed -= p.id
                    log += "${p.id}↑"
                    multi = false
                }
                seen += p.id
            }
            for (id in pressed.toList()) {
                if (id !in seen) {
                    pressed -= id
                    log += "$id↑"
                }
            }
        }
    }

    private val checker = ProtocolChecker()
    private val phone = PhoneModel()

    /** 1:1 的画面（视图 1920×1080，视频 1920×1080）：视频坐标就是视图坐标取整。 */
    private val fullScreen = VideoViewport(1920, 1080, 1920, 1080)

    private fun mapper(viewport: VideoViewport? = fullScreen, edgeSlop: Float = 0f) =
        TouchMapper(edgeSlopPx = edgeSlop).also { it.setViewport(viewport) }

    private fun TouchMapper.on(action: TouchMapper.Action, changed: Int, vararg pointers: P): List<String> {
        val out = onEvent(
            action,
            changed,
            pointers.size,
            IntArray(pointers.size) { pointers[it].id },
            FloatArray(pointers.size) { pointers[it].x },
            FloatArray(pointers.size) { pointers[it].y },
        )
        return send(out)
    }

    /** 过一遍协议检查，再交给手机模型。 */
    private fun send(touches: List<HiSightInput.Touch>): List<String> {
        checker.check(touches)
        phone.feed(touches)
        return describe(touches)
    }

    private fun describe(touches: List<HiSightInput.Touch>): List<String> =
        touches.map { t -> t.action.name + " " + t.points.joinToString(" ") { "${it.id}@${it.x},${it.y}" } }

    @Test
    fun singleTapIsSentImmediately() {
        val m = mapper()
        assertEquals(listOf("DOWN 0@100,200"), m.on(TouchMapper.Action.DOWN, 0, P(0, 100.7f, 200.2f)))
        assertEquals(listOf("UP 0@101,201"), m.on(TouchMapper.Action.UP, 0, P(0, 101f, 201f)))
        assertNull(checker.down)
        assertEquals(listOf("0↓", "0↑"), phone.log)
    }

    @Test
    fun longPressHoldsWithoutExtraEvents() {
        val m = mapper()
        assertEquals(listOf("DOWN 0@100,200"), m.on(TouchMapper.Action.DOWN, 0, P(0, 100f, 200f)))
        // 手指轻微抖动但没离开这个像素：什么都不发，手机那边一直按着。
        assertEquals(emptyList(), m.on(TouchMapper.Action.MOVE, -1, P(0, 100.3f, 200.4f)))
        assertEquals(listOf("UP 0@100,200"), m.on(TouchMapper.Action.UP, 0, P(0, 100.3f, 200.4f)))
        assertEquals(listOf("0↓", "0↑"), phone.log)
    }

    @Test
    fun dragAndMoveSkipsUnchangedPixels() {
        val m = mapper()
        m.on(TouchMapper.Action.DOWN, 0, P(0, 100f, 200f))
        assertEquals(emptyList(), m.on(TouchMapper.Action.MOVE, -1, P(0, 100.4f, 200.9f)), "还在同一个视频像素里")
        assertEquals(listOf("MOVE 0@150,200"), m.on(TouchMapper.Action.MOVE, -1, P(0, 150f, 200f)))
        assertEquals(listOf("UP 0@150,200"), m.on(TouchMapper.Action.UP, 0, P(0, 150f, 200f)))
    }

    @Test
    fun secondFingerLandingLaterJoinsWithoutLiftingTheFirst() {
        val m = mapper()
        m.on(TouchMapper.Action.DOWN, 0, P(0, 100f, 200f))
        m.on(TouchMapper.Action.MOVE, -1, P(0, 104f, 200f))
        // 第二根手指晚到多久都一样：不先抬起第一根，直接带上新手指发 MOVE。
        assertEquals(
            listOf("MOVE 0@104,200 1@600,300"),
            m.on(TouchMapper.Action.POINTER_DOWN, 1, P(0, 104f, 200f), P(1, 600f, 300f)),
        )
        assertEquals(
            listOf("MOVE 0@90,190 1@650,320"),
            m.on(TouchMapper.Action.MOVE, -1, P(0, 90f, 190f), P(1, 650f, 320f)),
        )
        // 先松开第二根：剩下的那根照样按着，快照里少了第二根。
        assertEquals(listOf("MOVE 0@90,190"), m.on(TouchMapper.Action.POINTER_UP, 1, P(0, 90f, 190f), P(1, 650f, 320f)))
        assertEquals(emptyList(), m.on(TouchMapper.Action.MOVE, -1, P(0, 90f, 190f)), "安卓随后的 MOVE 与快照相同")
        // 剩下那根没动就松开：只是整组手势结束，不是一次点击。
        assertEquals(listOf("UP 0@90,190"), m.on(TouchMapper.Action.UP, 0, P(0, 90f, 190f)))
        assertNull(checker.down)
        assertEquals(listOf("0↓", "1↓", "1↑", "0↑"), phone.log, "手机上第一根手指中途没有抬起过")
        assertTrue(phone.held.isEmpty())
    }

    @Test
    fun firstFingerLiftingFirstEndsWithTheOther() {
        val m = mapper()
        m.on(TouchMapper.Action.DOWN, 0, P(0, 100f, 200f))
        m.on(TouchMapper.Action.POINTER_DOWN, 1, P(0, 100f, 200f), P(1, 500f, 200f))
        m.on(TouchMapper.Action.MOVE, -1, P(0, 80f, 200f), P(1, 540f, 200f))
        assertEquals(listOf("MOVE 1@540,200"), m.on(TouchMapper.Action.POINTER_UP, 0, P(0, 80f, 200f), P(1, 540f, 200f)))
        assertEquals(listOf("MOVE 1@560,210"), m.on(TouchMapper.Action.MOVE, -1, P(1, 560f, 210f)))
        // 最后一根是 1 号：UP 只带它自己。
        assertEquals(listOf("UP 1@570,210"), m.on(TouchMapper.Action.UP, 1, P(1, 570f, 210f)))
        assertEquals(listOf("0↓", "1↓", "0↑", "1↑"), phone.log)
        assertTrue(phone.held.isEmpty())
    }

    @Test
    fun pinchRotateAndTwoFingerDragAreSnapshots() {
        val m = mapper()
        m.on(TouchMapper.Action.DOWN, 0, P(0, 900f, 500f))
        m.on(TouchMapper.Action.POINTER_DOWN, 1, P(0, 900f, 500f), P(1, 1000f, 500f))
        // 张开、旋转、平移：每一步都是带两个点的 MOVE。
        for ((a, b) in listOf(850f to 1050f, 840f to 1060f)) {
            assertEquals(listOf("MOVE 0@${a.toInt()},500 1@${b.toInt()},500"), m.on(TouchMapper.Action.MOVE, -1, P(0, a, 500f), P(1, b, 500f)))
        }
        assertEquals(listOf("MOVE 0@840,480 1@1060,520"), m.on(TouchMapper.Action.MOVE, -1, P(0, 840f, 480f), P(1, 1060f, 520f)))
        assertEquals(listOf("MOVE 0@740,380 1@960,420"), m.on(TouchMapper.Action.MOVE, -1, P(0, 740f, 380f), P(1, 960f, 420f)))
        m.on(TouchMapper.Action.POINTER_UP, 0, P(0, 740f, 380f), P(1, 960f, 420f))
        m.on(TouchMapper.Action.UP, 1, P(1, 960f, 420f))
        assertEquals(listOf("0↓", "1↓", "0↑", "1↑"), phone.log)
    }

    @Test
    fun twoFingerTapWithoutMovesStillShowsBothFingers() {
        val m = mapper()
        assertEquals(listOf("DOWN 0@100,100"), m.on(TouchMapper.Action.DOWN, 0, P(0, 100f, 100f)))
        // 两根手指在同一帧里先后落下、没有任何 MOVE：第二根马上进快照，手机能看到双指轻点。
        assertEquals(listOf("MOVE 0@100,100 1@300,100"), m.on(TouchMapper.Action.POINTER_DOWN, 1, P(0, 100f, 100f), P(1, 300f, 100f)))
        assertEquals(listOf("MOVE 0@100,100"), m.on(TouchMapper.Action.POINTER_UP, 1, P(0, 100f, 100f), P(1, 300f, 100f)))
        assertEquals(listOf("UP 0@100,100"), m.on(TouchMapper.Action.UP, 0, P(0, 100f, 100f)))
        assertEquals(listOf("0↓", "1↓", "1↑", "0↑"), phone.log)
    }

    @Test
    fun threeFingersComeAndGoInAnyOrder() {
        val m = mapper()
        m.on(TouchMapper.Action.DOWN, 0, P(0, 100f, 100f))
        assertEquals(listOf("MOVE 0@100,100 1@200,100"), m.on(TouchMapper.Action.POINTER_DOWN, 1, P(0, 100f, 100f), P(1, 200f, 100f)))
        assertEquals(
            listOf("MOVE 0@100,100 1@200,100 2@300,100"),
            m.on(TouchMapper.Action.POINTER_DOWN, 2, P(0, 100f, 100f), P(1, 200f, 100f), P(2, 300f, 100f)),
        )
        assertEquals(
            listOf("MOVE 0@100,150 1@200,150 2@300,150"),
            m.on(TouchMapper.Action.MOVE, -1, P(0, 100f, 150f), P(1, 200f, 150f), P(2, 300f, 150f)),
        )
        assertEquals(
            listOf("MOVE 0@100,150 2@300,150"),
            m.on(TouchMapper.Action.POINTER_UP, 1, P(0, 100f, 150f), P(1, 200f, 150f), P(2, 300f, 150f)),
        )
        assertEquals(listOf("MOVE 2@300,150"), m.on(TouchMapper.Action.POINTER_UP, 0, P(0, 100f, 150f), P(2, 300f, 150f)))
        assertEquals(listOf("UP 2@300,160"), m.on(TouchMapper.Action.UP, 2, P(2, 300f, 160f)))
        assertEquals(listOf("0↓", "1↓", "2↓", "1↑", "0↑", "2↑"), phone.log)
        assertTrue(phone.held.isEmpty())
    }

    @Test
    fun reusedPointerIdIsANewFingerOnThePhone() {
        val m = mapper()
        m.on(TouchMapper.Action.DOWN, 0, P(0, 100f, 100f))
        m.on(TouchMapper.Action.POINTER_DOWN, 1, P(0, 100f, 100f), P(1, 500f, 100f))
        m.on(TouchMapper.Action.POINTER_UP, 0, P(0, 100f, 100f), P(1, 500f, 100f))
        // 安卓把空出来的 0 号给了新落下的手指：手机那边旧的 0 号已经抬起，新手指是一次新的按下，不是瞬移。
        assertEquals(
            listOf("MOVE 0@900,700 1@500,100"),
            m.on(TouchMapper.Action.POINTER_DOWN, 0, P(0, 900f, 700f), P(1, 500f, 100f)),
        )
        assertEquals(listOf("0↓", "1↓", "0↑", "0↓"), phone.log)
        m.on(TouchMapper.Action.POINTER_UP, 1, P(0, 900f, 700f), P(1, 500f, 100f))
        m.on(TouchMapper.Action.UP, 0, P(0, 900f, 700f))
        assertEquals(listOf("0↓", "1↓", "0↑", "0↓", "1↑", "0↑"), phone.log)
    }

    @Test
    fun cancelEndsMultiFingerGestureWithSinglePointUp() {
        val m = mapper()
        m.on(TouchMapper.Action.DOWN, 0, P(0, 100f, 200f))
        m.on(TouchMapper.Action.POINTER_DOWN, 1, P(0, 100f, 200f), P(1, 300f, 400f))
        m.on(TouchMapper.Action.MOVE, -1, P(0, 150f, 250f), P(1, 310f, 410f))
        // 取消没有对应动作：在上次的位置抬起编号最小的点，手机把其余的一并抬起。
        assertEquals(listOf("UP 0@150,250"), m.on(TouchMapper.Action.CANCEL, -1, P(0, 999f, 999f), P(1, 999f, 999f)))
        assertTrue(phone.held.isEmpty(), "手机上没有挂着的手指")
        assertEquals(listOf("0↓", "1↓", "0↑", "1↑"), phone.log)
        assertEquals(emptyList(), m.on(TouchMapper.Action.MOVE, -1, P(0, 160f, 250f)), "取消后的事件不再发送")
        // 下一次单指点按照常是点按。
        assertEquals(listOf("DOWN 0@50,60"), m.on(TouchMapper.Action.DOWN, 0, P(0, 50f, 60f)))
        assertEquals(listOf("UP 0@50,60"), m.on(TouchMapper.Action.UP, 0, P(0, 50f, 60f)))
        assertEquals(listOf("0↓", "1↓", "0↑", "1↑", "0↓", "0↑"), phone.log)
    }

    @Test
    fun explicitCancelIgnoresFingersUntilLifted() {
        val m = mapper()
        m.on(TouchMapper.Action.DOWN, 0, P(0, 100f, 200f))
        assertEquals(listOf("UP 0@100,200"), send(m.cancel()))
        assertEquals(emptyList(), m.on(TouchMapper.Action.MOVE, -1, P(0, 300f, 200f)))
        // 取消时按着的手指被忽略，后落下的手指照常是一组新手势。
        assertEquals(listOf("DOWN 1@500,500"), m.on(TouchMapper.Action.POINTER_DOWN, 1, P(0, 300f, 200f), P(1, 500f, 500f)))
        assertEquals(listOf("UP 1@500,500"), m.on(TouchMapper.Action.POINTER_UP, 1, P(0, 300f, 200f), P(1, 500f, 500f)))
        assertEquals(emptyList(), m.on(TouchMapper.Action.UP, 0, P(0, 300f, 200f)))
        assertEquals(listOf("DOWN 0@50,60"), m.on(TouchMapper.Action.DOWN, 0, P(0, 50f, 60f)), "下一次按下照常开始")
    }

    @Test
    fun silentResetSendsNothing() {
        val m = mapper()
        m.on(TouchMapper.Action.DOWN, 0, P(0, 100f, 200f))
        m.on(TouchMapper.Action.POINTER_DOWN, 1, P(0, 100f, 200f), P(1, 300f, 200f))
        // 发送端已经和这里对不上：清状态时不能再补发抬起（会被拒）。
        m.reset()
        assertEquals(emptyList(), m.on(TouchMapper.Action.MOVE, -1, P(0, 300f, 200f), P(1, 310f, 200f)))
        assertEquals(emptyList(), m.on(TouchMapper.Action.POINTER_UP, 1, P(0, 300f, 200f), P(1, 310f, 200f)))
        assertEquals(emptyList(), m.on(TouchMapper.Action.UP, 0, P(0, 300f, 200f)))
        // 测试里的检查器还认为手机按着（真实场景是发送端丢过事件），这里另起一个检查器看新手势。
        val fresh = ProtocolChecker()
        val next = m.onEvent(TouchMapper.Action.DOWN, 0, 1, intArrayOf(0), floatArrayOf(50f), floatArrayOf(60f))
        assertEquals(listOf("DOWN 0@50,60"), describe(fresh.check(next)), "下一次按下照常开始")
    }

    @Test
    fun pressInBlackBarIsIgnoredAndEdgeSlopClamps() {
        // 1080×1080 的画面在 1920×1080 的视图里，左右各 420 像素黑边。
        val bars = VideoViewport(1920, 1080, 1080, 1080)
        val strict = mapper(viewport = bars)
        assertEquals(emptyList(), strict.on(TouchMapper.Action.DOWN, 0, P(0, 100f, 500f)))
        assertEquals(emptyList(), strict.on(TouchMapper.Action.MOVE, -1, P(0, 600f, 500f)), "按下在黑边里的手指整根忽略")
        assertEquals(emptyList(), strict.on(TouchMapper.Action.UP, 0, P(0, 600f, 500f)))

        val lenient = mapper(viewport = bars, edgeSlop = 30f)
        assertEquals(listOf("DOWN 0@0,500"), lenient.on(TouchMapper.Action.DOWN, 0, P(0, 400f, 500f)), "离边缘 20 像素：接受并钳到边缘")
        assertEquals(listOf("MOVE 0@100,500"), lenient.on(TouchMapper.Action.MOVE, -1, P(0, 520f, 500f)))
        assertEquals(listOf("MOVE 0@0,500"), lenient.on(TouchMapper.Action.MOVE, -1, P(0, 10f, 500f)), "滑进黑边钳到边缘")
        assertEquals(listOf("UP 0@0,0"), lenient.on(TouchMapper.Action.UP, 0, P(0, 10f, -40f)))
    }

    @Test
    fun ignoredFingerDoesNotBlockOthers() {
        val bars = VideoViewport(1920, 1080, 1080, 1080)
        val m = mapper(viewport = bars)
        assertEquals(emptyList(), m.on(TouchMapper.Action.DOWN, 0, P(0, 50f, 500f)), "黑边里，忽略")
        assertEquals(listOf("DOWN 1@540,540"), m.on(TouchMapper.Action.POINTER_DOWN, 1, P(0, 50f, 500f), P(1, 960f, 540f)))
        assertEquals(emptyList(), m.on(TouchMapper.Action.MOVE, -1, P(0, 80f, 500f), P(1, 960f, 540f)), "被忽略的手指动了不发")
        // 有效的只剩这一根：它抬起就是 UP，不管被忽略的手指还按没按着。
        assertEquals(listOf("UP 1@541,541"), m.on(TouchMapper.Action.POINTER_UP, 1, P(0, 80f, 500f), P(1, 961f, 541f)))
        assertEquals(emptyList(), m.on(TouchMapper.Action.UP, 0, P(0, 80f, 500f)))
        assertNull(checker.down)
        assertEquals(listOf("1↓", "1↑"), phone.log)
    }

    @Test
    fun limitsGroupToTenPoints() {
        val m = mapper()
        val pointers = ArrayList<P>()
        for (id in 0..10) {
            pointers += P(id, 100f + id * 50, 300f)
            val action = if (id == 0) TouchMapper.Action.DOWN else TouchMapper.Action.POINTER_DOWN
            m.on(action, id, *pointers.toTypedArray())
        }
        assertEquals(10, checker.down?.size, "第 11 根手指被忽略")
        val moved = pointers.map { P(it.id, it.x, it.y + 20) }.toTypedArray()
        val move = m.on(TouchMapper.Action.MOVE, -1, *moved)
        assertEquals(1, move.size)
        assertFalse(move.single().contains(" 10@"), "被忽略的手指不出现在事件里")
        assertEquals(emptyList(), m.on(TouchMapper.Action.POINTER_UP, 10, *moved), "被忽略的手指抬起什么都不发")
    }

    @Test
    fun pointerIdsOutsideProtocolRangeAreIgnored() {
        val m = mapper()
        assertEquals(emptyList(), m.on(TouchMapper.Action.DOWN, 40, P(40, 100f, 100f)))
        assertEquals(listOf("DOWN 3@200,100"), m.on(TouchMapper.Action.POINTER_DOWN, 3, P(40, 100f, 100f), P(3, 200f, 100f)))
    }

    @Test
    fun viewportChangeEndsGesture() {
        val m = mapper()
        m.on(TouchMapper.Action.DOWN, 0, P(0, 100f, 200f))
        m.on(TouchMapper.Action.POINTER_DOWN, 1, P(0, 100f, 200f), P(1, 300f, 200f))
        val ended = m.setViewport(VideoViewport(1920, 1080, 1080, 1920))
        assertEquals(listOf("UP 0@100,200"), send(ended), "只带一个点，手机把另一根一并抬起")
        assertEquals(1920, ended.single().width, "抬起用的仍是旧画面的尺寸")
        assertTrue(phone.held.isEmpty())
        assertEquals(emptyList(), m.on(TouchMapper.Action.MOVE, -1, P(0, 300f, 200f), P(1, 310f, 200f)))
        assertEquals(emptyList(), m.on(TouchMapper.Action.POINTER_UP, 1, P(0, 300f, 200f), P(1, 310f, 200f)))
        assertEquals(emptyList(), m.on(TouchMapper.Action.UP, 0, P(0, 300f, 200f)))
        // 新画面：1080×1920 竖屏在 1920×1080 视图里，宽 608、左边 656，视图中心对应画面中心。
        assertEquals(listOf("DOWN 0@540,960"), m.on(TouchMapper.Action.DOWN, 0, P(0, 960f, 540f)))
        assertEquals(emptyList(), send(m.setViewport(VideoViewport(1920, 1080, 1080, 1920))), "尺寸没变不打断")
    }

    @Test
    fun withoutViewportTouchesAreIgnored() {
        val m = mapper(viewport = null)
        assertEquals(emptyList(), m.on(TouchMapper.Action.DOWN, 0, P(0, 100f, 200f)))
        assertEquals(emptyList(), m.on(TouchMapper.Action.UP, 0, P(0, 100f, 200f)))
    }

    @Test
    fun newDownClosesStaleGesture() {
        val m = mapper()
        m.on(TouchMapper.Action.DOWN, 0, P(0, 100f, 200f))
        m.on(TouchMapper.Action.POINTER_DOWN, 1, P(0, 100f, 200f), P(1, 500f, 200f))
        // 漏了抬起又来一次 DOWN：先在上次的位置结束旧的一组（1 个点），再开始新的。
        assertEquals(listOf("UP 0@100,200", "DOWN 0@300,400"), m.on(TouchMapper.Action.DOWN, 0, P(0, 300f, 400f)))
        assertEquals(listOf("0↓", "1↓", "0↑", "1↑", "0↓"), phone.log)
    }
}
