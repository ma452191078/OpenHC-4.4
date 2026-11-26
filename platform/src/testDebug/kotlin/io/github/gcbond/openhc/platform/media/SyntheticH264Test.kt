package io.github.gcbond.openhc.platform.media

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyntheticH264Test {

    @Test
    fun keyFrameCarriesParameterSetsWithCroppedSize() {
        val landscape = SyntheticH264(320, 180)
        val au = landscape.encode()
        assertEquals(listOf(7, 8, 5), AnnexB.split(au).map { it.type })
        val scan = AccessUnitScan().scan(au, 0, au.size)
        assertTrue(scan.isSyncPoint)
        val sps = H264Sps.parse(au, scan.spsStart, scan.spsEnd - scan.spsStart)
        assertEquals(320, sps.width)
        assertEquals(180, sps.height)
        assertEquals(192, sps.codedHeight)
        assertTrue(sps.reorderFree)
        assertEquals(0, sps.maxNumReorderFrames)

        val portrait = SyntheticH264(180, 320)
        val pau = portrait.encode()
        val pscan = AccessUnitScan().scan(pau, 0, pau.size)
        val psps = H264Sps.parse(pau, pscan.spsStart, pscan.spsEnd - pscan.spsStart)
        assertEquals(180, psps.width)
        assertEquals(320, psps.height)
        assertEquals(192, psps.codedWidth)
    }

    @Test
    fun unchangedMacroblocksAreSkipped() {
        val encoder = SyntheticH264(320, 180, keyFrameInterval = 100)
        encoder.frame.fill(Yuv.of(30, 30, 30))
        val key = encoder.encode()
        // 一个宏块都没变：整帧跳过，只剩条带头。
        val still = encoder.encode()
        assertEquals(listOf(1), AnnexB.split(still).map { it.type })
        assertTrue(still.size < 16, "全跳过的 P 帧只有几个字节，实际 ${still.size}")
        // 改一个 4×4 的块：只重写它所在的那个宏块。
        encoder.frame.fillRect(100, 100, 4, 4, Yuv.of(255, 0, 0))
        val changed = encoder.encode()
        assertTrue(changed.size in 384..420, "一个 I_PCM 宏块约 384 字节，实际 ${changed.size}")
        val scan = AccessUnitScan().scan(changed, 0, changed.size)
        assertFalse(scan.isSyncPoint)
        assertEquals(1, scan.sliceCount)
        assertTrue(key.size > 240 * 384, "关键帧里 240 个宏块全部是 I_PCM")
    }

    @Test
    fun keyFramesFollowIntervalAndForce() {
        val encoder = SyntheticH264(64, 64, keyFrameInterval = 3)
        val types = (0 until 8).map { i ->
            val au = encoder.encode(forceKeyFrame = i == 4)
            AnnexB.split(au).any { it.type == AnnexB.NAL_IDR }
        }
        assertEquals(listOf(true, false, false, true, true, false, false, true), types)
    }

    @Test
    fun outputNeverEmulatesStartCodes() {
        val encoder = SyntheticH264(96, 64)
        // 很暗的颜色让 PCM 样本里出现 01～03，配合条带头里的 0，最容易出现需要转义的组合。
        encoder.frame.fill((16 shl 16) or (1 shl 8) or 2)
        encoder.frame.fillRect(0, 0, 32, 32, (1 shl 16) or (3 shl 8) or 1)
        repeat(3) {
            val au = encoder.encode()
            for (nal in AnnexB.split(au)) {
                for (i in nal.start until nal.end - 2) {
                    val emulated = au[i].toInt() == 0 && au[i + 1].toInt() == 0 && au[i + 2].toInt() in 0..2
                    assertFalse(emulated, "NAL 里出现了起始码样式的字节")
                }
            }
            encoder.frame.fillRect(it * 16, 40, 16, 16, (1 shl 16) or (1 shl 8) or 1)
        }
    }

    @Test
    fun croppedPlanesMatchYuv420pLayout() {
        val frame = YuvFrame(32, 32)
        frame.fill(Yuv.of(0, 0, 255))
        frame.fillRect(0, 0, 2, 2, Yuv.of(255, 255, 255))
        val out = frame.cropped(4, 2)
        assertEquals(4 * 2 + 2 * 2 * 1, out.size)
        assertEquals(Yuv.y(Yuv.of(255, 255, 255)), out[0].toInt() and 0xFF)
        assertEquals(Yuv.y(Yuv.of(0, 0, 255)), out[2].toInt() and 0xFF)
    }
}
