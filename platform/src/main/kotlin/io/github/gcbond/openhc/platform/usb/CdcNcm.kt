package io.github.gcbond.openhc.platform.usb

import io.github.gcbond.openhc.core.common.ByteReader
import io.github.gcbond.openhc.core.common.ByteWriter
import io.github.gcbond.openhc.core.common.MalformedMessageException

/** USB CDC NCM 1.0 的类请求（发给控制口，wIndex 是控制口的接口号）。 */
object CdcNcm {
    /** 设备 → 主机、类请求、接收者为接口。 */
    const val REQUEST_TYPE_IN = 0xA1

    /** 主机 → 设备、类请求、接收者为接口。 */
    const val REQUEST_TYPE_OUT = 0x21

    const val GET_NTB_PARAMETERS = 0x80
    const val SET_NTB_INPUT_SIZE = 0x86

    /** GET_NTB_PARAMETERS 应答的长度。 */
    const val NTB_PARAMETERS_LENGTH = 28

    /** 安卓 9 以前 `UsbDeviceConnection.bulkTransfer` 一次最多传 16384 字节，多的会被截掉。 */
    const val LEGACY_MAX_BULK_TRANSFER = 16384

    /** NTB16 的块长度字段是 16 位，接收缓冲不需要更大。 */
    const val MAX_NTB16_BLOCK = 65536

    /** 规范规定的 NTB 最大长度的下限。 */
    const val MIN_NTB_SIZE = 2048

    /** SET_NTB_INPUT_SIZE 的 4 字节载荷：dwNtbInMaxSize（小端）。 */
    fun encodeNtbInputSize(size: Int): ByteArray {
        require(size >= MIN_NTB_SIZE) { "NTB 输入上限不能小于 $MIN_NTB_SIZE: $size" }
        return ByteWriter(4).u32Le(size.toLong()).toByteArray()
    }

    /**
     * 车机这边每次接收用多大的缓冲，以及要不要先用 SET_NTB_INPUT_SIZE 把手机的 NTB 上限压下来。
     *
     * 一次 bulk 传输就是一块 NTB；系统单次传输上限比手机的 NTB 上限小时，块会被截断，所以要先告诉手机发小一点。
     * 实测手机报的 NTB 输入上限是 16384，正好等于老系统的单次上限，用不着压。
     *
     * @param deviceInMax 手机报的 dwNtbInMaxSize；取不到时传 null。
     * @param sdkInt 安卓版本号（`Build.VERSION.SDK_INT`）。
     */
    fun receivePlan(deviceInMax: Long?, sdkInt: Int): ReceivePlan {
        val systemMax = if (sdkInt < 28) LEGACY_MAX_BULK_TRANSFER else MAX_NTB16_BLOCK
        if (deviceInMax == null) return ReceivePlan(systemMax, null)
        val device = deviceInMax.coerceIn(MIN_NTB_SIZE.toLong(), MAX_NTB16_BLOCK.toLong()).toInt()
        return if (device > systemMax) ReceivePlan(systemMax, systemMax) else ReceivePlan(device, null)
    }

    /**
     * @property receiveSize 每次接收的字节数。
     * @property setInputSize 需要先发 SET_NTB_INPUT_SIZE 时的取值；不需要时为 null。
     */
    data class ReceivePlan(val receiveSize: Int, val setInputSize: Int?)
}

/**
 * GET_NTB_PARAMETERS 的应答（28 字节，小端）：
 * ```
 * wLength 2 | bmNtbFormatsSupported 2 | dwNtbInMaxSize 4 | wNdpInDivisor 2 | wNdpInPayloadRemainder 2
 * wNdpInAlignment 2 | 保留 2 | dwNtbOutMaxSize 4 | wNdpOutDivisor 2 | wNdpOutPayloadRemainder 2
 * wNdpOutAlignment 2 | wNtbOutMaxDatagrams 2
 * ```
 * In 指手机 → 车机，Out 指车机 → 手机。实测手机报：支持 NTB16 与 NTB32，In 上限 16384，Out 上限 3568，
 * Out 对齐 4/0/4。
 */
data class NtbParameters(
    val formatsSupported: Int,
    val ntbInMaxSize: Long,
    val ntbOutMaxSize: Long,
    val outDivisor: Int,
    val outPayloadRemainder: Int,
    val outAlignment: Int,
    val outMaxDatagrams: Int,
) {
    val supportsNtb16: Boolean get() = (formatsSupported and 0x01) != 0

    companion object {
        /** 解析应答；短于 28 字节抛 [MalformedMessageException]。 */
        fun parse(data: ByteArray, length: Int = data.size): NtbParameters {
            if (length < CdcNcm.NTB_PARAMETERS_LENGTH) {
                throw MalformedMessageException("NTB 参数太短: $length 字节")
            }
            val r = ByteReader(data, 0, CdcNcm.NTB_PARAMETERS_LENGTH)
            r.skip(2) // wLength
            val formats = r.u16Le()
            val inMax = r.u32Le()
            r.skip(2 + 2 + 2 + 2) // In 方向的对齐参数与保留字段：车机收的时候不需要
            val outMax = r.u32Le()
            val outDivisor = r.u16Le()
            val outRemainder = r.u16Le()
            val outAlignment = r.u16Le()
            val outMaxDatagrams = r.u16Le()
            return NtbParameters(formats, inMax, outMax, outDivisor, outRemainder, outAlignment, outMaxDatagrams)
        }
    }
}
