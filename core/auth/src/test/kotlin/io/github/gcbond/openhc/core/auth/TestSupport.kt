/*
 * 其中的测试用手机角色（TestPhone）部分逻辑移植自 OpenHarmony security_device_auth（deviceauth_lite，
 * OpenHarmony-v4.0-Release），Apache-2.0，原版权归其作者（Copyright (c) 2020-2022 Huawei Device Co., Ltd.）。
 * 本文件有改动：用 Kotlin 重写客户端一侧的 PAKE、身份交换与 STS 流程，只用于测试。
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.gcbond.openhc.core.auth

import io.github.gcbond.openhc.core.auth.crypto.Ed25519Keys
import io.github.gcbond.openhc.core.auth.crypto.Hkdf
import io.github.gcbond.openhc.core.auth.crypto.HmacSha256
import io.github.gcbond.openhc.core.auth.crypto.Modp
import io.github.gcbond.openhc.core.auth.crypto.Sha256
import io.github.gcbond.openhc.core.auth.crypto.X25519Keys
import io.github.gcbond.openhc.core.auth.hichain.KeyAgreementConst
import io.github.gcbond.openhc.core.auth.hichain.decryptPayload
import io.github.gcbond.openhc.core.auth.hichain.draw
import io.github.gcbond.openhc.core.auth.hichain.encryptPayload
import io.github.gcbond.openhc.core.auth.hichain.hexValue
import io.github.gcbond.openhc.core.auth.hichain.intValue
import io.github.gcbond.openhc.core.auth.hichain.objectValue
import io.github.gcbond.openhc.core.auth.hichain.padded16
import io.github.gcbond.openhc.core.auth.hichain.parseJsonObject
import io.github.gcbond.openhc.core.common.identity.AuthServiceIdentity
import io.github.gcbond.openhc.core.common.toHex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.security.SecureRandom

/** 合成的测试身份：全部是一眼能看出来的假值。 */
internal object Synthetic {
    val SERVICE = AuthServiceIdentity("org.example.auth", "EXAMPLE")
    const val CAR_ID = "00112233445566778899AABBCCDDEEFF00112233445566778899AABBCCDDEEFF"
    const val PHONE_ID = "FFEEDDCCBBAA99887766554433221100FFEEDDCCBBAA99887766554433221100"
    const val PIN = "024680"
    const val ENVELOPE_VERSION = "X_0@00000000000000"
}

/**
 * 确定性随机源：SHA-256(seed || BE32(i)) 依次拼接，按取用顺序消耗。
 * 与生成固定向量时给 C 实现打的桩完全相同，所以同一种子下两边取到的字节一样。
 */
internal class SequenceRandom(seed: String) : SecureRandom() {
    private val seedBytes = seed.toByteArray(Charsets.US_ASCII)
    private var block = ByteArray(0)
    private var position = 0
    private var counter = 0
    var drawn = 0
        private set

    @Synchronized
    override fun nextBytes(bytes: ByteArray) {
        for (i in bytes.indices) {
            if (position == block.size) {
                val md = MessageDigest.getInstance("SHA-256")
                md.update(seedBytes)
                md.update(byteArrayOf((counter ushr 24).toByte(), (counter ushr 16).toByte(), (counter ushr 8).toByte(), counter.toByte()))
                block = md.digest()
                counter++
                position = 0
            }
            bytes[i] = block[position++]
        }
        drawn += bytes.size
    }
}

/** C 实现产出的固定向量（src/test/resources/.../accessory-vectors.json）。 */
internal object Vectors {
    val root: JsonObject by lazy {
        val stream = Vectors::class.java.getResourceAsStream("accessory-vectors.json")
            ?: error("缺少测试向量资源")
        Json.parseToJsonElement(stream.use { it.readBytes().toString(Charsets.UTF_8) }).jsonObject
    }

    fun scenario(name: String): JsonObject = root[name]!!.jsonObject

    fun messages(name: String): List<Pair<String, String>> = scenario(name)["messages"]!!.jsonArray.map {
        val o = it.jsonObject
        o["from"]!!.jsonPrimitive.content to o["message"]!!.jsonPrimitive.content
    }

    fun phoneMessages(name: String) = messages(name).filter { it.first == "phone" }.map { it.second }

    fun carMessages(name: String) = messages(name).filter { it.first == "car" }.map { it.second }

    fun string(o: JsonObject, key: String): String = o[key]!!.jsonPrimitive.content

    fun ints(o: JsonObject, key: String): List<Int> = o[key]!!.jsonArray.map { it.jsonPrimitive.content.toInt() }

    fun strings(element: JsonElement?): List<String> = (element as JsonArray).map { it.jsonPrimitive.content }

    val probes: List<JsonObject> get() = root["probes"]!!.jsonArray.map { it.jsonObject }

