/*
 * 部分逻辑移植自 OpenHarmony security_device_auth（deviceauth_lite，OpenHarmony-v4.0-Release），Apache-2.0，
 * 原版权归其作者（Copyright (c) 2020 Huawei Device Co., Ltd.）。本文件有改动：
 * 用 Kotlin 重写收包、消息分发、状态检查与子对象互斥规则，只保留车机角色；回调改为返回动作列表，密钥存取改为 [TrustStore]。
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
import io.github.gcbond.openhc.core.auth.crypto.Modp
import io.github.gcbond.openhc.core.auth.crypto.X25519Keys
import io.github.gcbond.openhc.core.auth.hichain.ExchangeMessage
import io.github.gcbond.openhc.core.auth.hichain.ExchangeServer
import io.github.gcbond.openhc.core.auth.hichain.HcFailure
import io.github.gcbond.openhc.core.auth.hichain.HiChainBuild
import io.github.gcbond.openhc.core.auth.hichain.HiChainCode
import io.github.gcbond.openhc.core.auth.hichain.HiChainParse
import io.github.gcbond.openhc.core.auth.hichain.PakeEndRequest
import io.github.gcbond.openhc.core.auth.hichain.PakeServer
import io.github.gcbond.openhc.core.auth.hichain.PakeStartRequest
import io.github.gcbond.openhc.core.auth.hichain.StsEndRequest
import io.github.gcbond.openhc.core.auth.hichain.StsServer
import io.github.gcbond.openhc.core.auth.hichain.StsStartRequest
import io.github.gcbond.openhc.core.auth.hichain.draw
import io.github.gcbond.openhc.core.auth.hichain.numberAsInt
import io.github.gcbond.openhc.core.auth.hichain.field
import io.github.gcbond.openhc.core.auth.hichain.parseJsonObject
import io.github.gcbond.openhc.core.auth.hichain.strictInt
import io.github.gcbond.openhc.core.common.MalformedMessageException
import kotlinx.serialization.json.JsonObject
import java.security.SecureRandom

/**
 * [AccessoryAuthenticator] 的纯 Kotlin 实现。
 *
 * 行为与原库的车机角色一致：注入同一随机数序列时，发出的每条消息逐字节相同。
 * 放行规则：手机发起 PIN 绑定（BIND）时本次必须配了连接码；发起免码认证（AUTHENTICATE）时必须已信任该手机；
 * 其余操作一律拒绝。
 *
 * 取随机数的顺序也与之一致（同一序列才能得到同样的消息）：创建时若本机还没有长期身份，先取 32 字节生成
 * Ed25519 种子；PAKE 开始：salt 16 → 挑战 16 → 私有指数 32；身份交换：nonce 12；
 * STS 开始：X25519 私钥 32 → salt 16 → nonce 12 → 挑战 16；STS 结束：nonce 12。
 *
 * 新生成的长期身份只在内存里，首次配对的身份交换成功时与手机公钥一起写入 [TrustStore]；写入失败按
 * [HcError.SAVE_LTPK_FAILED] 处理。失败的会话不改动存储。非线程安全：同一实例只能在一个线程里喂消息。
 */
