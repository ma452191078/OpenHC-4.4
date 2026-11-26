package io.github.gcbond.openhc.core.nearby

import java.util.UUID

/**
 * 车机 GATT 服务端对请求的回应规则（布局见 [NearbyGatt]）。只回应、不在 GATT 上跑控制通道：
 *
 * | 请求 | 回应 |
 * |---|---|
 * | 读 `046C` | 文本 `busy`（车机没有老版本的组网地址），支持带偏移的长读；偏移超出长度回「偏移无效」 |
 * | 读其他特征 | 「不允许读」，不带数据 |
 * | 写布局里的特征 | 成功（要回应时把原值带回去）；分段写（prepared write）先攒着，执行写时拼起来算一次写 |
 * | 写不认识的特征 | 「不允许写」 |
 * | 读、写 `2A01` / `2A03` 的 CCCD | 读回当前值；写只收偏移 0 的 2 字节，记下来（`2A01` 初值开、`2A03` 初值关） |
 *
 * 状态按客户端（地址文本）分开记，客户端断开就丢掉。收到的完整写入经 [Write.received] 交给调用方记日志。
 * 线程安全：方法都加锁（安卓的 GATT 回调在不同的线程上）。
 */
class NearbyGattResponder(
    /** 一次分段写最多攒多少字节，超出就拒绝（防止对端无限攒）。 */
    private val maxPreparedBytes: Int = DEFAULT_MAX_PREPARED_BYTES,
) {
    /** 回给客户端的状态码和值。 */
    class Reply(val status: Int, value: ByteArray?) {
        private val bytes = value?.copyOf()
        val value: ByteArray? get() = bytes?.copyOf()

        override fun toString() = "Reply(状态 $status, ${bytes?.size ?: 0} 字节)"
    }

    /** 写请求的回应，以及这次凑成的完整写入（分段写还没执行时为 null）。 */
    class Write(val reply: Reply, val characteristic: UUID?, received: ByteArray?) {
        private val data = received?.copyOf()
        val received: ByteArray? get() = data?.copyOf()
    }

    private class Client {
        val clientConfig = HashMap<UUID, ByteArray>()
        var preparedFor: UUID? = null
        var prepared = java.io.ByteArrayOutputStream()
    }

    private val clients = HashMap<String, Client>()

    @Synchronized
    fun connected(client: String) {
        clients.getOrPut(client) { Client() }
    }

    @Synchronized
    fun disconnected(client: String) {
        clients.remove(client)
    }

    /** 当前连着的客户端数。 */
    val connectedCount: Int @Synchronized get() = clients.size

    @Synchronized
    fun readCharacteristic(client: String, characteristic: UUID, offset: Int): Reply {
        if (characteristic != NearbyGatt.LEGACY_EXCHANGE) return Reply(STATUS_READ_NOT_PERMITTED, null)
        val value = NearbyGatt.LEGACY_BUSY
        if (offset < 0 || offset > value.size) return Reply(STATUS_INVALID_OFFSET, null)
        clients.getOrPut(client) { Client() }
        return Reply(STATUS_SUCCESS, value.copyOfRange(offset, value.size))
    }

    @Synchronized
    fun writeCharacteristic(client: String, characteristic: UUID, prepared: Boolean, offset: Int, value: ByteArray?): Write {
        val known = NearbyGatt.characteristic(characteristic)
        if (known == null || !known.writable) return Write(Reply(STATUS_WRITE_NOT_PERMITTED, null), characteristic, null)
        val data = value ?: ByteArray(0)
        if (!prepared) {
            if (offset != 0) return Write(Reply(STATUS_INVALID_OFFSET, null), characteristic, null)
            return Write(Reply(STATUS_SUCCESS, data), characteristic, data)
        }
        val state = clients.getOrPut(client) { Client() }
        if (state.preparedFor != null && state.preparedFor != characteristic) state.reset()
        if (offset != state.prepared.size()) return Write(Reply(STATUS_INVALID_OFFSET, null), characteristic, null)
        if (state.prepared.size() + data.size > maxPreparedBytes) {
            state.reset()
            return Write(Reply(STATUS_PREPARE_QUEUE_FULL, null), characteristic, null)
        }
        state.preparedFor = characteristic
        state.prepared.write(data, 0, data.size)
        return Write(Reply(STATUS_SUCCESS, data), characteristic, null)
    }

    /** 执行（[execute] 为 true）或取消分段写。 */
    @Synchronized
    fun executeWrite(client: String, execute: Boolean): Write {
        val state = clients[client] ?: return Write(Reply(STATUS_SUCCESS, null), null, null)
        val characteristic = state.preparedFor
        val data = state.prepared.toByteArray()
        state.reset()
        return if (execute && characteristic != null) {
            Write(Reply(STATUS_SUCCESS, null), characteristic, data)
        } else {
            Write(Reply(STATUS_SUCCESS, null), characteristic, null)
        }
    }

    @Synchronized
    fun readDescriptor(client: String, characteristic: UUID, descriptor: UUID, offset: Int): Reply {
        val initial = clientConfigInitial(characteristic, descriptor) ?: return Reply(STATUS_READ_NOT_PERMITTED, null)
        if (offset != 0) return Reply(STATUS_INVALID_OFFSET, null)
        val current = clients[client]?.clientConfig?.get(characteristic) ?: initial
        return Reply(STATUS_SUCCESS, current)
    }

    @Synchronized
    fun writeDescriptor(client: String, characteristic: UUID, descriptor: UUID, offset: Int, value: ByteArray?): Reply {
        clientConfigInitial(characteristic, descriptor) ?: return Reply(STATUS_WRITE_NOT_PERMITTED, null)
        if (offset != 0) return Reply(STATUS_INVALID_OFFSET, null)
        if (value == null || value.size != CLIENT_CONFIG_LENGTH) return Reply(STATUS_INVALID_ATTRIBUTE_LENGTH, null)
        clients.getOrPut(client) { Client() }.clientConfig[characteristic] = value.copyOf()
        return Reply(STATUS_SUCCESS, value)
    }

    /** 客户端有没有开 [characteristic] 的通知（没写过 CCCD 时按初值）。 */
    @Synchronized
    fun notificationsEnabled(client: String, characteristic: UUID): Boolean {
        val initial = NearbyGatt.characteristic(characteristic)?.clientConfig ?: return false
        val current = clients[client]?.clientConfig?.get(characteristic) ?: initial
        return (current[0].toInt() and 0x03) != 0
    }

    private fun clientConfigInitial(characteristic: UUID, descriptor: UUID): ByteArray? {
        if (descriptor != NearbyGatt.CLIENT_CONFIG) return null
        return NearbyGatt.characteristic(characteristic)?.clientConfig
    }

    private fun Client.reset() {
        preparedFor = null
        prepared = java.io.ByteArrayOutputStream()
    }

    companion object {
        /** 状态码（ATT 错误码，安卓 GATT 服务端回应时原样使用）。 */
        const val STATUS_SUCCESS = 0x00
        const val STATUS_READ_NOT_PERMITTED = 0x02
        const val STATUS_WRITE_NOT_PERMITTED = 0x03
        const val STATUS_INVALID_OFFSET = 0x07
        const val STATUS_PREPARE_QUEUE_FULL = 0x09
        const val STATUS_INVALID_ATTRIBUTE_LENGTH = 0x0D

        const val DEFAULT_MAX_PREPARED_BYTES = 4096
        private const val CLIENT_CONFIG_LENGTH = 2

        /** 控制帧的帧头字节。 */
        private const val FRAME_HEAD = 0x7F

        /** 一段写入的日志描述：长度，以及看起来是不是 nearby 控制帧。不输出内容。 */
        fun describe(data: ByteArray): String {
            if (data.isEmpty()) return "0 字节"
            val head = data[0].toInt() and 0xFF
            val looksLikeFrame = head == FRAME_HEAD
            return "${data.size} 字节，首字节 ${String.format(java.util.Locale.ROOT, "%02X", head)}" +
                if (looksLikeFrame) "（像 nearby 控制帧：手机想在 GATT 上跑控制通道）" else ""
        }
    }
}
