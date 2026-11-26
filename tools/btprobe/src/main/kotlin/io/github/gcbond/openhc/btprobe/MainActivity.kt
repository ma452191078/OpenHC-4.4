package io.github.gcbond.openhc.btprobe

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val denied = result.filterValues { granted -> !granted }.keys
            ProbeSession.permissionHint = if (denied.isEmpty()) {
                null
            } else {
                "没有授予：${denied.joinToString("、") { it.substringAfterLast('.') }}。蓝牙相关的检测会失败（结果里记为异常）；" +
                    "可以在系统设置里给本应用打开「附近的设备」权限后再测。"
            }
            // 拒绝了也照样检测：系统信息、地址属性等不依赖这些权限，权限被拒本身也会如实记进结果。
            ProbeSession.start(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val targetSdk = applicationInfo.targetSdkVersion
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ProbeScreen(
                        targetSdk = targetSdk,
                        onStart = ::startProbe,
                        onStopWaiting = ProbeSession::stopWaiting,
                        onCopy = ::copyResults,
                        onSave = ::saveResults,
                        onOpenSettings = ::openAppSettings,
                    )
                }
            }
        }
    }

    private fun startProbe() {
        val missing = missingRuntimePermissions()
        if (missing.isEmpty()) {
            ProbeSession.permissionHint = null
            ProbeSession.start(this)
        } else {
            permissionRequest.launch(missing.toTypedArray())
        }
    }

    /** 安卓 12 起按实际授权状态申请，不按风味区分：两个风味走同一套逻辑，对照才干净。 */
    private fun missingRuntimePermissions(): List<String> {
        if (Build.VERSION.SDK_INT < 31) return emptyList()
        return BtProbe.RUNTIME_PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    private fun copyResults() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        if (clipboard == null) {
            toast("拿不到剪贴板")
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("OpenHC 蓝牙探针结果", ProbeSession.exportText(this)))
        toast("已复制全部结果（地址已打码）")
    }

    /** 存到应用自己的外部存储目录（Android/data/<包名>/files），不需要存储权限。 */
    private fun saveResults() {
        val dir = getExternalFilesDir(null)
        if (dir == null) {
            ProbeSession.notice = "外部存储现在不可用，没法保存；可以先用「复制全部结果」。"
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "btprobe-${BuildConfig.FLAVOR}-$stamp.txt")
        ProbeSession.notice = try {
            file.writeText(ProbeSession.exportText(this), Charsets.UTF_8)
            "已保存（地址已打码）：${file.absolutePath}"
        } catch (e: IOException) {
            "保存失败：${e.message}"
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast("这台设备打不开应用详情页，请到系统设置里手动找到本应用")
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun ProbeScreen(
    targetSdk: Int,
    onStart: () -> Unit,
    onStopWaiting: () -> Unit,
    onCopy: () -> Unit,
    onSave: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val items = ProbeSession.items
    val running = ProbeSession.running
    val waitingLeft = ProbeSession.waitingSecondsLeft
    // 安卓 15 起 targetSdk 35 以上的应用强制全屏绘制，要自己避开状态栏和导航栏。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("OpenHC 蓝牙探针", style = MaterialTheme.typography.headlineSmall)
        Text(
            "风味 ${BuildConfig.FLAVOR} · targetSdk $targetSdk · 版本 ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onStart, enabled = !running) { Text("开始检测") }
            if (running && waitingLeft > 0) {
                OutlinedButton(onClick = onStopWaiting) { Text("停止等待") }
            }
            OutlinedButton(onClick = onCopy, enabled = items.isNotEmpty()) { Text("复制全部结果") }
            OutlinedButton(onClick = onSave, enabled = items.isNotEmpty()) { Text("保存到文件") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = ProbeSession.waitForConnections,
                onCheckedChange = { ProbeSession.waitForConnections = it },
                enabled = !running,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "检测完后保持监听 ${ProbeSession.WAIT_SECONDS} 秒，等另一台设备来连",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(statusLine(items, running, waitingLeft), style = MaterialTheme.typography.bodyMedium)
        ProbeSession.permissionHint?.let { hint ->
            Text(hint, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onOpenSettings) { Text("打开本应用的系统设置") }
        }
        ProbeSession.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        HorizontalDivider()
        SelectionContainer(modifier = Modifier.weight(1f)) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(items) { index, item ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (index == 0 || items[index - 1].section != item.section) {
                            Text(
                                item.section,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        ResultRow(item)
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(item: ProbeItem) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            item.status.label,
            color = statusColor(item.status),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.widthIn(min = 40.dp),
        )
        Column {
            Text(item.name, style = MaterialTheme.typography.bodyMedium)
            if (item.value.isNotEmpty()) {
                Text(
                    item.value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun statusColor(status: Status): Color {
    val dark = isSystemInDarkTheme()
    return when (status) {
        Status.OK -> if (dark) Color(0xFF81C784) else Color(0xFF2E7D32)
        Status.FAIL -> if (dark) Color(0xFFFFB74D) else Color(0xFFE65100)
        Status.ERROR -> MaterialTheme.colorScheme.error
        Status.SKIP -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun statusLine(items: List<ProbeItem>, running: Boolean, waitingLeft: Int): String = when {
    running && waitingLeft > 0 -> "等待连接中，还剩 $waitingLeft 秒……"
    running -> "正在检测……"
    items.isEmpty() -> "点「开始检测」开始。屏幕上显示完整值；复制、存文件和 logcat 里的地址会打码。"
    else -> {
        val counts = items.groupingBy { it.status }.eachCount()
        "检测完成：" + Status.entries.joinToString("，") { "${it.label} ${counts[it] ?: 0}" }
    }
}