class KotlinAccessoryAuthenticator(
    config: AccessoryAuthConfig,
    private val trustStore: TrustStore,
    private val random: SecureRandom,
) : AccessoryAuthenticator {

    private val serviceId = ServiceId.of(config.service)
    private val selfId = config.selfAuthId.toByteArray(Charsets.US_ASCII)
    private val peerId = config.peerAuthId.toByteArray(Charsets.US_ASCII)
    private val pin: ByteArray? = config.pin
    private val identity: LocalIdentity
    override val peerTrusted: Boolean

    private var state = State.INIT
    private var lastState = State.INIT
    private var operationCode = HcOperation.NO_OPERATION
    private var pake: PakeServer? = null
    private var sts: StsServer? = null
    private var pakeClientBuilt = false
    private var sessionKey: ByteArray? = null

    override var isFinished: Boolean = false
        private set

    init {
        identity = trustStore.findLocalIdentity(serviceId, selfId)
            ?: LocalIdentity(serviceId, selfId, random.draw(Ed25519Keys.KEY_LENGTH))
        peerTrusted = trustStore.findPeer(serviceId, peerId) != null
    }

    @Throws(MalformedMessageException::class)
    override fun onMessage(message: String): List<AccessoryAction> {
        if (isFinished) return emptyList()
        val root: JsonObject
        val code: Int
        try {
            root = parseJsonObject(message, "认证消息")
            code = root["message"].strictInt() ?: throw MalformedMessageException("认证消息缺少整数 message 字段")
        } catch (e: MalformedMessageException) {
            finish()
            throw e
        }
        if (code == HiChainCode.INFORM_MESSAGE) {
            // 对端的错误通知：认证结束，不回包（否则双方会互发通知）。
            // 识别错误通知时字段名区分大小写。
            val errorCode = (root["payload"] as? JsonObject)?.get("errorCode").numberAsInt() ?: HcError.INPUT_ERROR.code
            finish()
            return listOf(AccessoryAction.Failed(AuthFailure.Peer(errorCode)))
        }
        return receive(root)
    }

    private fun receive(root: JsonObject): List<AccessoryAction> {
        val actions = ArrayList<AccessoryAction>(2)
        var receivedCode = 0
        var reply: String? = null
        var error: HcError? = null
        var silent = false
        try {
            // 认证库按不区分大小写的字段名重新读 message（与入口处的区分大小写检查各自独立）。
            val code = root.field("message").numberAsInt() ?: -1
            val parsed = deserialize(code, root)
            receivedCode = code
            val nav = navigate(code)
            checkMessageSupport(nav, parsed)
            buildObject(nav)
            reply = process(parsed)
        } catch (f: HcFailure) {
            error = f.error
        } catch (f: SilentFailure) {
            error = f.error
            silent = true
        }
        if (error != null) {
            if (!silent) actions += AccessoryAction.Send(HiChainBuild.inform(error.code))
            lastState = state
            state = State.OVER
            finish()
            actions += AccessoryAction.Failed(AuthFailure.Local(error))
            return actions
        }
        actions += AccessoryAction.Send(reply!!)
        setResult(receivedCode, actions)
        return actions
    }

    // ---------------------------------------------------------------- 解析与分发

    private sealed class Parsed {
        class PakeStart(val request: PakeStartRequest) : Parsed()
        class PakeEnd(val request: PakeEndRequest) : Parsed()
        class Exchange(val request: ExchangeMessage) : Parsed()
        class StsStart(val request: StsStartRequest) : Parsed()
        class StsEnd(val request: StsEndRequest) : Parsed()

        /** 应答类消息：只校验格式，后面按原库流程报错。 */
        class Response(val code: Int) : Parsed()
    }

    private fun deserialize(code: Int, root: JsonObject): Parsed {
        // 原库把 -1 当作「没取到 message」；payload 字段缺失也在识别消息类型之前就失败。
        if (code == -1) throw HcFailure(HcError.BUILD_OBJECT_FAILED)
        val payloadElement = root.field("payload") ?: throw HcFailure(HcError.BUILD_OBJECT_FAILED)
        val payload = payloadElement as? JsonObject
        fun <T> parse(block: (JsonObject) -> T): T {
            if (payload == null) throw HcFailure(HcError.BUILD_OBJECT_FAILED)
            return try {
                block(payload)
            } catch (e: MalformedMessageException) {
                throw HcFailure(HcError.BUILD_OBJECT_FAILED)
            }
        }
        return when (code) {
            HiChainCode.PAKE_REQUEST -> Parsed.PakeStart(parse(HiChainParse::pakeRequest))
            HiChainCode.PAKE_CLIENT_CONFIRM -> Parsed.PakeEnd(parse(HiChainParse::pakeClientConfirm))
            HiChainCode.EXCHANGE_REQUEST -> Parsed.Exchange(parse(HiChainParse::exchange))
            HiChainCode.AUTH_START_REQUEST -> Parsed.StsStart(parse(HiChainParse::authStartRequest))
            HiChainCode.AUTH_ACK_REQUEST -> Parsed.StsEnd(parse(HiChainParse::authAckRequest))
            HiChainCode.PAKE_RESPONSE, HiChainCode.PAKE_SERVER_CONFIRM_RESPONSE, HiChainCode.EXCHANGE_RESPONSE,
            HiChainCode.AUTH_START_RESPONSE, HiChainCode.AUTH_ACK_RESPONSE -> {
                parse { HiChainParse.validateResponse(code, it) }
                Parsed.Response(code)
            }
            // 授权增删、安全克隆不支持：按「解析失败」处理。
            HiChainCode.ADD_AUTHINFO_REQUEST, HiChainCode.REMOVE_AUTHINFO_REQUEST,
            HiChainCode.ADD_AUTHINFO_RESPONSE, HiChainCode.REMOVE_AUTHINFO_RESPONSE,
            HiChainCode.SEC_CLONE_START_REQUEST, HiChainCode.SEC_CLONE_ACK_REQUEST,
            -> throw HcFailure(HcError.BUILD_OBJECT_FAILED)
            else -> throw HcFailure(HcError.UNKNOW_MESSAGE)
        }
    }

    private enum class Modular { PAKE, STS, EXCHANGE, OTHER }

    private class Nav(val modular: Modular, val step: Int, val isRequest: Boolean)

    private fun navigate(code: Int): Nav {
        val modular = when (code and 0x7FFF) {
            HiChainCode.PAKE_REQUEST, HiChainCode.PAKE_CLIENT_CONFIRM -> Modular.PAKE
            HiChainCode.EXCHANGE_REQUEST -> Modular.EXCHANGE
            HiChainCode.AUTH_START_REQUEST, HiChainCode.AUTH_ACK_REQUEST -> Modular.STS
            else -> Modular.OTHER
        }
        return Nav(modular, code and 0xF, code and 0x8000 == 0)
    }

    private fun checkMessageSupport(nav: Nav, parsed: Parsed) {
        val stateOk = if (nav.modular == Modular.PAKE || nav.modular == Modular.STS) {
            state == State.INIT || state == State.KEY_AGREEMENT ||
                lastState == State.INIT || lastState == State.KEY_AGREEMENT
        } else {
            state == State.KEY_AGREEMENT || state == State.OPERATION ||
                lastState == State.KEY_AGREEMENT || lastState == State.OPERATION
        }
        if (!stateOk) throw HcFailure(HcError.STATE_ERROR)

        val op = when (parsed) {
            is Parsed.PakeStart -> parsed.request.operationCode.takeIf {
                it == HcOperation.BIND || it == HcOperation.AUTH_KEY_AGREEMENT
            } ?: INVALID_OPERATION
            is Parsed.StsStart -> parsed.request.operationCode.takeIf { it in STS_OPERATIONS } ?: INVALID_OPERATION
            else -> HcOperation.NO_OPERATION
        }
        when (op) {
            INVALID_OPERATION -> throw HcFailure(HcError.OPERATION_CODE_ERROR)
            HcOperation.NO_OPERATION -> if (!isLegal(operationCode, nav.modular)) throw HcFailure(HcError.MESSAGE_INCONSISTENCY)
            else -> {
                if (!confirm(op)) throw HcFailure(HcError.SERVICE_CONFIRM_ERROR)
                operationCode = op
            }
        }
    }

    /** 放行规则（见类说明）。 */
    private fun confirm(op: Int): Boolean = when (op) {
        HcOperation.BIND -> pin != null
        HcOperation.AUTHENTICATE -> peerTrusted
        else -> false
    }

    private fun isLegal(op: Int, modular: Modular): Boolean = when (modular) {
        Modular.PAKE -> op == HcOperation.BIND || op == HcOperation.AUTH_KEY_AGREEMENT
        Modular.EXCHANGE -> op == HcOperation.BIND
        Modular.STS -> op in STS_OPERATIONS
        Modular.OTHER -> false
    }

    /** 按需创建子协议对象；PAKE 与 STS 互斥。 */
    private fun buildObject(nav: Nav) {
        when (nav.modular) {
            Modular.PAKE -> if (nav.isRequest) {
                if (pake == null) {
                    if (sts != null) throw HcFailure(HcError.REPEATED_REFERENCE)
                    pake = PakeServer(pin ?: ByteArray(0), SESSION_KEY_LENGTH, selfId, random)
                }
            } else if (!pakeClientBuilt) {
                if (sts != null) throw HcFailure(HcError.REPEATED_REFERENCE)
                pakeClientBuilt = true
            }
            Modular.STS -> if (nav.isRequest) {
                if (sts == null) {
                    if (pake != null || pakeClientBuilt) throw HcFailure(HcError.REPEATED_REFERENCE)
                    sts = StsServer(selfId, peerId, SESSION_KEY_LENGTH, identity, random) {
                        trustStore.findPeer(serviceId, peerId)?.publicKey
                    }
                }
            } else {
                // 车机一侧没有 STS 客户端；能走到这里时 STS 服务端必然已存在，原库在此报互斥。
                throw HcFailure(HcError.REPEATED_REFERENCE)
            }
            Modular.EXCHANGE, Modular.OTHER -> Unit
        }
    }

    private fun process(parsed: Parsed): String = when (parsed) {
        is Parsed.PakeStart -> pake!!.startResponse(parsed.request)
        is Parsed.PakeEnd -> pake!!.endResponse(parsed.request).also { sessionKey = pake!!.serviceKey.copyOf() }
        is Parsed.Exchange -> exchange(parsed.request)
        is Parsed.StsStart -> sts!!.startResponse(parsed.request)
        is Parsed.StsEnd -> sts!!.endResponse(parsed.request).also { sessionKey = sts!!.serviceKey.copyOf() }
        is Parsed.Response -> when (parsed.code) {
            // 应答类消息会落到新建的客户端子对象上，它还在初始态，原库报协议状态错误。
            HiChainCode.PAKE_RESPONSE, HiChainCode.PAKE_SERVER_CONFIRM_RESPONSE -> throw HcFailure(HcError.PROTOCOL_STATE_ERROR)
            // 原库在这里会空指针崩溃，不回包；这里同样不回包，按失败结束。
            HiChainCode.EXCHANGE_RESPONSE -> throw SilentFailure(HcError.PAKE_OBJECT_ERROR)
            else -> throw HcFailure(HcError.INNER_ERROR)
        }
    }

    private fun exchange(request: ExchangeMessage): String {
        val server = pake ?: throw HcFailure(HcError.PAKE_OBJECT_ERROR)
        val result = ExchangeServer.respond(server, request, identity, random)
        val peer = try {
            TrustedPeer(serviceId, result.peerAuthId, result.peerPublicKey)
        } catch (e: IllegalArgumentException) {
            throw HcFailure(HcError.SAVE_LTPK_FAILED)
        }
        try {
            trustStore.commitPairing(identity, peer)
        } catch (e: Exception) {
            throw HcFailure(HcError.SAVE_LTPK_FAILED)
        }
        return result.response
    }

    private fun setResult(receivedCode: Int, actions: MutableList<AccessoryAction>) {
        val (mapped, target) = when (receivedCode) {
            HiChainCode.PAKE_REQUEST, HiChainCode.AUTH_START_REQUEST -> HcResult.KEY_AGREEMENT_PROCESSING to State.KEY_AGREEMENT
            HiChainCode.PAKE_CLIENT_CONFIRM, HiChainCode.AUTH_ACK_REQUEST -> HcResult.KEY_AGREEMENT_END to State.OPERATION
            HiChainCode.EXCHANGE_REQUEST -> HcResult.END_SUCCESS to State.OVER
            else -> return
        }
        val result = if (target == State.OPERATION &&
            (operationCode == HcOperation.AUTHENTICATE || operationCode == HcOperation.AUTH_KEY_AGREEMENT)
        ) {
            if (state != State.OVER) {
                lastState = state
                state = State.OVER
            }
            HcResult.END_SUCCESS
        } else {
            if (state != target) {
                lastState = state
                state = target
            }
            mapped
        }
        if (result == HcResult.END_SUCCESS) {
            val key = sessionKey
            sessionKey = null
            finish()
            actions += if (key != null && key.size == SESSION_KEY_LENGTH) {
                AccessoryAction.Succeeded(key)
            } else {
                AccessoryAction.Failed(AuthFailure.Local(HcError.INNER_ERROR))
            }
        } else {
            actions += AccessoryAction.Progress(result)
        }
    }

    private fun finish() {
        isFinished = true
        pake?.wipe()
        sts?.wipe()
    }

    /** 原库在这种情形下不回包（会崩溃）。 */
    private class SilentFailure(val error: HcError) : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    private enum class State { INIT, KEY_AGREEMENT, OPERATION, OVER }

    companion object {
        /** 会话密钥长度（字节）。 */
        const val SESSION_KEY_LENGTH = 16

        private const val INVALID_OPERATION = -1
        private val STS_OPERATIONS = setOf(
            HcOperation.AUTHENTICATE, HcOperation.ADD_AUTHINFO, HcOperation.REMOVE_AUTHINFO,
            HcOperation.UNBIND, HcOperation.SEC_CLONE_OP,
        )

        /** 用同一个信任库和随机源创建认证器的工厂。 */
        fun factory(trustStore: TrustStore, random: SecureRandom): AccessoryAuthenticatorFactory =
            AccessoryAuthenticatorFactory { config -> KotlinAccessoryAuthenticator(config, trustStore, random) }

        /**
         * 预热：加载密码相关的类并生成曲线运算的预计算表（首次使用时才做，老车机上可能要几百毫秒）。
         * 同步执行，不开线程；建议平台层在应用启动后挑个后台线程调用一次，避免拖慢手机的首次握手应答。
         */
        fun warmUp() {
            Ed25519Keys.sign(ByteArray(Ed25519Keys.KEY_LENGTH), ByteArray(0))
            X25519Keys.publicKey(ByteArray(X25519Keys.KEY_LENGTH))
            Modp.modPow(byteArrayOf(2), byteArrayOf(2), Modp.LENGTH_3072)
            HiChainBuild.inform(0)
        }
    }
}
