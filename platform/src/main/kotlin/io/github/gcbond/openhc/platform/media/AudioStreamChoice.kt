package io.github.gcbond.openhc.platform.media

/**
 * 一路声音放到车机的哪个声道（纯逻辑，可在电脑上测）。
 *
 * - [AUTO]：按这一路的用途交给系统选（[trackAttributes]），默认就是它；
 * - 1～20：声道号，也就是安卓老式的流类型（3 媒体、5 通知、9 语音播报……），11 起多半是车机厂商自己加的
 *   （例如单独的导航声道）。按号建音轨见 [PcmTrack]。编号和 DiPlay 的声道设置一致，0 同样表示自动。
 */
object AudioStreamChoice {
    const val AUTO = 0
    const val MIN = 1
    const val MAX = 20

    /** 不在 1～20 里的一律按自动（存坏了的值、没存过）。 */
    fun normalize(value: Int): Int = if (value in MIN..MAX) value else AUTO

    /** 给日志看的写法。 */
    fun label(value: Int): String = if (value == AUTO) "自动" else "$value 号"
}
