package io.github.gcbond.openhc.platform.usb

import io.github.gcbond.openhc.platform.net.NcmDataPath
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * 有线会话里「手机已连上的 TCP 网络」：车机地址已经配在 TUN 上，手机经 USB 网卡能连到它。
 *
 * 只对本应用有效：本应用发往这个网段的连接走 USB 网卡到手机，车机上别的应用看不到这个网络。
 */
class WiredNetwork internal constructor(
    /** 车机地址（与 carInfo 里的一致）。 */
    val carAddress: Inet4Address,
    val prefixLength: Int,
    private val phoneAddressSource: () -> ByteArray?,
    private val statsSource: () -> NcmDataPath.Stats,
) {
    /**
     * 在车机地址的 [port] 上开一个已经 bind 好的监听，由调用方 accept 和关闭。
     *
     * 在 [WiredSession.Callback.onNetworkReady] 里调用：那时会话还没开始收发，返回之后才开始，
     * 手机的第一个连接请求一定能被接住（实测手机收到 ARP 应答后几毫秒就发起连接，没人监听时内核会直接拒绝）。
     */
    @Throws(IOException::class)
    fun bindServerSocket(port: Int, backlog: Int = DEFAULT_BACKLOG): ServerSocket {
        require(port in 1..65535) { "端口非法: $port" }
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(carAddress, port), backlog)
        } catch (e: IOException) {
            socket.close()
            throw e
        }
        return socket
    }

    /** 手机在网卡上用的地址；手机还没发过包时为 null。手机连不上时会自己换地址重试。 */
    fun phoneAddress(): Inet4Address? = phoneAddressSource()?.let { InetAddress.getByAddress(it) as Inet4Address }

    /** 数据面计数，排查用。 */
    fun stats(): NcmDataPath.Stats = statsSource()

    private companion object {
        const val DEFAULT_BACKLOG = 4
    }
}
