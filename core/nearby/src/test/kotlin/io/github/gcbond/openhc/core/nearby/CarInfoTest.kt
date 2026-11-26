package io.github.gcbond.openhc.core.nearby

import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.common.identity.HostIdentity
import io.github.gcbond.openhc.core.common.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** carInfo 组包、解析与 deviceId。 */
class CarInfoTest {

    @Test
    fun carInfoMatchesReference() {
        val one = NearbySession.carInfo(Vectors.HOST_1, Vectors.BT_1, Vectors.IP_1, Vectors.PORT_1)
        assertEquals(Vectors.CAR_INFO_1, one.toHex())
        val two = NearbySession.carInfo(Vectors.HOST_2, Vectors.BT_2, Vectors.IP_2, Vectors.PORT_2)
        assertEquals(Vectors.CAR_INFO_2, two.toHex())
        assertEquals(NearbySession.CAR_INFO_LENGTH, one.size)
        assertEquals(CarInfo.LENGTH, NearbySession.CAR_INFO_LENGTH)
    }

    @Test
    fun layoutFields() {
        val bytes = hex(Vectors.CAR_INFO_1)
        assertEquals("hwnearby", String(bytes, 0, 8, Charsets.US_ASCII))
        // 总长 91（小端）+ 9 字节固定头
        assertEquals("5b00" + "010000010100000101", bytes.copyOfRange(8, 19).toHex())
        // 最后一个 TLV：07 04 端口 u32 小端
        assertEquals("0704419c0000", bytes.copyOfRange(85, 91).toHex())
    }

    @Test
    fun deviceIdMatchesReference() {
        assertEquals(Vectors.DEVICE_ID_1, NearbySession.deviceId(Vectors.HOST_1, Vectors.BT_1))
        assertEquals(Vectors.DEVICE_ID_2, NearbySession.deviceId(Vectors.HOST_2, Vectors.BT_2))
        // 地址大小写、分隔符不影响结果：哈希的输入是字节转成的大写十六进制。
        assertEquals(Vectors.DEVICE_ID_1, NearbySession.deviceId(Vectors.HOST_1, "02-11-22-33-44-55"))
        assertEquals(Vectors.DEVICE_ID_2, NearbySession.deviceId(Vectors.HOST_2, "0a:1b:2c:3d:4e:5f"))
    }

    @Test
    fun deviceIdFallbackWhenAddressUnavailable() {
        assertEquals(Vectors.DEVICE_ID_1_UNAVAILABLE, NearbySession.deviceId(Vectors.HOST_1, "02:00:00:00:00:00"))
        assertEquals(Vectors.DEVICE_ID_1_UNAVAILABLE, NearbySession.deviceId(Vectors.HOST_1, null))
        // 规则只针对这个特定值：全 0 地址本身算出来也一样（哈希输入相同），别的地址不受影响。
        assertEquals(Vectors.DEVICE_ID_1_UNAVAILABLE, NearbySession.deviceId(Vectors.HOST_1, "00:00:00:00:00:00"))
        assertFalse(Vectors.DEVICE_ID_1_UNAVAILABLE == NearbySession.deviceId(Vectors.HOST_1, "02:00:00:00:00:01"))
    }

    @Test
    fun unavailableAddressStillGoesOnTheWire() {
        for (bt in listOf(null, "02:00:00:00:00:00")) {
            val info = CarInfo.parse(NearbySession.carInfo(Vectors.HOST_1, bt, Vectors.IP_1, Vectors.PORT_1))
            // 线上的地址字段照填 02:00:00:00:00:00，deviceId 字段用退化后的哈希。
            assertEquals("020000000000", info.bluetoothAddress.toHex())
            assertEquals(Vectors.DEVICE_ID_1_UNAVAILABLE, info.deviceId.toHex(upperCase = true))
        }
    }

    @Test
    fun parseRoundTrip() {
        val info = CarInfo.parse(hex(Vectors.CAR_INFO_2))
        assertEquals("123456", info.modelId.toHex(upperCase = true))
        assertEquals(0x78, info.subModelId)
        assertEquals(Vectors.DEVICE_ID_2, info.deviceId.toHex(upperCase = true))
        assertEquals("0a1b2c3d4e5f", info.bluetoothAddress.toHex())
        assertEquals(Vectors.IP_2, info.hostIp)
        assertEquals(Vectors.PORT_2, info.port)
        assertContentEquals(hex(Vectors.CAR_INFO_2), info.encode())
    }

