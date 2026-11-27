// 安卓适配层：把协议核心需要的「字节进出」接到安卓的 USB、网络、编解码、蓝牙、WiFi 上。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.gcbond.openhc.platform"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":core:common"))
    // media 包的对外接口直接用协议层的类型（解码器实现 AccessUnitSink，触控换算产出 HiSightInput），
    // 所以用 api 暴露给依赖方。
    api(project(":core:hisight"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}
