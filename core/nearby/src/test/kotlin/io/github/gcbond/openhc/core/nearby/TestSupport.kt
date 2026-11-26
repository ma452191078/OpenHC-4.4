package io.github.gcbond.openhc.core.nearby

import io.github.gcbond.openhc.core.common.hexToBytes
import io.github.gcbond.openhc.core.common.identity.HostIdentity
import io.github.gcbond.openhc.core.common.identity.NearbyKeyMaterial
import java.security.SecureRandom

internal fun hex(text: String): ByteArray = text.hexToBytes()

/** start 起连续 n 个递增字节。 */
internal fun incrementing(start: Int, n: Int): ByteArray = ByteArray(n) { (start + it).toByte() }

/** 合成明文：第 i 个字节 = (i × mul + add) mod 256，与生成对照向量时的公式相同。 */
internal fun pattern(n: Int, mul: Int = 7, add: Int = 3): ByteArray =
    ByteArray(n) { ((it * mul + add) and 0xFF).toByte() }

/** 按给定顺序吐出字节的「随机数」，每次 nextBytes 取一段，长度必须对得上。 */
internal class FixedSecureRandom(vararg chunks: ByteArray) : SecureRandom() {
    private val queue = ArrayList(chunks.map { it.copyOf() })

    val remaining: Int get() = queue.size

    override fun nextBytes(bytes: ByteArray) {
        check(queue.isNotEmpty()) { "固定随机序列已经用完" }
        val next = queue.removeAt(0)
        check(next.size == bytes.size) { "要 ${bytes.size} 字节，序列里这一段是 ${next.size} 字节" }
        next.copyInto(bytes)
    }
}

/**
 * 对照向量：由另一份独立实现（Python）用下面这组**假常量**生成，Kotlin 实现必须逐字节一致。
 * 车型编号、蓝牙地址、IP、端口、种子、手机报文内容全部是合成值。
 */
internal object Vectors {
    /** 16 字节假密钥材料：rootKey = 00..0F，constA = 10..1F，constB = 20..2F。 */
    val KEYS_16 = NearbyKeyMaterial(incrementing(0x00, 16), incrementing(0x10, 16), incrementing(0x20, 16))
    const val DERIVED_ROOT_16 = "35323f3c393633303d3a3734313e3b38"

    /** 24 字节假密钥材料：rootKey = 40..57，constA = 60..77，constB = 80..97。用来核对按字节移位的写法不依赖长度。 */
    val KEYS_24 = NearbyKeyMaterial(incrementing(0x40, 24), incrementing(0x60, 24), incrementing(0x80, 24))
    const val DERIVED_ROOT_24 = "a5a2b7b4b1a6a3a0adaaa7a4a1aeaba8b5b2afaca9b6b3b0"

    val SEED_0 = incrementing(0x01, 8)
    val SEED_A = incrementing(0xA1, 8)
    val SEED_B = incrementing(0xB1, 8)
    val SEED_C = incrementing(0xC1, 8)
    val SEED_D = incrementing(0xD1, 8)
    val SEED_E = incrementing(0xE1, 8)
    val SEED_F = incrementing(0xF1, 8)
    val SEED_G = incrementing(0x51, 8)

    /** 明文 = pattern(长度)，种子 = SEED_0；最后一条明文 = pattern(40, 13, 0x55)，种子 = SEED_A。 */
    val CFB_LENGTHS = intArrayOf(0, 1, 15, 16, 17, 31, 32, 36, 100)

    val CFB_16 = listOf(
        "",
        "0b",
        "0b9518028b5bf87c033caf121c8f83",
        "0b9518028b5bf87c033caf121c8f83d1",
        "0b9518028b5bf87c033caf121c8f83d192",
        "0b9518028b5bf87c033caf121c8f83d1927f99f864d62bf5a8c2fcb99cd446",
        "0b9518028b5bf87c033caf121c8f83d1927f99f864d62bf5a8c2fcb99cd4469d",
        "0b9518028b5bf87c033caf121c8f83d1927f99f864d62bf5a8c2fcb99cd4469dffb7ac5a",
        "0b9518028b5bf87c033caf121c8f83d1927f99f864d62bf5a8c2fcb99cd4469dffb7ac5adde14c84aca8d496c5a5eb77" +
            "02d7d417ac35dd16d746370bb7c829fad87be40b6dfc78d17220ad14b9b5e1ad7c81000edda770a0de025f6eaf21f794" +
            "5a73e045",
    )
    const val CFB_16_SEED_A = "50215051aa45a48716015c63db8b2b9477336018c80dec00ce9c4abb6e4b248fcc545c22c5df4d49"

