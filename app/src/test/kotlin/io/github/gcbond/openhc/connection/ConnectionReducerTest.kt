package io.github.gcbond.openhc.connection

import io.github.gcbond.openhc.core.auth.AuthFailure
import io.github.gcbond.openhc.core.auth.AuthMode
import io.github.gcbond.openhc.core.dmsdp.KeepAliveMode
import io.github.gcbond.openhc.core.hisight.HiSightEndReason
import io.github.gcbond.openhc.core.session.EndReason
import io.github.gcbond.openhc.core.session.SessionChannel
import io.github.gcbond.openhc.core.session.SessionEvent
import io.github.gcbond.openhc.core.session.SessionLayer
import io.github.gcbond.openhc.core.session.VideoParameters
import io.github.gcbond.openhc.platform.usb.WiredEndReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 状态归并：有线状态、会话事件、本机操作 → 界面状态。测试数据都是合成值。 */
class ConnectionReducerTest {

    private val video = VideoParameters(1280, 720, 60, touchInput = true, keyInput = false, textInput = false)

    private fun run(vararg signals: ConnectionSignal, from: ConnectionUiState = ConnectionUiState()): ConnectionUiState =
        signals.fold(from) { state, signal -> ConnectionReducer.reduce(state, signal) }

    private fun wired(progress: WiredProgress) = ConnectionSignal.Wired(progress)
    private fun session(event: SessionEvent) = ConnectionSignal.Session(event)

    /** 到「控制通道已连上」为止的一串输入。 */
    private val untilPhoneConnected = arrayOf(
        ConnectionSignal.AttemptStarted(1),
        session(SessionEvent.ControlListening(40000)),
        wired(WiredProgress.WaitingForPhone),
        wired(WiredProgress.WaitingForUsbPermission(forNetwork = false)),
        wired(WiredProgress.SendingCarInfo),
        wired(WiredProgress.WaitingForPhoneSwitch(slow = false)),
        wired(WiredProgress.OpeningNetwork),
        wired(WiredProgress.NetworkReady),
        session(SessionEvent.PhoneConnected("192.168.66.2")),
    )

    @Test
    fun startsInIdle() {
        val state = ConnectionUiState()
        assertEquals(Phase.IDLE, state.phase)
        assertFalse(state.active)
    }

    @Test
    fun wiredProgressMapsToPhases() {
        var state = run(ConnectionSignal.AttemptStarted(1))
        assertEquals(Phase.PREPARING, state.phase)
        assertTrue(state.active)

        state = run(wired(WiredProgress.WaitingForPhone), from = state)
        assertEquals(Phase.WAITING_FOR_PHONE, state.phase)
        state = run(wired(WiredProgress.UsbDebuggingOn), from = state)
        assertEquals(Phase.USB_DEBUGGING_ON, state.phase)
        // 关掉 USB 调试、或授权前被拔掉，平台层会回到等手机。
        state = run(wired(WiredProgress.WaitingForPhone), from = state)
        assertEquals(Phase.WAITING_FOR_PHONE, state.phase)

        state = run(wired(WiredProgress.WaitingForUsbPermission(forNetwork = false)), from = state)
        assertEquals(Phase.WAITING_USB_PERMISSION to Step.USB_PERMISSION_PHONE, state.phase to state.step)
        state = run(wired(WiredProgress.SendingCarInfo), from = state)
        assertEquals(Phase.CONNECTING to Step.SENDING_CAR_INFO, state.phase to state.step)
        state = run(wired(WiredProgress.WaitingForPhoneSwitch(slow = false)), from = state)
        assertEquals(Phase.CONFIRM_ON_PHONE to Step.PHONE_SWITCH, state.phase to state.step)
        state = run(wired(WiredProgress.WaitingForPhoneSwitch(slow = true)), from = state)
        assertEquals(Phase.CONFIRM_ON_PHONE to Step.PHONE_SWITCH_SLOW, state.phase to state.step)
        state = run(wired(WiredProgress.WaitingForUsbPermission(forNetwork = true)), from = state)
        assertEquals(Phase.WAITING_USB_PERMISSION to Step.USB_PERMISSION_NETWORK, state.phase to state.step)
        state = run(wired(WiredProgress.OpeningNetwork), from = state)
        assertEquals(Phase.CONNECTING to Step.OPENING_NETWORK, state.phase to state.step)
        state = run(wired(WiredProgress.NetworkReady), from = state)
        assertEquals(Phase.CONNECTING to Step.WAITING_PHONE_LINK, state.phase to state.step)
    }

