package io.github.gcbond.openhc.platform.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.SystemClock
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue

/**
 * 经典蓝牙 RFCOMM 客户端：主动连手机的 RFCOMM 服务（不加密、不要求认证），用于车机主动回连，和 [RfcommServer] 相对。
 *
 * 怎么连：[channel] 为 null 时按 [uuid] 查手机的 SDP 记录再连（例如手机的蓝牙控制服务 FE35）。
 * 不为 null 时先按这个通道号直连（手机在应答里给的认证子通道；原厂也这么连，用的是系统的隐藏接口），
 * 拿不到这个接口时退回按 [uuid] 查 SDP（手机给这个通道也登记了 SDP 记录）。走的是哪条路经 [Events.onNote] 报出来。
 * 连之前先停掉本机的蓝牙扫描（扫描会拖慢连接）。
 *
 * 线程：[start] 之后一个线程「取设备 → 建 socket → connect → onConnected → 读」；写在另一个线程上排队。回调按
 * `onConnected` → `onData`… → `onClosed` / `onConnectionError` 的顺序发生；连不上是 `onConnectFailed`。
 * 读写循环、排队写、关闭逻辑和 [RfcommServer] 一致。本机关掉之后不再回调。
 *
 * 安卓 12 起连接要「附近的设备」权限（BLUETOOTH_CONNECT），没有时报 onConnectFailed。
 */
