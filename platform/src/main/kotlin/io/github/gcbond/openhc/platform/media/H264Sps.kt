package io.github.gcbond.openhc.platform.media

import io.github.gcbond.openhc.core.common.MalformedMessageException

/**
 * H.264 序列参数集（SPS）里解码器配置要用到的字段。纯逻辑，不引用安卓类。
 *
 * [width]、[height] 是裁剪后的显示尺寸；[codedWidth]、[codedHeight] 是按宏块对齐的编码尺寸。
 */
data class H264Sps(
    val profileIdc: Int,
    val constraintFlags: Int,
    val levelIdc: Int,
    val spsId: Int,
    val chromaFormatIdc: Int,
    val bitDepthLuma: Int,
    val bitDepthChroma: Int,
    val picOrderCntType: Int,
    val maxNumRefFrames: Int,
    val frameMbsOnly: Boolean,
    val codedWidth: Int,
    val codedHeight: Int,
    val width: Int,
    val height: Int,
    /** VUI 里的 max_num_reorder_frames；没写（或 VUI 读不出来）为 null。 */
    val maxNumReorderFrames: Int?,
    /** VUI 里的 max_dec_frame_buffering；没写为 null。 */
    val maxDecFrameBuffering: Int?,
) {
    /** 码流保证解码顺序就是显示顺序：Baseline 不允许 B 条带，或者 VUI 明说不需要重排。 */
    val reorderFree: Boolean
        get() = profileIdc == PROFILE_BASELINE || maxNumReorderFrames == 0

    companion object {
        const val PROFILE_BASELINE = 66

        /** 这些 profile 的 SPS 里多出色度格式、位深和缩放矩阵几个字段。 */
        private val EXTENDED_HEADER_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

        /** 宽、高的宏块数上限（16384 像素），超过就当格式错误，不拿荒唐的尺寸去配解码器。 */
        private const val MAX_MBS = 1024

        /**
         * 解析一个 SPS NAL 单元：[offset] 指向 NAL 头字节（不含起始码），载荷里可以带防竞争字节。
         * 尺寸之前的字段不合法就抛 [MalformedMessageException]。VUI 读不出来不算错，只是 [maxNumReorderFrames]
         * 等字段为 null：显示尺寸在 VUI 之前已经确定，VUI 只用来判断能不能低延迟输出。
         */
        fun parse(nal: ByteArray, offset: Int = 0, length: Int = nal.size - offset): H264Sps {
            require(offset >= 0 && length >= 0 && offset + length <= nal.size) { "范围非法" }
            if (length < 4) throw MalformedMessageException("SPS 太短")
            if (AnnexB.nalType(nal[offset]) != AnnexB.NAL_SPS) throw MalformedMessageException("不是 SPS")
            val r = RbspReader(nal, offset + 1, offset + length)

            val profileIdc = r.u(8)
            val constraintFlags = r.u(8)
            val levelIdc = r.u(8)
            val spsId = r.ue(max = 31)

            var chromaFormatIdc = 1
            var separateColourPlane = false
            var bitDepthLuma = 8
            var bitDepthChroma = 8
            if (profileIdc in EXTENDED_HEADER_PROFILES) {
                chromaFormatIdc = r.ue(max = 3)
                if (chromaFormatIdc == 3) separateColourPlane = r.flag()
                bitDepthLuma = 8 + r.ue(max = 6)
                bitDepthChroma = 8 + r.ue(max = 6)
                r.flag() // qpprime_y_zero_transform_bypass_flag
                if (r.flag()) { // seq_scaling_matrix_present_flag
                    val lists = if (chromaFormatIdc != 3) 8 else 12
                    for (i in 0 until lists) {
                        if (r.flag()) skipScalingList(r, if (i < 6) 16 else 64)
                    }
                }
            }

            r.ue(max = 12) // log2_max_frame_num_minus4
            val picOrderCntType = r.ue(max = 2)
            when (picOrderCntType) {
                0 -> r.ue(max = 12) // log2_max_pic_order_cnt_lsb_minus4
                1 -> {
                    r.flag() // delta_pic_order_always_zero_flag
                    r.se() // offset_for_non_ref_pic
                    r.se() // offset_for_top_to_bottom_field
                    val cycle = r.ue(max = 255)
                    repeat(cycle) { r.se() } // offset_for_ref_frame
                }
            }
            val maxNumRefFrames = r.ue(max = 16)
            r.flag() // gaps_in_frame_num_value_allowed_flag
            val widthMbs = r.ue(max = MAX_MBS - 1) + 1
            val heightMapUnits = r.ue(max = MAX_MBS - 1) + 1
            val frameMbsOnly = r.flag()
            if (!frameMbsOnly) r.flag() // mb_adaptive_frame_field_flag
            r.flag() // direct_8x8_inference_flag

            var cropLeft = 0L
            var cropRight = 0L
            var cropTop = 0L
            var cropBottom = 0L
            if (r.flag()) { // frame_cropping_flag
                cropLeft = r.ueLong()
                cropRight = r.ueLong()
                cropTop = r.ueLong()
                cropBottom = r.ueLong()
            }

            val codedWidth = widthMbs * 16
            val frameHeightMbs = (if (frameMbsOnly) 1 else 2) * heightMapUnits
            if (frameHeightMbs > MAX_MBS) throw MalformedMessageException("SPS 的高度超出范围")
            val codedHeight = frameHeightMbs * 16

            // 裁剪单位：单色或四个分量分开编码时按亮度像素；否则按色度采样间隔，场编码时纵向再乘 2。
            val chromaArrayType = if (separateColourPlane) 0 else chromaFormatIdc
            val fieldFactor = if (frameMbsOnly) 1 else 2
            val cropUnitX: Int
            val cropUnitY: Int
            if (chromaArrayType == 0) {
                cropUnitX = 1
                cropUnitY = fieldFactor
            } else {
                cropUnitX = if (chromaFormatIdc == 3) 1 else 2
                cropUnitY = (if (chromaFormatIdc == 1) 2 else 1) * fieldFactor
            }
            val width = codedWidth - cropUnitX * (cropLeft + cropRight)
            val height = codedHeight - cropUnitY * (cropTop + cropBottom)
            if (width <= 0 || height <= 0) throw MalformedMessageException("SPS 的裁剪超出画面")

            var maxNumReorderFrames: Int? = null
            var maxDecFrameBuffering: Int? = null
            if (r.flag()) { // vui_parameters_present_flag
                try {
                    val restriction = parseVuiRestriction(r)
                    if (restriction != null) {
                        maxNumReorderFrames = restriction.first
                        maxDecFrameBuffering = restriction.second
                    }
                } catch (e: MalformedMessageException) {
                    // 当作没有 VUI：不据此假定可以低延迟输出。
                }
            }

            return H264Sps(
                profileIdc = profileIdc,
                constraintFlags = constraintFlags,
                levelIdc = levelIdc,
                spsId = spsId,
                chromaFormatIdc = chromaFormatIdc,
                bitDepthLuma = bitDepthLuma,
                bitDepthChroma = bitDepthChroma,
                picOrderCntType = picOrderCntType,
                maxNumRefFrames = maxNumRefFrames,
                frameMbsOnly = frameMbsOnly,
                codedWidth = codedWidth,
                codedHeight = codedHeight,
                width = width.toInt(),
                height = height.toInt(),
                maxNumReorderFrames = maxNumReorderFrames,
                maxDecFrameBuffering = maxDecFrameBuffering,
            )
        }

        private fun skipScalingList(r: RbspReader, size: Int) {
            var last = 8
            var next = 8
            for (j in 0 until size) {
                if (next != 0) {
                    val delta = r.se()
                    if (delta !in -128..127) throw MalformedMessageException("缩放矩阵取值超出范围")
                    next = (last + delta + 256) % 256
                }
                if (next != 0) last = next
            }
        }

        /** 读完 VUI，返回 bitstream_restriction 里的 (max_num_reorder_frames, max_dec_frame_buffering)，没写返回 null。 */
        private fun parseVuiRestriction(r: RbspReader): Pair<Int, Int>? {
            if (r.flag()) { // aspect_ratio_info_present_flag
                if (r.u(8) == EXTENDED_SAR) r.bits(32) // sar_width、sar_height
            }
            if (r.flag()) r.flag() // overscan_info_present_flag → overscan_appropriate_flag
            if (r.flag()) { // video_signal_type_present_flag
                r.u(3) // video_format
                r.flag() // video_full_range_flag
                if (r.flag()) r.bits(24) // colour_primaries、transfer_characteristics、matrix_coefficients
            }
            if (r.flag()) { // chroma_loc_info_present_flag
                r.ue(max = 5)
                r.ue(max = 5)
            }
            if (r.flag()) { // timing_info_present_flag
                r.bits(32) // num_units_in_tick
                r.bits(32) // time_scale
                r.flag() // fixed_frame_rate_flag
            }
            val nalHrd = r.flag()
            if (nalHrd) skipHrd(r)
            val vclHrd = r.flag()
            if (vclHrd) skipHrd(r)
            if (nalHrd || vclHrd) r.flag() // low_delay_hrd_flag
            r.flag() // pic_struct_present_flag
            if (!r.flag()) return null // bitstream_restriction_flag
            r.flag() // motion_vectors_over_pic_boundaries_flag
            r.ueLong() // max_bytes_per_pic_denom
            r.ueLong() // max_bits_per_mb_denom
            r.ueLong() // log2_max_mv_length_horizontal
            r.ueLong() // log2_max_mv_length_vertical
            val reorder = r.ue(max = 16)
            val buffering = r.ue(max = 16)
            return reorder to buffering
        }

        private fun skipHrd(r: RbspReader) {
            val cpbCount = r.ue(max = 31) + 1
            r.u(4) // bit_rate_scale
            r.u(4) // cpb_size_scale
            repeat(cpbCount) {
                r.ueLong() // bit_rate_value_minus1
                r.ueLong() // cpb_size_value_minus1
                r.flag() // cbr_flag
            }
            r.u(5) // initial_cpb_removal_delay_length_minus1
            r.u(5) // cpb_removal_delay_length_minus1
            r.u(5) // dpb_output_delay_length_minus1
            r.u(5) // time_offset_length
        }

        private const val EXTENDED_SAR = 255
    }
}
