package io.github.gcbond.openhc.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import io.github.gcbond.openhc.BuildConfig
import io.github.gcbond.openhc.OpenHcApp
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.audio.MicPermission
import io.github.gcbond.openhc.connection.BluetoothAddressPolicy
import io.github.gcbond.openhc.connection.DisplayPlan
import io.github.gcbond.openhc.connection.UsbAutoConnect
import io.github.gcbond.openhc.connection.VideoClarity
import io.github.gcbond.openhc.diagnostics.DiagnosticsExport
import io.github.gcbond.openhc.diagnostics.DiagnosticsUploader
import io.github.gcbond.openhc.dim.DimMediaInfo
import io.github.gcbond.openhc.platform.media.AudioPreview
import io.github.gcbond.openhc.platform.media.AudioStreamChoice
import io.github.gcbond.openhc.platform.media.AudioUsage
import io.github.gcbond.openhc.platform.net.VpnConsent
import io.github.gcbond.openhc.settings.AppSettings
import io.github.gcbond.openhc.settings.AudioPreferences
import io.github.gcbond.openhc.settings.ClarityPreferences
import io.github.gcbond.openhc.settings.MediaKeyPreferences
import io.github.gcbond.openhc.settings.VideoPreferences
import io.github.gcbond.openhc.settings.WirelessPreferences
import io.github.gcbond.openhc.wireless.WirelessNetworkPlan
import io.github.gcbond.openhc.startup.BootOutcome
import io.github.gcbond.openhc.startup.BootRecord
import io.github.gcbond.openhc.startup.BootStart
import io.github.gcbond.openhc.startup.BootStartPolicy
import io.github.gcbond.openhc.connection.DisplayArea
import java.text.DateFormat
import java.util.Date

/*
 * 设置页的各个段落（4.4 fork：View 体系版，对照已删的各 Compose SettingsSection 文件）。
 *
 * 段落里需要系统弹框的（申请权限、跳系统设置页）经 [SectionHost] 交给宿主 Activity 的
 * 经典 requestPermissions / startActivityForResult 流程，结果由 SettingsScreen 路由回各段。
 */

/** 设置段落要弹系统框时需要的宿主能力（SettingsScreen 实现，转给 Activity 的经典流程）。 */
internal interface SectionHost {
    /** 经典 requestPermissions（4.4 上安装时全授予，只有 23+ 的旧机型会真的弹框）。 */
    fun requestPermission(permission: String, requestCode: Int)

    /** 经典 startActivityForResult。 */
    fun startActivityForResult(intent: Intent, requestCode: Int)
}

/** 设置页自己这一侧的请求码（1～3 是 MainActivity 连接链的）。 */
internal const val REQ_SETTINGS_MIC = 4
internal const val REQ_BOOT_NOTIFICATIONS = 5
internal const val REQ_SETTINGS_PAGE = 6

/**
 * 「画面」一组：帧率、清晰度、全屏显示，加上「按屏幕刷新节拍上屏」（原 Compose 版没有这行，
 * 字符串一直留着，4.4 fork 把它补上）。改动在下次连接时生效。
 */
internal class PictureGroup(
    private val context: Context,
    private val settings: AppSettings,
) : LinearLayout(context) {
    private val video = VideoPreferences(context)
    private val clarity = ClarityPreferences(context)

    init {
        orientation = VERTICAL
        rebuild()
    }

    fun rebuild() {
        removeAllViews()
        val screen = DisplayArea.screenFor(context)
        val group = SettingsGroup(context, context.getString(R.string.settings_group_picture))

        // 画面流畅度（帧率）：自动按屏幕刷新率，或固定 30/60。
        val fpsValues = listOf(0) + DisplayPlan.FPS_CHOICES
        group.addRowView(
            choiceRow(
                context,
                context.getString(R.string.settings_fps),
                fpsValues.map { value ->
                    if (value == 0) context.getString(R.string.settings_choice_auto)
                    else context.getString(R.string.settings_fps_fixed, value)
                },
                fpsValues.indexOf(settings.fps),
                context.getString(R.string.settings_fps_auto, screen.autoFps) + "。" +
                    context.getString(R.string.settings_fps_hint),
            ) { index ->
                settings.fps = fpsValues[index]
                rebuild()
            },
        )

        // 清晰度：决定向手机声明的目标码率。
        val entries = VideoClarity.entries
        group.addRowView(
            choiceRow(
                context,
                context.getString(R.string.settings_clarity),
                entries.map { clarityShortLabel(it) },
                entries.indexOf(clarity.clarity),
                clarityLabel(clarity.clarity) + "。" + context.getString(R.string.settings_clarity_hint),
            ) { index ->
                clarity.clarity = entries[index]
                rebuild()
            },
        )

        // 全屏显示：投屏时藏不藏系统栏。
        group.addRowView(
            switchRow(
                context,
                context.getString(R.string.settings_full_screen),
                settings.fullScreen,
                context.getString(R.string.settings_full_screen_hint),
            ) {
                settings.fullScreen = it
                rebuild()
            },
        )

        // 按屏幕刷新节拍上屏（调试，默认开）。
        group.addRowView(
            switchRow(
                context,
                context.getString(R.string.settings_video_frame_pacing),
                video.framePacing,
                context.getString(R.string.settings_video_frame_pacing_hint),
            ) {
                video.framePacing = it
            },
        )

        addView(group)
    }

    private fun clarityShortLabel(clarity: VideoClarity): String = context.getString(
        when (clarity) {
            VideoClarity.STANDARD -> R.string.settings_clarity_standard_short
            VideoClarity.HIGH -> R.string.settings_clarity_high_short
            VideoClarity.HIGHEST -> R.string.settings_clarity_highest_short
        },
    )

    private fun clarityLabel(clarity: VideoClarity): String {
        val mbps = (clarity.bitrate ?: 0) / 1_000_000
        return when (clarity) {
            VideoClarity.STANDARD -> context.getString(R.string.settings_clarity_standard)
            VideoClarity.HIGH -> context.getString(R.string.settings_clarity_high, mbps)
            VideoClarity.HIGHEST -> context.getString(R.string.settings_clarity_highest, mbps)
        }
    }
}

