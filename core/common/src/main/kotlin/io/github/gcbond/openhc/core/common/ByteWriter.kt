package io.github.gcbond.openhc.core.common

import io.github.gcbond.openhc.core.common.codec.Ea

/** 按顺序拼字节，自动扩容。多字节整数分大端（`Be`）和小端（`Le`），变长整数见 [ea]。 */
class ByteWriter(initialCapacity: Int = 64) {
    private var buf = ByteArray(maxOf(initialCapacity, 16))

    var size: Int = 0
        private set

    fun u8(value: Int): ByteWriter {
        require(value in 0..0xFF) { "u8 越界: $value" }
        ensure(1)
        buf[size++] = value.toByte()
        return this
    }

    fun u16Be(value: Int): ByteWriter {
        require(value in 0..0xFFFF) { "u16 越界: $value" }
        ensure(2)
        buf[size++] = (value ushr 8).toByte()
        buf[size++] = value.toByte()
        return this
    }

    fun u16Le(value: Int): ByteWriter {
        require(value in 0..0xFFFF) { "u16 越界: $value" }
        ensure(2)
        buf[size++] = value.toByte()
        buf[size++] = (value ushr 8).toByte()
        return this
    }

    fun u32Be(value: Long): ByteWriter {
        require(value in 0..0xFFFF_FFFFL) { "u32 越界: $value" }
        ensure(4)
        for (shift in intArrayOf(24, 16, 8, 0)) buf[size++] = (value ushr shift).toByte()
        return this
    }

    fun u32Le(value: Long): ByteWriter {
        require(value in 0..0xFFFF_FFFFL) { "u32 越界: $value" }
        ensure(4)
        for (shift in intArrayOf(0, 8, 16, 24)) buf[size++] = (value ushr shift).toByte()
        return this
    }

    /** 写一个 EA 变长整数（最短形式，格式见 [Ea]）。超出 0..[Ea.MAX_VALUE] 抛 [IllegalArgumentException]。 */
    fun ea(value: Int): ByteWriter = bytes(Ea.encode(value))

    fun bytes(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): ByteWriter {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) { "范围非法" }
        ensure(length)
        System.arraycopy(data, offset, buf, size, length)
        size += length
        return this
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)

    private fun ensure(extra: Int) {
        if (size + extra <= buf.size) return
        var cap = buf.size
        while (cap < size + extra) cap *= 2
        buf = buf.copyOf(cap)
    }
}
