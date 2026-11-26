package io.github.gcbond.openhc.platform.media

import io.github.gcbond.openhc.core.common.hexToBytes
import io.github.gcbond.openhc.core.common.toHex
import java.util.Random
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AnnexBTest {

    private fun hex(s: String) = s.replace(" ", "").hexToBytes()

    @Test
    fun splitsThreeAndFourByteStartCodes() {
        // 四字节起始码 + SPS，三字节起始码 + PPS，四字节起始码 + IDR 条带。
        val au = hex("00000001 6742c01f 000001 68ce3c80 00000001 65888400ff")
        assertEquals(
            listOf(AnnexB.NalUnit(4, 8, 7), AnnexB.NalUnit(11, 15, 8), AnnexB.NalUnit(19, 24, 5)),
            AnnexB.split(au),
        )
    }

    @Test
    fun ignoresBytesBeforeFirstStartCodeAndTrailingZeros() {
        // 开头的垃圾不算；NAL 末尾的 0（trailing_zero_8bits、下一个四字节起始码的头一个字节）去掉。
        val au = hex("abcd 000001 0911 0000 00000001 4101 000000")
        assertEquals(listOf(AnnexB.NalUnit(5, 7, 9), AnnexB.NalUnit(13, 15, 1)), AnnexB.split(au))
    }

    @Test
    fun skipsEmptyNalsAndDataWithoutStartCode() {
        assertEquals(emptyList(), AnnexB.split(hex("0102030405")), "没有起始码")
        assertEquals(emptyList(), AnnexB.split(ByteArray(0)))
        assertEquals(emptyList(), AnnexB.split(hex("00000001")), "起始码后面没有数据")
        assertEquals(emptyList(), AnnexB.split(hex("000001 0000")), "去掉末尾的 0 之后是空的")
        assertEquals(listOf(AnnexB.NalUnit(6, 8, 1)), AnnexB.split(hex("000001 000001 4101")), "两个起始码挨着")
    }

    @Test
    fun respectsOffsetAndLength() {
        // ff | 00 00 01 65 01 | 00 00 01 41 02 | ff
        val data = hex("ff 000001 6501 000001 4102 ff")
        assertEquals(listOf(AnnexB.NalUnit(4, 6, 5)), AnnexB.split(data, 1, 7), "第二个起始码被截在范围外")
        assertEquals(listOf(AnnexB.NalUnit(4, 6, 5), AnnexB.NalUnit(9, 11, 1)), AnnexB.split(data, 1, 10))
    }

    @Test
    fun startCodeSearchMatchesNaiveSearch() {
        val random = Random(1234)
        repeat(5000) {
            // 0 和 1 多一些，起始码和「差一点的起始码」才会频繁出现。
            val data = ByteArray(random.nextInt(40)) {
                when (random.nextInt(4)) {
                    0, 1 -> 0
                    2 -> 1
                    else -> random.nextInt(256)
                }.toByte()
            }
            val from = if (data.isEmpty()) 0 else random.nextInt(data.size)
            assertEquals(naiveFind(data, from), AnnexB.findStartCode(data, from, data.size), data.toHex())
        }
    }

    @Test
    fun escapeInsertsEmulationPreventionBytes() {
        assertContentEquals(hex("00000301"), AnnexB.escape(hex("000001")))
        assertContentEquals(hex("00000302"), AnnexB.escape(hex("000002")))
        assertContentEquals(hex("00000303"), AnnexB.escape(hex("000003")), "00 00 03 本身也要转义")
        assertContentEquals(hex("000004"), AnnexB.escape(hex("000004")), "00 00 04 不用转义")
        assertContentEquals(hex("00000300000301"), AnnexB.escape(hex("0000000001")), "转义后零的计数从头算")
        assertContentEquals(hex("000003"), AnnexB.escape(hex("0000")), "以 00 结尾（cabac_zero_word）时末尾补 03")
        assertContentEquals(hex("0000030003"), AnnexB.escape(hex("000000")), "先在第三个 00 前插入，再在末尾补")
    }

    @Test
    fun escapeAndUnescapeRoundTrip() {
        val random = Random(99)
        repeat(1000) {
            val size = random.nextInt(64)
            val rbsp = ByteArray(size) { (if (random.nextInt(3) == 0) random.nextInt(4) else 0).toByte() }
            // 真实的 RBSP 以停止位结尾，最后一个字节不是 0。
            if (size > 0) rbsp[size - 1] = 0x80.toByte()
            val escaped = AnnexB.escape(rbsp)
            assertFalse(containsStartCodeEmulation(escaped), "转义后不能出现 00 00 00、00 00 01、00 00 02：${escaped.toHex()}")
            assertContentEquals(rbsp, AnnexB.unescape(escaped))
        }
    }

    @Test
    fun readerRemovesEmulationPreventionOnTheFly() {
        // RBSP 00 00 01 80 在 NAL 里是 00 00 03 01 80。
        val reader = RbspReader(hex("0000030180"), 0, 5)
        assertEquals(0L, reader.bits(16))
        assertEquals(1L, reader.bits(8))
        assertEquals(1, reader.bit())
    }

    @Test
    fun readerDecodesExpGolomb() {
        // ue：1 → 0，010 → 1，011 → 2，00100 → 3；se：010 → +1，011 → -1，00100 → +2。
        // 最后一个值有 31 个前导 0，读的是 NAL 载荷，所以要先加防竞争字节。
        val bits = AnnexB.escape(TestBits().ue(0).ue(1).ue(2).ue(3).se(1).se(-1).se(2).ue(4_000_000_000L).rbsp())
        val reader = RbspReader(bits, 0, bits.size)
        assertEquals(0, reader.ue())
        assertEquals(1, reader.ue())
        assertEquals(2, reader.ue())
        assertEquals(3, reader.ue())
        assertEquals(1, reader.se())
        assertEquals(-1, reader.se())
        assertEquals(2, reader.se())
        assertEquals(4_000_000_000L, reader.ueLong())
    }

    private fun naiveFind(data: ByteArray, from: Int): Int {
        for (i in from until data.size - 2) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) return i
        }
        return -1
    }

    private fun containsStartCodeEmulation(data: ByteArray): Boolean {
        for (i in 0 until data.size - 2) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() in 0..2) return true
        }
        return false
    }
}
