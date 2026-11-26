package io.github.gcbond.openhc.core.dmsdp.audio

import io.github.gcbond.openhc.core.dmsdp.AesCtr
import io.github.gcbond.openhc.core.dmsdp.SequenceRandom
import io.github.gcbond.openhc.core.dmsdp.Synthetic
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 麦克风上行打包：帧外壳、RTP 头、PCM 分片头逐字节核对；解密一律用 JCE 的 AES/CTR 独立完成，不用被测代码的实现。
 */
class MicPacketizerTest {

    /** 解开一帧 `0xAB ‖ 长度 ‖ IV16 ‖ 密文`，返回 IV 和明文（RTP 包）。 */
    private fun open(frame: ByteArray): Pair<ByteArray, ByteArray> {
        assertEquals(0xAB, frame[0].toInt() and 0xFF, "加密帧类型")
        val length = ((frame[1].toInt() and 0xFF) shl 8) or (frame[2].toInt() and 0xFF)
        assertEquals(frame.size - 3, length, "长度字段 = IV + 密文")
        val iv = frame.copyOfRange(3, 19)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(Synthetic.KEY, "AES"), IvParameterSpec(iv))
        return iv to cipher.doFinal(frame, 19, frame.size - 19)
    }

    private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    private fun pcm(size: Int) = ByteArray(size) { (it * 7 + 3).toByte() }

    private fun packetizer(random: SequenceRandom = SequenceRandom()) = MicPacketizer(AesCtr(Synthetic.KEY), random)

    @Test
    fun singlePcmFragmentHasExactLayout() {
        val data = pcm(640)
        val frames = packetizer().pcm(data, 0, data.size, timestamp = 0x0123_4567L)
        val (_, rtp) = open(frames.single())
        assertEquals(12 + 12 + 640, rtp.size)
        assertContentEquals(
            byteArrayOf(
                0x80.toByte(), 0x80.toByte(), 0x00, 0x00, // V=2；marker=1、PT 0；序号 0
                0x01, 0x23, 0x45, 0x67, // 时间戳
                0x00, 0x00, 0x04, 0x4C, // SSRC：流号 1100、用途 0
                0x00, 0x01, 0x00, 0x00, // 0、唯一一片（位置 1）、无扩展
                0x00, 0x00, 0x00, 0x00, // 分片序号 0、0
                0x00, 0x00, 0x02, 0x80.toByte(), // 扩展长度 0、本片 640 字节
            ),
            rtp.copyOf(24),
        )
        assertContentEquals(data, rtp.copyOfRange(24, rtp.size))
    }

    @Test
    fun ivComesFromTheRandomSourceAndFrameSizeIsBounded() {
        val random = SequenceRandom()
        val frames = packetizer(random).pcm(pcm(640), 0, 640, 0)
        val (iv, _) = open(frames.single())
        assertContentEquals(ByteArray(16) { ((it * 37 + 11) and 0xFF).toByte() }, iv, "IV 取自注入的随机源")
        assertEquals(16, random.index)
        assertEquals(3 + 16 + 12 + 12 + 640, frames.single().size)
    }

    /** 20ms 的 48kHz 立体声：3840 字节拆成 1024/1024/1024/768，位置 1、2、2、3，只有最后一片置 marker。 */
    @Test
    fun largerPcmBufferIsFragmentedBy1024Bytes() {
        val data = pcm(3840)
        val packer = packetizer()
        val frames = packer.pcm(data, 0, data.size, timestamp = 9_000L)
        assertEquals(4, frames.size)
        val rebuilt = ArrayList<Byte>()
        frames.forEachIndexed { i, frame ->
            val (_, rtp) = open(frame)
            assertEquals(i, u16(rtp, 2), "序号逐包加一")
            assertEquals(9_000L, u32(rtp, 4), "同一块共用时间戳")
            assertEquals(if (i == 3) 0x80 else 0x00, rtp[1].toInt() and 0xFF, "只有最后一片置 marker（PT 0）")
            assertEquals(0, rtp[12].toInt())
            assertEquals(listOf(1, 2, 2, 3)[i], rtp[13].toInt(), "分片位置")
            assertEquals(i, u16(rtp, 16), "分片序号")
            assertEquals(0, u16(rtp, 18))
            assertEquals(0, u16(rtp, 20))
            val n = if (i == 3) 768 else 1024
            assertEquals(n, u16(rtp, 22), "本片数据长度")
            assertEquals(24 + n, rtp.size)
            for (k in 24 until rtp.size) rebuilt += rtp[k]
        }
        assertContentEquals(data, rebuilt.toByteArray(), "按顺序拼回原数据")
        assertEquals(4, packer.packets)
        assertEquals(frames.sumOf { it.size }.toLong(), packer.bytes)
    }

    @Test
    fun exactlyTwoFragmentsAreFirstAndLast() {
        val frames = packetizer().pcm(pcm(2048), 0, 2048, 0)
        assertEquals(listOf(1, 3), frames.map { open(it).second[13].toInt() })
        assertEquals(listOf(1024, 1024), frames.map { u16(open(it).second, 22) })
    }

    @Test
    fun aacFrameIsOnePacketWithPayloadType96() {
        val adts = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x60, 0x40, 0x10, 0x1F, 0xFC.toByte(), 0x21, 0x22, 0x23)
        val (_, rtp) = open(packetizer().aac(adts, 0, adts.size, timestamp = 1_920L))
        assertEquals(0x80, rtp[0].toInt() and 0xFF)
        assertEquals(0x80 or 96, rtp[1].toInt() and 0xFF, "marker + PT 96")
        assertEquals(1_920L, u32(rtp, 4))
        assertEquals(0x44CL, u32(rtp, 8))
        assertContentEquals(adts, rtp.copyOfRange(12, rtp.size), "ADTS 帧原样作载荷，没有分片头")
    }

    @Test
    fun sequenceWrapsAfter65535AndTimestampKeepsLow32Bits() {
        val packer = packetizer()
        val one = byteArrayOf(0x11)
        repeat(65_535) { packer.aac(one, 0, 1, 0) }
        val (_, last) = open(packer.aac(one, 0, 1, 0x1_0000_0002L))
        assertEquals(65_535, u16(last, 2))
        assertEquals(2L, u32(last, 4), "时间戳只取低 32 位")
        val (_, wrapped) = open(packer.aac(one, 0, 1, 0))
        assertEquals(0, u16(wrapped, 2), "序号回绕")
    }

    @Test
    fun oversizedInputIsRejected() {
        val packer = packetizer()
        assertFailsWith<IllegalArgumentException> { packer.pcm(ByteArray(4097), 0, 4097, 0) }
        assertFailsWith<IllegalArgumentException> { packer.pcm(ByteArray(10), 0, 0, 0) }
        val max = MicPacketizer.MAX_AAC_FRAME
        assertEquals(1500, 16 + 12 + max, "最大的 AAC 帧正好放满一个 1500 字节的密文帧")
        assertFailsWith<IllegalArgumentException> { packer.aac(ByteArray(max + 1), 0, max + 1, 0) }
        assertEquals(1500 + 3, packer.aac(ByteArray(max), 0, max, 0).size)
    }
}