/** 「媒体按键」一组：把车机的媒体键转给手机（无状态，一个开关）。 */
internal fun mediaKeyGroup(context: Context): SettingsGroup =
    SettingsGroup(context, context.getString(R.string.settings_media_keys)).apply {
        val prefs = MediaKeyPreferences(context)
        addRowView(
            switchRow(
                context,
                context.getString(R.string.settings_media_keys_enabled),
                prefs.enabled,
                context.getString(R.string.settings_media_keys_hint),
            ) { prefs.enabled = it },
        )
    }

/**
 * 「连接方式」一组：启用有线连接（默认关），开着时再给「插线时不再弹 USB 授权框」（经 ReplugGate 自动放行）。
 */
internal class UsbAutoConnectGroup(private val context: Context) : LinearLayout(context) {
    private val settings = AppSettings(context)

    init {
        orientation = VERTICAL
        rebuild()
    }

    fun rebuild() {
        removeAllViews()
        val wiredEnabled = settings.wiredEnabled
        val group = SettingsGroup(context, context.getString(R.string.settings_group_connection))
        group.addRowView(
            switchRow(
                context,
                context.getString(R.string.settings_wired_enabled),
                wiredEnabled,
                context.getString(R.string.settings_wired_enabled_hint),
            ) { enabled ->
                settings.wiredEnabled = enabled
                // 关掉有线时把「只用数据线连接」一起复位：否则无线会一直停在关闭状态。
                if (!enabled) WirelessPreferences(context).wiredOnly = false
                rebuild()
            },
        )
        // 有线关着时「插线时不再弹窗」没有意义，不显示。
        if (wiredEnabled) {
            val enabled = UsbAutoConnect.isEnabled(context)
            group.addRowView(
                switchRow(
                    context,
                    context.getString(R.string.settings_usb_auto),
                    enabled,
                    context.getString(if (enabled) R.string.settings_usb_auto_hint_on else R.string.settings_usb_auto_hint_off),
                ) {
                    UsbAutoConnect.setEnabled(context, it)
                    // 以系统里的实际状态为准（切换失败时开关弹回）。
                    rebuild()
                },
            )
        }
        addView(group)
    }
}

/**
 * 「无线」一组（5.0 无线恢复）：「只用数据线连接」开关，加无线方式单选（车机热点 / 5 GHz 直连）。
 * 直连要安卓 10（API 29）起才支持：低版本上那一格压暗不可点，下面说明原因；
 * 4.4（SDK<21）上无线整体不可用，整组不挂进设置页（SettingsScreen 里判断）。
 * 组标题没有现成资源（strings 里各段的标题都有，唯独无线没有），用中文字面量。
 */
internal class WirelessSection(private val context: Context) : LinearLayout(context) {
    private val preferences = WirelessPreferences(context)

    init {
        orientation = VERTICAL
        rebuild()
    }

