package io.github.gcbond.openhc.connection

import kotlin.test.Test
import kotlin.test.assertEquals

/** 「插线时不再弹窗」：有线会话要不要先等系统按「默认使用」自动授权。 */
class UsbAutoConnectPolicyTest {

    @Test
    fun waitsOnlyWhenTheFilterIsOnAndTheUserAcceptedOnce() {
        assertEquals(UsbAutoConnectPolicy.PERMISSION_GRACE_MS, UsbAutoConnectPolicy.permissionGraceMs(enabled = true, attachAccepted = true))
        assertEquals(0L, UsbAutoConnectPolicy.permissionGraceMs(enabled = true, attachAccepted = false), "还没在系统框里同意过，系统不会自动授权")
        assertEquals(0L, UsbAutoConnectPolicy.permissionGraceMs(enabled = false, attachAccepted = true), "开关关着过滤器停用，系统不会自动授权")
        assertEquals(0L, UsbAutoConnectPolicy.permissionGraceMs(enabled = false, attachAccepted = false))
    }

    @Test
    fun graceIsShortEnoughNotToHoldUpAManualDialog() {
        // 系统的自动授权比设备接入广播晚几十到一百多毫秒；等太久的话，没勾「默认使用」时用户要白等。
        assertEquals(800L, UsbAutoConnectPolicy.PERMISSION_GRACE_MS)
    }
}
