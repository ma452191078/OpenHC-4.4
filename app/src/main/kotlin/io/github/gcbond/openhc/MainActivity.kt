package io.github.gcbond.openhc

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import io.github.gcbond.openhc.audio.MicPermission
import io.github.gcbond.openhc.connection.ConnectionController
import io.github.gcbond.openhc.connection.ConnectionUiState
import io.github.gcbond.openhc.connection.DisplayArea
import io.github.gcbond.openhc.connection.Notice
import io.github.gcbond.openhc.connection.Phase
import io.github.gcbond.openhc.core.hisight.HiSightInput
import io.github.gcbond.openhc.IdentityLoader
import io.github.gcbond.openhc.input.MediaKeys
import io.github.gcbond.openhc.platform.media.VideoDecoder
import io.github.gcbond.openhc.platform.net.VpnConsent
import io.github.gcbond.openhc.settings.AppSettings
import io.github.gcbond.openhc.settings.AudioPreferences
import io.github.gcbond.openhc.settings.UpdatePreferences
import io.github.gcbond.openhc.settings.WirelessPreferences
import io.github.gcbond.openhc.startup.BootStart
import io.github.gcbond.openhc.startup.BootStartPolicy
import io.github.gcbond.openhc.ui.HomeActions
import io.github.gcbond.openhc.ui.HomeScreen
import io.github.gcbond.openhc.ui.Palette
import io.github.gcbond.openhc.ui.POST_NOTIFICATIONS
import io.github.gcbond.openhc.ui.ProjectionScreen
import io.github.gcbond.openhc.ui.REQ_BOOT_NOTIFICATIONS
import io.github.gcbond.openhc.ui.REQ_SETTINGS_MIC
import io.github.gcbond.openhc.ui.REQ_SETTINGS_PAGE
import io.github.gcbond.openhc.ui.SettingsScreen
import io.github.gcbond.openhc.ui.UpdateOverlay
import io.github.gcbond.openhc.update.UpdatePhase
import io.github.gcbond.openhc.update.UpdateUiState
import io.github.gcbond.openhc.wireless.WirelessController
import io.github.gcbond.openhc.wireless.WirelessPhase
import io.github.gcbond.openhc.wireless.WirelessUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/*
 * 单 Activity(4.4 fork)。
 *
 * 原版是 ComponentActivity + Compose(AppRoot 三屏组合)。安卓 4.4 上没有那些依赖,
 * 这里改回经典 android.app.Activity + 手写 View:三屏(HOME / SETTINGS / PROJECTION)
 * 各是一棵视图树,用根 FrameLayout 的 add/removeView 切换 —— 真实的挂载 / 卸载,
 * 投屏视图离开窗口时才会走 onDetachedFromWindow,系统栏才恢复。
 *
 * 权限链(点开始后)用经典 requestPermissions / startActivityForResult:
 * 通知权限(33+)与麦克风(23+)在 4.4 上安装时全授予,门控条件直接走不到;
 * VPN 授权用 VpnConsent.request + onActivityResult。
 * StateFlow 的消费放后台协程,collect 到值后 runOnUiThread 回界面线程
 * (依赖里只有 kotlinx-coroutines-core,没有安卓的 Main 调度器)。
 *
 * 5.0 无线恢复:无线控制器三个流照同样模式收(SDK>=21 才建/才碰);
 * 投屏屏按哪边在投屏选状态源,自动切屏计数有线/无线分开。
 */
class MainActivity : Activity() {

    /** 三屏。 */
    private enum class Screen { HOME, SETTINGS, PROJECTION }

    /** 启动方式:点开始、插线拉起、开机自动打开。 */
    private enum class StartKind { START, USB_ATTACHED, BOOT }

    private val controller: ConnectionController
        get() = (application as OpenHcApp).connection

    /** 5.0 无线恢复：无线控制器；4.4（SDK<21）上直接 null，不去触发 lazy 构建。 */
    private val wireless: WirelessController?
        get() = if (Build.VERSION.SDK_INT >= 19) (application as OpenHcApp).wireless else null

    private val settings by lazy { AppSettings(this) }

    /** 走启动链时还没派发给控制器的启动方式。 */
    private var pendingStart: StartKind? = null

    /** 车机身份(缺了不能连,首页有提示)。 */
    private val identity: IdentityLoader.Result by lazy { IdentityLoader.load(this) }

