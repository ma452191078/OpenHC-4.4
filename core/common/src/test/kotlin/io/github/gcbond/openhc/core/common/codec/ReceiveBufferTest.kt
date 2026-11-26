package io.github.gcbond.openhc.core.common.codec

import io.github.gcbond.openhc.core.common.hexToBytes
import java.io.ByteArrayOutputStream
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class ReceiveBufferTest {

    @Test
    fun compactsBeforeGrowing() {
        val buffer = ReceiveBuffer(8)
        val first = buffer.array
        buffer.append(ByteArray(6) { it.toByte() })
        buffer.consume(4)
        assertEquals(4, buffer.start)
        // 剩 2 字节 + 新来 5 字节 = 7，不超过 8：搬到开头，不换数组。
        buffer.append(byteArrayOf(10, 11, 12, 13, 14))
        assertSame(first, buffer.array)
        assertEquals(0, buffer.start)
        assertEquals(7, buffer.end)
        assertContentEquals(byteArrayOf(4, 5, 10, 11, 12, 13, 14), buffer.copy(0, 7))
    }

    @Test
    fun growsByDoubling() {
        val buffer = ReceiveBuffer(4)
        val first = buffer.array
        val data = ByteArray(1000) { it.toByte() }
        buffer.append(data, 0, 3)
        buffer.consume(1)
        // 剩 2 字节 + 新来 9 字节 = 11：容量 4 → 8 → 16。
        buffer.append(data, 3, 9)
        assertNotSame(first, buffer.array)
        assertEquals(16, buffer.array.size)
        assertEquals(11, buffer.size)
        assertContentEquals(data.copyOfRange(1, 12), buffer.copy(0, 11))
    }

    @Test
    fun readsAreRelativeToStart() {
        val buffer = ReceiveBuffer()
        buffer.append("ff0102030405060708".hexToBytes())
        buffer.consume(1)
        assertEquals(1, buffer.byteAt(0))
        assertEquals(8, buffer.byteAt(7))
        assertEquals(0x04030201L, buffer.u32Le(0))
        assertEquals(0x05060708L, buffer.u32Be(4))
        assertContentEquals(byteArrayOf(3, 4), buffer.copy(2, 2))
        // 直接读底层数组。
        assertEquals(1, buffer.array[buffer.start].toInt())
        assertEquals(buffer.start + 8, buffer.end)
    }

    @Test
    fun unsignedReads() {
        val buffer = ReceiveBuffer()
        buffer.append("ffffffff80".hexToBytes())
        assertEquals(0xFF, buffer.byteAt(0))
        assertEquals(0xFFFFFFFFL, buffer.u32Le(0))
        assertEquals(0xFFFFFF80L, buffer.u32Be(1))
    }

    @Test
    fun consumingEverythingRewinds() {
        val buffer = ReceiveBuffer(16)
        buffer.append(ByteArray(10))
        buffer.consume(10)
        assertEquals(0, buffer.size)
        assertEquals(0, buffer.start)
        assertEquals(0, buffer.end)
        buffer.append(ByteArray(3))
        buffer.clear()
        assertEquals(0, buffer.size)
        assertEquals(0, buffer.start)
    }

    @Test
    fun appendHonoursOffsetAndLength() {
        val buffer = ReceiveBuffer()
        buffer.append(byteArrayOf(1, 2, 3, 4, 5), 1, 3)
        buffer.append(byteArrayOf(9), 1, 0)
        assertContentEquals(byteArrayOf(2, 3, 4), buffer.copy(0, buffer.size))
    }

    /** 随机切分写入、随机取走，内容始终与参照模型一致。 */
    @Test
    fun randomOperationsMatchModel() {
        val random = Random(42)
        val buffer = ReceiveBuffer(16)
        var model = ByteArray(0)
        var next = 0
        repeat(2000) {
            if (random.nextInt(3) != 0) {
                val n = random.nextInt(300)
                val chunk = ByteArray(n) { (next + it).toByte() }
                next += n
                buffer.append(chunk)
                model += chunk
            } else if (model.isNotEmpty()) {
                val n = random.nextInt(model.size + 1)
                assertContentEquals(model.copyOf(n), buffer.copy(0, n))
                buffer.consume(n)
                model = model.copyOfRange(n, model.size)
            }
            assertEquals(model.size, buffer.size)
        }
        val rest = ByteArrayOutputStream().apply { write(buffer.array, buffer.start, buffer.size) }.toByteArray()
        assertContentEquals(model, rest)
    }

    @Test
    fun rejectsBadArguments() {
        assertFailsWith<IllegalArgumentException> { ReceiveBuffer(0) }
        val buffer = ReceiveBuffer()
        assertFailsWith<IllegalArgumentException> { buffer.append(ByteArray(4), 3, 2) }
        assertFailsWith<IllegalArgumentException> { buffer.append(ByteArray(4), -1, 1) }
        assertFailsWith<IllegalArgumentException> { buffer.append(ByteArray(4), 1, Int.MAX_VALUE) }
        buffer.append(ByteArray(4))
        assertFailsWith<IllegalArgumentException> { buffer.consume(5) }
        assertFailsWith<IllegalArgumentException> { buffer.consume(-1) }
        assertFailsWith<IndexOutOfBoundsException> { buffer.byteAt(4) }
        assertFailsWith<IndexOutOfBoundsException> { buffer.byteAt(-1) }
        assertFailsWith<IndexOutOfBoundsException> { buffer.u32Le(1) }
        assertFailsWith<IndexOutOfBoundsException> { buffer.u32Be(1) }
        assertFailsWith<IndexOutOfBoundsException> { buffer.copy(2, 3) }
        assertEquals(4, buffer.size)
    }
}
