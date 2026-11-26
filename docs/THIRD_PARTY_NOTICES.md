# 第三方声明

## 打包进应用的依赖

| 组件 | 作者 | 许可 |
|---|---|---|
| Kotlin 标准库 | JetBrains | Apache-2.0 |
| kotlinx.serialization | JetBrains | Apache-2.0 |
| AndroidX、Jetpack Compose | Android 开源项目 | Apache-2.0 |
| Bouncy Castle（bcprov-jdk18on，只用轻量级 API 做 X25519、Ed25519） | The Legion of the Bouncy Castle Inc. | MIT 风格的 Bouncy Castle 许可，原文见 [licenses/BouncyCastle.txt](licenses/BouncyCastle.txt) |
| ZXing core（com.google.zxing:core，无线连接页生成配对二维码） | ZXing authors | Apache-2.0，原文见 [licenses/Apache-2.0.txt](licenses/Apache-2.0.txt) |

## 只在构建或测试时使用（不进安装包）

| 组件 | 用途 | 许可 |
|---|---|---|
| Gradle、Android Gradle 插件 | 构建 | Apache-2.0 |
| Animal Sniffer Gradle 插件、安卓 API 签名 | 检查协议核心没有用到老安卓上不存在的接口 | MIT |
| JUnit 4 | 单元测试 | EPL-1.0 |

## 移植的代码

| 来源 | 版本 | 许可 | 移植到 |
|---|---|---|---|
| OpenHarmony security_device_auth（deviceauth_lite） | OpenHarmony-v4.0-Release，提交 d7f9169 | Apache-2.0，原文见 [licenses/Apache-2.0.txt](licenses/Apache-2.0.txt) | `core/auth`：`HcError.kt`、`KotlinAccessoryAuthenticator.kt`、`hichain/HiChainMessages.kt`、`hichain/KeyAgreement.kt`（改写为 Kotlin，文件头保留原版权与许可声明并注明改动） |

以后移植宽松许可的代码时，在上表登记来源、版本、许可，并在移植后的文件头保留原版权与许可声明、注明改动。
