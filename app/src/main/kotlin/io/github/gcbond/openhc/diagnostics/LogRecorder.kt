package io.github.gcbond.openhc.diagnostics

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Process
import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把本应用自己的日志一直记进私有目录（「导出诊断日志」用）。车机的日志缓冲常常只有 256 KB、全车共用，
 * 事后再读早就被冲掉了，所以要边跑边记。
 *
 * - 起一个 `logcat` 子进程：普通应用本来就只读得到自己的日志，安卓 7.0 起再按本进程号过滤一遍；
 *   逐行写进 [CURRENT]，满 [MAX_FILE_BYTES] 就换成 [PREVIOUS]（旧的删掉），最多两份。
 * - 启动时把系统崩溃缓冲里本应用的记录（上次崩溃的堆栈）存进 [CRASH]。
 * - `logcat` 子进程意外退出时隔几秒重启，最多 [MAX_RESTARTS] 次；起不来就不记（不影响别的功能）。
 *
 * 线程：自己的后台线程写文件；[flush] 和读文件的方法可在任意线程调。
 */
internal object LogRecorder {
    private const val TAG = "OpenHC-Diag"
    const val DIR = "diagnostics"
    const val CURRENT = "log.txt"
    const val PREVIOUS = "log.1.txt"
    const val CRASH = "crash.txt"
    const val MAX_FILE_BYTES = 2L * 1024 * 1024
    private const val MAX_CRASH_BYTES = 256 * 1024
    private const val MAX_RESTARTS = 20
    private const val RESTART_DELAY_MS = 5_000L

    private val lock = Any()

    @Volatile
    private var dir: File? = null
    private var writer: BufferedWriter? = null
    private var written = 0L

    /** 开始记（应用启动时调一次；再调不做什么）。 */
    fun start(context: Context) {
        synchronized(lock) {
            if (dir != null) return
            dir = logFolder(context)
        }
        val thread = Thread({ run() }, "OpenHC-LogRecorder")
        thread.isDaemon = true
        thread.start()
    }

