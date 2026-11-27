package io.github.gcbond.openhc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import io.github.gcbond.openhc.settings.BrandPreferences
import java.io.ByteArrayOutputStream

/**
 * 车机品牌图标（HiCar dataType 502）：手机连上后会用 `{"brandIconData":"PNG"}` 来要，车机把 PNG 原样回过去。
 *
 * 图标取用户在「设置 → 车辆品牌」里选中的车标（`assets/brands` 目录下的 96×96 PNG）；
 * 没选（「默认」）时返回 null，走原来的行为（不回传图标）。
 */
internal object BrandIcon {

    private const val TAG = "OpenHC-BrandIcon"

    /**
     * 单条设备记录的数据上限，与 core/dmsdp 的 `DeviceRecords.MAX_SEND_DATA`
     * （`ControlFrames.MAX_PAYLOAD(10240) - IV(16) - HEADER(11)`）保持一致。
     * 超限会让整次连接在 DmsdpConfig 校验处直接失败，所以这里再兜一层。
     */
    private const val MAX_ICON_BYTES = 10_240 - 16 - 11

    fun load(context: Context): ByteArray? {
        val appContext = context.applicationContext
        val brand = BrandPreferences(appContext).brand ?: return null
        val bytes = brand.iconBytes(appContext) ?: return null
        if (bytes.size <= MAX_ICON_BYTES) return bytes
        Log.w(TAG, "车标 ${brand.id} 原始 ${bytes.size} 字节超过上限，尝试压缩")
        return shrink(bytes)
    }

    /** 超限兜底：逐级缩小并重新编码 PNG，仍超限则放弃（保持不回报图标，不拖垮连接）。 */
    private fun shrink(bytes: ByteArray): ByteArray? {
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        var edge = maxOf(source.width, source.height)
        while (edge > 32) {
            edge = edge * 3 / 4
            val scale = edge.toFloat() / maxOf(source.width, source.height)
            val width = (source.width * scale).toInt().coerceAtLeast(1)
            val height = (source.height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(source, width, height, true)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            if (scaled !== source) scaled.recycle()
            if (out.size() <= MAX_ICON_BYTES) {
                source.recycle()
                return out.toByteArray()
            }
        }
        source.recycle()
        Log.w(TAG, "车标压缩后仍超过上限，本次不回传")
        return null
    }
}
