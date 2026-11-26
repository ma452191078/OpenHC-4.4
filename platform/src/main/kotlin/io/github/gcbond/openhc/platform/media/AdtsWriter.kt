package io.github.gcbond.openhc.platform.media

/**
 * 写 ADTS 头（ISO/IEC 13818-7，纯逻辑）：编码器吐出的是裸 AAC 帧，车机麦克风上行时每帧前面加 7 字节 ADTS 头
 * （不带 CRC），接收端靠它知道对象类型、采样率和声道。
 *
 * ```
 * 同步字 0xFFF(12) ‖ ID 0（MPEG-4）(1) ‖ layer 00(2) ‖ 无 CRC 1(1) ‖ 对象类型−1(2) ‖ 采样率索引(4) ‖ 私有位 0(1)
 * ‖ 声道配置(3) ‖ 原始/拷贝/版权位 0000(4) ‖ 帧长（含头）(13) ‖ 缓冲满度 0x7FF(11) ‖ 原始数据块数−1 = 0(2)
 * ```
 */
object AdtsWriter {
    const val HEADER_BYTES = 7

    /** 帧长字段 13 位，含头。 */
    const val MAX_FRAME_BYTES = 0x1FFF

    /**
     * 把 `header[at, at + 7)` 写成一个 ADTS 头。[payloadLength] 是裸 AAC 帧的长度（不含头）。
     * 采样率要在标准表里（[AacConfig.sampleRateIndex]），声道 1～7。
     */
    fun write(header: ByteArray, at: Int, sampleRate: Int, channels: Int, payloadLength: Int, objectType: Int = AacConfig.OBJECT_TYPE_LC) {
        val index = AacConfig.sampleRateIndex(sampleRate)
        require(index >= 0) { "采样率不在 ADTS 的表里: $sampleRate" }
        require(channels in 1..7) { "声道配置超出范围: $channels" }
        require(objectType in 1..4) { "ADTS 只能写对象类型 1～4: $objectType" }
        val frameLength = payloadLength + HEADER_BYTES
        require(payloadLength >= 0 && frameLength <= MAX_FRAME_BYTES) { "帧太长: $payloadLength" }
        require(at >= 0 && at <= header.size - HEADER_BYTES) { "位置超出范围" }
        val profile = objectType - 1
        header[at] = 0xFF.toByte()
        header[at + 1] = 0xF1.toByte()
        header[at + 2] = ((profile shl 6) or (index shl 2) or (channels shr 2)).toByte()
        header[at + 3] = (((channels and 0x03) shl 6) or (frameLength shr 11)).toByte()
        header[at + 4] = ((frameLength shr 3) and 0xFF).toByte()
        header[at + 5] = (((frameLength and 0x07) shl 5) or 0x1F).toByte()
        header[at + 6] = 0xFC.toByte()
    }

    /** 裸 AAC 帧加上 ADTS 头，返回新数组。 */
    fun wrap(payload: ByteArray, offset: Int, length: Int, sampleRate: Int, channels: Int): ByteArray {
        require(offset >= 0 && length >= 0 && offset <= payload.size - length) { "范围非法" }
        val frame = ByteArray(HEADER_BYTES + length)
        write(frame, 0, sampleRate, channels, length)
        System.arraycopy(payload, offset, frame, HEADER_BYTES, length)
        return frame
    }
}
