package io.github.gcbond.openhc.tools.desktop

import io.github.gcbond.openhc.core.hisight.AccessUnit
import io.github.gcbond.openhc.core.hisight.AccessUnitSink
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Path

/**
 * 把收到的 H.264 访问单元原样（Annex-B，带起始码）顺序写进文件，并统计帧数、关键帧数、字节数。
 * 在协议线程上被回调；直接写 [AccessUnit] 的缓冲区，不另外复制。
 */
internal class VideoFileSink(private val out: OutputStream) : AccessUnitSink, Closeable {
    var frames = 0L
        private set
    var keyFrames = 0L
        private set
    var bytes = 0L
        private set

    override fun onAccessUnit(unit: AccessUnit) {
        out.write(unit.data, unit.offset, unit.length)
        frames++
        bytes += unit.length
        if (H264.isKeyFrame(unit.data, unit.offset, unit.length)) keyFrames++
    }

    override fun close() {
        out.close()
    }

    companion object {
        /** 新建输出文件（0600）；文件已存在时失败，不覆盖。 */
        @Throws(IOException::class)
        fun create(path: Path): VideoFileSink = VideoFileSink(BufferedOutputStream(PrivateFiles.createNew(path), 1 shl 20))
    }
}

/** Annex-B H.264 的最小解析：只看 NAL 头的类型。 */
internal object H264 {
    private const val NAL_IDR = 5

    /**
     * 这一帧是否关键帧（含 IDR 片）。依次找起始码，看到第一个片（NAL 类型 1～5）就下结论，
     * 不扫完整帧：片之前通常只有很短的 SPS、PPS、SEI、分隔符。
     */
    fun isKeyFrame(data: ByteArray, offset: Int, length: Int): Boolean {
        val end = offset + length
        var i = offset
        while (i + 3 <= end) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                val header = i + 3
                if (header >= end) return false
                val type = data[header].toInt() and 0x1F
                if (type in 1..5) return type == NAL_IDR
                i = header + 1
                continue
            }
            i++
        }
        return false
    }
}
