package io.github.gcbond.openhc.audio

import android.content.Context

/**
 * 声音抓包的入口：[AudioOutput] 每次连接用它开一个抓包器（[AudioCapture] 的类注释）。
 *
 * 4.4 fork：写文件的抓包实现原先在 debug 源集（往 files/audio-capture/ 抄原始字节），debug、release 两个变体
 * 源集已随单源集构建一起删掉，main 里给发布语义的空实现——不抓包、不写文件。要抓包时再把它挪回来。
 */
internal object AudioCaptureFactory {

    /** 空实现：什么都不抓。 */
    fun create(context: Context, attemptId: Int): AudioCapture = AudioCapture.None
}
