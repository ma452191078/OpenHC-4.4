package io.github.gcbond.openhc.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import io.github.gcbond.openhc.BuildConfig
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.net.CarHttps
import io.github.gcbond.openhc.settings.UpdatePreferences
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 版本更新：从 GitHub Releases 取发行版列表，比版本号；下载安装包后按发行页给出的 SHA256 校验，再交给系统安装器。
 *
 * 没有专用服务器，发布流程就是在本仓库的 Releases 页发版：
 * tag 用 v<版本>（如 v0.0.2），上传 openhc-android4.4-<版本>.apk，并在发行说明里写一行 SHA256。
 *
 * 证书策略：优先用「系统 + 内置根证书」严格校验；校验失败（GitHub 换 CA、根证书过期等）自动降级为
 * 不校验证书，但下载后强制校验 SHA256 与「安装包签名＝本应用签名」，因此 CA 变更不会让更新功能失效，
 * 也不会让第三方安装包蒙混过关（系统本身也只允许同签名覆盖安装）。
 *
 * 网络请求都在单独线程跑，状态经 [addListener] 回到主线程。
 */
class UpdateManager(context: Context) {

    private val appContext = context.applicationContext
    private val settings = UpdatePreferences(appContext)
    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "OpenHC-Update").apply { isDaemon = true }
    }
    private val listeners = CopyOnWriteArrayList<(UpdateUiState) -> Unit>()
    private val checking = AtomicBoolean(false)

    @Volatile
    private var current = UpdateUiState()

    @Volatile
    private var cancelRequested = false

    @Volatile
    private var activeConnection: HttpURLConnection? = null

    /** 证书校验失败过一次以后，本进程内直接用不校验证书的方式，避免每次都要先失败一遍。 */
    @Volatile
    private var strictTlsFailed = false

    val state: UpdateUiState get() = current

    fun addListener(listener: (UpdateUiState) -> Unit) {
        listeners += listener
        listener(current)
    }

    fun removeListener(listener: (UpdateUiState) -> Unit) {
        listeners -= listener
    }

    /** 打开应用时的自动检查：设置里关着就什么都不做。 */
    fun autoCheck() {
        if (settings.autoCheck) check()
    }

    /** 检查更新（手动检查也走这里）。已在检查/下载时不重复发起。 */
    fun check() {
        val phase = current.phase
        if (phase == UpdatePhase.DOWNLOADING || phase == UpdatePhase.VERIFYING) return
        if (!checking.compareAndSet(false, true)) return
        publish(current.copy(phase = UpdatePhase.CHECKING, messageRes = null, detail = null, needsInstallPermission = false))
        worker.execute {
            val next = try {
                fetchLatest()
            } catch (t: Throwable) {
                Log.w(TAG, "检查更新失败：${t.javaClass.simpleName} ${t.message}")
                failed(R.string.update_failed_network, t.javaClass.simpleName)
            }
            checking.set(false)
            publish(next)
        }
    }

    /**
     * 下载并安装当前发现的新版本。已有校验过的安装包时直接安装，不再重复下载。
     * 发行页没给 SHA256、系统还没授权「安装未知应用」时直接报给界面，不下载。
     */
    fun downloadAndInstall() {
        val release = current.release ?: return
        val phase = current.phase
        if (phase == UpdatePhase.DOWNLOADING || phase == UpdatePhase.VERIFYING) return

        val sha = release.sha256
        if (sha.isNullOrBlank()) {
            publish(current.copy(phase = UpdatePhase.FAILED, messageRes = R.string.update_failed_no_checksum, detail = null))
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && !appContext.packageManager.canRequestPackageInstalls()) {
            publish(
                current.copy(
                    phase = UpdatePhase.FAILED,
                    messageRes = R.string.update_failed_install_permission,
                    needsInstallPermission = true,
                    detail = null,
                ),
            )
            return
        }

        cancelRequested = false
        publish(
            current.copy(
                phase = UpdatePhase.DOWNLOADING,
                progress = 0,
                messageRes = null,
                detail = null,
                needsInstallPermission = false,
            ),
        )
        worker.execute {
            try {
                val dir = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, DIR).apply { mkdirs() }
                val target = File(dir, UpdateVersions.assetName(release.version))
                if (!target.isFile || !sha256(target).equals(sha, ignoreCase = true)) {
                    if (target.isFile) target.delete()
                    if (!downloadToFile(release, target)) {
                        if (cancelRequested) {
                            publish(current.copy(phase = UpdatePhase.AVAILABLE, progress = 0))
                        } else {
                            publish(current.copy(phase = UpdatePhase.FAILED, messageRes = R.string.update_failed_download))
                        }
                        return@execute
                    }
                }
                publish(current.copy(phase = UpdatePhase.VERIFYING, progress = 100))
                val actual = sha256(target)
                if (!actual.equals(sha, ignoreCase = true)) {
                    Log.w(TAG, "SHA256 不匹配：期望 $sha，实际 $actual")
                    target.delete()
                    publish(current.copy(phase = UpdatePhase.FAILED, messageRes = R.string.update_failed_checksum))
                    return@execute
                }
                // 证书可能降级成不校验，这里再确认安装包确实是本应用的签名：
                // 系统安装器也要求同签名，提前比对能给出明确提示，而不是让车主看到「应用未安装」。
                if (!sameSignerAsInstalled(target)) {
                    target.delete()
                    publish(current.copy(phase = UpdatePhase.FAILED, messageRes = R.string.update_failed_signature))
                    return@execute
                }
                install(target)
            } catch (t: Throwable) {
                Log.w(TAG, "下载安装包失败：${t.javaClass.simpleName} ${t.message}")
                publish(current.copy(phase = UpdatePhase.FAILED, messageRes = R.string.update_failed_download))
            }
        }
    }

    /** 取消正在进行的下载：断开连接，读循环随即退出。 */
    fun cancelDownload() {
        cancelRequested = true
        activeConnection?.disconnect()
    }

    /** 跳系统「安装未知应用」授权页（安卓 8 起才需要）。 */
    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT < 26) return
        val intent = Intent(
            "android.settings.MANAGE_UNKNOWN_APP_SOURCES",
            Uri.parse("package:${appContext.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(intent) }
            .onFailure { Log.w(TAG, "打开安装授权页失败：${it.javaClass.simpleName}") }
    }

    // ---------------------------------------------------------------- 网络

    /**
     * 优先严格校验证书；证书校验失败（GitHub 换 CA、根证书过期等）时自动降级为不校验证书，
     * 以免更新功能随着 CA 变更一起失效。降级后仍由 SHA256 + APK 签名比对把关。
     */
    private fun fetchLatest(): UpdateUiState {
        try {
            return fetchLatestOnce(strictTlsFailed)
        } catch (t: Throwable) {
            if (!strictTlsFailed && isTlsProblem(t)) {
                strictTlsFailed = true
                Log.w(TAG, "证书校验不通过，后续改用不校验证书的方式（仍校验 SHA256 与 APK 签名）：${t.javaClass.simpleName} ${t.message}")
                return fetchLatestOnce(lenient = true)
            }
            throw t
        }
    }

    private fun fetchLatestOnce(lenient: Boolean): UpdateUiState {
        val conn = open(UpdateVersions.RELEASES_API, "application/vnd.github+json", lenient)
            ?: return failed(R.string.update_failed_network, null)
        try {
            val code = conn.responseCode
            if (code == 403 || code == 429) return failed(R.string.update_failed_rate_limited, "HTTP $code")
            if (code != 200) return failed(R.string.update_failed_http, "HTTP $code")
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val release = pickLatest(JSONArray(body)) ?: return failed(R.string.update_failed_no_release, null)
            Log.i(TAG, "最新发行版 ${release.tag}，本机 ${BuildConfig.VERSION_NAME}，SHA256 ${release.sha256 ?: "未提供"}")
            return if (UpdateVersions.isNewer(release.version, BuildConfig.VERSION_NAME)) {
                UpdateUiState(phase = UpdatePhase.AVAILABLE, release = release)
            } else {
                UpdateUiState(phase = UpdatePhase.UP_TO_DATE)
            }
        } finally {
            conn.disconnect()
        }
    }

    /** 列表里挑版本号最高、非草稿非预发布的那个。 */
    private fun pickLatest(releases: JSONArray): ReleaseInfo? {
        var best: ReleaseInfo? = null
        for (i in 0 until releases.length()) {
            val item = releases.optJSONObject(i) ?: continue
            if (item.optBoolean("draft") || item.optBoolean("prerelease")) continue
            val tag = item.optString("tag_name")
            val version = UpdateVersions.fromTag(tag) ?: continue
            if (best != null && UpdateVersions.compare(version, best.version) <= 0) continue

            val body = item.optString("body")
            var sha = UpdateVersions.parseSha256(body)
            var url = UpdateVersions.downloadUrl(tag, version)
            var apiUrl: String? = null
            var size = 0L
            val assets = item.optJSONArray("assets")
            if (assets != null) {
                for (j in 0 until assets.length()) {
                    val asset = assets.optJSONObject(j) ?: continue
                    if (sha == null) {
                        val digest = asset.optString("digest")
                        if (digest.startsWith("sha256:")) sha = digest.removePrefix("sha256:").lowercase(Locale.US)
                    }
                    if (asset.optString("name") == UpdateVersions.assetName(version)) {
                        url = asset.optString("browser_download_url").ifBlank { url }
                        size = asset.optLong("size")
                        val id = asset.optLong("id")
                        if (id > 0) apiUrl = UpdateVersions.assetApiUrl(id)
                    }
                }
            }
            best = ReleaseInfo(version, tag, body, url, apiUrl, sha, size)
        }
        return best
    }

    /**
     * 依次尝试接口 asset 地址和发行页地址：国内网络下 github.com 的解析经常被污染，
     * 而 api.github.com 能通，asset 接口会 302 到 objects.githubusercontent.com。
     */
    private fun downloadToFile(release: ReleaseInfo, target: File): Boolean {
        val urls = listOfNotNull(release.assetApiUrl, release.apkUrl).distinct()
        for (url in urls) {
            if (cancelRequested) return false
            val ok = try {
                downloadFrom(url, release, target, strictTlsFailed)
            } catch (t: Throwable) {
                if (!strictTlsFailed && isTlsProblem(t)) {
                    strictTlsFailed = true
                    Log.w(TAG, "证书校验不通过，后续改用不校验证书的方式（仍校验 SHA256 与 APK 签名）：${t.javaClass.simpleName} ${t.message}")
                    try {
                        downloadFrom(url, release, target, lenient = true)
                    } catch (retry: Throwable) {
                        logDownloadError(retry)
                        false
                    }
                } else {
                    logDownloadError(t)
                    false
                }
            }
            if (ok) return true
            if (cancelRequested) return false
            Log.w(TAG, "地址下载失败，换下一个：$url")
            if (target.isFile) target.delete()
        }
        return false
    }

    private fun downloadFrom(url: String, release: ReleaseInfo, target: File, lenient: Boolean): Boolean {
        val conn = open(url, "application/octet-stream", lenient) ?: return false
        activeConnection = conn
        try {
            val code = conn.responseCode
            if (code != 200 && code != 206) {
                Log.w(TAG, "下载安装包返回 HTTP $code（$url）")
                return false
            }
            // getContentLengthLong 是 API 24 才有的，4.4 上只能读头。
            val total = conn.getHeaderField("Content-Length")?.toLongOrNull()?.takeIf { it > 0 } ?: release.sizeBytes
            var read = 0L
            var lastPercent = -1
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelRequested) return false
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        read += n
                        if (total > 0) {
                            val percent = (read * 100 / total).toInt().coerceIn(0, 99)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                publish(current.copy(phase = UpdatePhase.DOWNLOADING, progress = percent))
                            }
                        }
                    }
                }
            }
            Log.i(TAG, "下载完成：${target.name}，$read 字节")
            return read > 0
        } finally {
            activeConnection = null
            conn.disconnect()
        }
    }

    /** 调试包带堆栈，方便定位 TLS 之类的问题；正式包只留一行。 */
    private fun logDownloadError(t: Throwable) {
        if (BuildConfig.DEBUG) {
            Log.w(TAG, "下载中断：${t.javaClass.simpleName} ${t.message}", t)
        } else {
            Log.w(TAG, "下载中断：${t.javaClass.simpleName} ${t.message}")
        }
    }

    /** 证书/握手类异常：需要降级重试的只有这些，网络超时之类的降级也没用。 */
    private fun isTlsProblem(t: Throwable): Boolean = CarHttps.isTlsProblem(t)

    private fun open(url: String, accept: String?, lenient: Boolean = false): HttpURLConnection? =
        CarHttps.open(appContext, url, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, accept, lenient)?.apply {
            setRequestProperty("User-Agent", USER_AGENT)
        }

    /**
     * 下载到的安装包是否和本应用同一签名。证书降级后这是最关键的一道校验：
     * 别人替换安装包也没用，系统只允许同签名覆盖安装。
     */
    private fun sameSignerAsInstalled(apk: File): Boolean {
        val manager = appContext.packageManager
        return try {
            val flags = if (Build.VERSION.SDK_INT >= 28) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            val archive = manager.getPackageArchiveInfo(apk.absolutePath, flags)
            val installed = manager.getPackageInfo(appContext.packageName, flags)
            val archiveDigests = signerDigests(archive)
            val installedDigests = signerDigests(installed)
            val same = archiveDigests.isNotEmpty() && archiveDigests.any { it in installedDigests }
            Log.i(TAG, "APK 签名比对：安装包 ${archiveDigests.size} 个签名，本机 ${installedDigests.size} 个，结果 $same")
            same
        } catch (t: Throwable) {
            Log.w(TAG, "APK 签名比对失败：${t.javaClass.simpleName} ${t.message}")
            false
        }
    }

    /** 取出包里的签名证书 SHA-256（28 起走 signingInfo，之前走 signatures）。 */
    private fun signerDigests(info: PackageInfo?): Set<String> {
        if (info == null) return emptySet()
        val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners ?: info.signingInfo?.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        return signatures.orEmpty().mapTo(LinkedHashSet()) { signature ->
            val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
            digest.joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 0xFF) }
        }
    }

    // ---------------------------------------------------------------- 校验与安装

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val text = StringBuilder(64)
        for (byte in digest.digest()) text.append(String.format(Locale.US, "%02x", byte.toInt() and 0xFF))
        return text.toString()
    }

    private fun install(apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !appContext.packageManager.canRequestPackageInstalls()) {
            publish(
                current.copy(
                    phase = UpdatePhase.FAILED,
                    messageRes = R.string.update_failed_install_permission,
                    needsInstallPermission = true,
                    detail = null,
                ),
            )
            return false
        }
        return try {
            val uri = if (Build.VERSION.SDK_INT >= 24) {
                FileProvider.getUriForFile(appContext, "${appContext.packageName}.updates", apk)
            } else {
                Uri.fromFile(apk)
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            appContext.startActivity(intent)
            Log.i(TAG, "已把 ${apk.name} 交给系统安装器")
            publish(current.copy(phase = UpdatePhase.INSTALLING, progress = 100, messageRes = R.string.update_install_started, detail = null))
            true
        } catch (t: Throwable) {
            Log.w(TAG, "启动安装器失败：${t.javaClass.simpleName} ${t.message}")
            publish(current.copy(phase = UpdatePhase.FAILED, messageRes = R.string.update_failed_install))
            false
        }
    }

    // ---------------------------------------------------------------- 状态

    private fun publish(next: UpdateUiState) {
        current = next
        main.post { listeners.forEach { it(next) } }
    }

    private fun failed(res: Int, detail: String? = null): UpdateUiState =
        UpdateUiState(phase = UpdatePhase.FAILED, messageRes = res, detail = detail)

    private companion object {
        const val TAG = "OpenHC-Update"
        const val DIR = "updates"
        const val USER_AGENT = "OpenHC-Android4.4"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 20_000
    }
}