    fun rebuild() {
        removeAllViews()
        val group = SettingsGroup(context, "无线")

        // 只用数据线连接：启用有线时才显示，免得把无线整个关掉。
        if (AppSettings(context).wiredEnabled) {
            group.addRowView(
                switchRow(
                    context,
                    context.getString(R.string.settings_wireless_wired_only),
                    preferences.wiredOnly,
                    context.getString(R.string.settings_wireless_wired_only_hint),
                ) {
                    preferences.wiredOnly = it
                    rebuild()
                },
            )
        }

        // 无线方式：车机热点（默认）/ 5 GHz 直连（试验）。改动在下次无线连接生效。
        val directSupported = WirelessPreferences.wifiDirectSupported
        group.row {
            addView(rowHeader(context, context.getString(R.string.settings_wireless_mode), context.getString(R.string.settings_wireless_mode_hint)))
            addView(modeStrip(directSupported))
            addView(
                rowNote(
                    context,
                    if (directSupported) {
                        context.getString(R.string.settings_wireless_mode_direct_note)
                    } else {
                        // 直连不支持的说明没有现成资源。
                        "「5 GHz 直连」需安卓 10 及以上，这台不支持，只能用「车机开热点」。"
                    },
                ),
            )
        }

        // 自动连接手机（默认开）：上游把这行放在「开机自动打开」段下面，这里并入无线组；
        // 没有它这个默认开的开关就关不掉。改动的生效时机同上游——下一次无线连接的启停。
        group.addRowView(
            switchRow(
                context,
                context.getString(R.string.settings_wireless_connect_on_bt),
                preferences.connectOnBluetooth,
                context.getString(R.string.settings_wireless_connect_on_bt_hint),
            ) {
                preferences.connectOnBluetooth = it
                rebuild()
            },
        )

        // 车机蓝牙地址（手填）：系统读不到真实地址时（部分 4.4 ROM、模拟器）用它；系统读得到就忽略它。
        // 无线的二维码、握手、认证都由它推出，必须是真实地址。
        group.row {
            addView(
                rowHeader(
                    context,
                    "车机蓝牙地址",
                    "系统读不到蓝牙地址时才用。格式如 F4:26:79:DE:97:97。",
                ),
            )
            val input = EditText(context).apply {
                setSingleLine(true)
                setText(AppSettings(context).manualBluetoothAddress ?: "")
                hint = "AA:BB:CC:DD:EE:FF"
                setTextColor(Palette.Text)
                setHintTextColor(Palette.Muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                // 等宽字体 + inset 底 + lineStrong 边（原型 text-input）。
                typeface = android.graphics.Typeface.MONOSPACE
                background = solidRound(Palette.Inset, context.dp(8f).toFloat(), context.dp(1f).toFloat(), Palette.LineStrong)
                val pad = context.dp(10f)
                setPadding(pad, pad, pad, pad)
            }
            // 输入框和「保存」并排：输入框吃满剩余宽度，保存按钮贴在右侧。
            val inputRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            inputRow.addView(
                input,
                LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            inputRow.addView(
                actionButton(context, "保存", ActionKind.PRIMARY) {
                    val raw = input.text.toString().trim()
                    val normalized = if (raw.isEmpty()) null else BluetoothAddressPolicy.normalize(raw)
                    if (raw.isNotEmpty() && normalized == null) {
                        Toast.makeText(context, "地址格式不对，应形如 F4:26:79:DE:97:97", Toast.LENGTH_SHORT).show()
                        return@actionButton
                    }
                    AppSettings(context).manualBluetoothAddress = normalized
                    Toast.makeText(context, if (normalized == null) "已清除手填地址" else "已保存", Toast.LENGTH_SHORT).show()
                },
                LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginStart = context.dp(8f)
                },
            )
            addView(inputRow)
        }

        addView(group)
    }

    /**
     * 单选条：inset 底 + line 边；选中 acc 底 + accInk 粗字；未选中灰字；不支持的那格压暗不可点。
     * Widgets 的 choiceRow 不支持逐格禁用，这里照它的样子手搓一份。
     */
    private fun modeStrip(directSupported: Boolean): LinearLayout {
        val strip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = solidRound(Palette.Inset, context.dp(10f).toFloat(), context.dp(1f).toFloat(), Palette.Line)
            setPadding(context.dp(3f), context.dp(3f), context.dp(3f), context.dp(3f))
        }
        fun segment(label: CharSequence, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
            strip.addView(
                TextView(context).apply {
                    text = label
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                    gravity = android.view.Gravity.CENTER
                    val verticalPad = context.selectorVerticalPad()
                    setPadding(0, verticalPad, 0, verticalPad)
                    if (selected) {
                        setTextColor(Palette.AccInk)
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        background = solidRound(Palette.Accent, context.dp(7f).toFloat())
                    } else {
                        setTextColor(Palette.Muted)
                    }
                    if (!enabled) alpha = 0.4f
                    isClickable = enabled
                    setOnClickListener { if (enabled) onClick() }
                },
                LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    weight = 1f
                },
            )
        }
        segment(context.getString(R.string.settings_wireless_mode_hotspot), selected = !preferences.wifiDirect, enabled = true) {
            if (preferences.wifiDirect) {
                preferences.wifiDirect = false
                rebuild()
            }
        }
        segment(
            context.getString(R.string.settings_wireless_mode_direct),
            selected = preferences.wifiDirect,
            enabled = directSupported,
        ) {
            if (!preferences.wifiDirect) {
                preferences.wifiDirect = true
                rebuild()
            }
        }
        return strip
    }
}

/**
 * 「设备管理」一段：列出配对成功过的所有手机（无线投屏连成功过），按配对时间倒序，最近那台在最前。
 * 自动连接手机时会优先连最前面那台。每台可删除；删除后车机不再自动连它，需要时重新扫码配对。
 * 只读写 [WirelessPreferences]（和自动回连共用同一份记录），不碰正在运行的连接。
 */
internal class DeviceManagerSection(private val context: Context) : LinearLayout(context) {
    private val preferences = WirelessPreferences(context)
    private val timeFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

    init {
        orientation = VERTICAL
        rebuild()
    }

    fun rebuild() {
        removeAllViews()
        val group = SettingsGroup(context, context.getString(R.string.settings_devices_title))
        val devices = preferences.pairedDevices
        if (devices.isEmpty()) {
            group.row { addView(rowNote(context, context.getString(R.string.settings_devices_empty))) }
        } else {
            group.row { addView(rowDescription(context, context.getString(R.string.settings_devices_hint))) }
            devices.forEach { device -> addDeviceRow(group, device) }
        }
        addView(group)
    }

