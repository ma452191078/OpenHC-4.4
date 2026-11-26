# core:auth —— 配对认证

- 首次连接：车机给出 6 位连接码，双方做口令认证（PAKE，3072 位有限域 SPEKE），再交换长期身份（Ed25519 公钥），
  得到 16 字节会话密钥。
- 再次连接：用首次配对保存的长期身份做免码认证（STS：X25519 临时密钥 + Ed25519 互签），得到新的 16 字节会话密钥。
- 外层的 HiCar 认证 JSON（`oper_type` 1/3）的解析与生成，以及认证连接上的分帧。
- 长期身份与信任列表的存储接口（[`TrustStore`](src/main/kotlin/io/github/gcbond/openhc/core/auth/TrustStore.kt)），
  core 里只有接口、内存实现和版本化的持久格式；落盘与私钥保护由平台层实现。

## 承载

认证消息**不走**控制通道（nearby 帧）。握手完成后，手机在控制通道上请求车机为业务 19（标签 `hicarAuth`）
新开一条 TCP（无线时是蓝牙上的一条 RFCOMM 子通道）；车机监听、把端口回给手机，手机连进来。之后这条连接上的字节全部交给本模块：

```
认证 TCP 收到的字节 ──▶ HiCarAuthSession.feed(bytes, offset, length)
                         └─▶ [Send(bytes) …]  原样写回同一条 TCP
                             HandshakeAnswered(mode, pinCode)   首次配对时 pinCode 可显示给用户
                             Succeeded(sessionKey, …) / Failed(…)
TCP 断开 ──────────────▶ HiCarAuthSession.onConnectionClosed()
```

这条 TCP 上是一个接一个的 UTF-8 JSON 对象，没有长度头也没有换行，单条上限 10240 字节；分帧在本模块里做。
本模块不碰 Socket、不开线程、不计时；超时与断线由调用方处理。

无线连接用 `HiCarAuthConfig(…, pin = 当前连接码, usbConnection = false)`：握手应答里 `usb_connection` 为 false、
不下发 `pin_code`（手机从二维码或用户输入拿到连接码），首次配对的口令就是二维码和屏幕上显示的那个连接码；
之后的认证消息与有线逐字节相同。手机握手里的 `usb_connection` 和配置不一致时按 `UnsupportedConnection` 失败。

## 接口分层

| 类型 | 作用 |
|---|---|
| `HiCarAuthSession` | 外层会话：字节流分帧、设备握手（`oper_type=1`）、把认证库消息装进 `oper_type=3` |
| `AccessoryAuthenticator` | 车机角色（HC_ACCESSORY）的认证库接口：喂入一条原始认证消息，返回要回发的消息和结果 |
| `KotlinAccessoryAuthenticator` | 上面接口的纯 Kotlin 实现；以后需要时可以换成别的实现 |
| `TrustStore` / `InMemoryTrustStore` / `TrustStoreCodec` | 长期身份与已配对手机的存储接口、内存实现、版本化字节格式 |

随机数由调用方注入 `SecureRandom`。会话密钥以 16 字节 `ByteArray` 交出，不进日志。

## 行为对齐与验证

`KotlinAccessoryAuthenticator` 与原库（OpenHarmony deviceauth_lite）的车机角色行为一致：

- 注入同一随机数序列、收到同样的手机消息时，发出的每一条消息逐字节相同（字段顺序、空白、十六进制大小写），
  结果码序列和会话密钥相同；错误路径的错误码也相同。`src/test/resources/.../accessory-vectors.json`
  是由该 C 实现注入确定性随机数后产出的固定向量（身份、连接码、服务身份都是合成值），`CVectorsTest` 逐条对照。
- 外层 JSON 的期望值按 Python `json.dumps(..., ensure_ascii=False, separators=(",", ":"))` 的紧凑输出生成
  （非 ASCII 原样输出），`HiCarAuthSessionTest` 对照。

## 移植来源

算法与消息格式移植自 OpenHarmony `security_device_auth`（`frameworks/deviceauth_lite`，OpenHarmony-v4.0-Release，
Apache-2.0）。移植的文件头保留了出处与许可声明（第三方声明待登记）。
