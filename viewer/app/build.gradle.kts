import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 签名配置来源: 环境变量(CI) 优先, 其次 local.properties(本机)。
 * 二者都不入库 —— 但两边必须是同一个 keystore, 否则新版 APK 签名不一致会装不上去,
 * 应用内自动更新就会失败。
 */
val signingProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingValue(name: String): String? =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: signingProps.getProperty(name)?.takeIf { it.isNotBlank() }

val keystoreFile = signingValue("SIGNING_STORE_FILE")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.phonecast.viewer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.phonecast.viewer"
        minSdk = 24
        targetSdk = 34
        // versionName 必须与发布 tag 一致, 否则更新检查会反复提示同一个版本
        versionCode = 15
        versionName = "0.9.2"
    }

    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = keystoreFile
                storePassword = signingValue("SIGNING_STORE_PASSWORD")
                keyAlias = signingValue("SIGNING_KEY_ALIAS")
                keyPassword = signingValue("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 没配 keystore 时不签名(构建仍能过), 但产物不可用于覆盖安装
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            // 本机 debug 也用正式签名: 这样 adb 装的包和 Release 产物能互相覆盖升级
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    // 打包出 app-release.apk / app-debug.apk 之外再给一个稳定名字, 方便 CI 取
    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                .outputFileName = "phonecast-viewer-$name.apk"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // 纯 Java 的二维码解码核心 (不含 UI/相机), 用于 App 内置扫码器。
    // 系统相机大多不会打开 phonecast:// 自定义协议, 所以必须自己扫。
    implementation("com.google.zxing:core:3.5.3")
}
