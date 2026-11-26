package io.github.gcbond.openhc.core.nearby

import io.github.gcbond.openhc.core.common.MalformedMessageException

/**
 * 通道管理（外层类型 0）的载荷：一串 TLV，tag 成对出现，奇数是请求、偶数是对应的应答。
 *
 * | tag | 值 |
 * |---|---|
 * | 1 / 2 | 通道管理版本，小端无符号整数（手机发 5，车机自报 2） |
 * | 3 / 4 | 3：发起方的摘要文本（手机发的是 12 位十六进制加结尾 NUL）；4：应答方的 deviceId 文本 |
 * | 5 / 6 | 加密开关，首字节 0 = 明文，非 0 = 加密；收到后本连接立即切换 |
 * | 7 / 8 | 发送方的蓝牙地址文本（`AA:BB:CC:DD:EE:FF`） |
 * | 15 | connectId：6 位十进制文本，车机在连接建立后先发，收到的一方不处理 |
 *
 * 手机首包里另有 16（`&&` 分隔的文本）和 18（网卡列表），含义未知，车机不解析，这里归入 [Item.Unknown]。
 */
internal object ChannelManagement {
    const val OUTER_TYPE = 0

    const val TAG_VERSION_REQUEST = 1
    const val TAG_VERSION_RESPONSE = 2
    const val TAG_SUMMARY_REQUEST = 3
    const val TAG_SUMMARY_RESPONSE = 4
    const val TAG_ENCRYPTION_REQUEST = 5
    const val TAG_ENCRYPTION_RESPONSE = 6
    const val TAG_BLUETOOTH_REQUEST = 7
    const val TAG_BLUETOOTH_RESPONSE = 8
    const val TAG_CONNECT_ID = 15

    /** 车机回给对端的通道管理版本：固定为 2，不回显对端的版本。 */
    const val LOCAL_VERSION = 2

    const val CONNECT_ID_MIN = 100_000
    const val CONNECT_ID_MAX = 999_999

    sealed class Item {
        class Version(val request: Boolean, val version: Long) : Item()

        /** [request] 为真时是 tag 3（对端摘要），否则是 tag 4（对端 deviceId 文本）。 */
        class Summary(val request: Boolean, val raw: ByteArray) : Item() {
            val text: String get() = textOf(raw)
        }

        class Encryption(val request: Boolean, val enabled: Boolean) : Item()

        class BluetoothAddress(val request: Boolean, val raw: ByteArray) : Item() {
            val text: String get() = textOf(raw)
        }

        class ConnectId(val raw: ByteArray) : Item()

        class Unknown(val tlv: Tlv) : Item()
    }

    /** 把载荷解成条目，保持原顺序。任何一条不合法都抛 [MalformedMessageException]，此时整帧作废。 */
    fun decode(payload: ByteArray): List<Item> = Tlv.parseAll(payload).map { decodeItem(it) }

    fun connectIdValue(connectId: Int): ByteArray {
        require(connectId in CONNECT_ID_MIN..CONNECT_ID_MAX) { "connectId 应为 6 位十进制数: $connectId" }
        return connectId.toString().toByteArray(Charsets.US_ASCII)
    }

    private fun decodeItem(t: Tlv): Item {
        val v = t.valueView()
        return when (t.tag) {
            TAG_VERSION_REQUEST, TAG_VERSION_RESPONSE ->
                Item.Version(t.tag == TAG_VERSION_REQUEST, littleEndianVersion(t.tag, v))
            TAG_SUMMARY_REQUEST, TAG_SUMMARY_RESPONSE -> Item.Summary(t.tag == TAG_SUMMARY_REQUEST, v.copyOf())
            TAG_ENCRYPTION_REQUEST, TAG_ENCRYPTION_RESPONSE -> {
                if (v.isEmpty()) throw MalformedMessageException("加密开关 tag ${t.tag} 没有值")
                Item.Encryption(t.tag == TAG_ENCRYPTION_REQUEST, v[0].toInt() != 0)
            }
            TAG_BLUETOOTH_REQUEST, TAG_BLUETOOTH_RESPONSE ->
                Item.BluetoothAddress(t.tag == TAG_BLUETOOTH_REQUEST, v.copyOf())
            TAG_CONNECT_ID -> Item.ConnectId(v.copyOf())
            else -> Item.Unknown(t)
        }
    }

    private fun littleEndianVersion(tag: Int, v: ByteArray): Long {
        if (v.isEmpty() || v.size > 4) throw MalformedMessageException("版本 tag $tag 应为 1～4 字节，实际 ${v.size} 字节")
        var out = 0L
        for (i in v.indices.reversed()) out = (out shl 8) or (v[i].toLong() and 0xFF)
        return out
    }

    /** 文本字段按 UTF-8 解，去掉结尾的 NUL。 */
    fun textOf(raw: ByteArray): String {
        var end = raw.size
        while (end > 0 && raw[end - 1].toInt() == 0) end--
        return String(raw, 0, end, Charsets.UTF_8)
    }
}
