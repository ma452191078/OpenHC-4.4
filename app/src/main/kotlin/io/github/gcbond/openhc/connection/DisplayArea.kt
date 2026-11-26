package io.github.gcbond.openhc.connection

import android.annotation.SuppressLint
import android.content.Context

/**
 * 投屏画面区域：「全屏显示」开时是整块屏幕；关时保留系统底部导航栏，画面让出它的位置（比如车机装了常驻
 * 悬浮底栏，全屏时会被它挡住）。声明给手机的屏幕尺寸必须和画面区域一致，手机才按它编码、不拉伸。
 */
internal object DisplayArea {
    private const val PREFS = "openhc-display"
    private const val KEY_WIDTH = "content_width"
    private const val KEY_HEIGHT = "content_height"

    /**
     * 这次连接开始时用的全屏设置。投屏页按它藏栏，保证画面区域和这次声明给手机的尺寸一致：
     * 连接中途改设置不影响本次（手机中途不理会尺寸变化）。
     */
    @Volatile
    var activeFullScreen: Boolean = true

    /** 实测的底部导航栏高度（像素，不管它此刻显不显示）；0 表示还没测到。由界面拿到窗口插入量时记下。 */
    @Volatile
    var measuredNavigationBarHeight: Int = 0

    /** 投屏视图上一次的真实尺寸（像素）；0 表示还没投屏过。见 [recordContentSize]。 */
    @Volatile
    private var contentWidth: Int = 0

    @Volatile
    private var contentHeight: Int = 0

    @Volatile
    private var loaded: Boolean = false

    /**
     * 声明用的屏幕参数。
     *
     * 优先用投屏视图实测到的尺寸：车机的底部栏是它自己的界面，系统报的导航栏高度（这里 48px）和真实占用
     * （70px）对不上，只按系统值扣会差 22px，画面就左右各留一点黑边。投屏过一次以后这里就是准确值
     * （存进 SharedPreferences，重启后仍然有效）。还没投屏过时退回「屏幕高度减导航栏」。
     */
    fun screenFor(context: Context): ScreenSpec {
        val screen = DeviceInfo.screen(context)
        loadOnce(context)
        val width = contentWidth
        val height = contentHeight
        if (width in 1..screen.width && height in 1..screen.height) {
            return screen.copy(width = width and 1.inv(), height = height and 1.inv())
        }
        val bar = navigationBarHeight(context)
        if (bar <= 0) return screen
        return screen.copy(height = (screen.height - bar).coerceAtLeast(1) and 1.inv())
    }

    /** 记下投屏视图的真实尺寸（投屏页布局完成时调用）。尺寸变了才写盘。 */
    fun recordContentSize(context: Context, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (width == contentWidth && height == contentHeight) return
        contentWidth = width
        contentHeight = height
        try {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_WIDTH, width).putInt(KEY_HEIGHT, height).apply()
        } catch (e: RuntimeException) {
            // 存不下就只在本次进程里有效。
        }
    }

    private fun loadOnce(context: Context) {
        if (loaded) return
        loaded = true
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            contentWidth = prefs.getInt(KEY_WIDTH, 0)
            contentHeight = prefs.getInt(KEY_HEIGHT, 0)
        } catch (e: RuntimeException) {
            // 读不到就按没投屏过处理。
        }
    }

    /** 底部导航栏高度：优先用实测值，没有就用系统资源里的默认高度，再没有就是 0。 */
    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    fun navigationBarHeight(context: Context): Int {
        measuredNavigationBarHeight.takeIf { it > 0 }?.let { return it }
        val id = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id != 0) context.resources.getDimensionPixelSize(id) else 0
    }
}
