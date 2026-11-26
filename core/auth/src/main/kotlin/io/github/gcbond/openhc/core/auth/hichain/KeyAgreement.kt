/*
 * 部分逻辑移植自 OpenHarmony security_device_auth（deviceauth_lite，OpenHarmony-v4.0-Release），Apache-2.0，
 * 原版权归其作者（Copyright (c) 2020-2022 Huawei Device Co., Ltd.）。本文件有改动：
 * 用 Kotlin 重写 PAKE、STS 服务端与长期身份交换；密钥存取改为 [LocalIdentity] 与回调。
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
package io.github.gcbond.openhc.core.auth.hichain

import io.github.gcbond.openhc.core.auth.HcError
import io.github.gcbond.openhc.core.auth.LocalIdentity
import io.github.gcbond.openhc.core.auth.crypto.AesGcm
import io.github.gcbond.openhc.core.auth.crypto.Ed25519Keys
import io.github.gcbond.openhc.core.auth.crypto.HmacSha256
import io.github.gcbond.openhc.core.auth.crypto.Hkdf
import io.github.gcbond.openhc.core.auth.crypto.Modp
import io.github.gcbond.openhc.core.auth.crypto.Sha256
import io.github.gcbond.openhc.core.auth.crypto.X25519Keys
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays

/** 认证库内部的失败，带要写进 0x8080 通知的错误码。不带消息文本，避免把认证材料带进日志。 */
internal class HcFailure(val error: HcError) : RuntimeException() {
    override fun fillInStackTrace(): Throwable = this
}

/** 子协议（PAKE / STS）的进度。终态（超时、出错、完成）不能再改，也不能回退。 */
internal enum class ProtocolState { UNINIT, INIT, START_REQUEST, START_RESPONSE, END_REQUEST, TIMEOUT, ERROR, FINISH }

internal class ProtocolBase {
    var state = ProtocolState.INIT
        private set
    var lastState = ProtocolState.INIT
        private set

    fun setState(next: ProtocolState) {
        if (state == ProtocolState.TIMEOUT || state == ProtocolState.ERROR || state == ProtocolState.FINISH) return
        if (next.ordinal < state.ordinal) return
        if (state != next) {
            lastState = state
            state = next
        }
    }

    /** 服务端能否处理「开始」请求：首次，或已应答过一次（对端重发）。 */
    fun canSendStartResponse() = state == ProtocolState.INIT || lastState == ProtocolState.INIT

    /** 服务端能否处理「结束」请求：已应答过开始请求。 */
    fun canSendEndResponse() = state == ProtocolState.START_RESPONSE || lastState == ProtocolState.START_RESPONSE
}

internal object KeyAgreementConst {
    const val CHALLENGE_LENGTH = 16
    const val SALT_LENGTH = 16
    const val HKDF_SECRET_LENGTH = 32
    const val PAKE_SESSION_KEY_LENGTH = 16
    const val PAKE_HMAC_KEY_LENGTH = 32
    const val PAKE_ESK_LENGTH = 32
    const val STS_SESSION_KEY_LENGTH = 16
    const val GCM_NONCE_LENGTH = 12
    const val EXCHANGE_PLAIN_MAX = 300
    const val STS_PLAIN_MAX = 256

    val SPEKE_BASE_INFO = "hichain_speke_base_info".toByteArray(Charsets.US_ASCII)
    val SPEKE_SESSION_KEY_INFO = "hichain_speke_sessionkey_info".toByteArray(Charsets.US_ASCII)
    val RETURN_KEY_INFO = "hichain_return_key".toByteArray(Charsets.US_ASCII)
    val STS_AUTH_INFO = "hichain_auth_info".toByteArray(Charsets.US_ASCII)
    val EXCHANGE_REQUEST_AAD = "hichain_exchange_request".toByteArray(Charsets.US_ASCII)
    val EXCHANGE_RESPONSE_AAD = "hichain_exchange_response".toByteArray(Charsets.US_ASCII)

    /** 3072 位素数。对端请求里的 support256mod 不改变它（与原库一致）。 */
    const val PRIME_LENGTH = Modp.LENGTH_3072
}

internal fun SecureRandom.draw(length: Int): ByteArray = ByteArray(length).also { nextBytes(it) }

/** 挑战值在 HMAC、签名原文里总按 16 字节缓冲区参与运算，不足的部分是零。 */
internal fun ByteArray.padded16(): ByteArray = copyOf(KeyAgreementConst.CHALLENGE_LENGTH)

/** AES-GCM 封装：输出 = 12 字节随机 nonce || 密文 || 16 字节标签。 */
internal fun encryptPayload(key: ByteArray, plain: ByteArray, aad: ByteArray, random: SecureRandom): ByteArray {
    val nonce = random.draw(KeyAgreementConst.GCM_NONCE_LENGTH)
    return nonce + AesGcm.encrypt(key, nonce, aad, plain)
}

