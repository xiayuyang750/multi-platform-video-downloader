// java.util.Properties 必须 import 进来用：在 Kotlin DSL 里直接写
// java.util.Properties 会被解析成 Gradle 的 java 扩展（JavaPluginExtension），
// 报 "Unresolved reference 'util'"。
import java.util.Properties

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

// 签名信息放在 android/keystore.properties（不进版本控制）。
// 文件不存在时留空 —— 这样别人 clone 下来仍能跑 assembleDebug，
// 不会因为缺签名文件而连调试包都构建不了。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
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
        // 1.0.3：界面视觉焕新（品牌渐变、卡片质感、动效），安卓端自此与网页端
        // 各自演进 —— 详见 ui/Theme.kt 顶部注释
        versionCode = 3
        versionName = "1.0.3"

        ndk {
            // Chaquopy 的 Python 解释器是原生组件，必须显式声明 ABI。
            // 只打 arm64-v8a：测试机是纯 arm64 设备，每多打一个 ABI 就多几 MB。
            // 以后要跑模拟器再补 x86_64。
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 拿到签名配置才挂上去，否则 release 构建会因为缺 keystore 直接失败
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        // 界面用 Compose 写。AGP 9 内置 Kotlin 支持，先只开这个开关 ——
        // 若它同时内置了 Compose 编译器，就不该再声明
        // org.jetbrains.kotlin.plugin.compose，否则会撞版本。
        compose = true
        // 检查更新要读 BuildConfig.VERSION_NAME 做版本比较
        buildConfig = true
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

// 产物直接叫「水印工坊.apk」，而不是默认的 app-release.apk ——
// 用户拿到手、在文件管理器里看到的就是这个可读的名字。
// 版本号不塞进文件名：名字保持固定，版本信息由 APK 内部和发布页承担。
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("水印工坊.apk")
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
    // 页面转场（AnimatedContent）、卡片展开（animateContentSize）、选中态变色
    // （animateColorAsState）都在这个 artifact 里。它本来就是 foundation / material3
    // 的运行时依赖，所以不会让 APK 变大 —— 显式声明只是为了让它出现在**编译**类路径上
    // （implementation 级别的传递依赖不进编译类路径，写代码时会报 Unresolved reference）。
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.13.0")
    // 状态管理用 ViewModel：解析是几十秒的长任务，转屏或切后台不能把
    // 进行中的状态丢掉；collectAsStateWithLifecycle 则保证界面不可见时停订阅
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    // 底部导航要的三个图标（解析/历史/设置）在 core 里就有。
    // extended 是为了历史项那几个操作图标（复制链接 / 重新解析 / 播放 / 下载）——
    // core 里没有「复制」这类图标，用近似的 Share 会让人误以为是分享，
    // 图标语义错了比多占几 MB 更糟。
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")

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
