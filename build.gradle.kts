import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import ru.vyarus.gradle.plugin.animalsniffer.AnimalSnifferExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.animalsniffer) apply false
}

val catalog = libs

subprojects {
    // 纯 JVM 模块（协议核心）：产出 Java 11 字节码，测试用 JUnit 4。
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
        }
        extensions.configure<KotlinJvmProjectExtension> {
            compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
        }
        tasks.withType<Test>().configureEach { useJUnit() }
        dependencies { "testImplementation"(catalog.kotlin.test) }
    }

    // 4.4 fork：协议核心要跑在安卓 4.4（API 19）的车机上：用安卓 API 19 签名检查主代码，
    // 防止误用老安卓上没有的 JDK 接口（例如 java.util.Base64、java.time、HexFormat）。
    plugins.withId("ru.vyarus.animalsniffer") {
        extensions.configure<AnimalSnifferExtension> {
            sourceSets = listOf(the<SourceSetContainer>()["main"])
        }
        dependencies {
            "signature"(variantOf(catalog.android.api19.signature) { artifactType("signature") })
        }
    }

    plugins.withId("org.jetbrains.kotlin.android") {
        extensions.configure<KotlinAndroidProjectExtension> {
            compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
        }
    }
}
