package io.github.gcbond.openhc.core.hisight.input

import io.github.gcbond.openhc.core.common.hexToBytes
import io.github.gcbond.openhc.core.common.toHex
import io.github.gcbond.openhc.core.hisight.FixedRandom
import io.github.gcbond.openhc.core.hisight.HiSightInput
import io.github.gcbond.openhc.core.hisight.Vectors
import io.github.gcbond.openhc.core.hisight.arr
import io.github.gcbond.openhc.core.hisight.bool
import io.github.gcbond.openhc.core.hisight.crypto.AesCtr
import io.github.gcbond.openhc.core.hisight.int
import io.github.gcbond.openhc.core.hisight.long
import io.github.gcbond.openhc.core.hisight.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 回控封包逐字节对照参考实现（同样的 IV 序列），包括拒绝的事件不产生包、不消耗 IV。 */
class UibcEncoderTest {
    @Test
    fun packetsMatchPrototype() {
        val cases = Vectors.section("remote") as JsonArray
        for (element in cases) {
            val case = element.jsonObject
            val name = case.str("name")
            val random = FixedRandom(case.arr("ivs").map { it.jsonPrimitive.content.hexToBytes() })
            val encoder = UibcEncoder(
                Vectors.key, random,
                case.arr("events").map { it.jsonPrimitive.content }.toSet(),
                case.arr("vendors").map { it.jsonPrimitive.content }.toSet(),
                (case["source"] as? JsonPrimitive)?.intOrNull,
            )
            for ((index, stepElement) in case.arr("steps").withIndex()) {
                val step = stepElement.jsonObject
                val label = "$name 第 $index 步"
                val expected = step.arr("packets").map { it.jsonPrimitive.content }
                val event = step.getValue("event")
                val actual = try {
                    if (event is JsonPrimitive && event.content == "release") {
                        encoder.release().map { it.toHex() }
                    } else {
                        listOf(encoder.encode(inputOf(event.jsonObject)).toHex())
                    }
                } catch (e: IllegalArgumentException) {
                    null
                } catch (e: IllegalStateException) {
                    null
                }
                if (step.bool("ok")) {
                    assertEquals(expected, actual, label)
                } else {
                    assertEquals(null, actual, "$label：参考实现拒绝，本实现却生成了包")
                }
            }
        }
    }

    private fun inputOf(event: JsonObject): HiSightInput = when (event.str("kind")) {
        "touch" -> HiSightInput.Touch(
            when (event.str("action")) {
                "down" -> HiSightInput.Touch.Action.DOWN
                "up" -> HiSightInput.Touch.Action.UP
                else -> HiSightInput.Touch.Action.MOVE
            },
            event.arr("points").map { p ->
                val o = p.jsonObject
                HiSightInput.Point(o.int("id"), o.int("x"), o.int("y"))
            },
            event.int("width"), event.int("height"),
        )
        "key" -> HiSightInput.Key(
            if (event.str("action") == "down") HiSightInput.Key.Action.DOWN else HiSightInput.Key.Action.UP,
            event.int("code"), event.long("meta"),
        )
        else -> HiSightInput.Text(event.str("text"))
    }

