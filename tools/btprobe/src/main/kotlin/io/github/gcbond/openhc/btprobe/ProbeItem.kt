package io.github.gcbond.openhc.btprobe

/** 每个检测项的结论。 */
enum class Status(val label: String) {
    /** 拿到了能用的值。 */
    OK("成功"),

    /** 调用没出错，但值不能用（例如通道号是 -1、地址是占位值）。 */
    FAIL("失败"),

    /** 调用抛了异常（被隐藏接口限制拦下、权限不足、IO 错误等）。 */
    ERROR("异常"),

    /** 这台设备或这次检测不适用。 */
    SKIP("跳过"),
}

/**
 * 一条检测结果。
 *
 * [value] 是屏幕上显示的完整值；[exportValue] 用于复制、存文件和 logcat，里面的本机地址和对端地址要打码。
 * 两者只在含地址的项上不同。
 */
data class ProbeItem(
    val section: String,
    val name: String,
    val status: Status,
    val value: String,
    val exportValue: String = value,
)
