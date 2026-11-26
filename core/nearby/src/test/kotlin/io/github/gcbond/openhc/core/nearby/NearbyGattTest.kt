package io.github.gcbond.openhc.core.nearby

import io.github.gcbond.openhc.core.nearby.NearbyGattResponder.Companion.STATUS_INVALID_ATTRIBUTE_LENGTH
import io.github.gcbond.openhc.core.nearby.NearbyGattResponder.Companion.STATUS_INVALID_OFFSET
import io.github.gcbond.openhc.core.nearby.NearbyGattResponder.Companion.STATUS_PREPARE_QUEUE_FULL
import io.github.gcbond.openhc.core.nearby.NearbyGattResponder.Companion.STATUS_READ_NOT_PERMITTED
import io.github.gcbond.openhc.core.nearby.NearbyGattResponder.Companion.STATUS_SUCCESS
import io.github.gcbond.openhc.core.nearby.NearbyGattResponder.Companion.STATUS_WRITE_NOT_PERMITTED
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** GATT 布局和回应规则。客户端地址是合成值。 */
class NearbyGattTest {

    private val phone = "02:00:00:00:00:01"
    private val other = "02:00:00:00:00:02"

    @Test
    fun layoutHasTwoServicesInAddOrder() {
        val services = NearbyGatt.SERVICES
        assertEquals(listOf("FE35", "046A"), services.map { NearbyGatt.short(it.uuid) })
        assertEquals(listOf("2A00", "2A01", "2A02", "2A03"), services[0].characteristics.map { NearbyGatt.short(it.uuid) })
        assertEquals(listOf("046C"), services[1].characteristics.map { NearbyGatt.short(it.uuid) })
        assertEquals(NearbySession.BLUETOOTH_SERVICE_UUID, NearbyGatt.SERVICE)
    }

    @Test
    fun characteristicPropertiesAndClientConfig() {
        val exchange = NearbyGatt.characteristic(NearbyGatt.EXCHANGE)!!
        assertEquals(0x0A, exchange.properties)
        assertTrue(exchange.readable && exchange.writable)
        assertFalse(exchange.hasClientConfig)

        val notification = NearbyGatt.characteristic(NearbyGatt.NOTIFICATION)!!
        assertEquals(0x30, notification.properties)
        assertContentEquals(byteArrayOf(1, 0), notification.clientConfig)

        val synergyReceive = NearbyGatt.characteristic(NearbyGatt.SYNERGY_RECEIVE)!!
        assertEquals(0x08, synergyReceive.properties)
        assertFalse(synergyReceive.readable)
        assertTrue(synergyReceive.writable)

        val synergyNotify = NearbyGatt.characteristic(NearbyGatt.SYNERGY_NOTIFY)!!
        assertEquals(0x30, synergyNotify.properties)
        assertContentEquals(byteArrayOf(0, 0), synergyNotify.clientConfig)

        val legacy = NearbyGatt.characteristic(NearbyGatt.LEGACY_EXCHANGE)!!
        assertEquals(0x0A, legacy.properties)
        assertNull(NearbyGatt.characteristic(NearbyGatt.CLIENT_CONFIG))
    }

