package io.github.gcbond.openhc.platform.usb

import android.hardware.usb.UsbDevice
import android.os.Build

/** 安卓 `UsbDevice` → [UsbDeviceDescription]。 */
object UsbDescriber {

    fun describe(device: UsbDevice): UsbDeviceDescription {
        // 4.4 fork：KitKat 的 UsbInterface 没有 getAlternateSetting（API 21 才加）。
        // 4.4 的枚举把每个备用设置都当成一个接口条目、顺序与描述符一致，
        // 同一接口号内第几次出现就是备用几（NCM 数据口：备用 0 在前、备用 1 在后）。
        val alternates = HashMap<Int, Int>()
        val interfaces = (0 until device.interfaceCount).map { i ->
            val iface = device.getInterface(i)
            UsbInterfaceDescription(
                index = i,
                id = iface.id,
                alternateSetting = if (Build.VERSION.SDK_INT >= 21) {
                    iface.alternateSetting
                } else {
                    val next = (alternates[iface.id] ?: -1) + 1
                    alternates[iface.id] = next
                    next
                },
                interfaceClass = iface.interfaceClass,
                interfaceSubclass = iface.interfaceSubclass,
                interfaceProtocol = iface.interfaceProtocol,
                endpoints = (0 until iface.endpointCount).map { e ->
                    val endpoint = iface.getEndpoint(e)
                    UsbEndpointDescription(
                        index = e,
                        address = endpoint.address,
                        type = endpoint.type,
                        maxPacketSize = endpoint.maxPacketSize,
                    )
                },
            )
        }
        return UsbDeviceDescription(device.vendorId, device.productId, device.deviceClass, interfaces)
    }

    /** 这台设备从有线投屏的角度看处于哪种状态，给界面显示用。 */
    fun phoneMode(device: UsbDevice): PhoneUsbMode =
        if (!HuaweiPhone.isHuaweiVendor(device.vendorId)) PhoneUsbMode.NOT_A_PHONE else HuaweiPhone.classify(describe(device))

    /** 日志用的简短描述：厂商号、产品号、接口类别。不含序列号等设备标识。 */
    fun summary(device: UsbDevice): String {
        val d = describe(device)
        val interfaces = d.interfaces.joinToString(" ") { "${it.interfaceClass}/${it.interfaceSubclass}" }
        return "%04x:%04x 类 %d 接口 [%s]".format(java.util.Locale.ROOT, d.vendorId, d.productId, d.deviceClass, interfaces)
    }
}
