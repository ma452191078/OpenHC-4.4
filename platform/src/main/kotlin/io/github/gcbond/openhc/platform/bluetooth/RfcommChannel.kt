package io.github.gcbond.openhc.platform.bluetooth

import android.bluetooth.BluetoothServerSocket
import android.os.Build
import java.lang.reflect.Field

/**
 * 读 RFCOMM 监听的实际通道号（无线连接要把认证子通道的通道号告诉手机，手机按通道号直连、不查 SDP）。
 *
 * 按 UUID 开的监听，公开接口拿不到系统分配的通道号：`getPsm()` 和 `toString()` 里的通道读的是服务端 socket 自己的字段，
 * 这种开法下一直是 -1。开监听时协议栈把实际通道号写回的是它内部那个 socket 的端口字段，所以先用反射读
 * 「服务端 socket 的 `mSocket` 字段 → 内部 socket 的 `mPort` 字段」。这两个字段在系统的非公开接口名单里不限 targetSdk，
 * 安卓 10 车机上以 targetSdk 36 实测读得到；以后的系统要是拦了，反射失败，再退回公开接口（多半还是 -1）。
 */
object RfcommChannel {
    const val MIN = 1
    const val MAX = 30

    /** 读到的有效通道号；读不到返回 0。 */
    fun read(server: BluetoothServerSocket): Int {
        innerPort(server)?.takeIf { isValid(it) }?.let { return it }
        val public = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) server.psm else channelFromText(server.toString())
        } catch (e: RuntimeException) {
            null
        }
        return public?.takeIf { isValid(it) } ?: 0
    }

    fun isValid(channel: Int): Boolean = channel in MIN..MAX

    /** 反射读 [outer] 的 `mSocket` 字段，再读它的 `mPort` 字段（沿父类找）；任何一步不行返回 null。 */
    fun innerPort(outer: Any): Int? = try {
        val inner = field(outer.javaClass, "mSocket")?.get(outer)
        if (inner == null) null else field(inner.javaClass, "mPort")?.getInt(inner)
    } catch (e: ReflectiveOperationException) {
        null
    } catch (e: RuntimeException) {
        // 被非公开接口限制拦下、字段类型不对等。
        null
    }

    /** 公开的 `toString()` 里形如 `Channel: 8` 的一段。 */
    fun channelFromText(text: String?): Int? =
        text?.let { CHANNEL_TEXT.find(it) }?.groupValues?.get(1)?.toIntOrNull()

    private val CHANNEL_TEXT = Regex("Channel: (-?\\d+)")

    private fun field(start: Class<*>, name: String): Field? {
        var c: Class<*>? = start
        while (c != null) {
            try {
                return c.getDeclaredField(name).apply { isAccessible = true }
            } catch (e: NoSuchFieldException) {
                c = c.superclass
            }
        }
        return null
    }
}