class RfcommClient(
    private val adapter: BluetoothAdapter,
    private val remoteAddress: String,
    private val uuid: UUID,
    private val events: Events,
    private val threadName: String = "OpenHC-RfcommClient",
    private val channel: Int? = null,
) : RfcommEndpoint {

    /** 主动连接的事件，在连接线程上按顺序回调，回调里不要阻塞。 */
    interface Events {
        /** 连上了。 */
        fun onConnected()

        /** 没连上（地址不对、对端没在听、没权限、SDP 查不到服务等）。 */
        fun onConnectFailed(message: String)

        /** 收到的数据，新数组，归调用方。 */
        fun onData(data: ByteArray)

        /** 对端关闭了连接。 */
        fun onClosed()

        /** 连接上读写出错（不是本机关的）。 */
        fun onConnectionError(message: String)

        /** 只用于诊断的说明（走的是哪条路之类），可以直接写日志，不含设备地址。 */
        fun onNote(message: String) {}
    }

    private val lock = Any()
    private var started = false
    private var socket: BluetoothSocket? = null
    private var closed = false

    private val writes = LinkedBlockingQueue<Any>()

    @Volatile
    private var lastWriteAt = 0L

    /** 开始：取设备、连接。只能调一次。 */
    override fun start() {
        synchronized(lock) {
            check(!started) { "start 只能调用一次" }
            started = true
        }
        daemon(threadName) { run() }
    }

    /** 把 [bytes] 按顺序写到连接上；已关闭时丢掉，还没连上时排队等连上再写。 */
    override fun write(bytes: ByteArray) {
        if (isClosed()) return
        writes.add(bytes)
    }

    /** 客户端没有监听，什么都不做。 */
    override fun closeListener() = Unit

    /** 关连接：先把排着的写发完（有上限），再关。还没连上时，连上后也直接关掉。 */
    override fun closeConnection() = close()

    fun close() {
        val client = synchronized(lock) {
            if (closed) return
            closed = true
            socket
        } ?: return
        writes.add(CLOSE)
        daemon("$threadName-close") {
            sleepQuietly(FLUSH_LIMIT_MS)
            closeQuietly(client)
        }
    }

    // 权限由调用方在开始前确认；没有时系统抛 SecurityException，按连接失败上报。
    @SuppressLint("MissingPermission")
    private fun run() {
        val device = try {
            adapter.getRemoteDevice(remoteAddress)
        } catch (e: IllegalArgumentException) {
            return failConnect("蓝牙地址不对：${describe(e)}")
        }
        try {
            adapter.cancelDiscovery()
        } catch (e: SecurityException) {
            // 安卓 12 起停扫描要扫描权限；没有就不停，只是连得慢些。
        }
        val client = connect(device) ?: return failConnect(connectError ?: "连接失败")
        val keep = synchronized(lock) {
            if (!closed) socket = client
            !closed
        }
        if (!keep) return closeQuietly(client)
        daemon("$threadName-write") { writeLoop(client) }
        if (!isClosed()) events.onConnected()
        readLoop(client)
    }

    /** 连接线程上记下最后一个失败原因（连接失败时上报给调用方）。 */
    private var connectError: String? = null

    /**
     * 取一条能用的连接。
     * 1. 指定了通道号（认证子通道，手机在应答里给的）：按它直连，拿不到系统接口时退回 SDP；
     * 2. 没指定（控制通道）：先按 SDP 查服务记录；
     * 3. SDP 查不到时，按 [CANDIDATE_CHANNELS] 里的通道号逐个直连兜底——部分车机（如安卓 4.4）的蓝牙栈
     *    查不到手机端的 HiCar 服务，但手机端服务的通道号是固定/相邻分配的，跳过 SDP 直连往往能通。
     */
    private fun connect(device: BluetoothDevice): BluetoothSocket? {
        val byChannel = channel
        if (byChannel != null) {
            connectByChannel(device, byChannel)?.let { return it }
            note("按通道号 $byChannel 直连没成，改按 SDP 查 $uuid")
        }
        connectBySdp(device)?.let { return it }
        if (byChannel == null) {
            note("SDP 没查到手机的服务，改按候选通道号直连：${CANDIDATE_CHANNELS.joinToString()}")
            for (ch in CANDIDATE_CHANNELS) {
                if (isClosed()) return null
                sleepQuietly(CHANNEL_RETRY_GAP_MS)
                connectByChannel(device, ch)?.let {
                    note("按通道号 $ch 直连成功")
                    return it
                }
            }
            note("候选通道号都不通")
        }
        return null
    }

    /** 按 SDP 查 [uuid] 的通道号再连；失败返回 null（原因记进 [connectError]）。 */
    @SuppressLint("MissingPermission")
    private fun connectBySdp(device: BluetoothDevice): BluetoothSocket? {
        val client = try {
            device.createInsecureRfcommSocketToServiceRecord(uuid)
        } catch (e: IOException) {
            connectError = "建 socket 失败：${describe(e)}"
            note(connectError!!)
            return null
        } catch (e: SecurityException) {
            connectError = "没有蓝牙权限（安卓 12 起要「附近的设备」权限）：${describe(e)}"
            note(connectError!!)
            return null
        }
        try {
            client.connect()
            note("按 SDP 连上（$uuid）")
            return client
        } catch (e: IOException) {
            closeQuietly(client)
            connectError = "连接失败（SDP）：${describe(e)}"
            note(connectError!!)
            return null
        } catch (e: SecurityException) {
            closeQuietly(client)
            connectError = "没有蓝牙权限：${describe(e)}"
            note(connectError!!)
            return null
        }
    }

    /** 按通道号直连；拿不到系统隐藏接口、连不上都返回 null。 */
    @SuppressLint("MissingPermission")
    private fun connectByChannel(device: BluetoothDevice, ch: Int): BluetoothSocket? {
        val client = try {
            val method = BluetoothDevice::class.java.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
            method.invoke(device, ch) as BluetoothSocket
        } catch (e: ReflectiveOperationException) {
            note("按通道号直连的系统接口用不了（${describe(e)}）")
            return null
        } catch (e: SecurityException) {
            note("按通道号直连被系统拦了（${describe(e)}）")
            return null
        }
        try {
            client.connect()
            return client
        } catch (e: IOException) {
            closeQuietly(client)
            connectError = "连接失败（通道号 $ch）：${describe(e)}"
            note(connectError!!)
            return null
        } catch (e: SecurityException) {
            closeQuietly(client)
            connectError = "按通道号 $ch 连接被系统拦了：${describe(e)}"
            note(connectError!!)
            return null
        }
    }

    private fun readLoop(client: BluetoothSocket) {
        val buffer = ByteArray(READ_BUFFER_BYTES)
        try {
            val input = client.inputStream
            while (true) {
                val n = input.read(buffer)
                if (n < 0) {
                    if (!isClosed()) events.onClosed()
                    return
                }
                if (n > 0 && !isClosed()) events.onData(buffer.copyOf(n))
            }
        } catch (e: IOException) {
            if (isClosed()) return
            if (e.message?.contains(PEER_CLOSED_TEXT) == true) events.onClosed() else events.onConnectionError("读失败：${describe(e)}")
        }
    }

    private fun writeLoop(client: BluetoothSocket) {
        try {
            val output = client.outputStream
            while (true) {
                val item = writes.take()
                if (item === CLOSE) break
                output.write(item as ByteArray)
                output.flush()
                lastWriteAt = SystemClock.elapsedRealtime()
            }
            val sinceWrite = SystemClock.elapsedRealtime() - lastWriteAt
            if (lastWriteAt != 0L && sinceWrite < LINGER_MS) sleepQuietly(LINGER_MS - sinceWrite)
        } catch (e: IOException) {
            if (!isClosed()) events.onConnectionError("写失败：${describe(e)}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        closeQuietly(client)
    }

    private fun failConnect(message: String) {
        if (!isClosed()) events.onConnectFailed(message)
    }

    private fun note(message: String) {
        if (!isClosed()) events.onNote(message)
    }

    private fun isClosed() = synchronized(lock) { closed }

    private companion object {
        val CLOSE = Any()
        const val FLUSH_LIMIT_MS = 1_500L
        const val LINGER_MS = 200L
        const val READ_BUFFER_BYTES = 4096
        const val PEER_CLOSED_TEXT = "read return: -1"

        /**
         * SDP 查不到手机端 HiCar 服务时按这些通道号直连兜底。手机端 FE35（控制）和 FE36（认证）通常相邻分配，
         * 实测认证子通道是 8，所以控制通道最可能是 7 或 8，其余是邻近的容错值。
         */
        val CANDIDATE_CHANNELS = intArrayOf(7, 8, 6, 9)

        /** 两个候选通道号之间稍等一下，别把蓝牙栈排满。 */
        const val CHANNEL_RETRY_GAP_MS = 150L

        fun daemon(name: String, body: () -> Unit) {
            val t = Thread(body, name)
            t.isDaemon = true
            t.start()
        }

        fun sleepQuietly(millis: Long) {
            try {
                Thread.sleep(millis)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        fun closeQuietly(closeable: Closeable) {
            try {
                closeable.close()
            } catch (e: IOException) {
                // 关闭时的错误没有意义。
            }
        }

        fun describe(e: Throwable): String = e.javaClass.simpleName + (e.message?.let { "：$it" } ?: "")
    }
}
