/*
 * 部分逻辑移植自 OpenHarmony security_device_auth（deviceauth_lite，OpenHarmony-v4.0-Release），Apache-2.0，
 * 原版权归其作者（Copyright (c) 2020 Huawei Device Co., Ltd.）。本文件有改动：
 * 用 Kotlin 重写消息的解析与拼装、版本解析与兼容判断；JSON 改用 kotlinx.serialization 解析。
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

import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.common.toHex
import kotlinx.serialization.json.JsonObject
import java.math.BigInteger

/** 消息类型号（`message` 字段）。低 4 位是本模块内的步骤号：1 = 开始，2 = 结束。 */
internal object HiChainCode {
    const val PAKE_REQUEST = 0x0001
    const val PAKE_CLIENT_CONFIRM = 0x0002
    const val EXCHANGE_REQUEST = 0x0003
    const val AUTH_START_REQUEST = 0x0011
    const val AUTH_ACK_REQUEST = 0x0012
    const val ADD_AUTHINFO_REQUEST = 0x0023
    const val REMOVE_AUTHINFO_REQUEST = 0x0033
    const val SEC_CLONE_START_REQUEST = 0x0041
    const val SEC_CLONE_ACK_REQUEST = 0x0042
    const val PAKE_RESPONSE = 0x8001
    const val PAKE_SERVER_CONFIRM_RESPONSE = 0x8002
    const val EXCHANGE_RESPONSE = 0x8003
    const val AUTH_START_RESPONSE = 0x8011
    const val AUTH_ACK_RESPONSE = 0x8012
    const val ADD_AUTHINFO_RESPONSE = 0x8023
    const val REMOVE_AUTHINFO_RESPONSE = 0x8033
    const val SEC_CLONE_START_RESPONSE = 0x8041
    const val SEC_CLONE_ACK_RESPONSE = 0x8042
    const val INFORM_MESSAGE = 0x8080
}

/** 各字段的长度上限（字节）。 */
internal object HiChainLimits {
    const val CHALLENGE = 16
    const val SALT = 16
    const val HMAC = 32
    const val PAKE_EPK = 384
    const val STS_EPK = 32
    const val AUTH_DATA = 256
    const val AUTH_ID = 64
    const val EXCHANGE_CIPHER = 350
    const val EXCHANGE_PUBLIC_KEY = 64
}

/** 协议版本「主.次.修订」，各段按无符号 32 位处理。 */
internal class KeyAgreementVersion(val first: Long, val second: Long, val third: Long) {
    override fun toString(): String = "$first.$second.$third"

    private fun parts() = longArrayOf(first, second, third)

    fun compareTo(other: KeyAgreementVersion): Int {
        val a = parts()
        val b = other.parts()
        for (i in a.indices) {
            if (a[i] != b[i]) return if (a[i] < b[i]) -1 else 1
        }
        return 0
    }

    companion object {
        val CURRENT = KeyAgreementVersion(1, 0, 0)

        /**
         * 按 '.' 切分（连续的 '.' 不产生空段），每段取开头的十进制数（允许前导空白和正负号，
         * 没有数字则为 0，溢出饱和），最多取三段，缺的段为 0。
         */
        fun parse(text: String): KeyAgreementVersion {
            val tokens = text.split('.').filter { it.isNotEmpty() }
            fun part(index: Int) = tokens.getOrNull(index)?.let(::leadingUnsigned) ?: 0L
            return KeyAgreementVersion(part(0), part(1), part(2))
        }

        private val U64_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)

        private fun leadingUnsigned(token: String): Long {
            var i = 0
            while (i < token.length && token[i] in " \t\n\u000B\u000C\r") i++
            var negative = false
            if (i < token.length && (token[i] == '+' || token[i] == '-')) {
                negative = token[i] == '-'
                i++
            }
            var value = BigInteger.ZERO
            var digits = 0
            while (i < token.length && token[i] in '0'..'9') {
                value = value.multiply(BigInteger.TEN).add(BigInteger.valueOf((token[i] - '0').toLong()))
                i++
                digits++
            }
            if (digits == 0) return 0
            val unsigned = when {
                value > U64_MAX -> U64_MAX
                negative -> BigInteger.ONE.shiftLeft(64).subtract(value).and(U64_MAX)
                else -> value
            }
            return unsigned.and(BigInteger.valueOf(0xFFFF_FFFFL)).toLong()
        }