    private fun addDeviceRow(group: SettingsGroup, device: WirelessPreferences.PairedDevice) {
        val name = deviceName(device)
        group.row {
            val line = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(rowTitle(context, name))
            texts.addView(rowDescription(context, device.address))
            if (device.pairedAtMillis > 0) {
                texts.addView(
                    rowDescription(
                        context,
                        context.getString(R.string.settings_devices_paired_at, timeFormat.format(Date(device.pairedAtMillis))),
                    ),
                )
            }
            line.addView(
                texts,
                LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f },
            )
            line.addView(
                actionButton(context, context.getString(R.string.settings_devices_delete), ActionKind.DANGER) {
                    confirmDelete(device, name)
                },
                LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginStart = context.dp(12f)
                },
            )
            addView(line)
        }
    }

    /** 删除前确认：车机触屏容易误点，删了不会自动连，得重新扫码。 */
    private fun confirmDelete(device: WirelessPreferences.PairedDevice, name: String) {
        AlertDialog.Builder(context)
            .setTitle(R.string.settings_devices_delete_title)
            .setMessage(context.getString(R.string.settings_devices_delete_message, name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.settings_devices_delete) { _, _ ->
                if (preferences.forgetPhone(device.address)) {
                    Toast.makeText(context, context.getString(R.string.settings_devices_deleted, name), Toast.LENGTH_SHORT).show()
                }
                rebuild()
            }
            .show()
    }

    /** 显示名：优先用配对时记下的名称，其次现场读蓝牙名称，都读不到就显示地址。 */
    private fun deviceName(device: WirelessPreferences.PairedDevice): String {
        device.name?.takeIf { it.isNotBlank() }?.let { return it }
        return try {
            @Suppress("DEPRECATION")
            BluetoothAdapter.getDefaultAdapter()
                ?.getRemoteDevice(device.address)
                ?.name
                ?.takeIf { it.isNotBlank() }
                ?: device.address
        } catch (e: RuntimeException) {
            device.address
        }
    }
}

/**
 * 「启动」一组：开机自动打开、开机静默连接；开着时列出还要车主做的系统设置
 * （悬浮窗权限、车机自启动管理）和上次开机的结果。
 * 5.0 无线恢复：文案按上游 BootStartSettingsSection 的分支（热点支持 / 无线开关 / 插线时不再弹窗）挑，
 * 4.4 上热点不支持，全落回有线版。
 */
