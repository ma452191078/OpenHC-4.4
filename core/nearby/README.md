# core:nearby —— 发现与控制通道

手机和车机建立连接的第一层，纯协议逻辑，不碰 USB/蓝牙/网络 API（那些在 `:platform`）。

- **carInfo**：车机经 USB 控制传输发给手机的 91 字节结构（TLV 布局、设备 ID 的算法），手机据此切到 USB 网卡模式。
- **二维码**：无线扫码配对的深链接（`PairingQrCode`）。
- **BLE 广播与 GATT**：车机广播的 Service Data 载荷（`BleAdvertisement`）、GATT 服务端的布局（`NearbyGatt`）和回应规则
  （`NearbyGattResponder`），无线自动回连用，见下文「BLE 广播与回连」。
- **控制帧**：控制通道上的帧格式（帧头、类型、TLV、长度、校验）与载荷加解密。控制通道有三种承载（`NearbyLink`）：
  有线是 USB 网卡上的第一条 TCP（通道 6），无线先是经典蓝牙 RFCOMM（通道 2），再是 WiFi 上的 TCP（通道 3），帧格式完全相同。
- **无线新增的消息**：蓝牙通道上的 RFCOMM 子通道请求与应答（消息层）、数据通道协商（资源层命令 1、2、7）、
  WiFi 组网层（外层类型 33：要 MAC、连接串、断开），以及连接串格式。
- 密钥材料来自身份资产（`IdentityAssets.nearbyKeys`），代码里不写任何常量。

## 对外接口

会话的入口类是 `NearbySession`，外加动作和事件两个 sealed 类型；无线另有几个公开的值类型和纯函数：`NearbyLink`、`WifiCapability`、`DataChannelRefusal`、`WifiMode`、`WifiConnectionInfo`、`PairingQrCode`、`WifiChannels`（见下文「无线」）。帧、TLV、各层消息的编解码都是 `internal`。

- `NearbySession.carInfo(host, bluetoothAddress, hostIp, port)`：生成 91 字节 carInfo。
  控制传输参数是 `CAR_INFO_REQUEST_TYPE`（0x40）、`CAR_INFO_REQUEST`（0x34）、`CAR_INFO_VALUE`（0）、
  `CAR_INFO_INDEX`（0xA0），长度 `CAR_INFO_LENGTH`（91）。
- `NearbySession.deviceId(host, bluetoothAddress)`：本机 deviceId（64 位大写十六进制）。认证、设备会话里的本机标识都用它。
  算法（含取不到蓝牙地址时的退化规则）在 core:common 的 `identity.DeviceId`，这里转调。
- `NearbySession(host, keys, bluetoothAddress, random, link = NearbyLink.USB)`：一条控制连接一个对象，不做 I/O。
  - `start(nowMillis)`：连接建立后立刻调用，发明文 connectId 并开始 5 秒握手计时；
  - `feed(bytes, offset, length)`：喂入读到的字节，半帧、粘包都可以；
  - `onTick(nowMillis)`：到 `nextTickAtMillis` 时调用，握手超时会返回 `HandshakeTimedOut`；
  - `acceptSocket(request, port)` / `rejectSocket(request)`：回应手机的建 socket 请求，可以晚回；
    手机可能为同一业务重复发请求，每个事件都要回，回同一个已在监听的端口。
- `carInfo`、`deviceId` 和会话构造要传同一个车机蓝牙地址，三处的 deviceId 才同源。
- 每个方法都返回 `List<NearbyAction>`，按顺序处理：`NearbyAction.Send` 是要写到控制通道上的一帧，
  `NearbyEvent` 是事件（握手完成、超时、对端蓝牙地址、建 socket 请求、自动拒绝、未处理条目、丢弃的数据，
  以及无线的协商请求与结果、手机 MAC、断开请求）。

### 无线

| 承载（`NearbyLink`） | 与有线的差别 |
|---|---|
| `BLUETOOTH`（通道 2） | 不报 TLV7。消息层上协议 0 的建 socket 请求（认证，业务 19）是要车机开 RFCOMM 子通道：用 `rfcommServiceUuid(request)` 开监听（请求没带 uuid 用 `DEFAULT_SUBCHANNEL_UUID`），拿到通道号后 `acceptRfcommSocket(request, 通道号)`，应答带通道号和 16 字节 UUID；请求里通道填 0 的按 2 处理。消息层命令 1（探活）自动回空的命令 2 |
| `WIFI`（通道 3） | `start` 发完 connectId 后再发一帧明文 `TLV1{02} TLV3(deviceId) TLV5{01}`（车机先当一次发起方），之后照常应答手机；不报 TLV7。业务 20 的请求与有线相同 |

蓝牙通道上的数据通道协商和组网：

- `NearbyEvent.DataChannelRequested`（资源层命令 1，带手机的 `WifiCapability`）→ `acceptDataChannel(request, 频率)`（车机当组主）
  或 `rejectDataChannel(request, DataChannelRefusal)`；手机的结果是 `NearbyEvent.DataChannelResult`（命令 7）。
- `requestWifiPeerAddress()`（组网层 tag 1）→ `NearbyEvent.WifiPeerAddress` 或 `WifiPeerBusy`（tag 2）→
  `sendWifiConnectionInfo(WifiConnectionInfo)`（tag 3）。
- WiFi 通道上对端请求断开（tag 5）时自动回 tag 6，交出 `WifiDisconnectRequested`；车机要断开用 `requestWifiDisconnect()`。

纯函数：

