package io.github.gcbond.openhc.btprobe

import android.os.Build
import java.lang.reflect.InvocationTargetException

/** 一次尝试的结果：拿到的值或抛出的异常，以及这次尝试期间系统报出的隐藏接口访问（成员签名）。 */
class Attempt<out T>(val value: T?, val error: Throwable?, val hiddenApiHits: List<String>)

/**
 * 执行 [block]，收下结果或异常。反射调用包着的真实异常会被剥出来。
 * 每次尝试最好只涉及一个隐藏成员，这样报出的访问能对上号。
 */
inline fun <T> attempt(block: () -> T): Attempt<T> {
    val mark = HiddenApiWatcher.mark()
    return try {
        val value = block()
        Attempt(value, null, HiddenApiWatcher.hitsSince(mark))
    } catch (e: Exception) {
        Attempt(null, unwrap(e), HiddenApiWatcher.hitsSince(mark))
    } catch (e: LinkageError) {
        Attempt(null, e, HiddenApiWatcher.hitsSince(mark))
    }
}

fun unwrap(e: Throwable): Throwable =
    if (e is InvocationTargetException) e.targetException ?: e else e

/** 把异常写成「类名：信息 → 原因」。找不到成员时，借助隐藏接口访问记录区分「被拦」和「没有」。 */
fun describeError(attempt: Attempt<*>): String {
    val e = attempt.error ?: return "没有异常，但也没拿到值"
    val base = e.javaClass.simpleName + (e.message?.takeIf { it.isNotBlank() }?.let { "：$it" } ?: "")
    val why = when (e) {
        is NoSuchFieldException, is NoSuchMethodException -> when {
            Build.VERSION.SDK_INT < 28 ->
                "这个版本没有该成员（安卓 9 以下没有隐藏接口限制）"
            attempt.hiddenApiHits.isNotEmpty() ->
                "成员存在，被隐藏接口限制拦下（系统报告了访问：${attempt.hiddenApiHits.joinToString()}）"
            HiddenApiWatcher.calibrated == true ->
                "系统没报告这次访问：多半是这个版本没有该成员（按这个签名）。" +
                    "「拦下的访问也会上报」未经真机验证，以 logcat 为准"
            else ->
                "分不清是被拦还是没有该成员（应用内监听没生效），看 logcat"
        }
        is SecurityException -> "权限不足"
        else -> null
    }
    return if (why == null) base else "$base → $why"
}

/** 反射成功时，如果这次访问被系统记为隐藏接口访问，补一句说明。 */
fun accessNote(attempt: Attempt<*>): String =
    if (attempt.hiddenApiHits.isEmpty()) "" else "（隐藏接口，已放行：${attempt.hiddenApiHits.joinToString()}）"