internal class BootStartSection(
    private val context: Context,
    private val host: SectionHost,
) : LinearLayout(context) {
    private val wirelessPreferences = WirelessPreferences(context)
    private var enabled = BootStart.isEnabled(context)
    private var silent = BootStart.isSilent(context)
    private var notifications = notificationsGranted(context)
    private var overlay = BootStart.canDrawOverlays(context)
    private val record = BootStart.lastRecord(context)
    private val installedAt = BootStart.installedAtMillis(context)
    private val bydAutostart = BootStart.bydAutostartIntent(context)

    init {
        orientation = VERTICAL
        rebuild()
    }

    fun rebuild() {
        removeAllViews()
        val vpnGranted = VpnConsent.isGranted(context)
        val group = SettingsGroup(context, context.getString(R.string.settings_boot_title))

        // 开机自动打开以后干什么：按上游 BootStartSettingsSection 的分支挑文案（5.0 无线恢复）；
        // 4.4 上 hotspotSupported 恒 false，自然全落回「等插线」两句，与砍无线时的行为一致。
        val hotspotSupported = WirelessNetworkPlan.hotspotSupported(Build.VERSION.SDK_INT)
        val wiredEnabled = AppSettings(context).wiredEnabled
        val wirelessWanted = !wirelessPreferences.wiredOnly && wirelessPreferences.connectOnBluetooth
        // 有线关着时插线入口不存在，开机也不会去等插线。
        val usbAutoConnect = wiredEnabled && UsbAutoConnect.isEnabled(context)
        val afterBoot = when {
            silent && (!wirelessWanted || !hotspotSupported) -> R.string.settings_boot_silent_wired
            !wirelessWanted -> R.string.settings_boot_wired
            usbAutoConnect -> if (silent) R.string.settings_boot_silent_wireless_both else R.string.settings_boot_wireless_both
            else -> if (silent) R.string.settings_boot_silent_wireless_blocked else R.string.settings_boot_wireless_blocked
        }
        // 静默那一行的提示：开机自动打开关着时它不起作用；开着时说还缺什么（先等插线时 VPN 授权一定用上）。
        // 先等不等插线按上游同款规则：热点不支持、或插线时不再弹窗没开而要等插线。
        val waitsForCable = wiredEnabled && (
            !hotspotSupported ||
                BootStartPolicy.waitForCable(
                    phonePlugged = false,
                    usbAutoConnect = usbAutoConnect,
                    wirelessWanted = wirelessWanted,
                )
            )
        val silentNote = when {
            !enabled -> context.getString(R.string.settings_boot_silent_needs_boot)
            !silent -> null
            waitsForCable && !vpnGranted -> context.getString(R.string.settings_boot_silent_needs_vpn)
            !notifications -> context.getString(R.string.settings_boot_silent_needs_notifications)
            else -> null
        }

        group.addRowView(
            switchRow(
                context,
                context.getString(R.string.settings_boot),
                enabled,
                context.getString(R.string.settings_boot_hint),
                note = if (enabled) context.getString(afterBoot) else null,
            ) {
                BootStart.setEnabled(context, it)
                // 以系统里的实际状态为准（切换失败时开关弹回）。
                enabled = BootStart.isEnabled(context)
                rebuild()
            },
        )
        group.addRowView(
            switchRow(
                context,
                context.getString(R.string.settings_boot_silent),
                silent,
                context.getString(R.string.settings_boot_silent_hint),
                note = silentNote,
                onNoteClick = if (enabled && silent && !notifications) {
                    {
                        openFirstAvailable(
                            listOf(notificationSettingsIntent(context), BootStart.appDetailsIntent(context)),
                        )
                    }
                } else {
                    null
                },
                enabled = enabled,
            ) {
                BootStart.setSilent(context, it)
                silent = it
                if (it && !notifications) {
                    try {
                        host.requestPermission(POST_NOTIFICATIONS, REQ_BOOT_NOTIFICATIONS)
                    } catch (e: ActivityNotFoundException) {
                        // 系统没有权限申请页：这一行的提示会让车主去系统设置里开。
                    }
                }
                rebuild()
            },
        )

        // 两个开关都关着时，只留比亚迪的放行入口（有那个对话框的车机）。
        if (!enabled) {
            if (bydAutostart != null) bydAutostartRow(group)
            addView(group)
            return
        }

        // 悬浮窗权限：安卓 10 起开机时后台打开界面要它。
        when {
            !BootStartPolicy.overlayRequired(Build.VERSION.SDK_INT) -> group.row {
                addView(rowDescription(context, context.getString(R.string.settings_boot_overlay_not_needed, Build.VERSION.RELEASE)))
            }
            overlay -> group.row { addView(rowDescription(context, context.getString(R.string.settings_boot_overlay_granted))) }
            else -> group.row {
                addView(
                    rowNote(
                        context,
                        context.getString(
                            if (silent) R.string.settings_boot_overlay_missing_silent
                            else R.string.settings_boot_overlay_missing,
                        ),
                        warn = true,
                        onClick = {
                            openFirstAvailable(
                                listOf(BootStart.overlaySettingsIntent(context), BootStart.appDetailsIntent(context)),
                            )
                        },
                    ),
                )
            }
        }
        // 车机系统的自启动管理：比亚迪直接打开它的对话框；别的车机只能说明，给个本应用系统设置页的入口。
        if (bydAutostart != null) {
            bydAutostartRow(group)
        } else {
            group.row {
                addView(autoStartRow())
            }
        }
        // 上次开机的结果：这次安装后还没见过开机广播的话，说明自启动被系统拦了。
        val seen = BootStartPolicy.bootSeenSinceInstall(record, installedAt)
        if (record != null || !seen) {
            group.row {
                if (record != null) {
                    addView(
                        TextView(context).apply {
                            text = lastBootText(record)
                            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (context.compactLayout()) 12f else 13f)
                            setTextColor(Palette.Text)
                        },
                    )
                }
                if (!seen) {
                    addView(rowDescription(context, context.getString(R.string.settings_boot_not_seen)))
                }
            }
        }
        addView(group)
    }

    /** 权限申请回来（安卓 13 起的通知权限）：重查后整组刷新。 */
    fun onNotificationPermissionResult() {
        notifications = notificationsGranted(context)
        rebuild()
    }

    /** 从系统设置页回来：重查悬浮窗和通知权限。 */
    fun onSettingsPageResult() {
        overlay = BootStart.canDrawOverlays(context)
        notifications = notificationsGranted(context)
        rebuild()
    }

    /** 比亚迪车机的后台启动放行：说明 + 直接打开它的「选择禁止后台启动项」对话框。 */
    private fun bydAutostartRow(group: SettingsGroup) {
        group.row {
            addView(autoStartLine(R.string.settings_boot_byd_title, R.string.settings_boot_byd_note, R.string.settings_boot_open_byd))
        }
    }

    /** 别的车机：说明 + 本应用系统设置页入口。 */
    private fun autoStartRow(): LinearLayout =
        LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }.apply {
            addView(autoStartLine(R.string.settings_boot_autostart_title, R.string.settings_boot_autostart_note, R.string.settings_boot_open_app_settings))
        }

    private fun autoStartLine(titleRes: Int, noteRes: Int, buttonRes: Int): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                rowHeader(context, context.getString(titleRes), context.getString(noteRes)),
                LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f },
            )
            addView(
                actionButton(context, context.getString(buttonRes)) {
                    openFirstAvailable(
                        listOf(bydAutostart, BootStart.appDetailsIntent(context)).filterNotNull(),
                    )
                },
            )
        }

    /** 逐个试：第一个能打开的系统设置页（比亚迪对话框、本应用详情页等）。全打不开就告诉车主。 */
    private fun openFirstAvailable(intents: List<Intent>) {
        val opened = intents.any { intent ->
            try {
                host.startActivityForResult(intent, REQ_SETTINGS_PAGE)
                true
            } catch (e: ActivityNotFoundException) {
                false
            }
        }
        if (!opened) {
            Toast.makeText(context, R.string.settings_boot_no_settings_page, Toast.LENGTH_LONG).show()
        }
    }

    /** 上次开机一行：时间、冷启还是重发、结果。 */
    private fun lastBootText(record: BootRecord): String {
        val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(record.atMillis))
        val kind = if (BootStartPolicy.isFreshBoot(record.sinceBootMillis)) {
            context.getString(R.string.settings_boot_fresh)
        } else {
            context.getString(R.string.settings_boot_resent, record.sinceBootMillis / 60_000)
        }
        val outcome = context.getString(
            when (record.outcome) {
                BootOutcome.PENDING -> R.string.settings_boot_outcome_pending
                BootOutcome.OPENED -> R.string.settings_boot_outcome_opened
                BootOutcome.COVERED -> R.string.settings_boot_outcome_covered
                BootOutcome.BLOCKED_NO_OVERLAY -> R.string.settings_boot_outcome_no_overlay
                BootOutcome.NOT_SHOWN -> R.string.settings_boot_outcome_not_shown
                BootOutcome.FAILED -> R.string.settings_boot_outcome_failed
                BootOutcome.SILENT_WIRED -> R.string.settings_boot_outcome_silent_wired
                BootOutcome.SILENT_WIRELESS -> R.string.settings_boot_outcome_silent_wireless
                BootOutcome.SILENT_STANDBY -> R.string.settings_boot_outcome_silent_standby
            },
        )
        return context.getString(R.string.settings_boot_last, time, kind, outcome)
    }

    /** 能显示通知：安卓 13 以前不用申请（4.4 恒真）。 */
    private fun notificationsGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /**
     * 本应用的通知设置页（安卓 8.0 起有；车机没有这一页时退到应用详情页）。只在安卓 13 起缺通知权限时用到；
     * 动作名、参数名直接写字符串：常量只在新版本 SDK 里有，值本身只是字符串。
     */
    private fun notificationSettingsIntent(context: Context): Intent =
        Intent("android.settings.APP_NOTIFICATION_SETTINGS").putExtra("android.provider.extra.APP_PACKAGE", context.packageName)
}

