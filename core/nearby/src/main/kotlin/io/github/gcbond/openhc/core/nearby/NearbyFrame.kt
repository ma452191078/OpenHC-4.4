package io.github.gcbond.openhc.core.nearby

import io.github.gcbond.openhc.core.common.ByteWriter
import io.github.gcbond.openhc.core.common.MalformedMessageException

/**
 * 控制通道上的一帧（有线时是第一条 TCP，蓝牙时是 RFCOMM，两者格式相同）：
 *
 * ```
 * 7F | EA(外层类型) | 标志段 | EA(载荷长度) | 载荷 | CRC16 低字节 | CRC16 高字节
 * 标志段 = EA(标志) EA(值长度) 值
 *   明文：标志 0、没有值              → 80 80
 *   加密：标志为奇数（发送时用 1），值 = TLV(1, 8 字节种子) → 81 8A 81 88 <种子>
 * ```
 *
 * 每条消息单独成帧。加密只作用于载荷，CRC-16/ARC 覆盖从 7F 到载荷末尾（加密帧算在密文上）。
 * [payload] 是线上的原样字节：加密帧里就是密文。
 */
internal class NearbyFrame(
    val outerType: Int,
    val flag: Int,
    flagValue: ByteArray,
    payload: ByteArray,
) {
    private val flagBytes = flagValue.copyOf()
    private val payloadBytes = payload.copyOf()

    init {
        require(outerType in 0..Ea.MAX_VALUE) { "外层类型越界: $outerType" }
        require(flag in 0..Ea.MAX_VALUE) { "标志越界: $flag" }
        require(payloadBytes.size <= Ea.MAX_VALUE) { "载荷过长: ${payloadBytes.size}" }
    }

    val isEncrypted: Boolean get() = flag and 1 == 1
    val flagValue: ByteArray get() = flagBytes.copyOf()
    val payload: ByteArray get() = payloadBytes.copyOf()

    /**
     * 加密帧的 8 字节种子（明文放在标志段的 TLV 1 里）；明文帧返回 null。
     * 标志段里找不到合格的种子时抛 [MalformedMessageException]。
     */
    val seed: ByteArray?
        get() {
            if (!isEncrypted) return null
            val item = Tlv.parseAll(flagBytes).firstOrNull { it.tag == SEED_TAG }
                ?: throw MalformedMessageException("加密帧的标志段里没有种子")
            if (item.length != SEED_LENGTH) {
                throw MalformedMessageException("种子应为 $SEED_LENGTH 字节，实际 ${item.length} 字节")
            }
            return item.value
        }

    internal fun payloadView(): ByteArray = payloadBytes

    fun encode(): ByteArray {
        val w = ByteWriter(payloadBytes.size + flagBytes.size + 16)
            .u8(HEAD)
            .ea(outerType)
            .tlv(flag, flagBytes)
            .ea(payloadBytes.size)
            .bytes(payloadBytes)
        val body = w.toByteArray()
        val crc = Crc16Arc.compute(body)
        return w.u16Le(crc).toByteArray()
    }

    override fun toString(): String =
        "NearbyFrame(外层类型=$outerType, 标志=$flag, 载荷 ${payloadBytes.size} 字节)"

    companion object {
        const val HEAD = 0x7F
        const val SEED_TAG = 1
        const val SEED_LENGTH = 8

        /** 发送加密帧时用的标志。 */
        const val FLAG_ENCRYPTED = 1

        fun plain(outerType: Int, payload: ByteArray): NearbyFrame =
            NearbyFrame(outerType, 0, EMPTY_BYTES, payload)

        /** [ciphertext] 必须是已经用 [seed] 加密过的载荷。 */
        fun encrypted(outerType: Int, ciphertext: ByteArray, seed: ByteArray): NearbyFrame {
            require(seed.size == SEED_LENGTH) { "种子应为 $SEED_LENGTH 字节" }
            return NearbyFrame(outerType, FLAG_ENCRYPTED, encodeTlv(SEED_TAG, seed), ciphertext)
        }
    }
}
