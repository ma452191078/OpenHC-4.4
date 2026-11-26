package io.github.gcbond.openhc.core.common.crypto

import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.common.hexToBytes
import io.github.gcbond.openhc.core.common.toHex
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AesCtrTest {

    // NIST SP 800-38A F.5.1 / F.5.2（CTR-AES128）。
    private val nistKey = "2b7e151628aed2a6abf7158809cf4f3c".hexToBytes()
    private val nistCounter = "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff".hexToBytes()
    private val nistPlain = (
        "6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e51" +
            "30c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710"
        ).hexToBytes()
    private val nistCipher = (
        "874d6191b620e3261bef6864990db6ce9806f66b7970fdff8617187bb9fffdff" +
            "5ae4df3edbd5d35e5b4f09020db03eab1e031dda2fbe03d1792170a0f3009cee"
        ).hexToBytes()

    private val key = ByteArray(16) { it.toByte() }

    /** NIST SP 800-38A F.5.1（加密）与 F.5.2（解密）；不满一块的长度是完整结果的前缀。 */
    @Test
    fun nistVectors() {
        val ctr = AesCtr(nistKey)
        for (n in intArrayOf(64, 63, 33, 17, 16, 15, 1, 0)) {
            val encrypted = ByteArray(n)
            ctr.transform(nistCounter, 0, nistPlain, 0, n, encrypted, 0)
            assertContentEquals(nistCipher.copyOf(n), encrypted, "加密 $n 字节")
            val decrypted = ByteArray(n)
            ctr.transform(nistCounter, 0, nistCipher, 0, n, decrypted, 0)
            assertContentEquals(nistPlain.copyOf(n), decrypted, "解密 $n 字节")
            assertContentEquals(nistPlain.copyOf(n), ctr.open(nistCounter + nistCipher.copyOf(n)), "open $n 字节")
        }
    }

    /**
     * 计数器按 128 位大端整体加一：低 32 位、低 64 位、全部 128 位分别溢出的 IV，
     * 与「AES-ECB 逐块加密 (IV + i) mod 2^128」独立算出的密钥流一致，也与 JCE 的 AES/CTR 一致。
     */
    @Test
    fun counterCarriesAcrossAll128Bits() {
        val ivs = listOf(
            "000000000000000000000000fffffffe",
            "0000000000000000ffffffffffffffff",
            "00000000ffffffffffffffffffffffff",
            "ffffffffffffffffffffffffffffffff",
            "fffffffffffffffffffffffffffffffd",
        )
        val ctr = AesCtr(key)
        for (ivHex in ivs) {
            val iv = ivHex.hexToBytes()
            val ours = ByteArray(80)
            ctr.transform(iv, 0, ByteArray(80), 0, 80, ours, 0)
            assertEquals(keystream(key, iv, 5).toHex(), ours.toHex(), "IV $ivHex")
            assertContentEquals(jceCtr(key, iv, ByteArray(80)), ours, "IV $ivHex 与 JCE")
        }
        // 低 64 位溢出后第二块的计数器是 00..01 00..00。
        val second = ByteArray(16)
        ctr.transform("00000000000000010000000000000000".hexToBytes(), 0, ByteArray(16), 0, 16, second, 0)
        val run = ByteArray(32)
        ctr.transform("0000000000000000ffffffffffffffff".hexToBytes(), 0, ByteArray(32), 0, 32, run, 0)
        assertContentEquals(second, run.copyOfRange(16, 32))
    }

    /** 跨越内部分段（4 KiB）的长数据、各种不对齐的长度，结果与 JCE 一致。 */
    @Test
    fun longDataMatchesJce() {
        val random = Random(3)
        val ctr = AesCtr(key)
        for (n in intArrayOf(4095, 4096, 4097, 8192 + 15, 100_003)) {
            val iv = ByteArray(16).also { random.nextBytes(it) }
            val plain = ByteArray(n).also { random.nextBytes(it) }
            val out = ByteArray(n)
            ctr.transform(iv, 0, plain, 0, n, out, 0)
            assertContentEquals(jceCtr(key, iv, plain), out, "长度 $n")
        }
    }

    @Test
    fun offsetsAndInPlace() {
        val ctr = AesCtr(nistKey)
        // IV、输入、输出都在更大的数组里。
        val ivHolder = byteArrayOf(1, 2) + nistCounter + byteArrayOf(3)
        val input = byteArrayOf(9, 9, 9) + nistPlain
        val output = ByteArray(5 + 64)
        ctr.transform(ivHolder, 2, input, 3, 64, output, 5)
        assertContentEquals(nistCipher, output.copyOfRange(5, 69))
        assertContentEquals(ByteArray(5), output.copyOf(5))
        // 原地变换。
        val inPlace = nistPlain.copyOf()
        ctr.transform(nistCounter, 0, inPlace, 0, 64, inPlace, 0)
        assertContentEquals(nistCipher, inPlace)
        // IV 和密文在同一个数组里、原地解密。
        val unit = nistCounter + nistCipher
        ctr.transform(unit, 0, unit, 16, 64, unit, 16)
        assertContentEquals(nistPlain, unit.copyOfRange(16, 80))
    }

    @Test
    fun sealDrawsOneIvAndOpenReverses() {
        val ctr = AesCtr(nistKey)
        for (plain in listOf(nistPlain, nistPlain.copyOf(17), ByteArray(0))) {
            val random = RecordingRandom(nistCounter)
            val sealed = ctr.seal(random, plain)
            assertEquals(listOf(16), random.requests, "每次只取一次 16 字节 IV")
            assertContentEquals(nistCounter + nistCipher.copyOf(plain.size), sealed)
            assertContentEquals(plain, ctr.open(sealed))
        }
        // 只加密数组里的一段；只解开数组里的一段。
        val sealed = ctr.seal(RecordingRandom(nistCounter), byteArrayOf(7) + nistPlain + byteArrayOf(7), 1, 64)
        assertContentEquals(nistCounter + nistCipher, sealed)
        val framed = byteArrayOf(0, 0) + sealed + byteArrayOf(0)
        assertContentEquals(nistPlain, ctr.open(framed, 2, sealed.size))
    }

    @Test
    fun openRejectsUnitWithoutFullIv() {
        val ctr = AesCtr(key)
        assertFailsWith<MalformedMessageException> { ctr.open(ByteArray(15)) }
        assertFailsWith<MalformedMessageException> { ctr.open(ByteArray(20), 10, 10) }
        assertEquals(0, ctr.open(ByteArray(16)).size)
    }

    @Test
    fun rejectsBadArguments() {
        assertFailsWith<IllegalArgumentException> { AesCtr(ByteArray(15)) }
        assertFailsWith<IllegalArgumentException> { AesCtr(ByteArray(32)) }
        val ctr = AesCtr(key)
        assertFailsWith<IllegalArgumentException> { ctr.transform(ByteArray(15), 0, ByteArray(1), 0, 1, ByteArray(1), 0) }
        assertFailsWith<IllegalArgumentException> { ctr.transform(ByteArray(16), 1, ByteArray(1), 0, 1, ByteArray(1), 0) }
        assertFailsWith<IllegalArgumentException> { ctr.transform(ByteArray(16), 0, ByteArray(1), 0, 2, ByteArray(2), 0) }
        assertFailsWith<IllegalArgumentException> { ctr.transform(ByteArray(16), 0, ByteArray(2), 0, 2, ByteArray(2), 1) }
        assertFailsWith<IllegalArgumentException> { ctr.seal(SecureRandom(), ByteArray(4), 3, 2) }
        assertFailsWith<IllegalArgumentException> { ctr.open(ByteArray(20), 10, 11) }
    }

    @Test
    fun toStringHidesKey() {
        val text = AesCtr(nistKey).toString()
        assertEquals("AesCtr(密钥不显示)", text)
        assertFalse(text.contains("2b7e"), text)
    }

    /** 独立算出的密钥流：AES-ECB 逐块加密 (IV + i) mod 2^128。 */
    private fun keystream(key: ByteArray, iv: ByteArray, blocks: Int): ByteArray {
        val ecb = Cipher.getInstance("AES/ECB/NoPadding")
        ecb.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val modulus = BigInteger.ONE.shiftLeft(128)
        val start = BigInteger(1, iv)
        val counters = ByteArray(blocks * 16)
        for (i in 0 until blocks) {
            val raw = start.add(BigInteger.valueOf(i.toLong())).mod(modulus).toByteArray()
            val block = ByteArray(16)
            val n = minOf(raw.size, 16)
            System.arraycopy(raw, raw.size - n, block, 16 - n, n)
            System.arraycopy(block, 0, counters, i * 16, 16)
        }
        return ecb.doFinal(counters)
    }

    private fun jceCtr(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    /** 记下每次 nextBytes 要了多少字节，并按顺序吐出给定的字节。 */
    private class RecordingRandom(private val fill: ByteArray) : SecureRandom() {
        val requests = ArrayList<Int>()

        override fun nextBytes(bytes: ByteArray) {
            requests += bytes.size
            System.arraycopy(fill, 0, bytes, 0, minOf(fill.size, bytes.size))
        }
    }
}
