package io.github.gcbond.openhc.projection

import android.view.View
import android.view.Window

/*
 * 系统栏（状态栏 / 导航栏）的隐藏与恢复（4.4 fork）。
 *
 * 原版用 androidx WindowCompat / WindowInsetsControllerCompat（安卓 5.0 起）；
 * 4.4 上改用 view.systemUiVisibility：IMMERSIVE_STICKY 恰好是 API 19 引入的，
 * 全屏藏状态栏 + 导航栏，用户划出时短暂显示后自动收回去（与原版沉浸行为一致）。
 * 再补一个经典的 FLAG_FULLSCREEN，兼容部分 4.4 车机的旧实现。
 */
internal object SystemBars {

    /**
     * 进入沉浸。fullScreen=false 时只藏状态栏、保留导航栏（与原版语义一致）。
     */
    fun hide(window: Window, view: View, fullScreen: Boolean) {
        var flags = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_FULLSCREEN
        if (fullScreen) {
            flags = flags or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        }
        view.systemUiVisibility = flags
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
    }

    /**
     * 恢复系统栏。
     */
    fun show(window: Window, view: View) {
        view.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
    }
}
