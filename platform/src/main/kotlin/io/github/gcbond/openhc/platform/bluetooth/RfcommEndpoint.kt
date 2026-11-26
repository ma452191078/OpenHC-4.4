package io.github.gcbond.openhc.platform.bluetooth

/**
 * 一个 RFCOMM 监听和它接受的那条连接的事件。在监听自己的线程上按发生顺序回调：
 * `onListening` → `onAccepted` → `onData`… → `onClosed` / `onConnectionError`；开不了或等连接时出错是 `onListenFailed`。
 * 回调里不要阻塞。本机关掉之后不再回调（和关闭同时发生的个别回调可能漏过来，调用方按状态丢弃即可）。
 */
interface RfcommEvents {
    /** 监听开好了。[channel] 是系统分配的 RFCOMM 通道号（1～30），读不到为 0。 */
    fun onListening(channel: Int)

    /** 监听没开成，或等连接时出错（不是本机关的）。 */
    fun onListenFailed(message: String)

    /** 接受了一条连接；[remoteAddress] 是对端的蓝牙地址（读不到为空串）。 */
    fun onAccepted(remoteAddress: String)

    /** 收到的数据，新数组，归调用方。 */
    fun onData(data: ByteArray)

    /** 对端关闭了连接。 */
    fun onClosed()

    /** 连接上读写出错（不是本机关的）。 */
    fun onConnectionError(message: String)
}

/** 一个只接受一条连接的 RFCOMM 监听。方法都不阻塞，可以在任意线程调用。 */
interface RfcommEndpoint {
    /** 开始：开监听、等连接。只能调一次。 */
    fun start()

    /** 把 [bytes] 按顺序写到已接受的连接上；还没接受或已关闭时丢掉。写失败报 [RfcommEvents.onConnectionError]。 */
    fun write(bytes: ByteArray)

    /** 关监听：撤掉服务记录、不再接受连接。已经接受的连接不受影响。 */
    fun closeListener()

    /** 关连接：先把排着的写发完（有上限），再关。还没接受连接时，之后接受到的也直接关掉。 */
    fun closeConnection()
}
