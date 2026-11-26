package io.github.gcbond.openhc.core.hisight.control

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 车机一侧发出的控制消息，逐字节固定。行尾一律 CRLF。注意几处与标准 RTSP 不同的写法：
 * 应答和本机 OPTIONS 用 `Cseq`，其余请求用 `CSeq`；SETUP/PLAY 走 TCP 时 Transport 仍写
 * `RTP/AVP/UDP`；TEARDOWN 的目标为空，所以方法后是两个空格。
 */
internal object RtspTemplates {
    /** ANNOUNCE 的正文：只提供一种加密选择。 */
    const val ANNOUNCE_BODY = "encrypt_description: encrypt_list=aes128ctr;version=1\r\n"

    /** 车机连上控制端口后首发的明文 ANNOUNCE，CSeq 固定为 1。 */
    val ANNOUNCE: String = "ANNOUNCE * RTSP/1.0\r\nCSeq: 1\r\n" +
        "Content-Type: text/parameters\r\nContent-Length: ${ANNOUNCE_BODY.length}\r\n\r\n" + ANNOUNCE_BODY

    const val PUBLIC_METHODS = "hisight1.1, GET_PARAMETER, SET_PARAMETER"

    /** 空正文 200；[withPublic] 时附上 OPTIONS 要的 Public 头。 */
    fun ok(cseq: Int, date: String, withPublic: Boolean = false): String =
        "RTSP/1.0 200 OK\r\nDate: $date\r\nCseq: $cseq\r\n" +
            (if (withPublic) "Public: $PUBLIC_METHODS\r\n" else "") + "\r\n"

    /** GET_PARAMETER 的 200 没有 Date；正文为空时也不带 Content-Type/Content-Length。 */
    fun getParameterOk(cseq: Int, body: String): String =
        "RTSP/1.0 200 OK\r\nCseq: $cseq\r\n" +
            (if (body.isEmpty()) "" else "Content-Type: text/parameters\r\nContent-Length: ${body.length}\r\n") +
            "\r\n" + body

    fun options(cseq: Int, date: String): String =
        "OPTIONS * RTSP/1.0\r\nDate: $date\r\nCseq: $cseq\r\nRequire: hisight1.1\r\n\r\n"

    /** 非 VTP 传输：client_port 固定 0。 */
    fun setup(cseq: Int): String =
        "SETUP * RTSP/1.0\r\nCSeq: $cseq\r\nTransport: RTP/AVP/UDP;unicast;client_port=0\r\n\r\n"

    /** 非 VTP 传输：client_port 固定 -1，不能换成本机端口；不带 Session 头。 */
    fun play(cseq: Int): String =
        "PLAY * RTSP/1.0\r\nCSeq: $cseq\r\nTransport: RTP/AVP/UDP;unicast;client_port=-1\r\n\r\n"

    fun teardown(cseq: Int): String = "TEARDOWN  RTSP/1.0\r\nCSeq: $cseq\r\n\r\n"

    private val WEEKDAYS = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

    /**
     * Date 头的取值：本地时间 `yyyy-MM-dd HH:mm:ss`（24 小时制），后接 ` AM ` 或 ` PM `
     * （以 12 点为界）和英文星期缩写，例如 `2026-01-02 13:04:05 PM Fri`。不是 RFC 日期格式。
     */
    fun date(wallClockMillis: Long, timeZone: TimeZone): String {
        val c = Calendar.getInstance(timeZone, Locale.US)
        c.timeInMillis = wallClockMillis
        val hour = c.get(Calendar.HOUR_OF_DAY)
        return pad(c.get(Calendar.YEAR), 4) + "-" + pad(c.get(Calendar.MONTH) + 1, 2) + "-" +
            pad(c.get(Calendar.DAY_OF_MONTH), 2) + " " + pad(hour, 2) + ":" +
            pad(c.get(Calendar.MINUTE), 2) + ":" + pad(c.get(Calendar.SECOND), 2) +
            (if (hour < 12) " AM " else " PM ") + WEEKDAYS[c.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY]
    }

    private fun pad(value: Int, width: Int): String {
        val s = value.toString()
        val out = StringBuilder(width)
        for (i in s.length until width) out.append('0')
        return out.append(s).toString()
    }
}
