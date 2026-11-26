package io.github.gcbond.openhc.core.nearby

import io.github.gcbond.openhc.core.common.identity.NearbyKeyMaterial
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 控制帧载荷的加解密。每帧独立：发送方生成 8 字节随机种子，明文放在帧的标志段里，载荷用
 * 由种子派生的密钥加密。
 *
 * ```
 * h1 = (constA 循环左移 3 字节) XOR constB
 * H  = (h1 循环右移 5 字节) XOR rootKey
 * E  = HMAC-SHA256(key = H, data = 种子)
 * AES-128-CFB：key = E[0, 16)，IV = E[16, 32)
 * ```
 *
 * 三段密钥材料来自身份资产，长度必须相同且不少于 5 字节（移位按字节做，少于 5 字节时没有定义）。
 */
internal class NearbyFrameCipher(keys: NearbyKeyMaterial) {
    private val derivedRoot: ByteArray

    init {
        val a = keys.constA
        val b = keys.constB
        val root = keys.rootKey
        val n = root.size
        require(n >= MIN_KEY_MATERIAL) { "nearby 密钥材料每段至少 $MIN_KEY_MATERIAL 字节，实际 $n 字节" }
        val h1 = ByteArray(n) { i -> (a[(i + 3) % n].toInt() xor b[i].toInt()).toByte() }
        derivedRoot = ByteArray(n) { i -> (h1[(i - 5 + n) % n].toInt() xor root[i].toInt()).toByte() }
        a.fill(0)
        b.fill(0)
        root.fill(0)
        h1.fill(0)
    }

    fun encrypt(plain: ByteArray, seed: ByteArray): ByteArray {
        val e = frameKey(seed)
        return try {
            AesCfb.encrypt(e.copyOfRange(0, 16), e.copyOfRange(16, 32), plain)
        } finally {
            e.fill(0)
        }
    }

    fun decrypt(ciphertext: ByteArray, seed: ByteArray): ByteArray {
        val e = frameKey(seed)
        return try {
            AesCfb.decrypt(e.copyOfRange(0, 16), e.copyOfRange(16, 32), ciphertext)
        } finally {
            e.fill(0)
        }
    }

    /** 用 [seed] 加密 [plain] 并组成加密帧。 */
    fun seal(outerType: Int, plain: ByteArray, seed: ByteArray): NearbyFrame =
        NearbyFrame.encrypted(outerType, encrypt(plain, seed), seed)

    /** 取出帧的明文载荷：明文帧原样返回，加密帧用帧里的种子解密。 */
    fun open(frame: NearbyFrame): ByteArray {
        val seed = frame.seed ?: return frame.payload
        return decrypt(frame.payloadView(), seed)
    }

    /** 只给测试核对推导结果用。 */
    internal fun derivedRootForTest(): ByteArray = derivedRoot.copyOf()

    private fun frameKey(seed: ByteArray): ByteArray {
        require(seed.size == NearbyFrame.SEED_LENGTH) { "种子应为 ${NearbyFrame.SEED_LENGTH} 字节" }
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(derivedRoot, HMAC))
        return mac.doFinal(seed)
    }

    override fun toString(): String = "NearbyFrameCipher(内容不显示)"

    companion object {
        const val MIN_KEY_MATERIAL = 5
        private const val HMAC = "HmacSHA256"
    }
}
