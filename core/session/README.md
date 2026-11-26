# core:session —— 会话编排

把 nearby、auth、dmsdp、hisight 四层串成一次完整的投屏连接：有线用 `ReceiverSession`，无线用 `WirelessReceiverSession`
（见下文「无线连接」）。纯 Kotlin/JVM，不引用安卓类；
是唯一同时依赖这四层的 core 模块。交接顺序、动作与事件的完整说明见
[docs/ARCHITECTURE.md「会话编排」](../../docs/ARCHITECTURE.md#会话编排)。

| 类型 | 作用 |
|---|---|
| `ReceiverConfig` / `DisplaySettings` | 本机配置：身份资产、车机蓝牙地址与 IP、控制端口、显示能力、认证外层版本、信任库。构造时把各层配置试建一遍，参数错误提前报出 |
| `ReceiverSession` | 编排器：持有四层的会话对象，负责层间交接。不做 I/O，输入 I/O 结果、输出动作和事件 |
| `SessionLogic` | 编排器对执行器的接口（`ReceiverSession` 实现它） |
| `SessionAction` / `SessionEvent` / `EndReason` | 统一的动作（监听、连接、写、关、UDP）、事件和结束原因 |
| `SocketRunner` | 用 java.net 阻塞 I/O 执行动作的执行器，安卓和电脑通用 |

## 用法

```kotlin
val config = ReceiverConfig(identity, bluetoothAddress, localIp, controlPort, display, authEnvelopeVersion, trustStore)
val carInfo = config.carInfo()            // 经 USB 发给手机（平台层或外部脚本负责）
val session = ReceiverSession(config) { unit -> decoder.queue(unit) }   // 视频帧：同步回调，缓冲区复用
val runner = SocketRunner(session, { event -> ui.show(event) })
runner.start()                            // 先开控制监听；收到 SessionEvent.ControlListening 后再发 carInfo
// …
runner.close()                            // 本机结束：按协议收尾（TEARDOWN），由上到下关闭全部连接
```

- 时间一律是单调时钟毫秒，由执行器传入；投屏控制消息 Date 头用的墙上时间来自 `ReceiverConfig.wallClock`。
- 视频帧在协议线程上经 `AccessUnitSink` 同步交出，不另外复制；要留到回调之后用，自己 `copyBytes()`。
- 触摸、按键：在协议线程上调用 `ReceiverSession.sendInput`，例如 `runner.submit { session.sendInput(input) }`。
- 认证外层消息的 `version` 取值由调用方提供（`authEnvelopeVersion`），仓库里不写。
- 声音（实验）：`ReceiverConfig(audio = AudioSettings())` 声明扬声器（默认不声明），再把音频出口交给编排器：
  `ReceiverSession(config, videoSink, audioListener)`。状态、原始字节和音频包都在协议线程上经
  `AudioStreamListener` 同步交出；车机这边的焦点变化用 `runner.submit { session.sendAudioFocus(change) }` 告诉手机。
  见下文「声音」。
- 车机麦克风（实验）：`ReceiverConfig(mic = MicSettings())` 声明麦克风（默认不声明），麦克风的状态出口作为第四个参数：
  `ReceiverSession(config, videoSink, audioListener, micListener)`。收到 `MicEvent.Started` 就开采集，
  采到的声音在协议线程上送：`runner.submit { now -> session.sendMicAudio(chunk, 0, chunk.size, now) }`，见下文「车机麦克风」。

## 声音（实验）

手机触发音频数据会话时，dmsdp 层先回 200，再要求开端口（`DmsdpAction.OpenAudioListeners`）。编排器在车机 IP 上
开数据端口的 TCP 监听：偶数 P（20000～59998 里随机挑），开好才交回 dmsdp 发 SETUP
（`client_port=P--1`：第二个是 RTCP 口，必须写 -1，否则手机建不起发送端）；开不了（端口被占）就换一个，
8 次都不行就告诉 dmsdp 放弃这次（不发 SETUP）。手机先回 200 再连 P，这条连接是 `SessionChannel.AUDIO`，
字节原样交给 dmsdp 拆包；分流时音乐和导航两路交错在这一条连接上。

`AudioSettings` 的几项：`transport`、`codec`（默认 AAC，应用的设置默认 PCM）、`multiBus`（声明多音频总线，默认开）、
`aacPhones`（要退回 AAC 的手机编号，应用按「PCM 不出声」记下的）。`sendAudioFocus` 把车机的焦点变化写到设备控制连接
（module 4），设备会话还没加密时什么都不发。

音频的监听、连接出错或被手机关掉**只影响声音**：关掉这一条、出诊断，会话照常继续（其他资源出错仍然结束会话）。
手机拆除数据会话时 dmsdp 要求关掉音频资源；会话结束时音频的连接和监听排在投屏之后、设备会话之前关闭。
无线的 WiFi 阶段同样可以声明：`WirelessReceiverConfig(audio = AudioSettings())`，音频出口作为 `WirelessReceiverSession` 的第三个参数，
规则与有线完全相同（默认不声明）。

## 车机麦克风（实验）

流程还没有在真机上验证过，dmsdp 层的诊断会把手机发来的头部原样记下来，见 [core/dmsdp/README.md「麦克风」](../dmsdp/README.md#麦克风实验)。

手机要录音（打电话、语音助手）时触发麦克风数据会话：dmsdp 层回 200、发能力协商；手机发 SETUP 后 dmsdp 要求开端口，
编排器在车机 IP 上开一个 TCP 监听（端口由系统分配，`SessionChannel.MIC`），开好交回 dmsdp 回 200（带 `server_port`）。
手机连上这个端口后，`sendMicAudio` 送来的声音由 dmsdp 打包、写到这条连接上；连接还没建立时送的直接丢掉。
麦克风的监听、连接出错或被手机关掉**只影响麦克风**：关掉这一条、出诊断，会话照常继续。
手机拆除时 dmsdp 要求关掉麦克风资源；会话结束时麦克风的连接和监听排在扬声器之后、设备会话之前关闭。
无线的 WiFi 阶段目前同样不声明麦克风。

## 无线连接

`WirelessReceiverSession` 同样不做 I/O。前半段在蓝牙上（控制、认证子通道、WiFi 协商、组网层）由它自己驱动；
车机给手机准备的 WiFi 网络是车机自己开的热点。热点上的 TCP 连进来以后，交给内嵌的 `ReceiverSession`
（控制通道是通道 3、认证已经做完），之后的设备会话和投屏与有线完全相同。

| 类型 | 作用 |
|---|---|
| `WirelessReceiverConfig` / `WirelessTimeouts` | 本机配置：身份资产、车机真实的蓝牙地址、显示能力、信任库、是否也支持 USB；各步超时 |
| `WirelessReceiverSession` | 无线编排器：输入 I/O 结果，输出 `WirelessOutput` |
| `WirelessOutput` | `Common`（与有线相同的 TCP 动作和会话事件）、`WirelessAction`（蓝牙、WiFi 动作）、`WirelessEvent`（无线事件） |
| `WirelessRunnerBridge` | 让 `SocketRunner` 执行无线会话：TCP 部分照旧执行，蓝牙、WiFi 部分交给平台回调，两边严格按会话给出的顺序 |

```kotlin
val config = WirelessReceiverConfig(identity, bluetoothAddress, display, trustStore)
val session = WirelessReceiverSession(config, { unit -> decoder.queue(unit) }, audioListener)   // 声音出口可省略
val bridge = WirelessRunnerBridge(session) { output -> bluetoothAndWifi.execute(output) }   // WirelessAction、WirelessEvent
val runner = SocketRunner(bridge, { event -> ui.show(event) })                              // TCP 动作、SessionEvent
bridge.attach(runner)
runner.start()
// 平台把蓝牙、WiFi 的结果交回：一律经协议线程，收到的数据先复制一份
runner.submit { now -> bridge.onAccepted(acceptId, phoneBluetoothAddress, now) }
runner.submit { now -> bridge.onData(id, copy, 0, copy.size, now) }
runner.submit { now -> bridge.onWifiNetworkReady(id, network, now) }
```

平台回调在协议线程上调用，里面不要阻塞，也不要直接调桥接的方法。一批输出里排在 TCP 动作后面的平台输出，
桥接会等执行器做完前面的 TCP 动作再交出（例如先发完投屏的 TEARDOWN 再关热点）。

### 交接顺序（车机视角）

```
 车机（OpenHC）                                                         手机
 ───────────────────────────────────────────────────────────────────────────────
 ① 开蓝牙控制监听：RFCOMM、服务 UUID 0000FE35-…、不加密（这时还不开热点）
    → PairingReady：显示二维码和 6 位连接码                      扫码 ────
                                                  ◀──── 按二维码里的蓝牙地址查 SDP、连 RFCOMM（通道 2）
 ② 先发明文 connectId；手机首包 ──▶ 应答 TLV2/4/6（加密，不报 TLV7）
                                                  ◀──── 消息层：开认证子通道（业务 19、协议 0）
 ③ 用请求里的 UUID（没带用 0000FE36-…）开 RFCOMM 监听，拿到通道号再回（应答带通道号和 UUID）
                                                  ◀──── 按通道号直接连（不查 SDP）
    关掉这个监听和控制监听（控制监听留到这时才关：先关的话它的通道号空出来会分给认证子通道，
    手机和车机之间这个通道号上已经有控制连接，第二条连不上）；认证与有线相同：应答里 usb_connection 为 false、不下发连接码，
    首次配对的口令就是二维码上的连接码 → 16 字节会话密钥；这时才开热点
                                                  ◀──── 资源层命令 1：要 WiFi 数据通道，带能力串
 ④ 报平台交回的热点频率（热点还没开好就等开好再回；手机按能力用不了这个频率就拒绝并结束）
    命令 2：车机当组主 + 频率 ──▶               ◀──── 命令 7：手机当组成员 + 同一频率
 ⑤ 热点已经开好，不再建网
 ⑥ 在热点地址的随机端口上开 TCP 控制监听（内嵌的 ReceiverSession）
 ⑦ 监听开好 → 组网层 tag 1 要 MAC ──▶           ◀──── tag 2：手机 WiFi MAC（或 busy）
    tag 3 连接串（热点格式：手机 IP、网络名、BSSID、密码、频率、端口、信道号、车机 IP）──▶
                                                  ◀──── 加入网络，TCP 连「车机 IP : 端口」（通道 3）
 ⑧ 先发 connectId，再当一次握手发起方：明文 TLV1{02} TLV3(deviceId) TLV5{01}
    手机首包 ──▶ 应答 TLV2/4/6（不报 TLV7）       ◀──── 手机回应车机的请求（TLV2/4/6）
                                                  ◀──── 资源层：业务 20（DMSDP，通道 3）
 ⑨ 认证已做完，直接开监听回端口 → 之后与有线完全相同
                                                  ◀──── 关掉两条蓝牙连接（正常，会话继续）
```

### 平台层要实现的动作

`WirelessAction` 经桥接的平台回调交出；结果按 id 交回同名方法（经 `runner.submit` 调桥接）。

| 动作 | 平台要做的 | 结果交回 |
|---|---|---|
| `ListenRfcomm(id, channel, uuid, acceptId, needChannelNumber)` | 开 RFCOMM 服务端：不加密、不要求认证、带服务记录（UUID = `uuid`，服务名随意）；只接受一条连接 | `onListening(id, 通道号)`：`needChannelNumber` 为 true 时必须是系统分配的真实通道号（1～30），拿不到交 0，会话会拒绝手机的请求并结束；`onAccepted(acceptId, 手机蓝牙地址)`；之后 `onData` / `onClosed` / `onIoError(acceptId, …)` |
| `RfcommWrite(id, bytes)` | 原样写到这条 RFCOMM 连接 | 失败 `onIoError(id, …)` |
| `RfcommClose(id)` | 关监听（撤掉服务记录）或连接。收尾时也会对还没连上的 `acceptId` 发，平台当空操作处理 | — |
| `StartWifiNetwork(id)` | 开热点（名称、密码、信道由系统定） | `onWifiNetworkReady(id, WifiNetwork(车机 IP, 网络名, BSSID, 密码, 实际频率))`，全部填实际值；失败 `onIoError(id, …)`；开好后热点没了 `onClosed(id)` |
| `StopWifiNetwork(id)` | 关热点 | — |

TCP 部分（`SessionAction`）与有线相同，由 `SocketRunner` 执行。

认证成功（`Authenticated`）之后才发 `StartWifiNetwork`：等扫码、等已配对的手机回连、没通过认证的连接都不开热点，
单射频车机开热点会断开自己的 WiFi，没有要连的手机时不该付这个代价。手机认证成功约 1 秒后才发协商请求，热点和它同时进行；
协商请求到时还没开好，就等开好再回。

热点的频率原样报给手机（协商命令 2 和连接串）。手机按它找网，所以平台应当交回热点的**真实频率**；普通应用在安卓上读不到
本地热点实际开在哪个信道，平台只能交回推测值（见 platform README「本地热点」），推测错了手机可能找不到网。
交回的频率不是信道频率（例如 0）时，会话以「网络信息不合法」结束。连接串用热点格式：网络段末尾多一行 `1`
（手机按普通 WiFi 方式加入），端口后多一段热点所在信道的信道号（由频率换算，例如 2437 → 6、5745 → 149）；
车机在协商命令 2 里报自己当组主（1）。

平台另外要提供：

- 车机经典蓝牙的**真实**公共地址（`WirelessReceiverConfig.bluetoothAddress`）：二维码、deviceId、手机算「车机 ID」都用它，
  公开接口只给占位值，要另想办法取得（读不到就只能让车主填写）。
- 同一时刻只能有一个程序注册蓝牙控制服务的 UUID，系统里另有同类服务在跑会抢走手机的连接。
- （可选）BLE 广播和 GATT 服务端（载荷、布局见 core:nearby「BLE 广播与回连」），以及会话结束后重新开一个会话，
  让配对过的手机能再次连上。应用里的做法（「等已配对的手机回连」）：会话的 `PairingReady`（蓝牙控制监听开好）之后
  再挂 GATT、发广播，`PhoneBluetoothConnected`、`Ended` 和收尾时都停广播；会话本身与扫码时完全相同。

### 事件

| 事件 | 时机 |
|---|---|
| `WirelessEvent.PairingReady(qrCode, pinCode)` | 蓝牙监听已开好：显示二维码和连接码（toString 不含内容） |
| `WirelessEvent.PhoneBluetoothConnected(address)` | 手机经蓝牙连上（有 BLE 广播的话可以停了） |
| `WirelessEvent.BluetoothHandshakeCompleted(encrypted)` | 蓝牙控制通道握手完成 |
| `WirelessEvent.DataChannelNegotiated(frequencyMhz)` | 手机确认了频率 |
| `WirelessEvent.WifiNetworkReady(frequencyMhz)` | 车机的热点开好了（常在手机发协商请求之前） |
| `WirelessEvent.ConnectionInfoSent(port, phoneMac, channel)` | 连接串已发，等手机入网（会话以 TCP 连入为准，`phoneMac` 只用于日志）；`channel` 是端口后那一段填的信道号 |
| `WirelessEvent.Ended(reason)` | 会话结束，只出现一次，总在最后 |

`Common` 里还会出现有线的会话事件：蓝牙段的 `AuthStarted`、`Authenticated`，WiFi 段的 `PhoneConnected`、`ControlHandshakeCompleted`、
`DeviceSessionReady`、`ProjectionRequested`、`VideoNegotiated`、`PlayAccepted`、`VideoStarted`，以及 `Diagnostic`。
不会出现 `ControlListening`（无线不发 carInfo，端口写进连接串）和有线的 `Ended`（由 `WirelessEvent.Ended` 代替）。
连接码在 `PairingReady` 里一次给出，不另发 `ShowPinCode`。

结束原因（`WirelessEndReason`）：本机停止、WiFi 阶段结束（带有线的 `EndReason`）、认证失败、某一步失败（蓝牙控制通道、认证、
数据通道协商、WiFi 组网、手机加入 WiFi，带说明）、内部错误。

### 计时与关闭

| 计时 | 默认 | 超时后 |
|---|---|---|
| 蓝牙控制通道握手 | 5 秒 | 结束 |
| 认证子通道的通道号回给手机后等它连入 | 5 秒 | 关监听、结束 |
| 一次认证（从子通道连上算起） | 120 秒 | 结束 |
| 开热点（从认证成功、开始开热点时算起） | 10 秒（应用给 15 秒） | 结束 |
| 要 MAC 起到手机连上 WiFi 上的控制端口 | 20 秒 | 结束 |

| 连接 | 被关闭或出错时 |
|---|---|
| 蓝牙控制连接 | 手机连上 WiFi 控制端口之前 → 结束；之后 → 正常（手机会主动关），会话继续 |
| 认证子通道 | 认证成功之前 → 认证失败；之后 → 正常 |
| 热点 | 开不成、超时、交回的信息拼不出合法的连接串、开好后没了 → 结束 |
| WiFi 上的 TCP | 同有线（内嵌的 `ReceiverSession` 处理）；它结束，整个会话随之结束 |

结束顺序：内嵌的有线编排器先按它自己的顺序收尾（TEARDOWN、由上到下关闭）→ 蓝牙认证子通道 → 蓝牙控制通道 → 关热点 → `Ended`。

## 测试

- `ReceiverSessionTest`：用假的层验证交接顺序（与参考实现一致）和各种异常、关闭路径。
- `RealLayersFlowTest`：真实的 dmsdp、hisight 层走完设备会话与投屏（手机侧数据按格式现场构造，
  投屏部分用 `session-vectors.json`，摘自 core:hisight 的合成对照向量），另测认证、nearby 的正式接线。
- `SocketRunnerTest`：执行器在本机回环上的监听、连接、收发、UDP、强制关闭和线程回收。
- `SessionOverSocketsTest`：编排器跑在真实执行器上，对端是回环上的脚本「手机」。
- `WirelessReceiverSessionTest`：用假的层和假的平台结果验证无线的交接顺序、拒绝、超时与关闭顺序。
- `WirelessRunnerBridgeTest`：平台输出与 TCP 输出的先后；无线会话经桥接跑在真实执行器和回环 socket 上。
- `RealWirelessFlowTest`：蓝牙、WiFi 两条控制通道用正式的 nearby 实现走一遍，手机侧的帧在测试里按协议格式另写一份。
- `AudioSessionTest`：音频端口开好才发 SETUP、端口被占换一个、音频出错不结束会话、收尾顺序、焦点变化发给手机；
  另用真实的 dmsdp 层从触发到拆除走一遍，手机侧的加密帧和音频包在测试里现场构造。
- `MicSessionWiringTest`：真实的 dmsdp 层走麦克风：SETUP 后开监听、开好才回 200、送出的声音写到麦克风连接（JCE 解开核对）、
  麦克风出错或被关不结束会话、收尾顺序。
