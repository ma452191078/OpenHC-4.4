package io.github.gcbond.openhc.core.hisight

import io.github.gcbond.openhc.core.common.hexToBytes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.security.SecureRandom

/**
 * 对照向量：由参考实现对合成输入运行得到，见 `hisight-vectors.json`。
 * 密钥是 00..0f，IV 是确定序列，H.264 是构造字节，URI 只用保留域名。
 */
internal object Vectors {
    val root: JsonObject by lazy {
        val text = Vectors::class.java.getResourceAsStream("/hisight-vectors.json")!!
            .use { it.readBytes().toString(Charsets.UTF_8) }
        Json.parseToJsonElement(text).jsonObject
    }

    val key: ByteArray get() = root.str("key").hexToBytes()

    fun section(name: String): JsonElement = root.getValue(name)
}

internal fun JsonObject.str(name: String): String = getValue(name).jsonPrimitive.content
internal fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.intOrNull!!
internal fun JsonObject.long(name: String): Long = getValue(name).jsonPrimitive.longOrNull!!
internal fun JsonObject.bool(name: String): Boolean = getValue(name).jsonPrimitive.booleanOrNull!!
internal fun JsonObject.obj(name: String): JsonObject = getValue(name).jsonObject
internal fun JsonObject.arr(name: String): JsonArray = getValue(name).jsonArray
internal fun JsonObject.isNull(name: String): Boolean = this[name] == null || this[name] is JsonNull
internal fun JsonObject.intOrNull(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull
internal fun JsonElement.hex(): ByteArray = jsonPrimitive.content.hexToBytes()

/** 测试用 IV 来源：按给定顺序依次交出。 */
internal class FixedRandom(private val values: List<ByteArray>) : SecureRandom() {
    var used = 0
        private set

    override fun nextBytes(bytes: ByteArray) {
        check(used < values.size) { "测试 IV 用完了" }
        val v = values[used++]
        check(v.size == bytes.size) { "测试 IV 长度不符" }
        System.arraycopy(v, 0, bytes, 0, v.size)
    }
}

internal fun configFrom(json: JsonObject) = HiSightConfig(
    screenWidth = json.int("screenWidth"),
    screenHeight = json.int("screenHeight"),
    videoWidth = json.int("videoWidth"),
    videoHeight = json.int("videoHeight"),
    fps = json.int("fps"),
    dpi = json.int("dpi"),
    gop = json.intOrNull("gop"),
    bitrate = json.intOrNull("bitrate"),
    touchInput = json.bool("touchInput"),
    keyInput = json.bool("keyInput"),
    textInput = json.bool("textInput"),
    deviceType = json.int("deviceType"),
)

internal fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)
