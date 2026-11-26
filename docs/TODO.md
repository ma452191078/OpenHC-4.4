# OpenHC 4.4 待办清单与后续优化计划

> 核查时间：2026-11-26
> 核查范围：Git 工作区与提交历史、`tasks.md`、`docs/superpowers/specs/2026-11-26-pairing-connection-ui/`、`CHANGELOG.md`、`README.md`、`dist/`、项目记忆，以及 `app` 关键代码路径。
> 本次已执行 `.\gradlew.bat :app:check`：**BUILD SUCCESSFUL（54s）**，单元测试与 lint 均通过。

## 一、当前状态快照

| 项目 | 现状 |
| --- | --- |
| 版本 | `0.0.1`（versionCode 1），APK 命名 `openhc-android4.4-<版本>.apk` |
| 远端 | `https://github.com/ma452191078/OpenHC-4.4.git`，main 最新 `c957dbb` |
| 工作区 | **33 个文件已修改 + 约 12 个新文件未提交**（应用内更新、仪表盘多媒体、二维码优先连接、BLE 移除） |
| dist | release 20:41 / debug 20:40，均为最新源码构建 |
| 构建 | `:app:check` + `assembleRelease` + `assembleDebug` 全部通过 |

## 二、未完成事项

### P0 仓库与交付卫生（已完成 2026-11-26）

- [x] **敏感文件未被忽略（安全）**：`.gitignore` 已补 `identity/`，`identity/openhc-identity.json` 不会再进仓库。
- [x] `.gitignore` 已补忽略：`bin/`、`.hallmark/`、`.screenshots/`、`.trae-html-share-packages/`、`car-apps/`、`dist/`。
- [x] 已提交当前全部改动（BLE 移除、二维码优先连接、应用内更新、仪表盘多媒体、文档）。
- [x] 已重新构建 release/debug 包，`dist/` 为最新源码产物。
- [x] 已清理 `dist/` 中 0.2.1 旧命名包。
- [x] 已移除 `app/build.gradle.kts` 与 `UpdateManager.kt` 的 UTF-8 BOM，并去掉文件末尾多余空行。
- [x] `docs/THIRD_PARTY_NOTICES.md` 已补登记 **bctls**（BouncyCastle TLS，应用内更新使用）。
- [x] `README.md` / `CHANGELOG.md` 已按 fork（`ma452191078/OpenHC-4.4`、0.0.1）重写。

### P1 代码已完成、待真机验证

- [ ] **5.0 车机（魅族 MX4）热点连接卡死修复**：`tasks.md` T11 未完成。需验证堆内存不再从 18MB 涨到 256MB、`WaitForGcToComplete` 不再刷屏、`WifiNetworkReady` 后能继续进入投屏。
- [ ] **4.4 车机（ECARX CS11）回归**：`tasks.md` T12，确认无线连接、投屏、触摸无回退。
- [ ] **触摸延迟实测**：`tasks.md` T14，无线码率已钳到 6Mbps，需在 MX4 上滑动对比。
- [ ] **全版本二维码优先流程**（11-26 16:35 需求，代码已在工作区实现）：
  - 打开应用直接显示二维码，不再出现等待页/加载动画；
  - 已配对时后台蓝牙监听 + 8 秒轮询兜底自动回连；
  - 手机主动断开后回到二维码页、停止自动重连，显示「开始连接」；
  - 点击「开始连接」恢复重连，同时支持扫码重连。
- [ ] **应用内更新端到端**：需在 GitHub 发 `v0.0.2` Release（上传 `openhc-android4.4-0.0.2.apk`，说明里写 SHA256），再验证检查 → 下载 → 校验 → 安装；重点验证 4.4 上 BouncyCastle TLS 握手。当前仅模拟器验证过界面（`.screenshots/emulator-update-*.png`）。
- [ ] **仪表盘多媒体（DIM）**：
  - 验证 `com.ecarx.dim_interaction/.server.DiServer` 是否允许第三方应用绑定（白名单/权限）；
  - 蓝牙 AVRCP 方案实机确认能显示歌名/歌手/进度；
  - 确认车机自带多媒体/CarPlay（走 MediaSession）不会被 OpenHC 的推送顶掉。
- [ ] **车机热点 20 秒内手机未连上**（11-26 13:22 会话遗留）：需在复用热点场景复测。
- [ ] 通话与车机麦克风未验证（README 已知限制）。
- [ ] 非纯血鸿蒙手机兼容性未验证（README 已知限制）。

### P2 功能半成品 / 未实现

