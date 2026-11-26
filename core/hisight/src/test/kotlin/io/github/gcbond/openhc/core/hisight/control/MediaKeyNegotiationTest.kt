package io.github.gcbond.openhc.core.hisight.control

import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.hisight.HiSightConfig
import io.github.gcbond.openhc.core.hisight.ascii
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 只为媒体键声明的按键事件：声明与 keyInput 相同，但手机不选回、没选源设备类型 7 都不让协商失败。 */
class MediaKeyNegotiationTest {
    private val touchAndMedia = HiSightConfig(640, 480, 640, 480, 30, 160, touchInput = true, mediaKeyInput = true)
    private val mediaOnly = HiSightConfig(640, 480, 640, 480, 30, 160, mediaKeyInput = true)
    private val touch = listOf("left_point_down", "left_point_up", "left_move")
    private val keys = listOf("left_key_down", "left_key_up")

    private fun select(caps: Capabilities, uibc: String?, device: String? = null) {
        var body = "his_video_formats: codecs 1;width 640;height 480;fps 30\r\n"
        if (uibc != null) body += "his_uibc_capability: $uibc\r\n"
        if (device != null) body += "his_device_type: $device\r\n"
        caps.select(ParameterText.parameters(ascii(body)))
    }

    private fun generic(events: List<String>) = "input_category_list=GENERIC;generic_cap_list=${events.joinToString(", ")};"

    @Test
    fun advertisedLikeKeyInput() {
        val query = ascii("his_version\r\nhis_video_formats\r\nhis_uibc_capability\r\nhis_device_type\r\n")
        val strict = HiSightConfig(640, 480, 640, 480, 30, 160, touchInput = true, keyInput = true)
        val body = Capabilities(touchAndMedia).responseBody(query)
        assertEquals(Capabilities(strict).responseBody(query), body)
        assertTrue(body.contains("his_uibc_capability: ${generic(touch + keys)}\r\n"))
    }

    @Test
    fun acceptedWithOrWithoutSource7() {
        for (device in listOf("device_type 4;subtype 0;", "source_device_type 7;source_subtype 0;device_type 4;subtype 0;", null)) {
            val caps = Capabilities(touchAndMedia)
            select(caps, generic(keys + touch), device)
            val setup = caps.mediaSetup(5000, 5001)
            assertEquals((touch + keys).toSet(), setup.inputEvents.toSet(), "his_device_type: $device")
            assertEquals(if (device?.startsWith("source") == true) 7 else null, setup.sourceDeviceType)
        }
    }

    @Test
    fun keysNotChosenOrHalfChosenLeaveTouchOnly() {
        for (chosen in listOf(touch, touch + "left_key_down", touch + "left_key_up")) {
            val caps = Capabilities(touchAndMedia)
            select(caps, generic(chosen), "device_type 4;subtype 0;")
            assertEquals(touch.toSet(), caps.mediaSetup(5000, 5001).inputEvents.toSet(), "手机选的是 $chosen")
        }
    }

    @Test
    fun touchIsStillMandatory() {
        val caps = Capabilities(touchAndMedia)
        assertFailsWith<MalformedMessageException> { select(caps, generic(keys)) }
        assertFailsWith<MalformedMessageException> { select(caps, null) }
        assertNull(caps.selection, "失败的选择不留下结果")
        // 声明了触摸就必须有回控端口。
        select(caps, generic(touch + keys))
        assertFailsWith<MalformedMessageException> { caps.mediaSetup(5000, -1) }
    }

    @Test
    fun strictKeyInputStillRequiresSource7() {
        val both = HiSightConfig(640, 480, 640, 480, 30, 160, touchInput = true, keyInput = true, mediaKeyInput = true)
        assertFailsWith<MalformedMessageException> { select(Capabilities(both), generic(touch + keys), "device_type 4;subtype 0;") }
        assertFailsWith<MalformedMessageException> {
            select(Capabilities(both), generic(touch), "source_device_type 7;device_type 4;subtype 0;")
        }
    }

    @Test
    fun mediaOnlyNeverFailsNegotiation() {
        for (uibc in listOf(null, "none", generic(emptyList()), generic(keys))) {
            val caps = Capabilities(mediaOnly)
            select(caps, uibc)
            val expected = if (uibc == generic(keys)) keys.toSet() else emptySet()
            assertEquals(expected, caps.mediaSetup(5000, 5001).inputEvents.toSet(), "手机选的是 $uibc")
            // 没有回控端口：这次没有按键，不算失败。
            assertTrue(caps.mediaSetup(5000, -1).inputEvents.isEmpty())
        }
        // 选了没声明的事件照样拒绝。
        assertFailsWith<MalformedMessageException> { select(Capabilities(mediaOnly), generic(touch)) }
    }
}
