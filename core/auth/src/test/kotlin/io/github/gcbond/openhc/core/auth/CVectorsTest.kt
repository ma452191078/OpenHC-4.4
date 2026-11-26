package io.github.gcbond.openhc.core.auth

import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.common.hexToBytes
import io.github.gcbond.openhc.core.common.toHex
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 与认证库车机角色（C 实现）逐字节对照：两边注入同一随机数序列、收到同样的手机消息，
 * 发出的每一条消息（字段顺序、空白、十六进制大小写）、结果码序列和会话密钥都必须相同。
 * 向量由 C 实现产出，见 accessory-vectors.json。
 */
class CVectorsTest {

    private class Run(val outputs: List<String>, val results: List<Int>, val actions: List<AccessoryAction>)

    private fun replay(auth: AccessoryAuthenticator, inputs: List<String>): Run {
        val all = inputs.flatMap { auth.onMessage(it) }
        return Run(all.sent(), all.resultCodes(), all)
    }

    /** 跑完首次配对向量，返回之后的信任库（重启后的 STS 向量要用）。 */
    private fun pairedStore(): InMemoryTrustStore {
        val store = InMemoryTrustStore()
        val auth = KotlinAccessoryAuthenticator(carConfig(Synthetic.PIN), store, SequenceRandom(seed("pair")))
        replay(auth, Vectors.phoneMessages("pair"))
        return store
    }

    private fun seed(scenario: String) = Vectors.string(Vectors.scenario(scenario), "carSeed")

    @Test
    fun pinPairingMatchesCByteForByte() {
        val store = InMemoryTrustStore()
        val random = SequenceRandom(seed("pair"))
        val auth = KotlinAccessoryAuthenticator(carConfig(Synthetic.PIN), store, random)
        assertEquals(false, auth.peerTrusted)
        val run = replay(auth, Vectors.phoneMessages("pair"))
        assertEquals(Vectors.carMessages("pair"), run.outputs)
        assertEquals(listOf(953, 106, 665), run.outputs.map { it.length })
        assertEquals(Vectors.ints(Vectors.scenario("pair"), "carResults"), run.results)
        val success = assertIs<AccessoryAction.Succeeded>(run.actions.last())
        assertEquals(Vectors.string(Vectors.scenario("pair"), "sessionKey"), success.sessionKey.toHex())
        // 取随机数：长期身份 32 + salt 16 + 挑战 16 + 私有指数 32 + nonce 12
        assertEquals(108, random.drawn)
        assertTrue(auth.isFinished)

        // 配对成功后信任库里有本机身份和手机记录
        val snapshot = store.snapshot()
        assertEquals(1, snapshot.identities.size)
        assertEquals(1, snapshot.peers.size)
        val peer = snapshot.peers.single()
        assertContentEquals(Synthetic.PHONE_ID.toByteArray(), peer.authId)
        assertEquals(TrustedPeer.USER_TYPE_CONTROLLER, peer.userType)
        assertEquals(TrustedPeer.PAIR_TYPE_BIND, peer.pairType)
        assertContentEquals(Synthetic.CAR_ID.toByteArray(), snapshot.identities.single().authId)
    }

    @Test
    fun stsReconnectMatchesCByteForByteAfterRestart() {
        // 模拟进程重启：信任库编码再解码
        val restored = InMemoryTrustStore(TrustStoreCodec.decode(TrustStoreCodec.encode(pairedStore().snapshot())))
        val random = SequenceRandom(seed("sts"))
        val auth = KotlinAccessoryAuthenticator(carConfig(pin = null), restored, random)
        assertEquals(true, auth.peerTrusted)
        val run = replay(auth, Vectors.phoneMessages("sts"))
        assertEquals(Vectors.carMessages("sts"), run.outputs)
        assertEquals(listOf(2, 0), run.results)
        val success = assertIs<AccessoryAction.Succeeded>(run.actions.last())
        assertEquals(Vectors.string(Vectors.scenario("sts"), "sessionKey"), success.sessionKey.toHex())
        // 取随机数：X25519 32 + salt 16 + nonce 12 + 挑战 16 + nonce 12（已有长期身份，不再生成）
        assertEquals(88, random.drawn)
    }

    @Test
    fun wrongPinMatchesC() {
        val store = InMemoryTrustStore()
        val auth = KotlinAccessoryAuthenticator(carConfig(Synthetic.PIN), store, SequenceRandom(seed("wrongPin")))
        val run = replay(auth, Vectors.phoneMessages("wrongPin"))
        assertEquals(Vectors.carMessages("wrongPin"), run.outputs)
        assertEquals("{\"message\":32896, \"payload\":{\"errorCode\":17}}", run.outputs.last())
        assertEquals(listOf(2, 1), run.results)
        val failed = assertIs<AccessoryAction.Failed>(run.actions.last())
        assertEquals(HcError.VERIFY_PROOF_FAILED, assertIs<AuthFailure.Local>(failed.failure).error)
        // 失败的会话不改动存储
        assertEquals(0, store.snapshot().peers.size)
        assertEquals(0, store.snapshot().identities.size)
    }

