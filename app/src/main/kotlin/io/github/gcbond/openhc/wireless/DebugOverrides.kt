package io.github.gcbond.openhc.wireless

/**
 * 调试开关。debug 构建里经反射读系统属性（如 debug.openhc.rfcomm_sdp，让认证子通道改按 SDP 查）。
 *
 * 4.4 fork：debug、release 两个变体源集已随单源集构建一起删掉，main 里给发布语义的存根——开关一律关断。
 */
internal object DebugOverrides {

    /** 关断默认：认证子通道按手机报的通道号直连，不去查 SDP。 */
    const val forceRfcommSdp: Boolean = false
}
