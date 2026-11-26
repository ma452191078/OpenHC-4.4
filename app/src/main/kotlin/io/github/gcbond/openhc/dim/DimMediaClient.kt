package io.github.gcbond.openhc.dim

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.ecarx.dim_interaction.bussiness.bean.IAdapterDI
import com.ecarx.dim_interaction.bussiness.bean.IMediaDI
import com.ecarx.dim_interaction.bussiness.bean.IMediaDiListener
import com.ecarx.dim_interaction.bussiness.bean.Media

/**
 * 一条要投到仪表盘的媒体信息。[coverPath] 传本机图片的绝对路径（会以 file:// 交给车机服务端读取）。
 */
data class DimMediaInfo(
    val title: String,
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val playing: Boolean = true,
    val coverPath: String? = null,
)

/**
 * 仪表盘多媒体信息投放（ECARX DiServer）。
 *
 * 链路：绑定 com.ecarx.dim_interaction/.server.DiServer（exported，无权限）→ 取 IMediaDI
 * → updateMediaSourceList / updateCurrentSourceType / updateMediaInfo / updateCurrentProgress。
 * 服务端再把媒体帧经 carsignal 发给仪表盘。
 *
 * 注意两个车机侧特性（已实测）：
 * - onBind 只在首个客户端绑定时调用一次，之后所有绑定方复用当时缓存的 binder，可能是 IAdapterDI
 *   也可能是 IMediaDI，所以先试 IAdapterDI.getIMediaDI() 再退回 IMediaDI。
 * - 媒体类型用 0–8 原始值，不是客户端 SDK 的 768+ 映射；本应用统一报「在线」(6)。
 *
 * 非 ECARX 车机上绑定直接失败，只记一条日志，不影响其它功能。所有 binder 调用都在自己的线程上。
 */
class DimMediaClient(context: Context) {
    private val app: Context = context.applicationContext
    private val thread = HandlerThread("OpenHC-Dim")
    private val handler: Handler by lazy {
        thread.start()
        Handler(thread.looper)
    }

    @Volatile
    private var mediaDI: IMediaDI? = null

    /** 还没连上时先记下最新一条，连上后补发。 */
    @Volatile
    private var pending: DimMediaInfo? = null

    private var lastSignature: String? = null
    private var bindRequested = false
    private var listener: IMediaDiListener? = null

    /** 仪表盘（方向盘）切音源回调，在 binder 线程上调用；为 null 时不注册监听。 */
    @Volatile
    var onSwitchSource: ((Int) -> Unit)? = null
        set(value) {
            field = value
            post { registerListener() }
        }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service == null) return
            post { onConnected(service) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mediaDI = null
        }
    }

    /** 绑定仪表盘服务；重复调用无副作用。 */
    fun start() {
        if (bindRequested) return
        val intent = Intent().apply {
            component = ComponentName(SERVICE_PACKAGE, SERVICE_CLASS)
            putExtra(EXTRA_APP_TYPE, APP_TYPE_MEDIA)
        }
        bindRequested = try {
            app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (t: Throwable) {
            Log.i(TAG, "绑定仪表盘服务失败：${t.javaClass.simpleName} ${t.message}")
            false
        }
        if (!bindRequested) Log.i(TAG, "本机没有 ECARX 仪表盘多媒体服务，跳过")
    }

    /** 推送当前媒体信息；内容没变时只更新进度。任意线程可调。 */
    fun update(info: DimMediaInfo) {
        pending = info
        post { push(info) }
    }

    /** 只更新播放进度（毫秒）。任意线程可调。 */
    fun updateProgress(positionMs: Long) {
        post {
            val di = mediaDI ?: return@post
            try {
                di.updateCurrentProgress(positionMs)
            } catch (t: Throwable) {
                Log.w(TAG, "更新进度失败：${t.javaClass.simpleName}")
            }
        }
    }

    private fun post(block: () -> Unit) {
        handler.post(block)
    }

    private fun onConnected(binder: IBinder) {
        val di = resolveMediaDI(binder) ?: run {
            Log.w(TAG, "服务返回的 binder 既不是 IAdapterDI 也不是 IMediaDI")
            return
        }
        mediaDI = di
        try {
            di.updateMediaSourceList(intArrayOf(MEDIA_TYPE_ONLINE))
            di.updateCurrentSourceType(MEDIA_TYPE_ONLINE)
            registerListener()
            pending?.let { push(it) }
            Log.i(TAG, "仪表盘媒体通道已连上")
        } catch (t: Throwable) {
            Log.w(TAG, "仪表盘媒体通道初始化失败：${t.javaClass.simpleName} ${t.message}")
        }
    }

    /** 服务端 binder 可能是整包接口，也可能是媒体接口，两种都试。 */
    private fun resolveMediaDI(binder: IBinder): IMediaDI? {
        try {
            IAdapterDI.Stub.asInterface(binder).getIMediaDI()?.let { return it }
        } catch (t: Throwable) {
            Log.d(TAG, "不是整包接口：${t.javaClass.simpleName}")
        }
        return try {
            IMediaDI.Stub.asInterface(binder)
        } catch (t: Throwable) {
            null
        }
    }

    private fun registerListener() {
        val callback = onSwitchSource ?: return
        val di = mediaDI ?: return
        val stub = object : IMediaDiListener.Stub() {
            override fun switchSource(sourceType: Int) {
                callback(sourceType)
            }
        }
        try {
            di.registerListener(stub)
            listener = stub
        } catch (t: Throwable) {
            Log.w(TAG, "注册仪表盘反控失败：${t.javaClass.simpleName}")
        }
    }

    private fun push(info: DimMediaInfo) {
        val di = mediaDI ?: return
        val signature = "${info.title}\u0000${info.artist}\u0000${info.album}\u0000" +
            "${info.playing}\u0000${info.durationMs}\u0000${info.coverPath}"
        if (signature != lastSignature) {
            lastSignature = signature
            val media = Media().apply {
                baseMediaType = MEDIA_TYPE_ONLINE
                basePlayState = if (info.playing) 1 else 0
                baseCurrentTime = info.positionMs
                baseTotalTime = info.durationMs
                playIcon1 = if (info.playing) 1 else 0
                albumCover2 = info.coverPath?.let { Uri.parse("file://$it") }
                currentSource8 = " "
                albumName9 = info.album
                songTitle10 = info.title
                artistName11 = info.artist
                sourceIcon17 = MEDIA_TYPE_ONLINE
            }
            try {
                // 音源菜单是各播放应用共用的，工厂应用可能会覆盖；换歌时顺手重新声明一次「在线」音源。
                di.updateMediaSourceList(intArrayOf(MEDIA_TYPE_ONLINE))
                di.updateCurrentSourceType(MEDIA_TYPE_ONLINE)
                di.updateMediaInfo(media)
                Log.i(TAG, "仪表盘媒体信息已更新：${info.title} - ${info.artist}")
            } catch (t: Throwable) {
                Log.w(TAG, "更新媒体信息失败：${t.javaClass.simpleName} ${t.message}")
                return
            }
        }
        try {
            di.updateCurrentProgress(info.positionMs)
        } catch (e: RemoteException) {
            Log.w(TAG, "更新进度失败：${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "OpenHC-Dim"
        const val SERVICE_PACKAGE = "com.ecarx.dim_interaction"
        const val SERVICE_CLASS = "com.ecarx.dim_interaction.server.DiServer"
        const val EXTRA_APP_TYPE = "app_type"
        const val APP_TYPE_MEDIA = "com.ecarx.media_center"

        /** 媒体类型：6 = 在线。 */
        const val MEDIA_TYPE_ONLINE = 6
    }
}
