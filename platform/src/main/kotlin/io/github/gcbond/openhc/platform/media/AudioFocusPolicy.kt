package io.github.gcbond.openhc.platform.media

/**
 * 音频焦点的决策（纯逻辑，不引用安卓类，可在电脑上测）：各用途（[AudioUsage]）什么时候向系统申请、放弃焦点，
 * 失去焦点时我们这一路压低还是静音，以及要告诉手机什么。安卓接口由 [AudioFocusController] 执行。
 *
 * | 用途 | 按出声申请时 | 什么时候申请 | 什么时候放弃 |
 * |---|---|---|---|
 * | 媒体 | 长期（GAIN） | 手机要媒体焦点；手机从没发过焦点消息时，媒体这一路开始出声 | 手机放弃后 [MEDIA_RELEASE_MILLIS] 内没再要；手机从没发过焦点消息时，出声停了 [MEDIA_IDLE_MILLIS] |
 * | 导航、提示与语音 | 短暂并压低别人 | 手机的分流焦点消息要这一路，或这一路开始出声 | 这一路放完 [TRANSIENT_HOLD_MILLIS] 且手机没在要；手机要了却一直没声音，等 [TRANSIENT_WAIT_MILLIS]；手机放弃了却还在响，最多再等 [TRANSIENT_FORCE_MILLIS] |
 * | 通话、铃声 | 短暂 | 同上；另外手机报来电 / 通话中时（[callStateChanged]）也申请，挂断前一直拿着 | 同上（挂断之后） |
 *
 * 来电、通话中：媒体在本地静音（不告诉手机，手机自己会处理它的音乐），挂断后恢复。语音助手在用（[assistantStateChanged]）：
 * 媒体在本地压低，空闲后恢复；手机没报空闲时 [ASSISTANT_STATE_MAX_MILLIS] 后自己恢复。
 *
 * 手机要焦点时带着它要的类型（[phoneRequested] 的 gain），照它申请：不分流的手机播报导航时只发「短暂、压低别人」，
 * 不能因此长期拿走车机收音机的焦点。已经拿着时只往更强的升级（短暂 → 长期），不往下降。
 *
 * 媒体只要手机发过焦点消息就听手机的，不再按出声申请：分流时音乐那一路连上就一直在发（没放歌时是静音），
 * 按出声申请会一连上就把车机的收音机等停掉。
 *
 * 系统通知焦点变化时：
 * - **媒体**长期失去：静音，告诉手机停下（[PhoneChange.LOST]），并放掉这次申请；等手机再要时再申请（手机从没发过焦点消息时，
 *   媒体放空后重新出声也算）。手机既不认「停下」、音乐那一路又一直不断流的话，会一直静音到手机再要焦点。
 *   暂时失去：静音，告诉手机暂停（[PhoneChange.LOST_TRANSIENT]）。暂时可压低：我们自己的导航等短暂申请引起的不理会
 *   （同一个应用里系统不会替我们压低媒体；这里假设手机发来的音乐在播报时已经自己压低了，待真机确认，
 *   要是没有，就在短暂的几路出声时给媒体出 [Level.DUCKED]）。别的应用引起的，告诉手机可以压低
 *   （[PhoneChange.DUCK]），系统不会自动压低的老系统（安卓 8.0 以下，[systemDucks] 为 false）自己压低。
 *   拿回焦点：恢复音量，之前告诉过手机暂停或压低的，告诉它接着放（[PhoneChange.GAINED]）。
 * - **导航等短暂的几路**：失去时静音、拿回来恢复，不告诉手机（它们本来就短，手机那边也只按媒体处理）。
 * - 申请被拒绝或要等（例如车机正在通话）：这一路先静音；媒体还会告诉手机暂停，拿到焦点时再告诉它接着放。
 * - **我们自己的几路互相之间**：每个用途各是系统里一个焦点客户端，我们申请一个用途时，正拿着焦点的另几个用途会各收到一次
 *   变化（申请长期 → 长期失去，短暂 → 暂时失去，短暂并压低别人 → 暂时可压低）。申请时按类型给它们各记一笔，收到对应的
 *   变化就不理会（例如来电后铃声那一路出声，通话不能因此被静音；播报中开始放歌，播报不能因此被掐掉）；申请被拒绝时撤掉。
 *
 * 不是线程安全的：调用方在同一个线程上串行调用。时间是单调时钟毫秒。
 */
