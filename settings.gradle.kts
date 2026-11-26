pluginManagement {
    repositories {
        // 4.4 fork：国内镜像优先，官方仓库兜底。
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 4.4 fork：国内镜像优先，官方仓库兜底。
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "openhc"

// 协议核心：纯 Kotlin/JVM，不依赖安卓，可在电脑上直接跑测试。按协议分层拆模块。
include(":core:common")
include(":core:nearby")
include(":core:auth")
include(":core:dmsdp")
include(":core:hisight")
// 会话编排：把上面几层串成一次完整连接，并提供基于 java.net 的阻塞 I/O 执行器（纯 JVM）。
include(":core:session")

// 安卓适配层（USB、网络、解码、蓝牙、WiFi）。
include(":platform")

// 应用本体。
include(":app")

// 开发工具：在电脑的 JVM 上直接连真手机跑完整会话（不打进安装包）。
include(":tools:desktop")
// 4.4 fork：真机实测小工具是 Compose 界面，本 fork 已移除 Compose，不再参与构建。
// include(":tools:btprobe")
