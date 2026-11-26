package io.github.gcbond.openhc.platform.media

import io.github.gcbond.openhc.core.common.MalformedMessageException
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AccessUnitScanTest {

    /** 条带 NAL：只写条带头的前几个字段（first_mb_in_slice、slice_type、pps id），后面随便补点数据。 */
    private fun slice(header: Int, firstMb: Int, sliceType: Int): ByteArray =
        nal(header, TestBits().ue(firstMb).ue(sliceType).ue(0).u(16, 0xABCD).rbsp())

    private fun pps(id: Int, tail: Int = 0): ByteArray = nal(0x68, TestBits().ue(id).ue(0).u(8, tail).rbsp())

    private val sps = spsNal(widthMbs = 20, heightMapUnits = 12, crop = intArrayOf(0, 0, 0, 6))
    private val sei = nal(0x06, byteArrayOf(5, 1, 0x55, 0x80.toByte()))

    @Test
    fun keyFrameWithParameterSets() {
        val au = accessUnit(sps, pps(0), sei, slice(0x65, 0, 7))
        val scan = AccessUnitScan().scan(au, 0, au.size)
        assertEquals(4, scan.nalCount)
        assertTrue(scan.hasIdr)
        assertTrue(scan.isSyncPoint)
        assertFalse(scan.isParameterSetsOnly)
        assertEquals(1, scan.sliceCount)
        assertContentEquals(sps, au.copyOfRange(scan.spsStart, scan.spsEnd))
        assertEquals(1, scan.ppsCount)
        assertContentEquals(pps(0), au.copyOfRange(scan.ppsStart(0), scan.ppsEnd(0)))
    }

    @Test
    fun classifiesSliceTypes() {
        val scan = AccessUnitScan()
        val p = accessUnit(slice(0x41, 0, 5))
        scan.scan(p, 0, p.size)
        assertFalse(scan.isSyncPoint, "P 帧")
        assertFalse(scan.hasBSlice)

        // 不是 IDR，但所有条带都是帧内条带（slice_type 7、2）：可以从这里开始解码。
        val allIntra = accessUnit(slice(0x21, 0, 7), slice(0x21, 40, 2))
        scan.scan(allIntra, 0, allIntra.size)
        assertFalse(scan.hasIdr)
        assertEquals(2, scan.sliceCount)
        assertTrue(scan.isSyncPoint)

        val mixed = accessUnit(slice(0x21, 0, 2), slice(0x21, 40, 0))
        scan.scan(mixed, 0, mixed.size)
        assertFalse(scan.isSyncPoint, "一半帧内、一半 P")

        val b = accessUnit(slice(0x01, 0, 6))
        scan.scan(b, 0, b.size)
        assertTrue(scan.hasBSlice)
        assertFalse(scan.isSyncPoint)

        val si = accessUnit(slice(0x21, 0, 9))
        scan.scan(si, 0, si.size)
        assertTrue(scan.isSyncPoint, "SI 条带也是帧内")
    }

    @Test
    fun unreadableSliceHeaderIsNotTreatedAsIntra() {
        // 条带头只有一个全 0 字节：读不出 slice_type，当作不能开始解码。
        val au = accessUnit(byteArrayOf(0x41, 0))
        val scan = AccessUnitScan().scan(au, 0, au.size)
        assertEquals(1, scan.nalCount)
        assertEquals(1, scan.sliceCount)
        assertFalse(scan.isSyncPoint)
        assertFalse(scan.hasBSlice)
    }

    @Test
    fun parameterSetsOnlyAndSeiOnly() {
        val scan = AccessUnitScan()
        val params = accessUnit(sps, pps(0), pps(1))
        scan.scan(params, 0, params.size)
        assertTrue(scan.isParameterSetsOnly)
        assertFalse(scan.isSyncPoint)
        assertEquals(2, scan.ppsCount)

        val seiOnly = accessUnit(sei)
        scan.scan(seiOnly, 0, seiOnly.size)
        assertEquals(1, scan.nalCount)
        assertFalse(scan.isParameterSetsOnly)
        assertEquals(0, scan.sliceCount)
        assertEquals(-1, scan.spsStart, "上一次的结果要清掉")
        assertEquals(0, scan.ppsCount)
    }

    @Test
    fun parameterSetsRememberLatestAndBuildCodecConfig() {
        val sets = ParameterSets()
        assertFalse(sets.isComplete)
        val first = sets.updateSps(sps, 0, sps.size)
        assertEquals(320, first.width)
        assertEquals(180, first.height)
        assertSame(first, sets.updateSps(sps, 0, sps.size), "内容没变就不重新解析")
        sets.updatePps(pps(1), 0, pps(1).size)
        sets.updatePps(pps(0), 0, pps(0).size)
        assertTrue(sets.isComplete)

        val startCode = byteArrayOf(0, 0, 0, 1)
        assertContentEquals(startCode + sps, sets.csd0())
        assertContentEquals(startCode + pps(0) + startCode + pps(1), sets.csd1(), "PPS 按编号排序")

        // 同一个编号的 PPS 换内容：覆盖旧的。
        sets.updatePps(pps(1, tail = 7), 0, pps(1, tail = 7).size)
        assertContentEquals(startCode + pps(0) + startCode + pps(1, tail = 7), sets.csd1())

        // SPS 变了：换成新的。
        val portrait = spsNal(widthMbs = 12, heightMapUnits = 20, crop = intArrayOf(0, 6, 0, 0))
        val second = sets.updateSps(portrait, 0, portrait.size)
        assertEquals(180, second.width)
        assertEquals(320, second.height)
        assertSame(second, sets.sps)
    }

    @Test
    fun malformedParameterSetsKeepPreviousState() {
        val sets = ParameterSets()
        sets.updateSps(sps, 0, sps.size)
        val broken = sps.copyOf(5)
        assertFailsWith<MalformedMessageException> { sets.updateSps(broken, 0, broken.size) }
        assertEquals(320, sets.sps?.width)
        assertFailsWith<MalformedMessageException>("不是 PPS") { sets.updatePps(sps, 0, sps.size) }
        assertFailsWith<MalformedMessageException>("PPS 编号超出范围") {
            val tooLarge = nal(0x68, TestBits().ue(256).rbsp())
            sets.updatePps(tooLarge, 0, tooLarge.size)
        }
        sets.clear()
        assertNull(sets.sps)
        assertFalse(sets.isComplete)
    }
}
