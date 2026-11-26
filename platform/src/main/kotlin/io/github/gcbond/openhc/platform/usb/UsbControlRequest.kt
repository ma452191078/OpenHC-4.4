package io.github.gcbond.openhc.platform.usb

/** 一次 USB 控制传输的 Setup 参数（数据由调用方另给）。 */
data class UsbControlRequest(
    /** bmRequestType：方向、类型、接收者。 */
    val requestType: Int,
    /** bRequest。 */
    val request: Int,
    /** wValue。 */
    val value: Int,
    /** wIndex。 */
    val index: Int,
    val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
) {
    init {
        require(requestType in 0..0xFF && request in 0..0xFF) { "bmRequestType / bRequest 是 8 位" }
        require(value in 0..0xFFFF && index in 0..0xFFFF) { "wValue / wIndex 是 16 位" }
        require(timeoutMs > 0) { "超时必须为正: $timeoutMs" }
    }

    /** 数据方向是不是主机 → 设备。 */
    val isHostToDevice: Boolean get() = (requestType and 0x80) == 0

    companion object {
        const val DEFAULT_TIMEOUT_MS = 1000

        /**
         * 发 carInfo 用的控制传输：主机 → 设备、厂商请求、接收者为设备（0x40），bRequest 0x34，wValue 0，wIndex 0xA0，
         * 数据阶段就是 carInfo 本身（实测）。手机收到后约 1～2 秒重新枚举成 USB 网卡。
         */
        val CAR_INFO = UsbControlRequest(requestType = 0x40, request = 0x34, value = 0, index = 0xA0)
    }
}
