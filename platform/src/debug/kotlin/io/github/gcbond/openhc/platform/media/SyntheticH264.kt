package io.github.gcbond.openhc.platform.media

import java.io.ByteArrayOutputStream

/**
 * 调试用的合成 H.264 码流（只编进调试构建）：不依赖编码器，任何车机上都能放，用来在真机上检查解码、显示和触摸换算。
 *
 * 码流参数：Constrained Baseline、CAVLC、POC type 2（解码顺序就是显示顺序）、一个参考帧、关闭环路滤波，
 * VUI 里声明不需要重排。
 * - 关键帧：SPS + PPS + IDR 条带，所有宏块都是 I_PCM（直接写像素）。
 * - 其他帧：一个 P 条带，和上一帧相同的宏块跳过（P_Skip；全帧运动矢量都是 0，跳过就是原样复制），
 *   变了的宏块用 I_PCM 重写。画面变化少时一帧只有几百字节。
 *
 * 解出来的像素和 [frame] 里画的逐字节一致（PCM 无损、没有滤波），所以也能拿来校验解码结果。
 * [width]、[height] 必须是偶数；编码尺寸按 16 对齐，多出来的部分用 SPS 的裁剪去掉。
 */
class SyntheticH264(val width: Int, val height: Int, private val keyFrameInterval: Int = 60) {
    init {
        require(width in 16..4096 && height in 16..4096) { "尺寸超出范围" }
        require(width % 2 == 0 && height % 2 == 0) { "宽高必须是偶数" }
        require(keyFrameInterval > 0) { "关键帧间隔必须是正数" }
    }

    val codedWidth = (width + 15) / 16 * 16
    val codedHeight = (height + 15) / 16 * 16
    private val mbWidth = codedWidth / 16
    private val mbHeight = codedHeight / 16

    /** 下一帧的画面：在这里画好再调用 [encode]。只有左上角 [width]×[height] 会显示出来。 */
    val frame = YuvFrame(codedWidth, codedHeight)

    /** 解码器手里的上一帧（P 帧和它比较）。 */
    private val reference = YuvFrame(codedWidth, codedHeight)
    private var encoded = 0L
    private var sinceKeyFrame = 0
    private var frameNum = 0
    private var idrPicId = 0

    /** 把 [frame] 编成一个 Annex-B 访问单元。第一帧、[forceKeyFrame] 或到了关键帧间隔时出关键帧。 */
    fun encode(forceKeyFrame: Boolean = false): ByteArray {
        val keyFrame = encoded == 0L || forceKeyFrame || sinceKeyFrame >= keyFrameInterval
        val out = ByteArrayOutputStream()
        if (keyFrame) {
            frameNum = 0
            writeNal(out, NAL_HEADER_SPS, spsRbsp())
            writeNal(out, NAL_HEADER_PPS, ppsRbsp())
            writeNal(out, NAL_HEADER_IDR, sliceRbsp(idr = true))
            reference.copyFrom(frame)
            idrPicId = (idrPicId + 1) and 0xFFFF
            sinceKeyFrame = 0
        } else {
            writeNal(out, NAL_HEADER_P, sliceRbsp(idr = false))
        }
        // 每一帧都是参考帧，frame_num 逐帧加一，按 MaxFrameNum（16）回绕。
        frameNum = (frameNum + 1) % MAX_FRAME_NUM
        sinceKeyFrame++
        encoded++
        return out.toByteArray()
    }

