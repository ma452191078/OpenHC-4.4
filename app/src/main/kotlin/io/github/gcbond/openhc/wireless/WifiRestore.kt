package io.github.gcbond.openhc.wireless

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import androidx.core.content.ContextCompat
import io.github.gcbond.openhc.platform.wifi.LocalHotspotOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 「退出后恢复车机 WiFi」每次复查时要做的事。 */
enum class WifiRestoreAction {
    /** 不用管：开关关着、WiFi 不是被热点顶掉的、已经回来了，或者又有连接在用热点。 */
    NONE,

    /** 还早：热点拆掉是异步的，系统有时会自己把 WiFi 开回来，下一次复查再看。 */
    CHECK_LATER,

    /** 应用自己打开 WiFi（安卓 9 及以下可以）。 */
    ENABLE_WIFI,

    /** 应用打不开 WiFi（安卓 10 起只有系统和用户能开关 WiFi）：提示车主去系统里打开。 */
    ASK_USER,
}

/**
 * 「退出后恢复车机 WiFi」的判定。纯逻辑，不引用安卓类，由 JVM 单元测试覆盖。
 *
 * 一次无线连接结束（用户停止、或连接自己断了）、热点关掉以后，在 [CHECK_DELAYS_MILLIS] 这几个时刻复查车机 WiFi：
 * - 开关关着、或者开热点时车机 WiFi 本来就是关的（不是被我们顶掉的）：不管；
 * - 又有连接在用热点（例如马上开始了下一次连接）：这次不管，记号留着，等那次连接结束再说；
 * - WiFi 已经回来了（系统自己开回来的）：不管；
 * - 安卓 9 及以下：用户点了停止就马上打开，连接自己结束的等到最后一次复查还没回来再打开
 *   （自动回连会很快开始下一次等待，不在两次连接之间来回开关 WiFi）；打开后到最后一次复查前还会再看，被顶回去就再开；
 * - 安卓 10 起：应用开关不了 WiFi，等到最后一次复查还没回来，就提示车主去系统里打开。
 */
object WifiRestorePolicy {
    /** 连接结束后第几毫秒复查车机 WiFi。 */
    val CHECK_DELAYS_MILLIS = longArrayOf(2_000, 5_000, 9_000)

    /** 安卓 9（API 28）及以下应用能直接开关 WiFi；安卓 10 起不行（目标版本高于 9 的应用调用无效）。 */
    const val APP_TOGGLE_MAX_SDK = 28

    class Input(
        val settingEnabled: Boolean,
        /** 有过一次开热点时车机 WiFi 是开着的：WiFi 是被我们的热点顶掉的。 */
        val wifiTakenDown: Boolean,
        /** 又有连接在用（或在等）热点。 */
        val hotspotBusy: Boolean,
        val wifiEnabledNow: Boolean,
        val sdkInt: Int,
        /** 用户点了停止（相当于「退出」）。 */
        val userStopped: Boolean,
        /** 这是最后一次复查。 */
        val lastCheck: Boolean,
    )

    /** [done]：这件事了结了，「WiFi 被顶掉」的记号可以清掉。 */
    data class Decision(val action: WifiRestoreAction, val done: Boolean)

    fun decide(input: Input): Decision = when {
        !input.settingEnabled -> Decision(WifiRestoreAction.NONE, done = true)
        !input.wifiTakenDown -> Decision(WifiRestoreAction.NONE, done = true)
        input.hotspotBusy -> Decision(if (input.lastCheck) WifiRestoreAction.NONE else WifiRestoreAction.CHECK_LATER, done = false)
        input.wifiEnabledNow -> Decision(WifiRestoreAction.NONE, done = true)
        input.sdkInt <= APP_TOGGLE_MAX_SDK -> if (input.userStopped || input.lastCheck) {
            Decision(WifiRestoreAction.ENABLE_WIFI, done = input.lastCheck)
        } else {
            Decision(WifiRestoreAction.CHECK_LATER, done = false)
        }
        input.lastCheck -> Decision(WifiRestoreAction.ASK_USER, done = true)
        else -> Decision(WifiRestoreAction.CHECK_LATER, done = false)
    }
}

/**
 * 「退出后恢复车机 WiFi」的执行（规则见 [WifiRestorePolicy]）。无线连接结束时 [onConnectionOver] 排几次复查；
 * 又要开热点时（[onHotspotRequested]）取消复查、收起提示。要车主自己打开 WiFi 时 [needsUser] 为 true，
 * 无线连接页据此显示一张卡片；车机 WiFi 打开后自动收起。
 *
 * 线程：复查在 [handler]（无线调度线程）上；[onHotspotRequested]、[dismiss] 可以在任意线程调用。
 */
