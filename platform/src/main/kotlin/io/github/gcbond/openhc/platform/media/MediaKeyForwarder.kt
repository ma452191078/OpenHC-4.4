package io.github.gcbond.openhc.platform.media

import io.github.gcbond.openhc.core.hisight.HiSightInput

/**
 * 车机媒体键（方向盘、媒体按钮）→ 发给手机的按键。纯逻辑，不引用安卓类，可在电脑上测。
 *
 * 发给手机的是投屏回控连接上的按键包（安卓键码，修饰位 0），按下、抬起各一个包，手机照普通媒体键处理。
 * 车机这边有两个入口，都交到这里：
 * - 按键事件（[onKey]）：前台窗口收到的、媒体按钮会话收到的，带真实的按下和抬起；
 * - 播放控制命令（[onCommand]）：媒体按钮会话的「播放、暂停、下一首……」，没有按下抬起之分，合成一对。
 *
 * 规则：
 * - 只管 [KEY_CODES] 里的媒体键，别的键不处理（交还系统）。
 * - 没有能发键的连接（[ready] 为 false）时不处理，车机自己的媒体照常。
 * - 按下照转；自动重复的按下不转，但算处理过（不让系统再派一次）。同一个键还没抬起又来一次按下：先补抬起再按下。
 * - 抬起只在转过它的按下时才转；孤立的抬起（它的按下给了系统）交还系统，让系统那边的按下、抬起配成对。
 * - 同一个按键事件（键码、动作、事件时间都相同）从两个入口各到一次时只转一次。
 * - 播放控制命令合成「按下 + 抬起」；这个键正按着时不合成（按键事件那边会发）。
 *
 * 不是线程安全的：调用方在同一个线程上串行调用。
 */
class MediaKeyForwarder {

    /** 收到按键的入口（只用于日志）。 */
    enum class Source(val label: String) {
        WINDOW("前台窗口"),
        SESSION("媒体会话"),
    }

    /** 媒体按钮会话的播放控制命令，以及它对应的安卓键码。 */
    enum class Command(val keyCode: Int) {
        PLAY(KEYCODE_MEDIA_PLAY),
        PAUSE(KEYCODE_MEDIA_PAUSE),
        PLAY_PAUSE(KEYCODE_MEDIA_PLAY_PAUSE),
        NEXT(KEYCODE_MEDIA_NEXT),
        PREVIOUS(KEYCODE_MEDIA_PREVIOUS),
        STOP(KEYCODE_MEDIA_STOP),
    }

    /**
     * 处理结果：[handled] 为 true 时调用方要把这个事件吃掉（不再交给系统）；[keys] 按顺序发给手机；
     * [note] 是给日志的一句说明。
     */
    class Result(val handled: Boolean, val keys: List<HiSightInput.Key>, val note: String)

    /** 现在有没有能发键的连接。变成 false 时清掉按下状态（连接收尾时会自己抬起还按着的键）。 */
    var ready: Boolean = false
        set(value) {
            field = value
            if (!value) {
                pressed.clear()
                recent.clear()
            }
        }

    private val pressed = LinkedHashSet<Int>()

    /** 最近处理过的按键事件，用来认出从两个入口各来一次的同一个事件。 */
    private val recent = ArrayDeque<Triple<Int, Boolean, Long>>()

    /** 一个按键事件：[down] 按下或抬起，[repeatCount] 自动重复次数，[eventTime] 事件时间（同一事件两个入口相同）。 */
    fun onKey(keyCode: Int, down: Boolean, repeatCount: Int, eventTime: Long): Result {
        if (keyCode !in KEY_CODES) return Result(false, emptyList(), "不是媒体键")
        if (!ready) return Result(false, emptyList(), "没有能发键的连接，交还系统")
        val id = Triple(keyCode, down, eventTime)
        if (id in recent) return Result(true, emptyList(), "同一事件已从另一个入口处理过")
        recent.addLast(id)
        while (recent.size > RECENT_EVENTS) recent.removeFirst()
        if (down) {
            if (repeatCount > 0) return Result(true, emptyList(), "自动重复，不转")
            val keys = ArrayList<HiSightInput.Key>(2)
            if (keyCode in pressed) keys += key(HiSightInput.Key.Action.UP, keyCode)
            keys += key(HiSightInput.Key.Action.DOWN, keyCode)
            pressed += keyCode
            return Result(true, keys, if (keys.size == 2) "上一次没抬起，先补抬起" else "转发")
        }
        if (!pressed.remove(keyCode)) return Result(false, emptyList(), "没转过它的按下，交还系统")
        return Result(true, listOf(key(HiSightInput.Key.Action.UP, keyCode)), "转发")
    }

    /** 一个播放控制命令：合成「按下 + 抬起」。 */
    fun onCommand(command: Command): Result {
        if (!ready) return Result(false, emptyList(), "没有能发键的连接")
        val code = command.keyCode
        if (code in pressed) return Result(true, emptyList(), "这个键正按着，不再合成")
        return Result(true, listOf(key(HiSightInput.Key.Action.DOWN, code), key(HiSightInput.Key.Action.UP, code)), "合成按下 + 抬起")
    }

    private fun key(action: HiSightInput.Key.Action, keyCode: Int) = HiSightInput.Key(action, keyCode, 0)

    companion object {
        const val KEYCODE_MEDIA_PLAY_PAUSE = 85
        const val KEYCODE_MEDIA_STOP = 86
        const val KEYCODE_MEDIA_NEXT = 87
        const val KEYCODE_MEDIA_PREVIOUS = 88
        const val KEYCODE_MEDIA_REWIND = 89
        const val KEYCODE_MEDIA_FAST_FORWARD = 90
        const val KEYCODE_MEDIA_PLAY = 126
        const val KEYCODE_MEDIA_PAUSE = 127

        /** 转给手机的媒体键（安卓键码）。 */
        val KEY_CODES: Set<Int> = setOf(
            KEYCODE_MEDIA_PLAY_PAUSE, KEYCODE_MEDIA_STOP, KEYCODE_MEDIA_NEXT, KEYCODE_MEDIA_PREVIOUS,
            KEYCODE_MEDIA_REWIND, KEYCODE_MEDIA_FAST_FORWARD, KEYCODE_MEDIA_PLAY, KEYCODE_MEDIA_PAUSE,
        )

        private const val RECENT_EVENTS = 8

        /** 日志里的键名，例如「下一首(87)」。 */
        fun label(keyCode: Int): String {
            val name = when (keyCode) {
                KEYCODE_MEDIA_PLAY_PAUSE -> "播放暂停"
                KEYCODE_MEDIA_STOP -> "停止"
                KEYCODE_MEDIA_NEXT -> "下一首"
                KEYCODE_MEDIA_PREVIOUS -> "上一首"
                KEYCODE_MEDIA_REWIND -> "快退"
                KEYCODE_MEDIA_FAST_FORWARD -> "快进"
                KEYCODE_MEDIA_PLAY -> "播放"
                KEYCODE_MEDIA_PAUSE -> "暂停"
                else -> "键"
            }
            return "$name($keyCode)"
        }
    }
}
