package io.github.gcbond.openhc.diagnostics

import android.content.Context
import android.util.Base64
import android.util.Log
import io.github.gcbond.openhc.BuildConfig
import io.github.gcbond.openhc.net.CarHttps
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 「上传诊断日志」：把与导出完全一致的报告（同样的脱敏规则）gzip 后传进七牛云收件桶
 * （BuildConfig.QINIU_*，见 app/build.gradle.kts）。上传时生成一个 6 位取件码，用它命名文件并在界面上展示，
 * 车主把码报给开发者，开发者按码在 bucket 里检索即可。
 *
 * 鉴权：按七牛的表单上传协议在本地签名上传凭证（HMAC-SHA1，标准 JCE 实现），上传端点见
 * BuildConfig.QINIU_UPLOAD_HOST（bucket 的区域不同域名不同）。
 *
 * 凭据打进 APK 可被提取；建议这对 AK/SK 只绑定收件桶（七牛可以对单 bucket 限权），泄露就换一对。
 * 上传失败重试无害，所以证书校验失败时允许降级为不校验（老车机系统缺新根证书时不至于传不上去；
 * 报告不含密钥，拦截者拿不到什么）。
 *
 * 阻塞：要读写几 MB 报告并发请求，别在界面线程上调。
 */
internal object DiagnosticsUploader {
    private const val TAG = "OpenHC-Diag"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val USER_AGENT = "OpenHC-Android4.4"

    /** 上传凭证的有效期（秒）。 */
    private const val TOKEN_TTL_SECONDS = 3600

    /** 上传内容的大小上限（gzip 后的报告远小于这个数，超了说明哪里不对，别硬传）。 */
    private const val MAX_BODY_BYTES = 40L * 1024 * 1024

    /** 取件码的字符表：大写字母和数字，去掉容易看混的 0/O、1/I/L（车主念给开发者听不出错）。 */
    private const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private const val CODE_LENGTH = 6
    private val random = SecureRandom()

    sealed class Result {
        /** 传好了；[code] 是取件码（界面上展示、车主转告），文件按它命名（后缀 .txt.gz）。 */
        class Uploaded(val code: String, val key: String) : Result()

        class Failed(val reason: String) : Result()
    }

    fun upload(context: Context): Result {
        val bucket = BuildConfig.QINIU_BUCKET
        if (bucket.isBlank() || BuildConfig.QINIU_ACCESS_KEY.isBlank() || BuildConfig.QINIU_SECRET_KEY.isBlank()) {
            return Result.Failed("没有配置收件桶（构建参数 openhc.qiniu.*），请改用「导出」")
        }
        val app = context.applicationContext ?: context
        LogRecorder.flush()
        val code = code()
        val name = "$code.txt.gz"
        return try {
            val body = body(app)
                ?: return Result.Failed("报告读不出来，请改用「导出」")
            formUpload(app, bucket, name, body)
            Result.Uploaded(code, name)
        } catch (e: IOException) {
            Result.Failed(describe(e))
        } catch (e: RuntimeException) {
            Result.Failed(describe(e))
        }
    }

    /** 6 位取件码。 */
    private fun code(): String = buildString {
        repeat(CODE_LENGTH) { append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]) }
    }

    /** 报告 → gzip，太大就放弃（null）。 */
    private fun body(app: Context): ByteArray? {
        val raw = DiagnosticsExport.buildReport(app, Date())
        val gz = ByteArrayOutputStream(raw.size / 4 + 1024)
        GZIPOutputStream(gz).use { it.write(raw) }
        val body = gz.toByteArray()
        if (body.size > MAX_BODY_BYTES) {
            Log.w(TAG, "报告 gzip 后 ${body.size} 字节，超过上限，不上传")
            return null
        }
        return body
    }

    // ---------------- 七牛表单上传 ----------------

    /** 上传凭证：`AK:HMAC-SHA1(SK, base64url(策略)):base64url(策略)`，策略限定只传这一个 key。 */
    private fun uploadToken(bucket: String, key: String): String {
        val policy = JSONObject().apply {
            put("scope", "$bucket:$key")
            put("deadline", System.currentTimeMillis() / 1000 + TOKEN_TTL_SECONDS)
        }.toString()
        val encoded = base64Url(policy.toByteArray(Charsets.UTF_8))
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(BuildConfig.QINIU_SECRET_KEY.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val sign = base64Url(mac.doFinal(encoded.toByteArray(Charsets.UTF_8)))
        return "${BuildConfig.QINIU_ACCESS_KEY}:$sign:$encoded"
    }

    /** 七牛的 UrlSafeBase64：`+`→`-`、`/`→`_`，**保留 `=` 填充**（官方 SDK 即如此；去填充会报 bad token，已实测）。 */
    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP)

    /**
     * 七牛表单上传：POST multipart/form-data，字段顺序按文档是 token、key、file（file 必须最后）。
     * 成功回 200 和 `{"key":…,"hash":…}`，失败回错误码和 `{"error":…}`。
     * 证书校验失败（老系统缺新根）时降级重试一次：报告不含密钥，拦不到什么要紧东西。
     */
    private fun formUpload(context: Context, bucket: String, key: String, body: ByteArray) {
        try {
            post(context, bucket, key, body, lenient = false)
        } catch (e: IOException) {
            if (!CarHttps.isTlsProblem(e)) throw e
            Log.w(TAG, "证书校验不通过，改用不校验证书的方式重传：${e.javaClass.simpleName} ${e.message}")
            post(context, bucket, key, body, lenient = true)
        }
    }

    private fun post(context: Context, bucket: String, key: String, body: ByteArray, lenient: Boolean) {
        val token = uploadToken(bucket, key)
        val boundary = "OpenHC${System.currentTimeMillis()}"
        val payload = multipart(token, key, body, boundary)
        val conn = CarHttps.open(
            context,
            "${BuildConfig.QINIU_UPLOAD_HOST}/",
            CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, null, lenient,
        ) ?: throw IOException("连不上上传域名 ${BuildConfig.QINIU_UPLOAD_HOST}")
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.setFixedLengthStreamingMode(payload.size)
            conn.outputStream.use { it.write(payload) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val detail = runCatching { JSONObject(text).optString("error") }.getOrNull()?.take(120).orEmpty()
                throw IOException("七牛返回 HTTP $code" + if (detail.isBlank()) "" else "：$detail")
            }
        } finally {
            conn.disconnect()
        }
    }

    /** 组 multipart 表单：token、key 两个文本字段，file 字节字段放最后。 */
    private fun multipart(token: String, key: String, file: ByteArray, boundary: String): ByteArray {
        val out = ByteArrayOutputStream()
        fun field(name: String, value: String) {
            out.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
            out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(Charsets.UTF_8))
            out.write(value.toByteArray(Charsets.UTF_8))
            out.write("\r\n".toByteArray(Charsets.UTF_8))
        }
        field("token", token)
        field("key", key)
        out.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
        out.write(
            ("Content-Disposition: form-data; name=\"file\"; filename=\"$key\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n").toByteArray(Charsets.UTF_8),
        )
        out.write(file)
        out.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    private fun describe(e: Throwable): String = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
}
