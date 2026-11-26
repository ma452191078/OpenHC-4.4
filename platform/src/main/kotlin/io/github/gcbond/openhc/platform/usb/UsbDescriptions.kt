package io.github.gcbond.openhc.platform.usb

/**
 * USB 设备的描述信息，与安卓的 `UsbDevice` 一一对应但不依赖安卓，识别和挑接口的逻辑因此能在电脑上测试。
 * 安卓对象到这里的转换见 [UsbDescriber]。
 */
data class UsbDeviceDescription(
    val vendorId: Int,
    val productId: Int,
    val deviceClass: Int,
    /** 按 `UsbDevice.getInterface(i)` 的顺序；同一个接口的每个备用设置各占一项。 */
    val interfaces: List<UsbInterfaceDescription>,
)

data class UsbInterfaceDescription(
    /** 在 `UsbDevice.getInterface(i)` 里的下标 i。 */
    val index: Int,
    /** 接口号（bInterfaceNumber）。 */
    val id: Int,
    val alternateSetting: Int,
    val interfaceClass: Int,
    val interfaceSubclass: Int,
    val interfaceProtocol: Int,
    val endpoints: List<UsbEndpointDescription>,
)

data class UsbEndpointDescription(
    /** 在 `UsbInterface.getEndpoint(i)` 里的下标 i。 */
    val index: Int,
    /** 端点地址，bit7 为 1 表示 IN（设备 → 主机）。 */
    val address: Int,
    /** 传输类型：0 控制、1 等时、2 批量、3 中断（与 `UsbConstants.USB_ENDPOINT_XFER_*` 相同）。 */
    val type: Int,
    val maxPacketSize: Int,
) {
    val isIn: Boolean get() = (address and 0x80) != 0
    val isBulk: Boolean get() = type == TYPE_BULK
    val isInterrupt: Boolean get() = type == TYPE_INTERRUPT

    companion object {
        const val TYPE_BULK = 2
        const val TYPE_INTERRUPT = 3
    }
}
