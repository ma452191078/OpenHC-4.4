package io.github.gcbond.openhc.core.nearby

import io.github.gcbond.openhc.core.common.identity.NearbyKeyMaterial
import io.github.gcbond.openhc.core.common.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** AES-128-CFB 与帧载荷加密（密钥推导）。 */
class CipherTest {

    /** NIST SP 800-38A F.3.13（CFB128-AES128.Encrypt）的公开测试向量。 */
    @Test
    fun aesCfbNistVector() {
        val key = hex("2b7e151628aed2a6abf7158809cf4f3c")
        val iv = hex("000102030405060708090a0b0c0d0e0f")
        val plain = hex(
            "6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e51" +
                "30c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710",
        )
        val cipher = hex(
            "3b3fd92eb72dad20333449f8e83cfb4ac8a64537a0b3a93fcde3cdad9f1ce58b" +
                "26751f67a3cbb140b1808cf187a4f4dfc04b05357c5d1c0eeac4c66f9ff7f2e6",
        )
        assertContentEquals(cipher, AesCfb.encrypt(key, iv, plain))
        assertContentEquals(plain, AesCfb.decrypt(key, iv, cipher))
        // CFB 按流处理：截掉一部分，结果就是完整密文的前缀。
        for (n in intArrayOf(1, 15, 17, 20, 63)) {
            assertContentEquals(cipher.copyOf(n), AesCfb.encrypt(key, iv, plain.copyOf(n)), "长度 $n")
            assertContentEquals(plain.copyOf(n), AesCfb.decrypt(key, iv, cipher.copyOf(n)), "长度 $n")
        }
        assertEquals(0, AesCfb.encrypt(key, iv, ByteArray(0)).size)
    }

    @Test
    fun aesCfbRejectsBadKeyOrIv() {
        assertFailsWith<IllegalArgumentException> { AesCfb.encrypt(ByteArray(15), ByteArray(16), ByteArray(1)) }
        assertFailsWith<IllegalArgumentException> { AesCfb.encrypt(ByteArray(16), ByteArray(8), ByteArray(1)) }
    }

    @Test
    fun derivedRootMatchesReference() {
        assertEquals(Vectors.DERIVED_ROOT_16, NearbyFrameCipher(Vectors.KEYS_16).derivedRootForTest().toHex())
        assertEquals(Vectors.DERIVED_ROOT_24, NearbyFrameCipher(Vectors.KEYS_24).derivedRootForTest().toHex())
    }

    @Test
    fun payloadEncryptionMatchesReference16() {
        checkCfbVectors(NearbyFrameCipher(Vectors.KEYS_16), Vectors.CFB_16, Vectors.CFB_16_SEED_A)
    }

    @Test
    fun payloadEncryptionMatchesReference24() {
        checkCfbVectors(NearbyFrameCipher(Vectors.KEYS_24), Vectors.CFB_24, Vectors.CFB_24_SEED_A)
    }

    private fun checkCfbVectors(cipher: NearbyFrameCipher, expected: List<String>, expectedSeedA: String) {
        assertEquals(Vectors.CFB_LENGTHS.size, expected.size)
        for ((i, n) in Vectors.CFB_LENGTHS.withIndex()) {
            val plain = pattern(n)
            assertEquals(expected[i], cipher.encrypt(plain, Vectors.SEED_0).toHex(), "长度 $n")
            assertContentEquals(plain, cipher.decrypt(hex(expected[i]), Vectors.SEED_0), "长度 $n")
        }
        assertEquals(expectedSeedA, cipher.encrypt(pattern(40, 13, 0x55), Vectors.SEED_A).toHex())
    }

    @Test
    fun sealedFrameMatchesReference() {
        val cipher = NearbyFrameCipher(Vectors.KEYS_24)
        val frame = cipher.seal(0, encodeTlv(7, Vectors.BT_1.toByteArray()), Vectors.SEED_B)
        assertEquals(Vectors.ENC_FRAME_24, frame.encode().toHex())
        assertContentEquals(encodeTlv(7, Vectors.BT_1.toByteArray()), cipher.open(frame))
    }

    @Test
    fun openReturnsPlainPayloadAsIs() {
        val cipher = NearbyFrameCipher(Vectors.KEYS_16)
        val frame = NearbyFrame.plain(4, byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), cipher.open(frame))
    }

    @Test
    fun differentSeedsGiveDifferentCiphertext() {
        val cipher = NearbyFrameCipher(Vectors.KEYS_16)
        val plain = pattern(32)
        assertFalse(cipher.encrypt(plain, Vectors.SEED_0).contentEquals(cipher.encrypt(plain, Vectors.SEED_A)))
    }

    @Test
    fun rejectsShortKeyMaterialAndBadSeed() {
        // 移位按字节做，少于 5 字节时没有定义：身份资产层构造时就拒绝。
        assertFailsWith<IllegalArgumentException> { NearbyKeyMaterial(ByteArray(4), ByteArray(4), ByteArray(4)) }
        val five = NearbyKeyMaterial(incrementing(0, 5), incrementing(5, 5), incrementing(10, 5))
        NearbyFrameCipher(five)
        val cipher = NearbyFrameCipher(Vectors.KEYS_16)
        assertFailsWith<IllegalArgumentException> { cipher.encrypt(ByteArray(1), ByteArray(7)) }
        assertFailsWith<IllegalArgumentException> { cipher.decrypt(ByteArray(1), ByteArray(9)) }
    }

    @Test
    fun cipherDoesNotPrintKeys() {
        val text = NearbyFrameCipher(Vectors.KEYS_16).toString()
        assertFalse(text.contains(Vectors.DERIVED_ROOT_16.take(8)), text)
    }
}
