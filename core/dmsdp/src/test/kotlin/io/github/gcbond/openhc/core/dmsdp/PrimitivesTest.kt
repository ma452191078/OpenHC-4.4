package io.github.gcbond.openhc.core.dmsdp

import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.common.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PrimitivesTest {

    @Test
    fun eaEncodingMatchesCrossVectors() {
        val vectors = mapOf(
            0 to "80", 1 to "81", 0x7F to "ff", 0x80 to "0081", 101 to "e5", 102 to "e6", 103 to "e7",
            104 to "e8", 105 to "e9", 0x3FFF to "7fff", 0x4000 to "000081", 0x1FFFFF to "7f7fff",
            0x200000 to "00000081", 0x0FFFFFFF to "7f7f7fff",
        )
        for ((value, encoded) in vectors) {
            assertEquals(encoded, Ea.encode(value).toHex(), "EA($value)")
            val bytes = hex(encoded)
            val decoded = Ea.decode(bytes, 0, bytes.size)!!
            assertEquals(value, decoded.value)
            assertEquals(bytes.size, decoded.next)
        }
        assertFailsWith<IllegalArgumentException> { Ea.encode(-1) }
        assertFailsWith<IllegalArgumentException> { Ea.encode(0x1000_0000) }
    }

    @Test
    fun eaDecodeDistinguishesShortFromOverlong() {
        assertNull(Ea.decode(hex("0000"), 0, 2), "还没见到结束位、不足 4 字节：等更多数据")
        assertFailsWith<MalformedMessageException> { Ea.decode(hex("00000000"), 0, 4) }
        assertFailsWith<MalformedMessageException> { Ea.decodeComplete(hex("0000"), 0, "测试") }
        val decoded = Ea.decode(hex("aa0081ff"), 1, 4)!!
        assertEquals(128, decoded.value)
        assertEquals(3, decoded.next)
    }

    /** CRC-16/ARC 的公开校验值。 */
    @Test
    fun crc16ArcCheckValue() {
        assertEquals(0xBB3D, Crc16Arc.compute("123456789".toByteArray()))
        assertEquals(0, Crc16Arc.compute(ByteArray(0)))
    }

    /** NIST SP 800-38A F.5.1 / F.5.2（CTR-AES128）。 */
    @Test
    fun aesCtrNistVectors() {
        val key = hex("2b7e151628aed2a6abf7158809cf4f3c")
        val iv = hex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff")
        val plain = hex(
            "6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e51" +
                "30c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710",
        )
        val cipherText = hex(
            "874d6191b620e3261bef6864990db6ce9806f66b7970fdff8617187bb9fffdff" +
                "5ae4df3edbd5d35e5b4f09020db03eab1e031dda2fbe03d1792170a0f3009cee",
        )
        val ctr = AesCtr(key)
        for (size in listOf(plain.size, 17, 1, 0)) {
            val out = ByteArray(size)
            ctr.transform(iv, plain, 0, size, out, 0)
            assertContentEquals(cipherText.copyOf(size), out, "加密 $size 字节")
            assertContentEquals(plain.copyOf(size), ctr.open(iv + cipherText.copyOf(size)), "解密 $size 字节")
        }
    }

    /**
     * 计数器按 128 位大端整体加一。
     *
     * 另一份实现所用的加密库只在低 64 位里加一并回绕，所以 IV 低 64 位全 1 时，从第二块起两边不同。
     * 这种情况要随机 IV 的低 64 位离溢出不到一帧的块数才会出现（每帧约 2^-52），实际碰不到；
     * 这里取 128 位整体进位，跨进位的部分以 JCE 自带的 AES/CTR/NoPadding 为参照。
     */
    @Test
    fun aesCtrCounterCarry() {
        val ctr = AesCtr(Synthetic.KEY)
        val plain37 = "OpenHC synthetic DMSDP vector 37 B!!!".toByteArray()
        fun seal(iv: ByteArray, plain: ByteArray): ByteArray {
            val out = ByteArray(16 + plain.size)
            System.arraycopy(iv, 0, out, 0, 16)
            ctr.transform(iv, plain, 0, plain.size, out, 16)
            return out
        }
        fun jce(iv: ByteArray, plain: ByteArray): ByteArray {
            val cipher = javax.crypto.Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(
                javax.crypto.Cipher.ENCRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(Synthetic.KEY, "AES"),
                javax.crypto.spec.IvParameterSpec(iv),
            )
            return iv + cipher.doFinal(plain)
        }
        assertEquals(CrossVectors.CTR_IV_ZERO, seal(ByteArray(16), plain37).toHex())
        val low64 = ByteArray(16) { if (it >= 8) 0xFF.toByte() else 0 }
        val fullWrap = ByteArray(16) { 0xFF.toByte() }
        for ((iv, vector) in listOf(low64 to CrossVectors.CTR_IV_LOW64_CARRY, fullWrap to CrossVectors.CTR_IV_FULL_WRAP)) {
            val ours = seal(iv, ByteArray(48))
            assertEquals(vector.substring(0, 64), ours.toHex().substring(0, 64), "第一块与另一份实现一致")
            assertContentEquals(jce(iv, ByteArray(48)), ours, "进位与 JCE 一致")
        }
        // 低 64 位进位到高 64 位：第二块的计数器是 00..01 00..00。
        val second = ByteArray(16)
        ctr.transform(hex("00000000000000010000000000000000"), ByteArray(16), 0, 16, second, 0)
        assertEquals(seal(low64, ByteArray(32)).copyOfRange(32, 48).toHex(), second.toHex())
    }

    @Test
    fun aesCtrSealUsesInjectedRandomAndRejectsShortPayload() {
        val random = SequenceRandom()
        val sealed = AesCtr(Synthetic.KEY).seal(random, "abc".toByteArray())
        assertEquals(19, sealed.size)
        assertContentEquals(ByteArray(16) { ((it * 37 + 11) and 0xFF).toByte() }, sealed.copyOf(16))
        assertEquals(16, random.index)
        assertFailsWith<MalformedMessageException> { AesCtr(Synthetic.KEY).open(ByteArray(15)) }
        assertFailsWith<IllegalArgumentException> { AesCtr(ByteArray(15)) }
        assertFailsWith<IllegalArgumentException> { AesCtr(ByteArray(32)) }
        assertEquals("AesCtr(密钥不显示)", AesCtr(Synthetic.KEY).toString())
    }

    @Test
    fun strictUtf8RejectsInvalidSequences() {
        assertEquals("测试", strictUtf8("测试".toByteArray(), what = "测试"))
        for (bad in listOf("ff", "c0af", "eda080", "e6b5")) {
            assertFailsWith<MalformedMessageException>(bad) { strictUtf8(hex(bad), what = "测试") }
        }
    }

    @Test
    fun streamBufferCompactsAndGrows() {
        val buffer = StreamBuffer()
        val data = ByteArray(1000) { it.toByte() }
        buffer.append(data, 0, 200)
        buffer.consume(150)
        buffer.append(data, 200, 800)
        assertEquals(850, buffer.size)
        assertContentEquals(data.copyOfRange(150, 1000), buffer.copy(0, 850))
        buffer.consume(850)
        assertEquals(0, buffer.size)
    }
}
