package io.github.gcbond.openhc.identity

import io.github.gcbond.openhc.core.common.identity.IdentityAssets
import io.github.gcbond.openhc.identity.IdentityFixtures.ROOT
import io.github.gcbond.openhc.identity.IdentityFixtures.assertNoKeyText
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 规范格式的编码，以及粘贴、导入文本的清理与上限。 */
class IdentityJsonTest {
    private val bom = Char(0xFEFF).toString()

    @Test
    fun encodeThenParseGivesTheSameIdentity() {
        val assets = IdentityFixtures.assets()
        val json = IdentityJson.encode(assets)
        val again = IdentityAssets.parse(json)
        assertEquals(json, IdentityJson.encode(again))
        assertEquals(assets.host, again.host)
        assertContentEquals(ROOT, again.nearbyKeys.rootKey)
        // 和文档里的示例同一个结构，便于车主对照。
        val example = """{"format":1,"host":{"modelId":"000000","subModelId":"00","brand":"OpenHC","deviceName":"OpenHC"},""" +
            """"nearbyKeys":{"rootKey":"AAAAAAAAAAAAAAAAAAAAAA==","constA":"AAAAAAAAAAAAAAAAAAAAAA==","constB":"AAAAAAAAAAAAAAAAAAAAAA=="},""" +
            """"authService":{"packageName":"org.example.auth","serviceType":"EXAMPLE","envelopeVersion":"X_0@00000000000000"}}"""
        assertEquals(IdentityJson.encode(IdentityAssets.parse(example)), IdentityJson.encode(IdentityJson.parseText(example)))
    }

    @Test
    fun pastedTextIsTrimmedAndBomIsDropped() {
        val json = IdentityFixtures.json()
        val assets = IdentityJson.parseText(bom + "\n  $json  \n")
        assertEquals("ABCDEF01", assets.host.fullModel)
        assertEquals(json, IdentityJson.decodeUtf8((bom + json).toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun emptyOrOversizedTextIsRejected() {
        assertFailsWith<IllegalArgumentException> { IdentityJson.parseText("  \n ") }
        assertFailsWith<IllegalArgumentException> { IdentityJson.parseText(" ".repeat(IdentityJson.MAX_BYTES + 1)) }
    }

    @Test
    fun syntaxErrorMessageDoesNotQuoteTheInput() {
        val json = IdentityFixtures.json()
        val broken = json.replace("\"constA\"", "constA")
        val error = assertFailsWith<IllegalArgumentException> { IdentityJson.parseText(broken) }
        assertNoKeyText(error.message!!, ROOT)
    }

    @Test
    fun readingStopsAtTheLimit() {
        val atLimit = ByteArray(IdentityJson.MAX_BYTES) { 'a'.code.toByte() }
        assertEquals(IdentityJson.MAX_BYTES, IdentityJson.readLimited(ByteArrayInputStream(atLimit)).size)
        assertFailsWith<IdentityTooLargeException> {
            IdentityJson.readLimited(ByteArrayInputStream(ByteArray(IdentityJson.MAX_BYTES + 1)))
        }
    }
}
