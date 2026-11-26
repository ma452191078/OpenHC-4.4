package io.github.gcbond.openhc.ui

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import io.github.gcbond.openhc.BuildConfig
import io.github.gcbond.openhc.OpenHcApp
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.settings.UpdatePreferences
import io.github.gcbond.openhc.update.UpdatePhase
import io.github.gcbond.openhc.update.UpdateUiState

/**
 * 设置「更新」段：当前版本、检查更新、新版本下载安装，以及两个更新开关。
 * 状态由 [io.github.gcbond.openhc.update.UpdateManager] 主线程回调，进来时挂监听，离开释放。
 */
internal class UpdateSection(private val context: Context) : LinearLayout(context) {

    private val settings = UpdatePreferences(context)
    private val manager = (context.applicationContext as? OpenHcApp)?.updater

    private var state = UpdateUiState()

    /** 监听只在挂窗口期间挂一份，重复 attach 不重复注册。 */
    private var listening = false

    private val listener: (UpdateUiState) -> Unit = { next ->
        state = next
        rebuild()
    }

    init {
        orientation = VERTICAL
        rebuild()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!listening) {
            listening = true
            manager?.addListener(listener)
        }
    }

    override fun onDetachedFromWindow() {
        if (listening) {
            listening = false
            manager?.removeListener(listener)
        }
        super.onDetachedFromWindow()
    }

    private fun rebuild() {
        removeAllViews()
        addView(versionGroup(), groupParams(0f))
        addView(optionsGroup(), groupParams(14f))
    }

    private fun versionGroup(): SettingsGroup {
        val group = SettingsGroup(context, context.getString(R.string.settings_group_update))
        val phase = state.phase
        val busy = phase == UpdatePhase.CHECKING || phase == UpdatePhase.DOWNLOADING || phase == UpdatePhase.VERIFYING

        group.row {
            val line = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            line.addView(
                rowHeader(
                    context,
                    context.getString(R.string.update_current_version, BuildConfig.VERSION_NAME),
                    statusText(phase),
                ),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f },
            )
            line.addView(
                actionButton(
                    context,
                    context.getString(if (phase == UpdatePhase.CHECKING) R.string.update_checking else R.string.update_check),
                    enabled = !busy,
                ) { manager?.check() },
            )
            addView(line)
        }

        val release = state.release
        if (phase == UpdatePhase.AVAILABLE && release != null) {
            group.row {
                val line = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                line.addView(
                    rowHeader(
                        context,
                        context.getString(R.string.update_available, release.version),
                        if (release.sizeBytes > 0) {
                            context.getString(R.string.update_available_hint) + " · " + formatApkSize(release.sizeBytes)
                        } else {
                            context.getString(R.string.update_available_hint)
                        },
                    ),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f },
                )
                line.addView(
                    actionButton(context, context.getString(R.string.update_download_install), ActionKind.PRIMARY) {
                        manager?.downloadAndInstall()
                    },
                )
                addView(line)
            }
        }

        if (phase == UpdatePhase.DOWNLOADING || phase == UpdatePhase.VERIFYING) {
            group.row {
                val line = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                line.addView(
                    rowTitle(
                        context,
                        if (phase == UpdatePhase.DOWNLOADING) {
                            context.getString(R.string.update_downloading, state.progress)
                        } else {
                            context.getString(R.string.update_verifying)
                        },
                    ),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f },
                )
                line.addView(
                    actionButton(context, context.getString(R.string.update_cancel_download)) {
                        manager?.cancelDownload()
                    },
                )
                addView(line)
            }
        }

        if (phase == UpdatePhase.FAILED && state.needsInstallPermission) {
            group.row {
                val line = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                line.addView(
                    rowTitle(context, context.getString(R.string.update_failed_install_permission)),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f },
                )
                line.addView(
                    actionButton(context, context.getString(R.string.update_open_install_permission), ActionKind.PRIMARY) {
                        manager?.openInstallPermissionSettings()
                    },
                )
                addView(line)
            }
        }
        return group
    }

    private fun optionsGroup(): SettingsGroup {
        val group = SettingsGroup(context, context.getString(R.string.settings_group_update_options))
        group.row {
            addView(
                switchRow(
                    context,
                    context.getString(R.string.update_auto_check),
                    checked = settings.autoCheck,
                    description = context.getString(R.string.update_auto_check_desc),
                ) { enabled -> settings.autoCheck = enabled },
            )
        }
        group.row {
            addView(
                switchRow(
                    context,
                    context.getString(R.string.update_notify_dialog),
                    checked = settings.notifyDialog,
                    description = context.getString(R.string.update_notify_dialog_desc),
                ) { enabled -> settings.notifyDialog = enabled },
            )
        }
        group.row {
            addView(rowNote(context, context.getString(R.string.update_network_note)))
        }
        return group
    }

    /** 主状态文案：结论 +（失败时的）原因细节。 */
    private fun statusText(phase: UpdatePhase): String? {
        val messageRes = state.messageRes
        val detail = state.detail
        val base = when (phase) {
            UpdatePhase.IDLE -> null
            UpdatePhase.CHECKING -> context.getString(R.string.update_checking)
            UpdatePhase.UP_TO_DATE -> context.getString(R.string.update_up_to_date)
            // 有新版本时下面单独一行说，不再重复。
            UpdatePhase.AVAILABLE -> null
            UpdatePhase.DOWNLOADING -> context.getString(R.string.update_downloading, state.progress)
            UpdatePhase.VERIFYING -> context.getString(R.string.update_verifying)
            UpdatePhase.INSTALLING -> context.getString(R.string.update_install_started)
            UpdatePhase.FAILED -> messageRes?.let { context.getString(it) }
        }
        if (phase == UpdatePhase.FAILED && base != null) {
            return if (detail.isNullOrBlank()) base else base + context.getString(R.string.update_detail, detail)
        }
        return base
    }

    private fun groupParams(topDp: Float): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(topDp)
        }
}