    val envelope: JsonObject get() = root["envelope"]!!.jsonObject
}

internal fun carConfig(pin: String?, peer: String = Synthetic.PHONE_ID) =
    AccessoryAuthConfig(Synthetic.SERVICE, Synthetic.CAR_ID, peer, pin)

/** 把动作列表折算成原驱动的结果码序列：进度原样，成功 0，失败 1。 */
internal fun List<AccessoryAction>.resultCodes(): List<Int> = mapNotNull {
    when (it) {
        is AccessoryAction.Progress -> it.result
        is AccessoryAction.Succeeded -> 0
        is AccessoryAction.Failed -> 1
        is AccessoryAction.Send -> null
    }
}

internal fun List<AccessoryAction>.sent(): List<String> = filterIsInstance<AccessoryAction.Send>().map { it.message }

/**
 * 测试用手机角色（HC_CENTRE）：照认证库客户端流程实现首次 PIN 绑定（PAKE + 身份交换）和 STS 免码认证。
 * 取随机数的顺序与认证库客户端一致：创建时缺长期身份先取 32 字节；PAKE：收到应答后私有指数 32 → 挑战 16，
 * 交换：nonce 12；STS 开始：X25519 私钥 32 → 挑战 16，收到应答后 nonce 12。
 */
internal class TestPhone(
    private val service: AuthServiceIdentity,
    phoneId: String,
    carId: String,
    pin: String?,
    private val random: SecureRandom,
    val store: Store = Store(),
) {
    /** 手机自己的长期身份种子和车机公钥。 */
    class Store {
        var seed: ByteArray? = null
        var carPublicKey: ByteArray? = null
    }

    private val selfId = phoneId.toByteArray(Charsets.US_ASCII)
    private val peerId = carId.toByteArray(Charsets.US_ASCII)
    private val pin = pin?.toByteArray(Charsets.US_ASCII) ?: ByteArray(0)

    var sessionKey: ByteArray? = null
        private set
    var succeeded = false
        private set
    var failedWith: Int? = null
        private set

    // PAKE / 交换
    private var salt = ByteArray(0)
    private var peerChallenge = ByteArray(0)
    private var selfChallenge = ByteArray(0)
    private var pakeSessionKey = ByteArray(0)
    private var hmacKey = ByteArray(0)
    private var serviceKey = ByteArray(0)

    // STS
    private var stsPrivate = ByteArray(0)
    private var stsPublic = ByteArray(0)
    private var peerStsPublic = ByteArray(0)
    private var stsSessionKey = ByteArray(0)

    init {
        if (store.seed == null) store.seed = random.draw(32)
    }

    private val publicKey get() = Ed25519Keys.publicKey(store.seed!!)

    private fun hex(b: ByteArray) = b.toHex(upperCase = true)

    fun startPake(): String =
        "{\"message\":1,\"payload\":{\"version\":{\"currentVersion\":\"1.0.0\",\"minVersion\":\"1.0.0\"},\"support256mod\":true,\"operationCode\":1}}"

    fun startSts(): String {
        stsPrivate = random.draw(32)
        stsPublic = X25519Keys.publicKey(stsPrivate)
        selfChallenge = random.draw(16)
        return "{\"authForm\":0,\"message\":17,\"payload\":{\"challenge\":\"${hex(selfChallenge)}\",\"epk\":\"${hex(stsPublic)}\"," +
            "\"operationCode\":2,\"version\":{\"currentVersion\":\"1.0.0\",\"minVersion\":\"1.0.0\"}," +
            "\"pkgName\":\"${service.packageName}\",\"serviceType\":\"${hex(service.serviceType.toByteArray())}\"," +
            "\"peerAuthId\":\"${hex(selfId)}\",\"peerUserType\":\"1\",\"keyLength\":\"16\"}}"
    }

    /** 处理车机发来的一条消息，返回要回发的消息（没有则为 null）。 */
    fun onMessage(text: String): String? {
        val root = parseJsonObject(text, "车机消息")
        val payload = root.objectValue("payload")!!
        return when (root.intValue("message")) {
            0x8001 -> onPakeResponse(payload)
            0x8002 -> onPakeConfirm(payload)
            0x8003 -> onExchangeResponse(payload)
            0x8011 -> onStsStartResponse(payload)
            0x8012 -> onStsAckResponse(payload)
            0x8080 -> {
                failedWith = payload.intValue("errorCode")
                null
            }
            else -> error("测试手机收到未知消息")
        }
    }

    private fun onPakeResponse(payload: JsonObject): String {
        salt = payload.hexValue("salt", 16)!!
        peerChallenge = payload.hexValue("challenge", 16)!!
        val peerEpk = payload.hexValue("epk", 384)!!
        val secret = Hkdf.sha256(pin, salt, KeyAgreementConst.SPEKE_BASE_INFO, 32)
        val esk = random.draw(32)
        val generator = Modp.modPow(secret, byteArrayOf(2), 384)
        val selfEpk = Modp.modPow(generator, esk, 384)
        check(Modp.isValidPublicValue(peerEpk, 384))
        val shared = Modp.modPow(peerEpk, esk, 384)
        val okm = Hkdf.sha256(shared, salt, KeyAgreementConst.SPEKE_SESSION_KEY_INFO, 32)
        pakeSessionKey = okm.copyOfRange(0, 16)
        hmacKey = okm.copyOfRange(16, 32).copyOf(32)
        selfChallenge = random.draw(16)
        val kcf = HmacSha256.mac(hmacKey, selfChallenge.padded16() + peerChallenge.padded16())
        return "{\"message\":2,\"payload\":{\"kcfData\":\"${hex(kcf)}\", \"challenge\":\"${hex(selfChallenge)}\", \"epk\":\"${hex(selfEpk)}\"}}"
    }

    private fun onPakeConfirm(payload: JsonObject): String {
        val kcf = payload.hexValue("kcfData", 32)!!
        val expected = HmacSha256.mac(hmacKey, peerChallenge.padded16() + selfChallenge.padded16())
        check(MessageDigest.isEqual(expected, kcf)) { "车机确认值不对" }
        serviceKey = Hkdf.sha256(pakeSessionKey, salt, KeyAgreementConst.RETURN_KEY_INFO, 16)
        val authInfo = "{\"authId\":\"${hex(selfId)}\", \"authPk\":\"${hex(publicKey)}\"}".toByteArray()
        val sign = Ed25519Keys.sign(store.seed!!, Sha256.digest(selfChallenge.padded16() + peerChallenge.padded16() + authInfo))
        val cipher = encryptPayload(pakeSessionKey, authInfo + sign, KeyAgreementConst.EXCHANGE_REQUEST_AAD, random)
        return "{\"message\":3,\"payload\":{\"exAuthInfo\":\"${hex(cipher)}\"}}"
    }

    private fun onExchangeResponse(payload: JsonObject): String? {
        val cipher = payload.hexValue("exAuthInfo", 350)!!
        val plain = decryptPayload(pakeSessionKey, cipher, KeyAgreementConst.EXCHANGE_RESPONSE_AAD, 300)!!
        val info = plain.copyOfRange(0, plain.size - 64)
        val sign = plain.copyOfRange(plain.size - 64, plain.size)
        val obj = parseJsonObject(String(info, Charsets.US_ASCII), "交换")
        val carPk = obj.hexValue("authPk", 64)!!
        check(Ed25519Keys.verify(carPk, Sha256.digest(peerChallenge.padded16() + selfChallenge.padded16() + info), sign))
        store.carPublicKey = carPk
        sessionKey = serviceKey
        succeeded = true
        return null
    }

    private fun onStsStartResponse(payload: JsonObject): String {
        salt = payload.hexValue("salt", 16)!!
        peerStsPublic = payload.hexValue("epk", 32)!!
        peerChallenge = payload.hexValue("challenge", 16)!!
        val authData = payload.hexValue("authData", 256)!!
        val shared = X25519Keys.agree(stsPrivate, peerStsPublic)!!
        stsSessionKey = Hkdf.sha256(shared, salt, KeyAgreementConst.STS_AUTH_INFO, 16)
        val carSign = decryptPayload(stsSessionKey, authData, selfChallenge, 256)!!
        val message = peerStsPublic + peerId + stsPublic + selfId
        check(Ed25519Keys.verify(store.carPublicKey!!, Sha256.digest(message), carSign)) { "车机签名不对" }
        val sign = Ed25519Keys.sign(store.seed!!, Sha256.digest(stsPublic + selfId + peerStsPublic + peerId))
        val cipher = encryptPayload(stsSessionKey, sign, peerChallenge, random)
        return "{\"authForm\":0,\"message\":18,\"payload\":{\"authData\":\"${hex(cipher)}\"}}"
    }

    private fun onStsAckResponse(payload: JsonObject): String? {
        val ret = decryptPayload(stsSessionKey, payload.hexValue("authReturn", 256)!!, selfChallenge, 256)!!
        check(ret.contentEquals(byteArrayOf(0)))
        sessionKey = Hkdf.sha256(stsSessionKey, salt, KeyAgreementConst.RETURN_KEY_INFO, 16)
        succeeded = true
        return null
    }
}

/** 用 JSON 字符串字面量包一层，模拟手机把认证库消息放进外层 `oper_type=3`。 */
internal fun phoneEnvelope(operType: Int, data: String, version: String = "P_0@00000000000000"): String =
    "{\"version\":${JsonPrimitive(version)},\"oper_type\":$operType,\"data\":${JsonPrimitive(data)}}"
