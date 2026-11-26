package io.github.gcbond.openhc

import androidx.multidex.MultiDexApplication
import io.github.gcbond.openhc.connection.ConnectionController
import io.github.gcbond.openhc.connection.Phase
import io.github.gcbond.openhc.diagnostics.LogRecorder
import io.github.gcbond.openhc.dim.DimMediaClient
import io.github.gcbond.openhc.startup.BootStart
import io.github.gcbond.openhc.startup.ProjectionSurfacer
import io.github.gcbond.openhc.wireless.LePairingNames
import io.github.gcbond.openhc.wireless.WirelessController

/** 4.4 fork：minSdk 19，API 21 以下要靠 MultiDexApplication 拆 dex（bcprov 等依赖超 64k 方法）。 */
class OpenHcApp : MultiDexApplication() {

    /** 有线投屏的总调度，进程内唯一。 */
    val connection: ConnectionController by lazy { ConnectionController(this) }

    /** 仪表盘多媒体信息投放（ECARX 车机才有对应的系统服务，没有时绑定失败即空转）。 */
    val dimMedia by lazy { DimMediaClient(this) }

    private val wirelessLazy = lazy {
        WirelessController(
            this,
            wiredActive = { connection.state.value.active },
            // 有线在准备或等插线、车机上又没插着手机：只是在等插线，认识的手机蓝牙连上时让给无线。
            wiredWaitingForPhone = {
                connection.state.value.phase in WIRED_WAITING_PHASES && !BootStart.phonePlugged(this)
            },
            stopWiredWaiting = { connection.stop() },
            resumeWiredWaiting = { connection.start() },
        ).also { controller ->
            controller.addHostListener { surfacer.onStateChanged() }
        }
    }

    /** 无线投屏的总调度，进程内唯一；和有线互斥（有线在进行时不开始）。 */
    val wireless: WirelessController by wirelessLazy

    /** 无线调度已经建了才返回它：只想看一眼状态时不为此去建。 */
    internal val wirelessIfCreated: WirelessController? get() = if (wirelessLazy.isInitialized()) wireless else null

    /** 本应用有没有界面在前台。 */
    internal val uiVisibility = UiVisibility()

    /** 开机静默连接：手机连上时把界面带到前台（静默关着时什么都不做）。 */
    private val surfacer by lazy { ProjectionSurfacer(this) }

    override fun onCreate() {
        super.onCreate()
        // 一启动就开始记自己的日志（「设置 → 诊断 → 导出诊断日志」用），越早越能把启动过程记全。
        LogRecorder.start(this)
        // 仪表盘多媒体通道：连上后等上游喂媒体信息（手机端元数据接入前先空转）。
        dimMedia.start()
        registerActivityLifecycleCallbacks(uiVisibility)
        // 5.0 无线恢复：有线启动时让无线退出扫码等待（有无线在等手机时才有效），有线结束后接着拨等着的手机。
        // 用 wirelessIfCreated 而不是 wireless：4.4 上无线从未创建（恒 null 空转），也不因有线事件强行拉起无线。
        connection.addHostListener {
            wirelessIfCreated?.onWiredChanged()
            surfacer.onStateChanged()
        }
        // 4.4 fork：「补全手机蓝牙配对」是无线功能，安卓 5.0 以下（4.4/4.4W）不运行（免得在 4.4 上碰无线代码路径）。
        // 5.0 起放行：它只做蓝牙适配器 setName，21 上无害，而 BLE 配对要用它恢复的配对名。
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            LePairingNames.restoreIfNeeded(this, "应用启动")
        }
    }

    private companion object {
        /** 有线还没接上手机的阶段：准备中、等插线。 */
        val WIRED_WAITING_PHASES = setOf(Phase.PREPARING, Phase.WAITING_FOR_PHONE)
    }
}
