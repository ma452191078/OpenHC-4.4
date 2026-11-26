package io.github.gcbond.openhc.platform.net

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log

/**
 * 有线连接前要先拿到用户的 VPN 授权（[WiredVpnService] 建 TUN 需要）。授权是持久的，同意一次之后不再弹框，
 * 但别的 VPN 应用被用户启用后，本应用的授权会被取代，需要重新同意。
 *
 * 应用层用法（在开始有线会话之前）：
 * ```
 * when (val status = VpnConsent.check(context)) {
 *     VpnConsent.Status.Granted -> 开始会话
 *     is VpnConsent.Status.NeedsUserConsent -> 用 Activity 拉起 status.intent，结果为 RESULT_OK 即同意
 *     is VpnConsent.Status.Unavailable -> 提示这台车机不能用有线连接
 * }
 * ```
 * 用 ComponentActivity 时可以 `registerForActivityResult(ActivityResultContracts.StartActivityForResult())`
 * 拉起 `status.intent`；也可以直接用 [request]。
 */
object VpnConsent {

    private const val TAG = "OpenHC-Vpn"

    /**
     * 4.4 fork：AOSP 4.4 的 VPN 授权确认框固定是这个组件，`VpnService.prepare` 返回的 Intent 只是把系统资源
     * `config_customVpnConfirmDialogComponent` 解析成组件名。车机 ROM 把这个资源改成了自家页面、而那个页面又被裁掉时，
     * prepare 给的 Intent 要么打不开（提示「系统里没有 VPN 授权页」），要么打开的是「VPN 设置」页（页面上写着「无 VPN」，
     * 在那里没法授权）。所以 19 上直接优先用 AOSP 的确认框。
     */
    private val LEGACY_CONFIRM = ComponentName("com.android.vpndialogs", "com.android.vpndialogs.ConfirmDialog")

    sealed class Status {
        /** 已授权。 */
        object Granted : Status()

        /** 需要用户在系统框里同意：用 Activity 拉起 [intent]，结果为 [Activity.RESULT_OK] 即同意。 */
        class NeedsUserConsent(val intent: Intent) : Status()

        /** 系统不让用 VPN（例如设备策略禁止、正处于别的 VPN 的锁定模式）。 */
        class Unavailable(val reason: String) : Status()
    }

    fun check(context: Context): Status = try {
        val intent = VpnService.prepare(context)
        if (intent == null) {
            Log.i(TAG, "VPN 已授权")
            Status.Granted
        } else {
            Log.i(
                TAG,
                "VPN 未授权，系统给的授权页 ${describe(intent)}，可解析=${intent.resolveActivity(context.packageManager) != null}",
            )
            Status.NeedsUserConsent(intent)
        }
    } catch (e: RuntimeException) {
        Log.w(TAG, "查询 VPN 授权失败：${e.javaClass.simpleName}: ${e.message}")
        Status.Unavailable("${e.javaClass.simpleName}: ${e.message}")
    }

    fun isGranted(context: Context): Boolean = check(context) === Status.Granted

    /**
     * 已授权返回 [Status.Granted]；否则用 [activity] 拉起系统授权框（结果在 `onActivityResult(requestCode, …)`），
     * 返回 [Status.NeedsUserConsent]。车机系统里没有授权框时返回 [Status.Unavailable]。
     */
    fun request(activity: Activity, requestCode: Int): Status {
        val status = check(activity)
        if (status is Status.NeedsUserConsent) {
            if (!launch(activity, requestCode, status.intent)) {
                return Status.Unavailable("系统里没有可用的 VPN 授权页")
            }
        }
        return status
    }

    /** 依次尝试候选授权页；第一个能拉起的返回 true。 */
    private fun launch(activity: Activity, requestCode: Int, systemIntent: Intent): Boolean {
        for (candidate in candidates(activity, systemIntent)) {
            try {
                Log.i(TAG, "打开 VPN 授权页：${describe(candidate)}")
                activity.startActivityForResult(candidate, requestCode)
                return true
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "VPN 授权页不存在：${describe(candidate)}")
            } catch (e: SecurityException) {
                Log.w(TAG, "打不开 VPN 授权页：${describe(candidate)}：${e.message}")
            }
        }
        return false
    }

    /** 4.4 上优先 AOSP 的确认框，它不在时再退回系统给的那一页；5.0 起行为不变。 */
    private fun candidates(context: Context, systemIntent: Intent): List<Intent> {
        if (Build.VERSION.SDK_INT >= 21) return listOf(systemIntent)
        val legacy = Intent().setComponent(LEGACY_CONFIRM)
        return if (legacy.resolveActivity(context.packageManager) != null) listOf(legacy, systemIntent) else listOf(systemIntent)
    }

    private fun describe(intent: Intent): String =
        intent.component?.flattenToShortString() ?: intent.action ?: intent.toString()
}
