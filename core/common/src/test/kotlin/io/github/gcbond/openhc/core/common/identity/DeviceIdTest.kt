package io.github.gcbond.openhc.core.common.identity

import io.github.gcbond.openhc.core.common.hexToBytes
import io.github.gcbond.openhc.core.common.toHex
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * 设备 ID 与蓝牙地址。车型编号、蓝牙地址全是合成值；期望的设备 ID 由另一份独立实现算出。
 */
class DeviceIdTest {
    private val host1 = HostIdentity(modelId = "ABCDEF", subModelId = "01", brand = "OpenHC", deviceName = "OpenHC Car")
    private val host2 = HostIdentity(modelId = "123456", subModelId = "78", brand = "OpenHC", deviceName = "OpenHC Car")

    private val id1 = "CD1C73F369C5FE0B80808D3D0335EAF7D99B2AB9FE15403DC621C166FBADCB6F"
    private val id2 = "8B0B8B908B3F4A710D3E64AC50D1B5888415BCB858ABC4326990A99D0C12410E"
    private val id1Unavailable = "091663FE36C73520BF769477343DCE2970328C50E05D70EEE1E91A618985D79B"

    @Test
    fun matchesReferenceValues() {
        assertEquals(id1, DeviceId.hex(host1, "02:11:22:33:44:55"))
        assertEquals(id2, DeviceId.hex(host2, "0A:1B:2C:3D:4E:5F"))
    }

    /** 哈希的输入是大写十六进制文本：车型编号 ‖ 子编号 ‖ 蓝牙地址。 */
    @Test
    fun hashInputIsUppercaseHexText() {
        val expected = MessageDigest.getInstance("SHA-256").digest("ABCDEF01021122334455".toByteArray(Charsets.UTF_8))
        assertEquals(id1, expected.toHex(upperCase = true))
        val raw = DeviceId.derive("abcdef".hexToBytes(), 0x01, "021122334455".hexToBytes())
        assertEquals(DeviceId.SIZE, raw.size)
        assertContentEquals(expected, raw)
    }

    @Test
    fun addressCaseAndSeparatorDoNotMatter() {
        assertEquals(id1, DeviceId.hex(host1, "02-11-22-33-44-55"))
        assertEquals(id2, DeviceId.hex(host2, "0a:1b:2c:3d:4e:5f"))
    }

    /** 取不到地址（null）或是 02:00:00:00:00:00：哈希改用 6 个 0 字节。 */
    @Test
    fun fallbackWhenAddressUnavailable() {
        assertEquals(id1Unavailable, DeviceId.hex(host1, null))
        assertEquals(id1Unavailable, DeviceId.hex(host1, BluetoothAddress.UNAVAILABLE))
        // 全 0 地址的哈希输入相同，结果也相同；别的地址不受影响。
        assertEquals(id1Unavailable, DeviceId.hex(host1, "00:00:00:00:00:00"))
        assertNotEquals(id1Unavailable, DeviceId.hex(host1, "02:00:00:00:00:01"))
        assertNotEquals(id1Unavailable, DeviceId.hex(host1, "03:00:00:00:00:00"))
        val raw = DeviceId.derive("abcdef".hexToBytes(), 1, BluetoothAddress.parse(BluetoothAddress.UNAVAILABLE))
        assertEquals(id1Unavailable, raw.toHex(upperCase = true))
    }

    @Test
    fun deriveRejectsBadLengths() {
        val bt = ByteArray(6)
        assertFailsWith<IllegalArgumentException> { DeviceId.derive(ByteArray(2), 0, bt) }
        assertFailsWith<IllegalArgumentException> { DeviceId.derive(ByteArray(3), 256, bt) }
        assertFailsWith<IllegalArgumentException> { DeviceId.derive(ByteArray(3), -1, bt) }
        assertFailsWith<IllegalArgumentException> { DeviceId.derive(ByteArray(3), 0, ByteArray(5)) }
    }

    @Test
    fun bluetoothAddressRoundTrip() {
        assertContentEquals("0a1b2c3d4e5f".hexToBytes(), BluetoothAddress.parse("0a:1B:2c:3D:4e:5F"))
        assertContentEquals("0a1b2c3d4e5f".hexToBytes(), BluetoothAddress.parse("0A-1B-2C-3D-4E-5F"))
        assertEquals("0A:1B:2C:3D:4E:5F", BluetoothAddress.format("0a1b2c3d4e5f".hexToBytes()))
        assertEquals(BluetoothAddress.UNAVAILABLE, BluetoothAddress.format(byteArrayOf(2, 0, 0, 0, 0, 0)))
        assertFailsWith<IllegalArgumentException> { BluetoothAddress.format(ByteArray(5)) }
    }

    @Test
    fun bluetoothAddressRejectsBadText() {
        val bad = listOf(
            "021122334455", "02:11:22:33:44", "02:11:22:33:44:5G", "02:11-22:33:44:55", "02:11:22:33:44:55:66",
            "02.11.22.33.44.55", "０2:11:22:33:44:55", " 2:11:22:33:44:55", "",
        )
        for (text in bad) {
            assertFailsWith<IllegalArgumentException>(text) { BluetoothAddress.parse(text) }
            assertFailsWith<IllegalArgumentException>(text) { DeviceId.hex(host1, text) }
        }
    }
}
