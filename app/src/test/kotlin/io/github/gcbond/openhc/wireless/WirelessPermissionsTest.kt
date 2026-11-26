package io.github.gcbond.openhc.wireless

import io.github.gcbond.openhc.wireless.WirelessPermissions.BLUETOOTH_ADVERTISE
import io.github.gcbond.openhc.wireless.WirelessPermissions.BLUETOOTH_CONNECT
import io.github.gcbond.openhc.wireless.WirelessPermissions.COARSE_LOCATION
import io.github.gcbond.openhc.wireless.WirelessPermissions.FINE_LOCATION
import io.github.gcbond.openhc.wireless.WirelessPermissions.NEARBY_WIFI_DEVICES
import io.github.gcbond.openhc.wireless.WirelessPermissions.POST_NOTIFICATIONS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 无线连接要的运行时权限按系统版本定，申请之后的结论。 */
class WirelessPermissionsTest {

    @Test
    fun requiredPermissionsByVersion() {
        for (sdk in listOf(23, 26, 28, 29, 30)) assertEquals(listOf(FINE_LOCATION), WirelessPermissions.required(sdk), "安卓 $sdk")
        for (sdk in listOf(31, 32)) assertEquals(listOf(BLUETOOTH_CONNECT, FINE_LOCATION), WirelessPermissions.required(sdk))
        for (sdk in listOf(33, 34, 36)) assertEquals(listOf(BLUETOOTH_CONNECT, NEARBY_WIFI_DEVICES), WirelessPermissions.required(sdk))
    }

    @Test
    fun requestListAddsCoarseOnTwelveAndNotificationsFromThirteen() {
        assertEquals(listOf(FINE_LOCATION), WirelessPermissions.toRequest(29, askNotifications = true), "安卓 13 以前没有通知权限")
        assertEquals(listOf(BLUETOOTH_CONNECT, FINE_LOCATION, COARSE_LOCATION), WirelessPermissions.toRequest(31, askNotifications = false))
        assertEquals(
            listOf(BLUETOOTH_CONNECT, NEARBY_WIFI_DEVICES, POST_NOTIFICATIONS),
            WirelessPermissions.toRequest(33, askNotifications = true),
        )
        assertEquals(listOf(BLUETOOTH_CONNECT, NEARBY_WIFI_DEVICES), WirelessPermissions.toRequest(36, askNotifications = false))
    }

    @Test
    fun advertisingPermissionIsAskedForButNeverRequired() {
        for (sdk in listOf(23, 29, 30)) assertEquals(emptyList(), WirelessPermissions.advertise(sdk), "安卓 $sdk 用清单里的旧权限")
        for (sdk in listOf(31, 33, 36)) assertEquals(listOf(BLUETOOTH_ADVERTISE), WirelessPermissions.advertise(sdk))
        assertEquals(
            listOf(BLUETOOTH_CONNECT, NEARBY_WIFI_DEVICES, BLUETOOTH_ADVERTISE, POST_NOTIFICATIONS),
            WirelessPermissions.toRequest(33, askNotifications = true, advertise = true),
        )
        assertEquals(listOf(FINE_LOCATION), WirelessPermissions.toRequest(29, askNotifications = false, advertise = true))
        // 缺广播权限不挡扫码：不在必须的里面，申请之后也不算缺。
        assertTrue(BLUETOOTH_ADVERTISE !in WirelessPermissions.required(36))
        assertNull(WirelessPermissions.afterRequest(33, { it != BLUETOOTH_ADVERTISE }, { true }))
    }

    @Test
    fun missingOnlyListsRequiredOnes() {
        val granted = setOf(BLUETOOTH_CONNECT, COARSE_LOCATION)
        assertEquals(listOf(FINE_LOCATION), WirelessPermissions.missing(31) { it in granted })
        assertEquals(emptyList(), WirelessPermissions.missing(33) { true })
    }

    @Test
    fun outcomeAfterTheSystemDialog() {
        assertNull(WirelessPermissions.afterRequest(33, { true }, { true }), "都给了")

        // 刚拒绝、系统还会再问：点「授权」再来一次。
        val again = assertNotNull(WirelessPermissions.afterRequest(33, { it == BLUETOOTH_CONNECT }, { true }))
        assertEquals(listOf(NEARBY_WIFI_DEVICES), again.missing)
        assertFalse(again.permanently)
        assertFalse(again.approximateOnly)

        // 拒绝后系统不再问（选了不再询问）：只能去系统设置。
        val forever = assertNotNull(WirelessPermissions.afterRequest(33, { false }, { false }))
        assertTrue(forever.permanently)

        // 安卓 12 上只给了大致位置。
        val approximate = assertNotNull(
            WirelessPermissions.afterRequest(31, { it == BLUETOOTH_CONNECT || it == COARSE_LOCATION }, { true }),
        )
        assertTrue(approximate.approximateOnly)
        assertEquals(listOf(FINE_LOCATION), approximate.missing)
        // 老版本没有「大致位置」这一选：缺精确位置就是缺。
        assertFalse(assertNotNull(WirelessPermissions.afterRequest(29, { it == COARSE_LOCATION }, { true })).approximateOnly)
    }
}
