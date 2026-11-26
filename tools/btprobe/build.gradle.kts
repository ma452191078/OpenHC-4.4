// 蓝牙能力探针：在真机上实测普通应用能不能拿到 RFCOMM 监听的实际通道号、能不能读到本机真实蓝牙地址。
// 独立的小应用，不依赖其他模块，也不被其他模块依赖，不进 OpenHC 安装包。用法见 README.md。
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 安装包命名为 openhc-btprobe-<风味>-<构建类型>.apk。
base {
    archivesName.set("openhc-btprobe")
}

android {
    namespace = "io.github.gcbond.openhc.btprobe"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.gcbond.openhc.btprobe"
        minSdk = libs.versions.minSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    // 两个风味只差 targetSdk：系统按 targetSdk 决定放行哪些隐藏接口。包名后缀不同，可以装在同一台设备上对照。
    flavorDimensions += "targetSdk"
    productFlavors {
        create("t36") {
            dimension = "targetSdk"
            applicationIdSuffix = ".t36"
            targetSdk = 36
            manifestPlaceholders["appLabel"] = "蓝牙探针 t36"
        }
        create("t26") {
            dimension = "targetSdk"
            applicationIdSuffix = ".t26"
            targetSdk = 26
            manifestPlaceholders["appLabel"] = "蓝牙探针 t26"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}
