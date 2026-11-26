package io.github.gcbond.openhc.core.nearby

import java.util.Locale
import java.util.UUID

/**
 * 车机 GATT 服务端的布局（BLE 上的协议事实）。和广播（[BleAdvertisement]）一起由车机挂出来，两个主服务：
 *
 * | 服务 | 特征 | 属性 | 读 / 写 | 客户端配置描述符（CCCD）初值 |
 * |---|---|---|---|---|
 * | `FE35` | `2A00`（交换） | 读、写 | 可读可写 | — |
 * | | `2A01`（通知） | 通知、指示 | 可读可写 | 开（`01 00`） |
 * | | `2A02`（协同接收） | 写 | 只写 | — |
 * | | `2A03`（协同通知） | 通知、指示 | 可读可写 | 关（`00 00`） |
 * | `046A`（老版本） | `046C`（老版本交换） | 读、写 | 可读可写 | — |
 *
 * 两个服务要**串行**添加：前一个的添加完成回调到了再加下一个（安卓的 GATT 服务端同一时间只接受一个待加的服务）。
 *
 * 各特征的用途：
 * - `2A00` 写 / `2A01` 通知：把 nearby 控制通道承载在 BLE 上（通道号 1）。客户端写进来的字节按与有线、蓝牙相同的
 *   控制帧格式拆帧，车机回的帧经 `2A01` 通知发出，按「MTU − 3」切片、一片发完再发下一片。握手里对端发 TLV7（带它自己的
 *   蓝牙地址）时，车机回 TLV8（车机经典蓝牙地址）——这是没拿到车机真实地址的客户端取地址的办法。
 * - `2A02` 写 / `2A03` 通知：协同业务的透传。
 * - `046C`：老版本的组网地址交换。客户端先读它；车机没有可用的组网地址时回文本 `busy`（[LEGACY_BUSY]）。
 *
 * 目前车机侧只挂服务、按 [NearbyGattResponder] 的规则回应，不在 GATT 上跑控制通道。实测的手机回连时不连 GATT
 * （直接按广播发送地址连经典蓝牙）；另一类手机系统可能会连上来取车机地址（没有验证）。
 */
object NearbyGatt {
    /** 蓝牙基础 UUID：16 位 UUID 填在第 3、4 字节。必须最先初始化，下面的常量都由它展开。 */
    private val BASE: UUID = UUID.fromString("00000000-0000-1000-8000-00805F9B34FB")

    /** 主服务（与蓝牙控制通道的 RFCOMM 服务 UUID 相同）。 */
    val SERVICE: UUID = NearbySession.BLUETOOTH_SERVICE_UUID

    /** 老版本的主服务。 */
    val LEGACY_SERVICE: UUID = uuid16(0x046A)

    /** 控制帧：客户端写。 */
    val EXCHANGE: UUID = uuid16(0x2A00)

    /** 控制帧：车机通知。 */
    val NOTIFICATION: UUID = uuid16(0x2A01)

    /** 协同业务：客户端写。 */
    val SYNERGY_RECEIVE: UUID = uuid16(0x2A02)

    /** 协同业务：车机通知。 */
    val SYNERGY_NOTIFY: UUID = uuid16(0x2A03)

    /** 老版本的交换特征。 */
    val LEGACY_EXCHANGE: UUID = uuid16(0x046C)

    /** 客户端配置描述符（CCCD）。 */
    val CLIENT_CONFIG: UUID = uuid16(0x2902)

    /** 特征属性位（蓝牙核心规范的取值）。 */
    const val PROPERTY_READ = 0x02
    const val PROPERTY_WRITE = 0x08
    const val PROPERTY_NOTIFY = 0x10
    const val PROPERTY_INDICATE = 0x20

    /** CCCD 的「开通知」「关」两个值（小端 16 位）。 */
    val NOTIFICATIONS_ENABLED: ByteArray get() = byteArrayOf(0x01, 0x00)
    val NOTIFICATIONS_DISABLED: ByteArray get() = byteArrayOf(0x00, 0x00)

    /** 老版本交换特征上「没有可用的组网地址」的回答。 */
    val LEGACY_BUSY: ByteArray get() = "busy".toByteArray(Charsets.US_ASCII)

    /**
     * 一个特征。[readable] / [writable] 是属性之外的访问权限；[clientConfig] 是 CCCD 的初值，没有 CCCD 为 null。
     */
    class Characteristic(
        val uuid: UUID,
        val properties: Int,
        val readable: Boolean,
        val writable: Boolean,
        clientConfig: ByteArray?,
    ) {
        private val config = clientConfig?.copyOf()

        val clientConfig: ByteArray? get() = config?.copyOf()

        val hasClientConfig: Boolean get() = config != null

        override fun toString() = "Characteristic(${short(uuid)})"
    }

    class Service(val uuid: UUID, val characteristics: List<Characteristic>) {
        override fun toString() = "Service(${short(uuid)}, ${characteristics.size} 个特征)"
    }

    /** 按添加顺序排好的两个服务。 */
    val SERVICES: List<Service> = listOf(
        Service(
            SERVICE,
            listOf(
                Characteristic(EXCHANGE, PROPERTY_READ or PROPERTY_WRITE, readable = true, writable = true, clientConfig = null),
                Characteristic(
                    NOTIFICATION, PROPERTY_NOTIFY or PROPERTY_INDICATE, readable = true, writable = true,
                    clientConfig = NOTIFICATIONS_ENABLED,
                ),
                Characteristic(SYNERGY_RECEIVE, PROPERTY_WRITE, readable = false, writable = true, clientConfig = null),
                Characteristic(
                    SYNERGY_NOTIFY, PROPERTY_NOTIFY or PROPERTY_INDICATE, readable = true, writable = true,
                    clientConfig = NOTIFICATIONS_DISABLED,
                ),
            ),
        ),
        Service(
            LEGACY_SERVICE,
            listOf(Characteristic(LEGACY_EXCHANGE, PROPERTY_READ or PROPERTY_WRITE, readable = true, writable = true, clientConfig = null)),
        ),
    )

    /** 布局里的特征；不认识返回 null。 */
    fun characteristic(uuid: UUID): Characteristic? = SERVICES.asSequence().flatMap { it.characteristics.asSequence() }.firstOrNull { it.uuid == uuid }

    /** 16 位 UUID 的短写（`2A00`），日志用；不是由基础 UUID 展开的原样输出。 */
    fun short(uuid: UUID): String {
        val sixteen = (uuid.mostSignificantBits ushr 32).toInt() and 0xFFFF
        return if (uuid == uuid16(sixteen)) String.format(Locale.ROOT, "%04X", sixteen) else uuid.toString()
    }

    /** 16 位 UUID 展开成 128 位（`0000xxxx-0000-1000-8000-00805F9B34FB`）。 */
    fun uuid16(value: Int): UUID {
        require(value in 0..0xFFFF) { "16 位 UUID 越界: $value" }
        return UUID(BASE.mostSignificantBits or (value.toLong() shl 32), BASE.leastSignificantBits)
    }
}
