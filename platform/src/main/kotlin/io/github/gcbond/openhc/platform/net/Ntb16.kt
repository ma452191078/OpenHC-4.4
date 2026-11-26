package io.github.gcbond.openhc.platform.net

import io.github.gcbond.openhc.core.common.MalformedMessageException

/**
 * USB CDC NCM 1.0 的 NTB16（16 位传输块）组帧与拆帧。纯逻辑，不依赖安卓。
 *
 * 结构（全部小端）：
 * ```
 * NTH16  12 字节   签名 "NCMH" | wHeaderLength=12 | wSequence | wBlockLength | wNdpIndex
 * NDP16  8+4n 字节 签名 "NCM0" | wLength | wNextNdpIndex | n 项 {wDatagramIndex, wDatagramLength}，以 {0,0} 结束
 * 数据报            一个以太网帧（不含 FCS）
 * ```
 * 本端发出的每块 NTB 只放一个数据报：NTH16 → 一项加终止项的 NDP16（16 字节）→ 数据报，
 * 各自的偏移见 [NtbOutLayout]。
 */
object Ntb16 {
    const val NTH16_LENGTH = 12
    const val NDP16_HEADER_LENGTH = 8
    const val NDP16_ENTRY_LENGTH = 4

    /** 只含一项和终止项的 NDP16 长度，也是 NDP16 的最小合法长度。 */
    const val SINGLE_NDP16_LENGTH = NDP16_HEADER_LENGTH + 2 * NDP16_ENTRY_LENGTH

    /** "NCMH"。 */
    const val NTH16_SIGNATURE = 0x484D434E

    /** "NCM0"：数据报不带 CRC。主机从不打开 CRC 模式，所以只认这一种。 */
    const val NDP16_SIGNATURE = 0x304D434E

    /** NTB16 的块长度字段是 16 位。 */
    const val MAX_BLOCK_LENGTH = 0xFFFF

    /** 以太网最短帧（不含 FCS）。更短的数据报补零到这个长度：ARP 应答只有 42 字节，不补齐有的对端会丢。 */
    const val MIN_DATAGRAM_LENGTH = 60

    /** 以太网头长度；比它还短的数据报不是以太网帧。 */
    const val MIN_ETHERNET_FRAME = 14

    /** 一块 NTB 里最多跟几个 NDP16。实际只见过 1 个；上限只是为了防止 wNextNdpIndex 成环。 */
    const val MAX_NDP_COUNT = 16

    /** 拆帧时逐个交出数据报；`offset`/`length` 指向传入缓冲里的以太网帧。 */
    fun interface DatagramSink {
        fun onDatagram(buffer: ByteArray, offset: Int, length: Int)
    }

    /**
     * 一个 [datagramLength] 字节的数据报按 [layout] 组成 NTB 后的总长度（含补齐）。
     *
     * @param zlpPacketSize 发送端点的最大包长。整块长度正好是它的整数倍时多补 1 个零字节：
     *   否则最后一个包是满包，对端要再等一个零长包才认为这块结束；补 1 字节让最后一个包变成短包。
     *   传 0 表示不做这项处理。
     */
    fun blockLength(layout: NtbOutLayout, datagramLength: Int, zlpPacketSize: Int): Int {
        require(datagramLength >= 0) { "数据报长度不能为负: $datagramLength" }
        require(zlpPacketSize >= 0) { "端点包长不能为负: $zlpPacketSize" }
        val payload = maxOf(datagramLength, MIN_DATAGRAM_LENGTH)
        var block = layout.datagramOffset + payload
        block += (4 - (block and 3)) and 3
        if (zlpPacketSize > 0 && block % zlpPacketSize == 0) block += 1
        return block
    }

