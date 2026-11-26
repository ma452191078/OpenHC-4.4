package io.github.gcbond.openhc.settings

import android.content.Context
import androidx.core.content.edit
import io.github.gcbond.openhc.platform.media.VideoDecoder

/**
 * 画面相关的调试设置（单独一个 SharedPreferences 文件）。改动在下一次连接时生效。任意线程可读写。
 */
class VideoPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * 调试：按屏幕刷新的节拍上屏（同一次刷新最多放一帧新画面，每帧最多晚一个刷新周期），默认开。
     * 关掉就是出帧立即上屏，用来在真机上对比上屏帧数和流畅感。
     */
    var framePacing: Boolean
        get() = prefs.getBoolean(KEY_FRAME_PACING, true)
        set(value) = prefs.edit { putBoolean(KEY_FRAME_PACING, value) }

    /** 下一次连接的解码器选项。 */
    fun decoderOptions(): VideoDecoder.Options = VideoDecoder.Options(framePacing = framePacing)

    private companion object {
        const val FILE = "openhc-video"
        const val KEY_FRAME_PACING = "debug_frame_pacing"
    }
}