    /**
     * 公共「下载/OpenHC」文件夹（车主在文件管理器里直接能看到）。只在安卓 5.0 及以下用：那时写公共目录只要清单里的
     * WRITE_EXTERNAL_STORAGE，不用运行时申请。6.0 起要运行时权限，这里不处理，返回 null，走应用自己的外部目录。
     */
    internal fun downloadsFolder(): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) return null
        return try {
            val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DiagnosticsReport.FOLDER)
            if ((folder.isDirectory || folder.mkdirs()) && folder.canWrite()) folder else null
        } catch (e: RuntimeException) {
            null
        }
    }

    /** 日志放的文件夹：能写公共「下载/OpenHC」就放那里，否则放应用自己的外部目录（再不行用内部目录）。 */
    private fun logFolder(context: Context): File {
        downloadsFolder()?.let { return it }
        val external = context.getExternalFilesDir(null)?.let { File(it, DIR) }
        if (external != null && (external.isDirectory || external.mkdirs())) return external
        return File(context.filesDir, DIR).apply { mkdirs() }
    }

    /** 现有的日志文件，旧的在前。 */
    fun logFiles(): List<File> {
        val d = dir ?: return emptyList()
        return listOf(File(d, PREVIOUS), File(d, CURRENT)).filter { it.isFile }
    }

    /** 上次启动时读到的崩溃记录文件（没有就 null）。 */
    fun crashFile(): File? = dir?.let { File(it, CRASH) }?.takeIf { it.isFile && it.length() > 0 }

    /** 把还在缓冲里的几行写进文件（导出前调）。 */
    fun flush() {
        synchronized(lock) {
            try {
                writer?.flush()
            } catch (e: IOException) {
                // 写不进去就算了，导出的是已经写进去的部分。
            }
        }
    }

    private fun run() {
        saveCrashBuffer()
        synchronized(lock) { openWriter() }
        mark("进程 ${Process.myPid()} 开始记录")
        var restarts = 0
        while (true) {
            val why = pump(tailOnly = restarts > 0)
            if (restarts >= MAX_RESTARTS) {
                mark("logcat 退出了（$why），已经重启 $MAX_RESTARTS 次，不再记")
                return
            }
            restarts++
            mark("logcat 退出了（$why），${RESTART_DELAY_MS / 1000} 秒后重启")
            try {
                Thread.sleep(RESTART_DELAY_MS)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    /** 跑一个 `logcat` 直到它退出，返回退出原因。[tailOnly]：重启时只接着记新日志，不把缓冲里的旧行再记一遍。 */
    private fun pump(tailOnly: Boolean): String {
        val command = mutableListOf("logcat", "-v", "threadtime")
        // 4.4 fork：-T 选项是安卓 5 起 logcat 才认；更早的系统带上它 logcat 起不来（会反复重启后放弃记录），
        // 5 以下重启时不带它，把缓冲里还没冲掉的旧行再记一遍（只是重复，无害）。
        if (tailOnly && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) command += listOf("-T", "1")
        val ownPid = Process.myPid().toString()
        // 安卓 7.0 起 logcat 自己按进程号过滤；更早的系统逐行看进程号（[DiagnosticsReport.fromProcess]）。
        val filterLines = Build.VERSION.SDK_INT < Build.VERSION_CODES.N
        if (!filterLines) command += "--pid=$ownPid"
        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (e: IOException) {
            Log.w(TAG, "起不来 logcat，诊断日志不记：${e.message}")
            return "起不来：${e.message}"
        }
        try {
            BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (filterLines && !DiagnosticsReport.fromProcess(line, ownPid)) continue
                    write(line)
                }
            }
        } catch (e: IOException) {
            return "读失败：${e.message}"
        } finally {
            process.destroy()
        }
        return "输出结束"
    }

    private fun write(line: String) {
        synchronized(lock) {
            val w = writer ?: return
            try {
                w.write(line)
                w.write("\n")
                w.flush()
                written += line.toByteArray(Charsets.UTF_8).size + 1L
                if (written >= MAX_FILE_BYTES) rotate()
            } catch (e: IOException) {
                // 写满或者文件出问题：丢这一行，下一行再试。
            }
        }
    }

    private fun mark(text: String) {
        val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        write("===== $time OpenHC 诊断记录：$text =====")
    }

    /** 调用时持有 [lock]。 */
    private fun openWriter() {
        val d = dir ?: return
        val file = File(d, CURRENT)
        written = if (file.isFile) file.length() else 0L
        if (written >= MAX_FILE_BYTES) return rotate()
        writer = try {
            BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8))
        } catch (e: IOException) {
            Log.w(TAG, "打不开诊断日志文件：${e.message}")
            null
        }
    }

    /** 调用时持有 [lock]。 */
    private fun rotate() {
        val d = dir ?: return
        try {
            writer?.close()
        } catch (e: IOException) {
            // 关不掉也接着换。
        }
        writer = null
        File(d, PREVIOUS).delete()
        File(d, CURRENT).renameTo(File(d, PREVIOUS))
        written = 0L
        writer = try {
            BufferedWriter(OutputStreamWriter(FileOutputStream(File(d, CURRENT), false), Charsets.UTF_8))
        } catch (e: IOException) {
            null
        }
    }

    /** 系统崩溃缓冲里本应用的记录（普通应用只读得到自己的）存进 [CRASH]，有上限。 */
    private fun saveCrashBuffer() {
        val d = dir ?: return
        val out = StringBuilder()
        try {
            val process = ProcessBuilder("logcat", "-d", "-b", "crash", "-v", "threadtime").redirectErrorStream(true).start()
            try {
                BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                    while (out.length < MAX_CRASH_BYTES) {
                        val line = reader.readLine() ?: break
                        out.append(line).append('\n')
                    }
                }
            } finally {
                process.destroy()
            }
        } catch (e: IOException) {
            return
        }
        val text = out.toString().trim()
        val file = File(d, CRASH)
        if (text.isEmpty() || text.startsWith("logcat:")) {
            file.delete()
            return
        }
        try {
            val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
            file.writeText("（$time 应用启动时读到的系统崩溃记录）\n$text\n", Charsets.UTF_8)
        } catch (e: IOException) {
            // 存不下就算了。
        }
    }
}