    @Test
    fun firstPairingShowsPinThenProjects() {
        var state = run(*untilPhoneConnected)
        assertEquals(Phase.CONNECTING to Step.HANDSHAKE, state.phase to state.step)
        assertTrue(state.phoneConnected)

        state = run(
            session(SessionEvent.ControlHandshakeCompleted(encrypted = true)),
            session(SessionEvent.AuthStarted(AuthMode.PIN_PAIRING)),
            from = state,
        )
        assertEquals(Phase.PAIRING, state.phase)
        assertNull(state.pinCode)

        state = run(session(SessionEvent.ShowPinCode("123456")), from = state)
        assertEquals(Phase.PAIRING, state.phase)
        assertEquals("123456", state.pinCode)

        state = run(session(SessionEvent.Authenticated(AuthMode.PIN_PAIRING)), from = state)
        assertEquals(Phase.CONNECTING to Step.DEVICE_SESSION, state.phase to state.step)
        assertNull(state.pinCode, "认证成功后不再显示连接码")

        state = run(
            session(SessionEvent.DeviceSessionReady(KeepAliveMode.UDP)),
            session(SessionEvent.ProjectionRequested(5000)),
            from = state,
        )
        assertEquals(Phase.CONNECTING to Step.NEGOTIATING, state.phase to state.step)

        state = run(session(SessionEvent.VideoNegotiated(video)), from = state)
        assertEquals(Phase.PROJECTING, state.phase)
        assertEquals(VideoInfo(1280, 720, 60, touchInput = true), state.video)
        assertFalse(state.inputReady, "回控就绪（PLAY 被接受）之前不能发触摸")
        assertFalse(state.firstFrame)

        state = run(session(SessionEvent.PlayAccepted), from = state)
        assertTrue(state.inputReady)
        state = run(session(SessionEvent.VideoStarted(video)), ConnectionSignal.VideoSize(1280, 720), from = state)
        assertTrue(state.firstFrame)
        assertEquals(1280 to 720, state.videoWidth to state.videoHeight)
    }

    @Test
    fun trustedReconnectNeverShowsAPin() {
        val state = run(*untilPhoneConnected, session(SessionEvent.AuthStarted(AuthMode.TRUSTED_RECONNECT)))
        assertEquals(Phase.CONNECTING to Step.AUTHENTICATING, state.phase to state.step)
        assertNull(state.pinCode)
    }

    @Test
    fun wiredProgressAfterThePhoneConnectedDoesNotMoveThePhaseBack() {
        val connected = run(*untilPhoneConnected, session(SessionEvent.ControlHandshakeCompleted(encrypted = true)))
        // 有线状态和会话事件来自不同线程，迟到的「网络已通」不能把阶段拉回去。
        val state = run(wired(WiredProgress.NetworkReady), wired(WiredProgress.OpeningNetwork), from = connected)
        assertEquals(connected, state)
    }

    @Test
    fun touchIsNotReadyWhenThePhoneDidNotNegotiateIt() {
        val noTouch = VideoParameters(1280, 720, 30, touchInput = false, keyInput = false, textInput = false)
        val state = run(
            *untilPhoneConnected,
            session(SessionEvent.VideoNegotiated(noTouch)),
            session(SessionEvent.PlayAccepted),
        )
        assertEquals(Phase.PROJECTING, state.phase)
        assertFalse(state.inputReady)
    }

