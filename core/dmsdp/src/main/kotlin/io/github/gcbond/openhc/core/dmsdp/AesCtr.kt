package io.github.gcbond.openhc.core.dmsdp

import io.github.gcbond.openhc.core.common.MalformedMessageException
import java.security.SecureRandom
import io.github.gcbond.openhc.core.common.crypto.AesCtr as AesCtrCipher

/**
 * DMSDP 控制连接与 UDP 保活用的 AES-128-CTR。
 *
 * - 密钥就是认证得到的 16 字节会话密钥，不再派生。
 * - 每帧独立生成 16 字节随机 IV，作为初始计数器；计数器按 128 位大端整体加一（溢出回绕）。
 * - 线上格式是 `IV(16) ‖ 密文`，密文与明文等长：无填充、无认证标签。
 *
 * 算法在 core:common 的 [AesCtrCipher]（在 ECB 上自己实现计数器，不依赖各家 JCE 对 CTR 进位的处理）；
 * 这里只保留本层的参数检查和报错说法。不是线程安全的。
 */
internal class AesCtr(sessionKey: ByteArray) {
    private val cipher: AesCtrCipher

    init {
        require(sessionKey.size == KEY_BYTES) { "sessionKey 必须是 16 字节" }
        cipher = AesCtrCipher(sessionKey)
    }

    /** 加密一帧：返回 `IV ‖ 密文`，IV 取自 [random]。 */
    fun seal(random: SecureRandom, plain: ByteArray, offset: Int = 0, length: Int = plain.size - offset): ByteArray =
        cipher.seal(random, plain, offset, length)

    /** 解密一帧 `IV ‖ 密文`；不足 16 字节（缺完整 IV）抛 [MalformedMessageException]。 */
    fun open(payload: ByteArray, offset: Int = 0, length: Int = payload.size - offset): ByteArray {
        if (length < IV_BYTES) throw MalformedMessageException("加密载荷只有 $length 字节，缺少完整 IV")
        return cipher.open(payload, offset, length)
    }

    /** CTR 变换（加解密相同）：`output = input XOR AES(counter_i)`。 */
    fun transform(iv: ByteArray, input: ByteArray, inOffset: Int, length: Int, output: ByteArray, outOffset: Int) {
        require(iv.size == IV_BYTES) { "IV 必须是 16 字节" }
        cipher.transform(iv, 0, input, inOffset, length, output, outOffset)
    }

    override fun toString(): String = "AesCtr(密钥不显示)"

    companion object {
        const val KEY_BYTES = AesCtrCipher.KEY_BYTES
        const val IV_BYTES = AesCtrCipher.IV_BYTES
    }
}
