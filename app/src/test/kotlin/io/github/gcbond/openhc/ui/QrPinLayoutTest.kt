package io.github.gcbond.openhc.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 首页无线卡片里二维码和连接码的尺寸：各种宽高下都放得下，不出现负数。（4.4 fork：从上游原样移植，覆盖 QrPinLayout 的 View 版重抄。） */
class QrPinLayoutTest {

    @Test
    fun wideCardKeepsBothQrAndPin() {
        // 横屏车机两栏的左卡片（约 686dp × 386dp）。
        val size = QrPinLayout.plan(width = 686f, height = 386f, digits = 6, compact = false)
        assertTrue(size.qr in QrPinLayout.MIN_QR..QrPinLayout.MAX_QR, "二维码 ${size.qr}")
        assertTrue(size.pinBox in QrPinLayout.MIN_PIN..QrPinLayout.PIN_BOX, "连接码方框 ${size.pinBox}")
        assertTrue(6 * (size.pinBox + 2 * size.pinGap) <= 686f + 0.01f, "连接码一行不超宽")
        assertTrue(size.qr + size.pinTop + size.pinBox + 2 * QrPinLayout.QR_MARGIN <= 386f + 0.01f, "竖向放得下")
    }

    @Test
    fun shortCardDropsPinBeforeShrinkingQrBelowFloor() {
        val size = QrPinLayout.plan(width = 600f, height = 120f, digits = 6, compact = true)
        assertEquals(0f, size.pinBox)
        assertEquals(120f - 2 * QrPinLayout.QR_MARGIN, size.qr)
    }

    @Test
    fun narrowCardHidesPin() {
        // 一行六个方框连最小尺寸都放不下。
        val size = QrPinLayout.plan(width = 200f, height = 600f, digits = 6, compact = false)
        assertEquals(0f, size.pinBox)
        assertTrue(size.qr <= 200f)
    }

    @Test
    fun unboundedHeightUsesWidthAndCap() {
        val size = QrPinLayout.plan(width = 1000f, height = Float.POSITIVE_INFINITY, digits = 6, compact = false)
        assertEquals(QrPinLayout.MAX_QR, size.qr)
        // 高度富余时连接码方框也封顶，不撑成巨块挤掉底部提示。
        assertEquals(QrPinLayout.MAX_PIN, size.pinBox)
        assertEquals(QrPinLayout.PIN_GAP, size.pinGap)
    }

    @Test
    fun degenerateSpaceNeverGoesNegative() {
        for ((w, h) in listOf(0f to 0f, -5f to 10f, 10f to -5f, 1f to 1f)) {
            val size = QrPinLayout.plan(w, h, digits = 6, compact = true)
            assertTrue(size.qr >= 1f && size.pinBox >= 0f && size.pinGap >= 0f, "$w x $h -> $size")
        }
    }

    @Test
    fun noPinGivesQrAllTheSpace() {
        val size = QrPinLayout.plan(width = 686f, height = 200f, digits = 0, compact = false)
        assertEquals(0f, size.pinBox)
        assertEquals(200f - 2 * QrPinLayout.QR_MARGIN, size.qr)
    }

    @Test
    fun pinRowShrinksToWidth() {
        val (box, gap) = QrPinLayout.pinRow(width = 300f, digits = 6, maxBox = QrPinLayout.PIN_BOX)
        assertTrue(6 * (box + 2 * gap) <= 300f + 0.01f)
        val (wideBox, wideGap) = QrPinLayout.pinRow(width = 2000f, digits = 6, maxBox = QrPinLayout.PIN_BOX)
        assertEquals(QrPinLayout.PIN_BOX, wideBox)
        assertEquals(QrPinLayout.PIN_GAP, wideGap)
    }
}