class AudioFocusPolicy(private val systemDucks: Boolean) {

    /** 申请哪种焦点；[strength] 越大越强（长期 > 短暂 > 短暂并压低别人）。 */
    enum class Gain(val label: String, val strength: Int) {
        GAIN("长期", 3),
        TRANSIENT("短暂", 2),
        TRANSIENT_EXCLUSIVE("短暂，独占", 2),
        TRANSIENT_MAY_DUCK("短暂，压低别人", 1),
        ;

        companion object {
            /** 手机焦点消息里的焦点类型 1～4（1 长期、2 短暂、3 短暂并压低别人、4 短暂独占）；别的值为 null。 */
            fun ofPhoneFocusType(focusType: Int?): Gain? = when (focusType) {
                1 -> GAIN
                2 -> TRANSIENT
                3 -> TRANSIENT_MAY_DUCK
                4 -> TRANSIENT_EXCLUSIVE
                else -> null
            }
        }
    }

    /** 我们这一路的音量档。 */
    enum class Level(val volume: Float, val label: String) {
        NORMAL(1f, "正常"),
        DUCKED(0.3f, "压低"),
        MUTED(0f, "静音"),
    }

    /** 要告诉手机的焦点变化（与车机发给手机的那几种一一对应）。 */
    enum class PhoneChange(val label: String) {
        GAINED("车机这边拿回焦点，手机可以接着放"),
        LOST("被车机别的应用长期占用，手机应当停下"),
        LOST_TRANSIENT("被车机别的应用暂时占用，手机应当暂停"),
        DUCK("车机别的应用要短暂出声，手机可以压低"),
    }

    /** 系统给的焦点变化。 */
    enum class Change { GAIN, LOSS, LOSS_TRANSIENT, LOSS_TRANSIENT_CAN_DUCK }

    /** 申请的结果。 */
    enum class Result { GRANTED, DELAYED, FAILED }

    /** 手机的通话状态。 */
    enum class CallPhase(val label: String) {
        IDLE("没有通话"),
        RINGING("来电"),
        ACTIVE("通话中"),
    }

    sealed class Command {
        data class Request(val usage: AudioUsage, val gain: Gain) : Command() {
            override fun toString() = "申请${usage.label}焦点（${gain.label}）"
        }

        data class Abandon(val usage: AudioUsage) : Command() {
            override fun toString() = "放弃${usage.label}焦点"
        }

        data class Volume(val usage: AudioUsage, val level: Level) : Command() {
            override fun toString() = "${usage.label}音量：${level.label}"
        }

        data class NotifyPhone(val change: PhoneChange) : Command() {
            override fun toString() = "告诉手机：${change.label}"
        }
    }

    private enum class State {
        /** 没有在申请。 */
        IDLE,
        HELD,

        /** 申请结果是「要等」，等系统给焦点。 */
        DELAYED,
        LOST_TRANSIENT,
        DUCKED,

        /** 长期失去（已放掉申请）或申请被拒：静音，等新的理由再申请。 */
        LOST,
        ;

        val active: Boolean get() = this == HELD || this == DELAYED || this == LOST_TRANSIENT || this == DUCKED
    }

    private class Slot(val usage: AudioUsage) {
        var state = State.IDLE

        /** 焦点给的音量档。 */
        var focusLevel = Level.NORMAL

        /** 实际下发过的音量档（焦点、通话、语音助手合起来的结果）。 */
        var applied = Level.NORMAL

        /** 通话期间钉住，不按出声和手机消息放掉。 */
        var pinned = false

