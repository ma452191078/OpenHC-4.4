package io.github.gcbond.openhc.core.auth

import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.common.identity.AuthServiceIdentity

/**
 * 车机一侧（HC_ACCESSORY）的配对认证：喂入手机发来的一条原始认证消息（HiChain JSON，
 * 即 HiCar 外层 `oper_type=3` 的 `data`），返回要回发的消息和事件。
 *
 * 同一个实例只服务一次认证：首次连接走 6 位连接码（PAKE + 长期身份交换），
 * 再次连接走 STS 免码认证。成功或失败后实例进入终态，之后喂入的消息一律忽略（返回空列表）。
 *
 * 这里只定义接口；默认实现是 [KotlinAccessoryAuthenticator]，必要时可以换成别的实现（例如 NDK 版）。
 * 实现不做任何 I/O，不开线程，也不持有连接。
 */
interface AccessoryAuthenticator {
    /** 本机是否已有这台手机的长期信任记录（决定能否免码回连）。创建时确定。 */
    val peerTrusted: Boolean

    /** 是否已经得出结果（成功或失败）。 */
    val isFinished: Boolean

    /**
     * 处理一条手机发来的认证消息。
     *
     * @return 依次执行的动作：[AccessoryAction.Send] 要按顺序发给手机；最后可能跟一个
     *   [AccessoryAction.Succeeded] 或 [AccessoryAction.Failed]。
     * @throws MalformedMessageException 消息不是 JSON 对象或 `message` 不是整数（认证随即结束，不回包）。
     */
    @Throws(MalformedMessageException::class)
    fun onMessage(message: String): List<AccessoryAction>
}

/** 创建认证器所需的参数。身份是 ASCII 原文（不做十六进制解码），每个 1～64 字节。 */
class AccessoryAuthConfig(
    /** 认证服务身份（来自身份资产），参与会话密钥推导和信任记录分区。 */
    val service: AuthServiceIdentity,
    /** 本机（车机）认证身份。 */
    val selfAuthId: String,
    /** 对端（手机）认证身份，取自手机握手消息。 */
    val peerAuthId: String,
    /** 本次首次配对用的连接码；为 null 表示不允许 PIN 配对（只接受免码回连）。 */
    pin: String?,
) {
    private val pinBytes: ByteArray? = pin?.toByteArray(Charsets.US_ASCII)

    init {
        requireAuthId(selfAuthId, "selfAuthId")
        requireAuthId(peerAuthId, "peerAuthId")
        require(service.packageName.toByteArray(Charsets.UTF_8).size <= MAX_SERVICE_FIELD_BYTES) { "packageName 过长" }
        require(service.serviceType.toByteArray(Charsets.UTF_8).size <= MAX_SERVICE_FIELD_BYTES) { "serviceType 过长" }
        if (pin != null) {
            require(pin.length in 1..MAX_PIN_BYTES && pin.all { it in ' '..'~' }) { "连接码必须是 1～16 个可打印 ASCII 字符" }
        }
    }

    /** 连接码的字节（ASCII）；调用方拿到的是副本。 */
    internal val pin: ByteArray? get() = pinBytes?.copyOf()

    val hasPin: Boolean get() = pinBytes != null

    override fun toString(): String = "AccessoryAuthConfig(hasPin=$hasPin)"

    companion object {
        const val MAX_AUTH_ID_BYTES = 64
        const val MAX_PIN_BYTES = 16
        const val MAX_SERVICE_FIELD_BYTES = 256

        internal fun requireAuthId(id: String, name: String) {
            require(id.length in 1..MAX_AUTH_ID_BYTES && id.all { it in ' '..'~' }) {
                "$name 必须是 1～64 个可打印 ASCII 字符"
            }
        }
    }
}

/** 按配置创建认证器。实现类自己决定信任库和随机数从哪来。 */
fun interface AccessoryAuthenticatorFactory {
    fun create(config: AccessoryAuthConfig): AccessoryAuthenticator
}

/** 认证器返回的动作，按顺序执行。 */
sealed class AccessoryAction {
    /** 发给手机的一条原始认证消息（HiChain JSON）。 */
    class Send(val message: String) : AccessoryAction() {
        override fun toString(): String = "Send(${message.length} 字符)"
    }

    /** 中间进度：[HcResult.KEY_AGREEMENT_PROCESSING] 或 [HcResult.KEY_AGREEMENT_END]，不代表认证完成。 */
    class Progress(val result: Int) : AccessoryAction() {
        override fun toString(): String = "Progress($result)"
    }

    /** 认证成功。[sessionKey] 是 16 字节会话密钥，调用方用完应清零，不要写日志。 */
    class Succeeded(val sessionKey: ByteArray) : AccessoryAction() {
        override fun toString(): String = "Succeeded(sessionKey=${sessionKey.size} 字节)"
    }

    /** 认证失败，实例进入终态。 */
    class Failed(val failure: AuthFailure) : AccessoryAction() {
        override fun toString(): String = "Failed($failure)"
    }
}

/** 认证失败的原因。 */
sealed class AuthFailure {
    /**
     * 本机按认证库规则判定失败。同一批动作里通常已给手机发了 0x8080 错误通知（错误码即 [error]）；
     * 个别情形不回包（车机收到不该收到的交换应答时；认证库在这里会崩溃）。
     */
    class Local(val error: HcError) : AuthFailure() {
        override fun toString(): String = "Local(${error.name}=${error.code})"
    }

    /** 手机发来 0x8080 错误通知，[errorCode] 原样保留。按协议不回包。 */
    class Peer(val errorCode: Int) : AuthFailure() {
        override fun toString(): String = "Peer($errorCode)"
    }

    /** 手机要求免码回连，但本机没有它的长期信任记录。 */
    object NotTrusted : AuthFailure() {
        override fun toString(): String = "NotTrusted"
    }

    /** 握手声明的连接方式（`usb_connection`）和本会话的配置不一致：有线会话收到无线握手，或反过来。 */
    object UnsupportedConnection : AuthFailure() {
        override fun toString(): String = "UnsupportedConnection"
    }

    /** 认证还没结束，认证 TCP 就断了。[incompleteMessage] 表示断开时还留有半条消息。 */
    class ConnectionClosed(val incompleteMessage: Boolean) : AuthFailure() {
        override fun toString(): String = "ConnectionClosed(incompleteMessage=$incompleteMessage)"
    }
}
