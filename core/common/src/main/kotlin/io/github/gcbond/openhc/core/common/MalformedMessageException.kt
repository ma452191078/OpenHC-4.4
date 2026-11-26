package io.github.gcbond.openhc.core.common

import java.io.IOException

/** 收到的数据不符合协议格式（长度不够、字段越界、取值非法等）。 */
class MalformedMessageException(message: String, cause: Throwable? = null) : IOException(message, cause)