    private lateinit var root: FrameLayout
    /** 屏内容层：切屏只换这一层，更新浮层挂在 root 上不受影响。 */
    private lateinit var screenLayer: FrameLayout
    private lateinit var home: HomeScreen
    private lateinit var settingsScreen: SettingsScreen
    private lateinit var projectionScreen: ProjectionScreen

    /** 当前屏。 */
    private var screen = Screen.HOME

    /** 状态机:投屏开始时自动切到投屏屏;记下已切过的 attempt,避免同一轮重复强拉。 */
    private var autoShownAttempt = 0

    /** 5.0 无线恢复：无线自己的自动切屏计数（有线/无线 attempt 各自从 1 起，共用会撞号）。 */
    private var autoShownWirelessAttempt = 0

    /** 手机把投屏切到后台的状态（点了 HiCar 里的车辆品牌图标）：切回首页，等手机恢复再回投屏。 */
    private var wiredDisplayPaused = false
    private var wirelessDisplayPaused = false

    /** 最新状态与解码器(各 StateFlow 异步到,渲染时取本地的最新值)。 */
    private var state = ConnectionUiState()
    private var decoder: VideoDecoder? = null
    private var touchResets = 0

    /** 5.0 无线恢复：无线侧同样三份本地缓存。 */
    private var wirelessState = WirelessUiState()
    private var wirelessDecoder: VideoDecoder? = null
    private var wirelessTouchResets = 0

    /** 更新浮层（提示/进度/失败）；都属于界面状态，不进状态机。 */
    private var updateOverlay: UpdateOverlay? = null
    private var progressOverlay: UpdateOverlay? = null

    /** 已经弹过提示的版本，避免同一次检查反复弹。 */
    private var promptedUpdateVersion: String? = null

    private val updateListener: (UpdateUiState) -> Unit = { onUpdateState(it) }

    /** StateFlow 消费协程(onStart 起、onStop 停)。 */
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collectJobs: List<Job> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = FrameLayout(this).apply { setBackgroundColor(Palette.Background) }
        screenLayer = FrameLayout(this)
        root.addView(
            screenLayer,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        home = HomeScreen(
            this,
            identity,
            HomeActions(
                onStart = { requestStart(StartKind.START) },
                onStop = { controller.stop() },
                onRetryNow = { controller.retryNow() },
                onGrantVpn = { requestStart(StartKind.START) },
                onResetTrustStore = { controller.resetTrustStoreAndStart() },
                // 5.0 无线恢复：无线侧的开始/停止（4.4 上无线入口不显示，点不到这里）。
                onWirelessStart = { wireless?.start() },
                onWirelessReconnect = { wireless?.startReconnect() },
                onWirelessStop = { wireless?.stop() },
                onWiredStart = { requestStart(StartKind.START) },
                onModeChanged = { (application as OpenHcApp).wirelessIfCreated?.onWiredOnlyChanged() },
            ),
            onShowProjection = { showProjectionFromHome() },
            onOpenSettings = { showScreen(Screen.SETTINGS) },
        )
        settingsScreen = SettingsScreen(this) { closeSettings() }
        // 触摸按当前投屏的那一路分发：无线在投屏就发给无线，否则发给有线（与 renderProjection 的判断一致）。
        projectionScreen = ProjectionScreen(
            this,
            onInput = { input ->
                if (wirelessState.phase == WirelessPhase.PROJECTING) {
                    wireless?.sendInput(input)
                } else {
                    controller.sendInput(input)
                }
            },
        )

        setContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        showScreen(Screen.HOME)
        // 首页只有无线面板，直接刷一次（4.4 以下没有无线调度，renderWireless 也不会被调到）。
        home.renderWireless(wirelessState)

        if (savedInstanceState == null) {
            handleLaunch(intent)
            // 打开应用按保存的模式直接进入连接：无线扫码/等待，或有线等插线。
            startSelectedConnectionOnLaunch()
        }
        home.renderWired(state)

        // 更新：状态随时回主线程；第一次创建才发起自动检查（旋转重建不重复请求）。
        (application as OpenHcApp).updater.addListener(updateListener)
        if (savedInstanceState == null) {
            (application as OpenHcApp).updater.autoCheck()
        }
    }

