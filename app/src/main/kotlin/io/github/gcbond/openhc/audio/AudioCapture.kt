package io.github.gcbond.openhc.audio

import io.github.gcbond.openhc.core.dmsdp.audio.AudioEvent
import io.github.gcbond.openhc.core.dmsdp.audio.AudioStreamInfo
import java.io.Closeable

/**
 * 调试抓包：把音频连接上的原始字节、识别结果和状态写到应用私有目录，方便取回分析。
 * 4.4 fork：原 debug 源集里的写文件实现已随变体源集一起删掉，`AudioCaptureFactory` 一律给空实现 [None]。
 *
 * 两个方法都在协议线程上调用，实现里不能阻塞（复制后交给自己的线程写盘）。
 */
internal interface AudioCapture : Closeable {
    fun onEvent(event: AudioEvent)

    fun onRawData(stream: AudioStreamInfo, data: ByteArray, offset: Int, length: Int)

    /** 把还没写完的写完、关闭文件。可以重复调用。 */
    override fun close()

    object None : AudioCapture {
        override fun onEvent(event: AudioEvent) = Unit

        override fun onRawData(stream: AudioStreamInfo, data: ByteArray, offset: Int, length: Int) = Unit

        override fun close() = Unit
    }
}
