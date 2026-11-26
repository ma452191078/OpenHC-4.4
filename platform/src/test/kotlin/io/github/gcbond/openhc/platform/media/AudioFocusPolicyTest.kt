package io.github.gcbond.openhc.platform.media

import io.github.gcbond.openhc.platform.media.AudioFocusPolicy.Change
import io.github.gcbond.openhc.platform.media.AudioFocusPolicy.Command
import io.github.gcbond.openhc.platform.media.AudioFocusPolicy.Gain
import io.github.gcbond.openhc.platform.media.AudioFocusPolicy.Level
import io.github.gcbond.openhc.platform.media.AudioFocusPolicy.PhoneChange
import io.github.gcbond.openhc.platform.media.AudioFocusPolicy.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 音频焦点状态机：申请与放弃的时机、失去焦点时压低或静音、告诉手机什么、自己的导航不算「被抢」。 */
class AudioFocusPolicyTest {

    private val media = AudioUsage.MEDIA
    private val nav = AudioUsage.NAVIGATION

    /** 申请都按批准处理，返回全部动作（含批准后的）。 */
    private fun AudioFocusPolicy.granted(commands: List<Command>, now: Long): List<Command> {
        val all = ArrayList(commands)
        for (c in commands) if (c is Command.Request) all += requestResult(c.usage, Result.GRANTED, now)
        return all
    }

    @Test
    fun mediaIsRequestedWhenThePhoneAsksAndReleasedAfterItGivesUp() {
        val policy = AudioFocusPolicy(systemDucks = true)
        assertEquals(listOf(Command.Request(media, Gain.GAIN)), policy.phoneRequested(media, 0))
        assertEquals(emptyList(), policy.requestResult(media, Result.GRANTED, 0))
        assertEquals(emptyList(), policy.phoneRequested(media, 100), "已经拿着就不再申请")

        assertEquals(emptyList(), policy.phoneAbandoned(media, 1_000))
        assertEquals(1_000 + AudioFocusPolicy.MEDIA_RELEASE_MILLIS, policy.nextDeadlineMillis)
        assertEquals(emptyList(), policy.onTick(2_499))
        assertEquals(listOf(Command.Abandon(media)), policy.onTick(2_500))
        assertNull(policy.nextDeadlineMillis)
    }

    @Test
    fun phoneAskingAgainWithinTheGraceKeepsTheFocus() {
        val policy = AudioFocusPolicy(systemDucks = true)
        policy.granted(policy.phoneRequested(media, 0), 0)
        policy.phoneAbandoned(media, 1_000)
        assertEquals(emptyList(), policy.phoneRequested(media, 1_200), "手机播报前后先放弃又马上要：不来回申请")
        assertNull(policy.nextDeadlineMillis)
        assertEquals(emptyList(), policy.onTick(10_000))
    }

    @Test
    fun soundAloneRequestsMediaAndSilenceReleasesItWhenThePhoneNeverSendsFocusMessages() {
        val policy = AudioFocusPolicy(systemDucks = true)
        assertEquals(listOf(Command.Request(media, Gain.GAIN)), policy.soundStarted(media, 0))
        policy.requestResult(media, Result.GRANTED, 0)
        assertEquals(emptyList(), policy.soundStopped(media, 3_000))
        assertEquals(emptyList(), policy.onTick(7_999))
        assertEquals(listOf(Command.Abandon(media)), policy.onTick(8_000))

        // 手机发过焦点消息之后就听手机的：出声停了也不放。
        val talking = AudioFocusPolicy(systemDucks = true)
        talking.granted(talking.phoneRequested(media, 0), 0)
        talking.soundStarted(media, 10)
        talking.soundStopped(media, 20)
        assertNull(talking.nextDeadlineMillis)
    }

    @Test
    fun mediaSoundDoesNotGrabTheFocusOnceThePhoneTalksFocus() {
        val policy = AudioFocusPolicy(systemDucks = true)
        policy.granted(policy.phoneRequested(media, 0), 0)
        policy.phoneAbandoned(media, 1_000)
        assertEquals(listOf<Command>(Command.Abandon(media)), policy.onTick(2_500))
        // 音乐那一路还在发（静音）、放空后又响：手机没要就不抢。
        assertEquals(emptyList(), policy.soundStarted(media, 3_000))
        assertEquals(listOf(Command.Request(media, Gain.GAIN)), policy.phoneRequested(media, 4_000))
    }

