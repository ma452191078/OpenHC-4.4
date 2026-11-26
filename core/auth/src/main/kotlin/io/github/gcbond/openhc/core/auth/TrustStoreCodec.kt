package io.github.gcbond.openhc.core.auth

import io.github.gcbond.openhc.core.common.ByteReader
import io.github.gcbond.openhc.core.common.ByteWriter
import io.github.gcbond.openhc.core.common.MalformedMessageException
import java.security.MessageDigest

/**
 * [TrustStoreSnapshot] 的持久化格式（版本化的字节串）。平台层负责把它放进安全存储（例如用系统密钥加密后落盘）。
 *
 * 版本 1，多字节整数一律大端：
 * ```
 * "OHTS"                     4 字节魔数
 * u16 版本 = 1
 * u16 本机身份条数 n
 *   n × { serviceId 32 | u8 authId 长度 L(1..64) | authId L | 私钥种子 32 | 公钥 32 }
 * u16 对端条数 m
 *   m × { serviceId 32 | u8 authId 长度 L(1..64) | authId L | 公钥 32 | u8 userType | u8 pairType }
 * ```
 * 解码时校验魔数、版本、长度、重复记录，公钥必须能由私钥推出，结尾不能有多余字节。
 * 以后改格式就升版本号，解码按版本分支；不认识的版本直接拒绝，不当成空库。
 */
object TrustStoreCodec {
    const val FORMAT_VERSION = 1
    private val MAGIC = byteArrayOf('O'.code.toByte(), 'H'.code.toByte(), 'T'.code.toByte(), 'S'.code.toByte())
    private const val MAX_RECORDS = 0xFFFF

    fun encode(snapshot: TrustStoreSnapshot): ByteArray {
        require(snapshot.identities.size <= MAX_RECORDS && snapshot.peers.size <= MAX_RECORDS) { "记录过多" }
        val w = ByteWriter(256).bytes(MAGIC).u16Be(FORMAT_VERSION)
        w.u16Be(snapshot.identities.size)
        for (identity in snapshot.identities) {
            val id = identity.authId
            w.bytes(identity.serviceId.toByteArray()).u8(id.size).bytes(id)
                .bytes(identity.privateKey).bytes(identity.publicKey)
        }
        w.u16Be(snapshot.peers.size)
        for (peer in snapshot.peers) {
            val id = peer.authId
            w.bytes(peer.serviceId.toByteArray()).u8(id.size).bytes(id).bytes(peer.publicKey)
                .u8(peer.userType).u8(peer.pairType)
        }
        return w.toByteArray()
    }

    /** 解码；格式不对抛 [MalformedMessageException]（异常信息不含密钥内容）。 */
    @Throws(MalformedMessageException::class)
    fun decode(data: ByteArray): TrustStoreSnapshot {
        val r = ByteReader(data)
        if (!MessageDigest.isEqual(r.bytes(MAGIC.size), MAGIC)) throw MalformedMessageException("信任库魔数不对")
        val version = r.u16Be()
        if (version != FORMAT_VERSION) throw MalformedMessageException("不认识的信任库版本: $version")
        val identities = ArrayList<LocalIdentity>()
        repeat(r.u16Be()) {
            val serviceId = ServiceId.fromBytes(r.bytes(ServiceId.SIZE))
            val authId = r.bytes(authIdLength(r))
            val privateKey = r.bytes(32)
            val publicKey = r.bytes(32)
            val identity = LocalIdentity(serviceId, authId, privateKey)
            if (!MessageDigest.isEqual(identity.publicKey, publicKey)) {
                throw MalformedMessageException("信任库里的本机公钥与私钥不匹配")
            }
            identities.add(identity)
        }
        val peers = ArrayList<TrustedPeer>()
        repeat(r.u16Be()) {
            val serviceId = ServiceId.fromBytes(r.bytes(ServiceId.SIZE))
            val authId = r.bytes(authIdLength(r))
            val publicKey = r.bytes(32)
            val userType = r.u8()
            val pairType = r.u8()
            peers.add(TrustedPeer(serviceId, authId, publicKey, userType, pairType))
        }
        if (r.remaining != 0) throw MalformedMessageException("信任库结尾有多余数据: ${r.remaining} 字节")
        return try {
            TrustStoreSnapshot(identities, peers)
        } catch (e: IllegalArgumentException) {
            throw MalformedMessageException("信任库内容非法: ${e.message}")
        }
    }

    private fun authIdLength(r: ByteReader): Int {
        val length = r.u8()
        if (length !in 1..AccessoryAuthConfig.MAX_AUTH_ID_BYTES) throw MalformedMessageException("authId 长度非法: $length")
        return length
    }
}
