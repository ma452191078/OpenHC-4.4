package io.github.gcbond.openhc.platform.net

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 一次会话用的 TUN：绑定 [WiredVpnService]、建 TUN，[close] 时关 TUN 并解除绑定。
 *
 * 关闭顺序：先停掉用 [tunnel] 的线程，再 [close]。
 */
internal class VpnTunnel private constructor(
    private val context: Context,
    private val connection: ServiceConnection,
    private val service: WiredVpnService,
    val tunnel: TunHandle,
    private val onRevoked: () -> Unit,
) : Closeable {

    private var closed = false

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        if (service.revokeListener === onRevoked) service.revokeListener = null
        tunnel.close()
        unbindQuietly(context, connection)
    }

    companion object {
        /**
         * 绑定服务并建 TUN，阻塞到建好、失败、超时或 [cancelled] 返回 true 为止。
         * 绑定回调在主线程上，所以不能在主线程调用。失败抛 [IOException]，消息说明原因。
         */
        fun open(
            context: Context,
            spec: TunSpec,
            timeoutMs: Long,
            cancelled: () -> Boolean,
            onRevoked: () -> Unit,
        ): VpnTunnel {
            check(Looper.myLooper() != Looper.getMainLooper()) { "不能在主线程建 TUN" }
            val app = context.applicationContext ?: context
            val connected = CountDownLatch(1)
            val serviceRef = AtomicReference<WiredVpnService?>()
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    serviceRef.set((binder as? WiredVpnService.LocalBinder)?.service)
                    connected.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    // 服务被系统回收时 TUN 一并失效，搬运线程会读到隧道关闭并收尾。
                }
            }
            val intent = Intent(app, WiredVpnService::class.java).setAction(WiredVpnService.ACTION_LOCAL_BIND)
            val bound = try {
                app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            } catch (e: RuntimeException) {
                unbindQuietly(app, connection)
                throw IOException("绑定 VPN 服务异常: ${e.javaClass.simpleName}", e)
            }
            if (!bound) {
                unbindQuietly(app, connection)
                throw IOException("绑定 VPN 服务失败（清单里没有声明服务？）")
            }
            try {
                val deadline = System.nanoTime() + timeoutMs * 1_000_000
                while (!connected.await(WAIT_SLICE_MS, TimeUnit.MILLISECONDS)) {
                    if (cancelled()) throw IOException("已取消")
                    if (System.nanoTime() > deadline) throw IOException("等 VPN 服务超时（$timeoutMs 毫秒）")
                }
                val service = serviceRef.get() ?: throw IOException("绑定到的不是本模块的 VPN 服务")
                service.revokeListener = onRevoked
                val descriptor = try {
                    service.establishTun(spec)
                } catch (e: RuntimeException) {
                    // 地址或路由被拒（IllegalArgumentException）、正处于其他 VPN 锁定状态（IllegalStateException）、
                    // 设备策略禁止 VPN（SecurityException）等。
                    throw IOException("系统拒绝建 TUN: ${e.javaClass.simpleName}: ${e.message}", e)
                } ?: throw IOException("系统拒绝建 TUN：没有 VPN 授权，或授权已被别的 VPN 应用取代")
                val tun = try {
                    // 4.4 fork：19 上没有 android.system（poll/Os），TunInterface 用不了，换 19 专用实现。
                    openTunHandle(descriptor)
                } catch (e: IOException) {
                    descriptor.close()
                    throw e
                }
                return VpnTunnel(app, connection, service, tun, onRevoked)
            } catch (e: IOException) {
                serviceRef.get()?.let { if (it.revokeListener === onRevoked) it.revokeListener = null }
                unbindQuietly(app, connection)
                throw e
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                serviceRef.get()?.let { if (it.revokeListener === onRevoked) it.revokeListener = null }
                unbindQuietly(app, connection)
                throw IOException("等 VPN 服务时被中断", e)
            }
        }

        private fun unbindQuietly(context: Context, connection: ServiceConnection) {
            try {
                context.unbindService(connection)
            } catch (e: IllegalArgumentException) {
                // 没绑上或已经解绑过。
            }
        }

        /** 4.4 fork：按系统版本挑 TUN 实现。 */
        private fun openTunHandle(descriptor: ParcelFileDescriptor): TunHandle =
            if (Build.VERSION.SDK_INT >= 21) openModernTun(descriptor) else openLegacyTun(descriptor)

        /** 4.4 fork：各实现单独放一个函数，dalvik 校验类时不解析方法体，19 上不会执行到这里。 */
        private fun openModernTun(descriptor: ParcelFileDescriptor): TunHandle = TunInterface(descriptor)

        private fun openLegacyTun(descriptor: ParcelFileDescriptor): TunHandle = TunInterface19(descriptor)

        private const val WAIT_SLICE_MS = 100L
    }
}
