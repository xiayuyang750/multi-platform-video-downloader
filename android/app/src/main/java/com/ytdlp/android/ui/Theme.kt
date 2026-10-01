package com.ytdlp.android.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设计基准。**安卓端自 v1.0.3 起独立演进，不再要求与 windows/web/style.css 逐值对齐**。
 *
 * 背景：v1.0.3 决定把安卓端做出更强的品牌辨识度（网页端不动），于是两端从
 * 「同一套令牌」变成了「同一套骨架、各自长肉」。配色仍沿用网页端那套蓝，
 * 所以看着还是一家人；但新增的阴影、动效、品牌渐变是安卓端独有的，
 * 不要再去 style.css 里找对应值 —— 找不到是正常的。
 *
 * 为什么不直接照搬 Material3 的默认配色：Material 的紫/青主色和网页端的
 * 蓝（#3563E9）差得远，用默认色会让两端观感完全是两个产品。
 */

/** 一套配色。分浅色/深色两份。 */
data class Tone(
    val bg: Color,
    val surface: Color,
    val surfaceHover: Color,
    val text: Color,
    val textMuted: Color,
    val border: Color,
    val accent: Color,
    val accentHover: Color,
    val accentSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
    /** 品牌渐变的两端色。语义见文件末尾的 Brand 注释。 */
    val brandStart: Color,
    val brandEnd: Color,
    /** 卡片投影高度。深色下为 0 —— 深色背景上的黑色阴影看不见，只会显脏。 */
    val cardElevation: Dp,
)

private val LightTone = Tone(
    bg = Color(0xFFF5F6F8),
    surface = Color(0xFFFFFFFF),
    surfaceHover = Color(0xFFF9FAFB),
    text = Color(0xFF1A1D21),
    textMuted = Color(0xFF6B7280),
    border = Color(0xFFE5E7EB),
    accent = Color(0xFF3563E9),
    accentHover = Color(0xFF2A50C4),
    accentSoft = Color(0xFFEEF2FE),
    danger = Color(0xFFDC2626),
    dangerSoft = Color(0xFFFEF2F2),
    // 浅色下与启动图标逐值相同 —— 用户从桌面点进来，看到的和图标是同一个色
    brandStart = Color(0xFF34D399),
    brandEnd = Color(0xFF0EA5E9),
    cardElevation = 2.dp,
)

private val DarkTone = Tone(
    bg = Color(0xFF131519),
    surface = Color(0xFF1C1F25),
    surfaceHover = Color(0xFF23272E),
    text = Color(0xFFE8EAED),
    textMuted = Color(0xFF9AA1AC),
    border = Color(0xFF2C3037),
    accent = Color(0xFF5B84FF),
    // 深色下悬停/按下是更亮而不是更暗，网页端也是这么定的
    accentHover = Color(0xFF7A9BFF),
    accentSoft = Color(0xFF1E2740),
    danger = Color(0xFFF87171),
    dangerSoft = Color(0xFF2A1B1B),
    // 深色下把图标那两个色按 0.8 倍压暗：原值在 #131519 上会亮得发飘，
    // 尤其 #34D399 这种高明度绿，大面积铺开会刺眼
    brandStart = Color(0xFF2AA97A),
    brandEnd = Color(0xFF0B84BA),
    cardElevation = 0.dp,
)

/** 尺寸令牌，对应 style.css 的 --radius-* 与各处 padding。 */
object Dim {
    val radiusSm = 8.dp
    val radius = 12.dp
    val radiusLg = 16.dp

    /** 页面左右留白。网页端是 32px，手机上按 16dp 收紧，否则内容区太窄 */
    val screenPadding = 16.dp

    /** 卡片内边距，网页端 .card 是 20px */
    val cardPadding = 16.dp

    val gapSm = 6.dp
    val gap = 10.dp
    val gapLg = 16.dp

    /**
     * 可点区域的最小高度。网页端按钮是 10px padding（约 40px 高），
     * 但安卓触控规范要求 48dp，小于这个值手指容易点空。
     */
    val touchTarget = 48.dp
}

/** 字号令牌，对应 style.css 里的各处 font-size。 */
object Font {
    val body = 14.sp          // body
    val viewTitle = 20.sp     // .view-title
    val cardTitle = 16.sp     // .result-title
    val meta = 13.sp          // .result-meta
    val hint = 12.5.sp        // .field-hint
    val tabBadge = 11.5.sp    // .tab-badge
}

/** 平台标识色，对应 style.css 的 .pf-* 系列。 */
object PlatformColor {
    val youtube = Color(0xFFFF0033)
    val bilibili = Color(0xFF00A1D6)
    val x = Color(0xFF111111)
    val douyin = Color(0xFFFE2C55)
    val tiktok = Color(0xFF161823)
    val instagram = Color(0xFFC13584)
    val other = Color(0xFF6B7280)

    /** 按平台名取色。名字来自 engine 的 detect_platform。 */
    fun of(platform: String): Color = when (platform) {
        "YouTube" -> youtube
        "B站" -> bilibili
        "X" -> x
        "抖音" -> douyin
        "TikTok" -> tiktok
        "Instagram" -> instagram
        else -> other
    }

