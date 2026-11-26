package io.github.gcbond.openhc.core.common.crypto

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-128-CFB，反馈 128 位（NIST SP 800-38A 第 6.3 节的 CFB128，即 JCE 的 `AES/CFB/NoPadding`），
 * 按流处理，任意长度都不补齐，输出与输入等长。安卓 6.0 自带这个算法名。
 *
 * 每次调用新建一个 [Cipher]，用一次 doFinal 处理全部数据：部分提供者的 update 会把不满一块的尾巴
 * 攒到 doFinal 才输出。不共享状态，线程安全。
 */
object AesCfb {
    private const val TRANSFORMATION = "AES/CFB/NoPadding"

    /** 密钥长度（字节）。 */
    const val KEY_BYTES = 16

    /** IV 长度（字节）。 */
    const val IV_BYTES = 16

    /** 加密。[key]、[iv] 长度不对抛 [IllegalArgumentException]；[data] 为空时返回空数组。 */
    fun encrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray = run(Cipher.ENCRYPT_MODE, key, iv, data)

    /** 解密。[key]、[iv] 长度不对抛 [IllegalArgumentException]；[data] 为空时返回空数组。 */
    fun decrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray = run(Cipher.DECRYPT_MODE, key, iv, data)

    private fun run(mode: Int, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) { "AES-128 密钥应为 $KEY_BYTES 字节" }
        require(iv.size == IV_BYTES) { "IV 应为 $IV_BYTES 字节" }
        // 空输入时有的提供者会返回 null，直接返回空数组。
        if (data.isEmpty()) return ByteArray(0)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val out = cipher.doFinal(data)
        check(out.size == data.size) { "CFB 输出长度应与输入相同" }
        return out
    }
}
