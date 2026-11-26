package io.github.gcbond.openhc.platform.net

import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.platform.net.TestFrames.NtbBuilder
import io.github.gcbond.openhc.platform.net.TestFrames.pattern
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Ntb16Test {

    private val layout = NtbOutLayout.DEFAULT

    /** 收集拆出来的数据报（复制出来，避免共用缓冲）。 */
    private fun unpack(block: ByteArray, offset: Int = 0, length: Int = block.size - offset): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val count = Ntb16.parse(block, offset, length) { buf, off, len -> out.add(buf.copyOfRange(off, off + len)) }
        assertEquals(out.size, count)
        return out
    }

    @Test
    fun defaultLayoutMatchesMeasuredAlignment() {
        // 实测手机报出向对齐 4/0/4：NDP16 紧跟 NTH16，数据报紧跟 16 字节的 NDP16。
        assertEquals(12, layout.ndpIndex)
        assertEquals(28, layout.datagramOffset)
    }

    @Test
    fun framesShortDatagramWithHeadersAndPadding() {
        val frame = pattern(42)
        val ntb = Ntb16.frame(frame, 0, frame.size, layout, sequence = 0x1234, zlpPacketSize = 512)

        assertEquals(88, ntb.size, "28 字节头 + 补到 60 字节的数据报")
        assertEquals(0x484D434EL, ntb.u32le(0), "NTH16 签名 NCMH")
        assertEquals(12, ntb.u16le(4))
        assertEquals(0x1234, ntb.u16le(6))
        assertEquals(88, ntb.u16le(8), "wBlockLength 等于整块长度")
        assertEquals(12, ntb.u16le(10))
        assertEquals(0x304D434EL, ntb.u32le(12), "NDP16 签名 NCM0")
        assertEquals(16, ntb.u16le(16))
        assertEquals(0, ntb.u16le(18))
        assertEquals(28, ntb.u16le(20))
        assertEquals(60, ntb.u16le(22), "短于 60 字节的数据报按 60 字节登记")
        assertEquals(0, ntb.u16le(24))
        assertEquals(0, ntb.u16le(26))
        assertContentEquals(frame, ntb.copyOfRange(28, 70))
        assertTrue(isAllZero(ntb, 70, 18), "补齐部分全是 0")
    }

    @Test
    fun sequenceWrapsAt16Bits() {
        val ntb = Ntb16.frame(pattern(60), 0, 60, layout, sequence = 0x1FFFF, zlpPacketSize = 512)
        assertEquals(0xFFFF, ntb.u16le(6))
    }

    @Test
    fun blockIsPaddedToFourBytes() {
        val ntb = Ntb16.frame(pattern(61), 0, 61, layout, sequence = 0, zlpPacketSize = 512)
        assertEquals(92, ntb.size, "28 + 61 = 89，补到 4 的整数倍")
        assertEquals(92, ntb.u16le(8))
        assertEquals(61, ntb.u16le(22), "数据报长度不含补齐")
    }

    @Test
    fun blockOnPacketBoundaryGetsOneExtraByte() {
        // 28 + 484 = 512：正好是端点包长的整数倍，多补 1 字节让最后一个包变成短包。
        val ntb = Ntb16.frame(pattern(484), 0, 484, layout, sequence = 0, zlpPacketSize = 512)
        assertEquals(513, ntb.size)
        assertEquals(513, ntb.u16le(8))
        assertEquals(484, ntb.u16le(22))

        assertEquals(508, Ntb16.blockLength(layout, 480, 512), "不在边界上就不补")
        assertEquals(129, Ntb16.blockLength(layout, 100, 64), "全速设备的 64 字节包长同样处理")
        assertEquals(512, Ntb16.blockLength(layout, 484, 0), "包长传 0 时不做这项处理")
        assertEquals(1025, Ntb16.blockLength(layout, 996, 1024), "超高速设备 1024 字节包长")
    }

    @Test
    fun frameInPlaceClearsStaleBytesInReusedBuffer() {
        val frame = pattern(42, start = 0x40)
        val buffer = ByteArray(200) { 0x5A }
        System.arraycopy(frame, 0, buffer, layout.datagramOffset, frame.size)
        val block = Ntb16.frameInPlace(buffer, layout, frame.size, sequence = 7, zlpPacketSize = 512)
        assertContentEquals(Ntb16.frame(frame, 0, frame.size, layout, 7, 512), buffer.copyOf(block))
    }

    @Test
    fun customLayoutHonoursDivisorRemainderAndAlignment() {
        val custom = NtbOutLayout.of(divisor = 8, remainder = 2, alignment = 16)
        assertEquals(16, custom.ndpIndex)
        assertEquals(34, custom.datagramOffset)
        assertEquals(2, custom.datagramOffset % 8)

        val frame = pattern(70)
        val ntb = Ntb16.frame(frame, 0, frame.size, custom, sequence = 1, zlpPacketSize = 512)
        assertEquals(16, ntb.u16le(10))
        assertEquals(34, ntb.u16le(16 + 8))
        assertTrue(isAllZero(ntb, 12, 4), "NTH16 与 NDP16 之间的空隙清零")
        val datagrams = unpack(ntb)
        assertEquals(1, datagrams.size)
        assertContentEquals(frame, datagrams[0])
    }

    @Test
    fun invalidLayoutParametersFallBackToDefaults() {
        assertTrue(!NtbOutLayout.isValid(divisor = 0, alignment = 4))
        assertTrue(!NtbOutLayout.isValid(divisor = 4, alignment = 6), "对齐必须是 2 的幂")
        assertTrue(!NtbOutLayout.isValid(divisor = 4, alignment = 2), "对齐至少 4")
        val fallback = NtbOutLayout.of(divisor = 0, remainder = 0, alignment = 6)
        assertEquals(12, fallback.ndpIndex)
        assertEquals(28, fallback.datagramOffset)
        assertEquals(30, NtbOutLayout.of(divisor = 4, remainder = 6, alignment = 4).datagramOffset, "余数按除数取模")
    }

    @Test
    fun parsesWhatItFrames() {
        val frame = pattern(1514)
        val datagrams = unpack(Ntb16.frame(frame, 0, frame.size, layout, sequence = 3, zlpPacketSize = 512))
        assertEquals(1, datagrams.size)
        assertContentEquals(frame, datagrams[0])
    }

    @Test
    fun parsesSeveralDatagramsInOneNdp() {
        val a = pattern(14, 0xA0)
        val b = pattern(20, 0xB0)
        val ntb = NtbBuilder(68).firstNdp(12).ndp(12, listOf(32 to a.size, 48 to b.size))
            .datagram(32, a).datagram(48, b).bytes
        val datagrams = unpack(ntb)
        assertEquals(2, datagrams.size)
        assertContentEquals(a, datagrams[0])
        assertContentEquals(b, datagrams[1])
    }

    @Test
    fun followsNdpChain() {
        val a = pattern(20, 1)
        val b = pattern(30, 2)
        // 第一个 NDP16 放在数据报后面，第二个放在第一个前面：规范只要求按偏移找，不要求顺序。
        val ntb = NtbBuilder(160)
            .firstNdp(100).ndp(100, listOf(16 to a.size), next = 120)
            .ndp(120, listOf(40 to b.size))
            .datagram(16, a).datagram(40, b).bytes
        val datagrams = unpack(ntb)
        assertEquals(2, datagrams.size)
        assertContentEquals(a, datagrams[0])
        assertContentEquals(b, datagrams[1])
    }

    @Test
    fun parsesAtNonZeroOffsetAndIgnoresBytesAfterBlock() {
        val frame = pattern(60, 9)
        val ntb = Ntb16.frame(frame, 0, frame.size, layout, sequence = 0, zlpPacketSize = 512)
        val buffer = ByteArray(5 + ntb.size + 7) { 0x33 }
        System.arraycopy(ntb, 0, buffer, 5, ntb.size)
        val datagrams = unpack(buffer, offset = 5, length = ntb.size + 7)
        assertEquals(1, datagrams.size)
        assertContentEquals(frame, datagrams[0])
    }

    @Test
    fun zeroBlockLengthMeansTransferLength() {
        val frame = pattern(60)
        val ntb = Ntb16.frame(frame, 0, frame.size, layout, sequence = 0, zlpPacketSize = 512)
        ntb.putU16le(8, 0)
        assertContentEquals(frame, unpack(ntb).single())
    }

    @Test
    fun entryWithZeroIndexOrLengthEndsTheList() {
        val a = pattern(20, 1)
        val ntb = NtbBuilder(80).firstNdp(12).ndp(12, listOf(40 to a.size, 0 to 30, 60 to 14))
            .datagram(40, a).bytes
        assertEquals(1, unpack(ntb).size)
    }

    @Test
    fun rejectsMalformedBlocksWithoutDeliveringAnything() {
        val good = Ntb16.frame(pattern(60), 0, 60, layout, sequence = 0, zlpPacketSize = 512)
        val cases = mapOf<String, (ByteArray) -> ByteArray>(
            "太短" to { it.copyOf(11) },
            "NTH16 签名不对" to { it.also { b -> b[3] = 'X'.code.toByte() } },
            "头长度不是 12" to { it.also { b -> b.putU16le(4, 16) } },
            "块长度超过收到的字节" to { it.also { b -> b.putU16le(8, b.size + 4) } },
            "块长度小于头" to { it.also { b -> b.putU16le(8, 8) } },
            "NDP16 偏移落在头里" to { it.also { b -> b.putU16le(10, 4) } },
            "NDP16 偏移越界" to { it.also { b -> b.putU16le(10, b.size - 4) } },
            "NDP16 带 CRC" to { it.also { b -> b.putU32le(12, 0x314D434E) } },
            "NDP16 长度太小" to { it.also { b -> b.putU16le(16, 12) } },
            "NDP16 长度越界" to { it.also { b -> b.putU16le(16, b.size) } },
            "数据报短于以太网头" to { it.also { b -> b.putU16le(22, 13) } },
            "数据报越界" to { it.also { b -> b.putU16le(22, b.size) } },
            "数据报偏移落在头里" to { it.also { b -> b.putU16le(20, 4) } },
            "NDP16 链指向自己" to { it.also { b -> b.putU16le(18, 12) } },
        )
        for ((name, corrupt) in cases) {
            val bad = corrupt(good.copyOf())
            var delivered = 0
            assertFailsWith<MalformedMessageException>(name) {
                Ntb16.parse(bad, 0, bad.size) { _, _, _ -> delivered++ }
            }
            assertEquals(0, delivered, "$name：格式不对时一个数据报也不交出")
        }
    }

    @Test
    fun laterBadEntryRejectsWholeBlock() {
        val a = pattern(20, 1)
        val ntb = NtbBuilder(80).firstNdp(12).ndp(12, listOf(40 to a.size, 70 to 40)).datagram(40, a).bytes
        var delivered = 0
        assertFailsWith<MalformedMessageException> { Ntb16.parse(ntb, 0, ntb.size) { _, _, _ -> delivered++ } }
        assertEquals(0, delivered, "先整块校验，再交出数据报")
    }

    @Test
    fun rejectsNdpCycle() {
        // 两个各自合法的 NDP16 互相指向，构成环：靠个数上限拦下。
        val ntb = NtbBuilder(64).firstNdp(12)
            .ndp(12, listOf(44 to 14), next = 28)
            .ndp(28, listOf(44 to 14), next = 12)
            .bytes
        var delivered = 0
        val error = assertFailsWith<MalformedMessageException> { Ntb16.parse(ntb, 0, ntb.size) { _, _, _ -> delivered++ } }
        assertTrue(error.message!!.contains("超过"), error.message)
        assertEquals(0, delivered)
    }

    @Test
    fun rejectsDatagramsTooShortForEthernetWhenFraming() {
        assertFailsWith<IllegalArgumentException> { Ntb16.frameInPlace(ByteArray(100), layout, 13, 0, 512) }
        assertFailsWith<IllegalArgumentException> { Ntb16.frameInPlace(ByteArray(80), layout, 60, 0, 512) }
    }
}
