package io.github.gcbond.openhc.platform.media

/** 测试用：按字段拼 RBSP 位串（每个字段写明取值），再加防竞争字节封成 NAL。 */
internal class TestBits {
    private val bits = StringBuilder()

    fun u(n: Int, value: Long): TestBits {
        for (i in n - 1 downTo 0) bits.append(if ((value ushr i) and 1L == 1L) '1' else '0')
        return this
    }

    fun u(n: Int, value: Int): TestBits = u(n, value.toLong())

    fun flag(set: Boolean): TestBits = u(1, if (set) 1 else 0)

    fun ue(value: Long): TestBits {
        val x = value + 1
        val length = 64 - java.lang.Long.numberOfLeadingZeros(x)
        repeat(length - 1) { bits.append('0') }
        return u(length, x)
    }

    fun ue(value: Int): TestBits = ue(value.toLong())

    fun se(value: Int): TestBits = ue(if (value > 0) 2L * value - 1 else -2L * value)

    /** 加上 rbsp_trailing_bits（一个 1，补 0 到字节边界）后的字节。 */
    fun rbsp(): ByteArray {
        val s = StringBuilder(bits).append('1')
        while (s.length % 8 != 0) s.append('0')
        return ByteArray(s.length / 8) { i -> s.substring(i * 8, i * 8 + 8).toInt(2).toByte() }
    }
}

/** NAL 头 + 加了防竞争字节的 RBSP。 */
internal fun nal(header: Int, rbsp: ByteArray): ByteArray = byteArrayOf(header.toByte()) + AnnexB.escape(rbsp)

/** 依次加四字节起始码拼成一个访问单元。 */
internal fun accessUnit(vararg nals: ByteArray): ByteArray =
    nals.fold(ByteArray(0)) { acc, n -> acc + byteArrayOf(0, 0, 0, 1) + n }

/**
 * 合成 SPS（字段取值都是参数，默认 Baseline、4:2:0 逐行、无裁剪、无 VUI）。
 * [vui] 为 null 时不写 VUI；否则按给定的 (max_num_reorder_frames, max_dec_frame_buffering) 写一个
 * 只有 bitstream_restriction 的 VUI。
 */
internal fun spsNal(
    profile: Int = 66,
    level: Int = 31,
    widthMbs: Int = 80,
    heightMapUnits: Int = 45,
    frameMbsOnly: Boolean = true,
    crop: IntArray? = null,
    chromaFormatIdc: Int = 1,
    pocType: Int = 2,
    maxRefFrames: Int = 1,
    scalingMatrix: Boolean = false,
    vui: Pair<Int, Int>? = null,
    vuiTiming: Boolean = false,
    vuiHrd: Boolean = false,
): ByteArray {
    val b = TestBits()
        .u(8, profile)
        .u(8, 0)
        .u(8, level)
        .ue(0) // seq_parameter_set_id
    if (profile in setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
        b.ue(chromaFormatIdc)
        if (chromaFormatIdc == 3) b.flag(false) // separate_colour_plane_flag
        b.ue(0).ue(0) // bit_depth_luma_minus8、bit_depth_chroma_minus8
        b.flag(false) // qpprime_y_zero_transform_bypass_flag
        b.flag(scalingMatrix)
        if (scalingMatrix) {
            val lists = if (chromaFormatIdc != 3) 8 else 12
            for (i in 0 until lists) {
                b.flag(i % 2 == 0)
                if (i % 2 == 0) {
                    // 第一个 delta 让 nextScale 变成 0（用默认矩阵），列表就此结束。
                    if (i == 0) b.se(-8) else repeat(if (i < 6) 16 else 64) { b.se(1) }
                }
            }
        }
    }
    b.ue(0) // log2_max_frame_num_minus4
    b.ue(pocType)
    when (pocType) {
        0 -> b.ue(2) // log2_max_pic_order_cnt_lsb_minus4
        1 -> {
            b.flag(false) // delta_pic_order_always_zero_flag
            b.se(-1) // offset_for_non_ref_pic
            b.se(0) // offset_for_top_to_bottom_field
            b.ue(2) // num_ref_frames_in_pic_order_cnt_cycle
            b.se(2).se(2)
        }
    }
    b.ue(maxRefFrames)
    b.flag(false) // gaps_in_frame_num_value_allowed_flag
    b.ue(widthMbs - 1)
    b.ue(heightMapUnits - 1)
    b.flag(frameMbsOnly)
    if (!frameMbsOnly) b.flag(true) // mb_adaptive_frame_field_flag
    b.flag(true) // direct_8x8_inference_flag
    b.flag(crop != null)
    if (crop != null) crop.forEach { b.ue(it) }
    b.flag(vui != null)
    if (vui != null) {
        b.flag(true).u(8, 255).u(16, 1).u(16, 1) // aspect_ratio_idc = 255 + sar 1:1
        b.flag(false) // overscan_info_present_flag
        b.flag(true).u(3, 5).flag(false).flag(true).u(8, 1).u(8, 1).u(8, 1) // video_signal_type + colour description
        b.flag(false) // chroma_loc_info_present_flag
        b.flag(vuiTiming)
        // num_units_in_tick = 1：31 个连续的 0 位，封装时一定会插入防竞争字节。
        if (vuiTiming) b.u(32, 1L).u(32, 120L).flag(true)
        b.flag(vuiHrd) // nal_hrd_parameters_present_flag
        if (vuiHrd) {
            b.ue(1).u(4, 0).u(4, 0) // cpb_cnt_minus1 = 1
            repeat(2) { b.ue(4_000_000_000L).ue(123).flag(false) } // 码率值超过 Int 也要能跳过
            b.u(5, 23).u(5, 23).u(5, 23).u(5, 24)
        }
        b.flag(false) // vcl_hrd_parameters_present_flag
        if (vuiHrd) b.flag(false) // low_delay_hrd_flag
        b.flag(false) // pic_struct_present_flag
        b.flag(true) // bitstream_restriction_flag
        b.flag(true).ue(0).ue(0).ue(16).ue(16)
        b.ue(vui.first).ue(vui.second)
    }
    return nal(0x67, b.rbsp())
}