    @Test
    fun transientLossMutesAndTellsThePhoneToPauseThenResumes() {
        val policy = AudioFocusPolicy(systemDucks = true)
        policy.granted(policy.phoneRequested(media, 0), 0)
        assertEquals(
            listOf(Command.Volume(media, Level.MUTED), Command.NotifyPhone(PhoneChange.LOST_TRANSIENT)),
            policy.focusChanged(media, Change.LOSS_TRANSIENT, 100),
        )
        assertEquals(
            listOf(Command.Volume(media, Level.NORMAL), Command.NotifyPhone(PhoneChange.GAINED)),
            policy.focusChanged(media, Change.GAIN, 5_000),
        )
        assertEquals(emptyList(), policy.focusChanged(media, Change.GAIN, 6_000), "没告诉过手机暂停就不再说")
    }

    @Test
    fun permanentLossStopsUntilThePhoneAsksAgain() {
        val policy = AudioFocusPolicy(systemDucks = true)
        policy.granted(policy.phoneRequested(media, 0), 0)
        policy.soundStarted(media, 0)
        assertEquals(
            listOf(Command.Volume(media, Level.MUTED), Command.Abandon(media), Command.NotifyPhone(PhoneChange.LOST)),
            policy.focusChanged(media, Change.LOSS, 100),
        )
        assertEquals(emptyList(), policy.focusChanged(media, Change.GAIN, 200), "已经放掉的申请，过时的回调不理会")
        assertEquals(emptyList(), policy.onTick(60_000), "一直静音，不自己抢回来")
        assertEquals(listOf(Command.Request(media, Gain.GAIN)), policy.phoneRequested(media, 70_000))
        assertEquals(
            listOf(Command.Volume(media, Level.NORMAL), Command.NotifyPhone(PhoneChange.GAINED)),
            policy.requestResult(media, Result.GRANTED, 70_000),
        )
    }

    @Test
    fun mediaComingBackAfterStarvationAlsoRequestsAgain() {
        val policy = AudioFocusPolicy(systemDucks = true)
        policy.granted(policy.soundStarted(media, 0), 0)
        policy.focusChanged(media, Change.LOSS, 100)
        policy.soundStopped(media, 200)
        assertEquals(listOf(Command.Request(media, Gain.GAIN)), policy.soundStarted(media, 9_000), "手机暂停后又放：重新申请")
    }

    @Test
    fun ourOwnNavigationDoesNotCountAsLosingTheMediaFocus() {
        val policy = AudioFocusPolicy(systemDucks = true)
        policy.granted(policy.phoneRequested(media, 0), 0)
        // 手机的分流消息先到：申请导航（短暂、压低别人）。
        assertEquals(listOf(Command.Request(nav, Gain.TRANSIENT_MAY_DUCK)), policy.phoneRequested(nav, 1_000))
        policy.requestResult(nav, Result.GRANTED, 1_000)
        // 系统因此通知媒体「暂时可压低」：是我们自己引起的，不压低、不告诉手机。
        assertEquals(emptyList(), policy.focusChanged(media, Change.LOSS_TRANSIENT_CAN_DUCK, 1_001))
        assertEquals(emptyList(), policy.soundStarted(nav, 1_050))
        assertEquals(emptyList(), policy.soundStopped(nav, 4_000))
        assertEquals(emptyList(), policy.phoneAbandoned(nav, 4_100))
        assertEquals(4_000 + AudioFocusPolicy.TRANSIENT_HOLD_MILLIS, policy.nextDeadlineMillis)
        assertEquals(listOf(Command.Abandon(nav)), policy.onTick(4_500), "放完再留一会儿就释放")
        assertEquals(emptyList(), policy.focusChanged(media, Change.GAIN, 4_501), "媒体拿回焦点：本来就没告诉过手机")
    }

    @Test
    fun navigationWithoutPhoneMessagesFollowsItsSound() {
        val policy = AudioFocusPolicy(systemDucks = true)
        val first = policy.granted(policy.soundStarted(nav, 0), 0)
        assertEquals(listOf<Command>(Command.Request(nav, Gain.TRANSIENT_MAY_DUCK)), first)
        policy.soundStopped(nav, 2_000)
        assertEquals(emptyList(), policy.soundStarted(nav, 2_300), "两句话之间的停顿不放")
        policy.soundStopped(nav, 3_000)
        assertEquals(emptyList(), policy.onTick(3_499))
        assertEquals(listOf(Command.Abandon(nav)), policy.onTick(3_500))
    }

