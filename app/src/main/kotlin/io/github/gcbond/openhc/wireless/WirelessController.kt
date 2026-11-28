package io.github.gcbond.openhc.wireless

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.gcbond.openhc.startup.BootStartPolicy
import io.github.gcbond.openhc.startup.BootStart
import io.github.gcbond.openhc.OpenHcApp
import io.github.gcbond.openhc.BrandIcon
import io.github.gcbond.openhc.IdentityLoader
import io.github.gcbond.openhc.audio.AudioOutput
import io.github.gcbond.openhc.connection.BluetoothAddressPolicy
import io.github.gcbond.openhc.connection.BluetoothSource
import io.github.gcbond.openhc.connection.DeviceInfo
import io.github.gcbond.openhc.connection.DisplayPlan
import io.github.gcbond.openhc.connection.VideoClarity
import io.github.gcbond.openhc.connection.withClarity
import io.github.gcbond.openhc.core.common.identity.HostIdentity
import io.github.gcbond.openhc.core.hisight.AccessUnit
import io.github.gcbond.openhc.core.hisight.AccessUnitSink
import io.github.gcbond.openhc.core.hisight.HiSightEndReason
import io.github.gcbond.openhc.core.hisight.HiSightInput
import io.github.gcbond.openhc.core.session.SessionEvent
import io.github.gcbond.openhc.core.session.DisplaySettings
import io.github.gcbond.openhc.core.session.EndReason
import io.github.gcbond.openhc.core.session.SocketRunner
import io.github.gcbond.openhc.core.session.VideoParameters
import io.github.gcbond.openhc.core.session.WirelessEndReason
import io.github.gcbond.openhc.core.session.WirelessEvent
import io.github.gcbond.openhc.core.session.WirelessReceiverConfig
import io.github.gcbond.openhc.core.session.WirelessReceiverSession
import io.github.gcbond.openhc.core.session.WirelessRunnerBridge
import io.github.gcbond.openhc.input.MediaKeys
import io.github.gcbond.openhc.platform.bluetooth.BluetoothStatus
import io.github.gcbond.openhc.platform.bluetooth.RfcommClient
import io.github.gcbond.openhc.platform.bluetooth.RfcommServer
import io.github.gcbond.openhc.platform.media.VideoDecoder
import io.github.gcbond.openhc.platform.wifi.LocalHotspotOwner
import io.github.gcbond.openhc.platform.wifi.WifiDirectGroupOwner
import io.github.gcbond.openhc.platform.wifi.MacText
import io.github.gcbond.openhc.platform.wifi.WifiStatus
import io.github.gcbond.openhc.settings.AppSettings
import io.github.gcbond.openhc.settings.AudioPreferences
import io.github.gcbond.openhc.settings.ClarityPreferences
import io.github.gcbond.openhc.settings.MediaKeyPreferences
import io.github.gcbond.openhc.settings.WirelessNetworkPreferences
import io.github.gcbond.openhc.settings.WirelessPreferences
import io.github.gcbond.openhc.trust.FileTrustStore
import io.github.gcbond.openhc.trust.TrustStoreCorruptException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * 无线投屏（打开应用显示二维码 → 蓝牙 → 车机开热点 → 投屏）的总调度。进程内唯一（应用对象持有），有连接期间由前台服务
 * [WirelessService] 保活。和有线的 [io.github.gcbond.openhc.connection.ConnectionController] 互斥：同时只能有一种连接。
 *
 * 一次连接的接线：
 * 1. 检查条件（系统能开本地热点、身份资产、车机真实蓝牙地址、蓝牙开着、运行时权限、信任库、画布），
 *    建 [WirelessReceiverConfig]（声明接受触摸、声音按设置）；
 * 2. 起前台服务；[SocketRunner] 经 [WirelessRunnerBridge] 跑 [WirelessReceiverSession]：TCP 部分由执行器做，蓝牙、WiFi 动作
 *    交给 [WirelessPlatformExecutor] 接到平台层；
 * 3. 会话事件、无线事件归并成 [state]：PairingReady 时显示二维码和连接码；VideoNegotiated 时在协议线程上建 [VideoDecoder]，
 *    之后每帧同步送进解码器；声音沿用有线的 [AudioOutput]。
 *
 * 收尾：会话结束（WirelessEvent.Ended）或用户停止 → 执行器 close（会话按顺序发 TEARDOWN、关蓝牙、关热点）→ 声音、解码器关掉
 * → 平台资源兜底关闭 → 回到空闲，显示原因。和有线不同，没有「拔线重插」：结束后点开始就能再来一次（会出新的二维码）。
 *
 * 自动连接手机（设置「自动连接手机」，默认开；规则在 [ReconnectPolicy]、[ReconnectTriggers]）：只拨用无线连成功过的手机，
 * 由车机主动拨（车机当蓝牙客户端发起，[startReconnect][io.github.gcbond.openhc.core.session.WirelessReceiverSession.startReconnect]），
 * 手机热点关着时手机不弹框直接连。两个触发点：
 * - 手机的经典蓝牙连上车机（[onPhoneBluetooth]，系统广播经 [BluetoothConnectionReceiver] 转来，本应用没在运行时由系统拉起）；
 * - 界面回到前台（[onAppForeground]，含打开应用、开机自动打开）：先显示二维码，再自动拨最近连成功的那台
 *   （手机没保持蓝牙链路也能拨）。一次蓝牙连接只拨一次。
 * 界面不在前台时先把它带到前台再拨（[surfaceUi]）：车主要看到投屏；而且安卓 10 起定位权限只在界面在前台时算数，否则开不了热点。
 * 有线在等插线时，认识的手机蓝牙连上就请有线停下、改走无线。拨号没成就回到空闲，界面上一行说明，不再等。
 * 新手机用二维码配对：打开应用后显示二维码，扫码并按提示输入车机上的连接码即可。
 *
 * WiFi（规则在 [WirelessNetworkPlan]）：车机开本地热点（[LocalHotspotOwner]，安卓 8.0 起才有，更早的车机用不了无线）。
 * 有连接在进行时就先把热点开好留着（车机主动回连要等车机连上手机的蓝牙控制通道之后才开，手机不在时不碰车机 WiFi），
 * 手机认证通过后直接用；连接结束就关（[syncHotspotStandby]），这期间车机自己的 WiFi 会被热点顶掉。
 * 「退出后恢复车机 WiFi」开着时，热点关掉后由 [wifiRestore] 把车机 WiFi 开回来（安卓 10 起改为提示车主）。
 *
 * 另一个设置：「只用数据线连接」开着时完全不做无线（不拨手机、开始不了，手机还没连上的扫码等待立刻停）。
 *
 * 线程：调度在自己的 [HandlerThread] 上串行执行，公开方法可以在任意线程调用（[sendInput] 在主线程，直接投到协议线程）。
 * 日志标签 `OpenHC-Wireless`。
 */
