package io.github.gcbond.openhc.core.common.codec

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Crc16ArcTest {

    /** CRC-16/ARC 的公开校验值：ASCII "123456789" → 0xBB3D。 */
    @Test
    fun standardCheckValue() {
        assertEquals(0xBB3D, Crc16Arc.compute("123456789".toByteArray(Charsets.US_ASCII)))
        assertEquals(0, Crc16Arc.compute(ByteArray(0)))
    }

    @Test
    fun syntheticPatternVector() {
        // 第 i 个字节 = (7i + 3) mod 256，共 64 字节；期望值由另一份独立实现算出。
        val pattern = ByteArray(64) { ((it * 7 + 3) and 0xFF).toByte() }
        assertEquals(0x412E, Crc16Arc.compute(pattern))
    }

    @Test
    fun honoursOffsetAndLength() {
        val padded = byteArrayOf(9, 9) + "123456789".toByteArray(Charsets.US_ASCII) + byteArrayOf(9)
        assertEquals(0xBB3D, Crc16Arc.compute(padded, 2, 9))
        assertEquals(Crc16Arc.compute(padded.copyOfRange(2, padded.size)), Crc16Arc.compute(padded, 2))
        assertEquals(0, Crc16Arc.compute(padded, padded.size, 0))
    }

    /** 查表实现与按定义逐位计算的结果一致。 */
    @Test
    fun matchesBitwiseDefinition() {
        val random = Random(7)
        for (n in listOf(1, 2, 15, 16, 17, 255, 1000)) {
            val data = ByteArray(n).also { random.nextBytes(it) }
            var crc = 0
            for (b in data) {
                crc = crc xor (b.toInt() and 0xFF)
                repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
            }
            assertEquals(crc, Crc16Arc.compute(data), "长度 $n")
        }
    }

    @Test
    fun rejectsBadRange() {
        val data = ByteArray(4)
        assertFailsWith<IllegalArgumentException> { Crc16Arc.compute(data, -1, 1) }
        assertFailsWith<IllegalArgumentException> { Crc16Arc.compute(data, 0, 5) }
        assertFailsWith<IllegalArgumentException> { Crc16Arc.compute(data, 3, 2) }
        assertFailsWith<IllegalArgumentException> { Crc16Arc.compute(data, 1, Int.MAX_VALUE) }
    }
}
