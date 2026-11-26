# 有线/无线配对与连接首页规格

## 目标

在 Android 4.4 兼容的手写 View 首页中，按是否配对过手机提供直接、安静的连接流程：无线未配对直接显示扫码页；无线已配对显示等待页并可手动进入扫码页；有线进入首页即自动等待/连接。所有主要状态采用图标在上、文本在下、整体居中的结构，异常显示错误图标和可操作的信息。

## 已确认的现状

- `WirelessPreferences.knownPhoneList` / `lastPhone` 已持久化无线投屏成功的手机蓝牙地址；`WirelessController` 在 `SessionEvent.VideoStarted` 调用 `rememberPhone`。
- `WirelessController.startOnLaunch()` / `onAppForeground()` 已能主动回连最近手机，系统蓝牙广播入口也已存在。
- `WirelessController.start()` 在已有等待连接时直接返回，无法作为已配对等待页的“扫码配对”按钮。
- `ConnectionController` 已通过 `FileTrustStore` 保存有线配对凭据，插线启动和自动重插流程已存在；无需再复制一套手机身份存储。
- `LogRecorder` 已持续捕获本进程 logcat，支持轮换和导出。两个控制器均记录阶段、会话事件和异常；本次只补自动启动、模式切换、扫码切换和有线配对成功的非敏感日志，不记录二维码、PIN、密钥或完整蓝牙地址。
- 首页仍有固定大标题、状态胶囊、三步骤和横向状态卡，与新要求不符。

## 界面行为

### 无线：未配对

1. 打开首页自动进入扫码等待，不经过三步骤或“开始”按钮。
2. 页面显示精确标题“请用手机自带扫码功能扫码配对”，下方居中显示二维码；连接码在手机认证阶段仍保留，供必要的手输场景使用。
3. 配对并投屏成功后沿用 `rememberPhone` 记录手机，下次启动走已配对流程。

### 无线：已配对

1. 打开首页后显示无线连接图标，下方为“等待连接”，整体居中，不显示固定面板标题或二维码。
2. 后台继续主动回连最近手机，同时保留手机蓝牙连上车机的触发入口；手机真正进入连接流程后改为旋转加载图标和“正在连接”，细分步骤提示可放在其下。
3. 等待页下方提供“请用手机自带扫码功能扫码配对”按钮。点击后结束尚未看到手机的旧等待，再启动一次 `Kind.MANUAL` 扫码等待，不能被旧 `attempt` 挡住。
4. 用户主动停止后不立即自动重启；蓝牙重新接入和下一次打开应用的既有规则保持不变。

### 有线：未配对与已配对

1. 用户切到有线首页时自动走现有权限、VPN 和连接启动链；应用启动时若保存的模式是有线，也自动启动。不得在 `renderWired` 中启动，避免重复循环。
2. 空闲、准备和等手机插入时只显示有线连接图标与“请使用数据线连接手机”，不显示固定标题、三步骤或常规“开始”按钮。
3. 插入手机后沿用现有自动连接；进入实际连接步骤后显示旋转加载图标和“正在连接”，必要的手机操作提示与 PIN 继续显示。
4. 投屏成功沿用 `FileTrustStore` 保存配对信息，并补一条成功日志；后续插入手机自动连接。
5. 授权、配对记录损坏等需要用户操作的异常仍显示对应操作按钮；进行中和投屏中保留停止/查看投屏按钮。

### 通用状态

- 主要状态结构统一为：上方图标、下方主文本、必要时再下方辅助说明，全部水平居中。
- 加载状态使用 API 19 可用的旋转圆弧 View，在 detach 时停止动画。
- 异常状态使用错误/提醒图标、具体原因和下一步建议，不再使用生硬的仅错误码文本。
- 顶栏品牌、模式切换、设置和页脚保留；面板内固定大标题和状态胶囊移除。

## 精确修改

### `app/src/main/kotlin/io/github/gcbond/openhc/ui/HomeScreen.kt`

