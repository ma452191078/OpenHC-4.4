package io.github.gcbond.openhc.platform.usb

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler

/** 不同安卓版本上 USB 授权与广播的写法差异，集中放在这里。 */
internal object UsbCompat {

    /**
     * USB 授权结果用的 PendingIntent。系统要往里面填设备和结果，所以必须可变：
     * 安卓 12 起要显式写 FLAG_MUTABLE；安卓 14 起可变的 PendingIntent 不能用隐式 Intent，所以指定了本应用包名。
     */
    fun permissionIntent(context: Context, action: String): PendingIntent {
        val intent = Intent(action).setPackage(context.packageName)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    /**
     * 在 [handler] 的线程上接收广播。安卓 13 起动态注册要声明是否接收别的应用的广播：这里只收系统的 USB 插拔广播
     * 和本应用 PendingIntent 发回的授权结果，所以声明为不导出（系统广播不受影响）。
     * 安卓 13 以前没有这个声明，lint 认不出版本判断，所以对低版本分支的提示做了抑制。
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun register(context: Context, receiver: BroadcastReceiver, filter: IntentFilter, handler: Handler) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter, null, handler)
        }
    }

    /** 取广播里的设备。安卓 13 的带类型版本有缺陷，从 14 起才用。 */
    fun deviceExtra(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
}
