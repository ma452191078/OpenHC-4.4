package io.github.gcbond.openhc.identity

import io.github.gcbond.openhc.IdentityLoader
import io.github.gcbond.openhc.identity.IdentityFixtures.ROOT
import io.github.gcbond.openhc.identity.IdentityFixtures.assertNoKeyText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/** 安装包里的身份参数：能用、没有、不能用。值都是合成的。 */
class IdentityResolutionTest {
    private val bundled = StoredText.Present(IdentityFixtures.json(modelId = "222222"))
    private val broken = StoredText.Present(IdentityFixtures.json().replace("\"format\": 1", "\"format\": 9"))

    @Test
    fun bundledCopyIsLoaded() {
        val loaded = assertIs<IdentityLoader.Result.Loaded>(IdentityResolution.resolve(bundled))
        assertEquals("222222", loaded.assets.host.modelId)
    }

    @Test
    fun missingWhenTheFileIsAbsent() {
        assertSame(IdentityLoader.Result.Missing, IdentityResolution.resolve(StoredText.Absent))
    }

    @Test
    fun brokenOrUnreadableFileIsReported() {
        assertIs<IdentityLoader.Result.Invalid>(IdentityResolution.resolve(broken))
        val unreadable = assertIs<IdentityLoader.Result.Invalid>(IdentityResolution.resolve(StoredText.Unreadable("读出错")))
        assertEquals("读出错", unreadable.reason)
    }

    @Test
    fun invalidReasonNeverContainsKeyMaterial() {
        // JSON 在密钥后面断掉：解析器的内部异常里可能带原文，交出来的说明里不能有。
        val json = IdentityFixtures.json()
        val truncated = json.substring(0, json.indexOf("constA"))
        val invalid = assertIs<IdentityLoader.Result.Invalid>(IdentityResolution.resolve(StoredText.Present(truncated)))
        assertNoKeyText(invalid.reason, ROOT)
        assertNoKeyText(StoredText.Present(json).toString(), ROOT)
    }
}
