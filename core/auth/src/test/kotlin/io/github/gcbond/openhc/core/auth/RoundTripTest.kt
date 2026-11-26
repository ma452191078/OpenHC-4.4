package io.github.gcbond.openhc.core.auth

import io.github.gcbond.openhc.core.common.MalformedMessageException
import java.io.IOException
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Kotlin 车机 ↔ 测试用 Kotlin 手机角色的完整往返（真随机数）。 */
class RoundTripTest {
    private val random = SecureRandom()

    private class Outcome(val car: List<AccessoryAction>, val phone: TestPhone)

    /** 让手机先发起，两边互发直到车机给出结果。 */
    private fun converse(car: AccessoryAuthenticator, phone: TestPhone, first: String): Outcome {
        val carActions = ArrayList<AccessoryAction>()
        var toCar: String? = first
        while (toCar != null && !car.isFinished) {
            val step = car.onMessage(toCar)
            carActions += step
            toCar = null
            for (message in step.sent()) {
                phone.onMessage(message)?.let { toCar = it }
            }
        }
        return Outcome(carActions, phone)
    }

    private fun pair(store: TrustStore, phoneStore: TestPhone.Store = TestPhone.Store(), carPin: String = "135724", phonePin: String = carPin): Outcome {
        val car = KotlinAccessoryAuthenticator(carConfig(carPin), store, random)
        val phone = TestPhone(Synthetic.SERVICE, Synthetic.PHONE_ID, Synthetic.CAR_ID, phonePin, random, phoneStore)
        return converse(car, phone, phone.startPake())
    }

    private fun sts(store: TrustStore, phoneStore: TestPhone.Store): Outcome {
        val car = KotlinAccessoryAuthenticator(carConfig(pin = null), store, random)
        val phone = TestPhone(Synthetic.SERVICE, Synthetic.PHONE_ID, Synthetic.CAR_ID, null, random, phoneStore)
        return converse(car, phone, phone.startSts())
    }

    private fun Outcome.carKey() = assertIs<AccessoryAction.Succeeded>(car.last()).sessionKey

    private fun Outcome.localError() = assertIs<AuthFailure.Local>(assertIs<AccessoryAction.Failed>(car.last()).failure).error

    @Test
    fun pairThenRestartThenStsReconnect() {
        val store = InMemoryTrustStore()
        val phoneStore = TestPhone.Store()
        val paired = pair(store, phoneStore)
        assertEquals(listOf(2, 3, 0), paired.car.resultCodes())
        assertEquals(16, paired.carKey().size)
        assertContentEquals(paired.phone.sessionKey, paired.carKey())

        // 重启：信任库落盘再读回
        val restored = InMemoryTrustStore(TrustStoreCodec.decode(TrustStoreCodec.encode(store.snapshot())))
        val reconnect = sts(restored, phoneStore)
        assertEquals(listOf(2, 0), reconnect.car.resultCodes())
        assertContentEquals(reconnect.phone.sessionKey, reconnect.carKey())
        assertFalse(reconnect.carKey().contentEquals(paired.carKey()), "每次会话密钥不同")
    }

    @Test
    fun wrongPinFailsOnBothSidesWithoutTouchingStore() {
        val store = InMemoryTrustStore()
        val outcome = pair(store, carPin = "000001", phonePin = "000002")
        assertEquals(HcError.VERIFY_PROOF_FAILED, outcome.localError())
        assertEquals(HcError.VERIFY_PROOF_FAILED.code, outcome.phone.failedWith)
        assertNull(outcome.phone.sessionKey)
        assertEquals(0, store.snapshot().peers.size)
        assertEquals(0, store.snapshot().identities.size)
    }

