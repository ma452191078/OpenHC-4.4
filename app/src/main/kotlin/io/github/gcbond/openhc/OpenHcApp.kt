package io.github.gcbond.openhc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.multidex.MultiDexApplication
import io.github.gcbond.openhc.connection.ConnectionController
import io.github.gcbond.openhc.connection.Phase
import io.github.gcbond.openhc.diagnostics.LogRecorder
import io.github.gcbond.openhc.dim.BtMediaWatcher
import io.github.gcbond.openhc.dim.DimMediaClient
import io.github.gcbond.openhc.startup.BootStart
import io.github.gcbond.openhc.startup.ProjectionSurfacer
import io.github.gcbond.openhc.update.UpdateManager
import io.github.gcbond.openhc.wireless.WirelessController

/** 4.4 fork：minSdk 19，API 21 以下要靠 MultiDexApplication 拆 dex（bcprov 等依赖超 64k 方法）。 */
class OpenHcApp : MultiDexApplication() {

    /** 有线投屏的总调度，进程内唯一。 */
    val connection: ConnectionController by lazy { ConnectionController(this) }

    /** 仪表盘多媒体信息投放（ECARX 车机才有对应的系统服务，没有时绑定失败即空转）。 */
    val dimMedia by lazy { DimMediaClient(this) }

    /** 车机蓝牙 AVRCP 的当前播放信息，转到 [dimMedia] 显示到仪表盘。 */
    val btMedia by lazy { BtMediaWatcher(this, dimMedia) }

    /** 版本更新：启动时检查一次 GitHub Releases，设置里也能手动检查。 */
    val updater by lazy { UpdateManager(this) }

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
        // 仪表盘多媒体通道：连上后等上游喂媒体信息（蓝牙 AVRCP 那一路随应用启动就开始监听）。
        dimMedia.start()
        btMedia.start()
        // 调试探针（仅 debug 包）：adb 广播触发 HiCar 元数据请求，用来确认 514/515 走哪个 module/type。
        if (BuildConfig.DEBUG) startAaProbe()
        registerActivityLifecycleCallbacks(uiVisibility)
        // 5.0 无线恢复：有线启动时让无线退出扫码等待（有无线在等手机时才有效），有线结束后接着拨等着的手机。
        // 用 wirelessIfCreated 而不是 wireless：4.4 上无线从未创建（恒 null 空转），也不因有线事件强行拉起无线。
        connection.addHostListener {
            wirelessIfCreated?.onWiredChanged()
            surfacer.onStateChanged()
        }
        // 4.4 fork：无线只走扫码配对 + 车机主动拨号，不用 BLE 广播。
    }

    /**
     * 调试探针（仅 debug 包）：adb 广播触发一次 HiCar 元数据请求，用来确认 514/515 在设备控制连接上
     * 用哪个 module/type 承载。回包会在会话诊断里以
     * `UnhandledDeviceData(module=..., type=..., ...：<JSON 预览>)` 出现。
     *
     * 用法：
     *   adb shell am broadcast -a io.github.gcbond.openhc.DEBUG_AA
     * 可选参数：
     *   --es module 2       只发指定 module（默认依次试 2、3）
     *   --es type 514       数据类型（默认 514 元数据能力）
     *   --es payload '{...}' 请求正文（默认 {"RequestAAData":7}）
     *   --ez nul true       正文末尾补一个 0 字节（SDK 的 HiCarSendData 传的是 strlen+1）
     */
    private fun startAaProbe() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val payload = intent.getStringExtra("payload") ?: "{\"RequestAAData\":7}"
                val nul = intent.getBooleanExtra("nul", false)
                val type = intent.getStringExtra("type")?.toIntOrNull() ?: AA_METADATA_REQUEST
                val modules = intent.getStringExtra("module")?.toLongOrNull()?.let { listOf(it) } ?: AA_PROBE_MODULES
                val bytes = if (nul) {
                    payload.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
                } else {
                    payload.toByteArray(Charsets.UTF_8)
                }
                for (module in modules) {
                    Log.i(AA_TAG, "元数据探针：module=$module type=$type nul=$nul payload=$payload")
                    connection.sendDeviceData(module, type, bytes)
                    wirelessIfCreated?.sendDeviceData(module, type, bytes)
                }
            }
        }
        ContextCompat.registerReceiver(this, receiver, IntentFilter(AA_PROBE_ACTION), ContextCompat.RECEIVER_EXPORTED)
        Log.i(AA_TAG, "元数据探针已就绪：adb shell am broadcast -a $AA_PROBE_ACTION")
    }

    private companion object {
        /** 有线还没接上手机的阶段：准备中、等插线。 */
        val WIRED_WAITING_PHASES = setOf(Phase.PREPARING, Phase.WAITING_FOR_PHONE)

        /** HiCar 元数据探针：广播 action、默认请求类型（514 = 元数据能力）和候选 module。 */
        const val AA_PROBE_ACTION = "io.github.gcbond.openhc.DEBUG_AA"
        const val AA_TAG = "OpenHC-AA"
        const val AA_METADATA_REQUEST = 514
        val AA_PROBE_MODULES = listOf(2L, 3L)
    }
}