/** 解开 [encryptPayload] 的输出；长度不对、明文为空或超过 [maxPlain]、标签不对都返回 null。 */
internal fun decryptPayload(key: ByteArray, cipher: ByteArray, aad: ByteArray, maxPlain: Int): ByteArray? {
    val n = KeyAgreementConst.GCM_NONCE_LENGTH
    if (cipher.size < n || cipher.size - n <= AesGcm.TAG_LENGTH) return null
    val plain = AesGcm.decrypt(key, cipher.copyOfRange(0, n), aad, cipher.copyOfRange(n, cipher.size)) ?: return null
    return if (plain.size > maxPlain) null else plain
}

/**
 * PAKE 服务端（基于口令的 SPEKE，有限域 3072 位）。
 *
 * - 基点 = HKDF(PIN, salt, "hichain_speke_base_info", 32)^2 mod p；本端公开值 epk = 基点^esk mod p。
 * - 共享秘密 = 对端 epk^esk mod p；HKDF(共享秘密, salt, "hichain_speke_sessionkey_info", 32) 的前 16 字节是
 *   会话密钥，后 16 字节接 16 个零字节作 HMAC 密钥（HMAC 补零后与只用这 16 字节等价）。
 * - 确认值 = HMAC(HMAC 密钥, 发送方挑战 || 接收方挑战)；输出密钥 = HKDF(会话密钥, salt, "hichain_return_key", 16)。
 * - 所有模幂结果都是 384 字节定长大端。
 */
internal class PakeServer(
    private val pin: ByteArray,
    private val keyLength: Int,
    val selfId: ByteArray,
    private val random: SecureRandom,
) {
    private val base = ProtocolBase()
    private var salt = ByteArray(0)
    var selfChallenge = ByteArray(0)
        private set
    var peerChallenge = ByteArray(0)
        private set
    private var esk = ByteArray(0)
    var sessionKey = ByteArray(0)
        private set
    private var hmacKey = ByteArray(0)
    var serviceKey = ByteArray(0)
        private set

    fun startResponse(request: PakeStartRequest): String {
        if (!base.canSendStartResponse()) throw HcFailure(HcError.PROTOCOL_STATE_ERROR)
        if (!KeyAgreementVersion.isPeerSupported(request.current, request.minimum)) {
            base.setState(ProtocolState.ERROR)
            throw HcFailure(HcError.VERSION_UNSUPPORT)
        }
        // 只在首次生成 salt、挑战和私有指数；对端重发请求时沿用，回出同一条应答。
        if (base.state == ProtocolState.INIT) {
            salt = random.draw(KeyAgreementConst.SALT_LENGTH)
            selfChallenge = random.draw(KeyAgreementConst.CHALLENGE_LENGTH)
            esk = random.draw(KeyAgreementConst.PAKE_ESK_LENGTH)
        }
        val secret = Hkdf.sha256(pin, salt, KeyAgreementConst.SPEKE_BASE_INFO, KeyAgreementConst.HKDF_SECRET_LENGTH)
        val generator = Modp.modPow(secret, byteArrayOf(2), KeyAgreementConst.PRIME_LENGTH)
        Arrays.fill(secret, 0)
        val epk = Modp.modPow(generator, esk, KeyAgreementConst.PRIME_LENGTH)
        val text = HiChainBuild.pakeResponse(selfChallenge, salt, epk)
        base.setState(ProtocolState.START_RESPONSE)
        return text
    }

    fun endResponse(request: PakeEndRequest): String {
        if (!base.canSendEndResponse()) throw HcFailure(HcError.PROTOCOL_STATE_ERROR)
        peerChallenge = request.challenge
        if (!Modp.isValidPublicValue(request.epk, KeyAgreementConst.PRIME_LENGTH)) {
            sessionKey = ByteArray(0)
            hmacKey = ByteArray(0)
            base.setState(ProtocolState.ERROR)
            throw HcFailure(HcError.GENERATE_SESSION_KEY_FAILED)
        }
        val shared = Modp.modPow(request.epk, esk, KeyAgreementConst.PRIME_LENGTH)
        val okm = Hkdf.sha256(shared, salt, KeyAgreementConst.SPEKE_SESSION_KEY_INFO, KeyAgreementConst.HKDF_SECRET_LENGTH)
        Arrays.fill(shared, 0)
        sessionKey = okm.copyOfRange(0, KeyAgreementConst.PAKE_SESSION_KEY_LENGTH)
        hmacKey = okm.copyOfRange(KeyAgreementConst.PAKE_SESSION_KEY_LENGTH, okm.size)
            .copyOf(KeyAgreementConst.PAKE_HMAC_KEY_LENGTH)
        Arrays.fill(okm, 0)

        val expected = HmacSha256.mac(hmacKey, peerChallenge.padded16() + selfChallenge.padded16())
        if (request.kcf.size != HmacSha256.LENGTH || !MessageDigest.isEqual(expected, request.kcf)) {
            base.setState(ProtocolState.ERROR)
            throw HcFailure(HcError.VERIFY_PROOF_FAILED)
        }
        serviceKey = Hkdf.sha256(sessionKey, salt, KeyAgreementConst.RETURN_KEY_INFO, keyLength)
        val proof = HmacSha256.mac(hmacKey, selfChallenge.padded16() + peerChallenge.padded16())
        base.setState(ProtocolState.FINISH)
        return HiChainBuild.pakeServerConfirm(proof)
    }

    fun wipe() {
        Arrays.fill(esk, 0)
        Arrays.fill(sessionKey, 0)
        Arrays.fill(hmacKey, 0)
        Arrays.fill(serviceKey, 0)
    }
}

