package io.github.gcbond.openhc.core.common.codec

import io.github.gcbond.openhc.core.common.MalformedMessageException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class JsonCodecTest {

    @Test
    fun parsesAnyElement() {
        assertEquals(JsonPrimitive(1), JsonCodec.parse("1", "数字"))
        assertEquals(2, assertIs<JsonArray>(JsonCodec.parse("[1, \"a\"]", "数组")).size)
        val obj = JsonCodec.parseObject(" {\"k\": \"中文\", \"n\": 2} ", "对象")
        assertEquals("中文", (obj["k"] as JsonPrimitive).content)
    }

    @Test
    fun malformedTextIsReportedWithoutOriginal() {
        for (text in listOf("{\"secret\":", "", "{} {}", "{'a':1}", "[1,]")) {
            val e = assertFailsWith<MalformedMessageException>(text) { JsonCodec.parse(text, "测试消息") }
            assertEquals("测试消息 不是合法 JSON", e.message)
            assertNull(e.cause, "不挂解析库的异常（它的消息里带原文）")
        }
        val e = assertFailsWith<MalformedMessageException> { JsonCodec.parseObject("{\"secret-token\"", "测试消息") }
        assertFalse(e.message.orEmpty().contains("secret"), e.message)
    }

    @Test
    fun parseObjectRejectsOtherElements() {
        for (text in listOf("[]", "1", "\"s\"", "null", "true")) {
            val e = assertFailsWith<MalformedMessageException>(text) { JsonCodec.parseObject(text, "测试消息") }
            assertEquals("测试消息 不是 JSON 对象", e.message)
        }
    }

    @Test
    fun encodesCompactUtf8() {
        val json = buildJsonObject {
            put("cmdId", "9")
            put("name", "车机 \"A\"")
            put("n", -1)
        }
        val expected = "{\"cmdId\":\"9\",\"name\":\"车机 \\\"A\\\"\",\"n\":-1}"
        assertContentEquals(expected.toByteArray(Charsets.UTF_8), JsonCodec.encodeToUtf8(json))
        assertEquals(json, JsonCodec.parse(expected, "回读"))
    }
}
