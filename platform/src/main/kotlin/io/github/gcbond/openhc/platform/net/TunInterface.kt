package io.github.gcbond.openhc.platform.net

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.Closeable
import java.io.FileDescriptor
import java.io.IOException
import java.io.InterruptedIOException

/**
 * 4.4 fork：TUN 描述符的统一入口，按 [PacketTunnel] 一次收发一个 IP 包。
 * 21+ 用 [TunInterface]（poll 模型，依赖 API 21 才开放的 android.system 包）；
 * 4.4 上没有 android.system，改用 [TunInterface19]（java.io 老路）。
 */
internal interface TunHandle : PacketTunnel, Closeable

/**
 * VpnService 建出的 TUN 描述符，按 [PacketTunnel] 一次收发一个 IP 包。描述符要是非阻塞的。
 *
 * 读用 poll 同时等 TUN 和一根唤醒管道，[wakeup] 往管道里写一个字节，阻塞中的读立刻返回。
 * 不能靠关 TUN 描述符来叫醒读线程：别的线程阻塞在读上时关描述符叫不醒它，描述符号还可能马上被系统复用。
 * 所以停止的顺序是：[wakeup] → 等读线程退出 → [close]。
 */
internal class TunInterface(private val descriptor: ParcelFileDescriptor) : TunHandle {

    private val fd: FileDescriptor = descriptor.fileDescriptor
    private val wakeRead: FileDescriptor
    private val wakeWrite: FileDescriptor
    private val lock = Any()

    @Volatile
    private var woken = false
    private var wakeSignalled = false
    private var closed = false

    init {
        val pipe = try {
            Os.pipe()
        } catch (e: ErrnoException) {
            throw IOException("建唤醒管道失败", e)
        }
        wakeRead = pipe[0]
        wakeWrite = pipe[1]
    }

    override fun read(buffer: ByteArray, offset: Int, maxLength: Int): Int {
        val tunPoll = StructPollfd().apply {
            fd = this@TunInterface.fd
            events = OsConstants.POLLIN.toShort()
        }
        val wakePoll = StructPollfd().apply {
            fd = wakeRead
            events = OsConstants.POLLIN.toShort()
        }
        val fds = arrayOf(tunPoll, wakePoll)
        while (!woken) {
            val ready = try {
                // 有唤醒管道时本可以无限等；留一个超时只是兜底，防止唤醒信号意外丢失时线程永远不退出。
                Os.poll(fds, POLL_TIMEOUT_MS)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR) continue
                return -1
            }
            if (woken || wakePoll.revents.toInt() != 0) return -1
            if (ready <= 0) continue
            val events = tunPoll.revents.toInt()
            if (events and OsConstants.POLLIN != 0) {
                try {
                    val n = Os.read(fd, buffer, offset, maxLength)
                    // TUN 正常不会读出 0 字节；读出 0 说明网卡已经没了。
                    return if (n > 0) n else -1
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) continue
                    return -1
                } catch (e: InterruptedIOException) {
                    return -1
                }
            }
            if (events and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0) return -1
        }
        return -1
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int): Boolean {
        if (woken) return false
        return try {
            Os.write(fd, buffer, offset, length) == length
        } catch (e: ErrnoException) {
            false
        } catch (e: InterruptedIOException) {
            false
        }
    }

    override fun wakeup() {
        woken = true
        synchronized(lock) {
            if (closed || wakeSignalled) return
            wakeSignalled = true
            try {
                Os.write(wakeWrite, WAKE_BYTE, 0, 1)
            } catch (e: ErrnoException) {
                // 写不进去时读线程最晚在 poll 超时后看到 woken 退出。
            } catch (e: InterruptedIOException) {
                // 同上。
            }
        }
    }

    /** 关闭 TUN 与唤醒管道。必须在读写线程都退出之后调用。可重复调用。 */
    override fun close() {
        wakeup()
        synchronized(lock) {
            if (closed) return
            closed = true
            closeQuietly(wakeRead)
            closeQuietly(wakeWrite)
            try {
                descriptor.close()
            } catch (e: IOException) {
                // 关闭失败没有补救办法。
            }
        }
    }

    private fun closeQuietly(fd: FileDescriptor) {
        try {
            Os.close(fd)
        } catch (e: ErrnoException) {
            // 同上。
        }
    }

    private companion object {
        const val POLL_TIMEOUT_MS = 1000
        val WAKE_BYTE = byteArrayOf(1)
    }
}
