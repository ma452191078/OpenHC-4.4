package io.github.gcbond.openhc.platform.net

// 数据面热路径上按下标读写多字节整数。网络包内部一律大端（网络序），NCM 的 NTB 结构一律小端。
// 调用方负责先做越界检查；这里不再重复检查，越界会抛 ArrayIndexOutOfBoundsException。

internal fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF

internal fun ByteArray.u16be(at: Int): Int = (u8(at) shl 8) or u8(at + 1)

internal fun ByteArray.u16le(at: Int): Int = u8(at) or (u8(at + 1) shl 8)

internal fun ByteArray.u32le(at: Int): Long = (u16le(at).toLong()) or (u16le(at + 2).toLong() shl 16)

internal fun ByteArray.putU16be(at: Int, value: Int) {
    this[at] = (value ushr 8).toByte()
    this[at + 1] = value.toByte()
}

internal fun ByteArray.putU16le(at: Int, value: Int) {
    this[at] = value.toByte()
    this[at + 1] = (value ushr 8).toByte()
}

internal fun ByteArray.putU32le(at: Int, value: Int) {
    putU16le(at, value and 0xFFFF)
    putU16le(at + 2, value ushr 16)
}

/** `[a+aOffset, a+aOffset+length)` 与 `[b+bOffset, …)` 逐字节相等。 */
internal fun regionEquals(a: ByteArray, aOffset: Int, b: ByteArray, bOffset: Int, length: Int): Boolean {
    for (i in 0 until length) {
        if (a[aOffset + i] != b[bOffset + i]) return false
    }
    return true
}

internal fun isAllZero(data: ByteArray, offset: Int, length: Int): Boolean {
    for (i in 0 until length) {
        if (data[offset + i].toInt() != 0) return false
    }
    return true
}
