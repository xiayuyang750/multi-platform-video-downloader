plugins {
    // AGP 9.0 起内置 Kotlin 支持，不能再显式声明 org.jetbrains.kotlin.android，
    // 否则报 "The 'org.jetbrains.kotlin.android' plugin is no longer required
    // for Kotlin support since AGP 9.0"。
    id("com.android.application")
    id("com.chaquo.python")
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

        ndk {
            // Chaquopy 的 Python 解释器是原生组件，必须显式声明 ABI。
            // 只打 arm64-v8a：测试机是纯 arm64 设备，每多打一个 ABI 就多几 MB。
            // 以后要跑模拟器再补 x86_64。
            abiFilters += listOf("arm64-v8a")
        }
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

chaquopy {
    defaultConfig {
        // buildPython 的大版本必须和 app 的 Python 版本一致，微版本无所谓。
        // 本机只装了 Python 3.12，所以 app 侧也用 3.12。
        version = "3.12"
        buildPython("D:/Dev-env/Python312/python.exe")

        pip {
            install("yt-dlp")
        }
    }
}