    /** 冷启动按保存的模式进入连接；插线/开机已经发起的启动由 StartPolicy 去重。 */
    private fun startSelectedConnectionOnLaunch() {
        val preferences = WirelessPreferences(this)
        if (settings.wiredEnabled && preferences.wiredOnly) {
            Log.i(TAG, "打开应用：当前是有线模式，自动开始等插线")
            wireless?.onWiredOnlyChanged()
            if (pendingStart == null && !state.active) requestStart(StartKind.START)
        } else {
            startWirelessOnLaunch()
        }
    }

    /**
     * 打开应用就发起一次无线连接：之前连成功过手机就直接主动回连（不用再扫码），否则出二维码。
     * 「只用数据线连接」是有线时代的开关，有线已隐藏，残留值会让无线一直起不来，这里清掉；
     * 清掉后要重新同步一次「手机蓝牙连上」的系统广播入口，否则自动回连收不到触发。
     */
    private fun startWirelessOnLaunch() {
        val wirelessController = wireless ?: return
        val preferences = WirelessPreferences(this)
        // 有线关着时清掉残留的「只用数据线连接」，否则无线一直起不来；启用有线时尊重用户选的模式。
        if (!settings.wiredEnabled && preferences.wiredOnly) {
            preferences.wiredOnly = false
            wirelessController.onWiredOnlyChanged()
        }
        wirelessController.startOnLaunch()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunch(intent)
    }

    override fun onStart() {
        super.onStart()
        // 有线三个 StateFlow 各一个后台 collect,值到了回界面线程。onStop 取消,生命周期干净。
        val wiredJobs = listOf(
            uiScope.launch {
                controller.state.collect { value -> runOnUiThread { onState(value) } }
            },
            uiScope.launch {
                controller.decoder.collect { value -> runOnUiThread { onDecoder(value) } }
            },
            uiScope.launch {
                controller.touchResets.collect { value -> runOnUiThread { onTouchResets(value) } }
            },
        )
        // 5.0 无线恢复:无线同样三个流照抄这个模式;4.4 上 wireless 为 null,一概不收。
        val wirelessJobs = wireless?.let { wirelessController ->
            listOf(
                uiScope.launch {
                    wirelessController.state.collect { value -> runOnUiThread { onWirelessState(value) } }
                },
                uiScope.launch {
                    wirelessController.decoder.collect { value -> runOnUiThread { onWirelessDecoder(value) } }
                },
                uiScope.launch {
                    wirelessController.touchResets.collect { value -> runOnUiThread { onWirelessTouchResets(value) } }
                },
            )
        } ?: emptyList()
        collectJobs = wiredJobs + wirelessJobs
        // 回到前台（含从后台被用户重新打开）：有没有连成功过的手机、蓝牙连着就连回去。
        // 手机蓝牙先连上车机、再打开应用时系统不会再发 ACL 广播，靠这一步补上。
        wireless?.onAppForeground()
    }

    override fun onStop() {
        super.onStop()
        collectJobs.forEach { it.cancel() }
        collectJobs = emptyList()
    }

    override fun onDestroy() {
        (application as OpenHcApp).updater.removeListener(updateListener)
        super.onDestroy()
        uiScope.cancel()
    }

    override fun onResume() {
        super.onResume()
        // 记下导航栏的实测高度(给 DisplayArea 算投屏用)。原版用 ViewCompat.getRootWindowInsets
        // (安卓 5.0 起);4.4 上用真实高度与应用可用高度之差。
        DisplayArea.measuredNavigationBarHeight = measureNavigationBarHeight()
    }

    /** 4.4 fork:导航栏高度 = getRealMetrics 与 getMetrics 的高度差。 */
    private fun measureNavigationBarHeight(): Int {
        val display = windowManager.defaultDisplay ?: return 0
        val real = DisplayMetrics()
        display.getRealMetrics(real)
        val usable = DisplayMetrics()
        display.getMetrics(usable)
        return maxOf(0, real.heightPixels - usable.heightPixels)
    }

    /**
     * 把屏幕锁在当前朝向（投屏期间用）。当前是竖屏就锁竖屏，横屏就锁横屏，并保留正/反向。
     * 用 4.x 就有的固定值（PORTRAIT/LANDSCAPE 及其反向），不用 SCREEN_ORIENTATION_LOCKED 这类新常量。
     */
    private fun currentLockedOrientation(): Int {
        val rotation = windowManager.defaultDisplay?.rotation ?: android.view.Surface.ROTATION_0
        val portrait = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        val forward = rotation == android.view.Surface.ROTATION_0 || rotation == android.view.Surface.ROTATION_90
        return when {
            portrait && forward -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            portrait -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            forward -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        }
    }