    /**
     * 原地组帧：数据报已经放在 `buffer[layout.datagramOffset, layout.datagramOffset + datagramLength)`，
     * 这里写好 NTH16、NDP16、补齐字节，返回整块长度。缓冲可以复用，除数据报外的字节都会被重写。
     */
    fun frameInPlace(
        buffer: ByteArray,
        layout: NtbOutLayout,
        datagramLength: Int,
        sequence: Int,
        zlpPacketSize: Int,
    ): Int {
        require(datagramLength >= MIN_ETHERNET_FRAME) { "数据报短于以太网头: $datagramLength" }
        val block = blockLength(layout, datagramLength, zlpPacketSize)
        require(block <= MAX_BLOCK_LENGTH) { "NTB16 放不下: $block 字节" }
        require(block <= buffer.size) { "缓冲不够: 需要 $block 字节，只有 ${buffer.size}" }
        val payload = maxOf(datagramLength, MIN_DATAGRAM_LENGTH)

        // NTH16 与 NDP16 之间、NDP16 与数据报之间的空隙，以及数据报之后的补齐，都要清零。
        buffer.fill(0, NTH16_LENGTH, layout.datagramOffset)
        buffer.fill(0, layout.datagramOffset + datagramLength, block)

        buffer.putU32le(0, NTH16_SIGNATURE)
        buffer.putU16le(4, NTH16_LENGTH)
        buffer.putU16le(6, sequence and 0xFFFF)
        buffer.putU16le(8, block)
        buffer.putU16le(10, layout.ndpIndex)

        val ndp = layout.ndpIndex
        buffer.putU32le(ndp, NDP16_SIGNATURE)
        buffer.putU16le(ndp + 4, SINGLE_NDP16_LENGTH)
        buffer.putU16le(ndp + 6, 0)
        buffer.putU16le(ndp + 8, layout.datagramOffset)
        buffer.putU16le(ndp + 10, payload)
        // 终止项 {0,0} 已在上面清零。
        return block
    }

    /** 把 `datagram[offset, offset + length)` 组成一块新的 NTB。 */
    fun frame(
        datagram: ByteArray,
        offset: Int,
        length: Int,
        layout: NtbOutLayout,
        sequence: Int,
        zlpPacketSize: Int,
    ): ByteArray {
        require(offset >= 0 && length >= 0 && offset + length <= datagram.size) { "范围非法" }
        val out = ByteArray(blockLength(layout, length, zlpPacketSize))
        System.arraycopy(datagram, offset, out, layout.datagramOffset, length)
        frameInPlace(out, layout, length, sequence, zlpPacketSize)
        return out
    }

    /**
     * 拆一块收到的 NTB16（一次 bulk 传输的内容），按顺序把每个数据报交给 [sink]，返回数据报个数。
     *
     * 先完整校验整块再交出数据报：格式不对时抛 [MalformedMessageException]，此时一个数据报也不会交出。
     * 只检查会影响解读的字段（签名、长度、偏移是否越界）；出向对齐这类只对发送端有约束的字段不检查。
     */
    fun parse(block: ByteArray, offset: Int, length: Int, sink: DatagramSink): Int {
        require(offset >= 0 && length >= 0 && offset + length <= block.size) { "范围非法" }
        val limit = checkedBlockLimit(block, offset, length)
        val count = walk(block, offset, limit, null)
        walk(block, offset, limit, sink)
        return count
    }

    /** 校验 NTH16，返回这块 NTB 的有效长度（相对 [offset]）。 */
    private fun checkedBlockLimit(block: ByteArray, offset: Int, length: Int): Int {
        if (length < NTH16_LENGTH) throw MalformedMessageException("NTB 太短: $length 字节")
        if (block.u32le(offset) != NTH16_SIGNATURE.toLong()) throw MalformedMessageException("NTH16 签名不对")
        val headerLength = block.u16le(offset + 4)
        if (headerLength != NTH16_LENGTH) throw MalformedMessageException("NTH16 头长度不是 12: $headerLength")
        val blockLength = block.u16le(offset + 8)
        // wBlockLength 为 0 表示块由短包结束，长度就是这次传输的长度。
        if (blockLength == 0) return length
        if (blockLength < NTH16_LENGTH) throw MalformedMessageException("NTB 块长度太小: $blockLength")
        if (blockLength > length) {
            throw MalformedMessageException("NTB 不完整: 声明 $blockLength 字节，只收到 $length 字节")
        }
        return blockLength
    }