    @Test
    fun repairingReplacesPeerKeyAndOldKeyNoLongerAuthenticates() {
        val store = InMemoryTrustStore()
        val oldPhone = TestPhone.Store()
        pair(store, oldPhone)
        // 手机清除了数据、换了长期身份后重新配对：车机覆盖旧记录，长期身份不变
        val identityBefore = store.snapshot().identities.single().publicKey
        val newPhone = TestPhone.Store()
        val again = pair(store, newPhone)
        assertEquals(listOf(2, 3, 0), again.car.resultCodes())
        assertEquals(1, store.snapshot().peers.size)
        assertContentEquals(identityBefore, store.snapshot().identities.single().publicKey)

        val stale = sts(store, oldPhone.also { it.carPublicKey = newPhone.carPublicKey })
        assertEquals(HcError.VERIFY_PROOF_FAILED, stale.localError())
        val fresh = sts(store, newPhone)
        assertContentEquals(fresh.phone.sessionKey, fresh.carKey())
    }

    @Test
    fun stsRefusedWhenPeerNotTrusted() {
        val car = KotlinAccessoryAuthenticator(carConfig(Synthetic.PIN), InMemoryTrustStore(), random)
        assertFalse(car.peerTrusted)
        val phone = TestPhone(Synthetic.SERVICE, Synthetic.PHONE_ID, Synthetic.CAR_ID, null, random)
        val actions = car.onMessage(phone.startSts())
        assertEquals(listOf("{\"message\":32896, \"payload\":{\"errorCode\":4}}"), actions.sent())
        assertEquals(HcError.SERVICE_CONFIRM_ERROR, assertIs<AuthFailure.Local>(assertIs<AccessoryAction.Failed>(actions.last()).failure).error)
    }

    @Test
    fun pinPairingRefusedWithoutPin() {
        val store = InMemoryTrustStore()
        val phoneStore = TestPhone.Store()
        pair(store, phoneStore)
        val car = KotlinAccessoryAuthenticator(carConfig(pin = null), store, random)
        assertTrue(car.peerTrusted)
        val actions = car.onMessage(TestPhone(Synthetic.SERVICE, Synthetic.PHONE_ID, Synthetic.CAR_ID, "1", random).startPake())
        assertEquals(HcError.SERVICE_CONFIRM_ERROR, assertIs<AuthFailure.Local>(assertIs<AccessoryAction.Failed>(actions.last()).failure).error)
    }

    @Test
    fun storeFailureDuringExchangeIsReportedAndNothingSucceeds() {
        val failing = object : TrustStore {
            override fun findLocalIdentity(serviceId: ServiceId, authId: ByteArray): LocalIdentity? = null
            override fun findPeer(serviceId: ServiceId, authId: ByteArray): TrustedPeer? = null
            override fun commitPairing(identity: LocalIdentity, peer: TrustedPeer) = throw IOException("磁盘满")
        }
        val outcome = pair(failing)
        assertEquals(HcError.SAVE_LTPK_FAILED, outcome.localError())
        assertEquals("{\"message\":32896, \"payload\":{\"errorCode\":31}}", outcome.car.sent().last())
        assertTrue(outcome.car.none { it is AccessoryAction.Succeeded })
    }

    @Test
    fun peerStsWithTamperedStoredKeyFails() {
        val store = InMemoryTrustStore()
        val phoneStore = TestPhone.Store()
        pair(store, phoneStore)
        val snapshot = store.snapshot()
        val peer = snapshot.peers.single()
        val forged = TrustedPeer(peer.serviceId, peer.authId, ByteArray(32) { 7 })
        val tampered = InMemoryTrustStore(TrustStoreSnapshot(snapshot.identities, listOf(forged)))
        assertEquals(HcError.VERIFY_PROOF_FAILED, sts(tampered, phoneStore).localError())
    }

    @Test
    fun differentServiceIdentityDoesNotShareTrust() {
        val store = InMemoryTrustStore()
        val phoneStore = TestPhone.Store()
        pair(store, phoneStore)
        val other = AccessoryAuthConfig(io.github.gcbond.openhc.core.common.identity.AuthServiceIdentity("org.example.other", "EXAMPLE"),
            Synthetic.CAR_ID, Synthetic.PHONE_ID, null)
        assertFalse(KotlinAccessoryAuthenticator(other, store, random).peerTrusted)
    }