        /** 对端的当前版本不低于本端、且对端要求的最低版本不高于本端，才兼容。 */
        fun isPeerSupported(peerCurrent: KeyAgreementVersion, peerMinimum: KeyAgreementVersion): Boolean =
            peerCurrent.compareTo(CURRENT) >= 0 && peerMinimum.compareTo(CURRENT) <= 0
    }
}

internal class PakeStartRequest(
    val current: KeyAgreementVersion,
    val minimum: KeyAgreementVersion,
    val operationCode: Int,
)

internal class PakeEndRequest(val challenge: ByteArray, val epk: ByteArray, val kcf: ByteArray)

internal class ExchangeMessage(val cipher: ByteArray)

internal class StsStartRequest(
    val current: KeyAgreementVersion,
    val minimum: KeyAgreementVersion,
    val operationCode: Int,
    val challenge: ByteArray,
    val epk: ByteArray,
)

internal class StsEndRequest(val authData: ByteArray)

/**
 * 消息解析。格式不对一律抛 [MalformedMessageException]，由调用方映射成认证库的错误码。
 * 数值字段取到 -1 视同缺失（原库用 -1 表示「没取到」）。
 */
internal object HiChainParse {
    private fun malformed(): Nothing = throw MalformedMessageException("认证消息字段缺失或格式不对")

    private fun JsonObject.requireHex(name: String, max: Int): ByteArray = hexValue(name, max) ?: malformed()

    private fun JsonObject.requireOperationCode(): Int {
        val op = intValue("operationCode") ?: malformed()
        if (op == -1) malformed()
        return op
    }

    private fun JsonObject.requireVersion(): Pair<KeyAgreementVersion, KeyAgreementVersion> {
        val version = objectValue("version") ?: malformed()
        val current = version.stringValue("currentVersion") ?: malformed()
        val minimum = version.stringValue("minVersion") ?: malformed()
        return KeyAgreementVersion.parse(current) to KeyAgreementVersion.parse(minimum)
    }

    private fun JsonObject.requireExchangeCipher(): ByteArray {
        val text = stringValue("exAuthInfo") ?: malformed()
        val cipher = decodeHex(text, HiChainLimits.EXCHANGE_CIPHER) ?: malformed()
        if (cipher.isEmpty()) malformed()
        return cipher
    }

    fun pakeRequest(payload: JsonObject): PakeStartRequest {
        val op = payload.requireOperationCode()
        val (current, minimum) = payload.requireVersion()
        // support256mod：本实现固定用 3072 位素数（与原库一致），这个字段不影响结果。
        return PakeStartRequest(current, minimum, op)
    }

    fun pakeClientConfirm(payload: JsonObject): PakeEndRequest {
        val challenge = payload.requireHex("challenge", HiChainLimits.CHALLENGE)
        val kcf = payload.requireHex("kcfData", HiChainLimits.HMAC)
        val epk = payload.requireHex("epk", HiChainLimits.PAKE_EPK)
        return PakeEndRequest(challenge, epk, kcf)
    }

    fun exchange(payload: JsonObject): ExchangeMessage = ExchangeMessage(payload.requireExchangeCipher())

    fun authStartRequest(payload: JsonObject): StsStartRequest {
        val op = payload.requireOperationCode()
        val challenge = payload.requireHex("challenge", HiChainLimits.CHALLENGE)
        val epk = payload.requireHex("epk", HiChainLimits.STS_EPK)
        // pkgName、peerAuthId、peerUserType、keyLength：车机一侧不用，缺失或格式不对也不拒收。
        val (current, minimum) = payload.requireVersion()
        return StsStartRequest(current, minimum, op, challenge, epk)
    }

    fun authAckRequest(payload: JsonObject): StsEndRequest = StsEndRequest(payload.requireHex("authData", HiChainLimits.AUTH_DATA))

