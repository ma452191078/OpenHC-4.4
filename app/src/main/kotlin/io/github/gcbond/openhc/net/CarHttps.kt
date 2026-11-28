package io.github.gcbond.openhc.net

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import io.github.gcbond.openhc.R
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.Provider
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 应用自己的 HTTPS 连接（应用内更新连 GitHub、诊断上传连七牛共用）。
 *
 * 4.4 的平台 TLS 只提供 CBC 套件，GitHub 只收 AEAD（GCM），握手会被直接拒掉；所以在 5.1 以下换用
 * BouncyCastle 的纯 Java JSSE（自带 TLS 1.2/1.3 与 GCM 套件）。
 *
 * 信任链默认用「系统信任 + 内置根证书」（res/raw/github_update_ca，更新时给 4.4 那些老系统的 CA 库补上
 * GitHub 现在的根）严格校验；[lenient] 为 true 时不再校验证书，调用方要自己有别的完整性校验（更新包靠
 * SHA256 与 APK 签名，诊断上传失败重试无害）。
 */
internal object CarHttps {
    private const val TAG = "OpenHC-Net"

    /** BC 的 JSSE Provider 需要同厂 BC 的 SecureRandom/DEFAULT 等实现。安卓自带的 Provider 也叫 "BC"， */
    @Volatile
    private var bcJsse: Provider? = null

    /**
     * 打开一个连接并配好 TLS。User-Agent 和其余头部由调用方设置；失败返回 null（原因已记日志）。
     */
    fun open(
        context: Context,
        url: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        accept: String?,
        lenient: Boolean = false,
    ): HttpURLConnection? =
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = true
            if (accept != null) conn.setRequestProperty("Accept", accept)
            configureTls(conn, context, lenient)
            conn
        } catch (t: Throwable) {
            Log.w(TAG, "请求 $url 失败：${t.javaClass.simpleName} ${t.message}")
            null
        }

    private fun configureTls(conn: HttpURLConnection, context: Context, lenient: Boolean) {
        if (conn !is HttpsURLConnection) return
        try {
            val trust: X509TrustManager = if (lenient) {
                TrustAllManager
            } else {
                combinedTrustManager(context) ?: return
            }
            val sslContext = if (Build.VERSION.SDK_INT < 22) {
                val provider = bcJsseProvider() ?: return
                // 固定 TLS 1.2：BC 1.79 和 GitHub 的下载 CDN（release-assets）走 TLS 1.3 时，
                // 握手阶段会收到提前的 application_data 而报 unexpected_message，1.2 稳定。
                SSLContext.getInstance("TLSv1.2", provider)
            } else {
                SSLContext.getInstance("TLS")
            }
            sslContext.init(null, arrayOf<TrustManager>(trust), null)
            conn.sslSocketFactory = sslContext.socketFactory
        } catch (t: Throwable) {
            Log.w(TAG, "配置 TLS 失败，退回系统默认：${t.javaClass.simpleName} ${t.message}")
        }
    }

    /**
     * BC 的 JSSE Provider 需要同厂 BC 的 SecureRandom/DEFAULT 等实现。安卓自带的 Provider 也叫 "BC"，
     * 但内容不全，按名字查会查到系统那个，所以这里直接把自建的 BouncyCastleProvider 实例交给它，
     * 也不往全局 JCA 注册，避免影响应用其他密码学路径。
     */
    private fun bcJsseProvider(): Provider? {
        bcJsse?.let { return it }
        synchronized(this) {
            bcJsse?.let { return it }
            return try {
                val provider = BouncyCastleJsseProvider(BouncyCastleProvider())
                bcJsse = provider
                provider
            } catch (t: Throwable) {
                Log.w(TAG, "加载 BC TLS Provider 失败：${t.javaClass.simpleName} ${t.message}")
                null
            }
        }
    }

    /** 先按系统信任链校验，失败再用内置根证书校验一次（旧系统 CA 库里没有 GitHub 现在的根）。 */
    private fun combinedTrustManager(context: Context): X509TrustManager? {
        val system = try {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
        } catch (t: Throwable) {
            Log.w(TAG, "取系统信任管理器失败：${t.javaClass.simpleName}")
            null
        }
        val pinned = try {
            pinnedTrustManager(context)
        } catch (t: Throwable) {
            Log.w(TAG, "加载内置根证书失败：${t.javaClass.simpleName} ${t.message}")
            null
        }
        return when {
            system == null -> pinned
            pinned == null -> system
            else -> CombinedTrustManager(system, pinned)
        }
    }

    /**
     * 用 res/raw 里打包的根证书建信任管理器（只用于本应用的 GitHub 请求，不影响应用其他联网）。
     * 4.4 的 CertificateFactory.generateCertificates 对多证书 PEM 只认出第一张，这里逐个块解析。
     */
    private fun pinnedTrustManager(context: Context): X509TrustManager {
        val text = context.resources.openRawResource(R.raw.github_update_ca)
            .use { it.bufferedReader().readText() }
        val certificateFactory = CertificateFactory.getInstance("X.509")
        val certificates = PEM_BLOCK.findAll(text).map { match ->
            val der = Base64.decode(match.groupValues[1].replace(WHITESPACE, ""), Base64.DEFAULT)
            certificateFactory.generateCertificate(ByteArrayInputStream(der))
        }.toList()
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        certificates.forEachIndexed { index, certificate -> store.setCertificateEntry("github-ca-$index", certificate) }
        Log.i(TAG, "内置根证书 ${certificates.size} 张：" +
            certificates.joinToString { (it as? X509Certificate)?.subjectDN?.name ?: it.type })
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(store)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            ?: throw IllegalStateException("内置根证书没有生成信任管理器")
    }

    /** 降级模式用：不校验证书（主机名校验仍由 HttpsURLConnection 做），完整性交给调用方的应用层校验。 */
    private object TrustAllManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private class CombinedTrustManager(
        private val system: X509TrustManager,
        private val pinned: X509TrustManager,
    ) : X509TrustManager {

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
            pinned.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            try {
                system.checkServerTrusted(chain, authType)
                return
            } catch (systemError: CertificateException) {
                try {
                    pinned.checkServerTrusted(chain, authType)
                } catch (pinnedError: CertificateException) {
                    Log.w(
                        TAG,
                        "证书校验失败：系统 ${systemError.javaClass.simpleName}(${systemError.message})，" +
                            "内置根 ${pinnedError.javaClass.simpleName}(${pinnedError.message})；" +
                            "链 " + chain.joinToString { c -> c.subjectDN.name },
                    )
                    throw pinnedError
                }
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> =
            system.acceptedIssuers + pinned.acceptedIssuers
    }

    /** 证书/握手类异常：需要降级重试的只有这些，网络超时之类的降级也没用。 */
    fun isTlsProblem(t: Throwable): Boolean {
        var current: Throwable? = t
        var depth = 0
        while (current != null && depth < 8) {
            if (current is SSLException || current is GeneralSecurityException) return true
            if (current.javaClass.name.contains("TlsFatalAlert")) return true
            current = current.cause
            depth++
        }
        return false
    }

    private val PEM_BLOCK = Regex("-----BEGIN CERTIFICATE-----([\\s\\S]*?)-----END CERTIFICATE-----")
    private val WHITESPACE = Regex("\\s")
}
