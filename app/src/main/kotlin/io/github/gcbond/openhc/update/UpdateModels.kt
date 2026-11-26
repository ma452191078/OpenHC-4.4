package io.github.gcbond.openhc.update

/** 一条可用于升级的发行版（来自 GitHub Releases）。 */
data class ReleaseInfo(
    /** 纯数字版本号，如 0.0.2（由 tag 去掉 v 前缀得到）。 */
    val version: String,
    /** 原始 tag，如 v0.0.2；下载地址要用它。 */
    val tag: String,
    /** 发行说明正文，弹窗里给车主看。 */
    val notes: String,
    /** 安装包下载地址。 */
    val apkUrl: String,
    /** GitHub 接口的 asset 下载地址（api.github.com，302 到 objects.githubusercontent.com）；拿不到 id 时为 null。 */
    val assetApiUrl: String? = null,
    /** 发行页给出的 SHA256（64 位小写十六进制）；null 表示没提供，按主流程规则不予安装。 */
    val sha256: String?,
    /** 安装包大小（字节）；0 表示未知。 */
    val sizeBytes: Long,
)

/** 更新流程所处的阶段。 */
enum class UpdatePhase {
    /** 还没检查过。 */
    IDLE,
    CHECKING,
    UP_TO_DATE,
    /** 发现比本机新的版本。 */
    AVAILABLE,
    DOWNLOADING,
    /** 下载完，正在算 SHA256。 */
    VERIFYING,
    /** 校验通过，已把安装包交给系统安装器。 */
    INSTALLING,
    FAILED,
}

/** 更新流程对外暴露的状态（主线程回调）。 */
data class UpdateUiState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val release: ReleaseInfo? = null,
    /** 下载进度 0..100。 */
    val progress: Int = 0,
    /** 失败/提示文案（字符串资源）；null 表示没有要说的。 */
    val messageRes: Int? = null,
    /** 补充细节，如 HTTP 状态码、异常类名；拼在文案后面的括号里。 */
    val detail: String? = null,
    /** 失败原因是缺少「安装未知应用」授权时，界面给一个去授权的入口。 */
    val needsInstallPermission: Boolean = false,
)

/** 版本号比较、SHA256 解析等纯逻辑（不碰安卓 API，单测直接跑）。 */
internal object UpdateVersions {

    private val TAG_PATTERN = Regex("^[vV]?(\\d+(?:\\.\\d+){0,3})$")

    /** 「sha256: xxx」「SHA256 校验值：xxx」都能认出来；只认 64 位十六进制。 */
    private val SHA_PATTERN = Regex("(?i)sha-?256[^0-9a-f]{0,16}([0-9a-f]{64})")

    /** tag → 纯数字版本号；不是 v1.2.3 这种形式就返回 null（跳过该发行版）。 */
    fun fromTag(tag: String): String? = TAG_PATTERN.matchEntire(tag.trim())?.groupValues?.get(1)

    /** 逐段按数字比较；段数不同时缺的段按 0 算（0.1 与 0.1.0 相等）。 */
    fun compare(a: String, b: String): Int {
        val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }

    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    /** 约定好的安装包名：openhc-android4.4-<版本>.apk。 */
    fun assetName(version: String): String = "openhc-android4.4-$version.apk"

    /** 约定的下载地址（接口里没带 asset 地址时用它拼）。 */
    fun downloadUrl(tag: String, version: String): String =
        "https://github.com/$REPO/releases/download/$tag/${assetName(version)}"

    /** 走 GitHub 接口的 asset 下载地址：落到 api.github.com，再 302 到 objects.githubusercontent.com。 */
    fun assetApiUrl(assetId: Long): String =
        "https://api.github.com/repos/$REPO/releases/assets/$assetId"

    /** 从发行说明里取 SHA256；取不到返回 null。 */
    fun parseSha256(text: String): String? =
        SHA_PATTERN.find(text)?.groupValues?.get(1)?.lowercase()

    private const val REPO = "ma452191078/OpenHC-4.4"

    /** 发行版列表接口。 */
    const val RELEASES_API = "https://api.github.com/repos/$REPO/releases?per_page=20"

    /** 发行版页面（手动检查失败时给车主看的地址）。 */
    const val RELEASES_PAGE = "https://github.com/$REPO/releases"
}

