package io.github.gcbond.openhc.wireless

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 「蓝牙连上自动连接」：只认手机，只用数据线连接开着时不听。 */
class BluetoothTriggerTest {

    @Test
    fun onlyPhonesCount() {
        assertTrue(BluetoothTrigger.isPhone(0x0200))
        assertFalse(BluetoothTrigger.isPhone(0x0400), "音视频设备（耳机、音箱）")
        assertFalse(BluetoothTrigger.isPhone(0x0700), "可穿戴设备（手表）")
        assertFalse(BluetoothTrigger.isPhone(null), "读不到设备类")
    }

    @Test
    fun listensOnlyWhenOnAndNotWiredOnly() {
        assertTrue(BluetoothTrigger.listens(connectOnBluetooth = true, wiredOnly = false))
        assertFalse(BluetoothTrigger.listens(connectOnBluetooth = false, wiredOnly = false))
        assertFalse(BluetoothTrigger.listens(connectOnBluetooth = true, wiredOnly = true))
    }
}