    /** 切屏:根容器换孩子(真实卸载,投屏视图的系统栏恢复靠它)。 */
    private fun showScreen(next: Screen) {
        val previous = screen
        if (previous == Screen.PROJECTION && next != Screen.PROJECTION) {
            // 离开投屏屏:把解码器从画面上摘下来(对应原版 Compose 的 onRelease)。
            projectionScreen.detachDecoder()
            // 离开投屏屏:恢复系统默认方向，允许车机再次旋转。
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (next == Screen.PROJECTION && previous != Screen.PROJECTION) {
            // 进入投屏屏:把方向锁在当前朝向。声明给手机的画布尺寸是连接开始时按这个朝向算的，
            // 中途旋转会让画面比例和触控基准对不上（协议不支持中途重新协商尺寸）。
            requestedOrientation = currentLockedOrientation()
        }
        screenLayer.removeAllViews()
        when (next) {
            Screen.HOME -> {
                screenLayer.addView(
                    home,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                )
                // 投屏期间首页不在窗口里：回来时按最后一次状态重画一次，按钮等子视图才正常。
                home.refreshPanel()
            }
            Screen.SETTINGS -> {
                // 进设置前整组重建:通知权限、悬浮窗、开机记录可能都变了。
                settingsScreen.rebuildSections()
                screenLayer.addView(
                    settingsScreen,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                )
            }
            Screen.PROJECTION -> {
                screenLayer.addView(
                    projectionScreen,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                )
                renderProjection()
            }
        }
        screen = next
        // 投屏中不弹更新提示：回到首页时补上（同一版本只会弹一次）。
        if (next == Screen.HOME) showUpdatePromptIfNeeded((application as OpenHcApp).updater.state)
    }

    /**
     * 首页点「回到投屏画面」：手机之前点了车辆品牌图标切走的话，先请它把 HiCar 切回前台，
     * 否则手机会一直停在后台状态，再点品牌图标不会再发暂停通知。
     */
    private fun showProjectionFromHome() {
        if (wirelessDisplayPaused) wireless?.projectionForeground()
        if (wiredDisplayPaused) controller.projectionForeground()
        showScreen(Screen.PROJECTION)
    }

    /**
     * 手机把投屏切到后台（例如点了 HiCar 里的车辆品牌图标）时回首页；切回来时若还在投屏就重新显示投屏。
     * 只在首页和投屏屏之间切，用户在设置页时不强行拉走。返回新的暂停标记。
     */
    private fun applyDisplayPaused(paused: Boolean, projecting: Boolean, previous: Boolean): Boolean {
        if (paused == previous) return previous
        if (paused) {
            if (screen == Screen.PROJECTION) showScreen(Screen.HOME)
        } else if (projecting && screen == Screen.HOME) {
            showScreen(Screen.PROJECTION)
        }
        return paused
    }

    /** 设置页返回:回首页;无线恢复后,原版的 wireless.onSettingsClosed() 也调回来(没建过无线控制器就跳过)。 */
    private fun closeSettings() {
        (application as OpenHcApp).wirelessIfCreated?.onSettingsClosed()
        // 设置里可能关掉了「启用有线连接」：把「只用数据线连接」一起复位，否则无线会一直停在关闭状态。
        val preferences = WirelessPreferences(this)
        if (!settings.wiredEnabled) preferences.wiredOnly = false
        home.refreshConnectionModes()
        showScreen(Screen.HOME)
        // 设置里可能改了「自动连接手机」：顶栏状态胶囊跟着刷新（renderWireless 会先刷页脚/顶栏再做去重）。
        home.renderWireless(wirelessState)
        home.renderWired(state)
        // 「只用数据线连接」可能变了：让无线调度停下等待或恢复。
        (application as OpenHcApp).wirelessIfCreated?.onWiredOnlyChanged()
    }

    /** StateFlow 的落点(界面线程);无线恢复后共六个。 */
    private fun onState(value: ConnectionUiState) {
        // 自动切屏状态机(对照原 AppRoot):
        // 投屏开始 → 自动切到投屏屏(同一轮 attempt 只切一次);
        // 投屏屏上投屏结束 → 回首页(无线还在投就先不回);要输连接码时如果还在设置页 → 也回首页。
        when {
            value.phase == Phase.PROJECTING && autoShownAttempt != value.attempt -> {
                autoShownAttempt = value.attempt
                if (screen != Screen.PROJECTION) showScreen(Screen.PROJECTION)
            }
            screen == Screen.PROJECTION && value.phase != Phase.PROJECTING &&
                wirelessState.phase != WirelessPhase.PROJECTING -> showScreen(Screen.HOME)
            value.phase == Phase.PAIRING && screen == Screen.SETTINGS -> showScreen(Screen.HOME)
        }
        // 手机点了 HiCar 里的车辆品牌图标要回车机界面：暂停投屏回首页；手机重新拉起 HiCar 时再回投屏。
        wiredDisplayPaused = applyDisplayPaused(value.displayPaused, value.phase == Phase.PROJECTING, wiredDisplayPaused)
        state = value
        home.renderWired(value)
        if (screen == Screen.PROJECTION) {
            renderProjection()
        }
    }

    private fun onDecoder(value: VideoDecoder?) {
        decoder = value
        if (screen == Screen.PROJECTION) {
            renderProjection()
        }
    }

    private fun onTouchResets(value: Int) {
        touchResets = value
        if (screen == Screen.PROJECTION) {
            renderProjection()
        }
    }

    // ---------- 5.0 无线恢复:无线三个流的落点 ----------

    private fun onWirelessState(value: WirelessUiState) {
        // 与有线同一个状态机,切屏计数器分开(两边 attempt 各自从 1 起)。
        when {
            value.phase == WirelessPhase.PROJECTING && autoShownWirelessAttempt != value.attempt -> {
                autoShownWirelessAttempt = value.attempt
                if (screen != Screen.PROJECTION) showScreen(Screen.PROJECTION)
            }
            screen == Screen.PROJECTION && value.phase != WirelessPhase.PROJECTING &&
                state.phase != Phase.PROJECTING -> showScreen(Screen.HOME)
            value.phase == WirelessPhase.WAITING_FOR_SCAN && screen == Screen.SETTINGS -> showScreen(Screen.HOME)
        }
        // 与有线同一条规则：手机切走/切回 HiCar 投屏时，在首页和投屏屏之间跟着切换。
        wirelessDisplayPaused = applyDisplayPaused(
            value.projection.displayPaused,
            value.phase == WirelessPhase.PROJECTING,
            wirelessDisplayPaused,
        )
        wirelessState = value
        home.renderWireless(value)
        if (screen == Screen.PROJECTION) {
            renderProjection()
        }
    }

    private fun onWirelessDecoder(value: VideoDecoder?) {
        wirelessDecoder = value
        if (screen == Screen.PROJECTION) {
            renderProjection()
        }
    }

    private fun onWirelessTouchResets(value: Int) {
        wirelessTouchResets = value
        if (screen == Screen.PROJECTION) {
            renderProjection()
        }
    }

    /** 投屏屏重挂:哪边在投屏就喂哪边的状态/解码器,另一边的流照常收着不打扰。 */
    private fun renderProjection() {
        if (wirelessState.phase == WirelessPhase.PROJECTING) {
            projectionScreen.render(wirelessState.projection, wirelessDecoder, wirelessTouchResets)
        } else {
            projectionScreen.render(state, decoder, touchResets)
        }
    }

    /** 返回键:关更新浮层 → 设置页回首页、投屏页回首页;首页走默认行为(退出)。 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (updateOverlay != null && keyCode == KeyEvent.KEYCODE_BACK) {
            if (progressOverlay != null) (application as OpenHcApp).updater.cancelDownload()
            dismissUpdateOverlay()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && screen != Screen.HOME) {
            if (screen == Screen.SETTINGS) closeSettings() else showScreen(Screen.HOME)
            return true
        }
        return MediaKeys.onWindowKey(event) || super.onKeyDown(keyCode, event)
    }

    // ---------------------------------------------------------------- 更新

    /** 更新状态落点（UpdateManager 已在主线程回调）。 */
    private fun onUpdateState(value: UpdateUiState) {
        if (isFinishing) return
        when (value.phase) {
            UpdatePhase.AVAILABLE -> {
                dismissDownloadProgress()
                showUpdatePromptIfNeeded(value)
            }
            UpdatePhase.DOWNLOADING -> showDownloadProgress(value.progress)
            UpdatePhase.VERIFYING -> showDownloadProgress(100)
            UpdatePhase.INSTALLING -> {
                dismissDownloadProgress()
                Toast.makeText(this, R.string.update_install_started, Toast.LENGTH_SHORT).show()
            }
            UpdatePhase.FAILED -> showUpdateFailure(value)
            else -> dismissDownloadProgress()
        }
    }

    /** 有新版本：按设置决定要不要弹窗；同一个版本在一次运行里只弹一次；投屏时不打扰。 */
    private fun showUpdatePromptIfNeeded(value: UpdateUiState) {
        val release = value.release ?: return
        if (promptedUpdateVersion == release.version) return
        if (!UpdatePreferences(this).notifyDialog) return
        if (screen == Screen.PROJECTION) return
        promptedUpdateVersion = release.version
        showUpdateOverlay(
            UpdateOverlay.prompt(
                this,
                release,
                BuildConfig.VERSION_NAME,
                onUpdate = {
                    dismissUpdateOverlay()
                    (application as OpenHcApp).updater.downloadAndInstall()
                },
                onLater = { dismissUpdateOverlay() },
            ),
        )
    }

    /** 下载进度：浮层常驻，只刷百分比。 */
    private fun showDownloadProgress(percent: Int) {
        val existing = progressOverlay
        if (existing != null) {
            existing.setProgress(percent)
            return
        }
        val overlay = UpdateOverlay.progress(this, percent) {
            (application as OpenHcApp).updater.cancelDownload()
        }
        showUpdateOverlay(overlay)
        progressOverlay = overlay
    }

    /** 失败提示：自动检查失败不打扰（设置页能看到原因），下载/安装失败或缺授权才弹。 */
    private fun showUpdateFailure(value: UpdateUiState) {
        val messageRes = value.messageRes ?: return
        if (progressOverlay == null && !value.needsInstallPermission) return
        val detail = value.detail?.takeIf { it.isNotBlank() }
        val message = getString(messageRes) + if (detail != null) getString(R.string.update_detail, detail) else ""
        dismissDownloadProgress()
        val needsPermission = value.needsInstallPermission
        showUpdateOverlay(
            UpdateOverlay.notice(
                this,
                message,
                actionLabel = if (needsPermission) getString(R.string.update_open_install_permission) else null,
                onAction = if (needsPermission) {
                    {
                        (application as OpenHcApp).updater.openInstallPermissionSettings()
                        dismissUpdateOverlay()
                    }
                } else {
                    null
                },
                onClose = { dismissUpdateOverlay() },
            ),
        )
    }

    /** 浮层挂在 root 上（屏内容在 screenLayer 里），切屏不会把浮层一起卸载。 */
    private fun showUpdateOverlay(view: UpdateOverlay) {
        updateOverlay?.let { root.removeView(it) }
        updateOverlay = view
        root.addView(
            view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    private fun dismissUpdateOverlay() {
        updateOverlay?.let { root.removeView(it) }
        updateOverlay = null
        progressOverlay = null
    }

    private fun dismissDownloadProgress() {
        val overlay = progressOverlay ?: return
        progressOverlay = null
        if (updateOverlay === overlay) {
            root.removeView(overlay)
            updateOverlay = null
        }
    }

    /** 车机的媒体键转给手机(只转发,不拦截返回)。 */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        MediaKeys.onWindowKey(event) || super.onKeyUp(keyCode, event)

    /** 启动意图:开机自动打开(等插线)、静默连接带界面到前台、插线拉起。 */
    private fun handleLaunch(intent: Intent) {
        // 没启用有线时：插线拉起、开机等插线的入口一律不处理，打开后直接走无线扫码。
        if (!settings.wiredEnabled) {
            if (BootStart.isBootLaunch(intent) || intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
                Log.i(TAG, "有线未启用：忽略开机等插线 / 插线拉起")
            }
            return
        }
        when {
            BootStart.isBootLaunch(intent) -> {
                if (BootStart.waitForCable(this)) {
                    Log.i(TAG, "开机自动打开：开始等插线")
                    requestStart(StartKind.BOOT)
                } else {
                    // 不满足等插线条件:忽略(条件在 startup.BootStart 里判;4.4 上无线不可用时恒真)。
                    Log.i(TAG, "开机自动打开：不满足等插线条件，忽略")
                }
            }
            intent.action == BootStartPolicy.ACTION_SHOW_PROJECTION -> {
                // 界面已在前面(静默连接拉起的);投屏开始的自动切屏由状态驱动,这里不做别的。
                Log.i(TAG, "开机静默连接：手机连上了，界面带到前台")
            }
            else -> handleUsbAttach(intent)
        }
    }

    /** 插线拉起:系统为 USB 设备弹出的入口(首次安装后插线即连)。 */
    private fun handleUsbAttach(intent: Intent) {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        settings.usbAttachAccepted = true
        Log.i(TAG, "插线拉起")
        requestStart(StartKind.USB_ATTACHED)
    }

    /** 走启动链:先过权限,再交 VPN 授权,最后派给控制器。 */
    private fun requestStart(kind: StartKind) {
        pendingStart = kind
        askNextPermission()
    }

    /**
     * 启动链的权限步(4.4 fork:经典 requestPermissions)。
     * 通知权限(33+)、麦克风(23+)在 4.4 上安装时全授予,门控条件走不到,直接过 VPN。
     */
    private fun askNextPermission() {
        if (pendingStart == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !settings.notificationPermissionAsked &&
            ContextCompat.checkSelfPermission(this, POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            settings.notificationPermissionAsked = true
            requestPermissions(arrayOf(POST_NOTIFICATIONS), REQ_START_NOTIFICATIONS)
            return
        }
        val audio = AudioPreferences(this)
        if (audio.micEnabled && !audio.micPermissionAsked && !MicPermission.granted(this)) {
            audio.micPermissionAsked = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_START_MIC)
            return
        }
        continueStart()
    }

    /** 权限步走完:VPN 授权(经典 startActivityForResult,结果在 onActivityResult)。 */
    private fun continueStart() {
        val kind = pendingStart ?: return
        // VpnConsent.request:已授权直接回 Granted;要同意就拉起系统授权框;
        // 车机里没有授权页时回 Unavailable("系统里没有 VPN 授权页")。
        when (val consent = VpnConsent.request(this, REQ_VPN)) {
            is VpnConsent.Status.Granted -> {
                pendingStart = null
                dispatch(kind)
            }
            is VpnConsent.Status.NeedsUserConsent -> Unit // 等授权框的结果回来
            is VpnConsent.Status.Unavailable -> {
                pendingStart = null
                controller.reportBlocked(Notice.VpnUnavailable(consent.reason))
            }
        }
    }

    /** VPN 授权框回来:同意了就继续启动,没同意就作为提示发回首页。 */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_VPN -> {
                val kind = pendingStart
                pendingStart = null
                if (kind == null) return
                // 以系统里实际的授权状态为准：有的 4.4 车机确认框关掉时回的不是 RESULT_OK，但授权已经生效。
                if (VpnConsent.isGranted(this)) {
                    dispatch(kind)
                } else {
                    Log.i(TAG, "用户没有同意 VPN 授权（resultCode=$resultCode）")
                    controller.reportBlocked(Notice.VpnConsentRequired)
                }
            }
            REQ_SETTINGS_PAGE -> settingsScreen.onActivityResult(requestCode)
        }
    }

    /** 权限申请回来:连接链的两步各自往下走,设置页的转给对应段落。 */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_START_NOTIFICATIONS -> askNextPermission()
            REQ_START_MIC -> {
                if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    Log.i(TAG, "用户没有给录音权限：这次不声明车机麦克风")
                    Toast.makeText(this, R.string.mic_permission_denied, Toast.LENGTH_LONG).show()
                }
                askNextPermission()
            }
            REQ_SETTINGS_MIC, REQ_BOOT_NOTIFICATIONS -> settingsScreen.onPermissionResult(requestCode)
        }
    }

    /** 启动方式派给有线控制器。 */
    private fun dispatch(kind: StartKind) {
        when (kind) {
            StartKind.START, StartKind.BOOT -> controller.start()
            StartKind.USB_ATTACHED -> controller.onUsbAttached()
        }
    }

    private companion object {
        private const val TAG = "OpenHC-UI"

        /** 连接链的系统框:VPN 授权、通知权限、麦克风。 */
        private const val REQ_VPN = 1
        private const val REQ_START_NOTIFICATIONS = 2
        private const val REQ_START_MIC = 3
    }
}
