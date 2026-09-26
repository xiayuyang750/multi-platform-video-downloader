package com.ytdlp.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设计基准：数值全部逐条取自 windows/web/style.css 的 :root 与深色媒体查询块。
 *
 * 两端共用同一套基准，是为了让安卓版看起来和 Windows 网页版「像一家人」。
 * 改这里之前请先改 style.css —— 否则两端会悄悄分叉。
 *
 * 为什么不直接照搬 Material3 的默认配色：Material 的紫/青主色和网页端的
 * 蓝（#3563E9）差得远，用默认色会让两端观感完全是两个产品。
 */

/** 一套配色。分浅色/深色两份，对应 style.css 里的两处定义。 */
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

@Composable
fun YtdlpTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
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

/** 网页端 .result-error 的样式：整行红字、保留换行、可直接选中复制。 */
@Composable
fun errorTextStyle() = TextStyle(
    fontSize = Font.meta,
    lineHeight = 22.sp,
    color = tone.danger,
)
