package io.github.gcbond.openhc.platform.media

import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.common.hexToBytes
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class H264SpsTest {

    /**
     * 用开源编码器对合成测试图案（彩条、计数器）编码得到的 SPS，与解析器的实现互相独立。
     * 期望值是另一个开源解码器解析同一字节得到的字段。
     */
    private object Vectors {
        /** Constrained Baseline，1280×720，无裁剪，VUI 带时间信息，不需要重排。 */
        val BASELINE_720 = "6742c01fd9005005bb0110000003001000000303c0f1832480".hexToBytes()

        /** High，1920×1080（编码 1088，底部裁 8 行），有 B 帧：需要重排 2 帧。 */
        val HIGH_1080 = "67640028acd940780227e5c044000003000400000300f03c60c658".hexToBytes()

        /** High，720×576 隔行（frame_mbs_only_flag = 0，高度按场的宏块对计）。 */
        val HIGH_INTERLACED = "6764001facd940b424d80880000003008000001e0f8b16cb".hexToBytes()

        /** High，1918×1078（右裁 2、底裁 10 像素），无 B 帧。 */
        val HIGH_ODD = "67640028acb200f0044f536022000003000200000300791e306490".hexToBytes()

        /** Main，1080×2340 竖屏（编码 1088×2352），无 B 帧。 */
        val MAIN_PORTRAIT = "674d4032d900440127e59f0110000003001000000303c0f1832480".hexToBytes()
    }

    @Test
    fun parsesBaseline720() {
        val sps = H264Sps.parse(Vectors.BASELINE_720)
        assertEquals(66, sps.profileIdc)
        assertEquals(0xC0, sps.constraintFlags)
        assertEquals(31, sps.levelIdc)
        assertEquals(2, sps.picOrderCntType)
        assertEquals(3, sps.maxNumRefFrames)
        assertEquals(1280, sps.codedWidth)
        assertEquals(720, sps.codedHeight)
        assertEquals(1280, sps.width)
        assertEquals(720, sps.height)
        assertEquals(0, sps.maxNumReorderFrames)
        assertEquals(3, sps.maxDecFrameBuffering)
        assertTrue(sps.reorderFree)
    }

    @Test
    fun parsesHigh1080WithBottomCrop() {
        val sps = H264Sps.parse(Vectors.HIGH_1080)
        assertEquals(100, sps.profileIdc)
        assertEquals(40, sps.levelIdc)
        assertEquals(1, sps.chromaFormatIdc)
        assertEquals(8, sps.bitDepthLuma)
        assertEquals(0, sps.picOrderCntType)
        assertEquals(1920, sps.codedWidth)
        assertEquals(1088, sps.codedHeight)
        assertEquals(1920, sps.width)
        assertEquals(1080, sps.height)
        assertEquals(2, sps.maxNumReorderFrames)
        assertEquals(4, sps.maxDecFrameBuffering)
        assertFalse(sps.reorderFree, "有 B 帧重排，不能按解码顺序立即输出")
    }

    @Test
    fun parsesInterlacedHeightInFieldMacroblockPairs() {
        val sps = H264Sps.parse(Vectors.HIGH_INTERLACED)
        assertFalse(sps.frameMbsOnly)
        assertEquals(720, sps.width)
        assertEquals(576, sps.codedHeight, "18 个场宏块对 × 2 × 16")
        assertEquals(576, sps.height)
    }

    @Test
    fun parsesOddCropOnBothEdges() {
        val sps = H264Sps.parse(Vectors.HIGH_ODD)
        assertEquals(1920, sps.codedWidth)
        assertEquals(1088, sps.codedHeight)
        assertEquals(1918, sps.width)
        assertEquals(1078, sps.height)
        assertEquals(0, sps.maxNumReorderFrames)
        assertTrue(sps.reorderFree)
    }

    @Test
    fun parsesPortraitMain() {
        val sps = H264Sps.parse(Vectors.MAIN_PORTRAIT)
        assertEquals(77, sps.profileIdc)
        assertEquals(50, sps.levelIdc)
        assertEquals(1088, sps.codedWidth)
        assertEquals(2352, sps.codedHeight)
        assertEquals(1080, sps.width)
        assertEquals(2340, sps.height)
        assertTrue(sps.reorderFree)
    }

    @Test
    fun parsesSyntheticBaselineWithoutVui() {
        val sps = H264Sps.parse(spsNal(widthMbs = 120, heightMapUnits = 68, crop = intArrayOf(0, 0, 0, 4)))
        assertEquals(1920, sps.width)
        assertEquals(1080, sps.height)
        assertNull(sps.maxNumReorderFrames)
        assertTrue(sps.reorderFree, "Baseline 不允许 B 条带")
    }

    @Test
    fun parsesHighProfileScalingMatrixAndFullVui() {
        val sps = H264Sps.parse(
            spsNal(
                profile = 100,
                widthMbs = 40,
                heightMapUnits = 23,
                crop = intArrayOf(0, 0, 0, 4),
                scalingMatrix = true,
                pocType = 1,
                maxRefFrames = 2,
                vui = 0 to 2,
                vuiTiming = true,
                vuiHrd = true,
            ),
        )
        assertEquals(640, sps.width)
        assertEquals(360, sps.height)
        assertEquals(1, sps.picOrderCntType)
        assertEquals(2, sps.maxNumRefFrames)
        assertEquals(0, sps.maxNumReorderFrames, "VUI 要穿过缩放矩阵、时间信息和 HRD 才能读到")
        assertEquals(2, sps.maxDecFrameBuffering)
        assertTrue(sps.reorderFree)
    }

    @Test
    fun cropUnitsFollowChromaFormat() {
        // 4:4:4：横向裁剪单位 1，纵向 1。
        val yuv444 = H264Sps.parse(spsNal(profile = 244, chromaFormatIdc = 3, widthMbs = 10, heightMapUnits = 10, crop = intArrayOf(1, 2, 3, 4)))
        assertEquals(160 - 3, yuv444.width)
        assertEquals(160 - 7, yuv444.height)
        // 4:2:2：横向 2，纵向 1。
        val yuv422 = H264Sps.parse(spsNal(profile = 122, chromaFormatIdc = 2, widthMbs = 10, heightMapUnits = 10, crop = intArrayOf(1, 2, 3, 4)))
        assertEquals(160 - 6, yuv422.width)
        assertEquals(160 - 7, yuv422.height)
        // 单色：裁剪单位按亮度像素，场编码时纵向乘 2。
        val mono = H264Sps.parse(
            spsNal(profile = 100, chromaFormatIdc = 0, widthMbs = 10, heightMapUnits = 5, frameMbsOnly = false, crop = intArrayOf(1, 1, 1, 1)),
        )
        assertEquals(160 - 2, mono.width)
        assertEquals(160 - 4, mono.height)
    }

    @Test
    fun handlesEmulationPreventionInsideSps() {
        val nal = spsNal(profile = 77, widthMbs = 20, heightMapUnits = 12, crop = intArrayOf(0, 0, 0, 6), vui = 0 to 1, vuiTiming = true)
        assertTrue(
            (0 until nal.size - 2).any { nal[it].toInt() == 0 && nal[it + 1].toInt() == 0 && nal[it + 2].toInt() == 3 },
            "构造的 SPS 应该带防竞争字节",
        )
        val sps = H264Sps.parse(nal)
        assertEquals(320, sps.width)
        assertEquals(180, sps.height)
        assertEquals(0, sps.maxNumReorderFrames, "防竞争字节之后的 VUI 字段也要读对")
    }

    @Test
    fun acceptsOffsetIntoLargerBuffer() {
        val nal = spsNal(widthMbs = 20, heightMapUnits = 12, crop = intArrayOf(0, 0, 0, 6))
        val buffer = byteArrayOf(9, 9, 9) + nal + byteArrayOf(7, 7)
        val sps = H264Sps.parse(buffer, 3, nal.size)
        assertEquals(320, sps.width)
        assertEquals(180, sps.height)
    }

    @Test
    fun truncatedVuiKeepsDimensions() {
        // 把 VUI 的后半截切掉：尺寸照常给出，只是不知道要不要重排。
        val full = spsNal(profile = 77, widthMbs = 20, heightMapUnits = 12, vui = 0 to 1, vuiTiming = true)
        val truncated = full.copyOf(full.size - 6)
        val sps = H264Sps.parse(truncated)
        assertEquals(320, sps.width)
        assertEquals(192, sps.height)
        assertNull(sps.maxNumReorderFrames)
        assertFalse(sps.reorderFree)
    }

    @Test
    fun rejectsMalformedSps() {
        assertFailsWith<MalformedMessageException>("不是 SPS") { H264Sps.parse(nal(0x68, TestBits().ue(0).rbsp())) }
        assertFailsWith<MalformedMessageException>("太短") { H264Sps.parse("6742c0".hexToBytes()) }
        assertFailsWith<MalformedMessageException>("尺寸之前就截断") { H264Sps.parse(Vectors.HIGH_1080.copyOf(8)) }
        assertFailsWith<MalformedMessageException>("裁剪超出画面") {
            H264Sps.parse(spsNal(widthMbs = 2, heightMapUnits = 2, crop = intArrayOf(8, 8, 0, 0)))
        }
        assertFailsWith<MalformedMessageException>("宽度超出上限") { H264Sps.parse(spsNal(widthMbs = 1025)) }
        assertFailsWith<MalformedMessageException>("chroma_format_idc 超出范围") {
            H264Sps.parse(spsNal(profile = 100, chromaFormatIdc = 4))
        }
    }
}