- `WifiCapability.canUse(频率)`：手机按它报的能力能不能用这个频率（协商时报给手机的就是车机热点的频率，先用它核对）。
- `WifiConnectionInfo`：连接串的生成与解析（热点和直连组两种格式都认），`pickPhoneAddress` 给手机挑同网段地址。
- `PairingQrCode.build(host, 蓝牙地址, 连接码, 是否也支持 USB)`：二维码内容。
- `WifiChannels`：信道与频率换算、协商用的 5 GHz 信道位图。

### BLE 广播与回连

没配对的手机靠车机的 BLE 广播弹「发现车机」，配对过的手机靠它回连（不用再扫码）。两种情况车机发的是**同一份**广播：

- 广播数据只有一段挂在 16 位服务 UUID `FDEE` 下的 Service Data，默认 11 字节：
  `01 01 01 03 <车型编号前 3 字节> 04 <后 1 字节> 10 <序列号>`（`BleAdvertisement.payload`）。车型编号必须按 6 + 2 拆开。
  可选项：`11 <发射功率>`；声明「车机用随机地址广播」时偏移 2 改成 `0F`、末尾加 `FF 01`（手机怎么处理没有验证，默认不发）。
- **载荷里没有任何和配对关系有关的字段。** 手机拿到的车机地址就是广播的发送地址，再和车型编号一起算车机 ID（与 deviceId
  同一算法）查自己的信任表，查得到就免码回连。所以广播发送地址必须是车机经典蓝牙的公共地址；系统蓝牙栈开了 LE 隐私的
  车机上，手机会去寻呼一个随机地址、必然超时——发送地址应用改不了。能补救的是让手机和车机补一次 LE 配对：手机拿到车机的
  身份解析密钥后，就能把随机地址解回车机的身份地址（应用设置里的「补全手机蓝牙配对」；补了以后能否免扫码要看具体车机和手机）。
- 序列号 1 字节，每发布一次加 1（`nextSequence`）；手机把序列号变了的广播当成一次新的发现（没配对的手机会再弹一次提醒）。
- 广播参数：传统广播、可连接、约 100 ms、中档功率、扫描响应为空；Flags `02 01 02` 由系统蓝牙栈加（`legacyAdvertisingData`
  给出空口上的完整数据，记日志、核对用）。
- 手机看到广播、经典蓝牙连上车机的 FE35 服务之后，与扫码完全相同：控制握手、认证子通道、认证（手机报 `has_pin=false`，
  走免码回连）、WiFi 协商。车机侧会话不需要新步骤，跳过的只是扫码。
- GATT（`NearbyGatt`）：`FE35` 服务下 `2A00`（读写）、`2A01`（通知，CCCD 初值开）、`2A02`（写）、`2A03`（通知，CCCD 初值关），
  `046A` 服务下 `046C`（读写），两个服务串行添加。`2A00` 写 / `2A01` 通知上承载的是控制帧（控制通道跑在 BLE 上，通道号 1），
  `046C` 是老版本的组网地址交换。车机现在只按 `NearbyGattResponder` 回应（读 `046C` 回 `busy`，其余读不允许，写和 CCCD 回成功），
  **不在 GATT 上跑控制通道**：实测的手机回连时不连 GATT；另一类手机系统可能会连上来取车机地址（握手里的 TLV7 / TLV8），没有验证。

## 与认证、设备会话的边界

**认证消息不在 nearby 控制通道上。** 握手完成后，手机在控制通道上发「建 socket」请求（业务号 19、标签 `hicarAuth`），
车机新开一个 TCP 监听（无线时是 RFCOMM 子通道），用 `acceptSocket`（无线 `acceptRfcommSocket`）把端口回给手机；手机连上这个端口，之后的配对认证数据是这条新 TCP 上的裸字节，
直接交给 core:auth，nearby 不再经手。设备会话同理：业务号 20（标签 `DMSDP`）的请求要等认证完成后再接受，
新 TCP 上的数据交给 core:dmsdp。什么时候接受、拒绝哪些业务，由串联各层的调用方决定。

## 还不确定的地方

- carInfo 头部 9 个固定字节的含义未知，按实测固定值写。
- 车机蓝牙地址取不到时：deviceId 的哈希改用 6 个 0 字节（已知规则）；但线上的地址字段、握手里回的 deviceId 文本、
  报给手机的蓝牙地址文本填什么没有确认，目前统一按 `02:00:00:00:00:00` 和退化后的 deviceId 处理。
- 消息层其他命令、外层类型 1、手机首包里的 16 和 18 格式未知，以 `NearbyEvent.Unhandled` 原样交出
  （有线通道上的数据通道协商命令也照旧这样交出）。
- 能力串里 tag 9～12、17、18 的含义未知，原样留在 `WifiCapability.otherFields`；热点连接串端口后那一段填热点所在信道的信道号（由报给手机的频率换算，频率是推测值时信道号也是推测）；
  BSSID 填错是否致命、手机是否把连接串里的手机 IP 直接配成自己的地址，都没有验证。
- 蓝牙通道上请求的通道字段填 0 时，应答里回 2（按「0 当作 2 处理」推定，没有抓到对照）。
- WiFi 通道上车机当发起方时手机回的 TLV6 值按「非 0 即加密」处理；车机只当应答方手机是否也接受，没有验证。
- 车机主动当蓝牙客户端回连手机（车机作为蓝牙通道上的握手发起方）没有实现：回连靠手机看到 BLE 广播后自己连上来。
- GATT 上的控制通道（通道号 1）没有实现；BLE 广播「随机地址」变体的手机侧处理没有验证。
