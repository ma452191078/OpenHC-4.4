package io.github.gcbond.openhc.btprobe

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 检测状态放在进程里而不是 Activity 里：界面重建不丢结果，也不打断正在进行的检测。
 * 界面状态只在主线程上改；检测线程通过 Handler 把结果送过来。
 */
object ProbeSession {

    const val TAG = "OpenHC-BtProbe"
    const val WAIT_SECONDS = 60

    val items = mutableStateListOf<ProbeItem>()

    var running by mutableStateOf(false)
        private set
    var waitingSecondsLeft by mutableIntStateOf(0)
        private set
    var waitForConnections by mutableStateOf(true)
    var permissionHint by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)

    private var startedAtMillis = 0L

    @Volatile
    private var stopWaitingRequested = false

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 在主线程上调用。正在检测时忽略。 */
    fun start(context: Context) {
        if (running) return
        items.clear()
        notice = null
        running = true
        waitingSecondsLeft = 0
        stopWaitingRequested = false
        startedAtMillis = System.currentTimeMillis()
        val appContext = context.applicationContext
        val wait = waitForConnections
        Log.i(TAG, "开始检测：风味 ${BuildConfig.FLAVOR}，targetSdk ${appContext.applicationInfo.targetSdkVersion}")
        thread(name = "btprobe") {
            val probe = BtProbe(
                appContext,
                emit = { item ->
                    log(item)
                    mainHandler.post { items.add(item) }
                },
                onWaiting = { left -> mainHandler.post { waitingSecondsLeft = left } },
                stopWaitingRequested = { stopWaitingRequested },
            )
            try {
                probe.run(wait, WAIT_SECONDS)
            } finally {
                Log.i(TAG, "检测结束")
                mainHandler.post {
                    running = false
                    waitingSecondsLeft = 0
                }
            }
        }
    }

    fun stopWaiting() {
        stopWaitingRequested = true
    }

    /** 复制、存文件用的全文；地址已打码。在主线程上调用。 */
    fun exportText(context: Context): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(startedAtMillis))
        val header = listOf(
            "OpenHC 蓝牙探针 ${BuildConfig.VERSION_NAME}（风味 ${BuildConfig.FLAVOR}，" +
                "targetSdk ${context.applicationInfo.targetSdkVersion}）",
            "检测时间：$time",
            "说明：地址只留第一段和最后两段，屏幕上是完整值。结果含本机设备信息，不要提交进仓库，也不要原样贴到公开的地方。",
        )
        return ProbeText.formatReport(header, items.toList())
    }

    private fun log(item: ProbeItem) {
        val line = "【${item.section}】" + ProbeText.exportLine(item)
        when (item.status) {
            Status.ERROR -> Log.e(TAG, line)
            Status.FAIL -> Log.w(TAG, line)
            Status.OK, Status.SKIP -> Log.i(TAG, line)
        }
    }
}
