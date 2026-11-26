package io.github.gcbond.openhc.platform.media

import java.util.Locale

/**
 * 各芯片厂商解码器私有的低延迟配置键，按解码器名字的前缀挑。纯逻辑，不引用安卓类。
 *
 * 这些键不在安卓标准里，不认识的解码器一般会忽略；万一因此配置失败，[VideoDecoder] 会去掉它们重试。
 * 没列出的厂商（联发科、全志、瑞芯微等）没有可靠的公开键，只用标准键。
 */
internal object VendorLowLatency {

    /**
     * [decodeOrderAllowed] 为 true 时才加「按解码顺序立即输出」一类的键：码流里有需要重排的 B 帧时，
     * 按解码顺序输出会让画面前后跳，所以只在确认没有 B 帧时打开。
     */
    fun keys(codecName: String, decodeOrderAllowed: Boolean): List<Pair<String, Int>> {
        val name = codecName.lowercase(Locale.ROOT)
        return when {
            name.startsWith("omx.qcom.") || name.startsWith("c2.qti.") -> buildList {
                add("vendor.qti-ext-dec-low-latency.enable" to 1)
                if (decodeOrderAllowed) add("vendor.qti-ext-dec-picture-order.enable" to 1)
            }
            name.startsWith("omx.hisi.") || name.startsWith("c2.hisi.") -> listOf(
                "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req" to 1,
                "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-rdy" to -1,
            )
            name.startsWith("omx.exynos.") || name.startsWith("c2.exynos.") ->
                listOf("vendor.rtc-ext-dec-low-latency.enable" to 1)
            name.startsWith("omx.amlogic.") || name.startsWith("c2.amlogic.") ->
                listOf("vendor.low-latency.enable" to 1)
            else -> emptyList()
        }
    }
}
