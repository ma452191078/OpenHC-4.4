package io.github.gcbond.openhc.dim

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * 从车机蓝牙 AVRCP 取当前播放信息（歌名/歌手/专辑/时长/进度/播放状态），交给 [DimMediaClient] 显示到仪表盘。
 *
 * 车机系统把 AVRCP 封装在 framework 的 ECARX AdaptAPI 里（com.ecarx.xui.adaptapi.bt.Bt → getAvrcp()），
 * 4.4 没有公开的 BluetoothAvrcpController，所以这里全部用反射：类在 boot classpath 上，第三方应用能直接加载；
 * 回调是接口 IAvrcpCallback，用 java.lang.reflect.Proxy 实现。
 *
 * - 注册回调后主动拉一次当前曲目和播放状态；之后靠事件（换曲、状态、进度）更新。
 * - 位置每 1 秒按本地时钟推进一次（AVRCP 的进度事件不是每秒都有）。
 * - 拿不到歌名（没有蓝牙音乐）时不写仪表，避免把车机自己的显示清掉。
 * - 非 ECARX 车机上加载不到这些类，只记一条日志。
 */
class BtMediaWatcher(
    private val context: Context,
    private val dim: DimMediaClient,
) {
    private val thread = HandlerThread("OpenHC-BtMedia")
    private val handler: Handler by lazy {
        thread.start()
        Handler(thread.looper)
    }

    private var started = false
    private var avrcp: Any? = null
    private var callback: Any? = null

    private var title = ""
    private var artist = ""
    private var album = ""
    private var durationMs = 0L
    private var positionMs = 0L
    private var playing = false

    /** 上次位置更新时的单调时钟；播放中按它把进度往前推。 */
    private var positionAt = 0L
    private var ticking = false

    /** 开始监听；重复调用无副作用。任意线程可调。 */
    fun start() {
        if (started) return
        started = true
        handler.post { connect() }
    }

    private fun connect() {
        try {
            val btClass = Class.forName(BT_CLASS)
            val bt = btClass.getMethod("create", Context::class.java).invoke(null, context)
                ?: return logMissing("create 返回 null")
            val avrcp = btClass.getMethod("getAvrcp").invoke(bt)
                ?: return logMissing("getAvrcp 返回 null")
            val callbackClass = Class.forName(CALLBACK_CLASS)
            val proxy = Proxy.newProxyInstance(
                callbackClass.classLoader,
                arrayOf(callbackClass),
                CallbackHandler(),
            )
            val registered = avrcp.javaClass
                .getMethod("registerAvrcpCallback", callbackClass)
                .invoke(avrcp, proxy) as? Boolean ?: false
            if (!registered) return logMissing("registerAvrcpCallback 返回 false")
            this.avrcp = avrcp
            this.callback = proxy
            Log.i(TAG, "蓝牙 AVRCP 监听已注册")
            requestSnapshot()
        } catch (t: Throwable) {
            logMissing("${t.javaClass.simpleName} ${t.message}")
        }
    }

    private fun logMissing(why: String) {
        Log.i(TAG, "本机没有可用的 ECARX 蓝牙媒体接口，跳过：$why")
    }

    /** 主动问一次当前曲目和播放状态。 */
    private fun requestSnapshot() {
        val a = avrcp ?: return
        invoke(a, "reqAvrcp13GetElementAttributesPlaying")
        invoke(a, "reqAvrcp13GetPlayStatus")
    }

    private fun invoke(target: Any, method: String, vararg args: Any?) {
        try {
            target.javaClass.getMethod(method, *args.map { it?.javaClass ?: Any::class.java }.toTypedArray())
                .invoke(target, *args)
        } catch (t: Throwable) {
            Log.w(TAG, "$method 调用失败：${t.javaClass.simpleName}")
        }
    }

    /** AVRCP 回调（BT 服务的 binder 线程）：先把数据拷出来，再转到自己的线程上处理。 */
    private inner class CallbackHandler : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            val name = method.name
            val copy = args?.copyOf() ?: emptyArray()
            handler.post { onCallback(name, copy) }
            return when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                else -> null
            }
        }
    }

    private fun onCallback(name: String, args: Array<out Any?>) {
        when (name) {
            "onAvrcpServiceReady" -> requestSnapshot()
            "onAvrcpStateChanged" -> requestSnapshot()
            "onAvrcp13EventTrackChanged" -> requestSnapshot()
            "onAvrcp13EventPlaybackStatusChanged" -> {
                (args.getOrNull(0) as? Byte)?.let { applyStatus(it) }
            }
            "onAvrcp13EventPlaybackPosChanged" -> {
                (args.getOrNull(0) as? Long)?.let { applyPosition(it) }
            }
            "retAvrcp13PlayStatus" -> {
                val length = args.getOrNull(0) as? Long ?: 0L
                val position = args.getOrNull(1) as? Long ?: 0L
                val status = args.getOrNull(2) as? Byte ?: 0
                durationMs = length
                applyPosition(position)
                applyStatus(status)
            }
            "retAvrcp13ElementAttributesPlaying" -> {
                val ids = args.getOrNull(0) as? IntArray
                val texts = args.getOrNull(1) as? Array<*>
                if (ids != null && texts != null) applyAttributes(ids, texts)
            }
        }
    }

    /** AVRCP 元素属性：1 标题、2 歌手、3 专辑、7 时长（毫秒）。 */
    private fun applyAttributes(ids: IntArray, texts: Array<*>) {
        var newTitle = title
        var newArtist = artist
        var newAlbum = album
        for (i in ids.indices) {
            val text = (texts.getOrNull(i) as? String)?.trim().orEmpty()
            when (ids[i]) {
                1 -> newTitle = text
                2 -> newArtist = text
                3 -> newAlbum = text
                7 -> text.toLongOrNull()?.let { if (it > 0) durationMs = it }
            }
        }
        title = newTitle
        artist = newArtist
        album = newAlbum
        Log.i(TAG, "蓝牙曲目：$title - $artist（$album），时长 ${durationMs}ms")
        push()
    }

    /** AVRCP 播放状态：0 停止、1 播放、2 暂停（3/4 快进快退按播放算）。 */
    private fun applyStatus(status: Byte) {
        val wasPlaying = playing
        playing = status == 1.toByte() || status == 3.toByte() || status == 4.toByte()
        if (playing && !wasPlaying) {
            positionAt = SystemClock.elapsedRealtime()
            startTicker()
        } else if (!playing) {
            stoppingTicker()
        }
        if (wasPlaying != playing) Log.i(TAG, "蓝牙播放状态：${if (playing) "播放" else "暂停/停止"}（$status）")
        push()
    }

    private fun applyPosition(position: Long) {
        positionMs = position
        positionAt = SystemClock.elapsedRealtime()
        if (playing) startTicker()
        pushProgress()
    }

    /** 播放中每秒把本地进度推给仪表（AVRCP 不会每秒上报进度）。 */
    private fun startTicker() {
        if (ticking) return
        ticking = true
        handler.postDelayed(tick, TICK_MILLIS)
    }

    private fun stoppingTicker() {
        ticking = false
        handler.removeCallbacks(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!ticking || !playing) return
            val elapsed = SystemClock.elapsedRealtime() - positionAt
            val position = (positionMs + elapsed).coerceAtMost(if (durationMs > 0) durationMs else Long.MAX_VALUE)
            dim.updateProgress(position)
            handler.postDelayed(this, TICK_MILLIS)
        }
    }

    private fun push() {
        if (title.isBlank()) return
        if (title == lastPushed && !playing) return
        lastPushed = title
        dim.update(
            DimMediaInfo(
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                positionMs = positionMs,
                playing = playing,
                coverPath = null,
                // 蓝牙音源：类型 2（仪表上显示「蓝牙音乐」）。
                mediaType = 2,
            ),
        )
    }

    private var lastPushed: String? = null

    private fun pushProgress() {
        if (title.isBlank()) return
        dim.updateProgress(positionMs)
    }

    private companion object {
        const val TAG = "OpenHC-BtMedia"
        const val BT_CLASS = "com.ecarx.xui.adaptapi.bt.Bt"
        const val CALLBACK_CLASS = "com.ecarx.xui.adaptapi.bt.avrcp.IAvrcpCallback"
        const val TICK_MILLIS = 1000L
    }
}
