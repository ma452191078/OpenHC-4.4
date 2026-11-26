package io.github.gcbond.openhc.platform.usb

/**
 * 手机切成 USB 网卡后要用到的接口和端点。实测的组成：
 * ```
 * 接口 0 备用 0  CDC NCM 控制口（类 2 / 子类 13），一个中断 IN 端点（通知）
 * 接口 1 备用 0  CDC 数据口（类 10），没有端点
 * 接口 1 备用 1  CDC 数据口（类 10），批量 IN + 批量 OUT
 * ```
 * 收发数据前要抢下控制口和数据口，先切到数据口的备用 0（让 NCM 功能复位），再切到带端点的备用设置。
 */
data class NcmInterfaces(
    val control: UsbInterfaceDescription,
    /** 数据口没有端点的那个备用设置（一般是备用 0）；没有时为 null。 */
    val dataIdle: UsbInterfaceDescription?,
    /** 数据口带批量端点的那个备用设置。 */
    val dataActive: UsbInterfaceDescription,
    val bulkIn: UsbEndpointDescription,
    val bulkOut: UsbEndpointDescription,
    /** 控制口上的通知端点；没有时为 null。 */
    val notification: UsbEndpointDescription?,
) {
    companion object {
        /** 从设备描述里挑出 NCM 要用的接口；缺控制口、缺带批量 IN/OUT 的数据口时返回 null。 */
        fun select(device: UsbDeviceDescription): NcmInterfaces? {
            val control = device.interfaces.firstOrNull {
                it.interfaceClass == HuaweiPhone.CLASS_COMM && it.interfaceSubclass == HuaweiPhone.SUBCLASS_NCM
            } ?: return null
            val dataActive = device.interfaces.firstOrNull { iface ->
                iface.interfaceClass == HuaweiPhone.CLASS_CDC_DATA &&
                    iface.endpoints.any { it.isBulk && it.isIn } &&
                    iface.endpoints.any { it.isBulk && !it.isIn }
            } ?: return null
            val dataIdle = device.interfaces.firstOrNull {
                it.interfaceClass == HuaweiPhone.CLASS_CDC_DATA && it.id == dataActive.id && it.endpoints.isEmpty()
            }
            return NcmInterfaces(
                control = control,
                dataIdle = dataIdle,
                dataActive = dataActive,
                bulkIn = dataActive.endpoints.first { it.isBulk && it.isIn },
                bulkOut = dataActive.endpoints.first { it.isBulk && !it.isIn },
                notification = control.endpoints.firstOrNull { it.isInterrupt && it.isIn },
            )
        }
    }
}
