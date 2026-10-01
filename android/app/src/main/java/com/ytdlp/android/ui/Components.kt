package com.ytdlp.android.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/** 平台图标：彩色圆角方块 + 白色品牌矢量，对应网页端的 .pf-icon。
 *
 * 网页端并不是「用首字当图标」—— 它内联了 simple-icons 的官方品牌 SVG，
 * 只是安卓端之前偷懒画了个首字。现在两边用同一份路径（见 BrandIcons.kt）。
 * 没有收录品牌路径的平台（目前只有「其他」）才退回原来的首字方案。 */
@Composable
fun PlatformBadge(platform: String, size: Int = 18) {
    // 解析 pathData 有开销，按平台缓存；列表滚动时不该每条都重解析一遍
    val vector = remember(platform) { brandVector(platform) }
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size * 0.28f).dp))
            .background(PlatformColor.of(platform)),
        contentAlignment = Alignment.Center,
    ) {
        if (vector != null) {
            // 网页端 18px 的底色方块里放 13px 的图标，这里按同样的比例缩放
            Image(
                imageVector = vector,
                contentDescription = null,
                modifier = Modifier.size((size * 0.72f).dp),
            )
        } else {
            Text(
                PlatformColor.initial(platform),
                color = Color.White,
                fontSize = (size * 0.6f).sp,
                fontWeight = FontWeight.Bold,
                lineHeight = (size * 0.6f).sp,
            )
        }
    }
}

/**
 * 卡片容器，对应网页端的 .card。
 *
 * 阴影 + 描边一起用：只描边是「线框」，看着像表格；只阴影在浅色背景上又太糊。
 * 网页端本来就是 `border` 和 `box-shadow: var(--shadow-sm)` 并存的，这里对齐它。
 * 深色下 elevation 为 0（见 Tone.cardElevation），只留描边。
 */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(Dim.radius)
    Box(
        modifier
            .fillMaxWidth()
            .shadow(tone.cardElevation, shape)
            .clip(shape)
            .background(tone.surface)
            .border(1.dp, tone.border, shape)
            .padding(Dim.cardPadding)
    ) {
        Column { content() }
    }
}

/**
 * 按下时轻微缩小的触感反馈。
 *
 * Material3 的按钮只有水波纹，手指按下去的那一瞬间「没反应」—— 水波纹是从触点
 * 扩散开的，等它铺满按钮时手指往往已经抬起来了。加一点缩放，按下去立刻有回应。
 * 幅度刻意压到 0.97：再大就成了「弹跳动画」，工具类应用不该这么活泼。
 */
@Composable
private fun pressedScale(interaction: MutableInteractionSource): Float {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        label = "pressed-scale",
    )
    return scale
}

/** 主按钮（实心 accent）。 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val scale = pressedScale(interaction)
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        modifier = modifier
            .defaultMinSize(minHeight = Dim.touchTarget)
            .graphicsLayer { scaleX = scale; scaleY = scale },
        shape = RoundedCornerShape(Dim.radiusSm),
        colors = ButtonDefaults.buttonColors(
            containerColor = tone.accent,
            contentColor = androidx.compose.ui.graphics.Color.White,
        ),
    ) {
        Text(text, fontSize = Font.body, fontWeight = FontWeight.Medium)
    }
}

/** 次要按钮（描边）。 */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val fg = if (danger) tone.danger else tone.text
    val interaction = remember { MutableInteractionSource() }
    val scale = pressedScale(interaction)
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        modifier = modifier
            .defaultMinSize(minHeight = Dim.touchTarget)
            .graphicsLayer { scaleX = scale; scaleY = scale },
        shape = RoundedCornerShape(Dim.radiusSm),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = fg),
    ) {
        Text(text, fontSize = Font.body, fontWeight = FontWeight.Medium)
    }
}

