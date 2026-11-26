package io.github.gcbond.openhc.core.nearby

import io.github.gcbond.openhc.core.common.identity.BluetoothAddress
import io.github.gcbond.openhc.core.common.identity.HostIdentity

/**
 * 扫码配对的二维码内容：一条深链接，参数顺序固定，**不做 URL 编码**：
 *
 * ```
 * hicar://car/connect?devicename=<名称>&mac=<车机蓝牙地址>&moduleid=<车型编号前 6 位>&submoduleid=<后 2 位>&pin=<连接码>&ability=<能力>
 * ```
 *
 * - `mac`：车机经典蓝牙公共地址，大写、冒号分隔。手机直接按它连车机的蓝牙，也用它和车型编号算「车机 ID」
 *   决定要不要输码，所以必须是真实地址，而且要和握手里 deviceId 用的是同一个。
 * - `moduleid` / `submoduleid`：车型编号拆成 6 + 2 位，与 deviceId、认证里的车型同源。
 * - `pin`：本次首次配对用的 6 位连接码（保留前导 0），和屏幕上显示的是同一个；扫码时手机自动带上。
 * - `ability`：只支持无线填 `4`，同时支持 USB 填 `6`（逐位含义未查明）。
 *
 * 无状态，线程安全。
 */
object PairingQrCode {
    const val ABILITY_WIRELESS = "4"
    const val ABILITY_WIRELESS_AND_USB = "6"

    private const val PREFIX = "hicar://car/connect?"

    /**
     * 生成二维码内容。
     *
     * @param bluetoothAddress 车机蓝牙地址（`AA:BB:CC:DD:EE:FF`，大小写、`-` 分隔都行）；取不到真实地址时不能生成。
     * @param pinCode 6 位数字连接码。
     * @param supportsUsb 本机是否也支持 USB 连接（决定 `ability`）。
     * @param deviceName 手机上显示的车机名；默认取身份资产里的名称，见 [displayName]。
     * @throws IllegalArgumentException 地址格式不对或是占位地址、连接码不是 6 位数字。
     */
    fun build(
        host: HostIdentity,
        bluetoothAddress: String,
        pinCode: String,
        supportsUsb: Boolean,
        deviceName: String = host.deviceName,
    ): String {
        val address = BluetoothAddress.format(BluetoothAddress.parse(bluetoothAddress))
        require(address != BluetoothAddress.UNAVAILABLE) { "二维码里必须是车机真实的蓝牙地址" }
        require(pinCode.length == 6 && pinCode.all { it in '0'..'9' }) { "连接码必须是 6 位数字" }
        val ability = if (supportsUsb) ABILITY_WIRELESS_AND_USB else ABILITY_WIRELESS
        return PREFIX + "devicename=" + displayName(deviceName) + "&mac=" + address +
            "&moduleid=" + host.modelId + "&submoduleid=" + host.subModelId +
            "&pin=" + pinCode + "&ability=" + ability
    }

    /**
     * 车机名放进不编码的查询串：会打断解析的字符（`& = # % + ?`、空白和控制字符）换成 `_`，其余原样。
     * 名称只在手机上显示，建议用 ASCII。
     */
    fun displayName(name: String): String {
        val sb = StringBuilder(name.length)
        for (c in name.trim()) sb.append(if (c in UNSAFE || c.isWhitespace() || c.isISOControl()) '_' else c)
        require(sb.isNotEmpty()) { "车机名不能为空" }
        return sb.toString()
    }

    private const val UNSAFE = "&=#%+?"
}
