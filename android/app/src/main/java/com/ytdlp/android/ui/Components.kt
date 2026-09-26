package com.ytdlp.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/** 平台图标：圆角方块 + 首字，对应网页端的 .pf-icon。
 *
 * 网页端刻意不用外部图片资源（避免加载失败/版权问题），安卓端沿用同一做法。 */
@Composable
fun PlatformBadge(platform: String, size: Int = 18) {
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(PlatformColor.of(platform)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            PlatformColor.initial(platform),
            color = androidx.compose.ui.graphics.Color.White,
            fontSize = (size * 0.6f).sp,
            fontWeight = FontWeight.Bold,
            lineHeight = (size * 0.6f).sp,
        )
    }
}

/** 卡片容器，对应网页端的 .card。 */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dim.radius))
            .background(tone.surface)
            .border(1.dp, tone.border, RoundedCornerShape(Dim.radius))
            .padding(Dim.cardPadding)
    ) {
        Column { content() }
    }
}

/** 主按钮（实心 accent）。 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = Dim.touchTarget),
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
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = Dim.touchTarget),
        shape = RoundedCornerShape(Dim.radiusSm),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = fg),
    ) {
        Text(text, fontSize = Font.body, fontWeight = FontWeight.Medium)
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

/** 相对时间。历史列表里用，超过一周就直接显示日期。 */
fun formatRelativeTime(unixSeconds: Long): String {
    if (unixSeconds <= 0) return ""
    val diff = System.currentTimeMillis() / 1000 - unixSeconds
    return when {
        diff < 60 -> "刚刚"
        diff < 3600 -> "${diff / 60} 分钟前"
        diff < 86400 -> "${diff / 3600} 小时前"
        diff < 86400 * 2 -> "昨天"
        diff < 86400 * 7 -> "${diff / 86400} 天前"
        else -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date(unixSeconds * 1000))
    }
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