    @Test
    fun navigationThatNeverSoundsOrNeverStopsIsStillReleased() {
        val silent = AudioFocusPolicy(systemDucks = true)
        silent.granted(silent.phoneRequested(nav, 0), 0)
        assertEquals(emptyList(), silent.onTick(4_999))
        assertEquals(listOf(Command.Abandon(nav)), silent.onTick(5_000), "手机要了却一直没声音")

        val endless = AudioFocusPolicy(systemDucks = true)
        endless.granted(endless.phoneRequested(nav, 0), 0)
        endless.soundStarted(nav, 10)
        endless.phoneAbandoned(nav, 1_000)
        assertEquals(emptyList(), endless.onTick(3_999))
        assertEquals(listOf(Command.Abandon(nav)), endless.onTick(4_000), "手机放弃了却一直没放空")
    }

    @Test
    fun anotherAppDuckingIsPassedToThePhoneAndDuckedLocallyOnlyOnOldSystems() {
        val old = AudioFocusPolicy(systemDucks = false)
        old.granted(old.phoneRequested(media, 0), 0)
        assertEquals(
            listOf(Command.Volume(media, Level.DUCKED), Command.NotifyPhone(PhoneChange.DUCK)),
            old.focusChanged(media, Change.LOSS_TRANSIENT_CAN_DUCK, 100),
        )
        assertEquals(
            listOf(Command.Volume(media, Level.NORMAL), Command.NotifyPhone(PhoneChange.GAINED)),
            old.focusChanged(media, Change.GAIN, 2_000),
        )

        val modern = AudioFocusPolicy(systemDucks = true)
        modern.granted(modern.phoneRequested(media, 0), 0)
        assertEquals(
            listOf<Command>(Command.NotifyPhone(PhoneChange.DUCK)),
            modern.focusChanged(media, Change.LOSS_TRANSIENT_CAN_DUCK, 100),
            "系统自己会压低我们，不再压一遍",
        )
    }

    @Test
    fun refusedOrDelayedRequestsMuteAndPauseThePhone() {
        val policy = AudioFocusPolicy(systemDucks = true)
        policy.phoneRequested(media, 0)
        assertEquals(
            listOf(Command.Volume(media, Level.MUTED), Command.NotifyPhone(PhoneChange.LOST_TRANSIENT)),
            policy.requestResult(media, Result.DELAYED, 0),
        )
        assertEquals(
            listOf(Command.Volume(media, Level.NORMAL), Command.NotifyPhone(PhoneChange.GAINED)),
            policy.focusChanged(media, Change.GAIN, 3_000),
            "等到了",
        )

        val refused = AudioFocusPolicy(systemDucks = true)
        refused.phoneRequested(media, 0)
        assertEquals(
            listOf(Command.Volume(media, Level.MUTED), Command.NotifyPhone(PhoneChange.LOST_TRANSIENT)),
            refused.requestResult(media, Result.FAILED, 0),
        )
        assertEquals(listOf(Command.Request(media, Gain.GAIN)), refused.phoneRequested(media, 5_000), "手机再要时再申请")

        // 导航被拒：只静音导航，不告诉手机。
        val navRefused = AudioFocusPolicy(systemDucks = true)
        navRefused.soundStarted(nav, 0)
        assertEquals(listOf<Command>(Command.Volume(nav, Level.MUTED)), navRefused.requestResult(nav, Result.FAILED, 0))
    }

    @Test
    fun transientRoutesAreMutedWhenSomethingExclusiveTakesOver() {
        val policy = AudioFocusPolicy(systemDucks = true)
        policy.granted(policy.soundStarted(nav, 0), 0)
        assertEquals(listOf<Command>(Command.Volume(nav, Level.MUTED)), policy.focusChanged(nav, Change.LOSS_TRANSIENT, 100))
        assertEquals(listOf<Command>(Command.Volume(nav, Level.NORMAL)), policy.focusChanged(nav, Change.GAIN, 900))
    }

