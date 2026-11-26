package io.github.gcbond.openhc.core.hisight

/**
 * 车机向手机声明的投屏能力（GET_PARAMETER 应答的内容），只用 H.264。
 *
 * 只声明这里明确配置的项；没配置的（GOP、码率）不发，音频、VTP、扩展媒体一律不声明。
 * 输入能力默认全关：只有打开的开关才会出现在 `his_uibc_capability` 里，且手机必须完整选回，
 * 否则协商失败，不会悄悄降级。唯一的例外是 [mediaKeyInput]：它是可选的，手机不选回只是这次没有媒体键。
 */
class HiSightConfig(
    /** 车机屏幕宽（像素），对应 `scr-w`。 */
    val screenWidth: Int,
    /** 车机屏幕高（像素），对应 `scr-h`。 */
    val screenHeight: Int,
    /** 允许手机编码的最大宽度，对应 `width`；手机选的宽度不能超过它。 */
    val videoWidth: Int,
    /** 允许手机编码的最大高度，对应 `height`。 */
    val videoHeight: Int,
    /** 帧率上限，对应 `fps`。车机屏幕刷新率不是 60Hz 时应按实际刷新率填。 */
    val fps: Int,
    val dpi: Int,
    /** 关键帧间隔；null 表示不声明。 */
    val gop: Int? = null,
    /** 码率；null 表示不声明。 */
    val bitrate: Int? = null,
    /** 触摸（单指和 1～10 指多点共用同一组能力）。 */
    val touchInput: Boolean = false,
    /** 物理按键（安卓键码）。手机还必须在 `his_device_type` 里选回 `source_device_type 7`。 */
    val keyInput: Boolean = false,
    /**
     * 媒体键（播放、暂停、上一首、下一首等），协商宽松：声明的两个按键事件与 [keyInput] 相同，
     * 但手机没把它们选回来（或只选了一半）、没选 `source_device_type 7` 都不算协商失败，只是这次发不了键或只能发基础键。
     *
     * 手机没选 7 时只能发基础键（返回、方向、通话、音量、媒体键），修饰位必须为 0：这组键在源设备类型 7 和其他类型下
     * 编码完全相同。同时打开 [keyInput] 时按 [keyInput] 的严格规则。
     */
    val mediaKeyInput: Boolean = false,
    /**
     * 实验：成文输入（整段文字）。要求同时打开 [keyInput]。目前手机不会选回这项能力，
     * 编码与手机侧的插入/替换语义都未验证，默认关闭。
     */
    val textInput: Boolean = false,
    /** `his_device_type` 里声明的 device_type，默认 4；5 只用于实验。 */
    val deviceType: Int = 4,
) {
    init {
        for ((name, value) in listOf(
            "screenWidth" to screenWidth, "screenHeight" to screenHeight,
            "videoWidth" to videoWidth, "videoHeight" to videoHeight, "fps" to fps, "dpi" to dpi,
        )) {
            require(value > 0) { "$name 必须是正整数" }
        }
        require(gop == null || gop > 0) { "gop 必须是正整数" }
        require(bitrate == null || bitrate > 0) { "bitrate 必须是正整数" }
        require(!textInput || keyInput) { "textInput 需要同时打开 keyInput" }
        require(deviceType == 4 || deviceType == 5) { "deviceType 只能是 4 或 5" }
    }
}