/**
 * 「声音」一组：总开关、声音延后、导航/媒体两路的声道选择（带试听）、车机麦克风（开关 + 权限状态）、
 * 按手机记下的 AAC 回退。试听在 AudioPreview 自己的线程上放完回调，回界面线程再刷新。
 */
internal class AudioSection(
    private val context: Context,
    private val host: SectionHost,
) : LinearLayout(context) {
    private val prefs = AudioPreferences(context)
    private val main = Handler(Looper.getMainLooper())

    /** 两路试听各自的界面状态（导航、媒体）。 */
    private inner class StreamUi(val usage: AudioUsage) {
        var previewing = false
        var note: PreviewNote? = null
        var previewButton: TextView? = null
        var noteText: TextView? = null
        var chips: HorizontalScrollView? = null
    }

    private val streams = AudioUsage.entries.associateWith { StreamUi(it) }

    init {
        orientation = VERTICAL
        rebuild()
    }

    fun rebuild() {
        removeAllViews()
        val group = SettingsGroup(context, context.getString(R.string.settings_audio))

        // 把手机的声音放到车机上。
        group.addRowView(
            switchRow(
                context,
                context.getString(R.string.settings_audio_enabled),
                prefs.enabled,
                context.getString(R.string.settings_audio_hint),
            ) { prefs.enabled = it },
        )

        // 声音延后（毫秒）。
        val delays = AudioPreferences.DELAY_CHOICES
        group.addRowView(
            choiceRow(
                context,
                context.getString(R.string.settings_audio_delay),
                delays.map { value ->
                    if (value == 0) context.getString(R.string.settings_audio_delay_min)
                    else context.getString(R.string.settings_audio_delay_plus, value)
                },
                delays.indexOf(prefs.delayMillis),
                context.getString(R.string.settings_audio_delay_hint),
            ) { index -> prefs.delayMillis = delays[index] },
        )

        // 导航、媒体两路：选声道 + 试听。
        addStreamRow(group, AudioUsage.NAVIGATION, R.string.settings_audio_navigation_stream, R.string.settings_audio_navigation_stream_hint)
        addStreamRow(group, AudioUsage.MEDIA, R.string.settings_audio_media_stream, R.string.settings_audio_media_stream_hint)

        // 车机麦克风（打电话、语音助手）：开关 + 录音权限状态（4.4 安装时授予，恒显示已允许）。
        group.addRowView(
            switchRow(
                context,
                context.getString(R.string.settings_mic_enabled),
                prefs.micEnabled,
                context.getString(R.string.settings_mic_hint),
            ) {
                prefs.micEnabled = it
            },
        )
        group.addRowView(micPermissionRow())

        // AAC 回退列表：PCM 下不出声的手机，记下来改声明 AAC；这里可以恢复。
        group.row {
            addView(rowHeader(context, context.getString(R.string.settings_audio_fallback_title), context.getString(R.string.settings_audio_fallback_help)))
            val records = prefs.pcmFallbacks().records.filter { it.useAac }
            if (records.isEmpty()) {
                addView(rowDescription(context, context.getString(R.string.settings_audio_fallback_none)))
                return@row
            }
            for (record in records) {
                val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                val name = record.name ?: context.getString(R.string.settings_audio_fallback_unnamed)
                val date = DateFormat.getDateInstance(DateFormat.SHORT).format(Date(record.updatedAtMillis))
                line.addView(
                    rowDescription(context, context.getString(R.string.settings_audio_fallback_item, name, record.key.take(6), record.lastFailure ?: "", date)),
                    LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f },
                )
                line.addView(
                    actionButton(context, context.getString(R.string.settings_audio_fallback_reset)) {
                        prefs.forgetPcmFallback(record.key)
                        rebuild()
                    },
                )
                addView(line)
            }
        }
        addView(group)
    }

    /** 录音权限回来后重查这一行。 */
    fun onMicPermissionResult() {
        rebuild()
    }

    /** 麦克风权限状态行：已授予给 ok 淡底胶囊（原型 perm-chip）；没有时给出申请入口。 */
    private fun micPermissionRow(): View =
        if (MicPermission.granted(context)) {
            TextView(context).apply {
                text = context.getString(R.string.settings_mic_permission_granted)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Palette.Ok)
                background = solidRound(Palette.OkSoft, context.dp(20f).toFloat())
                val px = context.dp(10f)
                val py = context.dp(4f)
                setPadding(px, py, px, py)
            }
        } else {
            rowNote(
                context,
                context.getString(R.string.settings_mic_permission_missing),
                warn = true,
                onClick = { host.requestPermission(Manifest.permission.RECORD_AUDIO, REQ_SETTINGS_MIC) },
            )
        }

    /** 一路声音：说明 + 试听按钮、试听结果提示、声道号选择条。 */
    private fun addStreamRow(group: SettingsGroup, usage: AudioUsage, titleRes: Int, hintRes: Int) {
        val ui = streams.getValue(usage)
        val row = group.row()
        val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        line.addView(
            rowHeader(context, context.getString(titleRes), context.getString(hintRes)),
            LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f },
        )
        ui.previewButton = actionButton(
            context,
            context.getString(R.string.settings_audio_preview),
            enabled = !ui.previewing,
        ) { startPreview(ui) }
        line.addView(ui.previewButton!!)
        row.addView(line)
        ui.noteText = TextView(context).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (context.compactLayout()) 12f else 13f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        row.addView(ui.noteText)
        ui.chips = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        row.addView(ui.chips)
        renderStream(ui)
    }

    private fun startPreview(ui: StreamUi) {
        if (ui.previewing) return
        ui.previewing = true
        ui.note = null
        renderStream(ui)
        val stream = if (ui.usage == AudioUsage.MEDIA) prefs.mediaStream else prefs.navigationStream
        AudioPreview.play(ui.usage, stream, prefs.lowLatency) { result ->
            Log.i(
                "OpenHC-Audio",
                "试听${ui.usage.label}：声道 ${AudioStreamChoice.label(result.stream)}，" +
                    "${result.route.ifEmpty { "按用途建" }}${result.error?.let { "；放不出来：$it" } ?: ""}",
            )
            main.post {
                ui.previewing = false
                ui.note = previewNote(context, result)
                renderStream(ui)
            }
        }
    }

    /** 按这一路的当前选择刷新试听按钮、提示、声道选择条。 */
    private fun renderStream(ui: StreamUi) {
        ui.previewButton?.let { button ->
            button.text = context.getString(
                if (ui.previewing) R.string.settings_audio_preview_playing else R.string.settings_audio_preview,
            )
            button.isClickable = !ui.previewing
            button.alpha = if (ui.previewing) 0.4f else 1f
        }
        ui.noteText?.let { noteText ->
            val note = ui.note
            if (note == null) {
                noteText.text = ""
                noteText.visibility = android.view.View.GONE
            } else {
                noteText.text = note.text
                noteText.setTextColor(if (note.warn) Palette.Warn else Palette.Muted)
                noteText.visibility = android.view.View.VISIBLE
            }
        }
        ui.chips?.let { scroll -> renderChips(ui, scroll) }
    }

    /** 声道选择条：「自动」加 1～20 号，inset 底 + line 边，选中 acc 底 + accInk 粗字（数字等宽）。 */
    private fun renderChips(ui: StreamUi, scroll: HorizontalScrollView) {
        val strip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = solidRound(Palette.Inset, context.dp(10f).toFloat(), context.dp(1f).toFloat(), Palette.Line)
            setPadding(context.dp(3f), context.dp(3f), context.dp(3f), context.dp(3f))
        }
        val selected = if (ui.usage == AudioUsage.MEDIA) prefs.mediaStream else prefs.navigationStream
        val choices = listOf(AudioStreamChoice.AUTO) + (AudioStreamChoice.MIN..AudioStreamChoice.MAX)
        choices.forEachIndexed { index, value ->
            strip.addView(
                TextView(context).apply {
                    text = if (value == AudioStreamChoice.AUTO) {
                        context.getString(R.string.settings_audio_stream_auto)
                    } else {
                        value.toString()
                    }
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                    gravity = android.view.Gravity.CENTER
                    // 和其他选择组件（无线方式、画面流畅度等）保持一致，统一由 selectorVerticalPad 控制高度。
                    val verticalPad = context.selectorVerticalPad()
                    setPadding(0, verticalPad, 0, verticalPad)
                    isClickable = true
                    if (value != AudioStreamChoice.AUTO) typeface = android.graphics.Typeface.MONOSPACE
                    if (value == selected) {
                        setTextColor(Palette.AccInk)
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        background = solidRound(Palette.Accent, context.dp(7f).toFloat())
                    } else {
                        setTextColor(Palette.Muted)
                    }
                    setOnClickListener {
                        if (ui.usage == AudioUsage.MEDIA) prefs.mediaStream = value else prefs.navigationStream = value
                        ui.note = null
                        renderStream(ui)
                    }
                },
                LinearLayout.LayoutParams(context.dp(52f), android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = context.dp(2f)
                },
            )
        }
        scroll.removeAllViews()
        scroll.addView(strip, android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
        // 打开时滚到选中那一格附近。
        val chipWidth = context.dp(52f) + context.dp(2f)
        scroll.post { scroll.scrollTo(maxOf(0, choices.indexOf(selected) - 2) * chipWidth, 0) }
    }
}