    @Test
    fun sessionEndStopsInputAndIgnoresLateEvents() {
        val projecting = run(
            *untilPhoneConnected,
            session(SessionEvent.VideoNegotiated(video)),
            session(SessionEvent.PlayAccepted),
        )
        var state = run(session(SessionEvent.Ended(EndReason.ProjectionEnded(HiSightEndReason.PEER_TEARDOWN))), from = projecting)
        assertEquals(Phase.STOPPING, state.phase)
        assertFalse(state.inputReady)
        // 收尾期间迟到的事件、有线状态不改变阶段。
        state = run(
            session(SessionEvent.VideoStarted(video)),
            wired(WiredProgress.Ended(WiredEndReason.STOPPED, replugRequired = true, detail = null)),
            from = state,
        )
        assertEquals(Phase.STOPPING, state.phase)
    }

    @Test
    fun usedInsertionEndsInReplugRequiredAndKeepsTheReason() {
        val cause = EndCause.Session(EndReason.ProjectionEnded(HiSightEndReason.PEER_TEARDOWN))
        val state = run(
            *untilPhoneConnected,
            session(SessionEvent.Ended(cause.reason)),
            ConnectionSignal.AttemptFinished(cause, replugRequired = true, keepConnecting = true),
        )
        assertEquals(Phase.REPLUG_REQUIRED to Step.REPLUG_UNPLUG, state.phase to state.step)
        assertTrue(state.active, "拔线重插后自动再连，仍算在连接")
        assertSame(cause, assertIs<Notice.Ended>(state.notice).cause)
        assertNull(state.video)
        assertFalse(state.phoneConnected)

        val unplugged = run(ConnectionSignal.ReplugWaiting(unplugged = true), from = state)
        assertEquals(Phase.REPLUG_REQUIRED to Step.REPLUG_WAIT, unplugged.phase to unplugged.step)
        assertEquals(state.notice, unplugged.notice, "等重插时仍显示上次结束的原因")

        // 拔够了时间，自动开始下一次：上次的原因清掉。
        val next = run(ConnectionSignal.AttemptStarted(2), wired(WiredProgress.WaitingForPhone), from = unplugged)
        assertEquals(Phase.WAITING_FOR_PHONE, next.phase)
        assertEquals(2, next.attempt)
        assertNull(next.notice)
    }

    @Test
    fun unpluggedWhileWaitingForTheSwitchStartsOverWithoutAnotherReplug() {
        // 等手机切换时用户照提示拔线、等够 5 秒：调度结束这次连接，重插要求已满足，紧接着开始下一次。
        val cause = EndCause.Wired(WiredEndReason.PHONE_UNPLUGGED, null)
        var state = run(
            ConnectionSignal.AttemptStarted(1),
            wired(WiredProgress.SendingCarInfo),
            wired(WiredProgress.WaitingForPhoneSwitch(slow = true)),
            ConnectionSignal.StopRequested,
        )
        assertEquals(Phase.STOPPING, state.phase)
        state = run(ConnectionSignal.AttemptFinished(cause, replugRequired = false, keepConnecting = true), from = state)
        assertEquals(Phase.IDLE, state.phase, "不进入等拔线重插")
        state = run(ConnectionSignal.AttemptStarted(2), wired(WiredProgress.WaitingForPhone), from = state)
        assertEquals(Phase.WAITING_FOR_PHONE, state.phase)
        assertTrue(state.active)
    }

    @Test
    fun errorBeforeCarInfoGoesBackToIdle() {
        val cause = EndCause.Wired(WiredEndReason.USB_PERMISSION_DENIED, null)
        val state = run(
            ConnectionSignal.AttemptStarted(1),
            wired(WiredProgress.WaitingForUsbPermission(forNetwork = false)),
            wired(WiredProgress.Ended(cause.reason, replugRequired = false, detail = null)),
            ConnectionSignal.AttemptFinished(cause, replugRequired = false, keepConnecting = false),
        )
        assertEquals(Phase.IDLE, state.phase)
        assertFalse(state.active)
        assertEquals(Notice.Ended(cause), state.notice)
    }

    @Test
    fun vpnConsentLossIsShownAsAConsentNotice() {
        val state = run(
            ConnectionSignal.AttemptStarted(1),
            ConnectionSignal.AttemptFinished(
                EndCause.Wired(WiredEndReason.VPN_CONSENT_REQUIRED, null),
                replugRequired = false,
                keepConnecting = false,
            ),
        )
        assertEquals(Phase.IDLE, state.phase)
        assertSame(Notice.VpnConsentRequired, state.notice)
    }

