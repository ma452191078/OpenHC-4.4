package io.github.gcbond.openhc.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.gcbond.openhc.BuildConfig
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.settings.AppSettings

/*
 * 设置页（4.4 fork：View 体系版，对照原型 settings 布局）。
 *
 * 宽屏：左 216dp 导航 + 右内容 pane；窄屏：导航变顶部横排 chips。
 * 低内存关键：只构建/挂载当前选中的 pane，切走就释放实例（不缓存跨段实例）。
 * 各段落（画面、媒体按键、连接方式、无线（SDK>=19）、启动、声音、诊断）都在 SettingsSections.kt 里。
 * 权限申请、跳系统设置页经经典 requestPermissions / startActivityForResult，
 * 结果由 MainActivity 路由回 [onPermissionResult] / [onActivityResult]，再切到对应段。
 */
internal class SettingsScreen(
    context: Context,
    private val onBack: () -> Unit,
) : LinearLayout(context), SectionHost {

    /** 左导航的九个段。 */
    private enum class Nav { PICTURE, BRAND, AUDIO, WIRELESS, DEVICES, BOOT, CONNECTION, UPDATE, DIAG }

    private val settings = AppSettings(context)
    private val host = ActivitySectionHost(context as Activity)
    private val wirelessAvailable = Build.VERSION.SDK_INT >= 19

    private var current = Nav.PICTURE

    // 只保留当前段的实例；切段时全部置 null 释放（懒加载，低内存）。
    private var picture: PictureGroup? = null
    private var brand: BrandSection? = null
    private var audio: AudioSection? = null
    private var wireless: WirelessSection? = null
    private var devices: DeviceManagerSection? = null
    private var boot: BootStartSection? = null
    private var usbAutoConnect: UsbAutoConnectGroup? = null
    private var update: UpdateSection? = null
    private var diagnostics: DiagnosticsSection? = null

    private lateinit var body: LinearLayout
    private lateinit var content: LinearLayout
    private val navButtons = LinkedHashMap<Nav, TextView>()
    private val navIcons = LinkedHashMap<Nav, ImageView>()

    init {
        orientation = VERTICAL
        setBackgroundColor(Palette.Background)
        val side = sidePadding()
        setPadding(side, context.dp(12f), side, context.dp(14f))
        addView(topBar(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        body = LinearLayout(context)
        addView(
            body,
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = context.dp(12f) },
        )
        rebuildSections()
    }

    override fun requestPermission(permission: String, requestCode: Int) {
        host.requestPermission(permission, requestCode)
    }

    override fun startActivityForResult(intent: Intent, requestCode: Int) {
        host.startActivityForResult(intent, requestCode)
    }

    /** 权限申请回来：构建并切到对应段（新构建会重查权限状态）。 */
    fun onPermissionResult(requestCode: Int) {
        when (requestCode) {
            REQ_SETTINGS_MIC -> showPane(Nav.AUDIO)
            REQ_BOOT_NOTIFICATIONS -> showPane(Nav.BOOT)
        }
    }

    /** 从系统设置页回来：切到启动段，新构建重查悬浮窗、通知权限。 */
    fun onActivityResult(requestCode: Int) {
        if (requestCode == REQ_SETTINGS_PAGE) {
            showPane(Nav.BOOT)
        }
    }

    /** 进设置前重建(由 MainActivity 在切屏时调用):权限、开机记录可能都变了；只重建当前段。 */
    fun rebuildSections() {
        body.removeAllViews()
        navButtons.clear()
        navIcons.clear()
        content = LinearLayout(context).apply { orientation = VERTICAL }
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // 内容宽够 720dp 才放得下左导航 + 正文；否则导航变顶部横排 chips。
        val wide = resources.displayMetrics.widthPixels - paddingStart - paddingEnd >= context.dp(720f)
        if (wide) {
            body.orientation = HORIZONTAL
            val nav = LinearLayout(context).apply { orientation = VERTICAL }
            addNavItems(nav)
            nav.addView(View(context), LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
            nav.addView(navFoot(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            body.addView(nav, LayoutParams(context.dp(216f), LayoutParams.MATCH_PARENT))
            body.addView(View(context), LayoutParams(context.dp(20f), LayoutParams.MATCH_PARENT))
            body.addView(scroll, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        } else {
            body.orientation = VERTICAL
            val chips = LinearLayout(context).apply { orientation = HORIZONTAL }
            addNavItems(chips)
            val chipScroll = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(chips, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            body.addView(chipScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            body.addView(
                scroll,
                LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = context.dp(10f) },
            )
        }
        showPane(current)
    }

    /** 构建并挂载选中段；其余段实例释放。 */
    private fun showPane(nav: Nav) {
        if (nav == Nav.WIRELESS && !wirelessAvailable) return
        current = nav
        content.removeAllViews()
        picture = null
        brand = null
        audio = null
        wireless = null
        devices = null
        boot = null
        usbAutoConnect = null
        update = null
        diagnostics = null
        when (nav) {
            Nav.PICTURE -> {
                val group = PictureGroup(context, settings)
                picture = group
                content.addView(group, sectionParams(0f))
                content.addView(mediaKeyGroup(context), sectionParams(14f))
            }
            Nav.BRAND -> {
                val section = BrandSection(context)
                brand = section
                content.addView(section, sectionParams(0f))
            }
            Nav.AUDIO -> {
                val section = AudioSection(context, this)
                audio = section
                content.addView(section, sectionParams(0f))
            }
            Nav.WIRELESS -> {
                val section = WirelessSection(context)
                wireless = section
                content.addView(section, sectionParams(0f))
            }
            Nav.DEVICES -> {
                val section = DeviceManagerSection(context)
                devices = section
                content.addView(section, sectionParams(0f))
            }
            Nav.BOOT -> {
                val section = BootStartSection(context, this)
                boot = section
                content.addView(section, sectionParams(0f))
            }
            Nav.CONNECTION -> {
                val group = UsbAutoConnectGroup(context)
                usbAutoConnect = group
                content.addView(group, sectionParams(0f))
            }
            Nav.UPDATE -> {
                val section = UpdateSection(context)
                update = section
                content.addView(section, sectionParams(0f))
            }
            Nav.DIAG -> {
                val section = DiagnosticsSection(context)
                diagnostics = section
                content.addView(section, sectionParams(0f))
            }
        }
        updateNavStyles()
    }

    /** 导航项：muted 图标 + 文案；选中 acc 字 + accSoft 底 + acc 边（样式在 updateNavStyles 里刷）。 */
    private fun addNavItems(container: LinearLayout) {
        val items = buildList {
            add(Triple(Nav.PICTURE, context.getString(R.string.settings_group_picture), "icons/nav_picture.svg"))
            add(Triple(Nav.BRAND, context.getString(R.string.settings_nav_brand), "icons/nav_brand.svg"))
            add(Triple(Nav.AUDIO, context.getString(R.string.settings_nav_audio), "icons/nav_audio.svg"))
            if (wirelessAvailable) {
                add(Triple(Nav.WIRELESS, context.getString(R.string.settings_nav_wireless), "icons/mode_wireless.svg"))
                add(Triple(Nav.DEVICES, context.getString(R.string.settings_nav_devices), "icons/link.svg"))
            }
            add(Triple(Nav.BOOT, context.getString(R.string.settings_boot_title), "icons/nav_boot.svg"))
            // 「连接方式」暂时隐藏，不在菜单里露出；对应段落代码保留，需要时把这一项加回来即可。
            add(Triple(Nav.UPDATE, context.getString(R.string.settings_nav_update), "icons/nav_update.svg"))
            add(Triple(Nav.DIAG, context.getString(R.string.settings_group_diagnostics), "icons/nav_diag.svg"))
        }
        items.forEachIndexed { index, (nav, label, iconPath) ->
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // 车机触屏：菜单项做高一点、字大一点，手指更好点。
                minimumHeight = context.dp(52f)
                val px = context.dp(12f)
                val py = context.dp(14f)
                setPadding(px, py, px, py)
                isClickable = true
                isFocusable = true
                setOnClickListener { showPane(nav) }
            }
            val icon = ImageView(context).apply {
                setImageDrawable(IconCache.get(context, iconPath))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            row.addView(icon, LayoutParams(context.dp(22f), context.dp(22f)))
            val text = TextView(context).apply {
                this.text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                maxLines = 1
            }
            row.addView(
                text,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(12f) },
            )
            navButtons[nav] = text
            navIcons[nav] = icon
            container.addView(
                row,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    if (index > 0) topMargin = context.dp(4f)
                },
            )
        }
    }

    /** 选中态：acc 字 + accSoft 底 + acc 边；未选 muted + 透明底。一次性换背景，无动画。 */
    private fun updateNavStyles() {
        navButtons.forEach { (nav, text) ->
            val on = nav == current
            text.setTextColor(if (on) Palette.Accent else Palette.Muted)
            text.setTypeface(text.typeface, if (on) Typeface.BOLD else Typeface.NORMAL)
            navIcons[nav]?.setColorFilter(
                PorterDuffColorFilter(if (on) Palette.Accent else Palette.Muted, PorterDuff.Mode.SRC_IN),
            )
            (text.parent as? View)?.background = if (on) {
                solidRound(Palette.AccSoft, context.dp(10f).toFloat(), context.dp(1f).toFloat(), Palette.AccLine2)
            } else {
                solidRound(Color.TRANSPARENT, context.dp(10f).toFloat())
            }
        }
    }

    /** nav-foot：等宽 dim，版本 + GPL-3.0 + 不连任何服务器。 */
    private fun navFoot(): TextView =
        TextView(context).apply {
            text = context.getString(R.string.home2_footer_version, BuildConfig.VERSION_NAME) +
                "\n" + context.getString(R.string.settings_nav_foot)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Palette.Dim)
            typeface = Typeface.MONOSPACE
            setPadding(context.dp(12f), context.dp(12f), context.dp(12f), 0)
        }

    /** 窄屏左右 16dp，宽屏 48dp（对应原版的 PageSide）。 */
    private fun sidePadding(): Int =
        if (resources.displayMetrics.widthPixels >= context.dp(Dimens.WIDE_PAGE_MIN)) context.dp(Dimens.PAGE_SIDE)
        else context.dp(Dimens.PAGE_SIDE_NARROW)

    private fun topBar(): LinearLayout =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                topBarButton(context, context.getString(R.string.action_back)) { onBack() },
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
            )
            addView(
                TextView(context).apply {
                    text = context.getString(R.string.settings_title)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    setTextColor(Palette.Text)
                    setTypeface(typeface, Typeface.BOLD)
                },
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(14f) },
            )
            addView(
                TextView(context).apply {
                    text = context.getString(R.string.settings_apply_hint)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(Palette.Dim)
                    gravity = Gravity.END
                },
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dp(14f) },
            )
        }

    private fun sectionParams(topDp: Float): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(topDp)
        }
}