package io.github.gcbond.openhc.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 拔线重插的闸门、「连续不在」计时与开始请求的处理。 */
class ReplugGateTest {

    private val gate = ReplugGate(absentMillis = 5_000)

    @Test
    fun openUntilAnInsertionIsUsed() {
        assertFalse(gate.isArmed)
        assertTrue(gate.isOpen(0))
        gate.onPresence(phonePresent = true, nowMillis = 10)
        assertTrue(gate.isOpen(10), "没上闸时手机在不在都能开始")
    }

    @Test
    fun opensOnlyAfterThePhoneHasBeenAwayLongEnough() {
        gate.arm(nowMillis = 1_000, phonePresent = true)
        assertFalse(gate.isOpen(1_000))
        // 会话结束约 1.5 秒后手机退回普通状态：短暂断开再出现，不算拔线。
        gate.onPresence(false, 2_400)
        assertTrue(gate.phoneAway)
        gate.onPresence(true, 2_600)
        assertFalse(gate.phoneAway)
        assertFalse(gate.isOpen(9_000), "手机一直插着，闸门不开")

        gate.onPresence(false, 10_000)
        assertFalse(gate.isOpen(14_999))
        gate.onPresence(false, 14_999)
        assertTrue(gate.isOpen(15_000))
        assertTrue(gate.isArmed, "只查询不改状态")
        gate.disarm()
        assertFalse(gate.isArmed)
        assertTrue(gate.isOpen(15_001))
    }

    @Test
    fun pluggingBackTooEarlyRestartsTheCount() {
        gate.arm(nowMillis = 0, phonePresent = false)
        gate.onPresence(false, 3_000)
        gate.onPresence(true, 3_500)
        assertFalse(gate.isOpen(6_000), "3.5 秒就插回：要重新拔")
        gate.onPresence(false, 7_000)
        assertFalse(gate.isOpen(11_999))
        assertTrue(gate.isOpen(12_000))
    }

    @Test
    fun armingWhileThePhoneIsAlreadyGoneCountsFromNow() {
        // 例如手机被拔掉导致会话结束：结束时手机已经不在了。
        gate.arm(nowMillis = 100, phonePresent = false)
        assertTrue(gate.phoneAway)
        assertFalse(gate.isOpen(5_099))
        assertTrue(gate.isOpen(5_100))
    }

    @Test
    fun rearmingForgetsAnEarlierAbsence() {
        gate.arm(nowMillis = 0, phonePresent = false)
        gate.disarm()
        gate.arm(nowMillis = 10_000, phonePresent = true)
        assertFalse(gate.isOpen(20_000), "上一次的离开不算到这一次")
    }

    @Test
    fun rejectsNonPositiveDuration() {
        assertFailsWith<IllegalArgumentException> { ReplugGate(0) }
    }

    @Test
    fun absenceTimerMeasuresOnlyContinuousAbsence() {
        val timer = AbsenceTimer()
        assertFalse(timer.away)
        assertEquals(0, timer.awayFor(1_000))
        // 手机切成 USB 网卡时普通状态的设备先断开，约 1.5 秒后网卡设备出现。
        timer.update(phonePresent = false, nowMillis = 1_000)
        timer.update(phonePresent = false, nowMillis = 2_000)
        assertEquals(1_500, timer.awayFor(2_500))
        timer.update(phonePresent = true, nowMillis = 2_500)
        assertEquals(0, timer.awayFor(9_000))
        // 真的拔了：从第一次发现不在时算起。
        timer.update(false, 10_000)
        timer.update(false, 12_000)
        assertEquals(5_000, timer.awayFor(15_000))
        timer.reset()
        assertFalse(timer.away)
    }

    @Test
    fun anythingIsIgnoredWhileAConnectionIsWanted() {
        // 发完 carInfo 后手机重新枚举成 USB 网卡：会话进行中，不能被打断。
        for (trigger in StartTrigger.values()) {
            for (open in listOf(true, false)) {
                assertEquals(StartDecision.IGNORE, StartPolicy.decide(trigger, connectionWanted = true, gateOpen = open))
            }
        }
    }

    @Test
    fun startsWhenTheGateIsOpen() {
        assertEquals(StartDecision.START, StartPolicy.decide(StartTrigger.BUTTON, connectionWanted = false, gateOpen = true))
        assertEquals(StartDecision.START, StartPolicy.decide(StartTrigger.USB_ATTACH, connectionWanted = false, gateOpen = true))
    }

    @Test
    fun onlyTheButtonWaitsForAReplug() {
        assertEquals(
            StartDecision.WAIT_FOR_REPLUG,
            StartPolicy.decide(StartTrigger.BUTTON, connectionWanted = false, gateOpen = false),
        )
        assertEquals(
            StartDecision.IGNORE,
            StartPolicy.decide(StartTrigger.USB_ATTACH, connectionWanted = false, gateOpen = false),
        )
    }

    @Test
    fun reenumerationAfterAUserStopDoesNotUndoTheStop() {
        // 投屏中用户点停止：这次连接发过 carInfo，收尾后回到空闲、闸门关上，手机还插着。
        var state = ConnectionReducer.reduce(ConnectionUiState(), ConnectionSignal.AttemptStarted(1))
        state = ConnectionReducer.reduce(state, ConnectionSignal.StopRequested)
        state = ConnectionReducer.reduce(
            state,
            ConnectionSignal.AttemptFinished(EndCause.UserStop, replugRequired = true, keepConnecting = false),
        )
        gate.arm(nowMillis = 0, phonePresent = true)
        assertEquals(Phase.IDLE, state.phase)

        // 约 1.5 秒后手机收到 TEARDOWN 退回普通状态，重新枚举把应用又拉起来一次：不算插线。
        gate.onPresence(false, 1_000)
        gate.onPresence(true, 1_500)
        assertEquals(
            StartDecision.IGNORE,
            StartPolicy.decide(StartTrigger.USB_ATTACH, connectionWanted = false, gateOpen = gate.isOpen(1_500)),
        )
        assertFalse(state.active, "仍是空闲，不要求前台")
        assertEquals(Phase.IDLE, state.phase)
    }

    @Test
    fun replugAfterLongEnoughAbsenceStartsAgain() {
        gate.arm(nowMillis = 0, phonePresent = true)
        // 调度每半秒查一次：看到手机离开够久，闸门打开，随即解除。
        gate.onPresence(false, 2_000)
        gate.onPresence(false, 7_000)
        assertTrue(gate.isOpen(7_000))
        gate.disarm()
        // 之后插线：照常开始。
        gate.onPresence(true, 9_000)
        assertEquals(
            StartDecision.START,
            StartPolicy.decide(StartTrigger.USB_ATTACH, connectionWanted = false, gateOpen = gate.isOpen(9_000)),
        )
    }
}
