# core:dmsdp —— 设备会话

认证之后的会话层：控制连接的分帧与加密切换、设备能力交换、服务（显示等）的通告、
时间同步应答、保活，以及文件分享等管理消息。纯协议逻辑，不碰 socket、线程和定时器。

入口是 `DmsdpSession`：收到的字节用 `feed` / `onDatagram` 喂进来；要发的字节和需要调用方做的事
（开监听、建 UDP、连文件端口、关连接）以 `DmsdpAction` 返回，协议事件以 `DmsdpEvent` 返回，
二者按产生顺序混排在同一个列表里。时间由参数 `nowMillis` 给出，IV 用注入的 `SecureRandom` 生成，
会话密钥是认证层给的 16 字节。

| 连接（`DmsdpChannel`） | 谁连谁 | 内容 |
|---|---|---|
| `SESSION`：业务 20 的 TCP | 手机连车机（端口由 nearby 层的资源应答给出） | `0x7F` 帧；只有首轮 16 字节协商，换回控制监听的端口 |
| `CONTROL`：控制 TCP | 手机连车机另开的控制监听 | 三字节头帧；明文选择加密后全程 AES-128-CTR |
| UDP 保活（`onDatagram`） | 车机连手机给的端口 | 加密的 16 字节保活包，手机选了 UDP 时才有 |
| `FILE_CONTROL` / `FILE_DATA` | 车机连手机给的端口 | 文件分享，不加密 |
| `AUDIO`：音频数据 TCP | 手机连车机在 SETUP 里给的端口 P（`client_port=P--1`，第二个口是 RTCP，写 -1 表示不建） | 手机的声音，一条连接上可能有几路（见下文） |
| `MIC`：麦克风数据 TCP | 手机连车机在麦克风 SETUP 的 200 里给的 `server_port` | 车机麦克风的声音，车机发、手机收（见下文） |

还没有实现：时间同步的 UDP 时间服务、设备消息的分片重组、扬声器和麦克风以外的数据会话
（这类请求以 `DmsdpEvent.UnhandledControlMessage` 原样交出）。

## 扬声器（实验）

`DmsdpConfig.speaker` 不为 null 时声明扬声器服务（服务类型 4、`speaker1`），手机就会把声音交给车机；默认不声明。

**属性 2102**（一段 JSON 文本，作为字符串放进属性，键名和顺序见 `DmsdpConfig.audioProfile`）决定手机怎么发：

- 编码：`codecs`、`profiles` 是空数组时手机用默认的 AAC；声明 `codecs: [1]` 和一个 48kHz、`channel` 12（立体声）、
  `format` 1（16 位）的 profile 时手机改发 PCM。两条路都已真机验证；SETUP 的 `Codec` 头不影响手机选哪种编码。
- 多音频总线（`Speaker.multiBus`，默认开）：`extendInfo` 是 `{"MultiAudioBusCap":"true","MultiAudioBusPolicy":"8:4096:1"}`
  （两个值都是字符串）。手机见到它就分流：音乐固定在第 8 路、一直在发；导航语音单独在第 0 路、只在播报时有包。
  RTP 的 SSRC 低 16 位是 1000 + 路号，高 16 位是用途号（安卓 `USAGE_*` 的取值，例如通话 2、铃声 6；0 表示没带），
  见 `audio.AudioBus`。通话、铃声和媒体在同一个数据会话里，按 SSRC 分路。

**按手机定声明**（`audio.SpeakerPlan`）：手机的设备信息（命令 101）里的 deviceId 算出一个本地编号（`audio.PhoneRef`，
SHA-256 前 8 字节）。`Speaker.codec` 是 PCM、而这台手机在 `Speaker.aacPhones` 里时，这次连接改声明 AAC。
声明在第一次用到时（回服务声明 102 或手机触发数据会话）定下，2102 和 SETUP 的 `Codec` 一致。

**PCM 能不能出声**（`audio.PcmCheck`，只在声明 PCM 时判，结论经 `AudioEvent.CodecVerdict` 交出，由应用按手机记下）：
收到 50 个能放的 PCM 包判「能出声」；载荷不像声音判「不出声」；手机发过焦点请求、整个连接却一次数据会话都没开、
且从第一次请求到连接结束超过 10 秒，在连接结束时判「不出声」。

**音频焦点消息**（设备消息 module 4，`audio.PhoneAudioFocus`）：