    /** 圆角方块里的首字（网页端 .pf-icon 就是这么做的，不依赖外部资源）。 */
    fun initial(platform: String): String = when (platform) {
        "YouTube" -> "Y"
        "B站" -> "B"
        "X" -> "X"
        "抖音" -> "抖"
        "TikTok" -> "T"
        "Instagram" -> "I"
        else -> "其"
    }
}

val LocalTone = staticCompositionLocalOf { LightTone }

/** 取当前配色。写成属性是为了少 import 一层，用起来就是 tone.accent。 */
val tone: Tone
    @Composable get() = LocalTone.current

/**
 * 品牌渐变画刷（薄荷绿 → 天蓝）。
 *
 * 和 [Tone.accent] 是**两个角色**，不要混着用：
 *   - `accent` 是**交互色**，管「能点的地方」—— 按钮、选中文字、链接、光标。
 *   - `brandBrush` 是**标识色**，管「我是谁」—— 进度条、导航选中态、空态图形。
 *
 * 为什么这么分：把绿色铺到按钮上，用户会失去「蓝色=可点」这条已经建立的直觉；
 * 但只把渐变用在少数几个标识位，既不破坏操作直觉，又能让人一眼认出这是哪个 App。
 * 浅色下的两个色值抄自 ic_launcher_background.xml，改色时两处一起改。
 */
val brandBrush: Brush
    @Composable get() {
        // tone 必须先取成局部变量：@Composable 属性不能在 remember 这类
        // 普通 lambda 里读，编译器会报「@Composable invocations can only
        // happen from the context of a @Composable function」。
        val t = tone
        return remember(t) { Brush.linearGradient(listOf(t.brandStart, t.brandEnd)) }
    }

/** 外观模式。[System] 跟随手机的深浅色设置，另外两个是用户的强制选择。 */
enum class ThemeMode(val label: String) {
    System("跟随系统"),
    Light("浅色"),
    Dark("深色"),
}

/**
 * 外观偏好的持久化。
 *
 * 为什么用 SharedPreferences 而不是走 engine 的 Settings（Python 侧）：
 * 外观是**纯 UI 偏好**，引擎没有任何理由知道它。为了取一个颜色选择走一趟
 * Chaquopy 往返，既慢又把 UI 和引擎耦在一起 —— 这是全项目第一处 SharedPreferences，
 * 但它只存这一件事，不与引擎的设置混在一起。
 */
object ThemePrefs {
    private const val FILE = "ui_prefs"
    private const val KEY_MODE = "theme_mode"

    fun load(ctx: Context): ThemeMode {
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_MODE, null)
        // 认不出的值（改过名的枚举、手改过的偏好文件）一律退回跟随系统，
        // 不能因为一个字符串对不上就崩在启动路径上
        return ThemeMode.entries.firstOrNull { it.name == raw } ?: ThemeMode.System
    }

    fun save(ctx: Context, mode: ThemeMode) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, mode.name).apply()
    }
}

/**
 * 把外观模式解析成「这一帧到底用深色还是浅色」。
 *
 * 放在这里而不是各个 Activity 里，是为了让「System 才读系统设置」这条规则
 * 只有一处实现 —— 否则主界面和反馈页很容易各写一遍、然后某天分叉。
 */
@Composable
fun YtdlpTheme(
    mode: ThemeMode = ThemeMode.System,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val t = if (darkTheme) DarkTone else LightTone

    // 把自定义令牌映射到 Material3 的槽位，这样 Material 自带的组件
    // （TextField、NavigationBar 等）也会自动贴近网页端的观感，
    // 不必每个组件都手写配色。
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = t.accent,
            onPrimary = Color.White,
            primaryContainer = t.accentSoft,
            onPrimaryContainer = t.accent,
            background = t.bg,
            onBackground = t.text,
            surface = t.surface,
            onSurface = t.text,
            surfaceVariant = t.surfaceHover,
            onSurfaceVariant = t.textMuted,
            outline = t.border,
            outlineVariant = t.border,
            error = t.danger,
            onError = Color.White,
            errorContainer = t.dangerSoft,
            onErrorContainer = t.danger,
        )
    } else {
        lightColorScheme(
            primary = t.accent,
            onPrimary = Color.White,
            primaryContainer = t.accentSoft,
            onPrimaryContainer = t.accent,
            background = t.bg,
            onBackground = t.text,
            surface = t.surface,
            onSurface = t.text,
            surfaceVariant = t.surfaceHover,
            onSurfaceVariant = t.textMuted,
            outline = t.border,
            outlineVariant = t.border,
            error = t.danger,
            onError = Color.White,
            errorContainer = t.dangerSoft,
            onErrorContainer = t.danger,
        )
    }

    val typography = Typography().let {
        it.copy(
            bodyMedium = it.bodyMedium.copy(fontSize = Font.body),
            titleLarge = it.titleLarge.copy(fontSize = Font.viewTitle, fontWeight = FontWeight.SemiBold),
            titleMedium = it.titleMedium.copy(fontSize = Font.cardTitle, fontWeight = FontWeight.SemiBold),
            bodySmall = it.bodySmall.copy(fontSize = Font.meta, color = t.textMuted),
            labelSmall = it.labelSmall.copy(fontSize = Font.hint, color = t.textMuted),
        )
    }

    CompositionLocalProvider(LocalTone provides t) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
