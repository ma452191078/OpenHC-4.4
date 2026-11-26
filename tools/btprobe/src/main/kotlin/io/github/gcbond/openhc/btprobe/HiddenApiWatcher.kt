package io.github.gcbond.openhc.btprobe

import android.bluetooth.BluetoothSocket
import android.os.Build
import android.os.Process
import android.os.StrictMode
import android.os.strictmode.NonSdkApiUsedViolation
import java.io.IOException
import java.util.concurrent.Executor
import kotlin.concurrent.thread

/**
 * 在应用内收集「隐藏接口访问」的证据，用来区分反射失败是被系统拦下，还是这个版本根本没有该成员。
 *
 * 安卓 9 起，打开 StrictMode 的非 SDK 接口检测后，每次访问隐藏接口系统都会回调一次，内容是成员签名；
 * 按设计，拦下的访问也会上报，但这一点未经真机验证。这里用同步执行的 Executor，回调就在发起访问的线程上、
 * 访问返回之前执行，所以能按线程和先后顺序精确归到某一次尝试。回调不说明放行还是拦下：这看反射本身成没成功；
 * 成员落在哪个名单（决定哪些 targetSdk 放行），看 logcat 的告警行。监听到底有没有生效，由 [calibrate] 自检。
 *
 * 只是打开系统自带的检测并记录结果，不改变系统放行或拦下的判断。
 */
object HiddenApiWatcher {

    private class Hit(val seq: Long, val thread: Thread, val signature: String)

    private const val MAX_HITS = 500
    private const val MAX_LOG_LINES = 100
    private const val LOGCAT_TIMEOUT_MS = 5_000L

    private val lock = Any()
    private val hits = ArrayList<Hit>()
    private var seq = 0L

    @Volatile
    var installed = false
        private set

    /** 自检结论：null 表示没做或不适用；true 表示对已知隐藏成员的访问被记到了；false 表示没记到。 */
    @Volatile
    var calibrated: Boolean? = null
        private set

    /** 打开检测。安卓 9 以下没有隐藏接口限制，返回 false。 */
    fun install(): Boolean {
        if (installed) return true
        if (Build.VERSION.SDK_INT < 28) return false
        return try {
            val policy = StrictMode.VmPolicy.Builder()
                .detectNonSdkApiUsage()
                .penaltyListener(Executor { it.run() }) { violation ->
                    if (violation is NonSdkApiUsedViolation) record(violation.message ?: violation.toString())
                }
                .build()
            StrictMode.setVmPolicy(policy)
            installed = true
            true
        } catch (e: RuntimeException) {
            false
        }
    }

    /** 自检结果：查没查到那个成员，以及监听有没有记到这次访问。 */
    class SelfCheck(val lookupSucceeded: Boolean, val reported: Boolean)

    /**
     * 自检：在当前线程上查一个已知的隐藏成员（只查不读），看监听有没有记到这次访问。
     * 选蓝牙 socket 内部的 mSocket 字段：安卓 9 起各版本都在放行名单里，探针别处不用它，不干扰后面的记录。
     * 没装上监听时返回 null。
     */
    fun calibrate(): SelfCheck? {
        if (!installed) return null
        val mark = mark()
        val lookupSucceeded = try {
            BluetoothSocket::class.java.getDeclaredField("mSocket")
            true
        } catch (e: NoSuchFieldException) {
            false
        }
        val reported = hitsSince(mark).isNotEmpty()
        calibrated = reported
        return SelfCheck(lookupSucceeded, reported)
    }

    /** 当前的序号；配合 [hitsSince] 取某段时间内本线程上的访问。 */
    fun mark(): Long = synchronized(lock) { seq }

    /** [mark] 之后、在当前线程上报出的隐藏接口访问（成员签名，去重）。 */
    fun hitsSince(mark: Long): List<String> {
        val current = Thread.currentThread()
        return synchronized(lock) {
            hits.filter { it.seq > mark && it.thread === current }.map { it.signature }.distinct()
        }
    }

    private fun record(signature: String) {
        synchronized(lock) {
            seq++
            hits.add(Hit(seq, Thread.currentThread(), signature))
            if (hits.size > MAX_HITS) hits.removeAt(0)
        }
    }

    /**
     * 读本进程自己的 logcat（应用读自己的日志不需要额外权限），挑出与蓝牙、系统属性有关的隐藏接口告警。
     * 告警行末尾的括号里写着成员所在的名单，以及这次是放行（allowed）还是拦下（denied）。
     * 一行都读不到（例如系统不让应用读日志）返回 null；安卓 9 以下没有这种告警，返回空列表。
     */
    fun readLogcatWarnings(): List<String>? {
        if (Build.VERSION.SDK_INT < 28) return emptyList()
        val process = try {
            ProcessBuilder("logcat", "-d", "-v", "time", "--pid=${Process.myPid()}")
                .redirectErrorStream(true)
                .start()
        } catch (e: IOException) {
            return null
        } catch (e: SecurityException) {
            return null
        }
        val found = LinkedHashSet<String>()
        var total = 0
        val reader = thread(name = "btprobe-logcat") {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        synchronized(found) {
                            total++
                            if (isRelevantWarning(line)) found.add(line.substringAfter("): ", line).trim())
                        }
                    }
                }
            } catch (e: IOException) {
                // 超时后 destroy 会打断读取，读到多少算多少。
            }
        }
        try {
            reader.join(LOGCAT_TIMEOUT_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        process.destroy()
        return synchronized(found) {
            if (total == 0) null else found.toList().takeLast(MAX_LOG_LINES)
        }
    }

    private fun isRelevantWarning(line: String): Boolean =
        line.contains("Accessing hidden") &&
            (line.contains("Landroid/bluetooth/") || line.contains("Landroid/os/SystemProperties"))
}