    private fun spsRbsp(): ByteArray = BitWriter().apply {
        bits(PROFILE_BASELINE.toLong(), 8)
        bits(0xC0, 8) // constraint_set0_flag、constraint_set1_flag：Constrained Baseline
        bits(LEVEL.toLong(), 8)
        ue(0) // seq_parameter_set_id
        ue(0) // log2_max_frame_num_minus4：MaxFrameNum = 16
        ue(2) // pic_order_cnt_type
        ue(1) // max_num_ref_frames
        bit(0) // gaps_in_frame_num_value_allowed_flag
        ue(mbWidth - 1)
        ue(mbHeight - 1)
        bit(1) // frame_mbs_only_flag
        bit(1) // direct_8x8_inference_flag
        val crop = codedWidth != width || codedHeight != height
        bit(if (crop) 1 else 0)
        if (crop) {
            // 4:2:0 逐行：裁剪单位是 2 个像素。
            ue(0)
            ue((codedWidth - width) / 2)
            ue(0)
            ue((codedHeight - height) / 2)
        }
        bit(1) // vui_parameters_present_flag
        repeat(8) { bit(0) } // 宽高比、overscan、信号类型、色度位置、时间信息、两组 HRD、pic_struct 都不写
        bit(1) // bitstream_restriction_flag
        bit(1) // motion_vectors_over_pic_boundaries_flag
        ue(0) // max_bytes_per_pic_denom
        ue(0) // max_bits_per_mb_denom
        ue(15) // log2_max_mv_length_horizontal
        ue(15) // log2_max_mv_length_vertical
        ue(0) // max_num_reorder_frames：不需要重排，解码器可以立即输出
        ue(1) // max_dec_frame_buffering
        trailing()
    }.toByteArray()

    private fun ppsRbsp(): ByteArray = BitWriter().apply {
        ue(0) // pic_parameter_set_id
        ue(0) // seq_parameter_set_id
        bit(0) // entropy_coding_mode_flag：CAVLC
        bit(0) // bottom_field_pic_order_in_frame_present_flag
        ue(0) // num_slice_groups_minus1
        ue(0) // num_ref_idx_l0_default_active_minus1
        ue(0) // num_ref_idx_l1_default_active_minus1
        bit(0) // weighted_pred_flag
        bits(0, 2) // weighted_bipred_idc
        se(0) // pic_init_qp_minus26
        se(0) // pic_init_qs_minus26
        se(0) // chroma_qp_index_offset
        bit(1) // deblocking_filter_control_present_flag：条带头里关环路滤波
        bit(0) // constrained_intra_pred_flag
        bit(0) // redundant_pic_cnt_present_flag
        trailing()
    }.toByteArray()

    private fun sliceRbsp(idr: Boolean): ByteArray {
        val w = BitWriter()
        w.ue(0) // first_mb_in_slice
        w.ue(if (idr) SLICE_TYPE_I_ALL else SLICE_TYPE_P_ALL)
        w.ue(0) // pic_parameter_set_id
        w.bits(frameNum.toLong(), LOG2_MAX_FRAME_NUM)
        if (idr) w.ue(idrPicId)
        if (!idr) {
            w.bit(0) // num_ref_idx_active_override_flag
            w.bit(0) // ref_pic_list_modification_flag_l0
        }
        if (idr) {
            w.bit(0) // no_output_of_prior_pics_flag
            w.bit(0) // long_term_reference_flag
        } else {
            w.bit(0) // adaptive_ref_pic_marking_mode_flag
        }
        w.se(0) // slice_qp_delta
        w.ue(1) // disable_deblocking_filter_idc：不做环路滤波，像素原样输出
        if (idr) {
            for (mby in 0 until mbHeight) {
                for (mbx in 0 until mbWidth) writePcm(w, MB_TYPE_I_PCM_IN_I, mbx, mby)
            }
        } else {
            var skipRun = 0
            for (mby in 0 until mbHeight) {
                for (mbx in 0 until mbWidth) {
                    if (frame.macroblockEquals(reference, mbx, mby)) {
                        skipRun++
                        continue
                    }
                    w.ue(skipRun)
                    skipRun = 0
                    writePcm(w, MB_TYPE_I_PCM_IN_P, mbx, mby)
                    frame.copyMacroblockTo(reference, mbx, mby)
                }
            }
            // 末尾连续跳过的宏块；最后一个宏块刚好编码了就不写（多写一个 0 会让解码器以为后面还有宏块）。
            if (skipRun > 0) w.ue(skipRun)
        }
        w.trailing()
        return w.toByteArray()
    }

    private fun writePcm(w: BitWriter, mbType: Int, mbx: Int, mby: Int) {
        w.ue(mbType)
        w.alignWithZeros() // pcm_alignment_zero_bit
        frame.writeMacroblock(w, mbx, mby)
    }