/** 试听结果的提示。 */
internal class PreviewNote(val text: String, val warn: Boolean)

private fun previewNote(context: Context, result: AudioPreview.Result): PreviewNote = when {
    result.error != null ->
        PreviewNote(context.getString(R.string.settings_audio_preview_failed, result.error), warn = true)
    result.streamRejected ->
        PreviewNote(context.getString(R.string.settings_audio_preview_rejected, result.stream), warn = true)
    result.stream == AudioStreamChoice.AUTO ->
        PreviewNote(context.getString(R.string.settings_audio_preview_auto), warn = false)
    else ->
        PreviewNote(context.getString(R.string.settings_audio_preview_stream, result.stream), warn = false)
}

/** 「诊断」一组：导出/上传诊断日志（都阻塞，放后台线程，完事回界面线程）。 */
internal class DiagnosticsSection(private val context: Context) : LinearLayout(context) {
    private val main = Handler(Looper.getMainLooper())
    private var busy = false
    private var uploading = false
    private var note: PreviewNote? = null
    private var uploadNote: PreviewNote? = null

    /** 调试包的仪表盘推送测试：轮换几条示例，方便在车机上看仪表盘是否跟着变。 */
    private var dimDemoIndex = 0
    private var dimNote: String? = null

    init {
        orientation = VERTICAL
        rebuild()
    }

