// 插件版本集中在这里。AGP 9 内置 Kotlin 支持，所以不声明
// org.jetbrains.kotlin.android（声明了反而报「AGP 9.0 起不再需要」）。
plugins {
    id("com.android.application") version "9.2.0" apply false
    id("com.chaquo.python") version "17.0.0" apply false
    // Kotlin 2.0 起 Compose 编译器改由独立插件提供，AGP 不会自动带上它，
    // 少了这句 `buildFeatures { compose = true }` 会直接报错要求补上。
    // 版本必须与 AGP 内置的 Kotlin 编译器一致，否则会撞版本。
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
}