- [ ] **HiCar 原生媒体元数据（方案 A）未实现**：目前只有方案 B（车机蓝牙 AVRCP），HiCar 音频走自身通道时拿不到元数据。下一步用 debug 探针确定 514/515 的 module/type：`adb shell am broadcast -a io.github.gcbond.openhc.DEBUG_AA`（代码见 `OpenHcApp.startAaProbe`）。
- [ ] 仪表盘音源反控未接线：`DimMediaClient.onSwitchSource` 已定义但没有任何使用方。
- [ ] 专辑封面未接入：`DimMediaInfo.coverPath` 目前恒为 `null`。
- [ ] **BLE 残留清理**（功能已移除，文案/注释未清）：
  - 未引用的字符串：`strings_wireless_auto.xml` 中 `wireless_beacon_*`、`settings_wireless_republish*`、`settings_wireless_random*`、`wireless_auto_hold_unsupported`；
  - 过时注释：`WirelessState.kt`、`WirelessController.kt` 类注释、`AndroidManifest.xml` BLE/GATT 权限说明；
  - 过时测试名：`WirelessReducerTest.autoReconnectAttemptKeepsTheLastEndReasonAndTracksTheBeacon`。
- [ ] 缺少「打开即二维码 / 手机主动断开后不自动拨」的单元测试覆盖（`restoreWaiting`、`lastEndByPeer` 仅被策略测试间接覆盖）。
- [ ] 5 GHz 直连上报频率仍是推测值 2437MHz（不影响实际连接，仅界面提示）。

## 三、后续优化计划

### M1 收尾与提交（P0，优先级最高）

目标：仓库干净、可交付、不泄密。

1. 先补 `.gitignore`（尤其 `identity/`），再 `git status` 复核无敏感文件；
2. 清理 BOM/空行、dist 旧包、BLE 残留文案与注释；
3. 更新第三方声明与版本说明；
4. 提交并推送；重新出 release 包放 `dist/`。

验收：`git status` 只剩预期文件；`dist/` 只有当前版本 release/debug；`:app:check` 通过。

### M2 真机验证矩阵（P1）

按「4.4 CS11 → 5.1 MX4 → 安卓 10+ 车机」顺序，逐项验证二维码主流程、自动回连、手机主动断开、更新、触摸延迟与 5.0 卡死修复。建议每次验证导出诊断日志归档，形成记录。

验收：每项在 `docs/verification/` 或 issue 中有结论与日志附件；不通过项回到修复。

### M3 多媒体与仪表盘完善

1. 先用探针确定 HiCar 514/515 的 module/type，再实现原生元数据（方案 A）；
2. 补 MediaSession 监听，覆盖车机自带多媒体/CarPlay；
3. 接入专辑封面与音源反控（按实际车机能力取舍）；
4. 仅在确有媒体信息时推送，避免顶掉车机原显示。

验收：HiCar 播放音乐时仪表盘稳定显示歌名/歌手/进度；暂停/切歌同步；退出后恢复车机原显示。

### M4 发布与更新链路

1. 制定发布流程：打 tag `vX.Y.Z` → 上传 APK → 发行说明写 SHA256；
2. 增加发布脚本/CI 自动计算 SHA256、校验包名与签名，减少手填错误；
3. 完整走一遍应用内更新（含 4.4 TLS、国内网络、限流 403/429）。

验收：旧版本能从应用内完成一次真实升级；SHA256/签名不符时拒绝安装并给出明确提示。

### M5 稳定性与性能

1. 8 秒轮询以 `ACL_CONNECTED` 事件为主、轮询兜底，降低后台开销；
2. 热点复用增加过渡态等待与失败重试窗口（针对 20 秒超时遗留）；
3. 保留并完善看门狗线程栈、强制关闭后的诊断日志；
4. 复核 ART（安卓 5.0+）分配压力与 GC 风暴路径，避免新增高频小对象。

### M6 文档与合规

1. 重写 README/CHANGELOG 为 fork 版本（0.0.1 起），说明与上游差异；
2. `SECURITY.md` 补充应用内更新通道说明（证书降级策略、SHA256 + 签名双校验）；
3. 第三方声明补 bctls。

## 四、本次核查依据

- `tasks.md`：T11（5.0 真机验证）、T12（4.4 回归）、T14（触摸延迟实测）未完成。
- `docs/superpowers/specs/2026-11-26-pairing-connection-ui/tasks.md`：任务与验收均已勾选，实机边界有注明。
- `git status` / `git diff --stat`：33 改 + 新增 update/dim 模块，均未提交。
- `dist/` 与源码 mtime 对比：release 包早于最新 `WirelessController.kt`。
- 代码检查：`App/build.gradle.kts` BOM、`.gitignore` 覆盖缺口、BLE 残留字符串与注释、`UpdateModels.kt` 仓库地址已正确指向 `ma452191078/OpenHC-4.4`。
- `:app:check`：通过。
