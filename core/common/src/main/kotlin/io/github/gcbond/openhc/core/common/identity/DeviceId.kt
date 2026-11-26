package io.github.gcbond.openhc.core.common.identity

import io.github.gcbond.openhc.core.common.hexToBytes
import io.github.gcbond.openhc.core.common.toHex
import java.security.MessageDigest

/**
 * 车机的设备 ID：carInfo、控制通道握手、配对认证和设备会话里的本机标识都是它，几处必须同源。
 *
 * ```
 * 设备 ID = SHA-256( UTF-8( 大写十六进制( 车型编号 3 字节 ‖ 子编号 1 字节 ‖ 蓝牙地址 6 字节 ) ) )
 * ```
 *
 * 取 32 字节原值；文本形式是它的 64 位大写十六进制。车型编号、子编号来自身份资产（[HostIdentity]），
 * 蓝牙地址按书写顺序（见 [BluetoothAddress]）。
 *
 * **退化规则**：车机蓝牙地址取不到（传 null）或是 [BluetoothAddress.UNAVAILABLE] 时，哈希输入里的
 * 蓝牙地址改用 6 个 0 字节（所以全 0 地址算出来的结果也一样）。线上的地址字段填什么由调用方决定，不归这里管。
 *
 * 无状态，线程安全。
 */
object DeviceId {
    /** 原值长度（字节）。 */
    const val SIZE = 32

    private val UNAVAILABLE_ADDRESS = byteArrayOf(2, 0, 0, 0, 0, 0)

    /**
     * 由身份资产和车机蓝牙地址文本算出 64 位大写十六进制的设备 ID。
     *
     * @param bluetoothAddress `AA:BB:CC:DD:EE:FF`（格式见 [BluetoothAddress.parse]）；取不到传 null。
     * @throws IllegalArgumentException 蓝牙地址格式不对。
     */
    fun hex(host: HostIdentity, bluetoothAddress: String?): String {
        val address = if (bluetoothAddress == null) UNAVAILABLE_ADDRESS else BluetoothAddress.parse(bluetoothAddress)
        val subModelId = host.subModelId.hexToBytes()[0].toInt() and 0xFF
        return derive(host.modelId.hexToBytes(), subModelId, address).toHex(upperCase = true)
    }

    /**
     * 由各字段的原始字节算出 32 字节设备 ID（[bluetoothAddress] 是 [BluetoothAddress.UNAVAILABLE] 时按退化规则）。
     *
     * @param modelId 车型编号前 3 字节。
     * @param subModelId 车型子编号，0..255。
     * @param bluetoothAddress 车机蓝牙地址 6 字节。
     * @throws IllegalArgumentException 长度或取值范围不对。
     */
    fun derive(modelId: ByteArray, subModelId: Int, bluetoothAddress: ByteArray): ByteArray {
        require(modelId.size == 3) { "车型编号应为 3 字节" }
        require(subModelId in 0..0xFF) { "子编号应为 1 字节" }
        require(bluetoothAddress.size == BluetoothAddress.SIZE) { "蓝牙地址应为 ${BluetoothAddress.SIZE} 字节" }
        val forHash = if (bluetoothAddress.contentEquals(UNAVAILABLE_ADDRESS)) ByteArray(BluetoothAddress.SIZE) else bluetoothAddress
        val text = (modelId + byteArrayOf(subModelId.toByte()) + forHash).toHex(upperCase = true)
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    }
}
