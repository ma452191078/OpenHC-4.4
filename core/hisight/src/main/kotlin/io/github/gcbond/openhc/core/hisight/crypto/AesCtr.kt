package io.github.gcbond.openhc.core.hisight.crypto

import io.github.gcbond.openhc.core.common.MalformedMessageException
import java.security.SecureRandom
import io.github.gcbond.openhc.core.common.crypto.AesCtr as AesCtrCipher

/**
 * HiSight 三条连接共用的 AES-128-CTR：密钥就是认证得到的 16 字节会话密钥，不再派生；
 * 每个加密单元自带 16 字节 IV，线上格式为 `IV16 || 密文`，计数器为大端、按 128 位整体加一、每单元从 IV 重新开始，
 * 没有填充和认证标签。
 *
 * 算法在 core:common 的 [AesCtrCipher]；这里只保留本层的接口和报错说法。
 * 实例复用内部缓冲区，不是线程安全的：控制、视频、回控各用一个实例。输入输出不要错位重叠。
 */
internal class AesCtr(sessionKey: ByteArray) {
    private val cipher: AesCtrCipher

    init {
        require(sessionKey.size == KEY_BYTES) { "会话密钥必须是 16 字节" }
        cipher = AesCtrCipher(sessionKey)
    }

    /** 加密一个单元，返回 `IV16 || 密文`。IV 每次从 [random] 取。 */
    fun seal(plain: ByteArray, random: SecureRandom): ByteArray = cipher.seal(random, plain)

    /** 解密 `IV16 || 密文`；不足 16 字节说明缺 IV，按对端数据非法处理。 */
    fun open(unit: ByteArray, offset: Int = 0, length: Int = unit.size - offset): ByteArray {
        if (length < IV_BYTES) throw MalformedMessageException("加密单元不足 16 字节，缺少 IV")
        return cipher.open(unit, offset, length)
    }

    /** 用 `iv[ivOffset, +16)` 把 `input[inOffset, +length)` 解密到 `output[outOffset..]`。 */
    fun decryptInto(
        iv: ByteArray, ivOffset: Int,
        input: ByteArray, inOffset: Int, length: Int,
        output: ByteArray, outOffset: Int,
    ) = cipher.transform(iv, ivOffset, input, inOffset, length, output, outOffset)

    companion object {
        const val KEY_BYTES = AesCtrCipher.KEY_BYTES
        const val IV_BYTES = AesCtrCipher.IV_BYTES
    }
}
