package io.github.gcbond.openhc.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.gcbond.openhc.CarBrand
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.settings.BrandPreferences

/**
 * 「车辆品牌」设置段：一个「默认」格 + 19 个车标格。
 *
 * 选择立即写入 [BrandPreferences]；品牌图标（dataType 502）和车机名称覆盖在下一次连接开始时生效，
 * 不改变 OpenHC 自己界面的显示。
 */
internal class BrandSection(context: Context) : LinearLayout(context) {

    private val preferences = BrandPreferences(context)

    init {
        orientation = VERTICAL
        rebuild()
    }

    fun rebuild() {
        removeAllViews()
        val group = SettingsGroup(context, context.getString(R.string.settings_brand_group))
        group.addRowView(header())
        group.addRowView(grid())
        addView(
            group,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
    }

    /** 说明 + 回传提示 + 当前选择。 */
    private fun header(): LinearLayout =
        LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(context.dp(16f), context.dp(12f), context.dp(16f), 0)
            addView(rowDescription(context, context.getString(R.string.settings_brand_desc)))
            addView(
                rowNote(context, context.getString(R.string.settings_brand_note)),
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = context.dp(6f)
                },
            )
            val current = preferences.brand?.displayName ?: context.getString(R.string.settings_brand_default)
            addView(
                TextView(context).apply {
                    text = context.getString(R.string.settings_brand_selected, current)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(Palette.Accent)
                },
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = context.dp(8f)
                },
            )
        }

    /** 车标网格：每行 4 个，最后一行用空位补齐保持对齐。 */
    private fun grid(): LinearLayout {
        val cells = ArrayList<View>()
        cells += cell(
            context.getString(R.string.settings_brand_default),
            BitmapFactory.decodeResource(context.resources, R.raw.car_brand_icon),
            preferences.brand == null,
        ) { select(null) }
        CarBrand.entries.forEach { brand ->
            cells += cell(brand.displayName, brand.iconBitmap(context), preferences.brand == brand) {
                select(brand)
            }
        }
        val grid = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(context.dp(16f), context.dp(8f), context.dp(16f), context.dp(14f))
        }
        cells.chunked(COLUMNS).forEachIndexed { index, rowCells ->
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            rowCells.forEachIndexed { column, cellView ->
                val params = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                if (column > 0) params.marginStart = context.dp(6f)
                row.addView(cellView, params)
            }
            repeat(COLUMNS - rowCells.size) {
                row.addView(View(context), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            }
            grid.addView(
                row,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    if (index > 0) topMargin = context.dp(6f)
                },
            )
        }
        return grid
    }

    private fun cell(name: String, bitmap: Bitmap?, selected: Boolean, onClick: () -> Unit): View =
        LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(context.dp(8f), context.dp(8f), context.dp(8f), context.dp(6f))
            background = if (selected) {
                solidRound(Palette.AccSoft, context.dp(12f).toFloat(), context.dp(1f).toFloat(), Palette.AccLine2)
            } else {
                solidRound(Palette.Inset, context.dp(12f).toFloat(), context.dp(1f).toFloat(), Palette.Line)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageBitmap(bitmap)
                    contentDescription = name
                },
                LayoutParams(context.dp(52f), context.dp(52f)),
            )
            addView(
                TextView(context).apply {
                    text = name
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(if (selected) Palette.Accent else Palette.Muted)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = context.dp(4f)
                },
            )
        }

    private fun select(brand: CarBrand?) {
        preferences.brandId = brand?.id
        Toast.makeText(
            context,
            if (brand == null) {
                context.getString(R.string.settings_brand_default_applied)
            } else {
                context.getString(R.string.settings_brand_applied, brand.displayName)
            },
            Toast.LENGTH_SHORT,
        ).show()
        rebuild()
    }

    private companion object {
        const val COLUMNS = 4
    }
}
