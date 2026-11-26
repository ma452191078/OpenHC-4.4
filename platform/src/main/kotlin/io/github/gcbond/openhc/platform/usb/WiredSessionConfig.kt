package io.github.gcbond.openhc.platform.usb

import android.app.PendingIntent
import io.github.gcbond.openhc.platform.net.EthernetEndpoint
import java.net.Inet4Address

/**
 * 一次有线会话的参数。
 *
 * @param carInfo 要发给手机的 carInfo（由 core:nearby 生成）。
 * @param carAddress 车机在 USB 网卡上的 IPv4 地址，必须与 carInfo 里写的一致：手机会在网卡上用 ARP 找这个地址，
 *   再用 TCP 连 carInfo 里的端口（实测手机与车机在同一个 /24 网段里，手机自己选地址）。
 * @param prefixLength 网段前缀长度；TUN 只路由这个网段。
 * @param carInfoRequest 发 carInfo 用的控制传输参数。
 * @param vpnSessionName 系统 VPN 通知和设置里显示的名字。
 * @param vpnConfigureIntent 用户在系统 VPN 设置里点本连接时打开的界面；不需要时为 null。
 * @param carMac 车机这一端在 USB 网卡上的 MAC；null 表示用 [EthernetEndpoint.DEFAULT_CAR_MAC]。
 */
class WiredSessionConfig(
    carInfo: ByteArray,
    val carAddress: Inet4Address,
    val prefixLength: Int = DEFAULT_PREFIX_LENGTH,
    val carInfoRequest: UsbControlRequest = UsbControlRequest.CAR_INFO,
    val timeouts: WiredTimeouts = WiredTimeouts(),
    val vpnSessionName: String = DEFAULT_VPN_SESSION_NAME,
    val vpnConfigureIntent: PendingIntent? = null,
    carMac: ByteArray? = null,
) {
    /** 副本，调用方之后改原数组不影响会话。 */
    val carInfo: ByteArray = carInfo.copyOf()
    val carMac: ByteArray = (carMac ?: EthernetEndpoint.DEFAULT_CAR_MAC).copyOf()

    init {
        // 控制传输的数据阶段最长 4096 字节（Linux usbfs 的上限）。
        require(carInfo.size in 1..MAX_CAR_INFO_LENGTH) { "carInfo 长度非法: ${carInfo.size}" }
        require(carInfoRequest.isHostToDevice) { "发 carInfo 的控制传输必须是主机 → 设备" }
        require(prefixLength in 8..30) { "前缀长度非法: $prefixLength" }
        val host = hostPart(carAddress, prefixLength)
        val allOnes = (1L shl (32 - prefixLength)) - 1
        require(host != 0L && host != allOnes) { "车机地址不能是网络地址或广播地址: ${carAddress.hostAddress}/$prefixLength" }
        require(this.carMac.size == 6 && (this.carMac[0].toInt() and 0x01) == 0) { "车机 MAC 必须是 6 字节单播地址" }
    }

    /** 网络地址（主机部分清零），TUN 路由用。 */
    val networkAddress: Inet4Address
        get() {
            val bytes = carAddress.address
            val mask = if (prefixLength == 0) 0L else (0xFFFFFFFFL shl (32 - prefixLength)) and 0xFFFFFFFFL
            val value = toLong(bytes) and mask
            return java.net.InetAddress.getByAddress(
                byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte()),
            ) as Inet4Address
        }

    private companion object {
        const val DEFAULT_PREFIX_LENGTH = 24
        const val DEFAULT_VPN_SESSION_NAME = "OpenHC"
        const val MAX_CAR_INFO_LENGTH = 4096

        fun toLong(bytes: ByteArray): Long =
            ((bytes[0].toLong() and 0xFF) shl 24) or ((bytes[1].toLong() and 0xFF) shl 16) or
                ((bytes[2].toLong() and 0xFF) shl 8) or (bytes[3].toLong() and 0xFF)

        fun hostPart(address: Inet4Address, prefixLength: Int): Long =
            toLong(address.address) and ((1L shl (32 - prefixLength)) - 1)
    }
}

/**
 * 会话各阶段的等待上限（毫秒）。默认值按实测定：
 * - carInfo 发出后手机通常 1～4 秒切成 USB 网卡；第一次连某台车机时要等用户在手机上点「连接」；
 * - 手机切过来后，约 19 秒内没人连上就会放弃并退回普通状态，所以第二次 USB 授权不必久等。
 */
class WiredTimeouts(
    /** 等手机插上；0 表示一直等到 [WiredSession.stop]。 */
    val phoneWaitMs: Long = 0,
    /** 等用户点普通状态手机的 USB 授权框。 */
    val phonePermissionMs: Long = 60_000,
    /** carInfo 发出后多久把状态标成「慢」（[WiredState.WaitingForPhoneSwitch.slow]）。 */
    val phoneSwitchHintMs: Long = 8_000,
    /** carInfo 发出后等手机切成 USB 网卡的上限。 */
    val phoneSwitchMs: Long = 90_000,
    /** 等用户点 USB 网卡的授权框。 */
    val usbNetworkPermissionMs: Long = 30_000,
    /**
     * 没有授权时先等这么久，看系统会不会自动授权，再弹授权框。只有应用在清单里给手机声明了 USB 设备过滤器、
     * 用户勾过「默认使用」，系统才会自动授权，而且比设备接入广播晚几十到一百多毫秒（实测），
     * 那时设成 800 左右能免掉一个多余的授权框；没声明过滤器时等也白等，所以默认 0。
     */
    val permissionGraceMs: Long = 0,
    /** 建 TUN 的上限。 */
    val vpnEstablishMs: Long = 5_000,
) {
    init {
        require(phoneWaitMs >= 0 && phonePermissionMs > 0 && phoneSwitchHintMs > 0 && phoneSwitchMs > 0) { "超时非法" }
        require(usbNetworkPermissionMs > 0 && permissionGraceMs >= 0 && vpnEstablishMs > 0) { "超时非法" }
    }
}