    @Test
    fun finishedAuthenticatorIgnoresFurtherInput() {
        val store = InMemoryTrustStore()
        val car = KotlinAccessoryAuthenticator(carConfig(Synthetic.PIN), store, random)
        assertFailsWith<MalformedMessageException> { car.onMessage("not json") }
        assertTrue(car.isFinished)
        assertEquals(emptyList(), car.onMessage("{\"message\":1,\"payload\":{}}"))

        val car2 = KotlinAccessoryAuthenticator(carConfig(Synthetic.PIN), store, random)
        val peerFailure = car2.onMessage("{\"message\":32896,\"payload\":{\"errorCode\":12}}")
        assertEquals(12, assertIs<AuthFailure.Peer>(assertIs<AccessoryAction.Failed>(peerFailure.single()).failure).errorCode)
        assertTrue(car2.isFinished)
        assertEquals(emptyList(), car2.onMessage("{\"message\":1,\"payload\":{}}"))

        val car3 = KotlinAccessoryAuthenticator(carConfig(Synthetic.PIN), store, random)
        val noCode = car3.onMessage("{\"message\":32896}")
        assertEquals(HcError.INPUT_ERROR.code, assertIs<AuthFailure.Peer>(assertIs<AccessoryAction.Failed>(noCode.single()).failure).errorCode)
    }

    @Test
    fun responseMessagesSentToCarFollowLibraryErrorCodes() {
        fun firstError(vararg inputs: String): Pair<List<String>, HcError> {
            val car = KotlinAccessoryAuthenticator(carConfig(Synthetic.PIN), InMemoryTrustStore(), random)
            val actions = inputs.flatMap { car.onMessage(it) }
            return actions.sent() to assertIs<AuthFailure.Local>(assertIs<AccessoryAction.Failed>(actions.last()).failure).error
        }
        val phone = TestPhone(Synthetic.SERVICE, Synthetic.PHONE_ID, Synthetic.CAR_ID, Synthetic.PIN, random)
        val pakeRequest = phone.startPake()
        val version = "\"version\":{\"currentVersion\":\"1.0.0\",\"minVersion\":\"1.0.0\"}"
        val pakeResponse = "{\"message\":32769,\"payload\":{$version,\"challenge\":\"00\",\"salt\":\"00\",\"epk\":\"02\"}}"
        // 初始态收到应答：操作码对不上
        assertEquals(HcError.MESSAGE_INCONSISTENCY, firstError(pakeResponse).second)
        // PIN 绑定开始后收到 PAKE 应答：新建的客户端子对象在初始态
        assertEquals(HcError.PROTOCOL_STATE_ERROR, firstError(pakeRequest, pakeResponse).second)
        // 收到交换应答：原库在这里会崩溃；这里不回包并报失败
        val (sent, error) = firstError(pakeRequest, "{\"message\":32771,\"payload\":{\"exAuthInfo\":\"00\"}}")
        assertEquals(HcError.PAKE_OBJECT_ERROR, error)
        assertEquals(1, sent.size, "只有对 PAKE 请求的应答，没有错误通知")
        // 授权增删等不支持的模块按解析失败处理；没有的类型号按未知消息处理
        assertEquals(HcError.BUILD_OBJECT_FAILED, firstError("{\"message\":35,\"payload\":{}}").second)
        assertEquals(HcError.UNKNOW_MESSAGE, firstError("{\"message\":32833,\"payload\":{}}").second)
        assertEquals(HcError.BUILD_OBJECT_FAILED, firstError("{\"message\":-1,\"payload\":{}}").second)
        assertEquals(HcError.BUILD_OBJECT_FAILED, firstError("{\"message\":1,\"payload\":\"x\"}").second)
    }
}
