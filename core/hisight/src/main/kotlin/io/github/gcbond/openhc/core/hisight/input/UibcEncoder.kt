package io.github.gcbond.openhc.core.hisight.input

import io.github.gcbond.openhc.core.hisight.HiSightInput
import io.github.gcbond.openhc.core.hisight.crypto.AesCtr
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom

/**
 * 回控连接上的输入封包（UIBC）。每个包：
 *
 * ```
 * 0x00 | 类别(1) | BE16 包总长 | IV16 || AES-CTR( FF×6 || 正文 )
 * ```
 *
 * 类别：通用输入（触摸、按键）为 0，成文输入为 2。正文：
 * - 触摸：动作(1：0 按下/1 抬起/2 移动) | BE16 (1 + 5N + 补齐) | N | N×(编号 1, BE16 x, BE16 y) | 补齐；
 *   `4 + 5N` 为奇数时末尾补一个 0。触点按编号升序。
 * - 按键：动作(1：3 按下/4 抬起) | BE16 9 | 0 | BE16 键码 | BE16 0 | BE32 修饰位。键码是安卓键码。
 *   手机选了源设备类型 7 时任意键码、修饰位照发；没选 7 时只能发 [BASIC_KEY_CODES]、修饰位为 0
 *   （这组键在两种源设备类型下字节相同）。
 * - 成文：0 | BE16 (2 + n + 补齐) | BE16 n | n 字节 UTF-8 | 补齐；`5 + n` 为奇数时补一个 0。
 *
 * 触摸的语义是「当前全部触点的快照」：一组手势里出现过多个触点后，手机拿每个包和它记着的按下触点比较，
 * 新编号当作按下、少了的编号当作抬起、其余是移动，包里的动作只用来认「1 个点的 UP」= 结束。所以：
 * DOWN 只在没有按下的触点时发；手指增减用 MOVE 表达（集合可以变）；UP 只能带 1 个当前按着的点，
 * 它结束整组手势，手机把其余还按着的点一并抬起。带多个点的 UP 手机只当移动、不会抬起，这里直接拒绝。
 *
 * 状态（按下中的触点快照、按下的键）在生成包时更新；调用方必须按顺序把包发出去，发送失败就关闭回控连接，
 * 不要重发，以免手机收到重复的按下。
 */
