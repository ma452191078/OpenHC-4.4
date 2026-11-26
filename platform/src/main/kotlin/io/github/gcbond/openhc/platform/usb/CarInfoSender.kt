package io.github.gcbond.openhc.platform.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

/**
 * 把 carInfo 用一次控制传输发给普通状态的手机，发完立刻关掉连接（之后手机会断开、重新枚举成 USB 网卡）。
 * 这是发给设备本身的厂商请求，不需要占用任何接口。
 */
internal object CarInfoSender {

    /** 打不开设备（没有授权或已拔出）。 */
    const val OPEN_FAILED = -2

    /** 返回控制传输的结果：实际发出的字节数；失败为负数。 */
    fun send(usbManager: UsbManager, device: UsbDevice, request: UsbControlRequest, payload: ByteArray): Int {
        val connection = try {
            usbManager.openDevice(device)
        } catch (e: RuntimeException) {
            null
        } ?: return OPEN_FAILED
        return try {
            connection.controlTransfer(
                request.requestType,
                request.request,
                request.value,
                request.index,
                payload,
                payload.size,
                request.timeoutMs,
            )
        } catch (e: RuntimeException) {
            -1
        } finally {
            connection.close()
        }
    }
}