        /** 我们自己的申请会让这一路收到的变化（长期失去、暂时失去、暂时可压低）各还有几次，收到就不理会。 */
        var selfLosses = 0
        var selfPauses = 0
        var selfDucks = 0

        fun clearSelf() {
            selfLosses = 0
            selfPauses = 0
            selfDucks = 0
        }
        var sounding = 0
        var phoneWants = false
        var phoneAbandonedAt: Long? = null
        var soundStoppedAt: Long? = null
        var requestedAt = 0L

        /** 正在申请（或拿着）的焦点类型。 */
        var gain: Gain? = null
    }

    private val slots = AudioUsage.entries.associateWith { Slot(it) }
    private var phoneEverSent = false

    /** 告诉过手机暂停 / 停下 / 压低、还没告诉它接着放。 */
    private var phoneToldLoss = false

    private var callPhase = CallPhase.IDLE

    /** 语音助手在用：到这个时间还没报空闲就当空闲；null 表示空闲。 */
    private var assistantUntil: Long? = null

    // ---------------- 输入 ----------------

    /**
     * 手机要这个用途的焦点（它要出声了），[gain] 是它要的类型（null 按用途的默认，见 [gainOf]）。
     * 没在申请就照它申请；已经拿着、而它要的更强时重新申请升级。
     */
    fun phoneRequested(usage: AudioUsage, nowMillis: Long, gain: Gain? = null): List<Command> {
        phoneEverSent = true
        val slot = slot(usage)
        slot.phoneWants = true
        slot.phoneAbandonedAt = null
        val wanted = gain ?: gainOf(usage)
        val out = ArrayList<Command>(2)
        val held = slot.gain
        if (!slot.state.active || (slot.state == State.HELD && held != null && wanted.strength > held.strength)) {
            request(slot, wanted, nowMillis, out)
        }
        update(nowMillis, out)
        return out
    }

    /** 手机放弃这个用途的焦点（它放完了）。 */
    fun phoneAbandoned(usage: AudioUsage, nowMillis: Long): List<Command> {
        phoneEverSent = true
        val slot = slot(usage)
        slot.phoneWants = false
        slot.phoneAbandonedAt = nowMillis
        val out = ArrayList<Command>(2)
        update(nowMillis, out)
        return out
    }

    /**
     * 这个用途现在是不是由我们替手机拿着焦点（含正在等系统给、暂时失去、被压低）；没在申请、长期失去、被拒绝时为 false。
     * 只读，不改变状态。
     */
    fun holds(usage: AudioUsage): Boolean = slot(usage).state.active

    /** 手机放弃全部焦点。 */
    fun phoneAbandonedAll(nowMillis: Long): List<Command> {
        phoneEverSent = true
        for (slot in slots.values) {
            slot.phoneWants = false
            slot.phoneAbandonedAt = nowMillis
        }
        val out = ArrayList<Command>(2)
        update(nowMillis, out)
        return out
    }

    /** 这个用途的一个播放器开始（或放空后重新）出声。 */
    fun soundStarted(usage: AudioUsage, nowMillis: Long): List<Command> {
        val slot = slot(usage)
        slot.sounding++
        slot.soundStoppedAt = null
        val out = ArrayList<Command>(2)
        if (!slot.state.active && (usage != AudioUsage.MEDIA || !phoneEverSent)) request(slot, gainOf(usage), nowMillis, out)
        update(nowMillis, out)
        return out
    }

    /** 这个用途的一个播放器放空了、或者关了（之前报过开始出声的才调）。 */
    fun soundStopped(usage: AudioUsage, nowMillis: Long): List<Command> {
        val slot = slot(usage)
        if (slot.sounding > 0) slot.sounding--
        if (slot.sounding == 0) slot.soundStoppedAt = nowMillis
        val out = ArrayList<Command>(2)
        update(nowMillis, out)
        return out
    }

