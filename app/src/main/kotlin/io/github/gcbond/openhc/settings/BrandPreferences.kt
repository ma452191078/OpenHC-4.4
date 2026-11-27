package io.github.gcbond.openhc.settings

import android.content.Context
import androidx.core.content.edit
import io.github.gcbond.openhc.CarBrand

/**
 * 车辆品牌设置（单独一个 SharedPreferences 文件）。
 *
 * `brandId` 为 null 表示「默认」：不覆盖车机名称，品牌图标仍用内置的 CarPlay 图标。
 * 选择车标后，下一次连接开始时生效（身份名称覆盖 + dataType 502 图标回传）。
 */
internal class BrandPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var brandId: String?
        get() = prefs.getString(KEY_BRAND, null)
        set(value) = prefs.edit {
            if (value == null) remove(KEY_BRAND) else putString(KEY_BRAND, value)
        }

    /** 当前选中的车标；「默认」或存了未知 id 时返回 null。 */
    val brand: CarBrand? get() = CarBrand.parse(brandId)

    private companion object {
        const val FILE = "openhc-brand"
        const val KEY_BRAND = "brand_id"
    }
}
