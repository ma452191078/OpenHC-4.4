package io.github.gcbond.openhc.core.dmsdp

import io.github.gcbond.openhc.core.common.MalformedMessageException

internal enum class KeepAliveTick { NONE, SEND, TIMEOUT }

/**
 * TCP 保活（手机在加密选择里选了 TCP，或没给 UdpKA）。
 *
 * - 加密切换后 1 秒第一轮，此后每秒一轮；时间以本轮被调用的时刻为准，主循环停顿后不追补。
 * - 每轮：上一轮以来收到过手机合法的 `GET_PARAMETER` 就清零计数，否则计数加一，**连续 10 轮**
 *   没收到就判定失活。收到第一个 GET 之前同样计数。
 * - 收到第一个 GET 之后，每轮车机也主动发一个 `GET_PARAMETER`。手机的 GET 不回 200。
 */
internal class TcpKeepAlive(startMillis: Long) {
    var nextAtMillis: Long? = startMillis + INTERVAL_MILLIS
        private set
    private var alive = false
    private var misses = 0
    private var started = false

    fun onPeerRequest() {
        alive = true
        started = true
    }

    fun tick(nowMillis: Long): KeepAliveTick {
        val due = nextAtMillis ?: return KeepAliveTick.NONE
        if (nowMillis < due) return KeepAliveTick.NONE
        if (alive) {
            misses = 0
            alive = false
        } else {
            misses++
            if (misses >= MAX_MISSES) {
                nextAtMillis = null
                return KeepAliveTick.TIMEOUT
            }
        }
        nextAtMillis = nowMillis + INTERVAL_MILLIS
        return if (started) KeepAliveTick.SEND else KeepAliveTick.NONE
    }

    companion object {
        const val INTERVAL_MILLIS = 1_000L
        const val MAX_MISSES = 10
    }
}

/**
 * UDP 保活（手机选了 `UdpKA: 1` 并给了端口）。
 *
 * 明文固定 16 字节：`BE32(0) ‖ BE16(10) ‖ 10 字节`。兼容格式后 10 字节全 0；扩展格式是
 * `0a de ‖ BE32(30) ‖ 4 字节 0`，其中 30 是周期参数（不是序号）。线上是
 * `0xAB ‖ BE16(32) ‖ IV(16) ‖ 密文(16)`，共 35 字节，每个数据报独立 IV。
 *
 * - 车机先发兼容格式；收到手机的扩展格式（`0a de` 且周期 30）才改发扩展格式、周期参数 30，
 *   收到其他合法保活包则回到兼容格式、周期参数 2。收到包不自动回应。
 * - 每轮间隔 = 周期参数 × 500 毫秒（兼容 1 秒，扩展 15 秒），偶数轮连发两个包。
 * - 检测窗口：轮数超过「周期参数 × [retry] ÷ 2」时检查窗口内是否收到过合法保活包，
 *   没收到就失活，收到了就清零重新计。兼容格式下 retry=160 时第 162 轮判定失活。
 */
internal class UdpKeepAlive(startMillis: Long, private val retry: Int) {
    var extended = false
        private set
    private var period = LEGACY_PERIOD
    private var round = 0
    private var receivedInWindow = 0
    var nextAtMillis: Long? = startMillis + LEGACY_PERIOD * HALF_SECOND_MILLIS
        private set

    fun tick(nowMillis: Long): KeepAliveTick {
        val due = nextAtMillis ?: return KeepAliveTick.NONE
        if (nowMillis < due) return KeepAliveTick.NONE
        if (round > period * retry / 2) {
            if (receivedInWindow == 0) {
                nextAtMillis = null
                return KeepAliveTick.TIMEOUT
            }
            round = 0
            receivedInWindow = 0
        }
        round++
        nextAtMillis = nowMillis + period * HALF_SECOND_MILLIS
        return if (round % 2 == 0) KeepAliveTick.SEND else KeepAliveTick.NONE
    }

    /** 记一个已解密、格式已校验的保活包；返回周期是否因此切换。 */
    fun onPeerKeepAlive(plain: ByteArray): Boolean {
        val isExtended = plain[6].toInt() and 0xFF == 0x0A && plain[7].toInt() and 0xFF == 0xDE &&
            readS32Be(plain, 8) == EXTENDED_PERIOD
        val changed = isExtended != extended
        extended = isExtended
        period = if (isExtended) EXTENDED_PERIOD else LEGACY_PERIOD
        receivedInWindow++
        return changed
    }

    /** 本机当前要发的 16 字节明文。 */
    fun payload(): ByteArray {
        val plain = ByteArray(PLAIN_BYTES)
        plain[5] = 0x0A
        if (extended) {
            plain[6] = 0x0A
            plain[7] = 0xDE.toByte()
            putS32Be(plain, 8, EXTENDED_PERIOD)
        }
        return plain
    }

    companion object {
        const val PLAIN_BYTES = 16
        const val DATAGRAM_BYTES = 3 + AesCtr.IV_BYTES + PLAIN_BYTES
        const val LEGACY_PERIOD = 2
        const val EXTENDED_PERIOD = 30
        private const val HALF_SECOND_MILLIS = 500L

        /** 明文前 6 字节：内部业务号 BE32(0)、长度 BE16(10)。 */
        private val PREFIX = byteArrayOf(0, 0, 0, 0, 0, 0x0A)

        fun seal(cipher: AesCtr, random: java.security.SecureRandom, plain: ByteArray): ByteArray =
            ControlFrames.encode(ControlFrames.FLAG_ENCRYPTED, cipher.seal(random, plain))

        /**
         * 拆一个保活数据报：必须是加密标志、三字节头声明的长度与数据报一致、解密后正好 16 字节且前缀正确。
         * 只接受这一种已证实的格式。
         */
        fun open(cipher: AesCtr, datagram: ByteArray, offset: Int, length: Int): ByteArray {
            if (length < 3 || datagram[offset].toInt() and 0xFF != ControlFrames.FLAG_ENCRYPTED) {
                throw MalformedMessageException("UDP 保活不是加密帧")
            }
            val declared = ((datagram[offset + 1].toInt() and 0xFF) shl 8) or (datagram[offset + 2].toInt() and 0xFF)
            if (declared != length - 3) throw MalformedMessageException("UDP 保活长度字段与数据报不符")
            val plain = cipher.open(datagram, offset + 3, length - 3)
            if (plain.size != PLAIN_BYTES) throw MalformedMessageException("UDP 保活明文不是 16 字节")
            for (i in PREFIX.indices) {
                if (plain[i] != PREFIX[i]) throw MalformedMessageException("UDP 保活明文前缀不对")
            }
            return plain
        }
    }
}
