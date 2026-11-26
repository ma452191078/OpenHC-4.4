package io.github.gcbond.openhc.core.auth

import io.github.gcbond.openhc.core.auth.crypto.Ed25519Keys
import io.github.gcbond.openhc.core.auth.crypto.Sha256
import io.github.gcbond.openhc.core.common.identity.AuthServiceIdentity
import io.github.gcbond.openhc.core.common.toHex
import java.io.IOException

/**
 * 认证服务身份的摘要：SHA-256(packageName || serviceType)，32 字节。
 * 信任记录按它分区：换了身份资产里的认证服务身份，旧记录自然不再匹配。
 */
class ServiceId private constructor(private val bytes: ByteArray) {
    fun toByteArray(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean = other is ServiceId && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "ServiceId(${bytes.copyOf(4).toHex()}…)"

    companion object {
        const val SIZE = 32

        fun of(service: AuthServiceIdentity): ServiceId = ServiceId(
            Sha256.digest(service.packageName.toByteArray(Charsets.UTF_8), service.serviceType.toByteArray(Charsets.UTF_8)),
        )

        fun fromBytes(bytes: ByteArray): ServiceId {
            require(bytes.size == SIZE) { "ServiceId 必须是 32 字节" }
            return ServiceId(bytes.copyOf())
        }
    }
}

/**
 * 本机长期身份：Ed25519 密钥对，用 32 字节私钥种子表示。按（服务身份, 本机认证身份）区分。
 * 私钥只在内存和平台层的安全存储里，[toString] 不输出。
 */
class LocalIdentity(val serviceId: ServiceId, authId: ByteArray, privateKey: ByteArray) {
    private val id = authId.copyOf()
    private val seed = privateKey.copyOf()

    init {
        require(id.size in 1..AccessoryAuthConfig.MAX_AUTH_ID_BYTES) { "authId 长度必须是 1～64 字节" }
        require(seed.size == Ed25519Keys.KEY_LENGTH) { "私钥必须是 32 字节" }
    }

    /** Ed25519 公钥（由私钥推出）。 */
    val publicKey: ByteArray = Ed25519Keys.publicKey(seed)
        get() = field.copyOf()

    val authId: ByteArray get() = id.copyOf()

    val privateKey: ByteArray get() = seed.copyOf()

    internal fun sign(message: ByteArray): ByteArray = Ed25519Keys.sign(seed, message)

    internal fun matches(serviceId: ServiceId, authId: ByteArray): Boolean =
        this.serviceId == serviceId && id.contentEquals(authId)

    override fun toString(): String = "LocalIdentity(${id.size} 字节身份，私钥不显示)"
}

/**
 * 已信任的对端：配对时交换得到的 Ed25519 长期公钥。按（服务身份, 对端认证身份）区分。
 *
 * [userType]、[pairType] 沿用认证库的取值：车机记下的手机都是控制端（1），经 PIN 绑定（0）。
 */
class TrustedPeer(
    val serviceId: ServiceId,
    authId: ByteArray,
    publicKey: ByteArray,
    val userType: Int = USER_TYPE_CONTROLLER,
    val pairType: Int = PAIR_TYPE_BIND,
) {
    private val id = authId.copyOf()
    private val key = publicKey.copyOf()

    init {
        require(id.size in 1..AccessoryAuthConfig.MAX_AUTH_ID_BYTES) { "authId 长度必须是 1～64 字节" }
        require(key.size == Ed25519Keys.KEY_LENGTH) { "公钥必须是 32 字节" }
        require(userType in 0..255 && pairType in 0..255) { "userType/pairType 越界" }
    }

    val authId: ByteArray get() = id.copyOf()

    val publicKey: ByteArray get() = key.copyOf()

    internal fun matches(serviceId: ServiceId, authId: ByteArray): Boolean =
        this.serviceId == serviceId && id.contentEquals(authId)

    override fun toString(): String = "TrustedPeer(${id.size} 字节身份, userType=$userType, pairType=$pairType)"

    companion object {
        const val USER_TYPE_ACCESSORY = 0
        const val USER_TYPE_CONTROLLER = 1
        const val PAIR_TYPE_BIND = 0
        const val PAIR_TYPE_AUTH = 1
    }
}

/**
 * 长期身份与信任关系的存储接口。core 只定义接口和一个内存实现；落盘（以及加密保护私钥）由平台层实现。
 *
 * 认证只在首次配对成功时写一次（[commitPairing]），失败的会话不改动存储。
 */
interface TrustStore {
    fun findLocalIdentity(serviceId: ServiceId, authId: ByteArray): LocalIdentity?

    fun findPeer(serviceId: ServiceId, authId: ByteArray): TrustedPeer?

    /**
     * 首次配对成功：一次性写入本机长期身份（可能是本次新生成的，也可能已存在）和对端记录，
     * 同一身份的旧记录被覆盖。写不进去就抛 [IOException]，本次配对按失败处理。
     */
    @Throws(IOException::class)
    fun commitPairing(identity: LocalIdentity, peer: TrustedPeer)
}

/** 存储内容的快照，用于持久化（见 [TrustStoreCodec]）。 */
class TrustStoreSnapshot(identities: List<LocalIdentity>, peers: List<TrustedPeer>) {
    val identities: List<LocalIdentity> = identities.toList()
    val peers: List<TrustedPeer> = peers.toList()

    init {
        for (i in this.identities.indices) {
            for (j in 0 until i) {
                require(!this.identities[j].matches(this.identities[i].serviceId, this.identities[i].authId)) {
                    "本机身份重复"
                }
            }
        }
        for (i in this.peers.indices) {
            for (j in 0 until i) {
                require(!this.peers[j].matches(this.peers[i].serviceId, this.peers[i].authId)) { "对端记录重复" }
            }
        }
    }

    override fun toString(): String = "TrustStoreSnapshot(${identities.size} 个本机身份, ${peers.size} 个对端)"

    companion object {
        val EMPTY = TrustStoreSnapshot(emptyList(), emptyList())
    }
}

/** 内存里的 [TrustStore]：测试用，也可以由平台层包一层做落盘。线程安全。 */
class InMemoryTrustStore(initial: TrustStoreSnapshot = TrustStoreSnapshot.EMPTY) : TrustStore {
    private val identities = initial.identities.toMutableList()
    private val peers = initial.peers.toMutableList()

    @Synchronized
    override fun findLocalIdentity(serviceId: ServiceId, authId: ByteArray): LocalIdentity? =
        identities.firstOrNull { it.matches(serviceId, authId) }

    @Synchronized
    override fun findPeer(serviceId: ServiceId, authId: ByteArray): TrustedPeer? =
        peers.firstOrNull { it.matches(serviceId, authId) }

    @Synchronized
    override fun commitPairing(identity: LocalIdentity, peer: TrustedPeer) {
        identities.removeAll { it.matches(identity.serviceId, identity.authId) }
        identities.add(identity)
        peers.removeAll { it.matches(peer.serviceId, peer.authId) }
        peers.add(peer)
    }

    /** 删除一台手机的信任记录（例如用户在车机上「忘记此设备」）。 */
    @Synchronized
    fun removePeer(serviceId: ServiceId, authId: ByteArray): Boolean = peers.removeAll { it.matches(serviceId, authId) }

    @Synchronized
    fun snapshot(): TrustStoreSnapshot = TrustStoreSnapshot(identities, peers)
}