internal class WifiRestorer(
    context: Context,
    private val handler: Handler,
    private val settingEnabled: () -> Boolean,
    private val log: (String) -> Unit,
) {
    private val app: Context = context.applicationContext ?: context
    private val mutableNeedsUser = MutableStateFlow(false)

    /** 要车主去系统里打开车机 WiFi（安卓 10 起）。 */
    val needsUser: StateFlow<Boolean> = mutableNeedsUser.asStateFlow()

    // 以下只在调度线程上访问。
    private val checks = ArrayList<Runnable>()
    private var receiverRegistered = false

    private val wifiState = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
            if (state == WifiManager.WIFI_STATE_ENABLED || state == WifiManager.WIFI_STATE_ENABLING) {
                handler.post {
                    if (mutableNeedsUser.value) log("恢复车机 WiFi：WiFi 已经打开，收起提示")
                    hideCard()
                }
            }
        }
    }

    /** 一次无线连接结束了（热点已经关掉或正在关）。[userStopped]：用户点了停止。在调度线程上调用。 */
    fun onConnectionOver(userStopped: Boolean) {
        cancelChecks()
        // 本进程没开过热点就什么都不用做（也不必为此起热点那边的线程）。
        val hotspots = LocalHotspotOwner.existing() ?: return
        if (!hotspots.stationWasOnBeforeHotspot) return
        if (!settingEnabled()) {
            hotspots.clearStationNote()
            return
        }
        val delays = WifiRestorePolicy.CHECK_DELAYS_MILLIS
        for ((index, delay) in delays.withIndex()) {
            val check = Runnable { check(userStopped, lastCheck = index == delays.lastIndex) }
            checks += check
            handler.postDelayed(check, delay)
        }
        log("恢复车机 WiFi：连接结束（${if (userStopped) "用户停止" else "连接自己结束"}），${delays.joinToString("/") { "${it / 1000}" }} 秒后复查车机 WiFi")
    }

    /** 又要开热点了：之前排的复查和提示都作废（WiFi 马上又会被热点顶掉）。任意线程。 */
    fun onHotspotRequested() {
        handler.post {
            if (checks.isNotEmpty()) log("恢复车机 WiFi：又要开热点了，取消复查")
            cancelChecks()
            hideCard()
        }
    }

    /** 车主点了「知道了」。任意线程。 */
    fun dismiss() {
        handler.post { hideCard() }
    }

    private fun check(userStopped: Boolean, lastCheck: Boolean) {
        val hotspots = LocalHotspotOwner.existing() ?: return
        val wifiOn = wifiEnabled()
        val decision = WifiRestorePolicy.decide(
            WifiRestorePolicy.Input(
                settingEnabled = settingEnabled(),
                wifiTakenDown = hotspots.stationWasOnBeforeHotspot,
                hotspotBusy = hotspots.busy,
                wifiEnabledNow = wifiOn,
                sdkInt = Build.VERSION.SDK_INT,
                userStopped = userStopped,
                lastCheck = lastCheck,
            ),
        )
        if (decision.done) hotspots.clearStationNote()
        when (decision.action) {
            WifiRestoreAction.NONE -> {
                log("恢复车机 WiFi：不用处理（WiFi ${if (wifiOn) "开着" else "关着"}，热点${if (hotspots.busy) "又在用" else "没在用"}）")
                cancelChecks()
            }
            WifiRestoreAction.CHECK_LATER -> Unit
            WifiRestoreAction.ENABLE_WIFI -> {
                val ok = enableWifi()
                log("恢复车机 WiFi：车机 WiFi 还关着，请求打开 = $ok")
                if (!ok && lastCheck) showCard()
            }
            WifiRestoreAction.ASK_USER -> {
                log("恢复车机 WiFi：车机 WiFi 没有自己回来，这个系统版本应用打不开 WiFi，提示车主去系统里打开")
                showCard()
            }
        }
    }

    private fun showCard() {
        mutableNeedsUser.value = true
        if (receiverRegistered) return
        try {
            ContextCompat.registerReceiver(
                app, wifiState, IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        } catch (e: RuntimeException) {
            log("恢复车机 WiFi：注册不了 WiFi 开关的广播（${e.message}），提示要车主自己收起")
        }
    }

    private fun hideCard() {
        mutableNeedsUser.value = false
        if (!receiverRegistered) return
        receiverRegistered = false
        try {
            app.unregisterReceiver(wifiState)
        } catch (e: IllegalArgumentException) {
            // 已经注销过。
        }
    }

    private fun cancelChecks() {
        for (check in checks) handler.removeCallbacks(check)
        checks.clear()
    }

    /** 安卓 7.0 以前用界面 Context 取 WifiManager 会泄漏，统一从应用 Context 取。 */
    private fun wifiManager(): WifiManager? = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private fun wifiEnabled(): Boolean = try {
        wifiManager()?.isWifiEnabled == true
    } catch (e: RuntimeException) {
        false
    }

    /** 只在安卓 9 及以下有用（见 [WifiRestorePolicy.APP_TOGGLE_MAX_SDK]）。 */
    private fun enableWifi(): Boolean = try {
        @Suppress("DEPRECATION")
        wifiManager()?.setWifiEnabled(true) == true
    } catch (e: RuntimeException) {
        false
    }
}