    @Test
    fun releaseAllGivesBackEverythingAndRestoresVolume() {
        val policy = AudioFocusPolicy(systemDucks = true)
        policy.granted(policy.phoneRequested(media, 0), 0)
        policy.granted(policy.soundStarted(nav, 10), 10)
        policy.focusChanged(media, Change.LOSS_TRANSIENT, 20)
        val released = policy.releaseAll()
        assertTrue(Command.Abandon(media) in released && Command.Abandon(nav) in released, "$released")
        assertTrue(Command.Volume(media, Level.NORMAL) in released, "$released")
        assertTrue(released.none { it is Command.NotifyPhone }, "收尾不告诉手机")
        assertNull(policy.nextDeadlineMillis)
        assertEquals(emptyList(), policy.focusChanged(media, Change.GAIN, 30))
        // 拆掉之后手机直接重新出声（没先要焦点）：按出声申请。
        assertEquals(listOf(Command.Request(media, Gain.GAIN)), policy.soundStarted(media, 40))
    }

    /** 手机「全部放弃」时导航还空着：留下的放弃时间不能让之后按出声的申请一出来就被放掉。 */
    @Test
    fun staleAbandonDoesNotReleaseAFreshRequest() {
        val policy = AudioFocusPolicy(systemDucks = true)
        assertEquals(emptyList(), policy.phoneAbandonedAll(0))
        val start = policy.soundStarted(nav, 3_000)
        assertEquals(listOf<Command>(Command.Request(nav, Gain.TRANSIENT_MAY_DUCK)), start)
        assertEquals(emptyList(), policy.requestResult(nav, Result.GRANTED, 3_000))
        assertNull(policy.nextDeadlineMillis, "还在响，不排放弃")
    }

    /** 不分流的手机播报时只要「短暂、压低别人」：照它申请，播完放掉，收音机只是被压低一会儿。 */
    @Test
    fun legacyPromptOnlyTakesTransientFocus() {
        val policy = AudioFocusPolicy(systemDucks = true)
        assertEquals(
            listOf(Command.Request(media, Gain.TRANSIENT_MAY_DUCK)),
            policy.phoneRequested(media, 0, Gain.TRANSIENT_MAY_DUCK),
        )
        policy.requestResult(media, Result.GRANTED, 0)
        policy.phoneAbandoned(media, 3_000)
        assertEquals(listOf<Command>(Command.Abandon(media)), policy.onTick(3_000 + AudioFocusPolicy.MEDIA_RELEASE_MILLIS))
    }

    @Test
    fun strongerRequestsUpgradeAndWeakerOnesDoNotDowngrade() {
        val policy = AudioFocusPolicy(systemDucks = true)
        policy.granted(policy.phoneRequested(media, 0, Gain.TRANSIENT_MAY_DUCK), 0)
        assertEquals(listOf(Command.Request(media, Gain.GAIN)), policy.phoneRequested(media, 100, Gain.GAIN), "开始放歌：升级成长期")
        assertEquals(emptyList(), policy.requestResult(media, Result.GRANTED, 100))
        assertEquals(emptyList(), policy.phoneRequested(media, 200, Gain.TRANSIENT_MAY_DUCK), "播报时不降级")
        assertEquals(emptyList(), policy.phoneRequested(media, 300, Gain.TRANSIENT), "短暂也不降级")
    }

    @Test
    fun phoneFocusTypesMapToGains() {
        assertEquals(Gain.GAIN, Gain.ofPhoneFocusType(1))
        assertEquals(Gain.TRANSIENT, Gain.ofPhoneFocusType(2))
        assertEquals(Gain.TRANSIENT_MAY_DUCK, Gain.ofPhoneFocusType(3))
        assertEquals(Gain.TRANSIENT_EXCLUSIVE, Gain.ofPhoneFocusType(4))
        for (other in listOf(null, 0, 5, -1)) assertNull(Gain.ofPhoneFocusType(other), "$other")
    }

    @Test
    fun gainsFollowTheUsage() {
        assertEquals(Gain.GAIN, AudioFocusPolicy.gainOf(AudioUsage.MEDIA))
        assertEquals(Gain.TRANSIENT_MAY_DUCK, AudioFocusPolicy.gainOf(AudioUsage.NAVIGATION))
        assertEquals(Gain.TRANSIENT_MAY_DUCK, AudioFocusPolicy.gainOf(AudioUsage.ASSISTANT))
        assertEquals(Gain.TRANSIENT, AudioFocusPolicy.gainOf(AudioUsage.CALL))
        assertEquals(Gain.TRANSIENT, AudioFocusPolicy.gainOf(AudioUsage.RINGTONE))
        val policy = AudioFocusPolicy(systemDucks = true)
        assertEquals(listOf(Command.Request(AudioUsage.RINGTONE, Gain.TRANSIENT)), policy.soundStarted(AudioUsage.RINGTONE, 0))
    }

