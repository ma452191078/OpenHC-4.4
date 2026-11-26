package io.github.gcbond.openhc.core.common.crypto

import io.github.gcbond.openhc.core.common.hexToBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AesCfbTest {

    // NIST SP 800-38A F.3.13 / F.3.14（CFB128-AES128）。
    private val key = "2b7e151628aed2a6abf7158809cf4f3c".hexToBytes()
    private val iv = "000102030405060708090a0b0c0d0e0f".hexToBytes()
    private val plain = (
        "6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e51" +
            "30c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710"
        ).hexToBytes()
    private val cipher = (
        "3b3fd92eb72dad20333449f8e83cfb4ac8a64537a0b3a93fcde3cdad9f1ce58b" +
            "26751f67a3cbb140b1808cf187a4f4dfc04b05357c5d1c0eeac4c66f9ff7f2e6"
        ).hexToBytes()

    /** F.3.13 加密。 */
    @Test
    fun nistEncrypt() {
        assertContentEquals(cipher, AesCfb.encrypt(key, iv, plain))
    }

    /** F.3.14 解密。 */
    @Test
    fun nistDecrypt() {
        assertContentEquals(plain, AesCfb.decrypt(key, iv, cipher))
    }

    /** 按流处理：截掉一部分，结果就是完整结果的前缀，不补齐。 */
    @Test
    fun partialLengthsArePrefixes() {
        for (n in intArrayOf(1, 15, 16, 17, 20, 63)) {
            assertContentEquals(cipher.copyOf(n), AesCfb.encrypt(key, iv, plain.copyOf(n)), "加密 $n 字节")
            assertContentEquals(plain.copyOf(n), AesCfb.decrypt(key, iv, cipher.copyOf(n)), "解密 $n 字节")
        }
        assertEquals(0, AesCfb.encrypt(key, iv, ByteArray(0)).size)
        assertEquals(0, AesCfb.decrypt(key, iv, ByteArray(0)).size)
    }

    @Test
    fun inputIsNotModified() {
        val input = plain.copyOf()
        AesCfb.encrypt(key, iv, input)
        assertContentEquals(plain, input)
        assertFalse(cipher.contentEquals(plain))
    }

    @Test
    fun rejectsBadKeyOrIv() {
        assertFailsWith<IllegalArgumentException> { AesCfb.encrypt(ByteArray(15), iv, plain) }
        assertFailsWith<IllegalArgumentException> { AesCfb.encrypt(ByteArray(32), iv, plain) }
        assertFailsWith<IllegalArgumentException> { AesCfb.decrypt(key, ByteArray(8), plain) }
        assertFailsWith<IllegalArgumentException> { AesCfb.decrypt(key, ByteArray(17), ByteArray(0)) }
    }
}