    /** [Command.Request] 的结果（执行方申请后马上交回）。 */
    fun requestResult(usage: AudioUsage, result: Result, nowMillis: Long): List<Command> {
        val slot = slot(usage)
        val out = ArrayList<Command>(2)
        if (slot.state != State.HELD) return out
        when (result) {
            Result.GRANTED -> {
                setLevel(slot, Level.NORMAL, out)
                if (usage == AudioUsage.MEDIA) tellPhoneGained(out)
            }
            Result.DELAYED -> {
                slot.state = State.DELAYED
                setLevel(slot, Level.MUTED, out)
                if (usage == AudioUsage.MEDIA) tellPhone(PhoneChange.LOST_TRANSIENT, out)
            }
            Result.FAILED -> {
                slot.state = State.LOST
                setLevel(slot, Level.MUTED, out)
                if (usage == AudioUsage.MEDIA) tellPhone(PhoneChange.LOST_TRANSIENT, out)
                expectSelfChanges(slot, slot.gain, -1)
            }
        }
        update(nowMillis, out)
        return out
    }

    /** 系统通知这个用途的焦点变了。没在申请的用途收到的（过时的回调）、我们自己的申请引起的，都不理会。 */
    fun focusChanged(usage: AudioUsage, change: Change, nowMillis: Long): List<Command> {
        val slot = slot(usage)
        val out = ArrayList<Command>(3)
        if (!slot.state.active || consumeSelfChange(slot, change)) return out
        val media = usage == AudioUsage.MEDIA
        when (change) {
            Change.GAIN -> {
                slot.state = State.HELD
                slot.clearSelf()
                setLevel(slot, Level.NORMAL, out)
                if (media) tellPhoneGained(out)
            }
            Change.LOSS -> {
                slot.state = State.LOST
                slot.clearSelf()
                setLevel(slot, Level.MUTED, out)
                out += Command.Abandon(usage)
                if (media) tellPhone(PhoneChange.LOST, out)
            }
            Change.LOSS_TRANSIENT -> {
                slot.state = State.LOST_TRANSIENT
                setLevel(slot, Level.MUTED, out)
                if (media) tellPhone(PhoneChange.LOST_TRANSIENT, out)
            }
            Change.LOSS_TRANSIENT_CAN_DUCK -> {
                slot.state = State.DUCKED
                setLevel(slot, if (systemDucks) Level.NORMAL else Level.DUCKED, out)
                if (media) tellPhone(PhoneChange.DUCK, out)
            }
        }
        update(nowMillis, out)
        return out
    }

    /**
     * 手机报通话状态：来电、通话中时申请通话焦点（短暂）并钉住，媒体在本地静音；挂断后通话焦点按短暂的规则放掉
     * （等通话那一路放完），媒体恢复。不告诉手机。
     */
    fun callStateChanged(phase: CallPhase, nowMillis: Long): List<Command> {
        val out = ArrayList<Command>(3)
        if (phase != callPhase) {
            callPhase = phase
            val call = slot(AudioUsage.CALL)
            if (phase == CallPhase.IDLE) {
                call.pinned = false
                call.phoneWants = false
                call.phoneAbandonedAt = nowMillis
            } else {
                call.pinned = true
                if (!call.state.active) request(call, Gain.TRANSIENT, nowMillis, out)
            }
            applyAll(out)
        }
        update(nowMillis, out)
        return out
    }

    /** 手机报语音助手状态：在用时媒体在本地压低，空闲后恢复。不申请焦点（助手出声时那一路自己会申请）。 */
    fun assistantStateChanged(active: Boolean, nowMillis: Long): List<Command> {
        val out = ArrayList<Command>(1)
        assistantUntil = if (active) nowMillis + ASSISTANT_STATE_MAX_MILLIS else null
        applyAll(out)
        update(nowMillis, out)
        return out
    }

    /** 推进定时（放弃焦点的等待）。最晚在 [nextDeadlineMillis] 时调用。 */
    fun onTick(nowMillis: Long): List<Command> {
        val out = ArrayList<Command>(1)
        update(nowMillis, out)
        return out
    }

