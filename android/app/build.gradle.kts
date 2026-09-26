plugins {
    // AGP 9.0 起内置 Kotlin 支持，不能再显式声明 org.jetbrains.kotlin.android，
    // 否则报 "The 'org.jetbrains.kotlin.android' plugin is no longer required
    // for Kotlin support since AGP 9.0"。
    id("com.android.application")
    id("com.chaquo.python")
    // 但 Compose 编译器插件不在 AGP 的内置范围里，必须自己应用，
    // 否则 buildFeatures.compose 一开就报错。
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.ytdlp.android"
    // Compose BOM 2026.09.00 里的库要求 compileSdk ≥ 37，所以这里必须用
    // 37（只影响编译期能用哪些 API，不改变运行时行为）。
    // targetSdk 仍留在 36：那是 Android 16 的正式版本号，也是本机的实测版本。
    compileSdk = 37
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

    buildFeatures {
        // 界面用 Compose 写。AGP 9 内置 Kotlin 支持，先只开这个开关 ——
        // 若它同时内置了 Compose 编译器，就不该再声明
        // org.jetbrains.kotlin.plugin.compose，否则会撞版本。
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // 必须让 AGP 把 jniLibs 里的文件解压到应用原生库目录。
            // 默认 useLegacyPackaging=false 时 .so 会留在 APK 内被直接 mmap，
            // nativeLibraryDir 下就没有实体文件，我们的 ffmpeg / qjs 这类
            // 「当作可执行文件调用」的二进制就无从执行。
            useLegacyPackaging = true
        }
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

dependencies {
    // 用 BOM 统一 Compose 各库的版本，避免手工对齐一堆版本号
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.activity:activity-compose:1.13.0")
    // 状态管理用 ViewModel：解析是几十秒的长任务，转屏或切后台不能把
    // 进行中的状态丢掉；collectAsStateWithLifecycle 则保证界面不可见时停订阅
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    // 底部导航要的三个图标（解析/历史/设置）在 core 里就有，
    // 不必引入 extended 那个几 MB 的大包
    implementation("androidx.compose.material:material-icons-core")

    // 播放器。用 Media3 的 ExoPlayer：它是系统级组件，硬解、音轨切换、
    // 各种容器格式都由系统兜底，比自己写 MediaPlayer 省心。
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")

    // 封面图加载。用 Coil2（io.coil-kt）而不是 Coil3（io.coil-kt.coil3）：
    // Coil3 是 Kotlin Multiplatform 库，会带进 kotlin-stdlib 2.4.x 和
    // JetBrains 那套 org.jetbrains.compose，而本项目用的是 androidx Compose
    // 且编译器是 AGP 内置的 Kotlin 2.2 —— 两套体系撞在一起会报
    // "Module was compiled with an incompatible version of Kotlin"。
    // Coil2 是纯 Android 库，依赖 androidx.compose，与本项目同源。
    implementation("io.coil-kt:coil-compose:2.7.0")
}
