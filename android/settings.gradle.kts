// 安卓端工程设置。版本组合刻意选在 Chaquopy 17 官方支持的区间内：
//   AGP 9.2      ← Chaquopy 17 支持 AGP 7.3 ~ 9.2，9.2 是上界
//   Gradle 9.7.1 ← AGP 9.2 要求 Gradle ≥ 9.4.1，用系统已装版本，无需再下载发行版
// 注意：AGP 8.x 在这台机器上不可用 —— Gradle 9.6.0 起移除了 AGP 8.x 依赖的
// 内部 API（InternalProblems），会直接报 "Failed to create service
// AndroidProblemReporterProvider"。所以这里必须走 AGP 9.x。
//
// 仓库顺序说明：本机直连 dl.google.com 时通时断，所以把阿里云镜像放前面（响应快、
// 有同步 Google Maven），官方源放后面兜底 —— 镜像没有的版本会自动回落到官方源。
pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
        // Chaquopy 自己的 Android wheel 仓库：pip 装包时从这里取预编译轮子
        maven { url = uri("https://chaquo.com/maven") }
    }
}

rootProject.name = "ytdlp-android"
include(":app")
