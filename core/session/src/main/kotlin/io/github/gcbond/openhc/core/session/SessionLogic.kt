package io.github.gcbond.openhc.core.session

/**
 * 不做 I/O 的会话逻辑：收到 I/O 结果就调对应方法，按返回的 [SessionOutput] 依次执行动作。
 * [ReceiverSession] 是正式实现；[SocketRunner] 只认这个接口，方便单独测试执行器。
 *
 * 所有方法都要在同一个线程上串行调用（不是线程安全的）。时间一律是单调时钟毫秒。
 * 会话结束（输出过 [SessionEvent.Ended]）之后，所有方法都返回空列表。
 */
interface SessionLogic {
    /** 下次希望 [onTick] 被调用的时间；没有定时任务时为 null。 */
    val nextTickAtMillis: Long?

    /** 是否已经输出过 [SessionEvent.Ended]。 */
    val isEnded: Boolean

    /** 开始：返回第一批动作（开控制监听）。只能调用一次。 */
    fun start(nowMillis: Long): List<SessionOutput>

    /** [SessionAction.Listen] 已开好，[port] 是实际端口。 */
    fun onListening(id: Int, port: Int, nowMillis: Long): List<SessionOutput>

    /** 监听接受了一条连接，连接 id 是 [SessionAction.Listen.acceptId]；[remoteHost] 是对端 IPv4 文本。 */
    fun onAccepted(id: Int, remoteHost: String, nowMillis: Long): List<SessionOutput>

    /** [SessionAction.Connect] 已连上。 */
    fun onConnected(id: Int, nowMillis: Long): List<SessionOutput>

    /** 连接 [id] 收到的字节。调用返回后 [data] 可以被调用方复用，实现方不能留着它。 */
    fun onData(id: Int, data: ByteArray, offset: Int, length: Int, nowMillis: Long): List<SessionOutput>

    /** UDP socket [id] 收到的一个数据报；[data] 的复用规则同 [onData]。 */
    fun onDatagram(id: Int, data: ByteArray, offset: Int, length: Int, nowMillis: Long): List<SessionOutput>

    /** 连接 [id] 被对端关闭（读到 EOF）。 */
    fun onClosed(id: Int, nowMillis: Long): List<SessionOutput>

    /** [id] 的监听、连接、收发或 UDP 出错。[message] 是给日志看的简短说明。 */
    fun onIoError(id: Int, message: String, nowMillis: Long): List<SessionOutput>

    /** 推进定时任务。可以早调、晚调。 */
    fun onTick(nowMillis: Long): List<SessionOutput>

    /** 本机结束会话：尽量按协议收尾（例如投屏的 TEARDOWN），然后关闭全部资源。 */
    fun stop(nowMillis: Long): List<SessionOutput>
}