    fun rebuild() {
        removeAllViews()
        val group = SettingsGroup(context, context.getString(R.string.settings_group_diagnostics))
        group.row {
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            line.addView(
                rowHeader(context, context.getString(R.string.settings_diag_export), context.getString(R.string.settings_diag_export_hint)),
                LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f },
            )
            line.addView(
                actionButton(
                    context,
                    context.getString(
                        if (busy) R.string.settings_diag_exporting else R.string.settings_diag_export_button,
                    ),
                    enabled = !busy,
                ) { startExport() },
            )
            addView(line)
            note?.let { addView(rowNote(context, it.text, it.warn)) }
        }
        if (BuildConfig.QINIU_BUCKET.isNotBlank() && BuildConfig.QINIU_ACCESS_KEY.isNotBlank() && BuildConfig.QINIU_SECRET_KEY.isNotBlank()) {
            group.row {
                val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                line.addView(
                    rowHeader(
                        context,
                        context.getString(R.string.settings_diag_upload),
                        context.getString(R.string.settings_diag_upload_hint),
                    ),
                    LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f },
                )
                line.addView(
                    actionButton(
                        context,
                        context.getString(
                            if (uploading) R.string.settings_diag_uploading else R.string.settings_diag_upload_button,
                        ),
                        enabled = !uploading,
                    ) { startUpload() },
                )
                addView(line)
                uploadNote?.let { addView(rowNote(context, it.text, it.warn)) }
            }
        }
        if (BuildConfig.DEBUG) {
            group.row {
                val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                line.addView(
                    rowHeader(context, "仪表盘多媒体测试", "仅调试包：按「在线」媒体类型推一条信息到仪表盘"),
                    LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f },
                )
                line.addView(actionButton(context, "推送") { pushDimDemo() })
                addView(line)
                dimNote?.let { addView(rowNote(context, it, warn = false)) }
            }
        }
        addView(group)
    }

    /** 把一条示例媒体信息推到仪表盘（媒体类型 6 = 在线）。 */
    private fun pushDimDemo() {
        val app = context.applicationContext as? OpenHcApp ?: return
        val demos = arrayOf(
            Triple("OpenHC 测试歌曲", "OpenHC 歌手", "测试专辑"),
            Triple("晴天", "周杰伦", "叶惠美"),
            Triple("夜曲", "周杰伦", "十一月的萧邦"),
        )
        val demo = demos[dimDemoIndex % demos.size]
        dimDemoIndex++
        app.dimMedia.update(
            DimMediaInfo(
                title = demo.first,
                artist = demo.second,
                album = demo.third,
                durationMs = 269_000,
                positionMs = 0,
                playing = true,
            ),
        )
        dimNote = "已推送：${demo.first}（媒体类型 6 在线）"
        rebuild()
    }

    private fun startUpload() {
        if (uploading) return
        uploading = true
        uploadNote = null
        rebuild()
        val app = context.applicationContext ?: context
        Thread({
            val result = DiagnosticsUploader.upload(app)
            main.post {
                uploading = false
                uploadNote = when (result) {
                    is DiagnosticsUploader.Result.Uploaded ->
                        PreviewNote(context.getString(R.string.settings_diag_uploaded, result.code), warn = false)
                    is DiagnosticsUploader.Result.Failed ->
                        PreviewNote(context.getString(R.string.settings_diag_upload_failed, result.reason), warn = true)
                }
                rebuild()
            }
        }, "OpenHC-DiagUpload").start()
    }

    private fun startExport() {
        if (busy) return
        busy = true
        note = null
        rebuild()
        val app = context.applicationContext ?: context
        Thread({
            val result = DiagnosticsExport.export(app)
            main.post {
                busy = false
                note = when (result) {
                    is DiagnosticsExport.Result.Saved -> PreviewNote(context.getString(R.string.settings_diag_saved, result.location), warn = false)
                    is DiagnosticsExport.Result.Failed -> PreviewNote(context.getString(R.string.settings_diag_failed, result.reason), warn = true)
                }
                rebuild()
            }
        }, "OpenHC-DiagExport").start()
    }
}

/** 直接写权限名：常量只在新版本 SDK 里有，值本身只是字符串。 */
internal const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

/** 设置页各段共用的「宿主就是 Activity」实现（4.4 fork：经典流程）。 */
internal class ActivitySectionHost(private val activity: Activity) : SectionHost {
    override fun requestPermission(permission: String, requestCode: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            activity.requestPermissions(arrayOf(permission), requestCode)
        }
    }

    override fun startActivityForResult(intent: Intent, requestCode: Int) {
        activity.startActivityForResult(intent, requestCode)
    }
}