    /** 下一次需要 [onTick] 的时间；没有等着的为 null。 */
    val nextDeadlineMillis: Long?
        get() = (slots.values.mapNotNull { releaseAt(it) } + listOfNotNull(assistantUntil)).minOrNull()

    /**
     * 一次数据会话被手机拆掉：除了通话中钉住的通话焦点，其余放掉、音量恢复，不告诉手机。之后又回到「手机还没发过焦点消息」：
     * 手机重新出声却没先要焦点时，按出声申请，免得放着声音却没拿焦点。通话和语音助手的状态不变。
     */
    fun dataSessionEnded(): List<Command> = release(keepCall = true)

    /** 连接结束：全部放掉、音量恢复、通话和语音助手的状态清零，不告诉手机。 */
    fun releaseAll(): List<Command> = release(keepCall = false)

    private fun release(keepCall: Boolean): List<Command> {
        val out = ArrayList<Command>()
        if (!keepCall) {
            callPhase = CallPhase.IDLE
            assistantUntil = null
        }
        for (slot in slots.values) {
            slot.sounding = 0
            slot.soundStoppedAt = null
            if (slot.pinned && keepCall) continue
            if (slot.state.active) out += Command.Abandon(slot.usage)
            slot.state = State.IDLE
            slot.gain = null
            slot.pinned = false
            slot.phoneWants = false
            slot.phoneAbandonedAt = null
            setLevel(slot, Level.NORMAL, out)
        }
        applyAll(out)
        phoneToldLoss = false
        phoneEverSent = false
        for (slot in slots.values) if (!slot.state.active) slot.clearSelf()
        return out
    }

    // ---------------- 内部 ----------------

    private fun slot(usage: AudioUsage): Slot = slots.getValue(usage)

    private fun request(slot: Slot, gain: Gain, nowMillis: Long, out: MutableList<Command>) {
        expectSelfChanges(slot, gain, +1)
        slot.state = State.HELD
        slot.gain = gain
        slot.requestedAt = nowMillis
        // 之前（例如手机「全部放弃」时这一路还空着）留下的放弃时间不算这次申请的。
        slot.phoneAbandonedAt = null
        out += Command.Request(slot.usage, gain)
    }

    /** 到点就放掉；语音助手过期就当空闲。 */
    private fun update(nowMillis: Long, out: MutableList<Command>) {
        assistantUntil?.let { until ->
            if (nowMillis >= until) {
                assistantUntil = null
                applyAll(out)
            }
        }
        for (slot in slots.values) {
            val at = releaseAt(slot) ?: continue
            if (nowMillis < at) continue
            out += Command.Abandon(slot.usage)
            slot.state = State.IDLE
            slot.gain = null
            slot.phoneAbandonedAt = null
            slot.clearSelf()
            setLevel(slot, Level.NORMAL, out)
            // 手机自己放弃的，不用再告诉它接着放。
            if (slot.usage == AudioUsage.MEDIA) phoneToldLoss = false
        }
    }

    /** 这一路什么时候该放掉焦点；不用放为 null。 */
    private fun releaseAt(slot: Slot): Long? {
        if (!slot.state.active || slot.pinned) return null
        if (slot.usage == AudioUsage.MEDIA) {
            if (slot.phoneWants) return null
            val abandoned = slot.phoneAbandonedAt
            if (abandoned != null) return abandoned + MEDIA_RELEASE_MILLIS
            if (phoneEverSent || slot.sounding > 0) return null
            return slot.soundStoppedAt?.let { it + MEDIA_IDLE_MILLIS }
        }
        if (slot.sounding > 0) return slot.phoneAbandonedAt?.let { it + TRANSIENT_FORCE_MILLIS }
        val quietSince = slot.soundStoppedAt ?: slot.requestedAt
        return quietSince + if (slot.phoneWants) TRANSIENT_WAIT_MILLIS else TRANSIENT_HOLD_MILLIS
    }

