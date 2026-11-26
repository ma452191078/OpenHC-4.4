# Tasks：修复安卓 5.0 车机「连接热点时 App 卡死」

- [x] T1 `SocketRunner.Options` 增加 `watchdogMillis`（默认 5000），新增 `progress` / `stage` 两个 `@Volatile` 字段
- [x] T2 `SocketRunner.runLoop/dispatch/execute/poll` 覆盖 `stage` + `progress` 更新
- [x] T3 `SocketRunner` 新增看门狗线程：停顿 5 秒按行打印 `stage` + 相关线程栈到 `options.log`（同一停顿只报一次）
- [x] T4 `SocketRunner.waitMillis()` 最小等待改 1ms，消除零等待自旋
- [x] T5 `runLoop()` 在 `onTick` 后校验 `nextTickAtMillis` 是否仍在过去：节流日志 + 5ms 退避
- [x] T6 `SocketRunner.drainImmediate()` 加迭代上限 1000，超限转投 `submit` 续跑
- [x] T7 `WirelessRunnerBridge.drain()` 加 `draining` 重入保护（try/finally 复位）
- [x] T8 `WirelessController` 构造 `Options` 时显式开启 `watchdogMillis = 5000`（`onWirelessEvent` 已只做 `Log` + `post`，无需再改）
- [x] T9 单测：`:core:session:test`、`:app:testDebugUnitTest` 全部通过
- [x] T10 构建 release/debug 并复制到 `dist\openhc-0.2.1-android4.4-wireless-identity-{release,debug}.apk`
- [ ] T11 5.0 车机验证（需真机：MX4 当前未连接 adb，无法自动执行）
- [ ] T12 4.4 车机回归（需真机：CS11 当前未连接 adb，无法自动执行）
- [x] T13 触摸滑动延迟：诊断日志证实是 12Mbps 在 2.4GHz 热点上拥塞（积压 5-6 帧、延迟 200-400ms），无线连接把声明码率钳到 6Mbps（`WirelessController.WIRELESS_MAX_BITRATE`），有线不受影响；已构建进 dist 包
- [ ] T14 触摸延迟实测验证（MX4 上装新包滑动对比；adb 不可用时手动装）
