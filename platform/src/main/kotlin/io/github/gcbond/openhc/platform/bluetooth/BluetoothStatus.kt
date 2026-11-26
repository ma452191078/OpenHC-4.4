package io.github.gcbond.openhc.platform.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context

/** 车机蓝牙的现状：开始无线连接前检查条件用。 */
object BluetoothStatus {

    enum class State {
        /** 没有蓝牙适配器。 */
        UNSUPPORTED,
        OFF,
        ON,
    }

    /** 本机的经典蓝牙适配器；没有蓝牙返回 null。 */
    fun adapter(context: Context): BluetoothAdapter? =
        (context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun state(adapter: BluetoothAdapter?): State = when {
        adapter == null -> State.UNSUPPORTED
        isEnabled(adapter) -> State.ON
        else -> State.OFF
    }

    /** 读开关状态不要运行时权限（安卓 11 及以前要清单里的普通蓝牙权限）；个别系统抛异常时按关着处理。 */
    private fun isEnabled(adapter: BluetoothAdapter): Boolean = try {
        adapter.isEnabled
    } catch (e: RuntimeException) {
        false
    }
}