    /** 应答类消息（车机一侧不该收到）：只按格式校验，不取值。 */
    fun validateResponse(code: Int, payload: JsonObject) {
        when (code) {
            HiChainCode.PAKE_RESPONSE -> {
                payload.requireHex("challenge", HiChainLimits.CHALLENGE)
                payload.requireHex("salt", HiChainLimits.SALT)
                payload.requireHex("epk", HiChainLimits.PAKE_EPK)
                payload.requireVersion()
            }
            HiChainCode.PAKE_SERVER_CONFIRM_RESPONSE -> payload.requireHex("kcfData", HiChainLimits.HMAC)
            HiChainCode.AUTH_START_RESPONSE -> {
                payload.requireHex("authData", HiChainLimits.AUTH_DATA)
                payload.requireHex("challenge", HiChainLimits.CHALLENGE)
                payload.requireHex("salt", HiChainLimits.SALT)
                payload.requireHex("epk", HiChainLimits.STS_EPK)
                payload.requireVersion()
            }
            HiChainCode.AUTH_ACK_RESPONSE -> payload.requireHex("authReturn", HiChainLimits.AUTH_DATA)
            HiChainCode.EXCHANGE_RESPONSE -> payload.requireExchangeCipher()
            else -> malformed()
        }
    }

    /** 交换消息解密后的身份信息 `{"authId":"…","authPk":"…"}`。 */
    fun exchangeAuthInfo(payload: JsonObject): Pair<ByteArray, ByteArray> {
        val authId = payload.requireHex("authId", HiChainLimits.AUTH_ID)
        val authPk = payload.requireHex("authPk", HiChainLimits.EXCHANGE_PUBLIC_KEY)
        return authId to authPk
    }
}

/**
 * 车机发出的消息。字段顺序、空白和十六进制大小写（大写）都与原库的格式串一致，不能改成序列化器输出。
 */
internal object HiChainBuild {
    private fun hex(bytes: ByteArray) = bytes.toHex(upperCase = true)

    private val VERSION_FIELDS =
        "\"version\":{\"currentVersion\":\"${KeyAgreementVersion.CURRENT}\",\"minVersion\":\"${KeyAgreementVersion.CURRENT}\"}"

    fun pakeResponse(challenge: ByteArray, salt: ByteArray, epk: ByteArray): String =
        "{\"message\":${HiChainCode.PAKE_RESPONSE},\"payload\":{$VERSION_FIELDS," +
            "\"challenge\":\"${hex(challenge)}\",\"salt\":\"${hex(salt)}\",\"epk\":\"${hex(epk)}\"}}"

    fun pakeServerConfirm(kcf: ByteArray): String =
        "{\"message\":${HiChainCode.PAKE_SERVER_CONFIRM_RESPONSE},\"payload\":{\"kcfData\":\"${hex(kcf)}\"}}"

    fun exchangeResponse(cipher: ByteArray): String =
        "{\"message\":${HiChainCode.EXCHANGE_RESPONSE},\"payload\":{\"exAuthInfo\":\"${hex(cipher)}\"}}"

    /** [peerUserType] 是发送方自己的角色：车机为 0（配件），以字符串形式写出。 */
    fun authStartResponse(
        authData: ByteArray,
        challenge: ByteArray,
        epk: ByteArray,
        salt: ByteArray,
        selfAuthId: ByteArray,
        peerUserType: Int,
    ): String =
        "{\"authForm\":0,\"message\":${HiChainCode.AUTH_START_RESPONSE},\"payload\":{" +
            "\"authData\":\"${hex(authData)}\",\"challenge\":\"${hex(challenge)}\",\"epk\":\"${hex(epk)}\"," +
            "\"salt\":\"${hex(salt)}\",$VERSION_FIELDS,\"peerAuthId\":\"${hex(selfAuthId)}\"," +
            "\"peerUserType\":\"$peerUserType\"}}"

    fun authAckResponse(authReturn: ByteArray): String =
        "{\"authForm\":0,\"message\":${HiChainCode.AUTH_ACK_RESPONSE},\"payload\":{\"authReturn\":\"${hex(authReturn)}\"}}"

    /** 交换消息里加密的身份信息（逗号后有一个空格）。 */
    fun exchangeAuthInfo(authId: ByteArray, publicKey: ByteArray): String =
        "{\"authId\":\"${hex(authId)}\", \"authPk\":\"${hex(publicKey)}\"}"

    /** 0x8080 错误通知（逗号后有一个空格）。错误码按 16 位无符号写出。 */
    fun inform(errorCode: Int): String =
        "{\"message\":${HiChainCode.INFORM_MESSAGE}, \"payload\":{\"errorCode\":${errorCode and 0xFFFF}}}"
}
