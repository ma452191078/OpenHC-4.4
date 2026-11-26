package io.github.gcbond.openhc.core.common.crypto

import io.github.gcbond.openhc.core.common.MalformedMessageException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * AES-128-CTR（NIST SP 800-38A 第 6.5 节）。
 *
 * - **计数器**：初始计数器块就是 16 字节 IV；每处理一块（16 字节），计数器按 128 位**大端**无符号整数
 *   整体加一，2^128 − 1 之后回绕到 0。这与 JCE 的 `AES/CTR/NoPadding`、OpenSSL 的 CTR 模式相同。
 *   计数器在 AES/ECB 上自己实现，结果不依赖 JCE 提供者对进位的处理。
 * - 加密和解密是同一个运算（输入异或密钥流），没有填充，也没有认证标签，输出与输入等长。
 * - [seal] / [open] 处理自带 IV 的加密单元：`IV(16) ‖ 密文`。
 *
 * 按 4 KiB 一段生成密钥流，复用实例里的缓冲区，不随数据长度分配内存。所以**不是线程安全的**：
 * 同一实例同一时间只能在一个线程里用。
 *
 * @param key 16 字节 AES-128 密钥，长度不对抛 [IllegalArgumentException]。
 */
class AesCtr(key: ByteArray) {
    private val ecb: Cipher
    private val counter = ByteArray(BLOCK_BYTES)
    private val counterBlocks = ByteArray(CHUNK_BYTES)
    private val keystream = ByteArray(CHUNK_BYTES)

    init {
        require(key.size == KEY_BYTES) { "AES-128 密钥必须是 $KEY_BYTES 字节" }
        ecb = Cipher.getInstance("AES/ECB/NoPadding")
        ecb.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
    }

    /**
     * CTR 变换（加解密相同）：`output[outOffset + i] = input[inOffset + i] XOR 密钥流[i]`，
     * 密钥流从 `iv[ivOffset, ivOffset + 16)` 这个计数器开始。
     *
     * 输出区可以与输入区完全重合（原地变换），但不能错位重叠。IV 在变换开始前就复制走，可以位于输入或输出数组里。
     *
     * @throws IllegalArgumentException IV、输入或输出的范围越界。
     */
    fun transform(
        iv: ByteArray, ivOffset: Int,
        input: ByteArray, inOffset: Int, length: Int,
        output: ByteArray, outOffset: Int,
    ) {
        require(ivOffset >= 0 && ivOffset <= iv.size - IV_BYTES) { "IV 范围非法: ivOffset=$ivOffset size=${iv.size}" }
        requireRange(input, inOffset, length, "输入")
        requireRange(output, outOffset, length, "输出")
        if (length == 0) return
        System.arraycopy(iv, ivOffset, counter, 0, IV_BYTES)
        var done = 0
        while (done < length) {
            val n = minOf(CHUNK_BYTES, length - done)
            val blocks = (n + BLOCK_BYTES - 1) / BLOCK_BYTES
            for (b in 0 until blocks) {
                System.arraycopy(counter, 0, counterBlocks, b * BLOCK_BYTES, BLOCK_BYTES)
                increment(counter)
            }
            ecb.doFinal(counterBlocks, 0, blocks * BLOCK_BYTES, keystream, 0)
            val inBase = inOffset + done
            val outBase = outOffset + done
            for (i in 0 until n) {
                output[outBase + i] = (input[inBase + i].toInt() xor keystream[i].toInt()).toByte()
            }
            done += n
        }
    }

    /**
     * 加密 `plain[offset, offset + length)`，返回 `IV(16) ‖ 密文`。IV 由 [random] 生成：
     * 每次调用正好取一次 16 字节（明文为空时也取）。
     *
     * @throws IllegalArgumentException 范围越界。
     */
    fun seal(random: SecureRandom, plain: ByteArray, offset: Int = 0, length: Int = plain.size - offset): ByteArray {
        requireRange(plain, offset, length, "明文")
        val iv = ByteArray(IV_BYTES)
        random.nextBytes(iv)
        val out = ByteArray(IV_BYTES + length)
        System.arraycopy(iv, 0, out, 0, IV_BYTES)
        transform(iv, 0, plain, offset, length, out, IV_BYTES)
        return out
    }

    /**
     * 解开 `sealed[offset, offset + length)` 这个 `IV(16) ‖ 密文` 单元，返回明文。
     *
     * @throws MalformedMessageException 不足 16 字节（缺完整 IV）。
     * @throws IllegalArgumentException 范围越界。
     */
    fun open(sealed: ByteArray, offset: Int = 0, length: Int = sealed.size - offset): ByteArray {
        requireRange(sealed, offset, length, "加密单元")
        if (length < IV_BYTES) throw MalformedMessageException("加密单元只有 $length 字节，不够 $IV_BYTES 字节的 IV")
        val out = ByteArray(length - IV_BYTES)
        transform(sealed, offset, sealed, offset + IV_BYTES, out.size, out, 0)
        return out
    }

    override fun toString(): String = "AesCtr(密钥不显示)"

    companion object {
        /** 密钥长度（字节）。 */
        const val KEY_BYTES = 16

        /** IV（初始计数器块）长度（字节）。 */
        const val IV_BYTES = 16

        private const val BLOCK_BYTES = 16

        /** 一次生成的密钥流长度，必须是块长的整数倍。 */
        private const val CHUNK_BYTES = 4096

        /** 计数器按 128 位大端整数加一，溢出回绕。 */
        private fun increment(counter: ByteArray) {
            for (i in BLOCK_BYTES - 1 downTo 0) {
                val v = (counter[i].toInt() and 0xFF) + 1
                counter[i] = v.toByte()
                if (v <= 0xFF) return
            }
        }

        private fun requireRange(data: ByteArray, offset: Int, length: Int, what: String) {
            require(offset >= 0 && length >= 0 && offset <= data.size - length) {
                "${what}范围非法: offset=$offset length=$length size=${data.size}"
            }
        }
    }
}