| 方向 | type | 数据 | 含义 |
|---|---|---|---|
| 手机 → 车机 | 1～4 | 用途号的十进制 ASCII（安卓 `USAGE_*` 的取值，例如 `"1"` 是媒体） | 要焦点；type 就是焦点类型（1 长期、2 短暂、3 短暂并压低别人、4 短暂独占） |
| 手机 → 车机 | 5 | 同上 | 放弃焦点 |
| 手机 → 车机 | 14、16 | UTF-8 JSON `{"contentType":…,"focusType":…,"usage":…}` | 分流后单独一路的焦点：播报前 `focusType` 3、`usage` 12，播完 `focusType` 5 |
| 手机 → 车机 | 15 | UTF-8 JSON，带非空 `multiPolicy` | 手机的多路策略 |
| 手机 → 车机 | 57 | 不看 | 放弃全部焦点 |
| 车机 → 手机 | 1 / -1 / -2 / -3 | 车机自己的设备 ID（UTF-8） | 车机这边拿回焦点 / 长期失去 / 暂时失去 / 可压低（`audio.CarAudioFocus`，用 `DmsdpSession.sendAudioFocus` 发） |

焦点消息解析后以 `AudioEvent.PhoneFocus` 交出，不回应；申请系统焦点是应用的事。

**通话和语音助手状态**（`audio.PhoneStateMessages`）：设备消息 type 504 `{"callState":N}`（0 挂断、1 来电、2 通话中）、
type 505 `{"voiceState":N}`（0 空闲，别的值当在用），UTF-8 JSON。它们走 module 1（控制数据）；module 4 上同样的 type 也认。
解析后以 `AudioEvent.PhoneCallState` / `PhoneVoiceState` 交出（带着实际的 module），不回应；格式不对只记诊断。

**各路去向**（`audio.AudioRouter`，每个 `AudioPacket` 带 `bus`、`usage` 和 `route`）：SSRC 带的用途是通话、铃声时
一律放到各自的通道；是导航、提示与语音时，声明了多音频总线就放到各自的通道。其余按路号：还没看出手机在分流时全部是媒体
（不分流的手机导航混在媒体那一路里）；看出分流（收到 type 14/16、带 multiPolicy 的 type 15、或第 8 路的包）之后，
第 8 路是媒体，第 0 路跟最近一条「要焦点」的分流消息的用途走（导航 → 导航、提示音和语音助手等 → 提示与语音、
通话 → 通话，还没收到时按导航），其他路号当媒体。用途不认识的按媒体放。某一路第一次出现、或换了通道时出
`AudioEvent.StreamRouted`（带用途，不认识时标出来）。

数据会话只实现 simple 流程，消息都在加密的控制连接上：

1. 手机先发焦点消息（见上表），再 `SET_PARAMETER`（头部 `ServiceID: speaker1`、`ServiceType: 3`、`DataSessionID`、
   `Simple: 1`、`ParaAudio`，正文 `msdp_trigger_method: OPTIONS`）→ 车机先回 200（ServiceID、ServiceType、
   DataSessionID、手机的 CSeq），再输出 `DmsdpAction.OpenAudioListeners`；调用方开好数据端口后调
   `audioListenersReady(P)`，车机发 SETUP（CSeq 与保活共用计数器；`Fillp`、`TCP` 由 `Speaker.transport` 决定、
   `Codec` 跟着这次的声明，UDP 时不带 `TCP` 头）。
2. 手机对 SETUP 的应答按 CSeq 对应；200 时出 `DmsdpEvent.AudioSessionEstablished`。手机先回 200 再连 P。
3. 手机 `msdp_trigger_method: TEARDOWN` → 回 200，输出 `DmsdpAction.CloseAudioTransport`。
   新的 DataSessionID 触发时先拆旧的。

服务声明里扬声器是类型 4，数据会话消息里是 3，不能混用。音频连接的字节用 `feed(AUDIO, …)` 喂进来，
状态、原始字节和拆出的音频包经构造时给的 `audio.AudioStreamListener` 同步交出（`AudioEvent`、`AudioPacket`）。

**分帧识别**（`audio.AudioDepacketizer`）：TCP 上的分帧还没有确定的说法，所以按「4 字节大端长度」「2 字节大端长度」
「标志 00/AB + 2 字节长度」「`$` + 通道 + 2 字节长度」四种帧头、每种再分「帧内直接是 RTP」和「帧内是 `IV16 ‖ 密文`、
解开是 RTP」逐个试；同一路（同一个 SSRC）连续 3 个 RTP 包帧长首尾相接、版本 2、载荷类型不变、序号逐个加一才算认出。
分流时几路交错到达，识别期间最多容许 4 路，之后各路分开计序号。之后按识别用的
几包多数决定**实际编码**（以合法的 ADTS 头开头是 AAC，否则当 PCM，不看车机要的是什么；两头都不像时按车机要的走）、
载荷是不是又一层 `IV16 ‖ 密文`、PCM 载荷像不像声音（像噪声就标为不能放），并判断 PCM 字节序。PCM 载荷前面带
12 字节头（实测 `00 01 ‖ 8 字节 ‖ 数据长度 BE16`）的包，最后两个字节等于其后的数据长度时去掉头再交出，对不上的原样交出；
头部按前几包和每 500 包抽一包记进诊断，用来核对各字段。识别不出来或失步时
只出诊断、转原始模式，**不抛异常**：声音的问题不能让设备会话作废。

