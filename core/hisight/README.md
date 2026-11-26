# core:hisight —— 投屏媒体

- RTSP 控制（能力查询与设置、SETUP、PLAY、保活、结束）。
- 视频走 TCP 的分帧、RTP 解包、PES 重组与载荷解密，输出 H.264 基本流交给平台层解码。
- 反向控制（触摸、按键）的封包。按键用安卓键码，按下、抬起各一个包；媒体键（播放、暂停、上一首、下一首等）
  另有宽松的开关 `mediaKeyInput`：手机不选回按键事件或不选源设备类型 7 都不让协商失败，没有 7 时只能发基础键、修饰位为 0。

解码、显示、触摸采集都在 `:platform` / `:app`，这里只做协议。

## 用法

入口是 `HiSightReceiver`，不持有连接、线程或定时器：调用方建连、收发，把收到的字节喂进来，
按返回的 `HiSightOutput` 依次执行（发字节、连端口、关连接）。三条 TCP 都由车机主动连到手机：

1. 设备会话层给出 HiSight 端口和 16 字节会话密钥 → 连控制端口 → `start()`。
2. 控制连接收到的字节 → `feedControl(…, wallClockMillis)`（墙上时间只用于 Date 头）。
3. 返回 `Connect(VIDEO / REMOTE_CONTROL, port)` → 连上后调 `onVideoConnected()` / `onRemoteControlConnected()`，
   需要的连接都就绪后返回 PLAY。
4. 视频连接收到的字节 → `feedVideo(…)`，每帧同步回调 `AccessUnitSink`。`AccessUnit` 及其缓冲区会复用，
   只在回调期间有效，要保留就 `copyBytes()`。
5. 触摸、按键 → `sendInput(…)`，返回的包写到回控连接。
6. 本机结束 → `shutdown()`；手机要求结束或连接被关时，对应方法直接返回收尾动作。

控制、视频、输入三组方法可以在不同线程调用，同一组内要串行。对端数据不合法抛
`MalformedMessageException`，之后调 `shutdown()` 收尾。

## 测试

`src/test/resources/hisight-vectors.json` 是对照向量：同样的合成输入（密钥 00..0f、确定的 IV 序列、
构造的 H.264 字节、保留域名）在参考实现上的输出。测试逐字节比对控制帧、视频访问单元和回控包，
并对控制消息解析器做差分（含逐字节截断和头部区域的单字节替换）。
