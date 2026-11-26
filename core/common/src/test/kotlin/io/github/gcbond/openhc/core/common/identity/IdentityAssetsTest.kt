package io.github.gcbond.openhc.core.common.identity

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class IdentityAssetsTest {

    // 全是假值：仓库里任何地方都不能出现真实的车型编号或密钥材料。
    private val sample = """
        {
          "format": 1,
          "host": { "modelId": "abcdef", "subModelId": "01", "brand": "OpenHC", "deviceName": "OpenHC Car" },
          "nearbyKeys": { "rootKey": "AAECAwQFBgc=", "constA": "CAkKCwwNDg8=", "constB": "EBESExQVFhc=" },
          "authService": { "packageName": "org.example.auth", "serviceType": "EXAMPLE", "envelopeVersion": "X_0@00000000000000" }
        }
    """.trimIndent()

    @Test
    fun parsesSample() {
        val assets = IdentityAssets.parse(sample)
        assertEquals("ABCDEF", assets.host.modelId)
        assertEquals("01", assets.host.subModelId)
        assertEquals("ABCDEF01", assets.host.fullModel)
        assertEquals("OpenHC Car", assets.host.deviceName)
        assertContentEquals(ByteArray(8) { it.toByte() }, assets.nearbyKeys.rootKey)
        assertContentEquals(ByteArray(8) { (it + 8).toByte() }, assets.nearbyKeys.constA)
        assertContentEquals(ByteArray(8) { (it + 16).toByte() }, assets.nearbyKeys.constB)
        assertEquals(AuthServiceIdentity("org.example.auth", "EXAMPLE"), assets.authService)
        assertEquals("X_0@00000000000000", assets.authEnvelopeVersion)
    }

    @Test
    fun keyMaterialIsNotPrinted() {
        val text = IdentityAssets.parse(sample).nearbyKeys.toString()
        assertFalse(text.contains("AAECAw"), text)
    }

    @Test
    fun keyMaterialIsDefensivelyCopied() {
        val keys = IdentityAssets.parse(sample).nearbyKeys
        keys.rootKey[0] = 99
        assertEquals(0, keys.rootKey[0].toInt())
    }

    @Test
    fun rejectsBadInput() {
        val cases = listOf(
            sample.replace("\"format\": 1", "\"format\": 2"),
            sample.replace("\"abcdef\"", "\"abcde\""),
            sample.replace("\"01\"", "\"1\""),
            sample.replace("\"OpenHC\"", "\"  \""),
            sample.replace("\"EBESExQVFhc=\"", "\"CAkK\""),
            // 三段都短于 5 字节
            sample.replace("\"AAECAwQFBgc=\"", "\"AAECAw==\"").replace("\"CAkKCwwNDg8=\"", "\"BAUGBw==\"").replace("\"EBESExQVFhc=\"", "\"CAkKCw==\""),
            sample.replace("\"AAECAwQFBgc=\"", "\"not base64!\""),
            sample.replace("\"rootKey\"", "\"rootkey\""),
            sample.replace("\"EXAMPLE\"", "\"\""),
            sample.replace("\"authService\"", "\"authservice\""),
            sample.replace("\"X_0@00000000000000\"", "\" \""),
            sample.replace("\"envelopeVersion\"", "\"version\""),
            "[]",
            "{",
        )
        for (json in cases) {
            assertFailsWith<IllegalArgumentException>(json) { IdentityAssets.parse(json) }
        }
    }
}
