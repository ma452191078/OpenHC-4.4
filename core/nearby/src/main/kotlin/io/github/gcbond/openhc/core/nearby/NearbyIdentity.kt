package io.github.gcbond.openhc.core.nearby

import io.github.gcbond.openhc.core.common.hexToBytes
import io.github.gcbond.openhc.core.common.identity.BluetoothAddress
import io.github.gcbond.openhc.core.common.identity.DeviceId
import io.github.gcbond.openhc.core.common.identity.HostIdentity
import io.github.gcbond.openhc.core.common.toHex

/**
 * nearby 层用到的本机身份：车型编号、子编号、车机蓝牙地址，以及由它们算出的 deviceId。
 * carInfo、控制通道握手里的 deviceId 文本和蓝牙地址文本都从这里取，保证同源。
 */
internal class NearbyIdentity(host: HostIdentity, bluetoothAddress: String?) {
    /** 车型编号前 3 字节。 */
    val modelId: ByteArray = host.modelId.hexToBytes()

    /** 车型子编号（1 字节）。 */
    val subModelId: Int = host.subModelId.hexToBytes()[0].toInt() and 0xFF

    /**
     * 线上发出去的蓝牙地址（6 字节）。取不到（传 null）时 OpenHC 发 `02:00:00:00:00:00`，
     * 也就是安卓对普通应用隐藏真实地址时给的值；车机在这种情况下应该填什么还没有确认。
     */
    val bluetoothAddress: ByteArray =
        if (bluetoothAddress == null) UNAVAILABLE_ADDRESS.copyOf() else BluetoothAddress.parse(bluetoothAddress)

    /** 蓝牙地址文本：大写、冒号分隔，例如 `0A:1B:2C:3D:4E:5F`。 */
    val bluetoothAddressText: String = BluetoothAddress.format(this.bluetoothAddress)

    /**
     * 32 字节 deviceId，算法（含取不到地址时的退化规则）见 core:common 的 [DeviceId]：
     * 线上地址是 `02:00:00:00:00:00` 时哈希改用 6 个 0 字节，线上的地址字段照填原值。
     */
    val deviceId: ByteArray = DeviceId.derive(modelId, subModelId, this.bluetoothAddress)

    /** deviceId 的 64 位大写十六进制文本：握手里回给手机的就是它，认证层也用它当本机标识。 */
    val deviceIdHex: String = deviceId.toHex(upperCase = true)

    override fun toString(): String = "NearbyIdentity(deviceId=${deviceIdHex.take(4)}…，内容不全显示)"

    private companion object {
        /** 取不到车机蓝牙地址时线上填的值。 */
        val UNAVAILABLE_ADDRESS = byteArrayOf(2, 0, 0, 0, 0, 0)
    }
}
