package io.github.gcbond.openhc.core.auth.hichain

import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.common.codec.JsonCodec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * 认证消息里的 JSON 读写小工具。
 *
 * 认证库消息（HiChain JSON）的读取规则与原库一致：字段名不区分大小写，同名取第一个；数值字段按整数读取，
 * 非整数向零截断、越界饱和到 Int 范围；字节字段是偶数长度的十六进制字符串（大小写都行），超过上限或格式不对
 * 就当作缺失。外层消息和错误通知的识别字段区分大小写，由调用处直接按原名取。
 */

/** 整段文本必须是一个 JSON 对象，否则抛 [MalformedMessageException]（异常信息不带原文），见 [JsonCodec.parseObject]。 */
internal fun parseJsonObject(text: String, what: String): JsonObject = JsonCodec.parseObject(text, what)

/** 按字段名取值，ASCII 字母不区分大小写，有多个匹配时取第一个。 */
internal fun JsonObject.field(name: String): JsonElement? {
    for ((key, value) in this) {
        if (key.length == name.length && key.indices.all { asciiLower(key[it]) == asciiLower(name[it]) }) return value
    }
    return null
}

private fun asciiLower(c: Char): Char = if (c in 'A'..'Z') c + ('a' - 'A') else c

/** 数值字段：缺失、不是数值时返回 null；非整数向零截断，超出 Int 范围时饱和。 */
internal fun JsonObject.intValue(name: String): Int? = field(name).numberAsInt()

internal fun JsonElement?.numberAsInt(): Int? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive is JsonNull || primitive.isString) return null
    val value = primitive.content.toDoubleOrNull() ?: return null
    return when {
        value.isNaN() -> null
        value >= Int.MAX_VALUE -> Int.MAX_VALUE
        value <= Int.MIN_VALUE -> Int.MIN_VALUE
        else -> value.toInt()
    }
}

/** 严格的整数：是数值，且没有小数部分、在 Int 范围内。 */
internal fun JsonElement?.strictInt(): Int? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive is JsonNull || primitive.isString) return null
    val value = primitive.content.toDoubleOrNull() ?: return null
    if (value.isNaN() || value != Math.floor(value) || value > Int.MAX_VALUE || value < Int.MIN_VALUE) return null
    return value.toInt()
}

internal fun JsonObject.stringValue(name: String): String? = field(name).stringOrNull()

internal fun JsonObject.objectValue(name: String): JsonObject? = field(name) as? JsonObject

/** 字段名区分大小写的字符串字段（外层消息用）。 */
internal fun JsonObject.exactString(name: String): String? = this[name].stringOrNull()

private fun JsonElement?.stringOrNull(): String? {
    val primitive = this as? JsonPrimitive ?: return null
    return if (primitive.isString) primitive.content else null
}

/** 十六进制字节字段；缺失、不是字符串、长度为奇数、含非十六进制字符或解码后超过 [maxBytes] 时返回 null。 */
internal fun JsonObject.hexValue(name: String, maxBytes: Int): ByteArray? {
    val text = stringValue(name) ?: return null
    return decodeHex(text, maxBytes)
}

internal fun decodeHex(text: String, maxBytes: Int): ByteArray? {
    if (text.length / 2 > maxBytes || text.length % 2 != 0) return null
    val out = ByteArray(text.length / 2)
    for (i in out.indices) {
        val hi = Character.digit(text[i * 2], 16)
        val lo = Character.digit(text[i * 2 + 1], 16)
        // Character.digit 也认全角数字等，这里只收 ASCII。
        if (hi < 0 || lo < 0 || text[i * 2].code > 0x7F || text[i * 2 + 1].code > 0x7F) return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

/**
 * 从 [start] 起找一个完整 JSON 对象的结束位置（不含），跳过字符串内的括号和转义。
 * 只做括号与字符串配对，不做完整语法检查；对象没有闭合返回 -1。
 */
internal fun jsonObjectEnd(text: CharSequence, start: Int): Int {
    if (start >= text.length || text[start] != '{') return -1
    var depth = 0
    var inString = false
    var escaped = false
    var i = start
    while (i < text.length) {
        val c = text[i]
        if (inString) {
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = false
            }
        } else {
            when (c) {
                '"' -> inString = true
                '{', '[' -> depth++
                '}', ']' -> {
                    depth--
                    if (depth == 0) return i + 1
                }
            }
        }
        i++
    }
    return -1
}

/**
 * 紧凑 JSON 输出，逐字节对齐 Python `json.dumps(obj, ensure_ascii=False, separators=(",", ":"))`：
 * 保持成员顺序；字符串只转义引号、反斜杠和控制字符（控制字符用小写十六进制 \u00xx），非 ASCII 原样输出。
 */
internal object CompactJson {
    fun write(element: JsonElement): String = StringBuilder().also { append(it, element) }.toString()

    fun writeStringMap(map: Map<String, String>): String {
        val sb = StringBuilder("{")
        var first = true
        for ((key, value) in map) {
            if (!first) sb.append(',')
            first = false
            quote(sb, key)
            sb.append(':')
            quote(sb, value)
        }
        return sb.append('}').toString()
    }

    fun quote(value: String): String = StringBuilder().also { quote(it, value) }.toString()

    private fun append(sb: StringBuilder, element: JsonElement) {
        when (element) {
            is JsonObject -> {
                sb.append('{')
                var first = true
                for ((key, value) in element) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, key)
                    sb.append(':')
                    append(sb, value)
                }
                sb.append('}')
            }
            is JsonArray -> {
                sb.append('[')
                element.forEachIndexed { index, item ->
                    if (index > 0) sb.append(',')
                    append(sb, item)
                }
                sb.append(']')
            }
            is JsonNull -> sb.append("null")
            is JsonPrimitive -> if (element.isString) quote(sb, element.content) else sb.append(element.content)
        }
    }

    private fun quote(sb: StringBuilder, value: String) {
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c.code < 0x20) {
                    sb.append("\\u00").append(HEX[c.code shr 4]).append(HEX[c.code and 0xF])
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
    }

    private const val HEX = "0123456789abcdef"
}
