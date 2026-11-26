package io.github.gcbond.openhc

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 本应用有没有界面在前台：至少一个 Activity 处于 started（看得见）。应用对象创建时登记（[OpenHcApp]），
 * 系统的生命周期回调和 [Listener] 都在主线程上。
 *
 * 用处：开机静默连接时，手机连上了而界面不在前台才去把它带到前台；补配对时离开界面太久就自动收掉入口。
 */
internal class UiVisibility : Application.ActivityLifecycleCallbacks {

    fun interface Listener {
        fun onUiVisibilityChanged(visible: Boolean)
    }

    private var started = 0
    private val listeners = CopyOnWriteArrayList<Listener>()

    /** 现在有界面看得见。主线程上读。 */
    val visible: Boolean get() = started > 0

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    override fun onActivityStarted(activity: Activity) {
        started++
        if (started == 1) notify(true)
    }

    override fun onActivityStopped(activity: Activity) {
        if (started == 0) return
        started--
        if (started == 0) notify(false)
    }

    private fun notify(visible: Boolean) {
        for (listener in listeners) listener.onUiVisibilityChanged(visible)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
