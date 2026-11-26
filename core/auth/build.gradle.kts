plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.animalsniffer)
}

dependencies {
    api(project(":core:common"))
    // 只用 BouncyCastle 的轻量级 API（X25519、Ed25519），不注册 JCA Provider：
    // 安卓 6.0 自带的 JDK 没有这两种曲线。
    implementation(libs.bouncycastle.prov)
}
