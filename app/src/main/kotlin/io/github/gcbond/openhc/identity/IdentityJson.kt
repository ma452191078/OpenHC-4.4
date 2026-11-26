package io.github.gcbond.openhc.identity

import io.github.gcbond.openhc.core.common.Base64
import io.github.gcbond.openhc.core.common.identity.IdentityAssets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** 身份参数文件大于 [IdentityJson.MAX_BYTES]：不像身份参数，不读。 */
class IdentityTooLargeException : IOException("文件太大（超过 ${IdentityJson.MAX_BYTES / 1024} KB）")

/**
 * 身份参数文件（app/src/main/assets/openhc-identity.json）的读写辅助。纯逻辑，不引用安卓类。
 *
 * 软件里保存的那份一律是 [encode] 出来的规范格式：字段齐全、密钥是带补齐的 Base64、车型编号大写，
 * 和导入的文件、粘贴的文字、手填的表单从哪条路来无关。
 */
object IdentityJson {
    /** 正常的文件不到 1 KB；超过这个大小就不是身份参数，不读进内存。 */
    const val MAX_BYTES = 64 * 1024

    private val pretty = Json { prettyPrint = true }

    /** 规范格式的 JSON 文本（末尾带换行）。 */
    fun encode(assets: IdentityAssets): String = build(
        modelId = assets.host.modelId,
        subModelId = assets.host.subModelId,
        brand = assets.host.brand,
        deviceName = assets.host.deviceName,
        rootKey = assets.nearbyKeys.rootKey,
        constA = assets.nearbyKeys.constA,
        constB = assets.nearbyKeys.constB,
        packageName = assets.authService.packageName,
        serviceType = assets.authService.serviceType,
        envelopeVersion = assets.authEnvelopeVersion,
    )

    /** 按各字段拼出规范格式的 JSON；不做校验，交给 [IdentityAssets.parse]。 */
    internal fun build(
        modelId: String,
        subModelId: String,
        brand: String,
        deviceName: String,
        rootKey: ByteArray,
        constA: ByteArray,
        constB: ByteArray,
        packageName: String,
        serviceType: String,
        envelopeVersion: String,
    ): String {
        val root: JsonObject = buildJsonObject {
            put("format", IdentityAssets.FORMAT)
            putJsonObject("host") {
                put("modelId", modelId)
                put("subModelId", subModelId)
                put("brand", brand)
                put("deviceName", deviceName)
            }
            putJsonObject("nearbyKeys") {
                put("rootKey", Base64.encode(rootKey))
                put("constA", Base64.encode(constA))
                put("constB", Base64.encode(constB))
            }
            putJsonObject("authService") {
                put("packageName", packageName)
                put("serviceType", serviceType)
                put("envelopeVersion", envelopeVersion)
            }
        }
        return pretty.encodeToString(JsonObject.serializer(), root) + "\n"
    }

    /**
     * 粘贴的文字或文件内容 → 身份参数：去掉开头的 BOM 和首尾空白，再交给 [IdentityAssets.parse]。
     *
     * @throws IllegalArgumentException 格式不对；信息里不含密钥内容，只用它的 message，不要往外给它的 cause
     *   （JSON 语法错误的 cause 里可能带一段原文）。
     */
    fun parseText(text: String): IdentityAssets {
        require(text.length <= MAX_BYTES) { "内容太长，不像身份参数" }
        val cleaned = text.removePrefix(BOM).trim()
        require(cleaned.isNotEmpty()) { "是空的" }
        return IdentityAssets.parse(cleaned)
    }

    /**
     * 读完 [input]（不关闭它），最多 [MAX_BYTES] 字节。
     *
     * @throws IdentityTooLargeException 超过上限。
     */
    @Throws(IOException::class)
    fun readLimited(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > MAX_BYTES) throw IdentityTooLargeException()
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /** 按 UTF-8 解码，去掉开头的 BOM（记事本类编辑器常加）。 */
    fun decodeUtf8(bytes: ByteArray): String = String(bytes, Charsets.UTF_8).removePrefix(BOM)

    private val BOM = Char(0xFEFF).toString()
}