    private fun writeNal(out: ByteArrayOutputStream, header: Int, rbsp: ByteArray) {
        out.write(START_CODE)
        out.write(header)
        out.write(AnnexB.escape(rbsp))
    }

    private companion object {
        const val PROFILE_BASELINE = 66
        const val LEVEL = 31
        const val LOG2_MAX_FRAME_NUM = 4
        const val MAX_FRAME_NUM = 1 shl LOG2_MAX_FRAME_NUM

        /** slice_type 7、5：本帧所有条带都是 I、P。 */
        const val SLICE_TYPE_I_ALL = 7
        const val SLICE_TYPE_P_ALL = 5

        /** I_PCM 的 mb_type：I 条带里是 25；P 条带里帧内宏块的编号要加 5。 */
        const val MB_TYPE_I_PCM_IN_I = 25
        const val MB_TYPE_I_PCM_IN_P = 30

        // NAL 头：forbidden_zero_bit 0、nal_ref_idc、nal_unit_type。
        const val NAL_HEADER_SPS = 0x67
        const val NAL_HEADER_PPS = 0x68
        const val NAL_HEADER_IDR = 0x65
        const val NAL_HEADER_P = 0x41

        val START_CODE = byteArrayOf(0, 0, 0, 1)
    }
}

/**
 * 4:2:0 的 YUV 画面（BT.601 有限范围），宽高是偶数。颜色用 [Yuv.of] 打包成一个 Int。
 */
class YuvFrame(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) { "宽高必须是正偶数" }
    }

    val luma = ByteArray(width * height)
    val cb = ByteArray(width / 2 * (height / 2))
    val cr = ByteArray(width / 2 * (height / 2))

    init {
        fill(Yuv.BLACK)
    }

    fun fill(color: Int) = fillRect(0, 0, width, height, color)

    /** 填充矩形（亮度坐标，超出画面的部分裁掉）；色度按矩形覆盖到的 2×2 块填。 */
    fun fillRect(x: Int, y: Int, w: Int, h: Int, color: Int) {
        val x0 = x.coerceIn(0, width)
        val y0 = y.coerceIn(0, height)
        val x1 = (x + w).coerceIn(0, width)
        val y1 = (y + h).coerceIn(0, height)
        if (x0 >= x1 || y0 >= y1) return
        val yv = Yuv.y(color).toByte()
        val uv = Yuv.u(color).toByte()
        val vv = Yuv.v(color).toByte()
        for (row in y0 until y1) luma.fill(yv, row * width + x0, row * width + x1)
        val cw = width / 2
        for (row in y0 / 2 until (y1 + 1) / 2) {
            cb.fill(uv, row * cw + x0 / 2, row * cw + (x1 + 1) / 2)
            cr.fill(vv, row * cw + x0 / 2, row * cw + (x1 + 1) / 2)
        }
    }

    fun copyFrom(other: YuvFrame) {
        require(other.width == width && other.height == height) { "尺寸不同" }
        other.luma.copyInto(luma)
        other.cb.copyInto(cb)
        other.cr.copyInto(cr)
    }

    /** 左上角 [w]×[h] 区域按 Y、U、V 平面依次排好（与常见的 yuv420p 原始帧格式相同）。 */
    fun cropped(w: Int, h: Int): ByteArray {
        require(w in 2..width && h in 2..height && w % 2 == 0 && h % 2 == 0) { "裁剪尺寸非法" }
        val out = ByteArray(w * h + 2 * (w / 2) * (h / 2))
        var at = 0
        for (row in 0 until h) {
            luma.copyInto(out, at, row * width, row * width + w)
            at += w
        }
        for (plane in arrayOf(cb, cr)) {
            for (row in 0 until h / 2) {
                plane.copyInto(out, at, row * (width / 2), row * (width / 2) + w / 2)
                at += w / 2
            }
        }
        return out
    }

    internal fun macroblockEquals(other: YuvFrame, mbx: Int, mby: Int): Boolean {
        for (row in 0 until 16) {
            val at = (mby * 16 + row) * width + mbx * 16
            for (i in at until at + 16) if (luma[i] != other.luma[i]) return false
        }
        val cw = width / 2
        for (row in 0 until 8) {
            val at = (mby * 8 + row) * cw + mbx * 8
            for (i in at until at + 8) if (cb[i] != other.cb[i] || cr[i] != other.cr[i]) return false
        }
        return true
    }

    internal fun copyMacroblockTo(other: YuvFrame, mbx: Int, mby: Int) {
        for (row in 0 until 16) {
            val at = (mby * 16 + row) * width + mbx * 16
            luma.copyInto(other.luma, at, at, at + 16)
        }
        val cw = width / 2
        for (row in 0 until 8) {
            val at = (mby * 8 + row) * cw + mbx * 8
            cb.copyInto(other.cb, at, at, at + 8)
            cr.copyInto(other.cr, at, at, at + 8)
        }
    }

    /** 按 I_PCM 的顺序写一个宏块：256 个亮度、64 个 Cb、64 个 Cr，各自按行扫描。 */
    internal fun writeMacroblock(out: BitWriter, mbx: Int, mby: Int) {
        for (row in 0 until 16) out.pcmBytes(luma, (mby * 16 + row) * width + mbx * 16, 16)
        val cw = width / 2
        for (plane in arrayOf(cb, cr)) {
            for (row in 0 until 8) out.pcmBytes(plane, (mby * 8 + row) * cw + mbx * 8, 8)
        }
    }
}

