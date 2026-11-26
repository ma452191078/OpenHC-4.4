package io.github.gcbond.openhc.btprobe

/**
 * 探针用到的纯文字处理：解析通道号、判断和打码蓝牙地址、拼导出文本。
 * 不引用安卓类，可以在电脑上直接跑单元测试。
 */
object ProbeText {

    /** RFCOMM 服务端通道号只有 1～30 有效。 */
    val RFCOMM_CHANNELS: IntRange = 1..30

    /** 系统给没有本机地址权限的应用返回的占位地址（安卓 6.0 起）。 */
    const val PLACEHOLDER_ADDRESS = "02:00:00:00:00:00"

    private const val ZERO_ADDRESS = "00:00:00:00:00:00"

    private val TO_STRING_CHANNEL = Regex("""Channel:\s*(-?\d+)""")

    // 六段十六进制，冒号或短横线分隔，前后分隔符一致。
    private val SEPARATED = Regex("""^[0-9A-Fa-f]{2}([:-])[0-9A-Fa-f]{2}(?:\1[0-9A-Fa-f]{2}){4}$""")

    // 12 位十六进制连写。
    private val BARE = Regex("""^[0-9A-Fa-f]{12}$""")

    // 自由文字里的分隔写法地址；前后不能紧挨十六进制字符或分隔符，免得误伤更长的串。
    private val SEPARATED_IN_TEXT =
        Regex("""(?<![0-9A-Fa-f:-])[0-9A-Fa-f]{2}([:-])[0-9A-Fa-f]{2}(?:\1[0-9A-Fa-f]{2}){4}(?![0-9A-Fa-f:-])""")

    enum class AddressKind {
        /** null、空串或只有空白。 */
        MISSING,

        /** 不是蓝牙地址的写法。 */
        MALFORMED,

        /** 系统给普通应用的占位值。 */
        PLACEHOLDER,

        /** 全零，无效。 */
        ALL_ZERO,

        /** 格式正确、不是已知的无效值，看起来是真实地址（是否真是本机地址还要和系统记录核对）。 */
        PLAUSIBLE,
    }

    /** 从 `BluetoothServerSocket.toString()` 的输出里取「Channel: N」。取不到返回 null。 */
    fun channelFromToString(text: String?): Int? =
        text?.let { TO_STRING_CHANNEL.find(it) }?.groupValues?.get(1)?.toIntOrNull()

    fun isValidChannel(channel: Int?): Boolean = channel != null && channel in RFCOMM_CHANNELS

    /** 把冒号、短横线分隔或 12 位连写的地址统一成大写、冒号分隔；不是地址写法返回 null。 */
    fun normalizeAddress(raw: String?): String? {
        val s = raw?.trim() ?: return null
        val hex = when {
            SEPARATED.matches(s) -> s.filter { it != ':' && it != '-' }
            BARE.matches(s) -> s
            else -> return null
        }
        return hex.uppercase().chunked(2).joinToString(":")
    }

    fun classifyAddress(raw: String?): AddressKind {
        if (raw.isNullOrBlank()) return AddressKind.MISSING
        return when (normalizeAddress(raw)) {
            null -> AddressKind.MALFORMED
            PLACEHOLDER_ADDRESS -> AddressKind.PLACEHOLDER
            ZERO_ADDRESS -> AddressKind.ALL_ZERO
            else -> AddressKind.PLAUSIBLE
        }
    }

    /**
     * 给单个地址打码：只留第一段和最后两段，保持原来的写法。
     * 占位值、全零和不是地址的串原样返回（它们不是设备标识，留着才看得出是哪种失败）。
     */
    fun maskAddress(raw: String): String {
        if (classifyAddress(raw) != AddressKind.PLAUSIBLE) return raw
        val s = raw.trim()
        if (BARE.matches(s)) return s.take(2) + "******" + s.takeLast(4)
        val separator = s[2]
        val parts = s.split(separator)
        return listOf(parts[0], "**", "**", "**", parts[4], parts[5]).joinToString(separator.toString())
    }

    /** 把自由文字（例如异常信息）里所有分隔写法的地址打码，作为导出前的兜底。 */
    fun maskAddresses(text: String): String = SEPARATED_IN_TEXT.replace(text) { maskAddress(it.value) }

    /** 一条结果的导出形式（复制、存文件、logcat 共用）：用 [ProbeItem.exportValue]，多行的值缩进对齐。 */
    fun exportLine(item: ProbeItem): String {
        val head = "[${item.status.label}] ${item.name}"
        val value = item.exportValue
        val line = if (value.isEmpty()) head else head + "：" + value.replace("\n", "\n    ")
        return maskAddresses(line)
    }

    /** 拼成可复制、可存文件的纯文本：先是 [header]，然后按分组列出每一项。 */
    fun formatReport(header: List<String>, items: List<ProbeItem>): String = buildString {
        for (line in header) append(maskAddresses(line)).append('\n')
        var section: String? = null
        for (item in items) {
            if (item.section != section) {
                section = item.section
                append('\n').append("== ").append(section).append(" ==\n")
            }
            append(exportLine(item)).append('\n')
        }
    }
}
