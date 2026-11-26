package io.github.gcbond.openhc.platform.media

/**
 * 单声道 16 位 PCM 的流式重采样（线性插值，纯逻辑）。车机麦克风开不了手机要的采样率时，用能开的采样率采，
 * 再换成要的；线性插值的音质够通话和语音识别。
 *
 * 跨块保持相位：位置用「输入帧 × 输出采样率」的整数记，不累积浮点误差；上一块最后一个采样留作下一块插值的起点。
 * 输出长度随块变化（例如 44.1kHz → 16kHz 每块 320 或 321 帧），由调用方攒成固定长度。不是线程安全的。
 */
class PcmResampler(val inputRate: Int, val outputRate: Int) {
    init {
        require(inputRate in 4_000..192_000) { "输入采样率超出范围: $inputRate" }
        require(outputRate in 4_000..192_000) { "输出采样率超出范围: $outputRate" }
    }

    /** 下一个输出采样的位置：以当前块第一帧为 0，单位是 1/[outputRate] 个输入帧；-outputRate 以上表示还落在上一块末尾。 */
    private var position = 0L
    private var previous: Short = 0
    private var hasPrevious = false

    /** 输入 [frames] 帧最多会产出多少帧（给调用方准备缓冲）。 */
    fun maxOutputFrames(frames: Int): Int = ((frames.toLong() + 1) * outputRate / inputRate + 1).toInt()

    /**
     * 处理 `input[0, frames)`，结果写进 [output]（从 0 起），返回写了多少帧。[output] 至少要
     * [maxOutputFrames] 那么长。
     */
    fun process(input: ShortArray, frames: Int, output: ShortArray): Int {
        require(frames in 0..input.size) { "帧数超出范围: $frames" }
        if (frames == 0) return 0
        if (inputRate == outputRate) {
            System.arraycopy(input, 0, output, 0, frames)
            previous = input[frames - 1]
            hasPrevious = true
            return frames
        }
        val rate = outputRate.toLong()
        val limit = (frames - 1).toLong() * rate
        var written = 0
        while (position <= limit) {
            // 位置不会小于 -outputRate（见 [position]），负数时一定落在上一块最后一帧和本块第一帧之间。
            val index = if (position >= 0) (position / rate).toInt() else -1
            val fraction = position - index * rate
            val a = sample(input, index)
            val b = if (fraction == 0L) a else sample(input, index + 1)
            output[written++] = (a + (b - a) * fraction / rate).toInt().toShort()
            position += inputRate
        }
        position -= frames.toLong() * rate
        previous = input[frames - 1]
        hasPrevious = true
        return written
    }

    private fun sample(input: ShortArray, index: Int): Long = when {
        index >= 0 -> input[index].toLong()
        hasPrevious -> previous.toLong()
        else -> input[0].toLong()
    }

    companion object {
        /**
         * 单声道 → 交错的 [channels] 声道 16 位小端字节（双声道时两个声道相同），写进 `out[outOffset…]`，
         * 返回写了多少字节。
         */
        fun toLittleEndian(mono: ShortArray, frames: Int, channels: Int, out: ByteArray, outOffset: Int): Int {
            require(channels in 1..2) { "只支持单声道和双声道: $channels" }
            var at = outOffset
            for (i in 0 until frames) {
                val v = mono[i].toInt()
                repeat(channels) {
                    out[at++] = v.toByte()
                    out[at++] = (v shr 8).toByte()
                }
            }
            return at - outOffset
        }
    }
}