## 麦克风（实验）

**本节的流程和载荷格式还没有在真机上验证过**（扬声器那边的外壳和加密已经验证）。手机发来的 SETUP、对能力协商的应答
和没处理的麦克风请求都把头部原样记进诊断，第一次真机联调时拿来对照。

`DmsdpConfig.mic` 不为 null 时声明麦克风服务（服务类型 2、`mic1`；数据会话消息里也是 2）：属性 2005、2004、2003 是空串，
`Mic.profile` 不为 null 时再挂 2102（`MicProfile.json`，形状与扬声器的能力相同）。手机拿 2102 里的 profile 和自己录音要的参数
协商出这次的采样参数（挑最接近的一个），「采样率 × 声道 × 位深」不超过 520000 时自动用 PCM；默认声明 16kHz 单声道、只给 PCM，
手机就会要 PCM。不挂 2102 时手机按自己的默认参数要（可能是 48kHz 立体声 AAC）。手机侧负责重采样成录音应用要的格式。

车机是发送方，消息都在加密的控制连接上（流程见 `MicSession`）：

1. 手机触发：`SET_PARAMETER`（`ServiceID: mic1`、`ServiceType: 2`、`DataSessionID`、`Simple: 1`、
   `ParaAudio: SampleRate=..;Format=..;ChannelNum=..;Codec=..`，正文 `msdp_trigger_method: OPTIONS`）。`ParaAudio` 的 `Codec`
   0 是 AAC、1 是 PCM。车机先回 200（头部 ServiceID、ServiceType、DataSessionID、手机的 CSeq），再发一条**请求**
   `OPTIONS * DMSDP/1.0`：ServiceID、ServiceType、DataSessionID、CSeq（本机计数）、`CryptoVer: 1`、`CryptoAbility: 222`、
   `CryptoAlg: aes128-ctr`、`Fillp: 0`、`TCP: 1`、`Codec`（压缩标志：AAC 写 1、PCM 写 0）、`Simple: 1`。
   手机按 `Fillp`、`TCP` 选收端的传输方式：`Fillp: 0` 加 `TCP: 1` 是 TCP，并且由手机去连车机。
2. 手机建好收端后发 `SETUP`（`Transport: RTP/AVP/UDP;unicast;client_port=P--1`）。车机输出 `DmsdpAction.OpenMicListener`，
   调用方开好监听（端口 S 由系统分配）后调 `micListenerReady(S)`，车机回 200：头部 ServiceID、ServiceType、DataSessionID、
   手机的 CSeq、`Transport: RTP/AVP/UDP;unicast;client_port=P--1;server_port=S--1`。
3. 手机连上 S（`DmsdpChannel.MIC`）：经 `audio.MicStreamListener` 交出 `MicEvent.Started`，调用方开始采集，
   每 20ms 用 `sendMicAudio` 送一块（PCM 是 16 位小端交错样本，AAC 是一个带 ADTS 头的帧），本类打包成要写的帧。
4. 手机 `msdp_trigger_method: TEARDOWN`（或直接发 `TEARDOWN` 请求）→ 回 200，输出 `DmsdpAction.CloseMicTransport`；
   `SET_PARAMETER` 带 `AudioParameter`（新的采样参数）→ 回 200、交出 `MicEvent.ParametersChanged`，之后按新参数送。
   新的 DataSessionID 触发时先拆旧的；手机关掉麦克风连接也结束这次会话。

**上行打包**（`audio.MicPacketizer`）：外壳与媒体声音相同，每个 RTP 包一帧
`0xAB ‖ 长度 BE16（含 IV）‖ IV16 ‖ AES-128-CTR(会话密钥)(RTP)`。RTP 头 12 字节，SSRC 固定 `0x0000044C`（录音流），
时间戳 90kHz（连上时取手机时间同步给的时钟估计，没有就用本机时钟，之后按采样数走）。PCM 的 PT 是 0：一块最多 4096 字节，
每片至多 1024 字节数据，前面带 12 字节分片头（字节 1 是位置：1 第一片或唯一一片、2 中间、3 最后；字节 4～5 是分片序号；
字节 10～11 是本片字节数，其余为 0），最后一片置 marker。AAC 的 PT 是 96，一帧一包，置 marker。
