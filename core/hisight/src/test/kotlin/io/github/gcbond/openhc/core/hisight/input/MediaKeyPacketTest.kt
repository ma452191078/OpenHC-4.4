package io.github.gcbond.openhc.core.hisight.input

import io.github.gcbond.openhc.core.common.hexToBytes
import io.github.gcbond.openhc.core.common.toHex
import io.github.gcbond.openhc.core.hisight.FixedRandom
import io.github.gcbond.openhc.core.hisight.HiSightInput
import io.github.gcbond.openhc.core.hisight.crypto.AesCtr
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 媒体键的回控包逐字节：外层头、IV、密文（用 JDK 自带的 AES/CTR 独立算一遍）和解开后的正文。
 * 密钥、IV 都是合成值。
 */
class MediaKeyPacketTest {
    private val key = ByteArray(16) { it.toByte() }
    private val iv = ByteArray(16) { (0xA0 + it).toByte() }
    private val keyEvents = setOf("left_key_down", "left_key_up")

    private fun encoder(source: Int?, ivs: Int = 2) = UibcEncoder(key, FixedRandom(List(ivs) { iv }), keyEvents, emptySet(), source)

    /** 正文明文：6 个 FF + 按键正文（动作、长度 9、保留 0、键码、第二键码 0、修饰位 0）。 */
    private fun plain(action: Int, code: Int): String =
        "ffffffffffff" + "%02x0009%02x%04x%04x%08x".format(action, 0, code, 0, 0)

    /** 用 JDK 的 AES/CTR 独立算出整个包：0x00 | 类别 0 | BE16 总长 | IV | 密文。 */
    private fun packet(action: Int, code: Int): String {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val sealed = iv + cipher.doFinal(plain(action, code).hexToBytes())
        val total = 4 + sealed.size
        return "0000%04x".format(total) + sealed.toHex()
    }

    @Test
    fun mediaKeyBodiesAreAndroidKeyCodes() {
        // 逐字节写出来：动作 03 按下 / 04 抬起，键码大端两字节。
        assertEquals("ffffffffffff030009000057000000000000", plain(3, 87)) // 下一首 按下
        assertEquals("ffffffffffff040009000057000000000000", plain(4, 87)) // 下一首 抬起
        assertEquals("ffffffffffff030009000058000000000000", plain(3, 88)) // 上一首
        assertEquals("ffffffffffff03000900007e000000000000", plain(3, 126)) // 播放
        assertEquals("ffffffffffff03000900007f000000000000", plain(3, 127)) // 暂停
        assertEquals("ffffffffffff030009000055000000000000", plain(3, 85)) // 播放暂停
        assertEquals("ffffffffffff030009000056000000000000", plain(3, 86)) // 停止
    }

    @Test
    fun mediaKeyPacketsMatchIndependentCipher() {
        for (source in listOf(7, null, 4)) {
            for (code in listOf(85, 86, 87, 88, 126, 127)) {
                val enc = encoder(source)
                val down = enc.encode(HiSightInput.Key(HiSightInput.Key.Action.DOWN, code, 0))
                val up = enc.encode(HiSightInput.Key(HiSightInput.Key.Action.UP, code, 0))
                assertEquals(packet(3, code), down.toHex(), "源设备类型 $source 键码 $code 按下")
                assertEquals(packet(4, code), up.toHex(), "源设备类型 $source 键码 $code 抬起")
                assertEquals(38, down.size, "4 字节头 + 16 字节 IV + 18 字节密文")
                assertEquals(plain(3, code), AesCtr(key).open(down, 4, down.size - 4).toHex())
            }
        }
    }

    @Test
    fun withoutSource7OnlyBasicKeysWithoutModifiers() {
        for (source in listOf(null, 4)) {
            val enc = encoder(source, ivs = 4)
            // 修饰位不为 0、或不是基础键：拒绝，且不消耗 IV、不记按下。
            assertFailsWith<IllegalArgumentException> { enc.encode(HiSightInput.Key(HiSightInput.Key.Action.DOWN, 87, 1)) }
            assertFailsWith<IllegalArgumentException> { enc.encode(HiSightInput.Key(HiSightInput.Key.Action.DOWN, 29, 0)) }
            assertFailsWith<IllegalArgumentException> { enc.encode(HiSightInput.Key(HiSightInput.Key.Action.DOWN, 3, 0)) }
            assertEquals(packet(3, 4), enc.encode(HiSightInput.Key(HiSightInput.Key.Action.DOWN, 4, 0)).toHex(), "返回键是基础键")
            assertEquals(packet(4, 4), enc.encode(HiSightInput.Key(HiSightInput.Key.Action.UP, 4, 0)).toHex())
        }
        // 选了 7：任意键码和修饰位照发。
        val full = encoder(7)
        full.encode(HiSightInput.Key(HiSightInput.Key.Action.DOWN, 29, 1))
        full.encode(HiSightInput.Key(HiSightInput.Key.Action.UP, 29, 1))
    }

    @Test
    fun upWithoutDownIsRejected() {
        val enc = encoder(null)
        assertFailsWith<IllegalStateException> { enc.encode(HiSightInput.Key(HiSightInput.Key.Action.UP, 87, 0)) }
        // 被拒绝的抬起不消耗 IV：之后的按下照常。
        assertEquals(packet(3, 87), enc.encode(HiSightInput.Key(HiSightInput.Key.Action.DOWN, 87, 0)).toHex())
    }
}