    @Test
    fun errorPathsMatchC() {
        val paired = pairedStore().snapshot()
        var checked = 0
        for (probe in Vectors.probes) {
            val name = Vectors.string(probe, "name")
            val pin = probe["carPin"].takeIf { it != null && it !is JsonNull }?.jsonPrimitive?.content
            val store = InMemoryTrustStore(if (Vectors.string(probe, "store") == "paired") paired else TrustStoreSnapshot.EMPTY)
            val auth = KotlinAccessoryAuthenticator(carConfig(pin), store, SequenceRandom(Vectors.string(probe, "carSeed")))
            val inputs = Vectors.strings(probe["inputs"])
            val errors = probe["errors"]!!.jsonArray.map { it.jsonObject }
            val inputError = errors.any { Vectors.string(it, "source") == "input" }
            val outputs = ArrayList<String>()
            val actions = ArrayList<AccessoryAction>()
            for (input in inputs) {
                if (inputError) {
                    assertFailsWith<MalformedMessageException>(name) { auth.onMessage(input) }
                    break
                }
                val step = auth.onMessage(input)
                actions += step
                outputs += step.sent()
            }
            assertEquals(Vectors.strings(probe["outputs"]), outputs, name)
            assertEquals(Vectors.ints(probe, "results"), actions.resultCodes(), name)
            val peerError = errors.firstOrNull { Vectors.string(it, "source") == "peer_inform" }
            if (peerError != null) {
                val failure = assertIs<AuthFailure.Peer>(assertIs<AccessoryAction.Failed>(actions.last()).failure, name)
                assertEquals(Vectors.string(peerError, "code").toInt(), failure.errorCode, name)
            }
            checked++
        }
        assertEquals(38, checked)
    }

    @Test
    fun localFailureCodesMatchInformMessage() {
        // 每个本地失败的探针：Failed 里的错误码与发出的 0x8080 通知一致。
        val paired = pairedStore().snapshot()
        for (probe in Vectors.probes) {
            val outputs = Vectors.strings(probe["outputs"])
            val inform = outputs.lastOrNull()?.takeIf { it.startsWith("{\"message\":32896") } ?: continue
            val pin = probe["carPin"].takeIf { it != null && it !is JsonNull }?.jsonPrimitive?.content
            val store = InMemoryTrustStore(if (Vectors.string(probe, "store") == "paired") paired else TrustStoreSnapshot.EMPTY)
            val auth = KotlinAccessoryAuthenticator(carConfig(pin), store, SequenceRandom(Vectors.string(probe, "carSeed")))
            val actions = Vectors.strings(probe["inputs"]).flatMap { auth.onMessage(it) }
            val failure = assertIs<AuthFailure.Local>(assertIs<AccessoryAction.Failed>(actions.last()).failure)
            val code = Regex("\"errorCode\":(\\d+)").find(inform)!!.groupValues[1].toInt()
            assertEquals(code, failure.error.code, Vectors.string(probe, "name"))
        }
    }

    @Test
    fun testPhoneMatchesCPhoneSoRoundTripsAreMeaningful() {
        // 测试用手机角色也逐字节对照 C 手机：同一随机序列、收到同样的车机消息，发出同样的消息。
        val phoneStore = TestPhone.Store()
        val pair = Vectors.scenario("pair")
        val phone = TestPhone(Synthetic.SERVICE, Synthetic.PHONE_ID, Synthetic.CAR_ID, Synthetic.PIN,
            SequenceRandom(Vectors.string(pair, "phoneSeed")), phoneStore)
        val sent = mutableListOf(phone.startPake())
        for (car in Vectors.carMessages("pair")) phone.onMessage(car)?.let { sent += it }
        assertEquals(Vectors.phoneMessages("pair"), sent)
        assertTrue(phone.succeeded)
        assertEquals(Vectors.string(pair, "sessionKey"), phone.sessionKey!!.toHex())

        val sts = Vectors.scenario("sts")
        val phone2 = TestPhone(Synthetic.SERVICE, Synthetic.PHONE_ID, Synthetic.CAR_ID, null,
            SequenceRandom(Vectors.string(sts, "phoneSeed")), phoneStore)
        val sent2 = mutableListOf(phone2.startSts())
        for (car in Vectors.carMessages("sts")) phone2.onMessage(car)?.let { sent2 += it }
        assertEquals(Vectors.phoneMessages("sts"), sent2)
        assertEquals(Vectors.string(sts, "sessionKey"), phone2.sessionKey!!.toHex())
    }

    @Test
    fun vectorsUseSyntheticValuesOnly() {
        val root = Vectors.root
        assertEquals(Synthetic.CAR_ID, Vectors.string(root, "carAuthId"))
        assertEquals(Synthetic.PHONE_ID, Vectors.string(root, "phoneAuthId"))
        assertEquals(Synthetic.PIN, Vectors.string(root, "pin"))
        val service = root["service"]!!.jsonObject
        assertEquals("org.example.auth", Vectors.string(service, "packageName"))
        assertEquals("EXAMPLE", Vectors.string(service, "serviceType"))
        assertNotNull(Vectors.string(Vectors.scenario("pair"), "sessionKey").hexToBytes())
        assertNull(root["realDevice"])
    }
}
