import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

// 发布签名：密钥不进仓库。环境变量 OPENHC_SIGNING 或 local.properties 里的 openhc.signing 指向一份配置文件
// （storeFile、storePassword、keyAlias、keyPassword）；都没有时正式包不签名，调试包照常用调试密钥。
val signingProperties: Properties? = (
    providers.environmentVariable("OPENHC_SIGNING").orNull
        ?: localProperties.getProperty("openhc.signing")
    )
    ?.takeIf { it.isNotBlank() }
    ?.let { path -> Properties().apply { file(path).inputStream().use { load(it) } } }

// 连接参数（openhc-identity.json，格式见 docs/identity.example.json）：不进仓库，构建时从本机目录打进 assets。
// 环境变量 OPENHC_IDENTITY_DIR 优先，其次 local.properties 的 openhc.identity.dir；都没有就不打包，装上后首页提示缺少。
val identityFileName = "openhc-identity.json"
val identityDir: File? = (
    providers.environmentVariable("OPENHC_IDENTITY_DIR").orNull
        ?: localProperties.getProperty("openhc.identity.dir")
    )
    ?.takeIf { it.isNotBlank() }
    ?.let { file(it) }
    ?.also { dir ->
        val names = dir.listFiles()?.map { it.name }?.sorted()
        require(names == listOf(identityFileName)) {
            "连接参数目录 $dir 里必须只有 $identityFileName（实际：$names），防止把别的私有文件打进安装包"
        }
    }

android {
    namespace = "io.github.gcbond.openhc"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.gcbond.openhc"
        // 4.4 fork：minSdk 19（安卓 4.4）。API 21 以下要用 legacy multidex 拆 dex。
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        multiDexEnabled = true
        // versionCode = 主版本 × 10000 + 次版本 × 100 + 补丁号。
        versionCode = 2
        versionName = "0.0.2"
    }

    sourceSets {
        getByName("main") {
            identityDir?.let { assets.srcDir(it) }
        }
    }

    signingConfigs {
        signingProperties?.let { props ->
            create("release") {
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 代码压缩：协议层只用到 BouncyCastle 的 X25519/Ed25519 轻量 API，R8 会把 PQC/JCA/ASN1
            // 等未引用的实现裁掉；保留规则见 proguard-rules.pro，改协议或加反射用法后要重新验证握手。
            isMinifyEnabled = true
            // 顺带清掉依赖里未引用的资源（androidx 的通话/通知图标等）。
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 没提供正式签名（OPENHC_SIGNING / openhc.signing）时用调试密钥签，方便直接装车机验证；
            // 提供了就仍走上面的正式签名配置。
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        // 4.4 fork：Compose 最低 API 21，UI 已全部重写为 View 体系，不再启用 Compose。
        buildConfig = true
        // 仪表盘（DIM）客户端用 AIDL 生成 IMediaDI/IAdapterDI 的 Binder 桩。
        aidl = true
    }

    packaging {
        resources {
            // BouncyCastle（core:auth 用）和 jspecify 都带了这份 OSGi 清单，打包时重名；安卓用不到 OSGi 清单。
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            // 只用到 X25519/Ed25519 轻量 API：PQC（含 1MB 级的 picnic 数据表）和证书路径校验的
            // 语言资源在运行时都不会被读到，直接不打包。
            excludes += "org/bouncycastle/pqc/**"
            excludes += "org/bouncycastle/x509/CertPathReviewerMessages*.properties"
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    // 会话编排：把四个协议层串成一次有线连接，执行器用 java.net，安卓上直接用。
    implementation(project(":core:session"))
    implementation(project(":platform"))
    // 连接状态用 StateFlow 交给界面。
    implementation(libs.kotlinx.coroutines.core)
    // 5.0 无线恢复：配对二维码。zxing-core 纯 Java 无 API 门槛，上游本来就有，fork UI 重写时被删。
    implementation(libs.zxing)
    // 应用内更新：安卓 4.4 的平台 TLS 只有 CBC 套件，GitHub 只收 AEAD；用 BC 的纯 Java JSSE 握手。
    implementation(libs.bouncycastle.tls)

    // 4.4 fork：androidx.core 用仍支持 minSdk 19 的版本（见 libs.versions.toml），
    // 只用 ContextCompat/NotificationCompat 这类纯兼容工具，不用 21+ 的 API。
    implementation(libs.androidx.core.ktx)
    // API 21 以下 legacy multidex（bcprov 等依赖超 64k 方法）。
    implementation(libs.androidx.multidex)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}

// 构建产物统一归档到 <项目根>/dist：assembleDebug / assembleRelease 跑完自动把最新 APK 按版本名拷过去。
val distDir = rootProject.file("dist")
android.applicationVariants.all {
    val variantName = name // debug / release
    val cap = variantName.replaceFirstChar { it.uppercaseChar() }
    val apks = outputs.map { it.outputFile }
    val versionName = android.defaultConfig.versionName ?: "dev"
    // release 用 openhc-android4.4-<版本>.apk；debug 加 -debug 后缀，避免和 release 同名互相覆盖。
    val apkSuffix = if (variantName == "release") "" else "-$variantName"
    val copyTask = tasks.register("copy${cap}ApkToDist", Copy::class) {
        from(apks)
        into(distDir)
        rename { "openhc-android4.4-$versionName$apkSuffix.apk" }
    }
    tasks.named("assemble$cap").configure { finalizedBy(copyTask) }
}

// 静默覆盖安装：Flyme 生产固件屏蔽了 `adb install`（INSTALL_FAILED_USER_RESTRICTED），
// 但 PackageManager 的会话安装（install-create/write/commit）可用。ANDROID_SERIAL 可指定设备。
tasks.register("installDebugSilent") {
    group = "install"
    description = "静默覆盖安装 debug 包（会话安装，绕过 Flyme 对 adb install 的限制）"
    dependsOn("assembleDebug")
    val apk = distDir.resolve("openhc-android4.4-${android.defaultConfig.versionName ?: "dev"}-debug.apk")
    doLast {
        fun exec(vararg cmd: String): String {
            val process = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().readText().trim()
            check(process.waitFor() == 0) { "执行失败：${cmd.joinToString(" ")}\n$text" }
            return text
        }
        val serial = System.getenv("ANDROID_SERIAL")
        fun adb(vararg args: String) =
            if (serial.isNullOrBlank()) arrayOf("adb", *args) else arrayOf("adb", "-s", serial, *args)

        check(apk.isFile) { "找不到 $apk，先跑 assembleDebug" }
        val remote = "/data/local/tmp/openhc-install.apk"
        val size = apk.length().toString()
        logger.lifecycle("推送 ${apk.name}（$size 字节）...")
        exec(*adb("push", apk.absolutePath, remote))
        val session = Regex("""install session \[(\d+)]""")
            .find(exec(*adb("shell", "pm", "install-create", "-r", "-t", "-S", size)))
            ?.groupValues?.get(1)
            ?: error("创建安装会话失败")
        exec(*adb("shell", "pm", "install-write", "-S", size, session, "base", remote))
        val commit = exec(*adb("shell", "pm", "install-commit", session))
        require(commit.contains("Success")) { "安装失败：$commit" }
        exec(*adb("shell", "rm", "-f", remote))
        logger.lifecycle("已静默覆盖安装（安装会话 $session）")
    }
}
