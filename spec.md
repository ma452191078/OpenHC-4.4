# Spec：修复安卓 5.0 车机「连接热点时 App 卡死」

## 1. 问题与现状

- 4.4 车机（CS11 / Freescale，设备号 0123456789ABCDEF）：无线连接正常，行为不得回退。
- 5.0 车机（魅族 MX4，SDK 22，设备号 750ABL57ZWTX）：连接热点时 App 卡死。
- 诊断日志 `openhc-diagnostics-20261125-133858.txt` 已确认的事实：
  - 进程 3401（第一次）：13:34:16 堆 18MB → 13:34:42 起约 5–8MB/s 增长 → 13:35:25 到 255MB/256MB，`0% free`。
  - 13:34:41.073 协议线程（tid 3458）打出 `[1] 开始开热点（手机已经认证通过）`；13:34:44.202 热点线程（tid 3461）报 `热点没开成：热点开好了，但 3 秒内没在网卡上找到它的地址`；此后协议线程再无任何日志。
  - 13:35:09 起 tid 3458 反复 `WaitForGcToComplete blocked ... cause Alloc`（635ms、1.24s、1.3s、1.4s…），主线程 3401 被卡 2.9s；蓝牙线程 3460/3488 到 13:35:15 仍正常打日志。
  - 进程 3861（第三次）：13:38:02.251 协议线程（tid 4078）打出 `[1] WifiNetworkReady(2437 MHz)`，之后再无该线程日志；13:38:38/39 手机主动断开蓝牙；13:38:41 用户停止；13:38:44 `执行器：会话没有按时结束，强制关闭全部连接`；13:38:48 `执行器：强制关闭后协议线程仍未结束`。

## 2. 根因分析（已收敛）

1. 不是 `runLoop` 空转退出不了：`SocketRunner.close()` 会置 `abandoned = true` 并投 `Input.Stop`，`runLoop` 在 `poll` 之后 `if (abandoned) break`。日志说“强制关闭后协议线程仍未结束”，说明协议线程卡在**某一次同步调用内部**。
2. 两个卡死时刻都紧贴平台输出的同步链路（`WirelessRunnerBridge.drain()` → `platform(head)` → `WirelessPlatformExecutor.execute()`）：
   - run1：`startLocalHotspot()` 打完 `开始开热点…` 之后（`handle.start()` 返回后）；
   - run3：`WifiNetworkReady` 之后进入 `startWifiPhase` → `routeWired { it.start(now) }`。
3. 症状形态（无日志 + 稳定小额分配 + ART GC 风暴 + 全局卡顿）符合**零等待自旋**：`SocketRunner.waitMillis()` 在 `session.nextTickAtMillis` 落在过去时返回 0，`queue.poll(0)` 立即返回 null，`runLoop` 每轮都执行 `listOfNotNull(...)` 分配一个小 List，并且每轮都会求值 `nextTickAtMillis`（`WirelessReceiverSession` 里同样每次构造 `listOfNotNull(...)`）。Dalvik（4.4）对这种分配不敏感，ART（5.0）会持续触发 GC，表现为卡死。
4. 次要结构风险（本次一并收敛，避免同类问题复发）：
   - `WirelessRunnerBridge.drain()` 没有重入保护：`platform(head)` 若回调进 `route()`（`drainScheduled == false` 时）会递归 `drain()`，嵌套 drain 各自可能再 `submit` 续跑任务。
   - `SocketRunner.drainImmediate()` 的 `while` 没有迭代上限，`immediate` 只要能自增就能无限跑。
   - 没有任何手段证明“卡在哪一行”：adb 反复 offline，`kill -3` 取不到栈。

## 3. 修改方案（decision-complete）

### 3.1 `core/session/.../SocketRunner.kt`

