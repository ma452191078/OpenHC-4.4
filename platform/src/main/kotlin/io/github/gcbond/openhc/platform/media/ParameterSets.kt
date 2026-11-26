package io.github.gcbond.openhc.platform.media

import io.github.gcbond.openhc.core.common.MalformedMessageException
import java.util.TreeMap

/**
 * 记住码流里最近出现的参数集，用来配置（和重建）解码器：SPS 只留最新的一个，PPS 按编号各留最新的。
 * 有的编码器只在开头发一次参数集，解码器出错重建时要靠这里补上。纯逻辑，不引用安卓类，不是线程安全的。
 */
internal class ParameterSets {
    var sps: H264Sps? = null
        private set
    private var spsNal: ByteArray? = null
    private val ppsById = TreeMap<Int, ByteArray>()
    private val reader = RbspReader()

    val isComplete: Boolean
        get() = sps != null && ppsById.isNotEmpty()

    /**
     * 记下一个 SPS（`[start, end)` 是 NAL 单元，从 NAL 头开始）。内容和上次一样时不重新解析。
     * 格式不对抛 [MalformedMessageException]，原来记的不变。
     */
    fun updateSps(data: ByteArray, start: Int, end: Int): H264Sps {
        val known = sps
        val knownNal = spsNal
        if (known != null && knownNal != null && regionEquals(data, start, end, knownNal)) return known
        val parsed = H264Sps.parse(data, start, end - start)
        spsNal = data.copyOfRange(start, end)
        sps = parsed
        return parsed
    }

    /** 记下一个 PPS（按 pic_parameter_set_id 覆盖）。格式不对抛 [MalformedMessageException]。 */
    fun updatePps(data: ByteArray, start: Int, end: Int) {
        if (end - start < 2 || AnnexB.nalType(data[start]) != AnnexB.NAL_PPS) {
            throw MalformedMessageException("不是 PPS")
        }
        val id = reader.reset(data, start + 1, end).ue(max = 255)
        val known = ppsById[id]
        if (known != null && regionEquals(data, start, end, known)) return
        ppsById[id] = data.copyOfRange(start, end)
    }

    /** 解码器配置里的 csd-0：带起始码的 SPS。 */
    fun csd0(): ByteArray = withStartCodes(listOfNotNull(spsNal))

    /** 解码器配置里的 csd-1：依次带起始码的各个 PPS。 */
    fun csd1(): ByteArray = withStartCodes(ppsById.values)

    fun clear() {
        sps = null
        spsNal = null
        ppsById.clear()
    }

    private fun withStartCodes(nals: Collection<ByteArray>): ByteArray {
        val out = ByteArray(nals.sumOf { it.size + START_CODE.size })
        var at = 0
        for (nal in nals) {
            START_CODE.copyInto(out, at)
            at += START_CODE.size
            nal.copyInto(out, at)
            at += nal.size
        }
        return out
    }

    private fun regionEquals(data: ByteArray, start: Int, end: Int, other: ByteArray): Boolean {
        if (end - start != other.size) return false
        for (i in other.indices) {
            if (data[start + i] != other[i]) return false
        }
        return true
    }

    private companion object {
        val START_CODE = byteArrayOf(0, 0, 0, 1)
    }
}
