package io.github.gcbond.openhc.identity

import io.github.gcbond.openhc.core.common.Base64
import io.github.gcbond.openhc.core.common.identity.IdentityAssets
import io.github.gcbond.openhc.core.common.toHex

/** 测试用的身份参数：全是合成值（递增字节、示例包名），不是任何真实取值。 */
internal object IdentityFixtures {
    /** 三段密钥：0x00..0x0F、0x10..0x1F、0x20..0x2F。 */
    val ROOT = ByteArray(16) { it.toByte() }
    val CONST_A = ByteArray(16) { (0x10 + it).toByte() }
    val CONST_B = ByteArray(16) { (0x20 + it).toByte() }

    const val PACKAGE = "org.example.auth"
    const val SERVICE = "EXAMPLE"
    const val ENVELOPE = "X_0@00000000000000"

    fun json(
        modelId: String = "ABCDEF",
        subModelId: String = "01",
        brand: String = "OpenHC",
        deviceName: String = "OpenHC Car",
        root: ByteArray = ROOT,
        constA: ByteArray = CONST_A,
        constB: ByteArray = CONST_B,
        packageName: String = PACKAGE,
        serviceType: String = SERVICE,
        envelope: String = ENVELOPE,
    ): String = IdentityJson.build(modelId, subModelId, brand, deviceName, root, constA, constB, packageName, serviceType, envelope)

    fun assets(
        modelId: String = "ABCDEF",
        subModelId: String = "01",
        brand: String = "OpenHC",
        deviceName: String = "OpenHC Car",
        root: ByteArray = ROOT,
        packageName: String = PACKAGE,
        serviceType: String = SERVICE,
        envelope: String = ENVELOPE,
    ): IdentityAssets = IdentityAssets.parse(
        json(modelId, subModelId, brand, deviceName, root, packageName = packageName, serviceType = serviceType, envelope = envelope),
    )

    /** 一段密钥可能出现在界面文字里的几种写法：都不许出现。 */
    fun spellings(key: ByteArray): List<String> {
        val base64 = Base64.encode(key)
        return listOf(base64, base64.trimEnd('='), key.toHex(), key.toHex(upperCase = true))
    }

    fun assertNoKeyText(text: String, vararg keys: ByteArray) {
        for (key in keys) {
            for (spelling in spellings(key)) {
                check(!text.contains(spelling)) { "文字里不该出现密钥内容：$text" }
            }
        }
    }
}