    /**
     * 我们申请 [requester] 的焦点（[delta] 为 +1），或这次申请被拒绝（-1、撤掉）：正拿着焦点的另几路会各收到一次变化，
     * 按申请的类型记下（长期 → 长期失去，短暂 → 暂时失去，短暂并压低别人 → 暂时可压低）。
     */
    private fun expectSelfChanges(requester: Slot, gain: Gain?, delta: Int) {
        for (other in slots.values) {
            if (other === requester || other.state != State.HELD) continue
            when (gain) {
                Gain.GAIN -> other.selfLosses = (other.selfLosses + delta).coerceAtLeast(0)
                Gain.TRANSIENT, Gain.TRANSIENT_EXCLUSIVE -> other.selfPauses = (other.selfPauses + delta).coerceAtLeast(0)
                Gain.TRANSIENT_MAY_DUCK -> other.selfDucks = (other.selfDucks + delta).coerceAtLeast(0)
                null -> Unit
            }
        }
    }

    /** 这次变化是我们自己的申请引起的：记的次数减一、返回 true（不理会）。 */
    private fun consumeSelfChange(slot: Slot, change: Change): Boolean = when (change) {
        Change.LOSS -> slot.selfLosses > 0 && slot.selfLosses-- > 0
        Change.LOSS_TRANSIENT -> slot.selfPauses > 0 && slot.selfPauses-- > 0
        Change.LOSS_TRANSIENT_CAN_DUCK -> slot.selfDucks > 0 && slot.selfDucks-- > 0
        Change.GAIN -> false
    }

    private fun setLevel(slot: Slot, level: Level, out: MutableList<Command>) {
        slot.focusLevel = level
        apply(slot, out)
    }

    /** 焦点、通话、语音助手合起来，取最低的那一档；变了才下发。 */
    private fun apply(slot: Slot, out: MutableList<Command>) {
        var level = slot.focusLevel
        if (slot.usage == AudioUsage.MEDIA) {
            if (callPhase != CallPhase.IDLE) {
                level = Level.MUTED
            } else if (assistantUntil != null && level == Level.NORMAL) {
                level = Level.DUCKED
            }
        }
        if (slot.applied == level) return
        slot.applied = level
        out += Command.Volume(slot.usage, level)
    }

    private fun applyAll(out: MutableList<Command>) {
        for (slot in slots.values) apply(slot, out)
    }

    private fun tellPhone(change: PhoneChange, out: MutableList<Command>) {
        phoneToldLoss = true
        out += Command.NotifyPhone(change)
    }

    private fun tellPhoneGained(out: MutableList<Command>) {
        if (!phoneToldLoss) return
        phoneToldLoss = false
        out += Command.NotifyPhone(PhoneChange.GAINED)
    }

    companion object {
        /** 手机放弃媒体焦点后等这么久没再要才放掉（手机播报前后常常先放弃、马上又要）。 */
        const val MEDIA_RELEASE_MILLIS = 1_500L

        /** 手机从没发过焦点消息时，媒体出声停了这么久就放掉。 */
        const val MEDIA_IDLE_MILLIS = 5_000L

        /** 导航等短暂的几路放完后再留这么久（两句话之间的停顿不来回申请）。 */
        const val TRANSIENT_HOLD_MILLIS = 500L

        /** 手机要了短暂焦点却一直没有声音，最多等这么久。 */
        const val TRANSIENT_WAIT_MILLIS = 5_000L

        /** 手机放弃了短暂焦点，我们这一路却还没放完，最多再等这么久。 */
        const val TRANSIENT_FORCE_MILLIS = 3_000L

        /** 手机报语音助手在用之后，最长这么久没报空闲就当空闲（免得一条消息丢了媒体一直压着）。 */
        const val ASSISTANT_STATE_MAX_MILLIS = 30_000L

        fun gainOf(usage: AudioUsage): Gain = when (usage) {
            AudioUsage.MEDIA -> Gain.GAIN
            AudioUsage.NAVIGATION, AudioUsage.ASSISTANT -> Gain.TRANSIENT_MAY_DUCK
            AudioUsage.CALL, AudioUsage.RINGTONE -> Gain.TRANSIENT
        }
    }
}
