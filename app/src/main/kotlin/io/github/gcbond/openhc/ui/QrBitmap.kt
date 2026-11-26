package io.github.gcbond.openhc.ui

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 5.0 无线恢复：配对二维码工具。
 * 把 PairingQrCode.build 生成的 hicar:// 连接串编码成黑白位图，
 * 供首页无线配对区用 ImageView 展示。zxing-core 是纯 Java 库，API 19 起可用，无版本门槛。
 */
internal object QrBitmap {

    /**
     * 编码一张二维码位图。
     *
     * @param content 二维码内容（hicar://car/connect?...&pin=6位码&...）
     * @param size 期望边长（px），调用方按卡片可用宽传值；位图边长即 size。
     * 安静区不画进位图（MARGIN=0），由界面白色底板内边距提供，避免白边随尺寸放大而码体不放大。
     * @return 内容为空、尺寸非法或编码失败（内容超限等）时返回 null，界面按无图收起。
     */
    fun encode(content: String, size: Int): Bitmap? {
        if (content.isEmpty() || size <= 0) return null
        // 纠错 M：手机扫码允许中等污损，上游默认档位。
        // MARGIN=0：zxing 默认四周加 4 模块安静区，尺寸越大白边越宽、码体占比越小；
        // 安静区改由白底板内边距承担，位图只画码体。
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 0,
        )
        val matrix: BitMatrix = try {
            QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        } catch (_: Exception) {
            return null
        }
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                pixels[row + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        // 黑白二维码用 RGB_565 无损且内存减半（老弱车机友好）。
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }
}
