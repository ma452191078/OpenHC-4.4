package io.github.gcbond.openhc.platform.media

import io.github.gcbond.openhc.core.common.MalformedMessageException

/**
 * 一个访问单元（一帧）的 NAL 摘要：有没有 IDR、参数集在哪、各条带是什么类型。
 * 每帧调用一次 [scan]，对象可复用、不分配；只能在一个线程里用。纯逻辑，不引用安卓类。
 */
class AccessUnitScan {
    var nalCount = 0
        private set
    var hasIdr = false
        private set
    var sliceCount = 0
        private set

    /** I 或 SI 条带（含 IDR）的个数。 */
    var intraSliceCount = 0
        private set
    var hasBSlice = false
        private set

    /** 第一个 SPS 的位置（指向 NAL 头），没有为 -1。 */
    var spsStart = -1
        private set
    var spsEnd = -1
        private set

    var ppsCount = 0
        private set
    private val ppsStarts = IntArray(MAX_PPS)
    private val ppsEnds = IntArray(MAX_PPS)

    private val reader = RbspReader()
    private val visitor = AnnexB.NalVisitor { data, start, end -> onNal(data, start, end) }

    /**
     * 可以从这一帧开始解码：含 IDR，或者所有条带都是帧内条带（不依赖之前的帧）。
     * 丢帧之后要等到这样的帧才能继续送，否则画面会花。
     */
    val isSyncPoint: Boolean
        get() = hasIdr || (sliceCount > 0 && intraSliceCount == sliceCount)

    /** 只有参数集、没有条带（例如编码器单独发出的 SPS/PPS）。 */
    val isParameterSetsOnly: Boolean
        get() = sliceCount == 0 && (spsStart >= 0 || ppsCount > 0)

    fun ppsStart(i: Int): Int = ppsStarts[i]
    fun ppsEnd(i: Int): Int = ppsEnds[i]

    fun scan(data: ByteArray, offset: Int, length: Int): AccessUnitScan {
        nalCount = 0
        hasIdr = false
        sliceCount = 0
        intraSliceCount = 0
        hasBSlice = false
        spsStart = -1
        spsEnd = -1
        ppsCount = 0
        nalCount = AnnexB.forEachNal(data, offset, length, visitor)
        return this
    }

    private fun onNal(data: ByteArray, start: Int, end: Int) {
        when (AnnexB.nalType(data[start])) {
            AnnexB.NAL_IDR -> {
                hasIdr = true
                sliceCount++
                intraSliceCount++
            }
            AnnexB.NAL_SLICE -> {
                sliceCount++
                when (sliceType(data, start, end)) {
                    SLICE_I, SLICE_SI -> intraSliceCount++
                    SLICE_B -> hasBSlice = true
                }
            }
            AnnexB.NAL_SPS -> if (spsStart < 0) {
                spsStart = start
                spsEnd = end
            }
            AnnexB.NAL_PPS -> if (ppsCount < MAX_PPS) {
                ppsStarts[ppsCount] = start
                ppsEnds[ppsCount] = end
                ppsCount++
            }
        }
    }

    /** 条带头的前两个字段：first_mb_in_slice、slice_type（0～9，模 5 后 0 P、1 B、2 I、3 SP、4 SI）。读不出来返回 -1。 */
    private fun sliceType(data: ByteArray, start: Int, end: Int): Int = try {
        reader.reset(data, start + 1, end)
        reader.ueLong()
        val type = reader.ue(max = 9)
        type % 5
    } catch (e: MalformedMessageException) {
        -1
    }

    companion object {
        const val SLICE_P = 0
        const val SLICE_B = 1
        const val SLICE_I = 2
        const val SLICE_SP = 3
        const val SLICE_SI = 4

        /** 一帧里记录的 PPS 个数上限，多出来的不记（仍随帧原样送进解码器）。 */
        private const val MAX_PPS = 8
    }
}