/**
 * PAKE 之后的长期身份交换（服务端）。用 PAKE 会话密钥做 AES-GCM：
 * 明文 = `{"authId":"…", "authPk":"…"}` || Ed25519 签名（64 字节），签的是 SHA-256(挑战 || 挑战 || 身份信息原文)，
 * 对端签名的挑战顺序是「对端挑战 || 本端挑战」，本端签名是「本端挑战 || 对端挑战」。
 */
internal object ExchangeServer {
    class Result(val response: String, val peerAuthId: ByteArray, val peerPublicKey: ByteArray)

    fun respond(pake: PakeServer, request: ExchangeMessage, identity: LocalIdentity, random: SecureRandom): Result {
        val key = pake.sessionKey
        val plain = decryptPayload(key, request.cipher, KeyAgreementConst.EXCHANGE_REQUEST_AAD, KeyAgreementConst.EXCHANGE_PLAIN_MAX)
            ?: throw HcFailure(HcError.DECRYPT_FAILED)
        if (plain.size <= Ed25519Keys.SIGNATURE_LENGTH) throw HcFailure(HcError.SIGN_TOO_SHORT)
        val authInfo = plain.copyOfRange(0, plain.size - Ed25519Keys.SIGNATURE_LENGTH)
        val signature = plain.copyOfRange(plain.size - Ed25519Keys.SIGNATURE_LENGTH, plain.size)
        val (peerAuthId, peerPublicKey) = parseAuthInfo(authInfo) ?: throw HcFailure(HcError.DECRYPT_FAILED)
        val peerSignSource = pake.peerChallenge.padded16() + pake.selfChallenge.padded16() + authInfo
        if (!Ed25519Keys.verify(peerPublicKey, Sha256.digest(peerSignSource), signature)) {
            throw HcFailure(HcError.VERIFY_EXCHANGE_FAILED)
        }

        val selfInfo = HiChainBuild.exchangeAuthInfo(pake.selfId, identity.publicKey).toByteArray(Charsets.US_ASCII)
        val selfSign = identity.sign(Sha256.digest(pake.selfChallenge.padded16() + pake.peerChallenge.padded16() + selfInfo))
        val cipher = encryptPayload(key, selfInfo + selfSign, KeyAgreementConst.EXCHANGE_RESPONSE_AAD, random)
        return Result(HiChainBuild.exchangeResponse(cipher), peerAuthId, peerPublicKey)
    }