    val CFB_24 = listOf(
        "",
        "0c",
        "0c1040e3e4d4742bd1d293e5666348",
        "0c1040e3e4d4742bd1d293e56663485c",
        "0c1040e3e4d4742bd1d293e56663485c8f",
        "0c1040e3e4d4742bd1d293e56663485c8fea3d4f329e4b053e708b154db1f9",
        "0c1040e3e4d4742bd1d293e56663485c8fea3d4f329e4b053e708b154db1f973",
        "0c1040e3e4d4742bd1d293e56663485c8fea3d4f329e4b053e708b154db1f973db6264a2",
        "0c1040e3e4d4742bd1d293e56663485c8fea3d4f329e4b053e708b154db1f973db6264a2e9bb77590cb97e541f4ff021" +
            "7e1578ad5d4b0f2d99e62a25c013b6197649bffb33aa32d95ae8608df8a733dc5f977f9d93e943fff1ad016bf4409d71" +
            "36e9905c",
    )
    const val CFB_24_SEED_A = "d29ea4e2624f52ef2267f0743c06341ec01584fc34efc1192297694c5a7d07ad71d02e9ec90f36f2"

    /** 24 字节常量下：TLV(7, "02:11:22:33:44:55") 用 SEED_B 加密成外层类型 0 的帧。 */
    const val ENC_FRAME_24 = "7f80818a8188b1b2b3b4b5b6b7b89315fd0bd9f7fd86848e1541202544122d4d981779fa"

    /** 合成车机身份 1。 */
    val HOST_1 = HostIdentity(modelId = "ABCDEF", subModelId = "01", brand = "OpenHC", deviceName = "OpenHC Car")
    const val BT_1 = "02:11:22:33:44:55"
    const val IP_1 = "192.168.66.123"
    const val PORT_1 = 40001
    const val DEVICE_ID_1 = "CD1C73F369C5FE0B80808D3D0335EAF7D99B2AB9FE15403DC621C166FBADCB6F"
    const val CAR_INFO_1 =
        "68776e65617262795b000100000101000001010103abcdef0201010320cd1c73f369c5fe0b80808d3d0335eaf7d99b2a" +
        "b9fe15403dc621c166fbadcb6f0406021122334455060e3139322e3136382e36362e3132330704419c0000"

    /** 合成车机身份 2：另一组编号、地址、IP，端口取最大值。 */
    val HOST_2 = HostIdentity(modelId = "123456", subModelId = "78", brand = "OpenHC", deviceName = "OpenHC Car")
    const val BT_2 = "0A:1B:2C:3D:4E:5F"
    const val IP_2 = "10.123.200.254"
    const val PORT_2 = 65535
    const val DEVICE_ID_2 = "8B0B8B908B3F4A710D3E64AC50D1B5888415BCB858ABC4326990A99D0C12410E"
    const val CAR_INFO_2 =
        "68776e65617262795b00010000010100000101010312345602017803208b0b8b908b3f4a710d3e64ac50d1b5888415bc" +
        "b858abc4326990a99d0c12410e04060a1b2c3d4e5f060e31302e3132332e3230302e3235340704ffff0000"

    /** 身份 1 的蓝牙地址换成 02:00:00:00:00:00（取不到）时：哈希改用 6 个 0 字节。 */
    const val DEVICE_ID_1_UNAVAILABLE = "091663FE36C73520BF769477343DCE2970328C50E05D70EEE1E91A618985D79B"

    // ── 一次有线握手里的各帧（16 字节常量、身份 1）──

    /** 车机先发的明文 connectId（123456）。 */
    const val CONNECT_ID_123456 = "7f808080888f863132333435364aac"

    /** 手机首包（明文）：TLV1{05} TLV3"0123456789AB\0" TLV5{02} TLV16（合成文本）TLV18（合成字节）。 */
    const val PHONE_FIRST =
        "7f8080801b81818105838d3031323334353637383941420085810290db30313233343536373839414243444546303132" +
        "333435363738394142434445463031323334353637383941424344454630313233343536373839414243444546262630" +
        "26263026264645444342413938373635343332313026263192a7303132333435363738393a3b3c3d3e3f404142434445" +
        "464748494a4b4c4d4e4f50515253545556343b"