/** RGB → BT.601 有限范围 YUV，打包成 `Y << 16 | U << 8 | V`。 */
object Yuv {
    val BLACK = of(0, 0, 0)

    fun of(r: Int, g: Int, b: Int): Int {
        val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
        val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
        val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
        return (y.coerceIn(0, 255) shl 16) or (u.coerceIn(0, 255) shl 8) or v.coerceIn(0, 255)
    }

    fun y(color: Int): Int = (color ushr 16) and 0xFF
    fun u(color: Int): Int = (color ushr 8) and 0xFF
    fun v(color: Int): Int = color and 0xFF
}

/** 按位写 RBSP。 */
internal class BitWriter {
    private var buffer = ByteArray(1024)
    private var size = 0
    private var current = 0
    private var bitCount = 0

    fun bit(b: Int) {
        current = (current shl 1) or (b and 1)
        bitCount++
        if (bitCount == 8) {
            push(current)
            current = 0
            bitCount = 0
        }
    }

    fun bits(value: Long, n: Int) {
        for (i in n - 1 downTo 0) bit(((value ushr i) and 1L).toInt())
    }

    fun ue(value: Int) {
        require(value >= 0) { "ue(v) 不能是负数" }
        val x = value.toLong() + 1
        val length = 64 - java.lang.Long.numberOfLeadingZeros(x)
        repeat(length - 1) { bit(0) }
        bits(x, length)
    }

    fun se(value: Int) = ue(if (value > 0) 2 * value - 1 else -2 * value)

    fun alignWithZeros() {
        while (bitCount != 0) bit(0)
    }

    /** rbsp_trailing_bits：一个 1，再补 0 到字节边界。 */
    fun trailing() {
        bit(1)
        alignWithZeros()
    }

    /**
     * 写 8 位 PCM 样本（必须字节对齐）。样本值 0 写成 1：早期版本的标准不允许 0（防止和起始码混淆），
     * 有的老解码器可能还按这个检查。
     */
    fun pcmBytes(src: ByteArray, offset: Int, length: Int) {
        check(bitCount == 0) { "PCM 样本必须字节对齐" }
        ensure(length)
        for (i in 0 until length) {
            val b = src[offset + i]
            buffer[size++] = if (b.toInt() == 0) 1 else b
        }
    }

    fun toByteArray(): ByteArray {
        check(bitCount == 0) { "还有不满一个字节的位" }
        return buffer.copyOf(size)
    }

    private fun push(b: Int) {
        ensure(1)
        buffer[size++] = b.toByte()
    }

    private fun ensure(extra: Int) {
        if (size + extra <= buffer.size) return
        var capacity = buffer.size
        while (capacity < size + extra) capacity *= 2
        buffer = buffer.copyOf(capacity)
    }
}
