package io.github.gcbond.openhc.core.nearby

import io.github.gcbond.openhc.core.common.ByteReader
import io.github.gcbond.openhc.core.common.ByteWriter
import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.common.codec.Ipv4Text

/**
 * carInfo：车机经 USB 控制传输交给手机的 91 字节结构，手机据此切成 USB 网卡，再来连车机的 IP:端口。
 *
 * 布局（多字节整数都是小端）：
 * ```
 * "hwnearby" | u16 总长 91 | 9 字节固定头（u16 1、u16 0x0100、u16 1、00 01 01，含义未知）
 * 之后是 TLV，tag 和长度各 1 字节（不是 EA）：
 *   01 03 车型编号前 3 字节    02 01 子编号 1 字节      03 20 deviceId 32 字节
 *   04 06 蓝牙地址 6 字节      06 0E 车机 IP 文本 14 字节  07 04 端口 u32
 * ```
 * IP 文本不补齐，长度必须正好 14 字符，所以总长固定为 91。车机端取 `192.168.66.` 加
 * 100～253 的末段，正好 14 字符；手机接不接受别的长度没有验证过。
 */
internal class CarInfo(
    modelId: ByteArray,
    val subModelId: Int,
    deviceId: ByteArray,
    bluetoothAddress: ByteArray,
    val hostIp: String,
    val port: Int,
) {
    private val model = modelId.copyOf()
    private val id = deviceId.copyOf()
    private val bt = bluetoothAddress.copyOf()

    init {
        require(model.size == MODEL_ID_SIZE) { "车型编号应为 $MODEL_ID_SIZE 字节" }
        require(subModelId in 0..0xFF) { "子编号应为 1 字节" }
        require(id.size == DEVICE_ID_SIZE) { "deviceId 应为 $DEVICE_ID_SIZE 字节" }
        require(bt.size == BLUETOOTH_SIZE) { "蓝牙地址应为 $BLUETOOTH_SIZE 字节" }
        requireValidHostIp(hostIp)
        requireValidPort(port)
    }

    val modelId: ByteArray get() = model.copyOf()
    val deviceId: ByteArray get() = id.copyOf()
    val bluetoothAddress: ByteArray get() = bt.copyOf()

    fun encode(): ByteArray {
        val w = ByteWriter(LENGTH)
            .bytes(MAGIC)
            .u16Le(LENGTH)
            .bytes(FIXED_HEADER)
        field(w, TAG_MODEL_ID, model)
        field(w, TAG_SUB_MODEL_ID, byteArrayOf(subModelId.toByte()))
        field(w, TAG_DEVICE_ID, id)
        field(w, TAG_BLUETOOTH, bt)
        field(w, TAG_HOST_IP, hostIp.toByteArray(Charsets.US_ASCII))
        field(w, TAG_PORT, ByteWriter(4).u32Le(port.toLong()).toByteArray())
        val out = w.toByteArray()
        check(out.size == LENGTH) { "carInfo 长度应为 $LENGTH，实际 ${out.size}" }
        return out
    }

    override fun toString(): String = "CarInfo(${hostIp}:$port，标识类字段不显示)"

    companion object {
        const val LENGTH = 91

        const val TAG_MODEL_ID = 1
        const val TAG_SUB_MODEL_ID = 2
        const val TAG_DEVICE_ID = 3
        const val TAG_BLUETOOTH = 4
        const val TAG_HOST_IP = 6
        const val TAG_PORT = 7

        const val MODEL_ID_SIZE = 3
        const val DEVICE_ID_SIZE = 32
        const val BLUETOOTH_SIZE = 6
        const val HOST_IP_LENGTH = 14

        private val MAGIC = "hwnearby".toByteArray(Charsets.US_ASCII)

        /** 总长之后的 9 个字节，实测为固定值，含义未知。 */
        private val FIXED_HEADER = byteArrayOf(0x01, 0x00, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x01)

        /** 各 TLV 应有的长度；carInfo 不允许出现别的 tag。 */
        private val FIELD_SIZES = mapOf(
            TAG_MODEL_ID to MODEL_ID_SIZE,
            TAG_SUB_MODEL_ID to 1,
            TAG_DEVICE_ID to DEVICE_ID_SIZE,
            TAG_BLUETOOTH to BLUETOOTH_SIZE,
            TAG_HOST_IP to HOST_IP_LENGTH,
            TAG_PORT to 4,
        )

        fun create(identity: NearbyIdentity, hostIp: String, port: Int): CarInfo =
            CarInfo(identity.modelId, identity.subModelId, identity.deviceId, identity.bluetoothAddress, hostIp, port)

        /** 解析 carInfo（自测和诊断用）。布局与上面不符时抛 [MalformedMessageException]。 */
        fun parse(data: ByteArray): CarInfo {
            if (data.size != LENGTH) throw MalformedMessageException("carInfo 应为 $LENGTH 字节，实际 ${data.size}")
            val r = ByteReader(data)
            if (!r.bytes(MAGIC.size).contentEquals(MAGIC)) throw MalformedMessageException("carInfo 魔数不对")
            val total = r.u16Le()
            if (total != LENGTH) throw MalformedMessageException("carInfo 总长字段为 $total，应为 $LENGTH")
            if (!r.bytes(FIXED_HEADER.size).contentEquals(FIXED_HEADER)) {
                throw MalformedMessageException("carInfo 固定头与已知布局不符")
            }
            val fields = HashMap<Int, ByteArray>()
            while (r.remaining > 0) {
                val tag = r.u8()
                val len = r.u8()
                val expected = FIELD_SIZES[tag] ?: throw MalformedMessageException("carInfo 出现未知 tag $tag")
                if (len != expected) throw MalformedMessageException("carInfo tag $tag 长度应为 $expected，实际 $len")
                if (fields.put(tag, r.bytes(len)) != null) throw MalformedMessageException("carInfo tag $tag 重复")
            }
            for (tag in FIELD_SIZES.keys) {
                if (tag !in fields) throw MalformedMessageException("carInfo 缺少 tag $tag")
            }
            val ipBytes = fields.getValue(TAG_HOST_IP)
            if (ipBytes.any { it < 0x20 || it > 0x7E }) throw MalformedMessageException("carInfo 的 IP 文本不是 ASCII")
            val ip = String(ipBytes, Charsets.US_ASCII)
            val port = ByteReader(fields.getValue(TAG_PORT)).u32Le()
            if (!Ipv4Text.isCanonical(ip)) throw MalformedMessageException("carInfo 的 IP 文本不是 IPv4 点分十进制")
            if (port !in 1L..65535L) throw MalformedMessageException("carInfo 的端口越界: $port")
            return CarInfo(
                modelId = fields.getValue(TAG_MODEL_ID),
                subModelId = fields.getValue(TAG_SUB_MODEL_ID)[0].toInt() and 0xFF,
                deviceId = fields.getValue(TAG_DEVICE_ID),
                bluetoothAddress = fields.getValue(TAG_BLUETOOTH),
                hostIp = ip,
                port = port.toInt(),
            )
        }

        /** 车机 IP 必须是规范的 IPv4 点分十进制（不带前导 0），而且正好 14 个字符。 */
        fun requireValidHostIp(ip: String) {
            require(ip.length == HOST_IP_LENGTH) {
                "车机 IP 文本必须正好 $HOST_IP_LENGTH 个字符（carInfo 里这个字段定长、不补齐，" +
                    "例如 192.168.66.100～192.168.66.253），实际 ${ip.length} 个字符: \"$ip\""
            }
            require(Ipv4Text.isCanonical(ip)) { "车机 IP 不是规范的 IPv4 点分十进制（每段 0～255、不带前导 0）: \"$ip\"" }
        }

        fun requireValidPort(port: Int) {
            require(port in 1..65535) { "端口必须在 1～65535 之间，实际 $port" }
        }

        private fun field(w: ByteWriter, tag: Int, value: ByteArray) {
            w.u8(tag).u8(value.size).bytes(value)
        }
    }
}