    @Test
    fun sixteenBitUuidsExpandOnTheBluetoothBase() {
        assertEquals(UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb"), NearbyGatt.EXCHANGE)
        assertEquals(UUID.fromString("0000046c-0000-1000-8000-00805f9b34fb"), NearbyGatt.LEGACY_EXCHANGE)
        assertEquals("2902", NearbyGatt.short(NearbyGatt.CLIENT_CONFIG))
        val custom = UUID.fromString("12345678-0000-1000-8000-00805f9b34fb")
        assertEquals(custom.toString(), NearbyGatt.short(custom))
    }

    @Test
    fun legacyExchangeReadAnswersBusyWithLongReads() {
        val r = NearbyGattResponder()
        r.connected(phone)
        val first = r.readCharacteristic(phone, NearbyGatt.LEGACY_EXCHANGE, 0)
        assertEquals(STATUS_SUCCESS, first.status)
        assertEquals("busy", String(first.value!!, Charsets.US_ASCII))
        assertEquals("sy", String(r.readCharacteristic(phone, NearbyGatt.LEGACY_EXCHANGE, 2).value!!, Charsets.US_ASCII))
        assertEquals(0, r.readCharacteristic(phone, NearbyGatt.LEGACY_EXCHANGE, 4).value!!.size)
        assertEquals(STATUS_INVALID_OFFSET, r.readCharacteristic(phone, NearbyGatt.LEGACY_EXCHANGE, 5).status)
    }

    @Test
    fun otherReadsAreNotPermitted() {
        val r = NearbyGattResponder()
        for (uuid in listOf(NearbyGatt.EXCHANGE, NearbyGatt.NOTIFICATION, NearbyGatt.SYNERGY_NOTIFY)) {
            val reply = r.readCharacteristic(phone, uuid, 0)
            assertEquals(STATUS_READ_NOT_PERMITTED, reply.status)
            assertNull(reply.value)
        }
    }

    @Test
    fun plainWriteSucceedsAndIsHandedOver() {
        val r = NearbyGattResponder()
        val frame = byteArrayOf(0x7F, 0x00, 0x01, 0x02)
        val w = r.writeCharacteristic(phone, NearbyGatt.EXCHANGE, prepared = false, offset = 0, value = frame)
        assertEquals(STATUS_SUCCESS, w.reply.status)
        assertContentEquals(frame, w.reply.value)
        assertContentEquals(frame, w.received)
        assertEquals(NearbyGatt.EXCHANGE, w.characteristic)
        assertEquals(STATUS_INVALID_OFFSET, r.writeCharacteristic(phone, NearbyGatt.EXCHANGE, false, 1, frame).reply.status)
        val unknown = UUID.fromString("00001234-0000-1000-8000-00805f9b34fb")
        assertEquals(STATUS_WRITE_NOT_PERMITTED, r.writeCharacteristic(phone, unknown, false, 0, frame).reply.status)
    }

    @Test
    fun preparedWritesAreJoinedOnExecute() {
        val r = NearbyGattResponder()
        r.connected(phone)
        val a = r.writeCharacteristic(phone, NearbyGatt.EXCHANGE, prepared = true, offset = 0, value = byteArrayOf(1, 2, 3))
        assertEquals(STATUS_SUCCESS, a.reply.status)
        assertNull(a.received, "分段写执行前不算收到")
        r.writeCharacteristic(phone, NearbyGatt.EXCHANGE, prepared = true, offset = 3, value = byteArrayOf(4, 5))
        // 偏移对不上（不是接着写）回「偏移无效」，已攒的不受影响。
        assertEquals(STATUS_INVALID_OFFSET, r.writeCharacteristic(phone, NearbyGatt.EXCHANGE, true, 9, byteArrayOf(6)).reply.status)
        val done = r.executeWrite(phone, execute = true)
        assertEquals(STATUS_SUCCESS, done.reply.status)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), done.received)
        assertEquals(NearbyGatt.EXCHANGE, done.characteristic)
        // 执行过就清空。
        assertNull(r.executeWrite(phone, execute = true).received)
    }

    @Test
    fun cancelledOrOversizedPreparedWritesAreDropped() {
        val r = NearbyGattResponder(maxPreparedBytes = 4)
        r.writeCharacteristic(phone, NearbyGatt.EXCHANGE, true, 0, byteArrayOf(1, 2))
        assertNull(r.executeWrite(phone, execute = false).received)
        r.writeCharacteristic(phone, NearbyGatt.EXCHANGE, true, 0, byteArrayOf(1, 2, 3))
        assertEquals(STATUS_PREPARE_QUEUE_FULL, r.writeCharacteristic(phone, NearbyGatt.EXCHANGE, true, 3, byteArrayOf(4, 5)).reply.status)
        assertNull(r.executeWrite(phone, execute = true).received, "超限后攒的丢掉")
    }

    @Test
    fun clientConfigIsPerClientWithLayoutDefaults() {
        val r = NearbyGattResponder()
        r.connected(phone)
        r.connected(other)
        assertTrue(r.notificationsEnabled(phone, NearbyGatt.NOTIFICATION), "2A01 默认开")
        assertFalse(r.notificationsEnabled(phone, NearbyGatt.SYNERGY_NOTIFY), "2A03 默认关")
        assertContentEquals(byteArrayOf(1, 0), r.readDescriptor(phone, NearbyGatt.NOTIFICATION, NearbyGatt.CLIENT_CONFIG, 0).value)

        assertEquals(STATUS_SUCCESS, r.writeDescriptor(phone, NearbyGatt.SYNERGY_NOTIFY, NearbyGatt.CLIENT_CONFIG, 0, byteArrayOf(1, 0)).status)
        assertTrue(r.notificationsEnabled(phone, NearbyGatt.SYNERGY_NOTIFY))
        assertFalse(r.notificationsEnabled(other, NearbyGatt.SYNERGY_NOTIFY), "另一个客户端不受影响")

        assertEquals(STATUS_INVALID_ATTRIBUTE_LENGTH, r.writeDescriptor(phone, NearbyGatt.NOTIFICATION, NearbyGatt.CLIENT_CONFIG, 0, byteArrayOf(1)).status)
        assertEquals(STATUS_INVALID_OFFSET, r.writeDescriptor(phone, NearbyGatt.NOTIFICATION, NearbyGatt.CLIENT_CONFIG, 1, byteArrayOf(0, 0)).status)
        assertEquals(STATUS_WRITE_NOT_PERMITTED, r.writeDescriptor(phone, NearbyGatt.EXCHANGE, NearbyGatt.CLIENT_CONFIG, 0, byteArrayOf(1, 0)).status)

        r.disconnected(phone)
        assertFalse(r.notificationsEnabled(phone, NearbyGatt.SYNERGY_NOTIFY), "断开后回到初值")
        assertEquals(1, r.connectedCount)
    }

    @Test
    fun describeTellsLengthAndWhetherItLooksLikeAFrame() {
        assertEquals("0 字节", NearbyGattResponder.describe(ByteArray(0)))
        assertTrue(NearbyGattResponder.describe(byteArrayOf(0x7F, 1, 2)).startsWith("3 字节，首字节 7F（像"))
        assertEquals("2 字节，首字节 01", NearbyGattResponder.describe(byteArrayOf(1, 2)))
    }
}