    @Test
    fun userStopEndsQuietlyEvenIfTheInsertionWasUsed() {
        val projecting = run(*untilPhoneConnected, session(SessionEvent.VideoNegotiated(video)))
        var state = run(ConnectionSignal.StopRequested, from = projecting)
        assertEquals(Phase.STOPPING, state.phase)
        state = run(ConnectionSignal.AttemptFinished(EndCause.UserStop, replugRequired = true, keepConnecting = false), from = state)
        assertEquals(Phase.IDLE, state.phase)
        assertFalse(state.active)
        assertNull(state.notice)
    }

    @Test
    fun stopWhileWaitingForReplugGoesStraightToIdle() {
        val waiting = run(ConnectionSignal.ReplugWaiting(unplugged = false))
        assertEquals(Phase.REPLUG_REQUIRED, waiting.phase)
        val state = run(ConnectionSignal.StopRequested, from = waiting)
        assertEquals(Phase.IDLE, state.phase)
        assertFalse(state.active)
        assertSame(state, run(ConnectionSignal.StopRequested, from = state), "空闲时停止什么都不变")
    }

    @Test
    fun blockedStartShowsTheReason() {
        val state = run(ConnectionSignal.Blocked(Notice.IdentityMissing))
        assertEquals(Phase.IDLE, state.phase)
        assertFalse(state.active)
        assertSame(Notice.IdentityMissing, state.notice)
    }

    @Test
    fun lateSignalsAfterFinishingAreIgnored() {
        val idle = run(ConnectionSignal.AttemptStarted(1), ConnectionSignal.AttemptFinished(EndCause.UserStop, false, false))
        val state = run(
            wired(WiredProgress.WaitingForPhone),
            session(SessionEvent.Diagnostic(SessionLayer.SESSION, "迟到")),
            ConnectionSignal.VideoSize(640, 480),
            ConnectionSignal.DecoderProblem("迟到"),
            from = idle,
        )
        assertEquals(idle, state)
    }

    @Test
    fun pinCodeIsNotPrinted() {
        val state = run(*untilPhoneConnected, session(SessionEvent.ShowPinCode("123456")))
        assertFalse(state.toString().contains("123456"))
    }

    @Test
    fun endCauseTrackerKeepsTheFirstRealReason() {
        // 有线先断（手机拔掉），会话随之被本机停止：显示有线的原因。
        EndCauseTracker().apply {
            onWiredEnded(WiredEndReason.PHONE_UNPLUGGED, null)
            onSessionEnded(EndReason.LocalStop)
            assertEquals(EndCause.Wired(WiredEndReason.PHONE_UNPLUGGED, null), result())
        }
        // 会话先结束（手机结束投屏），有线随之被停：显示会话的原因，STOPPED 不算。
        val peerClosed = EndReason.PeerClosed(SessionChannel.VIDEO, "合成")
        EndCauseTracker().apply {
            onSessionEnded(peerClosed)
            onWiredEnded(WiredEndReason.STOPPED, null)
            assertSame(peerClosed, assertIs<EndCause.Session>(result()).reason)
        }
        // 用户停止之后两头报的都是被停掉。
        EndCauseTracker().apply {
            onUserStop()
            onSessionEnded(EndReason.AuthFailed(AuthFailure.NotTrusted))
            onWiredEnded(WiredEndReason.STOPPED, null)
            assertSame(EndCause.UserStop, result())
        }
        // 两头都是被本机停掉、又没有别的记录时，按用户停止算。
        EndCauseTracker().apply {
            onSessionEnded(EndReason.LocalStop)
            onWiredEnded(WiredEndReason.STOPPED, null)
            assertSame(EndCause.UserStop, result())
        }
        EndCauseTracker().apply {
            onFailure("执行器意外结束")
            onWiredEnded(WiredEndReason.PHONE_UNPLUGGED, null)
            assertEquals(EndCause.Failure("执行器意外结束"), result())
        }
    }
}
