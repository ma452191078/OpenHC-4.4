package io.github.gcbond.openhc.core.nearby

/**
 * WiFi 信道与频率的换算，以及数据通道协商里 5 GHz 信道位图的位序。无状态，线程安全。
 */
object WifiChannels {
    /** 能力串里 5 GHz 信道位图的位序：第 0 位起依次是这些信道。更高的位含义未知，忽略。 */
    val CHANNELS_5G: List<Int> = listOf(36, 40, 44, 48, 149, 153, 157, 161, 165)

    /**
     * 信道号换成中心频率（MHz）：2.4 GHz 的 1～13 是 2407 + 5 × 信道，14 是 2484；
     * 5 GHz 的 32～177 是 5000 + 5 × 信道。其他信道号抛 [IllegalArgumentException]。
     */
    fun frequencyOf(channel: Int): Int = when (channel) {
        in 1..13 -> 2407 + 5 * channel
        14 -> 2484
        in 32..177 -> 5000 + 5 * channel
        else -> throw IllegalArgumentException("不是 2.4 GHz 或 5 GHz 的信道号: $channel")
    }

    /** 中心频率（MHz）换成信道号；不是 2.4 GHz 或 5 GHz 的信道频率时返回 null。 */
    fun channelOf(frequencyMhz: Int): Int? = when {
        frequencyMhz == 2484 -> 14
        frequencyMhz in 2412..2472 && (frequencyMhz - 2407) % 5 == 0 -> (frequencyMhz - 2407) / 5
        frequencyMhz in 5160..5885 && frequencyMhz % 5 == 0 -> (frequencyMhz - 5000) / 5
        else -> null
    }

    fun is2g(frequencyMhz: Int): Boolean = frequencyMhz in 2412..2484 && channelOf(frequencyMhz) != null

    fun is5g(frequencyMhz: Int): Boolean = frequencyMhz in 5160..5885 && channelOf(frequencyMhz) != null

    /** 信道位图里置位的、已知含义的 5 GHz 信道。 */
    fun channelsOf(bitmap: Long): Set<Int> {
        val out = LinkedHashSet<Int>()
        for ((bit, channel) in CHANNELS_5G.withIndex()) {
            if (bitmap and (1L shl bit) != 0L) out += channel
        }
        return out
    }

    /** 由 5 GHz 信道组成位图；不在 [CHANNELS_5G] 里的信道抛 [IllegalArgumentException]。 */
    fun bitmapOf(channels: Collection<Int>): Long {
        var bitmap = 0L
        for (channel in channels) {
            val bit = CHANNELS_5G.indexOf(channel)
            require(bit >= 0) { "信道 $channel 不在协商位图的位序里" }
            bitmap = bitmap or (1L shl bit)
        }
        return bitmap
    }
}
