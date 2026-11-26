package io.github.gcbond.openhc.diagnostics

/** 诊断日志文件的组成（纯逻辑，JVM 单元测试覆盖）：文件名、开头的说明和摘要、各段的标题。 */
internal object DiagnosticsReport {
    /** 存到「下载」里的子文件夹名。 */
    const val FOLDER = "OpenHC"

    /** [timestamp] 形如 `20261122-201530`。 */
    fun fileName(timestamp: String): String = "openhc-diagnostics-$timestamp.txt"

    /** 文件开头：一段说明，再一行一项的摘要（项目：值）。 */
    fun header(facts: List<Pair<String, String>>): String = buildString {
        append("OpenHC 诊断日志\n")
        append("只有 OpenHC 自己的运行记录：不含连接密钥和 WiFi 密码，手机、车机的蓝牙和网卡地址只留首尾几位。\n")
        append("报告问题时把整个文件附上即可。\n\n")
        for ((name, value) in facts) append(name).append("：").append(value.ifBlank { "（空）" }).append('\n')
    }

    fun section(title: String): String = "\n===== $title =====\n"

    /**
     * 导出前逐行处理：完整的蓝牙 / 网卡地址只留首尾几位（和 OpenHC 自己打日志时的写法一样）。OpenHC 自己本来就不打完整地址，
     * 这里兜住系统在本进程里打的日志（各家车机系统打什么不一定）。占位地址（全 0、02:00:00:00:00:00、全 f）不认人，原样留着。
     */
    fun redact(line: String): String = MAC.replace(line) { m ->
        val mac = m.value
        if (mac.lowercase() in KEEP) mac else mac.substring(0, 2) + ":**:**:**:" + mac.substring(12)
    }

    /**
     * 这一行（`logcat -v threadtime` 格式）是不是 [pid] 这个进程打的。不是日志行的（例如「--------- beginning of main」）算是。
     * 安卓 7.0 以下 logcat 不能按进程号过滤，靠这个保证只记本进程的日志（不依赖系统只给应用看自己日志的限制）。
     */
    fun fromProcess(line: String, pid: String): Boolean {
        val m = THREADTIME_PID.find(line) ?: return true
        return m.groupValues[1] == pid
    }

    private val THREADTIME_PID = Regex("^\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d+\\s+(\\d+)\\s+\\d+\\s")

    /** 冒号分隔的 6 段地址；前后紧挨着别的十六进制段的不算（例如更长的 IPv6 地址）。 */
    private val MAC = Regex("(?<![0-9A-Fa-f])(?<![0-9A-Fa-f]:)[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5}(?!:?[0-9A-Fa-f])")

    private val KEEP = setOf("00:00:00:00:00:00", "02:00:00:00:00:00", "ff:ff:ff:ff:ff:ff")
}