    private val call = AudioUsage.CALL

    /** 来电：申请通话焦点并钉住，媒体在本地静音、不告诉手机；我们自己引起的「暂时失去」不理会；挂断后恢复、放掉。 */
    @Test
    fun aCallTakesTheCallFocusAndMutesMediaLocallyUntilHangUp() {
        val policy = AudioFocusPolicy(systemDucks = true)
        policy.granted(policy.phoneRequested(media, 0), 0)
        assertEquals(
            listOf(Command.Request(call, Gain.TRANSIENT), Command.Volume(media, Level.MUTED)),
            policy.callStateChanged(AudioFocusPolicy.CallPhase.RINGING, 1_000),
        )
        assertEquals(emptyList(), policy.requestResult(call, Result.GRANTED, 1_000))
        assertEquals(emptyList(), policy.focusChanged(media, Change.LOSS_TRANSIENT, 1_001), "自己的通话申请引起的，不告诉手机")
        assertEquals(emptyList(), policy.callStateChanged(AudioFocusPolicy.CallPhase.ACTIVE, 5_000))
        assertEquals(emptyList(), policy.onTick(60_000), "通话中一直拿着")
        assertEquals(
            listOf(Command.Volume(media, Level.NORMAL), Command.Abandon(call)),
            policy.callStateChanged(AudioFocusPolicy.CallPhase.IDLE, 90_000),
        )
        assertEquals(emptyList(), policy.focusChanged(media, Change.GAIN, 90_001), "没告诉过手机暂停")
    }

    /** 自己的导航（压低别人）之后，别的应用真的暂时拿走焦点：照常静音、告诉手机暂停。 */
    @Test
    fun aRealTransientLossAfterOurOwnNavigationIsNotSwallowed() {
        val policy = AudioFocusPolicy(systemDucks = true)
        policy.granted(policy.phoneRequested(media, 0), 0)
        policy.granted(policy.soundStarted(nav, 10), 10)
        assertEquals(
            listOf(Command.Volume(media, Level.MUTED), Command.NotifyPhone(PhoneChange.LOST_TRANSIENT)),
            policy.focusChanged(media, Change.LOSS_TRANSIENT, 20),
        )
        assertEquals(emptyList(), policy.focusChanged(media, Change.LOSS_TRANSIENT_CAN_DUCK, 21).filterIsInstance<Command.NotifyPhone>())
    }

    /** 先来电、铃声那一路后出声：铃声的申请让通话那份收到「暂时失去」，是我们自己引起的，通话不能被静音。 */
    @Test
    fun ringtoneAfterTheCallStateDoesNotMuteTheCall() {
        val policy = AudioFocusPolicy(systemDucks = true)
        policy.granted(policy.callStateChanged(AudioFocusPolicy.CallPhase.RINGING, 0), 0)
        policy.granted(policy.soundStarted(AudioUsage.RINGTONE, 100), 100)
        assertEquals(emptyList(), policy.focusChanged(call, Change.LOSS_TRANSIENT, 101))
        policy.soundStopped(AudioUsage.RINGTONE, 5_000)
        policy.callStateChanged(AudioFocusPolicy.CallPhase.ACTIVE, 5_000)
        assertEquals(listOf<Command>(Command.Abandon(AudioUsage.RINGTONE)), policy.onTick(5_500))
        assertEquals(emptyList(), policy.focusChanged(call, Change.GAIN, 5_501))
        assertEquals(emptyList(), policy.soundStarted(call, 5_600), "接通后的声音不被吃掉")
    }

    /** 反过来：铃声先出声、来电状态后到，铃声不能被通话的申请静音。 */
    @Test
    fun theCallStateAfterTheRingtoneDoesNotMuteTheRingtone() {
        val policy = AudioFocusPolicy(systemDucks = true)
        policy.granted(policy.soundStarted(AudioUsage.RINGTONE, 0), 0)
        policy.granted(policy.callStateChanged(AudioFocusPolicy.CallPhase.RINGING, 50), 50)
        assertEquals(emptyList(), policy.focusChanged(AudioUsage.RINGTONE, Change.LOSS_TRANSIENT, 51))
    }

