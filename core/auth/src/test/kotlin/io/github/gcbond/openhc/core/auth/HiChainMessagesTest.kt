package io.github.gcbond.openhc.core.auth

import io.github.gcbond.openhc.core.auth.hichain.CompactJson
import io.github.gcbond.openhc.core.auth.hichain.HiChainBuild
import io.github.gcbond.openhc.core.auth.hichain.HiChainParse
import io.github.gcbond.openhc.core.auth.hichain.KeyAgreementVersion
import io.github.gcbond.openhc.core.auth.hichain.decodeHex
import io.github.gcbond.openhc.core.auth.hichain.jsonObjectEnd
import io.github.gcbond.openhc.core.auth.hichain.numberAsInt
import io.github.gcbond.openhc.core.auth.hichain.parseJsonObject
import io.github.gcbond.openhc.core.auth.hichain.strictInt
import io.github.gcbond.openhc.core.common.MalformedMessageException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 认证库消息的编解码、版本规则与 JSON 小工具。 */
class HiChainMessagesTest {

    private fun obj(text: String) = parseJsonObject(text, "测试")

    @Test
    fun buildersUseExactFormats() {
        val kcf = ByteArray(32) { it.toByte() }
        assertEquals(
            "{\"message\":32770,\"payload\":{\"kcfData\":\"000102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F\"}}",
            HiChainBuild.pakeServerConfirm(kcf),
        )
        assertEquals("{\"message\":32896, \"payload\":{\"errorCode\":17}}", HiChainBuild.inform(17))
        assertEquals("{\"authId\":\"4142\", \"authPk\":\"FF00\"}", HiChainBuild.exchangeAuthInfo("AB".toByteArray(), byteArrayOf(-1, 0)))
        assertEquals(
            "{\"authForm\":0,\"message\":32786,\"payload\":{\"authReturn\":\"0A\"}}",
            HiChainBuild.authAckResponse(byteArrayOf(10)),
        )
        val response = HiChainBuild.pakeResponse(ByteArray(16), ByteArray(16), ByteArray(384))
        assertEquals(953, response.length)
        assertTrue(response.startsWith("{\"message\":32769,\"payload\":{\"version\":{\"currentVersion\":\"1.0.0\",\"minVersion\":\"1.0.0\"},\"challenge\":\""))
        val start = HiChainBuild.authStartResponse(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3), byteArrayOf(4), "C".toByteArray(), 0)
        assertEquals(
            "{\"authForm\":0,\"message\":32785,\"payload\":{\"authData\":\"01\",\"challenge\":\"02\",\"epk\":\"03\",\"salt\":\"04\"," +
                "\"version\":{\"currentVersion\":\"1.0.0\",\"minVersion\":\"1.0.0\"},\"peerAuthId\":\"43\",\"peerUserType\":\"0\"}}",
            start,
        )
        assertEquals(665, HiChainBuild.exchangeResponse(ByteArray(310)).length)
    }

    @Test
    fun versionParsingFollowsPeerRules() {
        fun v(text: String) = KeyAgreementVersion.parse(text).toString()
        assertEquals("1.0.0", v("1.0.0"))
        assertEquals("1.0.0", v("1..0"))
        assertEquals("2.0.3", v(" 2.x.3"))
        assertEquals("1.2.0", v("1.2"))
        assertEquals("0.0.0", v(""))
        assertEquals("7.8.9", v("7.8.9.10"))
        assertEquals("4294967295.0.0", v("-1.0.0"))
        assertEquals("4294967295.0.0", v("99999999999999999999999.0.0"))
        assertEquals("12.0.0", v("+12abc.0.0"))

        fun ok(current: String, minimum: String) =
            KeyAgreementVersion.isPeerSupported(KeyAgreementVersion.parse(current), KeyAgreementVersion.parse(minimum))
        assertTrue(ok("1.0.0", "1.0.0"))
        assertTrue(ok("2.0.0", "0.9.0"))
        assertTrue(ok("1.0.1", "1.0.0"))
        assertFalse(ok("0.9.9", "0.9.0"), "对端当前版本比本端低")
        assertFalse(ok("2.0.0", "1.0.1"), "对端要求的最低版本比本端高")
    }

    @Test
    fun requestParsersAcceptPeerFormats() {
        val pake = HiChainParse.pakeRequest(obj("{\"version\":{\"currentVersion\":\"1.0.0\",\"minVersion\":\"1.0.0\"},\"support256mod\":true,\"operationCode\":1}"))
        assertEquals(1, pake.operationCode)
        // STS 开始请求里 peerUserType、keyLength 是字符串，pkgName、peerAuthId 可缺
        val sts = HiChainParse.authStartRequest(obj(
            "{\"challenge\":\"00\",\"epk\":\"\",\"operationCode\":2,\"version\":{\"currentVersion\":\"1.0.0\",\"minVersion\":\"1.0.0\"}," +
                "\"peerUserType\":\"1\",\"keyLength\":\"16\"}",
        ))
        assertEquals(2, sts.operationCode)
        assertContentEquals(byteArrayOf(0), sts.challenge)
        assertEquals(0, sts.epk.size)
        val confirm = HiChainParse.pakeClientConfirm(obj("{\"kcfData\":\"aB\", \"challenge\":\"\", \"epk\":\"02\"}"))
        assertContentEquals(byteArrayOf(0xAB.toByte()), confirm.kcf)
    }

    @Test
    fun requestParsersRejectMalformedFields() {
        val version = "\"version\":{\"currentVersion\":\"1.0.0\",\"minVersion\":\"1.0.0\"}"
        val bad = listOf(
            { HiChainParse.pakeRequest(obj("{$version}")) },
            { HiChainParse.pakeRequest(obj("{$version,\"operationCode\":-1}")) },
            { HiChainParse.pakeRequest(obj("{$version,\"operationCode\":\"1\"}")) },
            { HiChainParse.pakeRequest(obj("{\"operationCode\":1}")) },
            { HiChainParse.pakeRequest(obj("{\"operationCode\":1,\"version\":\"1.0.0\"}")) },
            { HiChainParse.pakeRequest(obj("{\"operationCode\":1,\"version\":{\"currentVersion\":\"1.0.0\"}}")) },
            { HiChainParse.pakeClientConfirm(obj("{\"kcfData\":\"00\",\"challenge\":\"${"00".repeat(17)}\",\"epk\":\"02\"}")) },
            { HiChainParse.pakeClientConfirm(obj("{\"kcfData\":\"${"00".repeat(33)}\",\"challenge\":\"00\",\"epk\":\"02\"}")) },
            { HiChainParse.pakeClientConfirm(obj("{\"kcfData\":\"0\",\"challenge\":\"00\",\"epk\":\"02\"}")) },
            { HiChainParse.pakeClientConfirm(obj("{\"kcfData\":\"zz\",\"challenge\":\"00\",\"epk\":\"02\"}")) },
            { HiChainParse.pakeClientConfirm(obj("{\"challenge\":\"00\",\"epk\":\"02\"}")) },
            { HiChainParse.exchange(obj("{\"exAuthInfo\":\"\"}")) },
            { HiChainParse.exchange(obj("{\"exAuthInfo\":\"${"00".repeat(351)}\"}")) },
            { HiChainParse.exchange(obj("{\"exAuthInfo\":12}")) },
            { HiChainParse.authStartRequest(obj("{\"challenge\":\"00\",\"epk\":\"${"00".repeat(33)}\",\"operationCode\":2,$version}")) },
            { HiChainParse.authAckRequest(obj("{\"authData\":\"${"00".repeat(257)}\"}")) },
        )
        for ((index, call) in bad.withIndex()) {
            assertFailsWith<MalformedMessageException>("用例 $index") { call() }
        }
    }

    @Test
    fun jsonNumberRulesMatchPeerParser() {
        fun n(text: String) = Json.parseToJsonElement(text).numberAsInt()
        assertEquals(1, n("1.9"))
        assertEquals(-1, n("-1.9"))
        assertEquals(Int.MAX_VALUE, n("1e10"))
        assertEquals(Int.MIN_VALUE, n("-1e10"))
        assertNull(n("\"1\""))
        assertNull(n("true"))
        assertNull(n("null"))
        fun s(text: String) = Json.parseToJsonElement(text).strictInt()
        assertEquals(1, s("1.0"))
        assertNull(s("1.5"))
        assertNull(s("2147483648"))
        assertEquals(Int.MIN_VALUE, s("-2147483648"))
        assertNull(s("\"3\""))
    }

    @Test
    fun hexDecodingIsStrict() {
        assertContentEquals(byteArrayOf(0x0A, 0xBC.toByte()), decodeHex("0abC", 2))
        assertNull(decodeHex("0abC", 1))
        assertNull(decodeHex("abc", 4))
        assertNull(decodeHex("０１", 4), "全角数字不算十六进制")
        assertContentEquals(ByteArray(0), decodeHex("", 0))
    }

    @Test
    fun objectEndSkipsStrings() {
        val text = "  {\"a\":\"}{\\\"\",\"b\":[1,{\"c\":2}]} tail"
        assertEquals(text.indexOf(" tail"), jsonObjectEnd(text, 2))
        assertEquals(-1, jsonObjectEnd("{\"a\":1", 0))
        assertEquals(-1, jsonObjectEnd("x{}", 0))
    }

    /** 期望值由 Python json.dumps(..., ensure_ascii=False, separators=(",", ":")) 生成。 */
    @Test
    fun compactJsonMatchesPythonDumps() {
        val input = obj("{\"a\":\"x\\\"y\\\\z\",\"b\":\"中文\",\"c\":\"\\n\\r\\t\\b\\f\\u0001\\u001f\\u007f\",\"d\":\"\\/<>&'\",\"e\":\"\\u2028\"}")
        assertEquals(
            "{\"a\":\"x\\\"y\\\\z\",\"b\":\"中文\",\"c\":\"\\n\\r\\t\\b\\f\\u0001\\u001f\u007f\",\"d\":\"/<>&'\",\"e\":\"\u2028\"}",
            CompactJson.write(input),
        )
        assertEquals(
            "{\"message\":32896,\"payload\":{\"errorCode\":17},\"list\":[1,true,null,\"s\"]}",
            CompactJson.write(obj("{\"message\":32896, \"payload\":{\"errorCode\":17},\"list\":[1, true, null, \"s\"]}")),
        )
        assertEquals("{\"k\":\"v\\\"\"}", CompactJson.writeStringMap(linkedMapOf("k" to "v\"")))
        assertEquals("\"a\\u0000b\"", CompactJson.quote("a\u0000b"))
        assertEquals(JsonPrimitive("中\"文").toString(), CompactJson.quote("中\"文"))
    }

    @Test
    fun parseJsonObjectRejectsNonObjects() {
        assertFailsWith<MalformedMessageException> { parseJsonObject("[]", "x") }
        assertFailsWith<MalformedMessageException> { parseJsonObject("{", "x") }
        assertFailsWith<MalformedMessageException> { parseJsonObject("{} {}", "x") }
        assertFailsWith<MalformedMessageException> { parseJsonObject("", "x") }
    }
}