    @Test
    fun rejectsBadHostIp() {
        val bad = listOf(
            "192.168.66.12",    // 13 个字符
            "192.168.66.1234",  // 15 个字符
            "192.168.066.100".take(14), // 前导 0
            "192.168.6a.100",   // 非数字
            "192.168.66.300",   // 超过 255
            "1921.168.6.100",   // 段太长
            "192.168.66..10",   // 空段
            "192.168.66.1.1",   // 5 段
            "１92.168.66.100",  // 全角数字
        )
        for (ip in bad) {
            val e = assertFailsWith<IllegalArgumentException>(ip) {
                NearbySession.carInfo(Vectors.HOST_1, Vectors.BT_1, ip, Vectors.PORT_1)
            }
            assertTrue(e.message!!.contains("IP"), e.message)
        }
        val tooShort = assertFailsWith<IllegalArgumentException> {
            NearbySession.carInfo(Vectors.HOST_1, Vectors.BT_1, "10.0.0.1", Vectors.PORT_1)
        }
        assertTrue(tooShort.message!!.contains("14"), tooShort.message)
        // 14 个字符的其他规范 IPv4 也接受。
        NearbySession.carInfo(Vectors.HOST_1, Vectors.BT_1, "172.16.100.200", Vectors.PORT_1)
    }

    @Test
    fun rejectsBadPortAndAddress() {
        for (port in intArrayOf(0, -1, 65536)) {
            assertFailsWith<IllegalArgumentException>("端口 $port") {
                NearbySession.carInfo(Vectors.HOST_1, Vectors.BT_1, Vectors.IP_1, port)
            }
        }
        NearbySession.carInfo(Vectors.HOST_1, Vectors.BT_1, Vectors.IP_1, 1)
        val badAddresses = listOf(
            "021122334455", "02:11:22:33:44", "02:11:22:33:44:5G", "02:11-22:33:44:55", "02:11:22:33:44:55:66",
        )
        for (bt in badAddresses) {
            assertFailsWith<IllegalArgumentException>(bt) { NearbySession.deviceId(Vectors.HOST_1, bt) }
        }
    }

    @Test
    fun parseRejectsMalformed() {
        val good = hex(Vectors.CAR_INFO_1)
        fun mutate(i: Int, v: Int) = good.copyOf().also { it[i] = v.toByte() }
        val cases = listOf(
            good.copyOf(90),                       // 长度不对
            good + byteArrayOf(0),                 // 长度不对
            mutate(0, 'H'.code),                   // 魔数
            mutate(8, 90),                         // 总长字段
            mutate(10, 2),                         // 固定头
            mutate(19, 5),                         // 未知 tag
            mutate(20, 4),                         // 长度与已知布局不符
            mutate(85, 1),                         // 端口 TLV 的 tag 改成 1（长度对不上）
            mutate(77, 'x'.code),                  // IP 不是数字
            mutate(77, 0x80),                      // IP 不是 ASCII
            good.copyOf().also { for (i in 87..90) it[i] = 0 }, // 端口 0
        )
        for ((i, bytes) in cases.withIndex()) {
            assertFailsWith<MalformedMessageException>("第 $i 种") { CarInfo.parse(bytes) }
        }
    }

    @Test
    fun identityToStringHidesValues() {
        val id = NearbyIdentity(Vectors.HOST_1, Vectors.BT_1)
        assertFalse(id.toString().contains(Vectors.DEVICE_ID_1), id.toString())
        assertFalse(id.toString().contains("22:33"), id.toString())
        val info = CarInfo.create(id, Vectors.IP_1, Vectors.PORT_1)
        assertFalse(info.toString().contains(Vectors.DEVICE_ID_1.take(8)), info.toString())
    }

    @Test
    fun hostIdentityFieldsGoIntoTlvs() {
        val host = HostIdentity(modelId = "000001", subModelId = "FE", brand = "OpenHC", deviceName = "OpenHC Car")
        val info = CarInfo.parse(NearbySession.carInfo(host, Vectors.BT_1, Vectors.IP_1, Vectors.PORT_1))
        assertEquals("000001", info.modelId.toHex())
        assertEquals(0xFE, info.subModelId)
    }
}