internal class UibcEncoder(
    sessionKey: ByteArray,
    private val random: SecureRandom,
    private val events: Set<String>,
    private val vendors: Set<String>,
    private val sourceDeviceType: Int?,
) {
    private class Gesture(val width: Int, val height: Int, val points: List<HiSightInput.Point>)

    private val cipher = AesCtr(sessionKey)
    private var gesture: Gesture? = null
    private val pressedKeys = LinkedHashMap<Int, Long>()

    fun encode(input: HiSightInput): ByteArray = when (input) {
        is HiSightInput.Touch -> touch(input.action, input.points, input.width, input.height)
        is HiSightInput.Key -> key(input.action, input.keyCode, input.metaState)
        is HiSightInput.Text -> text(input.text)
    }

    /** 收尾：取最后一次快照里编号最小的触点，在原位置发 1 点的 UP（手机随之抬起整组），再按按下的逆序抬起所有键。 */
    fun release(): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        gesture?.let { out += touch(HiSightInput.Touch.Action.UP, it.points.subList(0, 1), it.width, it.height) }
        for (code in pressedKeys.keys.toList().asReversed()) {
            out += key(HiSightInput.Key.Action.UP, code, pressedKeys.getValue(code))
        }
        return out
    }

    /** 连接已断：丢掉按下状态，不再生成释放包。 */
    fun clear() {
        gesture = null
        pressedKeys.clear()
    }

    private fun touch(action: HiSightInput.Touch.Action, points: List<HiSightInput.Point>, width: Int, height: Int): ByteArray {
        val event = when (action) {
            HiSightInput.Touch.Action.DOWN -> "left_point_down"
            HiSightInput.Touch.Action.UP -> "left_point_up"
            HiSightInput.Touch.Action.MOVE -> "left_move"
        }
        require(event in events) { "触摸事件未经本次协商允许" }
        require(width in 1..65535 && height in 1..65535) { "视频宽高必须是 1..65535" }
        require(points.size in 1..MAX_POINTS) { "触点数必须是 1..10" }
        for (p in points) {
            require(p.id in 0..31 && p.x in 0 until width && p.y in 0 until height) { "触点编号或坐标超出范围" }
        }
        val sorted = points.sortedBy { it.id }
        val ids = sorted.map { it.id }
        require(ids.toSet().size == ids.size) { "触点编号重复" }
        val current = gesture
        when (action) {
            HiSightInput.Touch.Action.DOWN -> check(current == null) { "还有按下的触点时不能再按下" }
            HiSightInput.Touch.Action.MOVE -> check(current != null) { "移动前必须先按下" }
            HiSightInput.Touch.Action.UP -> {
                require(sorted.size == 1) { "抬起只能带一个触点" }
                check(current != null && current.points.any { it.id == ids[0] }) { "抬起的触点不在当前按下的触点里" }
            }
        }

        val n = sorted.size
        val padding = (4 + 5 * n) % 2
        val body = ByteArray(4 + 5 * n + padding)
        body[0] = when (action) {
            HiSightInput.Touch.Action.DOWN -> 0
            HiSightInput.Touch.Action.UP -> 1
            HiSightInput.Touch.Action.MOVE -> 2
        }
        putBe16(body, 1, 1 + 5 * n + padding)
        body[3] = n.toByte()
        var at = 4
        for (p in sorted) {
            body[at] = p.id.toByte()
            putBe16(body, at + 1, p.x)
            putBe16(body, at + 3, p.y)
            at += 5
        }
        val packet = wrap(CATEGORY_GENERIC, body)
        gesture = if (action == HiSightInput.Touch.Action.UP) {
            null
        } else {
            Gesture(width, height, sorted.map { HiSightInput.Point(it.id, it.x, it.y) })
        }
        return packet
    }

    private fun key(action: HiSightInput.Key.Action, code: Int, meta: Long): ByteArray {
        val event = if (action == HiSightInput.Key.Action.DOWN) "left_key_down" else "left_key_up"
        require(event in events) { "按键未经本次协商允许" }
        require(code in 0..0xFFFF && meta in 0..0xFFFF_FFFFL) { "键码或修饰位超出范围" }
        require(sourceDeviceType == SOURCE_ANDROID_KEYS || (code in BASIC_KEY_CODES && meta == 0L)) {
            "手机没选源设备类型 7，只能发基础键、修饰位为 0"
        }
        if (action == HiSightInput.Key.Action.UP) check(code in pressedKeys) { "按键抬起前必须已经按下" }
        val body = ByteArray(12)
        body[0] = if (action == HiSightInput.Key.Action.DOWN) 3 else 4
        putBe16(body, 1, 9)
        putBe16(body, 4, code)
        for (i in 0 until 4) body[8 + i] = (meta ushr (24 - 8 * i)).toByte()
        val packet = wrap(CATEGORY_GENERIC, body)
        if (action == HiSightInput.Key.Action.DOWN) pressedKeys[code] = meta else pressedKeys.remove(code)
        return packet
    }

    private fun text(text: String): ByteArray {
        require(TEXT_VENDOR in vendors) { "成文输入未经本次协商允许" }
        require('\u0000' !in text) { "成文输入不能含 NUL" }
        val raw = try {
            val encoder = Charsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val buffer: ByteBuffer = encoder.encode(CharBuffer.wrap(text))
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        } catch (e: CharacterCodingException) {
            throw IllegalArgumentException("成文输入含无法编码的字符")
        }
        require(raw.size in 1..MAX_TEXT_BYTES) { "成文输入的 UTF-8 字节数必须是 1..472" }
        val padding = (5 + raw.size) % 2
        val body = ByteArray(5 + raw.size + padding)
        putBe16(body, 1, 2 + raw.size + padding)
        putBe16(body, 3, raw.size)
        System.arraycopy(raw, 0, body, 5, raw.size)
        return wrap(CATEGORY_VENDOR, body)
    }

    private fun wrap(category: Int, body: ByteArray): ByteArray {
        val plain = ByteArray(6 + body.size)
        for (i in 0 until 6) plain[i] = 0xFF.toByte()
        System.arraycopy(body, 0, plain, 6, body.size)
        val sealed = cipher.seal(plain, random)
        val packet = ByteArray(4 + sealed.size)
        packet[1] = category.toByte()
        putBe16(packet, 2, packet.size)
        System.arraycopy(sealed, 0, packet, 4, sealed.size)
        return packet
    }

    private fun putBe16(b: ByteArray, at: Int, value: Int) {
        b[at] = (value ushr 8).toByte()
        b[at + 1] = value.toByte()
    }

    companion object {
        const val TEXT_VENDOR = "input_content"

        /** 手机按安卓键码（含修饰位）解释按键包的源设备类型。 */
        const val SOURCE_ANDROID_KEYS = 7

        /**
         * 手机没选源设备类型 7 时还能发的基础键（安卓键码）：返回 4、通话 5、挂断 6、方向与确认 19～23、音量 24～25、
         * 媒体 85～90（播放暂停、停止、下一首、上一首、快退、快进）、播放 126、暂停 127。
         */
        val BASIC_KEY_CODES: Set<Int> = setOf(4, 5, 6, 19, 20, 21, 22, 23, 24, 25, 85, 86, 87, 88, 89, 90, 126, 127)

        private const val CATEGORY_GENERIC = 0
        private const val CATEGORY_VENDOR = 2
        private const val MAX_POINTS = 10
        private const val MAX_TEXT_BYTES = 472
    }
}