- `HomeActions` 增加 `onWirelessPairing` 和 `onWiredStart` 回调。
- `selectMode(wired = true)` 完成模式同步和渲染后调用一次 `onWiredStart`；无线切换仍调用 `onModeChanged`。
- `buildPanel` / `buildWiredPanel` / `buildPanelParts` 移除固定标题与状态胶囊；`PanelParts` 不再保存 `pill`。
- `renderWireless` 按以下优先级选择内容：异常；已配对且尚未看到手机的自动等待；手动二维码；手机已接入后的连接；投屏；其他状态。
- 已配对等待使用 `WirelessUiState.autoReconnect && !phoneConnected` 识别后台自动回连/等待，并以 `knownPhoneList` 作为空闲时的已配对依据；等待页包含扫码按钮，不再把该状态的 `qrCode` 展示出来。
- `renderWired` 将 `IDLE`、`PREPARING`、`WAITING_FOR_PHONE` 归为接线等待；`CONNECTING`、`PAIRING` 等实际过程归为加载；`notice` 归为错误/处理提示。
- 新增居中状态构建函数与旋转加载 View；二维码页加入精确标题。删除不再使用的三步骤调用，保留连接码、错误处理和必要按钮。
- 状态签名包含已配对/等待展示所需字段，防止模式或记录变化后界面不刷新。

### `app/src/main/kotlin/io/github/gcbond/openhc/wireless/WirelessController.kt`

- 新增 `startPairing()`：取消静默/自动重试；无连接时直接 `begin(Kind.MANUAL)`；若当前是尚未看到手机的等待，则登记一次性 `pendingPairing`，以 `StopReason.YIELD` 收尾。
- `afterAttempt()` 优先处理 `pendingPairing`，清理标记后启动 `Kind.MANUAL` 并写日志，保证扫码按钮能从已配对等待切到二维码。
- 自动回连/自动等待的 `AttemptStarted.autoReconnect` 对 `Kind.AUTO` 和 `Kind.RECONNECT` 都为 true，使 UI 能在手机接入前隐藏二维码和加载态。
- `restoreWaiting()` 有历史手机时先主动回连；无法发起时，有历史手机则进入 `Kind.AUTO` 等待，无历史手机才进入 `Kind.MANUAL` 二维码。
- `beginWaitingAfterSilent()` 对已有手机不再回退到二维码，而是进入自动等待；未配对仍显示二维码。
- 记录“打开应用/回到前台/手动扫码/回连转等待”等非敏感日志，沿用现有阶段和异常日志。

### `app/src/main/kotlin/io/github/gcbond/openhc/MainActivity.kt`

- 将 `onWirelessStart` 保持为兼容的重试入口，把新增 `onWirelessPairing` 接到 `WirelessController.startPairing()`。
- 新增 `onWiredStart`，调用 `requestStart(StartKind.START)`。
- 启动时若 `settings.wiredEnabled && WirelessPreferences.wiredOnly`，同步无线让出并在没有其他待处理启动时自动 `requestStart(StartKind.START)`；否则走 `startWirelessOnLaunch()`。
- 模式自动启动写日志；用户停止后不通过状态渲染再次启动。

### `app/src/main/kotlin/io/github/gcbond/openhc/connection/ConnectionController.kt`

- 在调度线程收到 `SessionEvent.VideoStarted` 时记录有线投屏成功、配对凭据已由 `FileTrustStore` 保留以及后续插线自动连接。无需新增重复的手机信息表。

### `app/src/main/res/values/strings_home2.xml`

- 新增扫码标题/按钮、等待连接、正在连接、请使用数据线连接手机等字符串；界面代码不硬编码这些主文案。

## 验证

1. 运行 `.\gradlew.bat :platform:test :app:testDebugUnitTest --console=plain`。
2. 运行 `.\gradlew.bat :app:assembleRelease :app:assembleDebug --console=plain`。
3. 将 debug 包安装到 `emulator-5554`，冷启动分别检查无线无配对、有配对记录、手动扫码切换和有线模式；截取首页状态图。
4. 检查日志包含自动启动、等待/回连、扫码切换、状态变化和异常；确认日志没有二维码、PIN、密钥或完整蓝牙地址。
5. 按项目既有命名更新 `dist` 的 release/debug APK。无真实手机时明确标注扫码、蓝牙和 USB 实机链路未验证，不宣称配对成功。

## 非目标

- 不改无线协议、热点/WiFi 直连、VPN、USB 协议和投屏参数。
- 不引入 Compose、第三方组件库或新的持久化框架。
- 不删除现有设置项，不创建 git commit。
