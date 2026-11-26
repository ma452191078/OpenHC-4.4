package io.github.gcbond.openhc.platform.usb

/** 有线会话的状态。 */
sealed class WiredState {

    /** 还没开始。 */
    data object Idle : WiredState()

    /** 等华为手机插上（或插着的设备不是可用的手机）。 */
    data object WaitingForPhone : WiredState()

    /**
     * 插着的华为手机开着 USB 调试，此时它不响应车机：请用户在手机上关掉 USB 调试。
     * 关掉后手机会重新连接，会话自动继续。
     */
    data object UsbDebuggingOn : WiredState()

    /** 等用户在车机弹出的 USB 授权框里点「允许」。 */
    data class WaitingForUsbPermission(val stage: UsbStage) : WiredState()

    /** 正在给手机发 carInfo。 */
    data object SendingCarInfo : WiredState()

    /**
     * carInfo 已发出，等手机切成 USB 网卡（实测通常 1～4 秒）。
     *
     * [slow] 为 true 表示已经等了一阵：第一次连这台车机时手机上会弹连接确认，要用户点「连接」；
     * 手机上没有弹框的话，多半是这次插入手机不理车机，要拔掉数据线、等 5 秒以上再插回。
     */
    data class WaitingForPhoneSwitch(val slow: Boolean) : WiredState()

    /** 手机已切成 USB 网卡，正在抢占接口、建 TUN。 */
    data object OpeningNetwork : WiredState()

    /** 链路已通，正在收发。手机会用 TCP 连 carInfo 里的车机地址和端口。 */
    class Connected(val network: WiredNetwork) : WiredState() {
        override fun toString(): String = "Connected(${network.carAddress.hostAddress}/${network.prefixLength})"
    }

    /**
     * 会话结束（终态）。[replugRequired] 为 true 表示这次插入里已经发过 carInfo：
     * 手机每次插入只响应一次，要再连需要拔掉数据线、等 5 秒以上再插回（实测马上插回手机常常不理车机）。
     */
    data class Ended(val reason: WiredEndReason, val replugRequired: Boolean, val detail: String? = null) : WiredState()
}

/** 两次 USB 授权：手机切成 USB 网卡后是一台新设备，安卓按设备实例记授权，所以要再授权一次。 */
enum class UsbStage {
    /** 普通状态的手机（发 carInfo 用）。 */
    PHONE,

    /** 切成 USB 网卡之后的手机（收发数据用）。 */
    USB_NETWORK,
}

enum class WiredEndReason {
    /** 调用方调了 [WiredSession.stop]。 */
    STOPPED,

    /** 已经有一个有线会话在进行。 */
    BUSY,

    /** 车机不支持 USB 主机模式。 */
    USB_HOST_UNSUPPORTED,

    /** 还没拿到 VPN 授权：先用 [io.github.gcbond.openhc.platform.net.VpnConsent] 请用户同意。 */
    VPN_CONSENT_REQUIRED,

    /** 系统不让用 VPN（设备策略禁止等）。 */
    VPN_UNAVAILABLE,

    /** 建 TUN 失败。 */
    VPN_FAILED,

    /** 等手机插上超时（只有设置了等待上限时才会出现）。 */
    PHONE_NOT_FOUND,

    /** 用户拒绝了 USB 授权。 */
    USB_PERMISSION_DENIED,

    /** 等 USB 授权超时。 */
    USB_PERMISSION_TIMEOUT,

    /** carInfo 控制传输失败。 */
    CAR_INFO_FAILED,

    /** 发了 carInfo，手机迟迟没有切成 USB 网卡。 */
    PHONE_DID_NOT_SWITCH,

    /** 手机切成 USB 网卡后接口不对，或抢占、切换失败。 */
    USB_NETWORK_UNUSABLE,

    /** 手机被拔掉，或者手机自己结束了连接（会话结束后手机会自己退回普通状态）。 */
    PHONE_UNPLUGGED,

    /** USB 收发持续出错。 */
    LINK_LOST,

    /** VPN 授权被撤销（用户断开，或被别的 VPN 应用顶替）。 */
    VPN_REVOKED,

    /** TUN 被系统关掉。 */
    TUNNEL_CLOSED,

    /** 意外错误（程序缺陷），见 [WiredState.Ended.detail]。 */
    ERROR,
}
