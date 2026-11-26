package io.github.gcbond.openhc.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 版本号比较、tag 解析、SHA256 提取这些纯逻辑。 */
class UpdateVersionsTest {

    @Test
    fun tagNormalizesWithAndWithoutV() {
        assertEquals("0.0.2", UpdateVersions.fromTag("v0.0.2"))
        assertEquals("0.0.2", UpdateVersions.fromTag("V0.0.2"))
        assertEquals("0.0.2", UpdateVersions.fromTag("0.0.2"))
        assertEquals("1.2.3.4", UpdateVersions.fromTag("v1.2.3.4"))
    }

    @Test
    fun nonVersionTagsAreIgnored() {
        assertNull(UpdateVersions.fromTag("release-1"))
        assertNull(UpdateVersions.fromTag("v0.0.2-beta"))
        assertNull(UpdateVersions.fromTag(""))
        assertNull(UpdateVersions.fromTag("v"))
    }

    @Test
    fun numericCompareNotLexicographic() {
        assertTrue(UpdateVersions.isNewer("0.0.10", "0.0.9"))
        assertTrue(UpdateVersions.isNewer("0.1", "0.0.9"))
        assertTrue(UpdateVersions.isNewer("1.0.0", "0.99.99"))
        assertFalse(UpdateVersions.isNewer("0.0.1", "0.0.1"))
        assertFalse(UpdateVersions.isNewer("0.0.1", "0.0.2"))
    }

    @Test
    fun missingSegmentsCountAsZero() {
        assertEquals(0, UpdateVersions.compare("0.1", "0.1.0"))
        assertEquals(1, UpdateVersions.compare("0.1.0.1", "0.1"))
    }

    @Test
    fun sha256ParsesCommonWordings() {
        val hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        assertEquals(hash, UpdateVersions.parseSha256("SHA256: $hash"))
        assertEquals(hash, UpdateVersions.parseSha256("SHA256 校验值：$hash\n"))
        assertEquals(hash, UpdateVersions.parseSha256("sha-256=$hash"))
    }

    @Test
    fun sha256NeedsFullSixtyFourHexChars() {
        assertNull(UpdateVersions.parseSha256("SHA256: 0123456789abcdef"))
        assertNull(UpdateVersions.parseSha256("没有校验值"))
    }

    @Test
    fun assetNameAndUrlFollowReleaseConvention() {
        assertEquals("openhc-android4.4-0.0.2.apk", UpdateVersions.assetName("0.0.2"))
        assertEquals(
            "https://github.com/ma452191078/OpenHC-4.4/releases/download/v0.0.2/openhc-android4.4-0.0.2.apk",
            UpdateVersions.downloadUrl("v0.0.2", "0.0.2"),
        )
    }
}
