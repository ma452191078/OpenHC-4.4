package io.github.gcbond.openhc.ui

import io.github.gcbond.openhc.connection.ConnectionUiState
import io.github.gcbond.openhc.connection.Notice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 首页状态卡不重复显示身份参数的提示（由首页的身份参数卡片按当前结果显示）。 */
class IdentityHomeNoticeTest {

    @Test
    fun identityNoticesAreLeftToTheIdentityCard() {
        assertNull(ConnectionUiState(notice = Notice.IdentityMissing).withoutIdentityNotice().notice)
        assertNull(ConnectionUiState(notice = Notice.IdentityInvalid("x")).withoutIdentityNotice().notice)
    }

    @Test
    fun otherNoticesStay() {
        val vpn = ConnectionUiState(notice = Notice.VpnConsentRequired)
        assertEquals(vpn, vpn.withoutIdentityNotice())
        val failed = ConnectionUiState(notice = Notice.StartFailed("x"))
        assertEquals(failed, failed.withoutIdentityNotice())
    }
}
