package io.github.gcbond.openhc.platform.media

import io.github.gcbond.openhc.core.hisight.HiSightInput
import io.github.gcbond.openhc.platform.media.MediaKeyForwarder.Command
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 媒体键转发：哪些键转、按下抬起怎么配对、自动重复和重复入口、播放控制命令合成。 */
class MediaKeyForwarderTest {

    private fun ready() = MediaKeyForwarder().apply { ready = true }

    /** 发给手机的按键写成「下87」「上87」，修饰位都必须是 0。 */
    private fun MediaKeyForwarder.Result.sent(): List<String> = keys.map {
        assertEquals(0L, it.metaState)
        (if (it.action == HiSightInput.Key.Action.DOWN) "下" else "上") + it.keyCode
    }

    @Test
    fun steeringWheelKeysMapToTheSameAndroidKeyCodes() {
        val f = ready()
        var t = 1000L
        for (code in listOf(85, 86, 87, 88, 89, 90, 126, 127)) {
            val down = f.onKey(code, down = true, repeatCount = 0, eventTime = ++t)
            val up = f.onKey(code, down = false, repeatCount = 0, eventTime = ++t)
            assertTrue(down.handled && up.handled, "键码 $code")
            assertEquals(listOf("下$code"), down.sent())
            assertEquals(listOf("上$code"), up.sent())
        }
    }

    @Test
    fun otherKeysAndNoConnectionGoBackToTheSystem() {
        val f = ready()
        for (code in listOf(4, 24, 25, 79, 19)) {
            assertFalse(f.onKey(code, true, 0, 1).handled, "键码 $code 不是媒体键")
        }
        val idle = MediaKeyForwarder()
        assertFalse(idle.onKey(87, true, 0, 1).handled, "没有连接时车机自己的媒体照常")
        assertFalse(idle.onCommand(Command.NEXT).handled)
        assertTrue(idle.onKey(87, true, 0, 1).keys.isEmpty())
    }

    @Test
    fun autoRepeatIsSwallowedNotForwarded() {
        val f = ready()
        assertEquals(listOf("下87"), f.onKey(87, true, 0, 10).sent())
        val repeat = f.onKey(87, true, 1, 11)
        assertTrue(repeat.handled)
        assertEquals(emptyList(), repeat.sent())
        assertEquals(listOf("上87"), f.onKey(87, false, 0, 12).sent())
    }

    @Test
    fun missingUpIsPatchedAndOrphanUpGoesBackToTheSystem() {
        val f = ready()
        f.onKey(88, true, 0, 10)
        assertEquals(listOf("上88", "下88"), f.onKey(88, true, 0, 20).sent(), "上一次没抬起：先补抬起")
        assertEquals(listOf("上88"), f.onKey(88, false, 0, 21).sent())
        val orphan = f.onKey(88, false, 0, 22)
        assertFalse(orphan.handled, "按下给了系统的抬起交还系统")
        assertEquals(emptyList(), orphan.sent())
    }

    @Test
    fun sameEventFromBothEntriesIsForwardedOnce() {
        val f = ready()
        assertEquals(listOf("下87"), f.onKey(87, true, 0, 500).sent())
        val again = f.onKey(87, true, 0, 500)
        assertTrue(again.handled)
        assertEquals(emptyList(), again.sent())
        assertEquals(listOf("上87"), f.onKey(87, false, 0, 600).sent())
        assertEquals(emptyList(), f.onKey(87, false, 0, 600).sent())
    }

    @Test
    fun commandsAreSynthesizedAsAPress() {
        val f = ready()
        val expected = mapOf(
            Command.PLAY to 126, Command.PAUSE to 127, Command.PLAY_PAUSE to 85,
            Command.NEXT to 87, Command.PREVIOUS to 88, Command.STOP to 86,
        )
        for ((command, code) in expected) {
            val r = f.onCommand(command)
            assertTrue(r.handled)
            assertEquals(listOf("下$code", "上$code"), r.sent(), "$command")
        }
        // 这个键正按着时（按键事件那边会发抬起），命令不再合成。
        f.onKey(87, true, 0, 1)
        assertEquals(emptyList(), f.onCommand(Command.NEXT).sent())
    }

    @Test
    fun losingTheConnectionForgetsPressedKeys() {
        val f = ready()
        f.onKey(87, true, 0, 1)
        f.ready = false
        assertFalse(f.onKey(87, false, 0, 2).handled, "连接没了：抬起交还系统，手机那边由连接收尾时抬起")
        f.ready = true
        assertEquals(listOf("下87"), f.onKey(87, true, 0, 3).sent(), "重新连上后不补抬起")
    }

    @Test
    fun labelsForLogs() {
        assertEquals("下一首(87)", MediaKeyForwarder.label(87))
        assertEquals("上一首(88)", MediaKeyForwarder.label(88))
        assertEquals("播放暂停(85)", MediaKeyForwarder.label(85))
    }
}
