package io.github.gcbond.openhc.core.auth

import io.github.gcbond.openhc.core.auth.crypto.AesGcm
import io.github.gcbond.openhc.core.auth.crypto.Ed25519Keys
import io.github.gcbond.openhc.core.auth.crypto.Hkdf
import io.github.gcbond.openhc.core.auth.crypto.HmacSha256
import io.github.gcbond.openhc.core.auth.crypto.Modp
import io.github.gcbond.openhc.core.auth.crypto.Sha256
import io.github.gcbond.openhc.core.auth.crypto.X25519Keys
import io.github.gcbond.openhc.core.common.hexToBytes
import io.github.gcbond.openhc.core.common.toHex
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 密码原语用公开标准的测试向量核对。 */
class CryptoTest {

    @Test
    fun sha256Fips180Vector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.digest("abc".toByteArray()).toHex())
        assertEquals(Sha256.digest("abc".toByteArray()).toHex(), Sha256.digest("a".toByteArray(), "bc".toByteArray()).toHex())
    }

    /** RFC 4231 测试用例 1、2。 */
    @Test
    fun hmacSha256Rfc4231() {
        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            HmacSha256.mac(ByteArray(20) { 0x0b }, "Hi There".toByteArray()).toHex(),
        )
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            HmacSha256.mac("Jefe".toByteArray(), "what do ya want for nothing?".toByteArray()).toHex(),
        )
        // 空密钥与补零后的密钥等价
        assertContentEquals(HmacSha256.mac(ByteArray(32), "x".toByteArray()), HmacSha256.mac(ByteArray(0), "x".toByteArray()))
    }

    /** RFC 5869 测试用例 1、3。 */
    @Test
    fun hkdfRfc5869() {
        val ikm = ByteArray(22) { 0x0b }
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            Hkdf.sha256(ikm, "000102030405060708090a0b0c".hexToBytes(), "f0f1f2f3f4f5f6f7f8f9".hexToBytes(), 42).toHex(),
        )
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            Hkdf.sha256(ikm, ByteArray(0), ByteArray(0), 42).toHex(),
        )
    }

    /** GCM 规范（McGrew & Viega）测试用例 4：AES-128，带附加数据。 */
    @Test
    fun aesGcmStandardVector() {
        val key = "feffe9928665731c6d6a8f9467308308".hexToBytes()
        val iv = "cafebabefacedbaddecaf888".hexToBytes()
        val aad = "feedfacedeadbeeffeedfacedeadbeefabaddad2".hexToBytes()
        val plain = ("d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a72" +
            "1c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39").hexToBytes()
        val expected = "42831ec2217774244b7221b784d0d49ce3aa212f2c02a4e035c17e2329aca12e" +
            "21d514b25466931c7d8f6a5aac84aa051ba30b396a0aac973d58e091" + "5bc94fbc3221a5db94fae95ae7121a47"
        val sealed = AesGcm.encrypt(key, iv, aad, plain)
        assertEquals(expected, sealed.toHex())
        assertContentEquals(plain, AesGcm.decrypt(key, iv, aad, sealed))
        val tampered = sealed.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertNull(AesGcm.decrypt(key, iv, aad, tampered))
        assertNull(AesGcm.decrypt(key, iv, "00".hexToBytes(), sealed))
        assertNull(AesGcm.decrypt(ByteArray(0), iv, aad, sealed))
        assertNull(AesGcm.decrypt(key, iv, aad, ByteArray(15)))
    }

    /** RFC 8032 第 7.1 节测试 1、2。 */
    @Test
    fun ed25519Rfc8032() {
        val sk1 = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60".hexToBytes()
        val pk1 = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
        val sig1 = "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
        assertEquals(pk1, Ed25519Keys.publicKey(sk1).toHex())
        assertEquals(sig1, Ed25519Keys.sign(sk1, ByteArray(0)).toHex())
        assertTrue(Ed25519Keys.verify(pk1.hexToBytes(), ByteArray(0), sig1.hexToBytes()))

        val sk2 = "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb".hexToBytes()
        val pk2 = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"
        val sig2 = "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"
        assertEquals(pk2, Ed25519Keys.publicKey(sk2).toHex())
        assertEquals(sig2, Ed25519Keys.sign(sk2, "72".hexToBytes()).toHex())
        assertTrue(Ed25519Keys.verify(pk2.hexToBytes(), "72".hexToBytes(), sig2.hexToBytes()))
        assertFalse(Ed25519Keys.verify(pk2.hexToBytes(), "73".hexToBytes(), sig2.hexToBytes()))
        assertFalse(Ed25519Keys.verify(pk1.hexToBytes(), "72".hexToBytes(), sig2.hexToBytes()))
        assertFalse(Ed25519Keys.verify(pk2.hexToBytes(), "72".hexToBytes(), sig2.hexToBytes().copyOf(63)))
        assertFalse(Ed25519Keys.verify(ByteArray(31), "72".hexToBytes(), sig2.hexToBytes()))
    }

    /** RFC 7748 第 6.1 节。 */
    @Test
    fun x25519Rfc7748() {
        val alice = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a".hexToBytes()
        val bob = "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb".hexToBytes()
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", X25519Keys.publicKey(alice).toHex())
        assertEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", X25519Keys.publicKey(bob).toHex())
        val shared = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
        assertEquals(shared, X25519Keys.agree(alice, X25519Keys.publicKey(bob))!!.toHex())
        assertEquals(shared, X25519Keys.agree(bob, X25519Keys.publicKey(alice))!!.toHex())
        // 小阶点：结果全零，拒绝
        assertNull(X25519Keys.agree(alice, ByteArray(32)))
        assertNull(X25519Keys.agree(alice, ByteArray(32).also { it[0] = 1 }))
        assertNull(X25519Keys.agree(alice, ByteArray(31)))
    }

    /** RFC 3526 的 MODP 素数与定长模幂。 */
    @Test
    fun modpPrimesAndFixedLengthOutput() {
        assertEquals(3072, Modp.P3072.bitLength())
        assertEquals(2048, Modp.P2048.bitLength())
        assertTrue(Modp.P3072.isProbablePrime(32))
        assertTrue(Modp.P2048.isProbablePrime(32))
        val ones = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
        assertEquals(ones, Modp.P3072.and(ones))
        assertEquals(ones, Modp.P3072.shiftRight(3072 - 64))

        val eight = Modp.modPow(byteArrayOf(2), byteArrayOf(3), 384)
        assertEquals(384, eight.size)
        assertEquals(8, eight[383].toInt())
        assertTrue(eight.copyOf(383).all { it.toInt() == 0 })
        // 结果最高字节为 0x00 以外时也是定长（不带 BigInteger 的符号字节）
        val big = Modp.modPow(Modp.P3072.subtract(BigInteger.ONE).toByteArray(), byteArrayOf(1), 384)
        assertEquals(384, big.size)
        assertEquals(0xFF, big[0].toInt() and 0xFF)

        val p = Modp.P3072
        fun fixed(v: BigInteger) = v.toByteArray().let { if (it.size > 384) it.copyOfRange(it.size - 384, it.size) else it }
        assertFalse(Modp.isValidPublicValue(ByteArray(0), 384))
        assertFalse(Modp.isValidPublicValue(byteArrayOf(1), 384))
        assertFalse(Modp.isValidPublicValue(ByteArray(384).also { it[383] = 1 }, 384))
        assertTrue(Modp.isValidPublicValue(byteArrayOf(2), 384))
        assertTrue(Modp.isValidPublicValue(fixed(p.subtract(BigInteger.valueOf(2))), 384))
        assertFalse(Modp.isValidPublicValue(fixed(p.subtract(BigInteger.ONE)), 384))
        assertFalse(Modp.isValidPublicValue(fixed(p), 384))
        assertFalse(Modp.isValidPublicValue(ByteArray(385).also { it[384] = 2 }, 384))
    }
}
