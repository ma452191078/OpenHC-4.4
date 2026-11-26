// 协议各层共用的字节读写、编码工具与身份资产格式。通用配置见根目录 build.gradle.kts。
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.animalsniffer)
}

dependencies {
    api(libs.kotlinx.serialization.json)
}
