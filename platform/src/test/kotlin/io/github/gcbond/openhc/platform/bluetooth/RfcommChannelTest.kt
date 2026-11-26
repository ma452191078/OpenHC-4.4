package io.github.gcbond.openhc.platform.bluetooth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 读 RFCOMM 通道号的反射与解析：用同样字段名的假类代替系统的 socket。 */
class RfcommChannelTest {

    @Suppress("unused")
    private class InnerSocket(private val mPort: Int)

    @Suppress("unused")
    private open class OuterBase(private val mSocket: Any?)

    /** 字段声明在父类上，也要找得到。 */
    private class Outer(socket: Any?) : OuterBase(socket)

    private class Unrelated

    @Test
    fun readsThePortOfTheInnerSocket() {
        assertEquals(9, RfcommChannel.innerPort(Outer(InnerSocket(9))))
        assertEquals(-1, RfcommChannel.innerPort(OuterBase(InnerSocket(-1))), "读到什么就交什么，有效性另判")
    }

    @Test
    fun missingFieldsGiveNull() {
        assertNull(RfcommChannel.innerPort(Unrelated()))
        assertNull(RfcommChannel.innerPort(Outer(null)))
        assertNull(RfcommChannel.innerPort(Outer(Unrelated())))
    }

    @Test
    fun onlyChannelsOneToThirtyAreValid() {
        assertTrue(RfcommChannel.isValid(1))
        assertTrue(RfcommChannel.isValid(30))
        assertFalse(RfcommChannel.isValid(0))
        assertFalse(RfcommChannel.isValid(-1))
        assertFalse(RfcommChannel.isValid(31))
    }

    @Test
    fun channelInThePublicText() {
        assertEquals(8, RfcommChannel.channelFromText("ServerSocket: Type: TYPE_RFCOMM Channel: 8"))
        assertEquals(-1, RfcommChannel.channelFromText("ServerSocket: Type: TYPE_RFCOMM Channel: -1"))
        assertNull(RfcommChannel.channelFromText("ServerSocket: Type: TYPE_RFCOMM"))
        assertNull(RfcommChannel.channelFromText(null))
    }
}
