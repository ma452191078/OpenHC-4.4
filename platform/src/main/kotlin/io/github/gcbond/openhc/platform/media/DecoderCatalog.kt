package io.github.gcbond.openhc.platform.media

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import java.util.Locale

/** 系统里的 H.264 解码器，按优先顺序排好，配置失败时依次换下一个。 */
internal object DecoderCatalog {
    const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC

    class Candidate(
        val name: String,
        val hardware: Boolean,
        /** 解码器声明支持这个尺寸。不支持的也会排在最后试一下：有的解码器报的能力偏保守。 */
        val sizeSupported: Boolean,
        /** 支持安卓标准的低延迟模式（安卓 11 起）。 */
        val lowLatency: Boolean,
    )

    private class Entry(val info: MediaCodecInfo, val capabilities: MediaCodecInfo.CodecCapabilities)

    /** 解码器清单第一次查要几十毫秒，查一次缓存起来（运行中不会变）。 */
    private val decoders: List<Entry> by lazy {
        val infos = try {
            // 4.4 fork：按注册表查的 MediaCodecList(int) 是 21+ 的，更早的系统用老的逐个枚举接口。
            // 老接口只报解码器本体，别名本来就报不出来，行为一致。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList()
            } else {
                @Suppress("DEPRECATION")
                (0 until MediaCodecList.getCodecCount()).mapNotNull { i ->
                    try {
                        MediaCodecList.getCodecInfoAt(i)
                    } catch (e: RuntimeException) {
                        // 个别条目坏了就跳过，别的照样用。
                        null
                    }
                }
            }
        } catch (e: RuntimeException) {
            emptyList()
        }
        infos.mapNotNull { info ->
            if (info.isEncoder) return@mapNotNull null
            if (info.supportedTypes.none { it.equals(MIME, ignoreCase = true) }) return@mapNotNull null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isAlias) return@mapNotNull null
            // 加密内容专用的解码器要配 MediaCrypto，用不上。
            if (info.name.lowercase(Locale.ROOT).endsWith(".secure")) return@mapNotNull null
            val capabilities = try {
                info.getCapabilitiesForType(MIME)
            } catch (e: IllegalArgumentException) {
                return@mapNotNull null
            }
            Entry(info, capabilities)
        }
    }

    /**
     * 能解 [width]×[height] 的解码器在前，硬件解码器在前；同一档里保持系统清单的顺序（一般是厂商推荐的顺序）。
     * 清单查不到任何 H.264 解码器时返回空列表，调用方再按类型让系统挑。
     */
    fun candidates(width: Int, height: Int): List<Candidate> = decoders
        .map { entry ->
            val sizeSupported = try {
                // 4.4 fork：VideoCapabilities 是 21+ 的，更早的系统查不了尺寸支持，当作不支持：
                // 不支持的照样排在末尾试，报的能力偏保守的解码器还有机会。
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                    false
                } else {
                    entry.capabilities.videoCapabilities?.isSizeSupported(width, height) ?: false
                }
            } catch (e: RuntimeException) {
                false
            }
            Candidate(
                name = entry.info.name,
                hardware = isHardware(entry.info),
                sizeSupported = sizeSupported,
                lowLatency = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    entry.capabilities.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency),
            )
        }
        .sortedWith(compareByDescending<Candidate> { it.sizeSupported }.thenByDescending { it.hardware })

    /** 安卓 10 起系统直接标明是否纯软件实现；更早只能按名字判断（系统自带的软件解码器有固定前缀）。 */
    private fun isHardware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return !info.isSoftwareOnly
        val name = info.name.lowercase(Locale.ROOT)
        return !(name.startsWith("omx.google.") || name.startsWith("c2.android.") || name.contains(".sw."))
    }
}
