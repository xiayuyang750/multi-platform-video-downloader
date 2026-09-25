plugins {
    // AGP 9.0 起内置 Kotlin 支持，不能再显式声明 org.jetbrains.kotlin.android，
    // 否则报 "The 'org.jetbrains.kotlin.android' plugin is no longer required
    // for Kotlin support since AGP 9.0"。
    id("com.android.application")
}

android {
    namespace = "com.ytdlp.android"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.ytdlp.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
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
}
