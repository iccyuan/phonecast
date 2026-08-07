plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.phonecast.viewer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.phonecast.viewer"
        minSdk = 24
        targetSdk = 34
        versionCode = 6
        versionName = "0.6.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
