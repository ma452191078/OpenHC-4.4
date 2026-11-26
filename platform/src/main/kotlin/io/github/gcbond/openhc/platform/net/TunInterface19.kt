package io.github.gcbond.openhc.platform.net

import android.os.ParcelFileDescriptor
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * 4.4 fork：安卓 4.4（API 19）上的 [TunHandle] 实现。
 *
 * 4.4 没有 `android.system` 包（poll/Os 是 API 21 才开放的），TUN 描述符只能用 java.io 老路读写。
 * KitKat 的 VpnService 建出的描述符本来就是非阻塞的（`com_android_server_connectivity_Vpn.cpp`
 * 里 `open("/dev/tun", O_RDWR | O_NONBLOCK)`），所以不需要唤醒管道：读不到数据时歇一会儿再试，
 * [wakeup] 就是设一个标志位，读循环最晚一个重试周期内退出，关闭时不会有卡在内核里的线程。
 */
internal class TunInterface19(private val descriptor: ParcelFileDescriptor) : TunHandle {

    private val input: FileInputStream = NonClosingInputStream(descriptor.fileDescriptor)
    private val output: FileOutputStream = NonClosingOutputStream(descriptor.fileDescriptor)

    @Volatile
    private var woken = false

    private var closed = false

    override fun read(buffer: ByteArray, offset: Int, maxLength: Int): Int {
        while (!woken) {
            val n = try {
                input.read(buffer, offset, maxLength)
            } catch (e: IOException) {
                // 老 IoBridge 把非阻塞读的 EAGAIN 折算成返回 0，能到这里的就是真错误（描述符已关等）。
                return -1
            }
            if (n > 0) return n
            if (n < 0) return -1 // 读出 0 字节：网卡没了（IoBridge 用 -1 表示 EOF）。
            // n == 0：非阻塞描述符暂时没数据。歇一会儿再试，免得空转烧 CPU。
            try {
                Thread.sleep(IDLE_RETRY_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return -1
            }
        }
        return -1
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int): Boolean {
        if (woken) return false
        return try {
            // 老 IoBridge.write 自己循环到写满；TUN 发送队列满时会抛 IOException（EAGAIN），
            // 按约定当作写不进去，包被丢弃。
            output.write(buffer, offset, length)
            true
        } catch (e: IOException) {
            false
        }
    }

    override fun wakeup() {
        woken = true
    }

    /** 关闭 TUN。可重复调用。 */
    override fun close() {
        wakeup()
        synchronized(this) {
            if (closed) return
            closed = true
            try {
                descriptor.close()
            } catch (e: IOException) {
                // 关闭失败没有补救办法。
            }
        }
    }

    private companion object {
        /** 非阻塞读空转时的重试间隔（毫秒）。 */
        const val IDLE_RETRY_MS = 10L
    }
}

/**
 * 4.4 fork：描述符的生命周期全归 [ParcelFileDescriptor]（与 21+ 的 [TunInterface] 一致），
 * 所以这两个流不许在 finalize/close 时把描述符关掉——否则 PFD 关掉、描述符号被系统复用后，
 * GC 触发的 finalize 会误关别的线程正在用的描述符。
 */
private class NonClosingInputStream(fd: FileDescriptor) : FileInputStream(fd) {
    override fun close() {}
}

/** 同上。 */
private class NonClosingOutputStream(fd: FileDescriptor) : FileOutputStream(fd) {
    override fun close() {}
}