    /**
     * 解析身份信息：到第一个零字节为止，跳过开头的空白和控制字符后取第一个 JSON 对象（其后的内容忽略，
     * 但仍参与签名校验）。
     */
    private fun parseAuthInfo(authInfo: ByteArray): Pair<ByteArray, ByteArray>? {
        val nul = authInfo.indexOf(0)
        val text = String(authInfo, 0, if (nul < 0) authInfo.size else nul, Charsets.ISO_8859_1)
        var start = 0
        while (start < text.length && text[start].code <= 0x20) start++
        val end = jsonObjectEnd(text, start)
        if (end < 0) return null
        return try {
            HiChainParse.exchangeAuthInfo(parseJsonObject(text.substring(start, end), "交换身份信息"))
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * STS 服务端（已配对设备免码认证）：X25519 临时密钥协商 + 双方用 Ed25519 长期密钥互签。
 *
 * - 会话密钥 = HKDF(X25519 共享秘密, salt, "hichain_auth_info", 16)，salt 由本端随机生成。
 * - 本端签 SHA-256(本端临时公钥 || 本端身份 || 对端临时公钥 || 对端身份)，用会话密钥加密，AAD 是对端挑战。
 * - 对端签名的原文顺序是「对端临时公钥 || 对端身份 || 本端临时公钥 || 本端身份」，AAD 是本端挑战。
 * - 结束应答是用会话密钥加密的 1 字节 0（AAD 是对端挑战）；输出密钥 = HKDF(会话密钥, salt, "hichain_return_key", 16)。
 */
internal class StsServer(
    private val selfId: ByteArray,
    private val peerId: ByteArray,
    private val keyLength: Int,
    private val identity: LocalIdentity,
    private val random: SecureRandom,
    private val peerPublicKey: () -> ByteArray?,
) {
    private val base = ProtocolBase()
    private var selfEphemeralPrivate = ByteArray(0)
    private var selfEphemeralPublic = ByteArray(0)
    private var peerEphemeralPublic = ByteArray(0)
    private var peerChallenge = ByteArray(0)
    private var myChallenge = ByteArray(0)
    private var salt = ByteArray(0)
    private var sessionKey = ByteArray(0)
    var serviceKey = ByteArray(0)
        private set
    private var cachedStartResponse: String? = null
    private var cachedEndResponse: String? = null

    fun startResponse(request: StsStartRequest): String {
        // 对端重发开始请求：原样回上一次的应答，不重新计算。
        cachedStartResponse?.let { return it }
        if (!base.canSendStartResponse()) throw HcFailure(HcError.PROTOCOL_STATE_ERROR)
        if (!KeyAgreementVersion.isPeerSupported(request.current, request.minimum)) {
            base.setState(ProtocolState.ERROR)
            throw HcFailure(HcError.VERSION_UNSUPPORT)
        }
        peerChallenge = request.challenge
        peerEphemeralPublic = request.epk
        if (base.state == ProtocolState.INIT) {
            selfEphemeralPrivate = random.draw(X25519Keys.KEY_LENGTH)
            selfEphemeralPublic = X25519Keys.publicKey(selfEphemeralPrivate)
            salt = random.draw(KeyAgreementConst.SALT_LENGTH)
        }
        val shared = X25519Keys.agree(selfEphemeralPrivate, peerEphemeralPublic) ?: run {
            base.setState(ProtocolState.ERROR)
            throw HcFailure(HcError.COMPUTE_STS_SHARED_SECRET_FAILED)
        }
        sessionKey = Hkdf.sha256(shared, salt, KeyAgreementConst.STS_AUTH_INFO, KeyAgreementConst.STS_SESSION_KEY_LENGTH)
        Arrays.fill(shared, 0)

        val signature = identity.sign(Sha256.digest(selfEphemeralPublic + selfId + peerEphemeralPublic + peerId))
        val authData = encryptPayload(sessionKey, signature, peerChallenge, random)
        if (base.state == ProtocolState.INIT) {
            myChallenge = random.draw(KeyAgreementConst.CHALLENGE_LENGTH)
        }
        val text = HiChainBuild.authStartResponse(authData, myChallenge, selfEphemeralPublic, salt, selfId, PEER_USER_TYPE_ACCESSORY)
        base.setState(ProtocolState.START_RESPONSE)
        cachedStartResponse = text
        return text
    }

    fun endResponse(request: StsEndRequest): String {
        cachedEndResponse?.let { return it }
        if (!base.canSendEndResponse()) throw HcFailure(HcError.PROTOCOL_STATE_ERROR)
        val signature = decryptPayload(sessionKey, request.authData, myChallenge, KeyAgreementConst.STS_PLAIN_MAX) ?: run {
            base.setState(ProtocolState.ERROR)
            throw HcFailure(HcError.DECRYPT_FAILED)
        }
        if (signature.size > Ed25519Keys.SIGNATURE_LENGTH) {
            base.setState(ProtocolState.ERROR)
            throw HcFailure(HcError.MEMCPY_ERROR)
        }
        val message = peerEphemeralPublic + peerId + selfEphemeralPublic + selfId
        val key = peerPublicKey()
        if (key == null || !Ed25519Keys.verify(key, Sha256.digest(message), signature)) {
            base.setState(ProtocolState.ERROR)
            throw HcFailure(HcError.VERIFY_PROOF_FAILED)
        }
        val authReturn = encryptPayload(sessionKey, byteArrayOf(0), peerChallenge, random)
        serviceKey = Hkdf.sha256(sessionKey, salt, KeyAgreementConst.RETURN_KEY_INFO, keyLength)
        val text = HiChainBuild.authAckResponse(authReturn)
        base.setState(ProtocolState.FINISH)
        cachedEndResponse = text
        return text
    }

    fun wipe() {
        Arrays.fill(selfEphemeralPrivate, 0)
        Arrays.fill(sessionKey, 0)
        Arrays.fill(serviceKey, 0)
    }

    private companion object {
        /** 应答里的 peerUserType 是发送方自己的角色：配件（车机）为 0。 */
        const val PEER_USER_TYPE_ACCESSORY = 0
    }
}