class WirelessController(
    context: Context,
    private val wiredActive: () -> Boolean,
    /** 有线只是在等插线（手机还没插上）。 */
    private val wiredWaitingForPhone: () -> Boolean = { false },
    /** 请有线停下等插线（认识的手机蓝牙连上、要改走无线时）。 */
    private val stopWiredWaiting: () -> Unit = {},
    /** 让有线回到等插线（为无线让路之后，无线没拨成或连接结束时）。 */
    private val resumeWiredWaiting: () -> Unit = {},
) {

    /** 前台服务监听：要不要留在前台、通知内容要不要更新。在主线程上回调。 */
    fun interface HostListener {
        fun onControllerChanged()
    }

    private val app: Context = context.applicationContext ?: context
    private val settings = AppSettings(app)
    private val worker = HandlerThread("OpenHC-Wireless").also { it.start() }
    private val handler = Handler(worker.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    // 4.4 fork：noBackupFilesDir 是安卓 5 起才有；更早的系统退回 filesDir（本应用清单里 allowBackup=false，不参与备份，等价）。
    private val trustDir by lazy {
        File(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) app.noBackupFilesDir else app.filesDir,
            TRUST_DIR,
        )
    }

    private val mutableState = MutableStateFlow(WirelessUiState())

    /** 无线连接页看的状态。 */
    val state: StateFlow<WirelessUiState> = mutableState.asStateFlow()

    private val mutableDecoder = MutableStateFlow<VideoDecoder?>(null)

    /** 当前连接的解码器（协商出投屏参数后才有），投屏页把画面接到它上面。 */
    val decoder: StateFlow<VideoDecoder?> = mutableDecoder.asStateFlow()

    private val mutableTouchResets = MutableStateFlow(0)

    /** 每次加一，投屏视图就清一次本地触摸状态（回控刚就绪、或发送端报告状态对不上时）。 */
    val touchResets: StateFlow<Int> = mutableTouchResets.asStateFlow()

    /** 前台服务应该在前台：有连接期间为 true。 */
    @Volatile
    var wantsForeground: Boolean = false
        private set

    /** 前台服务真的进了前台（服务自己报）。开机静默连接据此判断后台等待建立起来没有。 */
    @Volatile
    var serviceInForeground: Boolean = false
        internal set

    /** 有一次无线连接在进行（含等已配对的手机回连）。 */
    val isActive: Boolean get() = state.value.active

    /** 有线要不要因为无线而不开始：扫码等待里手机还没连上时不挡（有线来了就让出）。 */
    val blocksWired: Boolean get() = ReconnectPolicy.blocksWired(state.value)

    /** 自动回连排着下一次等待（两次等待之间）：前台服务的通知据此显示「正在等已配对的手机回连」。 */
    @Volatile
    var autoRetryPending: Boolean = false
        private set

    private val hostListeners = CopyOnWriteArrayList<HostListener>()
    private val wirelessPrefs = WirelessPreferences(app)
    private val networkPrefs = WirelessNetworkPreferences(app)

    /** 「退出后恢复车机 WiFi」：连接结束后复查车机 WiFi，安卓 10 起打不开时 [WifiRestorer.needsUser] 给无线页显示提示。 */
    internal val wifiRestore = WifiRestorer(app, handler, { networkPrefs.restoreWifiOnExit }) { Log.i(TAG, it) }

    // ---------------------------------------------------------------- 以下只在调度线程上访问

    private var attempt: Attempt? = null
    private var attemptCounter = 0
    /** 车机主动回连什么时候拨、拨谁（冷却、一次蓝牙连接只拨一次）。 */
    private val triggers = ReconnectTriggers(RECONNECT_COOLDOWN_MS)

    /** 有线在等插线时来了要拨的手机：先请有线停下，等它停好再拨（[onWiredChanged]）。 */
    private var pendingAfterWired: PendingReconnect? = null

    /** 扫码等待时手机蓝牙连上了：先停掉等待，收尾完再主动回连（[afterAttempt]）。 */
    private var pendingAfterWait: PendingReconnect? = null

    /** 用户点了停止：空闲说明显示「手机蓝牙重新连上时再自动连」，下次开始连接时清掉。 */
    private var userStopped = false

    /** 正在等界面带到前台再拨（那 1.5 秒里别的入口不拨，免得拨成另一台）。 */
    private var waitingForUi = false
    private var autoRetry: Runnable? = null
    private var lastWiredActive = false

    /**
     * 自动回连没连上手机后的静默重试：手机什么时候回来不确定，界面开着就一直等、按退避间隔再拨，
     * 不提示失败。连上、用户开始/停止、或界面离开前台时停。
     */
    private var silentRetry: Runnable? = null
    private var silentRetryDelayMs = SILENT_RETRY_MIN_MS

    /** 视频掉流看门狗：已经提示过、上次重试的时间（防止反复重连）。 */
    private var stallAnnounced = false
    private var lastStallRetryAt = 0L

    /**
     * 手机把投屏切到了后台（点了 HiCar 里的车辆品牌图标等）：这时手机本来就不发画面，
     * 不能当成掉流去提示和重连；手机恢复投屏（203）后再照常看。
     */
    private var displayPaused = false

    /** 多久看一次有没有视频数据。 */
    private val STALL_CHECK_MS = 1_000L

    /** 多久没画面就切到「等待画面」提示。 */
    private val STALL_HINT_MS = 3_000L

    /** 多久没画面就结束本次连接、自动重连。 */
    private val STALL_RETRY_MS = 10_000L

    /** 画面恢复多久以内算恢复（收起提示）。 */
    private val STALL_RESUME_MS = 1_000L

    /** 两次「掉流重连」之间至少隔这么久，防止来回重连。 */
    private val STALL_RETRY_GUARD_MS = 60_000L

    /** 掉流重连前的等待（让上一次连接收尾完）。 */
    private val STALL_RETRY_DELAY_MS = 3_000L

    /**
     * 连续多少个音频数据会话手机都没应答 SETUP 就判音频通道死了（手机侧建发送端失败的已知表现，一轮约 8 秒，
     * 5 次约 40 秒）：结束本次连接、自动重连，重连后多半恢复。连线一直没声音比断开重连一次体验好。
     */
    private val AUDIO_DEAD_THRESHOLD = 5

    /** 投屏中定时看有没有视频数据；手机停发时先提示、再自动重连一次。 */
    private val videoStallWatch = object : Runnable {
        override fun run() {
            val current = attempt
            if (current == null || mutableState.value.phase != WirelessPhase.PROJECTING) {
                stallAnnounced = false
                return
            }
            // 手机自己把投屏切后台了（回车机界面）：没有画面是正常的，别重连。
            if (displayPaused) {
                stallAnnounced = false
                handler.postDelayed(this, STALL_CHECK_MS)
                return
            }
            // 「画面停了」看两个时间：手机发来新数据的时间、真正送显的时间，取较晚的。
            // 解码积压的帧还在上屏（实测能撑好几秒）时不算停，免得画面明明在走就提示等画面。
            val decoder = current.videoDecoder
            val last = maxOf(decoder?.lastInputAtMillis ?: 0L, decoder?.lastShownAtMillis ?: -1L)
            val now = android.os.SystemClock.elapsedRealtime()
            if (last > 0L) {
                val age = now - last
                if (age >= STALL_HINT_MS && !stallAnnounced) {
                    stallAnnounced = true
                    Log.i(TAG, "视频中断 ${age} 毫秒（手机没再发画面），停在最后一帧等恢复")
                    apply(WirelessSignal.VideoStalled(true))
                }
                if (age >= STALL_RETRY_MS) {
                    stallAnnounced = false
                    if (now - lastStallRetryAt < STALL_RETRY_GUARD_MS) {
                        Log.w(TAG, "视频中断 ${age} 毫秒，但 ${STALL_RETRY_GUARD_MS / 1000} 秒内已经重试过，不再重连")
                    } else {
                        lastStallRetryAt = now
                        Log.w(TAG, "视频中断 ${age} 毫秒，结束本次连接并重连")
                        current.stopReason = StopReason.VIDEO_STALL
                        teardown(current)
                    }
                    return
                }
                if (age < STALL_RESUME_MS && stallAnnounced) {
                    stallAnnounced = false
                    Log.i(TAG, "视频恢复（1 秒内有新画面）")
                    apply(WirelessSignal.VideoStalled(false))
                }
            }
            handler.postDelayed(this, STALL_CHECK_MS)
        }
    }

    /**
     * 热点提前开好：认证通过才申请时正赶上手机关掉自己的热点、车机 WiFi 刚断，实测安卓 10 车机上系统一直不回话。
     * 什么时候开、什么时候关见 [syncHotspotStandby]。
     */
    private var hotspotStandby = false

    // ---------------------------------------------------------------- 蓝牙链路轮询兜底

    /** 上次轮询看到的每台已知手机的经典蓝牙链路状态（true 连着 / false 断着；没读到过就不在表里）。 */
    private val linkState = HashMap<String, Boolean>()

    /** 定时读蓝牙链路的任务（见 [checkKnownPhonesOnline]）；声明在 init 之前，init 里要启动它。 */
    private val linkWatch = object : Runnable {
        override fun run() {
            try {
                checkKnownPhonesOnline()
            } catch (e: RuntimeException) {
                Log.w(TAG, "轮询蓝牙链路出错：${e.message}")
            } finally {
                handler.postDelayed(this, LINK_WATCH_INTERVAL_MS)
            }
        }
    }

    /** 最近一次无线停下是不是用户点了停止（给「退出后恢复车机 WiFi」）。 */
    private var lastEndByUser = false

    init {
        // 蓝牙打开时看看要不要进入等待（车机开机时蓝牙常常比应用晚就绪）。这是只有系统能发的广播，导出也无妨。
        val bluetoothState = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val on = intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_ON
                if (on) post { refreshIdleStatus("蓝牙打开了") }
            }
        }
        try {
            ContextCompat.registerReceiver(
                app, bluetoothState, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED,
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "注册不了蓝牙开关的广播：${e.message}")
        }
        // 清单里「手机蓝牙连上」的入口按设置启停（安装后第一次启动、设置在别处被改过时对齐）。
        handler.post { syncBluetoothReceiver() }
        // 兜底：部分车机 ROM 不投递 ACL_CONNECTED 广播，定时读一次链路状态，发现已知手机刚连上就拨。
        handler.postDelayed(linkWatch, LINK_WATCH_INTERVAL_MS)
    }

    /**
     * 兜底发现「已知手机蓝牙连着车机」：链路「断开 → 连着」这一刻触发一次，和收到 ACL_CONNECTED 等价。
     * [initial]：刚打开/回到界面时补看一次——第一次读到「连着」也算新的一次（手机早就连着、收不到广播的情形）。
     */
    private fun checkKnownPhonesOnline(initial: Boolean = false) {
        // 车主点过停止：停在二维码页等扫码，不再自动拨（点「开始连接」或手机蓝牙重新连上才拨）。
        if (userStopped) return
        if (!BluetoothTrigger.listens(wirelessPrefs.connectOnBluetooth, wirelessPrefs.wiredOnly)) return
        // 界面不在前台时车机开不了热点，拨了也会失败：等界面回来再拨（回到前台本身也会拨一次）。
        if (!uiVisible()) return
        val known = wirelessPrefs.knownPhones
        if (known.isEmpty()) return
        val adapter = BluetoothStatus.adapter(app) ?: return
        if (BluetoothStatus.state(adapter) != BluetoothStatus.State.ON) return
        for (address in known) {
            val device = try {
                adapter.getRemoteDevice(address)
            } catch (e: IllegalArgumentException) {
                continue
            }
            val connected = BluetoothLinkState.isConnected(device) ?: continue
            val previous = linkState[address]
            linkState[address] = connected
            val justConnected = connected && (previous == false || (initial && previous == null))
            if (!justConnected) continue
            when (val decision = triggers.onPolledConnected(address, known, SystemClock.elapsedRealtime())) {
                is ReconnectTriggers.Decision.Dial -> {
                    Log.i(TAG, "发现手机蓝牙连着（${MacText.mask(decision.address)}），主动拨它")
                    dialReplacingWaiting(decision.address, "手机蓝牙已连接")
                }
                is ReconnectTriggers.Decision.Skip ->
                    Log.i(TAG, "发现手机蓝牙连着，不拨：${decision.reason}")
            }
            return
        }
    }

    /** 发触摸用，主线程读。 */
    @Volatile
    private var inputTarget: Attempt? = null

    // ---------------------------------------------------------------- 对外操作（任意线程）

    /**
     * 「开始无线连接」：先显示二维码，再试着主动回连最近连成功过的手机；拨不出去就留在二维码页。
     */
    fun start() = post {
        if (attempt != null) return@post
        cancelAutoRetry()
        val last = if (wirelessPrefs.connectOnBluetooth) wirelessPrefs.lastPhone else null
        if (last != null) {
            beginReconnect(last, "用户开始连接", preemptWired = false, uiOpening = true)
            if (attempt != null) return@post
        }
        begin(Kind.MANUAL)
    }

    /**
     * 二维码页面上的「开始连接」：当前正停在扫码等待里，先让出等待再主动拨最近连成功过的手机。
     * 车主点过「停止」之后再点它、或手机端主动断开后想重连，都走这里。
     */
    fun startReconnect() = post {
        if (wirelessPrefs.wiredOnly) return@post
        val last = if (wirelessPrefs.connectOnBluetooth) wirelessPrefs.lastPhone else null
        if (last == null) {
            Log.i(TAG, "没有连成功过的手机，点「开始连接」就留在二维码页")
            if (attempt == null) begin(Kind.MANUAL)
            return@post
        }
        userStopped = false
        cancelSilentRetry()
        dialReplacingWaiting(last, "车主点了开始连接")
    }

    /**
     * 打开应用时发起连接：直接进入二维码等待（任何版本都是这个界面）；之前连成功过手机、手机蓝牙也连着，
     * 再顺带主动拨一次（拨通了直接切投屏，拨不通就留在二维码页等扫码）。
     */
    fun startOnLaunch() = post {
        restoreWaiting("打开应用", allowDial = true)
    }

    /**
     * 用户从首页点「回到投屏画面」：清掉暂停标记，并请手机把 HiCar 切回前台（dataType 532，Background=false）。
     * 不这样做的话手机还停在后台状态，再点车辆品牌图标不会再发暂停通知。
     */
    fun projectionForeground() = post {
        displayPaused = false
        apply(WirelessSignal.ProjectionForeground)
        val current = attempt ?: return@post
        if (current.tearingDown || current.finished) return@post
        Log.i(TAG, "回到投屏画面：请手机把 HiCar 切回前台")
        current.runner.submit {
            current.bridge.sendDeviceData(1L, HICAR_TYPE_PROJECT_ACTION, PROJECT_ACTION_FOREGROUND)
        }
    }

    /** 「停止」：按顺序收尾，回到二维码页等扫码；之后不自动重连。 */
    fun stop() = post {
        cancelSilentRetry()
        val current = attempt
        apply(WirelessSignal.StopRequested)
        if (current == null) {
            releaseForeground()
            return@post
        }
        Log.i(TAG, "用户停止第 ${current.id} 次无线连接")
        userStopped = true
        current.stopReason = StopReason.USER
        current.causes.onUserStop()
        teardown(current)
    }

    /**
     * 界面回到前台（含开机自动打开）：进入二维码等待；空闲时如果手机蓝牙已经连着，再顺带拨一次。
     * 不把用户扔在「未开始 + 开始按钮」那一页。
     */
    fun onAppForeground() = post {
        refreshIdleStatus("界面回到前台")
        // 在后台申请热点会被系统拒绝：回到前台时补一次（已经开着或在申请时什么都不做）。
        if (hotspotStandby) warmHotspot()
        restoreWaiting("界面回到前台", allowDial = true)
    }

    /**
     * 开机静默连接（开机广播接收器转来，没有界面）：不拨手机（没有界面开不了热点），只把「自动连接手机」现在能不能起作用
     * 记在 [WirelessUiState.autoHold]，开机那边据此决定待命（等手机蓝牙连上时系统把本应用叫起来）还是改为打开界面。
     */
    fun onSilentBoot() = post {
        refreshIdleStatus("开机静默连接")
    }

    /** 有线的状态变了（主线程回调转来）：有线开始时让出扫码等待；有线停下后接着拨等着的手机，或更新空闲说明。 */
    fun onWiredChanged() = post {
        val active = wiredActive()
        if (active == lastWiredActive) return@post
        lastWiredActive = active
        if (active) {
            yieldWaiting("有线连接开始了")
            if (attempt == null) {
                setAutoHold(AutoHold.WIRED_ACTIVE)
                releaseForeground()
            }
        } else {
            val pending = pendingAfterWired
            if (pending != null) {
                pendingAfterWired = null
                Log.i(TAG, "有线已经停下，接着拨手机（${pending.why}）")
                beginReconnect(pending.address, pending.why, preemptWired = false, resumeWiredAfter = true)
                return@post
            }
            refreshIdleStatus("有线连接结束了")
        }
    }

    /** 有线马上要开始：手机还没连上的等待先让出；有线没真的开始（例如没给授权）时过一会儿回到等待。 */
    fun yieldToWired() = post {
        yieldWaiting("有线要开始")
    }

    /** 从设置页回来：设置可能改了（例如刚打开「自动连接手机」），同步广播入口后照界面回到前台一样进入等待。 */
    fun onSettingsClosed() = post {
        // 开关可能被改过：先同步清单里的「手机蓝牙连上」广播入口，再决定要不要拨。
        syncBluetoothReceiver()
        refreshIdleStatus("从设置页回来")
        restoreWaiting("从设置页回来", allowDial = false)
    }

    /** 设置里切了「只用数据线连接」：开 → 手机还没连上的无线（等扫码、在拨）立刻停，之后不做无线；关 → 刷新空闲说明。 */
    fun onWiredOnlyChanged() = post {
        syncBluetoothReceiver()
        cancelAutoRetry()
        if (!wirelessPrefs.wiredOnly) {
            refreshIdleStatus("关掉了只用数据线连接")
            return@post
        }
        val current = attempt
        if (current != null && isWaiting(current)) {
            Log.i(TAG, "打开了只用数据线连接，停止第 ${current.id} 次无线连接（手机还没连上）")
            current.stopReason = StopReason.AUTO_OFF
            current.causes.onUserStop()
            apply(WirelessSignal.StopRequested)
            teardown(current)
        } else if (current == null) {
            setAutoHold(AutoHold.WIRED_ONLY)
            releaseForeground()
        }
        // 手机已经连上的无线连接照常进行到结束，之后不再做无线。
    }

    /** 设置里切了「自动连接手机」。 */
    fun onConnectOnBluetoothChanged() = post {
        syncBluetoothReceiver()
        refreshIdleStatus("切了自动连接手机")
    }

    /**
     * 手机的经典蓝牙连上 / 断开了车机（任意线程，[BluetoothConnectionReceiver] 转来，只报蓝牙设备类是手机的）。
     * 只在「自动连接手机」开着、没开「只用数据线连接」时有用：连上是一次新的蓝牙连接，认识的手机就拨（[ReconnectTriggers]）；
     * 断开清掉「这次蓝牙连接里拨过」。
     */
    fun onPhoneBluetooth(connected: Boolean, address: String) = post {
        if (!BluetoothTrigger.listens(wirelessPrefs.connectOnBluetooth, wirelessPrefs.wiredOnly)) return@post
        if (connected) onPhoneBluetoothConnected(address) else onPhoneBluetoothDisconnected(address)
    }

    /** 界面那边就开始不了（例如权限被拒）。 */
    fun reportBlocked(notice: WirelessNotice) = post {
        if (attempt == null) block(notice)
    }

    /** 用户确认清除损坏的配对记录：把文件改名留存，然后重新开始。 */
    fun resetTrustStoreAndStart() = post {
        if (attempt != null) return@post
        try {
            FileTrustStore.setAside(trustDir, System.currentTimeMillis())?.let {
                Log.w(TAG, "损坏的配对记录已改名留存为 ${it.name}，之后手机要重新配对")
            }
        } catch (e: IOException) {
            block(WirelessNotice.TrustStoreUnavailable(e.message ?: e.javaClass.simpleName))
            return@post
        }
        cancelAutoRetry()
        begin(Kind.MANUAL)
    }

    /** 投屏视图换算好的触摸（主线程）。回控就绪前不发；发送在协议线程上做，异常就地接住。 */
    fun sendInput(input: HiSightInput) {        val current = inputTarget ?: return
        if (!current.inputReady) return
        current.runner.submitInput {
            try {
                current.bridge.sendInput(input)
            } catch (e: IllegalStateException) {
                Log.d(TAG, "触摸状态对不上，清掉重来：${e.message}")
                mutableTouchResets.update { it + 1 }
                emptyList()
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "触摸不合法，丢弃：${e.message}")
                emptyList()
            }
        }
    }

    /** 调试探针：在当前无线连接的设备控制连接上发一条 `$` 记录（例如 HiCar 元数据请求 dataType=514）。 */
    fun sendDeviceData(module: Long, type: Int, data: ByteArray) {
        val current = inputTarget ?: return
        Log.i(TAG, "发送设备数据：module=$module type=$type ${data.size} 字节")
        current.runner.submit { current.bridge.sendDeviceData(module, type, data) }
    }

    fun addHostListener(listener: HostListener) {
        hostListeners += listener
    }

    fun removeHostListener(listener: HostListener) {
        hostListeners -= listener
    }

    /** 前台服务起不来。自动进入的等待安静放弃，等界面回到前台再试。 */
    fun onServiceStartFailed(message: String) = post {
        wantsForeground = false
        // 两次等待之间、或自动进入的等待：都是自动回连这边的，安静放弃。
        val automatic = autoRetry != null || state.value.autoReconnect
        cancelAutoRetry()
        val current = attempt
        if (current == null) {
            if (automatic) setAutoHold(AutoHold.FOREGROUND_UNAVAILABLE) else block(WirelessNotice.StartFailed(message))
        } else if (current.kind == Kind.AUTO) {
            Log.w(TAG, "前台服务起不来（$message），放弃这次等待，界面回到前台时再试")
            current.stopReason = StopReason.SERVICE_GONE
            current.causes.onUserStop()
            apply(WirelessSignal.StopRequested)
            teardown(current)
        } else {
            current.causes.onFailure(message)
            apply(WirelessSignal.StopRequested)
            teardown(current)
        }
    }

    /** 前台服务不是自己要退出、却被销毁了：没有保活，停掉连接（自动回连等界面回到前台再试）。 */
    fun onServiceDestroyed() = post {
        if (!wantsForeground) return@post
        Log.w(TAG, "前台服务被系统结束，停止无线连接")
        wantsForeground = false
        cancelAutoRetry()
        val current = attempt ?: return@post
        apply(WirelessSignal.StopRequested)
        current.stopReason = StopReason.SERVICE_GONE
        current.causes.onUserStop()
        teardown(current)
    }

    // ---------------------------------------------------------------- 开始

    /**
     * 开一次连接。[kind] 是 [Kind.AUTO]（自动进入的等待）时开始不了只记日志、界面上一行说明（[AutoHold]），不报错。
     */
    private fun begin(kind: Kind, bluetoothTrigger: String? = null, reconnect: ReconnectTarget? = null, resumeWiredAfter: Boolean = false) {
        if (attempt != null) return
        // 真开始了一次连接：静默重试排队作废，间隔回到最短（下次没连上重新退避）。
        cancelSilentRetry()
        silentRetryDelayMs = SILENT_RETRY_MIN_MS
        userStopped = false
        if (wirelessPrefs.wiredOnly) return blocked(kind, WirelessNotice.WiredOnly, AutoHold.WIRED_ONLY)
        if (wiredActive()) return blocked(kind, WirelessNotice.WiredActive, AutoHold.WIRED_ACTIVE)
        // 本地热点安卓 8.0 起才有，更早的车机用不了无线。不要求车机 WiFi 开着：开热点时单射频车机自己的 WiFi 本来就会断。
        if (!WirelessNetworkPlan.hotspotSupported(Build.VERSION.SDK_INT)) {
            return blocked(kind, WirelessNotice.HotspotUnsupported, AutoHold.HOTSPOT_UNSUPPORTED)
        }
        val identity = when (val loaded = IdentityLoader.load(app)) {
            is IdentityLoader.Result.Loaded -> loaded.assets
            IdentityLoader.Result.Missing -> return blocked(kind, WirelessNotice.IdentityMissing, AutoHold.IDENTITY_MISSING)
            is IdentityLoader.Result.Invalid ->
                return blocked(kind, WirelessNotice.IdentityInvalid(loaded.reason), AutoHold.IDENTITY_MISSING)
        }
        // 二维码、握手、认证里的车机身份都由它推出，必须是真实地址：读不到就只能让车主填。
        val systemAddress = DeviceInfo.systemBluetoothAddress(app)
        val manualRaw = settings.manualBluetoothAddress
        val bluetooth = BluetoothAddressPolicy.chooseForWireless(systemAddress, manualRaw)
        Log.i(
            TAG,
            "[蓝牙地址] 系统读到=${if (systemAddress == null) "无" else MacText.mask(systemAddress)}，" +
                "手填=${if (manualRaw == null) "无" else MacText.mask(manualRaw)}，" +
                "采用=${bluetooth.address?.let { MacText.mask(it) } ?: "无"}（来源 ${bluetooth.source}）",
        )
        val address = bluetooth.address
            ?: run {
                Log.w(
                    TAG,
                    "[蓝牙地址] 系统和手填都没有可用地址，这次不开始（请在设置里的「车机蓝牙地址」填入车机的蓝牙地址）",
                )
                return blocked(kind, WirelessNotice.BluetoothAddressMissing, AutoHold.BLUETOOTH_ADDRESS_MISSING)
            }
        // 无线用系统里读到的真实地址；手填的只在读不到时用。手填的像编出来的地址时，界面提醒一句。
        val manual = BluetoothAddressPolicy.normalize(settings.manualBluetoothAddress)
        if (bluetooth.source == BluetoothSource.SYSTEM_PROPERTY && manual != null && manual != address) {
            Log.i(TAG, "手填的蓝牙地址和系统里读到的不同：无线用系统里的真实地址，手填的只用于有线")
        }
        val addressMismatch = bluetooth.source == BluetoothSource.MANUAL && BluetoothAddressPolicy.isLocallyAdministered(address)
        if (addressMismatch) {
            Log.w(TAG, "读不到系统里的蓝牙地址，用的是手填的；它像是编出来的地址，不是车机真实地址时扫码后不会有反应")
        }
        val adapter = BluetoothStatus.adapter(app)
        if (adapter == null) {
            Log.w(TAG, "[蓝牙] 这台机器没有蓝牙适配器，无线开不了")
            return blocked(kind, WirelessNotice.BluetoothUnsupported, AutoHold.BLUETOOTH_OFF)
        }
        val btState = BluetoothStatus.state(adapter)
        Log.i(TAG, "[蓝牙] 适配器状态 $btState")
        if (btState != BluetoothStatus.State.ON) {
            Log.w(TAG, "[蓝牙] 蓝牙没开（状态 $btState），无线开不了；打开蓝牙后再点开始")
            return blocked(kind, WirelessNotice.BluetoothOff, AutoHold.BLUETOOTH_OFF)
        }
        val wifi = WifiStatus.read(app)
        val missing = WirelessPermissions.missing(Build.VERSION.SDK_INT) { granted(it) }
        if (missing.isNotEmpty()) {
            Log.w(TAG, "[权限] 缺少无线需要的权限：${missing.joinToString()}")
            return blocked(kind, WirelessNotice.PermissionsDenied(missing, permanently = false), AutoHold.PERMISSIONS_MISSING)
        }
        val trustStore = try {
            FileTrustStore.open(trustDir)
        } catch (e: TrustStoreCorruptException) {
            return blocked(kind, WirelessNotice.TrustStoreCorrupt(e.message ?: ""), AutoHold.START_FAILED)
        } catch (e: IOException) {
            return blocked(kind, WirelessNotice.TrustStoreUnavailable(e.message ?: e.javaClass.simpleName), AutoHold.START_FAILED)
        }
        if (kind != Kind.MANUAL && trustStore.peerCount == 0) {
            return blocked(kind, WirelessNotice.StartFailed("没有配对过的手机"), AutoHold.NO_PAIRED_PHONE)
        }
        val fullScreen = settings.fullScreen
        val display = try {
            DisplayPlan.resolve(
                io.github.gcbond.openhc.connection.DisplayArea.screenFor(app),
                io.github.gcbond.openhc.connection.ResolutionChoice.Auto,
                settings.fps,
            )
        } catch (e: IllegalArgumentException) {
            return blocked(kind, WirelessNotice.StartFailed("屏幕参数不对：${e.message}"), AutoHold.START_FAILED)
        }
        io.github.gcbond.openhc.connection.DisplayArea.activeFullScreen = fullScreen
        Log.i(TAG, "画布 ${display.width}x${display.height}（全屏${if (fullScreen) "开" else "关"}）")
        val clarity = ClarityPreferences(app).clarity
        val audio = AudioPreferences(app).toSettings()
        val config = try {
            WirelessReceiverConfig(
                identity = identity,
                bluetoothAddress = address,
                // 无线走车机自己的 2.4GHz 热点，实际吞吐撑不住「高/最高」声明的 12/15 Mbps：实测帧成批迟到、
                // 到达抖动到 100ms、解码前积压 5-6 帧，上屏延迟到 200-400ms（触摸滑动明显滞后）。
                // 无线时统一把声明码率钳到 [WIRELESS_MAX_BITRATE]；「标准」原本不声明码率、手机按自己的高码率发，
                // 这里也显式声明上限，否则钳制不会生效。有线不受影响。
                display = display.withClarity(clarity).let {
                    val declared = it.bitrate
                    val target = minOf(declared ?: WIRELESS_MAX_BITRATE, WIRELESS_MAX_BITRATE)
                    if (declared == null || declared > WIRELESS_MAX_BITRATE) {
                        Log.i(
                            TAG,
                            "无线连接码率从 ${declared?.let { VideoClarity.describe(it) } ?: "不声明"} " +
                                "钳到 $WIRELESS_MAX_BITRATE（2.4GHz 热点撑不住更高）",
                        )
                    }
                    DisplaySettings(
                        width = it.width, height = it.height, fps = it.fps, dpi = it.dpi, density = it.density,
                        gop = it.gop, bitrate = target,
                    )
                },
                trustStore = trustStore,
                supportsUsb = true,
                touchInput = true,
                mediaKeyInput = MediaKeyPreferences(app).enabled,
                timeouts = WirelessNetworkPlan.timeouts(),
                audio = audio,
                // 「设置 → 车辆品牌」选中的车标作为品牌图标回传（dataType 502）；「默认」时为 null。
                brandIcon = BrandIcon.load(app),
            )
        } catch (e: IllegalArgumentException) {
            return blocked(kind, WirelessNotice.StartFailed("配置不合法：${e.message}"), AutoHold.START_FAILED)
        }
        if (!requestForeground()) {
            return blocked(kind, WirelessNotice.StartFailed(FOREGROUND_FAILED), AutoHold.FOREGROUND_UNAVAILABLE)
        }

        cancelAutoRetry()
        val directGroup = wirelessPrefs.usesWifiDirect
        val current = Attempt(
            ++attemptCounter, kind, config, adapter, identity.host, bluetoothTrigger, reconnect, resumeWiredAfter, directGroup,
        )
        attempt = current
        displayPaused = false
        if (reconnect != null) triggers.commitDial(reconnect.address, SystemClock.elapsedRealtime())
        inputTarget = current
        Log.i(
            TAG,
            "第 ${current.id} 次无线连接（${when (kind) { Kind.AUTO -> "等已配对的手机回连"; Kind.RECONNECT -> "车机主动回连"; Kind.MANUAL -> "用户开始" }}）：" +
                "蓝牙地址来源 ${bluetooth.source}，deviceId ${config.deviceId.take(4)}…，" +
                "画布 ${display.width}x${display.height}@${display.fps} dpi ${display.dpi}，系统 ${Build.VERSION.SDK_INT}，$wifi，" +
                "已配对手机 ${trustStore.peerCount} 台，声音 ${audio ?: "关"}，手机加入的网络 ${if (directGroup) "WiFi 直连组（试验）" else "本地热点"}",
        )
        Log.i(TAG, "第 ${current.id} 次无线连接的清晰度：${clarity.key}，声明码率 ${VideoClarity.describe(config.display.bitrate)}")
        setAutoHold(null)
        apply(WirelessSignal.AttemptStarted(current.id, addressMismatch, autoReconnect = kind != Kind.MANUAL, direct = directGroup))
        current.runner.start()
        watchTermination(current)
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    /** 开始不了：回到空闲，显示原因。 */
    private fun block(notice: WirelessNotice) {
        Log.w(TAG, "无线连接不能开始：$notice")
        apply(WirelessSignal.Blocked(notice))
        releaseForeground()
    }

    /** 开始不了：用户开始的显示原因；自动进入的只记日志、界面上一行说明。 */
    private fun blocked(kind: Kind, notice: WirelessNotice, hold: AutoHold) {
        if (kind == Kind.MANUAL) return block(notice)
        Log.i(TAG, "自动回连这次不进入等待：$hold（$notice）")
        setAutoHold(hold)
        releaseForeground()
    }

    // ---------------------------------------------------------------- 自动连接手机

    /** 更新空闲时的一行说明（「自动连接手机」能不能起作用）；没有连接时退出前台。 */
    private fun refreshIdleStatus(why: String) {
        if (attempt != null) return
        lastWiredActive = wiredActive()
        var hold = ReconnectPolicy.hold(reconnectConditions())
        if (hold == AutoHold.READY && userStopped) hold = AutoHold.PAUSED_BY_USER
        if (hold != state.value.autoHold) Log.i(TAG, "自动连接手机（$why）：$hold")
        setAutoHold(hold)
        releaseForeground()
    }

    /**
     * 界面在前台且空闲时一律先进入二维码等待（任何版本、有没有配对记录都是这个界面）。
     * [allowDial]：二维码出来后自动连一次连过的手机（拨通了切投屏，连不上留在二维码页）。
     * 用户点过停止时也不自动开始。
     */
    private fun restoreWaiting(why: String, allowDial: Boolean) {
        if (attempt != null || waitingForUi || wirelessPrefs.wiredOnly) return
        if (userStopped) {
            Log.i(TAG, "点过停止，不自动开始；手机蓝牙重新连上车机时再拨（$why）")
            return
        }
        cancelAutoRetry()
        Log.i(TAG, "显示二维码等待扫码（$why）")
        begin(Kind.MANUAL)
        if (!allowDial || attempt == null) return
        // 等二维码这一帧画出来再连（直接拨会让二维码一闪而过）。
        handler.postDelayed({ dialKnownPhoneAfterWaiting() }, LAUNCH_DIAL_DELAY_MS)
    }

    /**
     * 二维码已经显示出来：自动连接连过的手机。
     * 先看一眼蓝牙链路（手机蓝牙早就连着时直接拨）；没连着也直接拨最近连成功的那台——
     * 实车上手机通常不会主动保持和车机的蓝牙链路，只能由车机拨它。
     * 拨不通就留在二维码页，不提示失败。
     */
    private fun dialKnownPhoneAfterWaiting() {
        if (userStopped) return
        val current = attempt ?: return
        if (current.kind != Kind.MANUAL || current.tearingDown) return
        checkKnownPhonesOnline(initial = true)
        // 上面那条路已经改成主动回连了，不再重复拨。
        val waiting = attempt?.let { it.kind == Kind.MANUAL && !it.tearingDown } == true
        if (!waiting) return
        if (!wirelessPrefs.connectOnBluetooth || wirelessPrefs.wiredOnly) return
        val last = wirelessPrefs.lastPhone ?: return
        dialReplacingWaiting(last, "打开应用自动连接")
    }

    private fun onPhoneBluetoothConnected(address: String) {
        Log.i(TAG, "手机蓝牙连上了车机（${MacText.mask(address)}）")
        when (val decision = triggers.onBluetoothConnected(address, wirelessPrefs.knownPhones, SystemClock.elapsedRealtime())) {
            is ReconnectTriggers.Decision.Dial -> dialReplacingWaiting(decision.address, "手机蓝牙连上了")
            is ReconnectTriggers.Decision.Skip -> Log.i(TAG, "手机蓝牙连上了，不拨：${decision.reason}")
        }
    }

    /**
     * 手机蓝牙连上了、要车机主动拨它：如果这会儿正在扫码等待（手机还没连上），先把等待停掉，收尾完再拨，
     * 免得「等扫码」占着连接名额把主动回连挡掉。
     */
    private fun dialReplacingWaiting(address: String, why: String) {
        val current = attempt
        if (current == null) {
            beginReconnect(address, why, preemptWired = true)
            return
        }
        if (current.kind == Kind.MANUAL && !current.phoneSeen && !current.tearingDown) {
            Log.i(TAG, "停止扫码等待，改为车机主动回连（$why）")
            pendingAfterWait = PendingReconnect(address, why)
            current.stopReason = StopReason.YIELD
            current.causes.onUserStop()
            apply(WirelessSignal.StopRequested)
            teardown(current)
            return
        }
        Log.i(TAG, "已经有连接在进行，不拨（$why）")
    }

    private fun onPhoneBluetoothDisconnected(address: String) {
        triggers.onBluetoothDisconnected(address)
        // 认识的手机蓝牙断开：点过的停止只管到这次蓝牙连接为止。
        if (userStopped && wirelessPrefs.knownPhones.any { it.equals(address, ignoreCase = true) }) {
            userStopped = false
            refreshIdleStatus("手机蓝牙断开了")
        }
    }

    /** 读手机的蓝牙名称（「设置 → 设备」里显示用）；读不到或没权限返回 null，不影响连接。 */
    private fun phoneName(address: String): String? = try {
        BluetoothStatus.adapter(app)?.getRemoteDevice(address)?.name?.takeIf { it.isNotBlank() }
    } catch (e: RuntimeException) {
        null
    }

    /**
     * 车机主动回连（任意线程）：马上拨蓝牙地址为 [address] 的手机，不看「自动连接手机」开关和记录。给调试入口用；
     * 真的拨出去了同样计入冷却和这次蓝牙连接（[ReconnectTriggers.commitDial]，在 [begin] 里记）。
     */
    fun reconnectPhone(address: String, why: String) = post {
        beginReconnect(address, why, preemptWired = true)
    }

    /**
     * 拨 [address]：先判前置条件（不满足只在界面上一行说明，不弹界面），有线在等插线时按 [preemptWired] 请它让出，
     * 界面不在前台先带到前台、等 [SURFACE_WAIT_MS] 后确认界面真出来了再拨（出不来就不拨：开不了热点，拨了也白拨、手机还会记一次失败）。
     * [resumeWiredAfter]：有线是为这次让出来的，这次没拨成、或连接结束后让有线回到等插线。
     * [uiOpening]：调用方是界面自己（打开应用时），界面正在起来，不用再「先带到前台再拨」。
     * 真的开始拨了才记进拨号规则（[begin] 里 [ReconnectTriggers.commitDial]），没拨成不占名额。
     */
    private fun beginReconnect(
        address: String,
        why: String,
        preemptWired: Boolean,
        resumeWiredAfter: Boolean = false,
        uiOpening: Boolean = false,
    ) {
        fun giveUp() {
            if (resumeWiredAfter) {
                Log.i(TAG, "这次没拨成，让有线回到等插线")
                resumeWiredWaiting()
            }
        }
        if (attempt != null || waitingForUi) {
            Log.i(TAG, "已经有连接在进行或在等界面，不拨手机（$why）")
            return
        }
        val hold = ReconnectPolicy.hold(reconnectConditions(forDial = true)).takeUnless { it == AutoHold.READY }
        if (hold != null) {
            Log.i(TAG, "不拨手机（$why）：$hold")
            setAutoHold(hold)
            return giveUp()
        }
        if (wiredActive()) {
            if (preemptWired && wiredWaitingForPhone()) {
                Log.i(TAG, "有线在等插线：请它先停下，改为车机主动拨手机（$why）")
                pendingAfterWired = PendingReconnect(address, why)
                stopWiredWaiting()
            } else {
                Log.i(TAG, "有线连接在进行，不拨手机（$why）")
                setAutoHold(AutoHold.WIRED_ACTIVE)
            }
            return
        }
        val adapter = BluetoothStatus.adapter(app)
        val name = adapter?.let { phoneName(it, address) }
        if (name == null) {
            Log.i(TAG, "${if (adapter == null) "车机没有蓝牙" else "读不到这台手机的蓝牙名"}，不拨（$why）")
            return giveUp()
        }
        cancelAutoRetry()
        val target = ReconnectTarget(address, name)
        if (uiOpening || !surfaceUi(why)) {
            Log.i(TAG, "车机主动拨手机（${MacText.mask(address)}）：$why")
            begin(Kind.RECONNECT, reconnect = target, resumeWiredAfter = resumeWiredAfter)
            if (attempt == null) giveUp()
            return
        }
        Log.i(TAG, "车机主动拨手机（${MacText.mask(address)}）：$why；界面带到前台 $SURFACE_WAIT_MS 毫秒后再拨")
        waitingForUi = true
        handler.postDelayed({
            waitingForUi = false
            when {
                attempt != null -> Log.i(TAG, "等界面时已经有连接在进行，不拨了")
                !uiVisible() -> {
                    Log.i(TAG, "界面没能打开（多半缺悬浮窗权限或被系统拦了），这次不拨：开不了热点")
                    setAutoHold(AutoHold.UI_NOT_SHOWN)
                    giveUp()
                }
                else -> {
                    begin(Kind.RECONNECT, reconnect = target, resumeWiredAfter = resumeWiredAfter)
                    if (attempt == null) giveUp()
                }
            }
            syncHotspotStandby()
        }, SURFACE_WAIT_MS)
    }

    private fun uiVisible(): Boolean = (app as? OpenHcApp)?.uiVisibility?.visible != false

    /**
     * 车机主动回连前把界面带到前台；界面已经在前台返回 false，否则发出打开请求后返回 true。
     *
     * 一是车主要看到投屏；二是安卓 10 起定位权限是「使用期间允许」，只有界面在前台才算数（前台服务不算），
     * 界面不在前台时系统不让开热点（2026-11-22 实车：手机蓝牙连上触发、界面在后台，认证通过后开热点被拒
     * 「does not have Coarse/Fine Location permission」）。安卓 10 起后台打开界面要悬浮窗权限，没有时请求不报错、界面不出来。
     */
    private fun surfaceUi(why: String): Boolean {
        if (uiVisible()) return false
        Log.i(
            TAG,
            "车机主动回连：界面不在前台，先带到前台（$why）" + if (overlayGranted()) "" else "；没有悬浮窗权限，多半被系统拦下",
        )
        try {
            app.startActivity(BootStart.surfaceIntent(app))
        } catch (e: RuntimeException) {
            Log.w(TAG, "带不到前台：${e.javaClass.simpleName}：${e.message}")
        }
        return true
    }

    private fun overlayGranted(): Boolean = !BootStartPolicy.overlayRequired(Build.VERSION.SDK_INT) || BootStart.canDrawOverlays(app)

    /** 车机蓝牙记下的这台手机的名字（请手机开认证子通道时当业务标签；实车只验证过和手机蓝牙名一致的情况）。 */
    @SuppressLint("MissingPermission")
    private fun phoneName(adapter: BluetoothAdapter, address: String): String? = try {
        adapter.getRemoteDevice(address).name?.takeIf { it.isNotBlank() }
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: SecurityException) {
        null
    }

    /** 清单里「手机蓝牙连上」的入口：开关开着、没开只用数据线连接时启用。 */
    private fun syncBluetoothReceiver() {
        BluetoothConnectionReceiver.sync(app, BluetoothTrigger.listens(wirelessPrefs.connectOnBluetooth, wirelessPrefs.wiredOnly))
    }

    /**
     * 判断「自动连接手机」时的条件快照。[forDial]：马上要拨（触发规则已经定了拨谁），不再看开关、记录和悬浮窗（悬浮窗另看界面出没出来），
     * 有线在进行另外处理。
     */
    private fun reconnectConditions(forDial: Boolean = false): ReconnectPolicy.Conditions {
        val adapter = BluetoothStatus.adapter(app)
        val sdk = Build.VERSION.SDK_INT
        val address = BluetoothAddressPolicy.chooseForWireless(DeviceInfo.systemBluetoothAddress(app), settings.manualBluetoothAddress).address
        return ReconnectPolicy.Conditions(
            enabled = forDial || wirelessPrefs.connectOnBluetooth,
            wiredOnly = wirelessPrefs.wiredOnly,
            wiredActive = !forDial && wiredActive(),
            hotspotSupported = WirelessNetworkPlan.hotspotSupported(sdk),
            identityLoaded = IdentityLoader.load(app) is IdentityLoader.Result.Loaded,
            bluetoothAddressKnown = address != null,
            bluetoothOn = BluetoothStatus.state(adapter) == BluetoothStatus.State.ON,
            permissionsGranted = WirelessPermissions.missing(sdk) { granted(it) }.isEmpty(),
            knownPhones = if (forDial) 1 else wirelessPrefs.knownPhones.size,
            overlayGranted = forDial || overlayGranted(),
        )
    }

    private fun cancelAutoRetry() {
        val retry = autoRetry ?: return
        handler.removeCallbacks(retry)
        autoRetry = null
        autoRetryPending = false
    }

    private fun setAutoHold(hold: AutoHold?) {
        apply(WirelessSignal.AutoHoldChanged(hold))
    }

    /** 这次连接还在等手机经蓝牙连上（没在收尾）。 */
    private fun isWaiting(current: Attempt): Boolean = !current.phoneSeen && !current.tearingDown

    /** 让出还在等手机的自动等待；让了返回 true。 */
    private fun yieldWaiting(why: String): Boolean {
        val current = attempt ?: return false
        if (current.kind != Kind.AUTO || !isWaiting(current)) return false
        Log.i(TAG, "$why，停止等已配对的手机回连，让给有线")
        current.stopReason = StopReason.YIELD
        current.causes.onUserStop()
        apply(WirelessSignal.StopRequested)
        teardown(current)
        return true
    }

    // ---------------------------------------------------------------- 一次连接

    /** 这次连接是怎么开始的。 */
    private enum class Kind {
        /** 用户点了开始（或清除配对记录后重新开始）。 */
        MANUAL,

        /** 自动进入的「等已配对的手机回连」。 */
        AUTO,

        /** 车机主动回连：车机去拨已配对的手机（手机蓝牙连上车机时、或调试入口），不出二维码。 */
        RECONNECT,
    }

    /** 车机主动回连要拨的手机：蓝牙地址和设备名（请手机开认证子通道时的业务标签）。 */
    private class ReconnectTarget(val address: String, val name: String)

    /** 有线在等插线时来了要拨的手机：等有线停好后再拨。 */
    private class PendingReconnect(val address: String, val why: String)

    /** 本机为什么停掉这次连接（会话自己结束的为 null）。决定之后自动回连多久再来。 */
    private enum class StopReason {
        USER,

        /** 让给有线。 */
        YIELD,

        /** 按新设置重开等待。 */
        RESTART,

        /** 关掉了自动回连。 */
        AUTO_OFF,

        /** 前台服务起不来或被系统结束：等界面回到前台再试。 */
        SERVICE_GONE,

        /** 投屏中手机停发视频太久：结束本次连接，稍后自动重连一次。 */
        VIDEO_STALL,

        /** 音频通道死了（连续多个数据会话手机都没应答 SETUP，一直没声音）：结束本次连接，稍后自动重连一次。 */
        AUDIO_DEAD,
    }

    private inner class Attempt(
        val id: Int,
        val kind: Kind,
        config: WirelessReceiverConfig,
        /** 车机蓝牙适配器（开始前的检查已保证非空）。 */
        adapter: BluetoothAdapter,
        /** 车机身份（元数据请求的 RequestCaller 取这里的车型编号）。 */
        val host: HostIdentity,
        /** 这次等待是这台手机的经典蓝牙连上才开始的（地址），其他为 null。 */
        val bluetoothTrigger: String?,
        /** 车机主动回连的目标（[Kind.RECONNECT]），其他为 null。 */
        reconnect: ReconnectTarget?,
        /** 有线是为这次让出来的：结束后让有线回到等插线。 */
        val resumeWiredAfter: Boolean = false,
        /** 这次不开本地热点，改建 WiFi 直连组（设置里「无线方式」选了直连，安卓 10 起）。 */
        val directGroup: Boolean = false,
    ) {
        /** 投屏开始过。 */
        var projected = false

        /** 已经拿到手机的元数据能力应答（514），不用再重试。 */
        var metadataGranted = false

        /** 这次连的手机的蓝牙地址：主动回连时是拨的那台，扫码时手机经蓝牙连上来才知道；投屏开始后记进「连成功过的手机」。 */
        var phoneAddress: String? = reconnect?.address

        val sink = ForwardingSink()
        val startedAt = SystemClock.elapsedRealtime()

        /** 车机媒体键的去处（[MediaKeys]）：按键在主线程上转进来，投到协议线程上经桥接发给手机。 */
        val keys = MediaKeys.Target("无线 #$id") { key ->
            if (!tearingDown) runner.submit { MediaKeys.sendNow("无线 #$id", key, bridge::sendInput) }
        }
        val mediaKeysDeclared = config.mediaKeyInput

        /**
         * 声音出口：音频回调在协议线程上，收尾时在执行器停下之后关。日志里的连接编号加上偏移，和有线的区分开。
         * 车机这边的焦点变化投到协议线程上经桥接发给手机。
         */
        val audio: AudioOutput = AudioOutput(
            app, AUDIO_ID_BASE + id,
            mediaFocusChanged = { held -> MediaKeys.mediaFocus(keys, held) },
            tellPhone = { change -> runner.submit { bridge.sendAudioFocus(change) } },
            onChannelSuspect = { consecutive -> onAudioChannelSuspect(this, consecutive) },
        )
        val session: WirelessReceiverSession = WirelessReceiverSession(config, sink, audio)
        private val results = RunnerResults()
        val executor = WirelessPlatformExecutor(
            openRfcomm = { name, uuid, events ->
                RfcommServer(adapter, name, uuid, events, "OpenHC-Rfcomm-$id")
            },
            dialRfcomm = { address, uuid, channel, events ->
                val byChannel = channel.takeUnless { DebugOverrides.forceRfcommSdp }
                if (channel != null && byChannel == null) Log.i(TAG, "[$id] 调试开关：认证子通道改按 SDP 查")
                RfcommClient(adapter, address, uuid, events, "OpenHC-Wireless-$id-dial", byChannel)
            },
            // 会话在手机认证通过后才要热点；无线开着时热点已经先开好留着，这里直接接手。之前排的「恢复车机 WiFi」作废。
            startHotspot = { events ->
                wifiRestore.onHotspotRequested()
                LocalHotspotOwner.get(app).create(events) { Log.i(TAG, "[$id] 热点：$it") }
            },
            // 直连：不开热点、不动车机自己的 WiFi，所以也不排「恢复车机 WiFi」。
            startDirect = if (directGroup) {
                { events -> WifiDirectGroupOwner(app, events) { Log.i(TAG, "[$id] 直连：$it") } }
            } else {
                null
            },
            results = results,
            onEvent = { event -> onWirelessEvent(this, event) },
            log = { Log.i(TAG, "[$id] $it") },
        )
        val bridge: WirelessRunnerBridge = WirelessRunnerBridge(
            session,
            reconnect?.let { target -> { now: Long -> session.startReconnect(target.address, target.name, now) } } ?: session::start,
        ) { output -> executor.execute(output) }
        val runner: SocketRunner = SocketRunner(
            bridge,
            { event -> onProtocolEvent(this, event) },
            SocketRunner.Options(
                threadName = "OpenHC-Wireless-$id",
                // 协议线程卡住时把线程栈写进诊断日志：真机上 adb 经常拿不到，靠这条日志定位卡在哪个函数。
                watchdogMillis = 5_000,
                log = { Log.i(TAG, "[$id] 执行器：$it") },
            ),
        )
        val causes = WirelessEndTracker()
        var touchNegotiated = false
        var keyNegotiated = false
        @Volatile
        var tearingDown = false
        var finished = false

        /** 手机经蓝牙连上过。 */
        var phoneSeen = false
        var stopReason: StopReason? = null

        /** 收尾时记下的结束原因（[afterAttempt] 用它判断是不是手机端主动断开）。 */
        var endCause: WirelessEndCause? = null

        /** 协议线程建、调度线程收尾时关。 */
        @Volatile
        var videoDecoder: VideoDecoder? = null

        @Volatile
        var inputReady = false

        init {
            results.runner = runner
            results.bridge = bridge
            bridge.attach(runner)
        }
    }

    /** 视频回调的转接：解码器要等协商出参数才建，建好前到的帧丢掉（第一帧总在 PLAY 之后）。 */
    private class ForwardingSink : AccessUnitSink {
        @Volatile
        var target: AccessUnitSink? = null

        override fun onAccessUnit(unit: AccessUnit) {
            target?.onAccessUnit(unit)
        }
    }

    /** 会话事件（TCP 段与认证），在协议线程上。 */
    private fun onProtocolEvent(current: Attempt, event: SessionEvent) {
        if (event is SessionEvent.Diagnostic) {
            // 这台车机的 Flyme 关了 D 级日志；排查元数据时临时按 Info 记录，方便看手机上报了什么。
            if (LOG_SESSION_DIAGNOSTICS) Log.i(TAG, "[${current.id}] $event") else Log.d(TAG, "[${current.id}] $event")
        } else {
            Log.i(TAG, "[${current.id}] $event")
        }
        // 在协议线程上同步建好解码器：之后的帧也在这个线程上送，保证第一帧到时解码器已经接上。
        if (event is SessionEvent.VideoNegotiated) prepareDecoder(current, event.video)
        post { onSessionEvent(current, event) }
    }

    /** 无线事件，在协议线程上（经执行器转来）。 */
    private fun onWirelessEvent(current: Attempt, event: WirelessEvent) {
        Log.i(TAG, "[${current.id}] $event")
        // 结束原因按发生的先后记，不等调度线程排到。
        if (event is WirelessEvent.Ended) current.causes.onSessionEnded(event.reason)
        post {
            if (current !== attempt || current.finished) return@post
            when (event) {
                is WirelessEvent.PhoneBluetoothConnected -> {
                    current.phoneSeen = true
                    current.phoneAddress = event.address
                }
                else -> Unit
            }
            apply(WirelessSignal.Wireless(event))
            if (event is WirelessEvent.Ended) {
                if (current.tearingDown) maybeFinish(current) else teardown(current)
            }
        }
    }

    private fun onSessionEvent(current: Attempt, event: SessionEvent) {
        if (current !== attempt || current.finished) return
        apply(WirelessSignal.Session(event))
        when (event) {
            // 投屏开始了才算连成功：记下这台手机，之后「自动连接手机」就会拨它。
            is SessionEvent.VideoStarted -> {
                current.projected = true
                current.phoneAddress?.let { address ->
                    if (wirelessPrefs.rememberPhone(address, phoneName(address))) {
                        Log.i(TAG, "记下连成功的手机（${MacText.mask(address)}），之后会自动连它")
                    }
                }
            }
            is SessionEvent.VideoNegotiated -> {
                current.touchNegotiated = event.video.touchInput
                current.keyNegotiated = event.video.keyInput
                MediaKeys.negotiated(current.keys, current.mediaKeysDeclared, event.video.keyInput)
            }
            // PLAY 只在回控连上后才发：手机接受了 PLAY，回控就绪。
            SessionEvent.PlayAccepted -> {
                current.inputReady = current.touchNegotiated && !current.tearingDown
                if (current.keyNegotiated && !current.tearingDown) MediaKeys.ready(app, current.keys)
            }
            // HiCar 元数据：加密连上后先查一次能力，手机之后才会推 515（曲目/进度）。
            is SessionEvent.DeviceSessionReady -> requestHiCarMetadata(current)
            is SessionEvent.MetadataCapability -> {
                current.metadataGranted = true
                (app as? OpenHcApp)?.hicarMedia?.onCapability(event.json)
            }
            is SessionEvent.MediaMetadata -> (app as? OpenHcApp)?.hicarMedia?.onMetadata(event.json)
            is SessionEvent.NavigationMetadata -> (app as? OpenHcApp)?.hicarMedia?.onNavigation(event.json)
            // 手机切走 / 切回 HiCar 投屏：暂停期间视频本来就会停，掉流看门狗要让开。
            SessionEvent.DisplayPaused -> displayPaused = true
            SessionEvent.DisplayResumed -> displayPaused = false
            else -> Unit
        }
    }

    /**
     * 查询 HiCar 可开放的元数据类型（dataType 514，module 1）。手机之后才会推 515（曲目/进度）。
     *
     * 刚加密完就发时手机有时还不接（实测第一发没有应答），隔 2s、5s 再补发；收到应答就不再发。
     */
    private fun requestHiCarMetadata(current: Attempt) = requestHiCarMetadata(current, 0)

    private fun requestHiCarMetadata(current: Attempt, step: Int) {
        if (step >= HICAR_METADATA_DELAYS_MS.size) return
        handler.postDelayed({
            if (current.metadataGranted || current.finished || current !== attempt) return@postDelayed
            val request = hicarMetadataRequest(current.host.fullModel)
            for (module in HICAR_METADATA_MODULES) {
                Log.i(TAG, "无线 #${current.id}：请求 HiCar 元数据能力（module=$module type=514，第 ${step + 1} 次）")
                current.runner.submit { current.bridge.sendDeviceData(module, HICAR_TYPE_METADATA_ABILITY, request) }
            }
            requestHiCarMetadata(current, step + 1)
        }, HICAR_METADATA_DELAYS_MS[step])
    }

    private fun prepareDecoder(current: Attempt, video: VideoParameters) {
        val decoder = current.videoDecoder ?: VideoDecoder(DecoderListener(current.id)).also {
            current.videoDecoder = it
            current.sink.target = it
            mutableDecoder.value = it
        }
        decoder.prepare(video.width, video.height, video.fps)
    }

    private inner class DecoderListener(private val attemptId: Int) : VideoDecoder.Listener {
        override fun onVideoSizeChanged(width: Int, height: Int) {
            post { if (attempt?.id == attemptId) apply(WirelessSignal.VideoSize(width, height)) }
        }

        override fun onDecoderUnavailable(detail: String) {
            post { if (attempt?.id == attemptId) apply(WirelessSignal.DecoderProblem(detail)) }
        }
    }

    /** 执行器意外退出（不会有 Ended 事件）时也要能收尾：另开一个线程等它结束。 */
    private fun watchTermination(current: Attempt) {
        thread(name = "OpenHC-WirelessWatch-${current.id}", isDaemon = true) {
            try {
                while (!current.runner.awaitTermination(WATCH_SLICE_MS)) {
                    // 一直等到执行器结束。
                }
            } catch (e: InterruptedException) {
                return@thread
            }
            post { onRunnerTerminated(current) }
        }
    }

    private fun onRunnerTerminated(current: Attempt) {
        if (current.finished) return
        current.runner.failure?.let { failure ->
            Log.e(TAG, "第 ${current.id} 次无线连接的执行器意外结束", failure)
            current.causes.onFailure("执行器意外结束：${failure.javaClass.simpleName}：${failure.message}")
        }
        if (current.tearingDown) {
            maybeFinish(current)
        } else {
            apply(WirelessSignal.StopRequested)
            teardown(current)
        }
    }

    /** 按顺序收尾，见类注释。可重复调用。 */
    private fun teardown(current: Attempt) {
        if (current.tearingDown) return
        current.tearingDown = true
        current.inputReady = false
        MediaKeys.gone(current.keys)
        // 1. 协议层：会话按顺序收尾（TEARDOWN、关蓝牙、拆 WiFi 网络）；已经结束时立即返回，否则最多阻塞几秒。
        current.runner.close()
        // 2. 声音、解码器：执行器已经停了，不会再有回调。
        current.audio.close()
        current.sink.target = null
        current.videoDecoder?.close()
        current.videoDecoder = null
        mutableDecoder.value = null
        // 3. 平台资源兜底：正常收尾时会话已经逐个关过，这里什么都不剩；执行器意外退出时靠这一步撤掉服务记录、拆 WiFi 网络。
        current.executor.closeAll()
        maybeFinish(current)
    }

    /**
     * 音频通道疑似死了：投屏还活着但连续 [AUDIO_DEAD_THRESHOLD] 个数据会话手机都没应答 SETUP（一直没声音）。
     * 投屏动着不好让用户自己发现没声音，这里结束本次连接、收尾后自动拨回同一台手机（见 [afterAttempt]）。
     * 音频回调在协议线程上：收尾要等执行器，投到主线程做（和掉流重连一样）。
     */
    private fun onAudioChannelSuspect(current: Attempt, consecutive: Int) {
        if (consecutive < AUDIO_DEAD_THRESHOLD || current.tearingDown) return
        Log.w(TAG, "连续 $consecutive 个音频会话手机都没有应答 SETUP，音频通道多半已坏：结束本次连接并重连")
        current.stopReason = StopReason.AUDIO_DEAD
        handler.post { teardown(current) }
    }

    private fun maybeFinish(current: Attempt) {
        if (current.finished || !current.tearingDown || !current.runner.isTerminated) return
        current.finished = true
        if (attempt === current) {
            attempt = null
            inputTarget = null
        }
        val cause = current.causes.result()
        current.endCause = cause
        // 自动回连（打开应用时回连、手机蓝牙连上、掉流重连）还没连上手机就结束了：静默收尾，
        // 界面上不提示失败，回到空闲继续等下一次触发；用户自己点的开始、或已经投过屏的仍照常说明原因。
        val silent = current.kind != Kind.MANUAL && !current.projected && cause !is WirelessEndCause.UserStop
        Log.i(TAG, "第 ${current.id} 次无线连接已收尾：$cause${if (silent) "（自动回连没连上手机，静默处理）" else ""}")
        apply(WirelessSignal.AttemptFinished(cause, silent))
        afterAttempt(current)
    }

    /** 一次连接收尾之后：回到空闲（更新空闲说明、退出前台）。 */
    private fun afterAttempt(current: Attempt) {
        handler.removeCallbacks(videoStallWatch)
        // 扫码等待被「手机蓝牙连上了」顶掉：收尾完接着主动回连，不回到首页等用户再点。
        val pending = pendingAfterWait
        if (pending != null) {
            pendingAfterWait = null
            Log.i(TAG, "扫码等待已停，接着主动回连手机（${pending.why}）")
            beginReconnect(pending.address, pending.why, preemptWired = false)
            return
        }
        // 掉流/音频不通重连：等上一次收尾完再拨同一台手机（不看「自动连接手机」开关，避免用户等不到画面）。
        if (current.stopReason == StopReason.VIDEO_STALL || current.stopReason == StopReason.AUDIO_DEAD) {
            val address = current.phoneAddress
            if (address != null) {
                val why = if (current.stopReason == StopReason.VIDEO_STALL) "视频中断后重连" else "音频不通后重连"
                Log.i(TAG, "$why：${MacText.mask(address)}")
                handler.postDelayed({
                    if (attempt == null) reconnectPhone(address, why)
                }, STALL_RETRY_DELAY_MS)
            }
        }
        // 热点跟着连接走：连接结束就关、复查车机 WiFi（见 [syncHotspotStandby]）。不再自动回到等待。
        lastEndByUser = current.stopReason == StopReason.USER
        current.phoneAddress?.let { triggers.onSessionEnded(it, current.projected, stoppedByUser = lastEndByUser) }
        if (current.resumeWiredAfter && !wirelessPrefs.wiredOnly && !wiredActive()) {
            Log.i(TAG, "无线连接结束，让有线回到等插线（之前为无线让出来的）")
            resumeWiredWaiting()
        }
        if (current.stopReason == StopReason.SERVICE_GONE) {
            setAutoHold(AutoHold.FOREGROUND_UNAVAILABLE)
            releaseForeground()
            return
        }
        refreshIdleStatus("上一次连接结束")
        // 用户点「停止」：回到二维码页等扫码，但不自动重连（重连要等车主点「开始连接」或扫码）。
        if (current.stopReason == StopReason.USER) {
            cancelSilentRetry()
            cancelAutoRetry()
            Log.i(TAG, "用户停止：回到二维码页，不自动重连")
            if (attempt == null && uiVisible() && !wirelessPrefs.wiredOnly) {
                begin(Kind.MANUAL)
                // begin 会清掉这个标记，这里恢复：停在二维码页，之后不再自动拨。
                userStopped = true
            }
            return
        }
        // 手机端主动断开（手机上结束投屏）：和用户点停止一样，回到二维码页等扫码，不自动重连。
        if (peerStopped(current)) {
            cancelSilentRetry()
            cancelAutoRetry()
            Log.i(TAG, "手机端主动断开：回到二维码页，不自动重连")
            if (attempt == null && uiVisible() && !wirelessPrefs.wiredOnly) {
                begin(Kind.MANUAL)
                // begin 会清掉这个标记，这里恢复：停在二维码页，之后不再自动拨。
                userStopped = true
            }
            return
        }
        // 自动回连没连上手机：不回「未开始」，直接退回二维码静默等待（手机回来或新手机扫码都能连上）。
        if (current.kind != Kind.MANUAL && !current.projected && current.stopReason != StopReason.USER) {
            beginWaitingAfterSilent()
        }
    }

    /** 这次投屏是手机端主动结束的（手机点了断开投屏）。 */
    private fun peerStopped(current: Attempt): Boolean {
        if (!current.projected) return false
        val session = current.endCause as? WirelessEndCause.Session ?: return false
        val wired = session.reason as? WirelessEndReason.Wired ?: return false
        val projection = wired.reason as? EndReason.ProjectionEnded ?: return false
        return projection.reason == HiSightEndReason.PEER_TEARDOWN
    }

    /** 自动回连没连上：已配对留在等待页，未配对才回到二维码；起不来再排静默重试。 */
    private fun beginWaitingAfterSilent() {
        if (attempt != null || userStopped) return
        if (!uiVisible()) {
            scheduleSilentRetry()
            return
        }
        Log.i(TAG, "自动回连没连上手机，显示二维码等待扫码")
        begin(Kind.MANUAL)
        if (attempt == null) scheduleSilentRetry()
    }

    /**
     * 自动回连没连上手机后，安静地排队再试一次：界面在前台才重试（后台开不了热点），
     * 没有已知手机、用户点过停止、或前置条件不满足就不排；拨不出去时按退避间隔继续等。
     */
    private fun scheduleSilentRetry() {
        if (silentRetry != null) return
        if (!wirelessPrefs.connectOnBluetooth) return
        if (wirelessPrefs.lastPhone == null) return
        if (!uiVisible()) return
        val delay = silentRetryDelayMs
        Log.i(TAG, "自动回连没连上手机：${delay / 1000} 秒后再静默重试（界面留在首页等手机）")
        val retry = object : Runnable {
            override fun run() {
                silentRetry = null
                if (attempt != null) return
                val last = wirelessPrefs.lastPhone ?: return
                if (wirelessPrefs.wiredOnly) return
                if (!wirelessPrefs.connectOnBluetooth) {
                    Log.i(TAG, "「自动连接手机」关掉了，静默重试停止")
                    return
                }
                if (!uiVisible()) {
                    Log.i(TAG, "界面不在前台，静默重试先停下；回到界面或手机蓝牙再连上时接着来")
                    return
                }
                val hold = ReconnectPolicy.hold(reconnectConditions(forDial = true))
                if (hold != AutoHold.READY) {
                    Log.i(TAG, "静默重试暂停：$hold")
                    return
                }
                beginReconnect(last, "静默重试", preemptWired = false, uiOpening = true)
                if (attempt == null) {
                    // 这次还是没拨成（读不到设备名等）：退避后再试，不提示失败。
                    silentRetryDelayMs = (silentRetryDelayMs * 2).coerceAtMost(SILENT_RETRY_MAX_MS)
                    scheduleSilentRetry()
                }
            }
        }
        silentRetry = retry
        handler.postDelayed(retry, delay)
    }

    /** 停掉排着的静默重试（用户开始/停止、真开始一次连接时）。 */
    private fun cancelSilentRetry() {
        silentRetry?.let { handler.removeCallbacks(it) }
        silentRetry = null
    }

    // ---------------------------------------------------------------- 前台服务

    private fun requestForeground(): Boolean {
        if (wantsForeground) return true
        wantsForeground = true
        return try {
            ContextCompat.startForegroundService(app, Intent(app, WirelessService::class.java))
            true
        } catch (e: RuntimeException) {
            // 例如安卓 12 起应用在后台时不能起前台服务。
            Log.e(TAG, "起不了前台服务", e)
            wantsForeground = false
            false
        }
    }

    private fun releaseForeground() {
        if (!wantsForeground) return
        wantsForeground = false
        notifyHost()
    }

    private fun notifyHost() {
        mainHandler.post { for (listener in hostListeners) listener.onControllerChanged() }
    }

    // ---------------------------------------------------------------- 工具

    private fun apply(signal: WirelessSignal) {
        val before = mutableState.value
        val after = WirelessReducer.reduce(before, signal)
        if (after == before) return
        mutableState.value = after
        // 回控刚就绪：清一次本地触摸状态，就绪前按下的手指不会接着发 MOVE。
        if (after.inputReady && !before.inputReady) mutableTouchResets.update { it + 1 }
        if (after.phase != before.phase || after.step != before.step) {
            Log.i(TAG, "状态 ${before.phase}/${before.step} → ${after.phase}/${after.step}")
            notifyHost()
        }
        // 投屏中盯着视频流：手机停发时先提示、再重连。
        if (after.phase == WirelessPhase.PROJECTING && before.phase != WirelessPhase.PROJECTING) {
            stallAnnounced = false
            handler.removeCallbacks(videoStallWatch)
            handler.postDelayed(videoStallWatch, STALL_CHECK_MS)
        } else if (after.phase != WirelessPhase.PROJECTING) {
            handler.removeCallbacks(videoStallWatch)
        }
    }

    private fun post(block: () -> Unit) {
        handler.post {
            block()
            syncHotspotStandby()
        }
    }

    /**
     * 按现在的情况开着或关掉留着的热点。有连接在进行就开着（车机主动回连要等连上手机的蓝牙控制通道）；连接结束
     * （或切到仅有线、有线接手）就关掉，「退出后恢复车机 WiFi」开着时复查车机 WiFi（下一次又开热点时作废）。
     */
    private fun syncHotspotStandby() {
        val current = attempt
        // 车机主动回连要等车机连上手机的蓝牙控制通道才开：手机不在旁边时不顶掉车机自己的 WiFi。
        // 建直连组的连接不开热点（开热点会停掉车机 WiFi，组就建不起来了）。
        val wanted = current != null && !current.directGroup && (current.kind != Kind.RECONNECT || current.phoneSeen) &&
            !wirelessPrefs.wiredOnly && !wiredActive()
        if (wanted == hotspotStandby) return
        hotspotStandby = wanted
        if (wanted) {
            warmHotspot()
        } else {
            LocalHotspotOwner.existing()?.coolDown()
            wifiRestore.onConnectionOver(userStopped = lastEndByUser)
        }
    }

    /** 申请把热点开好留着；之前排的「恢复车机 WiFi」复查作废（WiFi 马上又会被热点顶掉）。 */
    private fun warmHotspot() {
        wifiRestore.onHotspotRequested()
        LocalHotspotOwner.get(app).keepWarm { Log.i(TAG, "热点：$it") }
    }

    private companion object {
        /** 同一台手机两次车机主动回连之间至少隔这么久。 */
        const val RECONNECT_COOLDOWN_MS = 60_000L

        /** 自动回连没连上手机后，静默重试的起始间隔；拨不出去时翻倍，最长 [SILENT_RETRY_MAX_MS]。 */
        const val SILENT_RETRY_MIN_MS = 10_000L
        const val SILENT_RETRY_MAX_MS = 60_000L

        /** 车机主动回连时界面不在前台：带到前台后等这么久再拨（界面一般 1 秒内出来；出不来也照拨）。 */
        const val SURFACE_WAIT_MS = 1_500L

        const val TAG = "OpenHC-Wireless"

        /** 与有线共用同一份配对记录：同一台手机有线、无线配一次就行。 */
        const val TRUST_DIR = "trust"

        /** 声音日志里的连接编号：无线的从 1001 起，和有线的 1、2、3… 分开。 */
        const val AUDIO_ID_BASE = 1000

        const val WATCH_SLICE_MS = 60_000L

        /** 轮询已知手机经典蓝牙链路状态的间隔（兜底 ACL_CONNECTED 广播收不到的车机）。 */
        const val LINK_WATCH_INTERVAL_MS = 8_000L

        /** 打开应用后先让二维码画出来，再过这么久看手机蓝牙是否连着、要不要主动拨。 */
        const val LAUNCH_DIAL_DELAY_MS = 600L

        /**
         * 无线连接向手机声明的码率上限（bit/s）：2.4GHz 热点的实际吞吐撑不住「高/最高」的 12/15 Mbps，
         * 突发帧在链路和解码入口排队，上屏延迟 200-400ms。钳到这里换来滑动跟手。
         */
        const val WIRELESS_MAX_BITRATE = 6_000_000

        const val FOREGROUND_FAILED = "前台服务启动失败"

        /** HiCar 应用元数据：514 查询能力。实测 module 1 会回 514 应答（MEDIA=true），手机随后推 515。 */
        const val HICAR_TYPE_METADATA_ABILITY = 514
        val HICAR_METADATA_MODULES = longArrayOf(1L)

        /** HiCar 前后台切换（dataType 532）：回到投屏画面时请手机把 HiCar 切回前台。 */
        const val HICAR_TYPE_PROJECT_ACTION = 532
        val PROJECT_ACTION_FOREGROUND = "{\"ProjectAction\":{\"Background\":false}}".toByteArray(Charsets.UTF_8)

        /** 514 查询的重试节奏（毫秒）：连上后立即发一次，手机没接就隔 2s、5s 再补。 */
        val HICAR_METADATA_DELAYS_MS = longArrayOf(0L, 2_000L, 5_000L)

        /** 514 请求正文：按文档带上 RequestCaller（当前车型 modelId），手机按车型记录授权。 */
        internal fun hicarMetadataRequest(modelId: String): ByteArray =
            """{"RequestAAData":7,"RequestCaller":"$modelId"}""".toByteArray(Charsets.UTF_8)

        /** 临时排查开关：把会话诊断事件也写成 Info 日志（这台 Flyme 关了 D 级日志）。查完改回 false。 */
        private const val LOG_SESSION_DIAGNOSTICS = true
    }
}
