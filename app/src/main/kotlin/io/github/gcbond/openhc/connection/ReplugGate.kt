package io.github.gcbond.openhc.connection

/**
 * 手机已经连续多久不在车机上。纯逻辑，时间由调用方传入（单调时钟毫秒）。不是线程安全的。
 *
 * 手机切成 USB 网卡、会话结束后退回普通状态时都会重新枚举，中间断开约一两秒；只有连续不在够久才算真的拔了线。
 */
class AbsenceTimer {
    private var since = NONE

    /** 报告此刻手机在不在（定时查设备列表）。 */
    fun update(phonePresent: Boolean, nowMillis: Long) {
        if (phonePresent) {
            since = NONE
        } else if (since == NONE) {
            since = nowMillis
        }
    }

    fun reset() {
        since = NONE
    }

    /** 手机现在不在。 */
    val away: Boolean get() = since != NONE

    /** 连续不在的时长；手机在时为 0。 */
    fun awayFor(nowMillis: Long): Long = if (since == NONE) 0 else nowMillis - since

    private companion object {
        const val NONE = Long.MIN_VALUE
    }
}

/**
 * 拔线重插的闸门。纯逻辑，时间由调用方传入（单调时钟毫秒）。不是线程安全的。
 *
 * 手机每次插入只响应一次 carInfo。一次连接发过 carInfo 之后（不管怎么结束的），手机约 1.5 秒后会自己退回普通状态、
 * 重新枚举一次，这时再发 carInfo 手机不理；马上拔了再插，手机也常常不理（实测）。所以：
 * - 发过 carInfo 的连接结束时 [arm]；
 * - 闸门关着时不自动开始新的连接，提示用户拔线、等够再插；
 * - 手机**连续** [absentMillis] 毫秒不在车机上，闸门才打开。重新枚举只断开一两秒，不会被当成拔线。
 */
class ReplugGate(val absentMillis: Long) {

    init {
        require(absentMillis > 0) { "absentMillis 必须为正" }
    }

    /** 闸门关着（等拔线重插）。 */
    var isArmed: Boolean = false
        private set

    private val absence = AbsenceTimer()

    /** 一次发过 carInfo 的连接结束了。[phonePresent] 是此刻手机在不在车机上。 */
    fun arm(nowMillis: Long, phonePresent: Boolean) {
        isArmed = true
        absence.reset()
        absence.update(phonePresent, nowMillis)
    }

    /** 报告此刻手机在不在车机上（定时查设备列表）。 */
    fun onPresence(phonePresent: Boolean, nowMillis: Long) {
        if (isArmed) absence.update(phonePresent, nowMillis)
    }

    /** 手机已经拔下（还在等够时间）。 */
    val phoneAway: Boolean get() = isArmed && absence.away

    /** 可以开始新的连接：没上闸，或者手机已经连续离开够久。只查询，不改变状态。 */
    fun isOpen(nowMillis: Long): Boolean = !isArmed || (absence.away && absence.awayFor(nowMillis) >= absentMillis)

    /** 解除（闸门已打开、开始了新的连接，或者用户坚持立即重试）。 */
    fun disarm() {
        isArmed = false
        absence.reset()
    }
}

/** 开始连接的请求从哪来。 */
enum class StartTrigger {
    /** 首页「开始」按钮：用户明确要连。 */
    BUTTON,

    /**
     * 「设备接入」把应用拉起来。不一定是用户插线：发完 carInfo 后手机重新枚举成 USB 网卡、
     * 会话结束（包括用户点了停止）约 1.5 秒后手机退回普通状态，都会再来一次。
     */
    USB_ATTACH,
}

/** 收到开始请求时怎么办。 */
enum class StartDecision {
    /** 什么都不做：已经在连接或在等拔线重插，或者这次「接入」只是手机重新枚举。 */
    IGNORE,

    /** 开始连接。 */
    START,

    /** 这台手机这次插入已经用过：提示拔线等够再插，到时自动开始。 */
    WAIT_FOR_REPLUG,
}

object StartPolicy {
    /**
     * - 用户要求连接期间（[connectionWanted]）一律忽略：会话中途的重新枚举不能打断它；
     * - 闸门开着（没用过这次插入，或手机已经拔下够久）就开始；
     * - 闸门关着时，「接入」不算数：用户点了停止之后手机退回普通状态那一下也是「接入」，不能把停止撤销掉；
     *   用户拔够了时间再插，闸门已经打开，照常开始。只有「开始」按钮才进入等拔线重插。
     */
    fun decide(trigger: StartTrigger, connectionWanted: Boolean, gateOpen: Boolean): StartDecision = when {
        connectionWanted -> StartDecision.IGNORE
        gateOpen -> StartDecision.START
        trigger == StartTrigger.USB_ATTACH -> StartDecision.IGNORE
        else -> StartDecision.WAIT_FOR_REPLUG
    }
}