    /** 车机：TLV7 = 本机蓝牙地址文本，SEED_A 加密。 */
    const val CAR_TLV7_SEED_A = "7f80818a8188a1a2a3a4a5a6a7a89382d20f1f19e2360d99f9b1b4194f14b8522fd49368"

    /** 车机：TLV2{02} TLV4(deviceId 文本) TLV6{01} 合成一帧，SEED_B 加密。 */
    const val CAR_REPLY_SEED_B =
        "7f80818a8188b1b2b3b4b5b6b7b8c8de4e0aa701b4990d64d4a5f2c69194fd35c5ca84ae788b7df257d5fe4a812d9ed9" +
        "59837d9cd78a7fe551d2f693b51238ec365eac5fd6e5de03f0737b4a529b864a61fccdce4ddfaa1a3d"

    /** 手机要求不加密时车机的明文应答：TLV2{02} TLV4(deviceId 文本) TLV6{00}。 */
    const val CAR_REPLY_PLAIN =
        "7f808080c882810284c04344314337334633363943354645304238303830384433443033333545414637443939423241" +
        "4239464531353430334443363231433136364642414443423646868100b0ab"

    /** 手机：明文 TLV8 = 合成蓝牙地址 02:66:77:88:99:AA。 */
    const val PHONE_TLV8 = "7f80808093889130323a36363a37373a38383a39393a4141db21"
    const val PHONE_BT = "02:66:77:88:99:AA"

    /** 手机：资源层建 socket 请求（通道 6、业务 19、标签 hicarAuth、协议 1），SEED_D 加密。 */
    const val PHONE_AUTH_REQ_SEED_D = "7f84818a8188d1d2d3d4d5d6d7d88f7f488057c62586c61bb0ba7b4d80ab3c9b"

    /** 车机：接受上面的请求，端口 40001，SEED_C 加密。 */
    const val CAR_AUTH_RESP_SEED_C = "7f84818a8188c1c2c3c4c5c6c7c893d8072e3b465aebb777e0cd54f2da3048bc497c688f"

    /** 手机：资源层建 socket 请求（通道 6、业务 20、标签 DMSDP、协议 1），SEED_E 加密。 */
    const val PHONE_DMSDP_REQ_SEED_E = "7f84818a8188e1e2e3e4e5e6e7e88b56d9fdc290a19b65ef23dab3c5"

    /** 车机：接受 DMSDP 请求，端口 40002，SEED_F 加密。 */
    const val CAR_DMSDP_RESP_SEED_F = "7f84818a8188f1f2f3f4f5f6f7f88f67c6d115efbef7ad9b08456044629c04a5"

    /** 车机：拒绝通道 6、业务 21、标签 other 的请求（状态 1、端口 0、协议 0），SEED_G 加密。 */
    const val CAR_REJECT_SEED_G = "7f84818a818851525354555657588d99338f2d91c2b32efd4fae69aeb457"

    /** 上面几帧的明文载荷。 */
    const val PAYLOAD_AUTH_REQ = "838d86938968696361724175746881"
    const val PAYLOAD_AUTH_RESP = "84918041388286938968696361724175746881"
    const val PAYLOAD_REJECT = "848b81808695856f7468657280"
    const val PAYLOAD_TLV7 = "879130323a31313a32323a33333a34343a3535"
    const val PAYLOAD_REPLY =
        "82810284c043443143373346333639433546453042383038303844334430333335454146374439394232414239464531" +
        "353430334443363231433136364642414443423646868101"

    /** EA 编码对照：值 → 字节。 */
    val EA = mapOf(
        0 to "80", 1 to "81", 2 to "82", 127 to "ff", 128 to "0081", 155 to "1b81", 300 to "2c82",
        16383 to "7fff", 16384 to "000081", 65535 to "7f7f83", 2097151 to "7f7fff", 2097152 to "00000081",
        268435455 to "7f7f7fff",
    )

    /** CRC-16/ARC：pattern(64) 的校验值。 */
    const val CRC_PATTERN_64 = 0x412E
}