    /** 播报中手机开始放歌：媒体的长期申请让导航那份收到「长期失去」，是我们自己引起的，播报不能被掐掉。 */
    @Test
    fun ourOwnMediaRequestDoesNotCutOurNavigation() {
        val policy = AudioFocusPolicy(systemDucks = true)
        policy.granted(policy.soundStarted(nav, 0), 0)
        policy.granted(policy.phoneRequested(media, 100), 100)
        assertEquals(emptyList(), policy.focusChanged(nav, Change.LOSS, 101))
        // 别的应用真的拿走导航的焦点时照常静音。
        assertEquals(listOf<Command>(Command.Volume(nav, Level.MUTED)), policy.focusChanged(nav, Change.LOSS_TRANSIENT, 200))
    }

    /** 申请被拒绝时，记下的「自己引起的变化」要撤掉，不然之后别人真拿走焦点会被当成自己的。 */
    @Test
    fun aRefusedRequestDoesNotLeaveExpectedChangesBehind() {
        val policy = AudioFocusPolicy(systemDucks = true)
        policy.granted(policy.phoneRequested(media, 0), 0)
        policy.soundStarted(AudioUsage.RINGTONE, 10)
        policy.requestResult(AudioUsage.RINGTONE, Result.FAILED, 10)
        assertEquals(
            listOf(Command.Volume(media, Level.MUTED), Command.NotifyPhone(PhoneChange.LOST_TRANSIENT)),
            policy.focusChanged(media, Change.LOSS_TRANSIENT, 20),
        )
    }

    @Test
    fun theCallAudioTailPlaysOutAfterHangUp() {
        val policy = AudioFocusPolicy(systemDucks = true)
        policy.granted(policy.callStateChanged(AudioFocusPolicy.CallPhase.ACTIVE, 0), 0)
        assertEquals(emptyList(), policy.soundStarted(call, 100), "已经拿着，不再申请")
        assertEquals(
            listOf<Command>(Command.Volume(media, Level.NORMAL)),
            policy.callStateChanged(AudioFocusPolicy.CallPhase.IDLE, 10_000),
            "媒体恢复；通话那一路还在响，等它放完再放焦点",
        )
        policy.soundStopped(call, 10_200)
        assertEquals(emptyList(), policy.onTick(10_699))
        assertEquals(listOf<Command>(Command.Abandon(call)), policy.onTick(10_700))
    }

    @Test
    fun aDataSessionEndingKeepsAnOngoingCall() {
        val policy = AudioFocusPolicy(systemDucks = true)
        policy.granted(policy.phoneRequested(media, 0), 0)
        policy.granted(policy.callStateChanged(AudioFocusPolicy.CallPhase.ACTIVE, 10), 10)
        assertEquals(listOf<Command>(Command.Abandon(media)), policy.dataSessionEnded(), "媒体放掉、仍然静音；通话焦点留着")
        assertEquals(listOf(Command.Volume(media, Level.NORMAL), Command.Abandon(call)), policy.releaseAll(), "连接结束才全部放掉")
    }

    @Test
    fun theVoiceAssistantDucksMediaUntilIdleOrExpiry() {
        val policy = AudioFocusPolicy(systemDucks = true)
        policy.granted(policy.phoneRequested(media, 0), 0)
        assertEquals(listOf<Command>(Command.Volume(media, Level.DUCKED)), policy.assistantStateChanged(true, 1_000))
        assertEquals(listOf<Command>(Command.Volume(media, Level.NORMAL)), policy.assistantStateChanged(false, 5_000))
        policy.assistantStateChanged(true, 6_000)
        assertEquals(6_000 + AudioFocusPolicy.ASSISTANT_STATE_MAX_MILLIS, policy.nextDeadlineMillis)
        assertEquals(emptyList(), policy.onTick(35_999))
        assertEquals(listOf<Command>(Command.Volume(media, Level.NORMAL)), policy.onTick(36_000), "没报空闲也会自己恢复")
        // 通话静音比语音助手压低优先。
        policy.assistantStateChanged(true, 40_000)
        policy.callStateChanged(AudioFocusPolicy.CallPhase.RINGING, 40_100)
        assertEquals(emptyList(), policy.assistantStateChanged(false, 40_200).filterIsInstance<Command.Volume>(), "通话中仍静音")
    }
}