    /** 沿 NDP16 链走一遍。sink 为 null 时只校验并计数。偏移都相对 NTB 起点 [base]，不超过 [limit]。 */
    private fun walk(block: ByteArray, base: Int, limit: Int, sink: DatagramSink?): Int {
        var ndp = block.u16le(base + 10)
        var ndpCount = 0
        var datagrams = 0
        while (true) {
            if (ndp < NTH16_LENGTH || ndp + NDP16_HEADER_LENGTH > limit) {
                throw MalformedMessageException("NDP16 偏移越界: $ndp（块长 $limit）")
            }
            if (++ndpCount > MAX_NDP_COUNT) throw MalformedMessageException("NDP16 超过 $MAX_NDP_COUNT 个")
            if (block.u32le(base + ndp) != NDP16_SIGNATURE.toLong()) {
                throw MalformedMessageException("NDP16 签名不对（偏移 $ndp）")
            }
            val ndpLength = block.u16le(base + ndp + 4)
            if (ndpLength < SINGLE_NDP16_LENGTH || ndp + ndpLength > limit) {
                throw MalformedMessageException("NDP16 长度非法: $ndpLength（偏移 $ndp，块长 $limit）")
            }
            val next = block.u16le(base + ndp + 6)
            var entry = ndp + NDP16_HEADER_LENGTH
            val entriesEnd = ndp + ndpLength
            while (entry + NDP16_ENTRY_LENGTH <= entriesEnd) {
                val index = block.u16le(base + entry)
                val length = block.u16le(base + entry + 2)
                // 终止项是 {0,0}；任一为 0 都按列表结束处理（偏移 0 落在 NTH16 里，长度 0 没有内容）。
                if (index == 0 || length == 0) break
                if (length < MIN_ETHERNET_FRAME) {
                    throw MalformedMessageException("数据报短于以太网头: $length 字节（偏移 $index）")
                }
                if (index < NTH16_LENGTH || index + length > limit) {
                    throw MalformedMessageException("数据报越界: 偏移 $index 长度 $length（块长 $limit）")
                }
                sink?.onDatagram(block, base + index, length)
                datagrams++
                entry += NDP16_ENTRY_LENGTH
            }
            if (next == 0) return datagrams
            if (next == ndp) throw MalformedMessageException("NDP16 链指向自己（偏移 $ndp）")
            ndp = next
        }
    }
}

/**
 * 出向（车机 → 手机）NTB 的布局：NDP16 放在 [ndpIndex]，数据报放在 [datagramOffset]（都从块首算起）。
 *
 * 由手机在 GET_NTB_PARAMETERS 里报的出向对齐要求算出：NDP16 偏移是 wNdpOutAlignment 的整数倍，
 * 数据报偏移满足 `偏移 % wNdpOutDivisor == wNdpOutPayloadRemainder`。实测手机报 4/0/4，
 * 算出来就是 NDP16 在 12、数据报在 28。
 */
class NtbOutLayout private constructor(val ndpIndex: Int, val datagramOffset: Int) {

    override fun toString(): String = "NtbOutLayout(ndp=$ndpIndex, datagram=$datagramOffset)"

    companion object {
        /** 对齐参数取不到或不合理时用的值，与实测手机上报的一致。 */
        const val DEFAULT_DIVISOR = 4
        const val DEFAULT_REMAINDER = 0
        const val DEFAULT_ALIGNMENT = 4

        /** 对齐参数的上限。规范里是 16 位字段，但真设备都很小；过大的值会让单个数据报的块超出手机的接收上限。 */
        const val MAX_ALIGNMENT = 256

        val DEFAULT: NtbOutLayout = of(DEFAULT_DIVISOR, DEFAULT_REMAINDER, DEFAULT_ALIGNMENT)

        /**
         * 按手机报的出向对齐参数算布局。[alignment] 必须是 ≥4 的 2 的幂（规范要求），[divisor] ≥1；
         * 不合规的参数会被替换成默认值（[isValid] 可以先判断）。
         */
        fun of(divisor: Int, remainder: Int, alignment: Int): NtbOutLayout {
            val d = if (divisor in 1..MAX_ALIGNMENT) divisor else DEFAULT_DIVISOR
            val a = if (isValidAlignment(alignment)) alignment else DEFAULT_ALIGNMENT
            // Kotlin 的 mod 结果与除数同号；java.lang.Math.floorMod 在安卓 7.0 以前没有。
            val r = remainder.mod(d)
            val ndp = alignUp(Ntb16.NTH16_LENGTH, a)
            val minimum = ndp + Ntb16.SINGLE_NDP16_LENGTH
            val datagram = minimum + (r - minimum).mod(d)
            return NtbOutLayout(ndp, datagram)
        }

        /** 参数是否合规（不合规时 [of] 会用默认值代替）。 */
        fun isValid(divisor: Int, alignment: Int): Boolean =
            divisor in 1..MAX_ALIGNMENT && isValidAlignment(alignment)

        private fun isValidAlignment(alignment: Int): Boolean =
            alignment in 4..MAX_ALIGNMENT && (alignment and (alignment - 1)) == 0

        private fun alignUp(value: Int, alignment: Int): Int = (value + alignment - 1) / alignment * alignment
    }
}
