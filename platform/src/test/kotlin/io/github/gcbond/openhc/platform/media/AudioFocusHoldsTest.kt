package io.github.gcbond.openhc.platform.media

import io.github.gcbond.openhc.platform.media.AudioFocusPolicy.Change
import io.github.gcbond.openhc.platform.media.AudioFocusPolicy.Result
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [AudioFocusPolicy.holds]：媒体按钮会话跟着它建和关，只在替手机拿着媒体焦点时为 true。 */
class AudioFocusHoldsTest {
    private val media = AudioUsage.MEDIA

    @Test
    fun followsGrantAndPermanentLoss() {
        val policy = AudioFocusPolicy(systemDucks = true)
        assertFalse(policy.holds(media))
        policy.phoneRequested(media, 0)
        policy.requestResult(media, Result.GRANTED, 0)
        assertTrue(policy.holds(media))
        policy.focusChanged(media, Change.LOSS_TRANSIENT, 100)
        assertTrue(policy.holds(media), "暂时失去（车机播报等）仍算拿着")
        policy.focusChanged(media, Change.GAIN, 200)
        policy.focusChanged(media, Change.LOSS_TRANSIENT_CAN_DUCK, 300)
        assertTrue(policy.holds(media), "被压低仍算拿着")
        policy.focusChanged(media, Change.LOSS, 400)
        assertFalse(policy.holds(media), "车机收音机等长期拿走")
    }

    @Test
    fun falseWhenRefusedAbandonedOrReleased() {
        val refused = AudioFocusPolicy(systemDucks = true)
        refused.phoneRequested(media, 0)
        refused.requestResult(media, Result.FAILED, 0)
        assertFalse(refused.holds(media))

        val abandoned = AudioFocusPolicy(systemDucks = true)
        abandoned.phoneRequested(media, 0)
        abandoned.requestResult(media, Result.GRANTED, 0)
        abandoned.phoneAbandoned(media, 1_000)
        assertTrue(abandoned.holds(media), "手机放弃后还有一段宽限")
        abandoned.onTick(1_000 + AudioFocusPolicy.MEDIA_RELEASE_MILLIS)
        assertFalse(abandoned.holds(media))

        val released = AudioFocusPolicy(systemDucks = true)
        released.phoneRequested(media, 0)
        released.requestResult(media, Result.GRANTED, 0)
        released.releaseAll()
        assertFalse(released.holds(media))
    }
}
