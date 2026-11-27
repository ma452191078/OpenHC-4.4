package io.github.gcbond.openhc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.IOException

/**
 * 车标 / 车辆品牌目录。
 *
 * 图标放在 `assets/brands/`（原样 PNG，不经过 aapt 压缩）：既用来在首页、设置页显示，
 * 也作为 HiCar 品牌图标（dataType 502）原样回传给手机。
 *
 * [deviceName] 为空表示不覆盖 `openhc-identity.json` 里的车机名称（领克图标沿用原名称）。
 */
internal enum class CarBrand(
    /** 存进 SharedPreferences 的稳定标识，不能改。 */
    val id: String,
    /** 设置页、首页显示的名称。 */
    val displayName: String,
    /** 选择后覆盖 `host.deviceName`；null 表示沿用身份文件里的名称。 */
    val deviceName: String?,
    /** assets 里的图标路径。 */
    private val assetPath: String,
) {
    LYNKCO_GRILLE("lynkco_grille", "领克 · 格栅", null, "brands/lynkco_grille.png"),
    LYNKCO_GRILLE_WHITE("lynkco_grille_white", "领克 · 格栅白", null, "brands/lynkco_grille_white.png"),
    LYNKCO_GRILLE_PLAIN("lynkco_grille_plain", "领克 · 格栅素", null, "brands/lynkco_grille_plain.png"),
    LYNKCO_GRILLE_TEAL("lynkco_grille_teal", "领克 · 格栅青", null, "brands/lynkco_grille_teal.png"),
    LYNKCO_GRILLE_BLUE("lynkco_grille_blue", "领克 · 格栅蓝", null, "brands/lynkco_grille_blue.png"),
    LYNKCO_WORD("lynkco_word", "领克 · 文字标", null, "brands/lynkco_word.png"),
    LYNKCO_WORD_TRANSPARENT("lynkco_word_transparent", "领克 · 文字透", null, "brands/lynkco_word_transparent.png"),
    LYNKCO_GRADIENT("lynkco_gradient", "领克 · 渐变标", null, "brands/lynkco_gradient.png"),
    LYNKCO_BLACK("lynkco_black", "领克 · 黑标", null, "brands/lynkco_black.png"),
    LYNKCO_BLACK_WORD("lynkco_black_word", "领克 · 黑字标", null, "brands/lynkco_black_word.png"),
    LYNKCO_BLACK_STACK("lynkco_black_stack", "领克 · 黑标竖", null, "brands/lynkco_black_stack.png"),
    GEELY("geely", "吉利", "Geely", "brands/geely.png"),
    AUDI("audi", "奥迪", "Audi", "brands/audi.png"),
    BMW("bmw", "宝马", "BMW", "brands/bmw.png"),
    MERCEDES("mercedes", "奔驰", "Mercedes-Benz", "brands/mercedes.png"),
    PORSCHE("porsche", "保时捷", "Porsche", "brands/porsche.png"),
    XIAOMI("xiaomi", "小米", "Xiaomi", "brands/xiaomi.png"),
    ZEEKR("zeekr", "极氪", "Zeekr", "brands/zeekr.png"),
    MEDIA("media", "媒体图标", null, "brands/media.png"),
    ;

    /** 解成界面用的小位图；读不到时返回 null。 */
    fun iconBitmap(context: Context): Bitmap? = try {
        context.assets.open(assetPath).use { BitmapFactory.decodeStream(it) }
    } catch (e: IOException) {
        Log.w(TAG, "读取车标失败：$id：${e.javaClass.simpleName}：${e.message}")
        null
    }

    /** 原样 PNG 字节，用于 dataType 502 回传。 */
    fun iconBytes(context: Context): ByteArray? = try {
        context.assets.open(assetPath).use { it.readBytes() }
    } catch (e: IOException) {
        Log.w(TAG, "读取车标字节失败：$id：${e.javaClass.simpleName}：${e.message}")
        null
    }

    companion object {
        private const val TAG = "OpenHC-CarBrand"

        /** 按存下的 id 找回车标；未知或空返回 null（表示「默认」）。 */
        fun parse(id: String?): CarBrand? = entries.firstOrNull { it.id == id }
    }
}
