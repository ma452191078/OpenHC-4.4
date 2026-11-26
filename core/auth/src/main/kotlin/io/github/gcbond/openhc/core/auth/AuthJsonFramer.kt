package io.github.gcbond.openhc.core.auth

import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.common.codec.Utf8
import java.io.ByteArrayOutputStream
import java.nio.charset.CharacterCodingException

/**
 * 认证 TCP 的分帧：这条连接上是一个接一个的 UTF-8 JSON 对象，没有长度头也没有换行。
 *
 * 按字节跟踪大括号/方括号深度和字符串状态，深度回到 0 即得到一条完整消息；跨包的半条消息和半个 UTF-8
 * 字符会留到下次。消息之间只允许 JSON 空白；单条超过 [maxMessageBytes] 字节、不是以 `{` 开头或不是合法
 * UTF-8 都抛 [MalformedMessageException]。
 */
internal class AuthJsonFramer(private val maxMessageBytes: Int = MAX_MESSAGE_BYTES) {
    private val current = ByteArrayOutputStream()
    private var started = false
    private var depth = 0
    private var inString = false
    private var escaped = false

    /** 是否留有未完成的消息。 */
    val hasPendingData: Boolean get() = started

    fun feed(bytes: ByteArray, offset: Int, length: Int): List<String> {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) { "范围非法" }
        val messages = ArrayList<String>(1)
        for (i in offset until offset + length) {
            val b = bytes[i].toInt() and 0xFF
            if (!started) {
                when (b) {
                    SPACE, TAB, LF, CR -> continue
                    OPEN_BRACE -> {
                        started = true
                        depth = 0
                        inString = false
                        escaped = false
                    }
                    else -> throw MalformedMessageException("认证消息必须是 JSON 对象")
                }
            }
            current.write(b)
            if (current.size() > maxMessageBytes) throw MalformedMessageException("认证消息超过 $maxMessageBytes 字节")
            if (inString) {
                when {
                    escaped -> escaped = false
                    b == BACKSLASH -> escaped = true
                    b == QUOTE -> inString = false
                }
                continue
            }
            when (b) {
                QUOTE -> inString = true
                OPEN_BRACE, OPEN_BRACKET -> depth++
                CLOSE_BRACE, CLOSE_BRACKET -> {
                    depth--
                    if (depth == 0) {
                        messages += decodeUtf8(current.toByteArray())
                        current.reset()
                        started = false
                    }
                }
            }
        }
        return messages
    }

    private fun decodeUtf8(data: ByteArray): String = try {
        Utf8.decodeStrict(data)
    } catch (e: CharacterCodingException) {
        throw MalformedMessageException("认证消息不是合法的 UTF-8")
    }

    companion object {
        /** 单条认证消息的上限（字节）。正常流程里最长的一条约 1.1 KB。 */
        const val MAX_MESSAGE_BYTES = 10240

        private const val SPACE = 0x20
        private const val TAB = 0x09
        private const val LF = 0x0A
        private const val CR = 0x0D
        private const val QUOTE = 0x22
        private const val BACKSLASH = 0x5C
        private const val OPEN_BRACE = 0x7B
        private const val CLOSE_BRACE = 0x7D
        private const val OPEN_BRACKET = 0x5B
        private const val CLOSE_BRACKET = 0x5D
    }
}