    /** 解开一个包看正文布局：外层头、六个 FF、两指无补齐。 */
    @Test
    fun touchLayout() {
        val key = ByteArray(16) { it.toByte() }
        val encoder = UibcEncoder(key, FixedRandom(listOf(ByteArray(16))), setOf("left_point_down", "left_point_up", "left_move"), emptySet(), null)
        val packet = encoder.encode(
            HiSightInput.Touch(HiSightInput.Touch.Action.DOWN, listOf(HiSightInput.Point(7, 30, 40), HiSightInput.Point(2, 10, 20)), 64, 64),
        )
        assertEquals("0000", packet.copyOf(2).toHex())
        assertEquals(packet.size, ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF))
        val plain = AesCtr(key).open(packet, 4, packet.size - 4)
        assertContentEquals("ffffffffffff00000b0202000a001407001e0028".hexToBytes(), plain)
    }

    /**
     * 触摸是「当前全部触点的快照」：MOVE 里可以增减触点；UP 只能带 1 个当前按着的点并结束整组；
     * 被拒的事件不改变状态。正文逐字节核对。
     */
    @Test
    fun snapshotTouchRules() {
        val key = ByteArray(16) { it.toByte() }
        val encoder = UibcEncoder(key, FixedRandom(List(16) { ByteArray(16) }), setOf("left_point_down", "left_point_up", "left_move"), emptySet(), null)
        fun touch(action: HiSightInput.Touch.Action, vararg points: Triple<Int, Int, Int>) =
            HiSightInput.Touch(action, points.map { HiSightInput.Point(it.first, it.second, it.third) }, 64, 64)
        fun body(packet: ByteArray): String = AesCtr(key).open(packet, 4, packet.size - 4).copyOfRange(6, packet.size - 20).toHex()
        val down = HiSightInput.Touch.Action.DOWN
        val move = HiSightInput.Touch.Action.MOVE
        val up = HiSightInput.Touch.Action.UP

        assertFailsWith<IllegalStateException>("没按下不能移动") { encoder.encode(touch(move, Triple(0, 1, 1))) }
        assertFailsWith<IllegalStateException>("没按下不能抬起") { encoder.encode(touch(up, Triple(0, 1, 1))) }
        assertEquals("000007010000010002" + "00", body(encoder.encode(touch(down, Triple(0, 1, 2)))))
        assertFailsWith<IllegalStateException>("按着时不能再按下") { encoder.encode(touch(down, Triple(1, 3, 4))) }
        // 第二根手指加进来：MOVE 带两个点。
        assertEquals("02000b02000001000201000300" + "04", body(encoder.encode(touch(move, Triple(0, 1, 2), Triple(1, 3, 4)))))
        // 0 号抬起：MOVE 里只剩 1 号。
        assertEquals("020007010100050006" + "00", body(encoder.encode(touch(move, Triple(1, 5, 6)))))
        assertFailsWith<IllegalArgumentException>("UP 只能 1 个点") { encoder.encode(touch(up, Triple(1, 5, 6), Triple(2, 7, 8))) }
        assertFailsWith<IllegalStateException>("0 号已经不在快照里") { encoder.encode(touch(up, Triple(0, 1, 2))) }
        // 被拒的事件没有改变状态：1 号照样能抬起，坐标用这次给的。
        assertEquals("010007010100090009" + "00", body(encoder.encode(touch(up, Triple(1, 9, 9)))))
        assertFailsWith<IllegalStateException>("抬起后整组结束") { encoder.encode(touch(move, Triple(1, 9, 9))) }
        assertTrue(encoder.release().isEmpty())

        // 收尾：只发 1 个点的 UP（快照里编号最小的那个，原位置），手机随之抬起整组。
        encoder.encode(touch(down, Triple(3, 10, 11)))
        encoder.encode(touch(move, Triple(3, 10, 11), Triple(1, 20, 21)))
        val released = encoder.release()
        assertEquals(1, released.size)
        assertEquals("010007010100140015" + "00", body(released.single()))
        assertTrue(encoder.release().isEmpty())
    }

    @Test
    fun releaseOrderAndClear() {
        val key = ByteArray(16)
        val events = setOf("left_point_down", "left_point_up", "left_move", "left_key_down", "left_key_up")
        val encoder = UibcEncoder(key, FixedRandom(List(16) { ByteArray(16) }), events, emptySet(), 7)
        encoder.encode(HiSightInput.Key(HiSightInput.Key.Action.DOWN, 10, 0))
        encoder.encode(HiSightInput.Key(HiSightInput.Key.Action.DOWN, 20, 0))
        encoder.encode(HiSightInput.Key(HiSightInput.Key.Action.DOWN, 10, 1))
        encoder.encode(HiSightInput.Touch(HiSightInput.Touch.Action.DOWN, listOf(HiSightInput.Point(0, 1, 1)), 4, 4))
        val released = encoder.release().map { AesCtr(key).open(it, 4, it.size - 4) }
        assertEquals(3, released.size)
        assertEquals(1, released[0][6].toInt())
        // 键按首次按下的逆序抬起：先 20，再 10（带最后一次的修饰位 1）。
        assertEquals(20, released[1][11].toInt())
        assertEquals(10, released[2][11].toInt())
        assertEquals(1, released[2][17].toInt())
        assertTrue(encoder.release().isEmpty())
        encoder.encode(HiSightInput.Key(HiSightInput.Key.Action.DOWN, 30, 0))
        encoder.clear()
        assertTrue(encoder.release().isEmpty())
    }
}
