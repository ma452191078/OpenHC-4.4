/*
 * 部分内容移植自 OpenHarmony security_device_auth（deviceauth_lite，OpenHarmony-v4.0-Release），Apache-2.0，
 * 原版权归其作者（Copyright (c) 2020-2021 Huawei Device Co., Ltd.）。本文件有改动：
 * 改写为 Kotlin 枚举与常量。
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

/**
 * 认证库的错误码。数值会写进 0x8080 错误通知的 `errorCode` 字段发给对端，两端取值一致。
 */
enum class HcError(val code: Int) {
    OK(0),
    INPUT_ERROR(1),
    INNER_ERROR(2),
    STATE_ERROR(3),
    SERVICE_CONFIRM_ERROR(4),
    UNKNOW_MESSAGE(5),
    NO_MESSAGE_TO_SEND(6),
    REPEATED_REFERENCE(7),
    NEED_DEPEND(8),
    BUILD_OBJECT_FAILED(9),
    BUILD_SEND_DATA_FAILED(10),
    MALLOC_FAILED(11),
    VERSION_UNSUPPORT(12),
    LARGE_PRIME_NUMBER_LEN_UNSUPPORT(13),
    CAL_BIGNUM_EXP_FAILED(14),
    INTERNAL_ERROR(15),
    IMPORT_AUTH_DATA_FAILED(16),
    VERIFY_PROOF_FAILED(17),
    GENERATE_PROOF_FAILED(18),
    GENERATE_SESSION_KEY_FAILED(19),
    ST_KEY_PAIR_FAILED(20),
    COMPUTE_STS_SHARED_SECRET_FAILED(21),
    COMPUTE_HKDF_FAILED(22),
    PAKE_SESSION_KEY_ERROR(23),
    PAKE_OBJECT_ERROR(24),
    STS_OBJECT_ERROR(25),
    DECRYPT_FAILED(26),
    ENCRYPT_FAILED(27),
    SIGN_TOO_SHORT(28),
    VERIFY_EXCHANGE_FAILED(29),
    SIGN_EXCHANGE_FAILED(30),
    SAVE_LTPK_FAILED(31),
    DELETE_LTPK_FAILED(32),
    GET_LTPK_FAILED(33),
    GEN_ALIAS_FAILED(34),
    GEN_SERVICE_ID_FAILED(35),
    GEN_RANDOM_FAILED(36),
    UNSUPPORT(37),
    MESSAGE_INCONSISTENCY(38),
    OPERATION_CODE_ERROR(39),
    MEMCPY_ERROR(40),
    PROTOCOL_STATE_ERROR(41),
    PROTOCOL_SET_STATE_ERROR(42),
    PROTOCOL_TIMEOUT_ERROR(43),
    PROTOCOL_SHARED_SECRET_FAIL(44),
    PROTOCOL_HKDF_FAIL(45),
    PROTOCOL_SHA_FAIL(46),
    PROTOCOL_RAND_FAIL(47),
    PROTOCOL_ENCRYPT_FAIL(48),
    PROTOCOL_DECRYPT_FAIL(49),
    PROTOCOL_VERIFY_DATA_FAIL(50),
    PROTOCOL_KEY_ALG_FAIL(51),
    PROTOCOL_RESULT_ERROR(52),
    KEK_NOT_EXIST(53),
    DEK_NOT_EXIST(54),
    TEMP_KEY_GEN_FAILED(55),
    INPUT_PTR_NULL(56),
    ;

    companion object {
        fun fromCode(code: Int): HcError? = values().firstOrNull { it.code == code }
    }
}

/** 认证进度（原库 `set_service_result` 的取值）。 */
object HcResult {
    const val END_SUCCESS = 0
    const val END_FAILED = 1
    const val KEY_AGREEMENT_PROCESSING = 2
    const val KEY_AGREEMENT_END = 3
    const val OPERATION_PROCESSING = 4
    const val OPERATION_END = 5
}

/** 消息里的操作码（`operationCode`）。 */
object HcOperation {
    const val NO_OPERATION = 0
    const val BIND = 1
    const val AUTHENTICATE = 2
    const val ADD_AUTHINFO = 3
    const val REMOVE_AUTHINFO = 4
    const val UNBIND = 5
    const val AUTH_KEY_AGREEMENT = 6
    const val SEC_CLONE_OP = 8
}
