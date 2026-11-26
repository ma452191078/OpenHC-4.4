package io.github.gcbond.openhc.core.auth.crypto

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.modes.GCMModeCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519
import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.math.BigInteger
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/*
 * 认证用到的密码原语。散列、HMAC、AES-GCM 和模幂走 JDK（安卓 6.0 都有）；
 * X25519、Ed25519 走 BouncyCastle 的轻量级 API（老安卓的 JDK 没有这两种曲线，也不注册 JCA Provider）。
 */

internal object Sha256 {
    fun digest(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (part in parts) md.update(part)
        return md.digest()
    }
}

internal object HmacSha256 {
    const val LENGTH = 32

    fun mac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // HMAC 会把短于分组长度的密钥补零，空密钥与一个零字节等价；SecretKeySpec 不接受空数组。
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(1) else key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}

/** RFC 5869 的 HKDF-SHA256（extract + expand）。 */
internal object Hkdf {
    fun sha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * HmacSha256.LENGTH) { "HKDF 输出长度非法: $length" }
        // salt 为空时按 RFC 取 HashLen 个零字节，与上面空密钥的处理等价。
        val prk = HmacSha256.mac(salt, ikm)
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            previous = HmacSha256.mac(prk, previous + info + byteArrayOf(counter.toByte()))
            val n = minOf(previous.size, length - offset)
            System.arraycopy(previous, 0, out, offset, n)
            offset += n
            counter++
        }
        return out
    }
}

/**
 * AES-GCM，标签 16 字节，输出 = 密文 || 标签。
 *
 * 不走 JCA 的 Cipher：安卓 4.4（API 19）自带 provider 的 GCM 用不了——GCMParameterSpec 不被识别
 * （init 抛 InvalidAlgorithmParameterException），退到 IvParameterSpec 后 init 能过，但协议要用的
 * AAD（updateAAD）会抛 UnsupportedOperationException（2026-11-25 车机实测）。改用 BouncyCastle 的
 * 轻量级实现（纯 Java，不注册 provider），各版本行为一致，128 位标签与协议一致。
 */
internal object AesGcm {
    const val TAG_LENGTH = 16

    private fun cipher(forEncryption: Boolean, key: ByteArray, nonce: ByteArray, aad: ByteArray): GCMModeCipher =
        GCMBlockCipher.newInstance(AESEngine()).apply {
            init(forEncryption, AEADParameters(KeyParameter(key), TAG_LENGTH * 8, nonce, aad))
        }

    fun encrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        val cipher = cipher(true, key, nonce, aad)
        val out = ByteArray(cipher.getOutputSize(plain.size))
        val n = cipher.processBytes(plain, 0, plain.size, out, 0)
        return out.copyOf(n + cipher.doFinal(out, n))
    }

    /** 解密并校验标签；标签不对返回 null。 */
    fun decrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, cipherAndTag: ByteArray): ByteArray? {
        if (key.size != 16 && key.size != 24 && key.size != 32) return null
        if (nonce.isEmpty() || cipherAndTag.size < TAG_LENGTH) return null
        return try {
            val cipher = cipher(false, key, nonce, aad)
            val out = ByteArray(cipher.getOutputSize(cipherAndTag.size))
            val n = cipher.processBytes(cipherAndTag, 0, cipherAndTag.size, out, 0)
            out.copyOf(n + cipher.doFinal(out, n))
        } catch (e: InvalidCipherTextException) {
            null
        } catch (e: RuntimeException) {
            null
        }
    }
}

/**
 * 有限域模幂。素数取自 RFC 3526 的 MODP 组：3072 位（第 15 组）与 2048 位（第 14 组）。
 * 结果按大端写成与素数等长的定长字节串（左补零）。
 */
internal object Modp {
    const val LENGTH_3072 = 384
    const val LENGTH_2048 = 256

    val P3072: BigInteger = BigInteger(
        "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74" +
            "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437" +
            "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
            "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05" +
            "98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB" +
            "9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
            "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF695581718" +
            "3995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33" +
            "A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7" +
            "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864" +
            "D87602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E2" +
            "08E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF",
        16,
    )

    val P2048: BigInteger = BigInteger(
        "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74" +
            "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437" +
            "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
            "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05" +
            "98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB" +
            "9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
            "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF695581718" +
            "3995497CEA956AE515D2261898FA051015728E5A8AACAA68FFFFFFFFFFFFFFFF",
        16,
    )

    fun prime(length: Int): BigInteger = when (length) {
        LENGTH_3072 -> P3072
        LENGTH_2048 -> P2048
        else -> throw IllegalArgumentException("不支持的素数长度: $length")
    }

    /** base^exponent mod p（两者都按无符号大端解释），输出 [length] 字节。 */
    fun modPow(base: ByteArray, exponent: ByteArray, length: Int): ByteArray {
        val result = BigInteger(1, base).modPow(BigInteger(1, exponent), prime(length))
        return toFixedLength(result, length)
    }

    /** 1 < value < p - 1，按无符号大端解释。 */
    fun isValidPublicValue(value: ByteArray, length: Int): Boolean {
        if (value.size > length) return false
        val v = BigInteger(1, value)
        return v > BigInteger.ONE && v < prime(length).subtract(BigInteger.ONE)
    }

    private fun toFixedLength(value: BigInteger, length: Int): ByteArray {
        val raw = value.toByteArray()
        val start = if (raw.size > 1 && raw[0].toInt() == 0) 1 else 0
        val significant = raw.size - start
        check(significant <= length) { "模幂结果超出素数长度" }
        val out = ByteArray(length)
        System.arraycopy(raw, start, out, length - significant, significant)
        return out
    }
}

/** Ed25519（RFC 8032 纯签名）。私钥用 32 字节种子表示。 */
internal object Ed25519Keys {
    const val KEY_LENGTH = 32
    const val SIGNATURE_LENGTH = 64

    fun publicKey(seed: ByteArray): ByteArray {
        require(seed.size == KEY_LENGTH) { "Ed25519 种子必须是 32 字节" }
        val pk = ByteArray(KEY_LENGTH)
        Ed25519.generatePublicKey(seed, 0, pk, 0)
        return pk
    }

    fun sign(seed: ByteArray, message: ByteArray): ByteArray {
        require(seed.size == KEY_LENGTH) { "Ed25519 种子必须是 32 字节" }
        val signature = ByteArray(SIGNATURE_LENGTH)
        Ed25519.sign(seed, 0, message, 0, message.size, signature, 0)
        return signature
    }

    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != KEY_LENGTH || signature.size != SIGNATURE_LENGTH) return false
        return try {
            Ed25519.verify(signature, 0, publicKey, 0, message, 0, message.size)
        } catch (e: RuntimeException) {
            false
        }
    }
}

/** X25519（RFC 7748）。私钥是 32 字节原始值，钳位在标量乘法里做。 */
internal object X25519Keys {
    const val KEY_LENGTH = 32

    fun publicKey(privateKey: ByteArray): ByteArray {
        require(privateKey.size == KEY_LENGTH) { "X25519 私钥必须是 32 字节" }
        val pk = ByteArray(KEY_LENGTH)
        X25519.generatePublicKey(privateKey, 0, pk, 0)
        return pk
    }

    /** 共享秘密；对端公钥长度不对或结果全零（小阶点）时返回 null。 */
    fun agree(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray? {
        if (privateKey.size != KEY_LENGTH || peerPublicKey.size != KEY_LENGTH) return null
        val out = ByteArray(KEY_LENGTH)
        return if (X25519.calculateAgreement(privateKey, 0, peerPublicKey, 0, out, 0)) out else null
    }
}