/**
 * 可选中的胶囊标签。历史页的平台筛选、设置页的外观选择共用这一个 ——
 * 同一个视觉元素在两处各写一份，是这类界面最容易悄悄跑偏的地方。
 *
 * @param badge 右侧的计数徽章；没有计数可显示的场景（如外观选择）传 null。
 */
@Composable
fun SelectChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    badge: Int? = null,
) {
    // 选中态做颜色过渡。硬切的话，点一下整个胶囊「啪」地换色，横向滑动切换时尤其明显。
    val bg by animateColorAsState(
        if (selected) tone.accentSoft else tone.surface,
        label = "chip-bg",
    )
    val line by animateColorAsState(
        if (selected) tone.accent else tone.border,
        label = "chip-border",
    )
    val fg by animateColorAsState(
        if (selected) tone.accent else tone.textMuted,
        label = "chip-fg",
    )
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .border(1.dp, line, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            label,
            fontSize = Font.meta,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = fg,
        )
        if (badge != null) {
            Text(badge.toString(), fontSize = Font.tabBadge, color = fg)
        }
    }
}

/** 区块小标题（设置页用）。 */
@Composable
fun FieldLabel(text: String) {
    Text(
        text,
        fontSize = Font.body,
        fontWeight = FontWeight.SemiBold,
        color = tone.text,
    )
}

/** 说明性小字。 */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        fontSize = Font.hint,
        lineHeight = 19.sp,
        color = tone.textMuted,
    )
}

/** 把秒数格式化成 12:34 或 1:02:03。 */
fun formatDuration(seconds: Int?): String {
    if (seconds == null || seconds <= 0) return ""
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * 绝对时间，格式和 Windows 端 app.js 的 formatTime 逐字一致：`2026-10-01 09:31`。
 *
 * 历史页原来用的是相对时间（「刚刚」「3 天前」），实机看下来有两个问题：
 *   1. 用户想知道的是「我什么时候解析的」，相对时间给不出具体时刻；
 *   2. 它和同一行的视频时长（`17:26` 这种）混在一起、又没有标签，
 *      看起来就像两个互相矛盾的时间，实测被当成 bug 报了上来。
 * 两端显示同一件事就该用同一种格式，所以这里直接对齐 Windows。
 */
fun formatDateTime(unixSeconds: Long): String {
    if (unixSeconds <= 0) return ""
    return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(unixSeconds * 1000))
}

/** 字节数格式化（下载进度用）。 */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.size - 1) {
        v /= 1024
        i++
    }
    return if (i == 0) "${bytes} B" else "%.1f %s".format(v, units[i])
}

/** 速度格式化。 */
fun formatSpeed(bytesPerSec: Double): String =
    if (bytesPerSec <= 0) "" else "${formatBytes(bytesPerSec.toLong())}/s"

/** 剩余时间格式化。 */
fun formatEta(seconds: Int?): String {
    if (seconds == null || seconds < 0) return ""
    return when {
        seconds < 60 -> "剩 ${seconds} 秒"
        seconds < 3600 -> "剩 ${seconds / 60} 分"
        else -> "剩 ${seconds / 3600} 小时"
    }
}

/** 让进度条宽度有个稳定的动画观感（网页端 .dl-fill 有 .25s 过渡）。 */
fun percentLabel(percent: Double?): String =
    if (percent == null) "" else "${(percent * 10).roundToInt() / 10.0}%"

/** 空的占位高度，用于列表底部避免被下载条盖住。 */
@Composable
fun Spacer12() = Box(Modifier.height(12.dp))

/**
 * 打开外部链接，失败时静默忽略。
 *
 * UriHandler.openUri 在「没有应用能处理这个链接」时会抛异常（设备上没装
 * 浏览器、或 scheme 被系统拦截），不包一层就是直接崩。解析页和历史页都要用，
 * 所以放在这里而不是各自的文件里。
 */
fun androidx.compose.ui.platform.UriHandler.openUriSafe(url: String) {
    if (url.isBlank()) return
    runCatching { openUri(url) }
}
