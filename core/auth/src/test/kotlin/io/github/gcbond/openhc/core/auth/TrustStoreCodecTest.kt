package io.github.gcbond.openhc.core.auth

import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.common.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 信任库的版本化持久格式。 */
class TrustStoreCodecTest {
    private companion object {
        /** 魔数 4 + 版本 2 + 本机身份条数 2。 */
        const val HEADER = 8

        /** 一条本机身份（authId 64 字节）：serviceId 32 + 长度 1 + authId 64 + 私钥 32 + 公钥 32。 */
        const val IDENTITY_RECORD = 32 + 1 + 64 + 32 + 32
        const val PUBLIC_KEY_OFFSET = HEADER + 32 + 1 + 64 + 32
    }

    private val service = ServiceId.of(Synthetic.SERVICE)
    private val other = ServiceId.fromBytes(ByteArray(32) { 1 })

    private fun sample() = TrustStoreSnapshot(
        identities = listOf(
            LocalIdentity(service, Synthetic.CAR_ID.toByteArray(), ByteArray(32) { it.toByte() }),
            LocalIdentity(other, "A".toByteArray(), ByteArray(32) { 9 }),
        ),
        peers = listOf(
            TrustedPeer(service, Synthetic.PHONE_ID.toByteArray(), ByteArray(32) { 3 }),
            TrustedPeer(other, "B".toByteArray(), ByteArray(32) { 4 }, TrustedPeer.USER_TYPE_ACCESSORY, TrustedPeer.PAIR_TYPE_AUTH),
        ),
    )

    @Test
    fun roundTrip() {
        val encoded = TrustStoreCodec.encode(sample())
        assertEquals("4f48545300010002", encoded.copyOf(8).toHex(), "魔数 OHTS、版本 1、2 个本机身份")
        val decoded = TrustStoreCodec.decode(encoded)
        assertEquals(2, decoded.identities.size)
        assertEquals(2, decoded.peers.size)
        assertContentEquals(sample().identities[0].privateKey, decoded.identities[0].privateKey)
        assertContentEquals(sample().identities[0].publicKey, decoded.identities[0].publicKey)
        assertEquals(service, decoded.identities[0].serviceId)
        assertEquals(TrustedPeer.USER_TYPE_ACCESSORY, decoded.peers[1].userType)
        assertEquals(TrustedPeer.PAIR_TYPE_AUTH, decoded.peers[1].pairType)
        assertContentEquals(encoded, TrustStoreCodec.encode(decoded))
        assertContentEquals(byteArrayOf(0x4f, 0x48, 0x54, 0x53, 0, 1, 0, 0, 0, 0), TrustStoreCodec.encode(TrustStoreSnapshot.EMPTY))
    }

    @Test
    fun rejectsDamagedOrUnknownData() {
        val good = TrustStoreCodec.encode(sample())
        val cases = mapOf(
            "空" to ByteArray(0),
            "魔数" to good.copyOf().also { it[0] = 'X'.code.toByte() },
            "版本" to good.copyOf().also { it[5] = 2 },
            "截断" to good.copyOf(good.size - 1),
            "尾部多余" to good + byteArrayOf(0),
            "公钥不匹配" to good.copyOf().also { it[PUBLIC_KEY_OFFSET] = (it[PUBLIC_KEY_OFFSET].toInt() xor 1).toByte() },
            "authId 长度为 0" to good.copyOf().also { it[HEADER + 32] = 0 },
        )
        for ((name, data) in cases) {
            assertFailsWith<MalformedMessageException>(name) { TrustStoreCodec.decode(data) }
        }
        val duplicate = TrustStoreCodec.encode(
            TrustStoreSnapshot(listOf(sample().identities[0]), listOf(sample().peers[0])),
        ).let { bytes ->
            // 把对端条数改成 2 并复制同一条记录
            val peerCount = HEADER + IDENTITY_RECORD
            val record = bytes.copyOfRange(peerCount + 2, bytes.size)
            bytes.copyOf(peerCount) + byteArrayOf(0, 2) + record + record
        }
        assertFailsWith<MalformedMessageException> { TrustStoreCodec.decode(duplicate) }
    }

    @Test
    fun inMemoryStoreCommitsAndRemoves() {
        val store = InMemoryTrustStore()
        val identity = LocalIdentity(service, Synthetic.CAR_ID.toByteArray(), ByteArray(32) { 5 })
        val peerId = Synthetic.PHONE_ID.toByteArray()
        store.commitPairing(identity, TrustedPeer(service, peerId, ByteArray(32) { 1 }))
        store.commitPairing(identity, TrustedPeer(service, peerId, ByteArray(32) { 2 }))
        assertEquals(1, store.snapshot().identities.size)
        assertEquals(1, store.snapshot().peers.size)
        assertContentEquals(ByteArray(32) { 2 }, store.findPeer(service, peerId)!!.publicKey)
        assertEquals(null, store.findPeer(other, peerId))
        assertTrue(store.removePeer(service, peerId))
        assertFalse(store.removePeer(service, peerId))
    }

    @Test
    fun keyMaterialIsCopiedAndNotPrinted() {
        val seed = ByteArray(32) { 0x42 }
        val identity = LocalIdentity(service, "A".toByteArray(), seed)
        seed[0] = 0
        assertEquals(0x42, identity.privateKey[0].toInt())
        identity.privateKey[1] = 0
        assertEquals(0x42, identity.privateKey[1].toInt())
        assertFalse(identity.toString().contains("42"))
        assertFailsWith<IllegalArgumentException> { LocalIdentity(service, ByteArray(0), seed) }
        assertFailsWith<IllegalArgumentException> { LocalIdentity(service, ByteArray(65), seed) }
        assertFailsWith<IllegalArgumentException> { TrustedPeer(service, "A".toByteArray(), ByteArray(31)) }
        assertFailsWith<IllegalArgumentException> { ServiceId.fromBytes(ByteArray(31)) }
    }
}
