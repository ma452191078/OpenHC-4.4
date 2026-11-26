package io.github.gcbond.openhc.platform.media

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VideoViewportTest {

    @Test
    fun exactFitHasNoBars() {
        val vp = VideoViewport(1920, 1080, 1920, 1080)
        assertEquals(listOf(0, 0, 1920, 1080), listOf(vp.left, vp.top, vp.width, vp.height))
        assertEquals(0, vp.videoX(0f))
        assertEquals(1919, vp.videoX(1919.9f))
        assertEquals(540, vp.videoY(540.5f))
    }

    @Test
    fun widerVideoGetsBarsTopAndBottom() {
        // 1920×720 的宽屏画面放进 1920×1080：上下各 180 像素黑边。
        val vp = VideoViewport(1920, 1080, 1920, 720)
        assertEquals(listOf(0, 180, 1920, 720), listOf(vp.left, vp.top, vp.width, vp.height))
        assertEquals(0, vp.videoY(180f))
        assertEquals(719, vp.videoY(899.9f))
        assertEquals(0f, vp.distanceOutside(960f, 500f))
        assertEquals(30f, vp.distanceOutside(960f, 150f))
        assertEquals(20f, vp.distanceOutside(960f, 920f))
    }

    @Test
    fun tallerVideoGetsBarsLeftAndRight() {
        // 竖屏 1080×2340 放进 1920×1080：高度占满，宽度按比例四舍五入成 498。
        val vp = VideoViewport(1920, 1080, 1080, 2340)
        assertEquals(1080, vp.height)
        assertEquals(498, vp.width)
        assertEquals((1920 - 498) / 2, vp.left)
        assertEquals(0, vp.top)
        assertEquals(0, vp.videoX(vp.left.toFloat()))
        assertEquals(1079, vp.videoX(vp.right - 0.01f))
        assertEquals(2339, vp.videoY(1079.99f))
    }

    @Test
    fun scalesUpSmallVideoAndMapsBackToPixels() {
        // 320×180 放大 6 倍铺满 1920×1080：视图上 6 个像素对应视频 1 个像素。
        val vp = VideoViewport(1920, 1080, 320, 180)
        assertEquals(listOf(0, 0, 1920, 1080), listOf(vp.left, vp.top, vp.width, vp.height))
        assertEquals(0, vp.videoX(5.99f))
        assertEquals(1, vp.videoX(6f))
        assertEquals(160, vp.videoX(960f))
        assertEquals(90, vp.videoY(540f))
    }

    @Test
    fun clampsOutsidePointsToEdgePixels() {
        val vp = VideoViewport(1920, 1080, 1080, 1080)
        assertEquals(420, vp.left)
        assertEquals(0, vp.videoX(0f), "左边黑边钳到第 0 列")
        assertEquals(1079, vp.videoX(1919f), "右边黑边钳到最后一列")
        assertEquals(0, vp.videoY(-50f))
        assertEquals(1079, vp.videoY(5000f))
        assertEquals(0, vp.videoX(Float.NaN))
        assertEquals(50f, vp.distanceOutside(370f, 500f))
        assertEquals(5f, vp.distanceOutside(417f, -4f), "角上按直线距离：3-4-5")
    }

    @Test
    fun equalityDependsOnInputsOnly() {
        assertEquals(VideoViewport(800, 480, 1280, 720), VideoViewport(800, 480, 1280, 720))
        assertFailsWith<IllegalArgumentException> { VideoViewport(0, 480, 1280, 720) }
        assertFailsWith<IllegalArgumentException> { VideoViewport(800, 480, 1280, 0) }
    }
}
