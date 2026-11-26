package io.github.gcbond.openhc.core.common.codec

import io.github.gcbond.openhc.core.common.MalformedMessageException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 对端 JSON 的解析和本机 JSON 的输出。
 *
 * 解析失败统一抛 [MalformedMessageException]，异常信息只含调用方给的说明 `what`：不带原文，
 * 也不挂解析库的异常——它的消息里带着 JSON 原文，可能有设备标识、名称、文件名。
 *
 * 用 kotlinx.serialization 的默认 [Json] 实例，线程安全。
 */
object JsonCodec {
    /**
     * 解析一段 JSON 文本（对象、数组、标量都可以）。
     *
     * @throws MalformedMessageException 不是合法 JSON，信息为「`what` 不是合法 JSON」。
     */
    fun parse(text: String, what: String): JsonElement = try {
        Json.parseToJsonElement(text)
    } catch (e: Exception) {
        throw MalformedMessageException("$what 不是合法 JSON")
    }

    /**
     * 解析一个 JSON 对象。
     *
     * @throws MalformedMessageException 不是合法 JSON（「`what` 不是合法 JSON」），
     *   或顶层不是对象（「`what` 不是 JSON 对象」）。
     */
    fun parseObject(text: String, what: String): JsonObject =
        parse(text, what) as? JsonObject ?: throw MalformedMessageException("$what 不是 JSON 对象")

    /** 按默认 [Json] 的紧凑格式输出，再编码成 UTF-8 字节。 */
    fun encodeToUtf8(element: JsonElement): ByteArray =
        Json.encodeToString(JsonElement.serializer(), element).toByteArray(Charsets.UTF_8)
}
