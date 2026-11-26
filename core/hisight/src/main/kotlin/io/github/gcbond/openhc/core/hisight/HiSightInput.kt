package io.github.gcbond.openhc.core.hisight

/** 交给 [HiSightReceiver.sendInput] 的反向控制输入。 */
sealed class HiSightInput {
    /**
     * 一次触摸事件，带当前全部触点的完整快照（1～10 个）。
     *
     * 坐标是手机视频画面里的像素（左上角为原点），由调用方从显示坐标换算好；[width]、[height] 是
     * 换算时依据的视频宽高，只用来检查坐标范围（0 ≤ x < width，0 ≤ y < height），不会发给手机。
     * 镜像模式下实际编码尺寸可能与协商值不同，应按解码器输出的实际尺寸填。
     *
     * 一组手势从 [Action.DOWN] 开始（没有按下的触点时才能发）、以 [Action.UP] 结束。中间手指增减用
     * [Action.MOVE] 表达：每个 MOVE 带当时全部按着的触点，手机按前后差别把新出现的当作按下、消失的当作抬起。
     * [Action.UP] 只能带 1 个当前按着的触点，它结束整组手势，其余还按着的触点由手机一并抬起；
     * 不要在手势中途发 UP 再 DOWN，手机会把它当成一次点击。
     */
    class Touch(val action: Action, val points: List<Point>, val width: Int, val height: Int) : HiSightInput() {
        enum class Action { DOWN, UP, MOVE }
    }

    /** 触点编号 0..31。 */
    class Point(val id: Int, val x: Int, val y: Int)

    /**
     * 物理按键：[keyCode] 是安卓键码（0..65535），[metaState] 是安卓修饰键位图（无符号 32 位）。
     * 同一个键可以连续按下（自动重复），抬起前必须按下过。
     */
    class Key(val action: Action, val keyCode: Int, val metaState: Long) : HiSightInput() {
        enum class Action { DOWN, UP }
    }

    /**
     * 实验：一段成文输入，按 UTF-8 编码后 1..472 字节，不能含 NUL。手机侧的编码与插入/替换语义未经验证。
     */
    class Text(val text: String) : HiSightInput()
}