1. `Options` 新增 `watchdogMillis: Long = 5_000`（<=0 关闭），用于协议线程停顿检测。
2. 新增两个 `@Volatile` 字段：`progress`（Long，协议线程每完成一次循环/一次调用就自增）与 `stage`（String，记录当前阶段，例如 `poll`、`dispatch:Task`、`execute:session.onTick`）。
3. `runLoop()` 中在每个阶段前后更新 `stage` 与 `progress`；`dispatch`/`execute`/`poll` 三处都要覆盖。
4. 新增 `startWatchdog()`：`start()` 时若 `watchdogMillis > 0`，起一个 daemon 线程，每 `watchdogMillis` 采样一次 `progress`；连续两次采样不变就把 `stage` + `Thread.getAllStackTraces()` 中线程名以 `options.threadName` 开头以及 `main` 的栈，写进 `options.log(...)`，同一停顿只报一次（下次 `progress` 变化后重置）。
5. 修复零等待自旋：`waitMillis()` 改为 `(due - now()).coerceIn(1, IDLE_WAIT_MILLIS)`（最小 1ms）。
6. `runLoop()` 中在 `execute(session.onTick(now))` 之后校验：若 `!session.isEnded` 且 `session.nextTickAtMillis` 仍然 `<= now`，记一次（每 10 秒节流）日志 `定时任务没有推进：nextTickAtMillis=…`，并 `Thread.sleep(5)` 退避，避免自旋。
7. `drainImmediate()` 加迭代上限 `MAX_IMMEDIATE = 1000`；超限时记日志并把剩余项转投 `submit { ... }` 继续处理，不再原地无限循环。

### 3.2 `core/session/.../WirelessRunnerBridge.kt`

1. 新增 `private var draining = false`。
2. `drain()` 入口：若 `draining` 为 true，直接把输出留在 `backlog` 并返回 `emptyList()`（由外层 drain 继续消费），不再递归。
3. `drain()` 用 `try/finally` 维护 `draining`，保证异常路径复位。

### 3.3 `app/.../wireless/WirelessController.kt`（仅在需要时）

1. 构造 `SocketRunner.Options` 时把 `watchdogMillis` 设为 5_000，`log` 继续接 `LogRecorder`，保证看门狗栈能进“导出诊断日志”。
2. `onEvent` 回调（在协议线程上执行）只做转投：把 UI 相关动作 `post` 到主线程，不在协议线程里做同步的重活；如果发现现有实现已经在协议线程上同步调用平台/UI，则改为 `handler.post`。

### 3.4 明确的验收行为

- 5.0 车机：连接热点不再出现堆从 18MB 涨到 256MB；`WaitForGcToComplete blocked` 不再刷屏；协议线程在 `WifiNetworkReady` 之后能继续处理 `onListening/onAccepted` 并进入投屏。
- 4.4 车机：无线连接、投屏、触摸行为与当前一致（不得回退）。
- 若看门狗抓到栈表明真正死循环在别处：按栈定点修复，修复后重新执行本 Spec 的验证步骤（该栈会写进导出日志，无需 adb）。

## 4. 验证步骤

1. 单测：`.\gradlew.bat :core:session:test :app:testDebugUnitTest --console=plain`（含 `SocketRunner` 相关用例）。
2. 构建：`.\gradlew.bat assembleRelease assembleDebug --console=plain`，产物复制到 `dist\openhc-0.2.1-android4.4-wireless-identity-release.apk`。
3. 5.0 车机（MX4）：安装 → 手机发起无线连接 → 观察到热点就绪 → 手机连上 TCP → 投屏出画面；同时盯 `adb logcat`/导出日志，确认无堆暴涨、无 `WaitForGcToComplete` 刷屏。
4. 4.4 车机（CS11）：同样流程跑一次，确认无回退。
5. 若第 3 步仍卡死：从导出日志里取看门狗打印的线程栈，按栈定点修复并重复 1–4。

## 5. 不做的事

- 不改热点复用（`borrowedHotspot`）语义：车机已有热点仍然复用、不预设配置、不代为关闭。
- 不改投屏尺寸/黑边、不恢复投屏页信息面板与停止按钮。
- 不动触摸延迟（用户已确认稍后处理）。
- 5GHz 频率仍按当前推测值上报（2437MHz）。
