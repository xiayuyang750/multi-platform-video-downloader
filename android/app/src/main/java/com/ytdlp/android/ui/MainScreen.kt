package com.ytdlp.android.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ytdlp.android.engine.Download

/** 三个页签。用 enum 而不是下标，免得以后插页面时对不上号。 */
private enum class Tab(val label: String, val icon: ImageVector) {
    Parse("解析", Icons.Default.Search),
    History("历史", Icons.Default.List),
    Settings("设置", Icons.Default.Settings),
}

/**
 * 应用主框架：底部导航 + 三个页面 + 常驻的下载进度条。
 *
 * 网页端是「左侧竖排导航 + 主区域 + 底部进度条」。安卓上左侧导航要横向滑动
 * 才能碰到，改成底部导航更符合单手操作习惯；配色和组件形态仍沿用网页端的
 * 设计令牌，所以两端看起来是一家。
 */
@Composable
fun MainScreen(vm: AppViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.Parse) }
    val download by vm.download.collectAsStateWithLifecycle()

    // 存储权限是跳到系统设置页手动开的，用户回来后必须重新检查一次，
    // 否则引导条会一直挂在那里（哪怕已经授权了）。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshStoragePermission()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        containerColor = tone.bg,
        bottomBar = {
            Column {
                // 下载条压在导航栏上方：它在网页端是固定的底部条，
                // 安卓上放这里既不挡内容也不会被系统手势条遮住
                DownloadBar(download, onDismiss = vm::dismissDownload)
                NavigationBar(containerColor = tone.surface) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = t.label, modifier = Modifier.size(22.dp)) },
                            label = { Text(t.label, fontSize = Font.hint) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = tone.accent,
                                selectedTextColor = tone.accent,
                                indicatorColor = tone.accentSoft,
                                unselectedIconColor = tone.textMuted,
                                unselectedTextColor = tone.textMuted,
                            ),
                        )
                    }
                }
            }
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            when (tab) {
                Tab.Parse -> ParseScreen(vm)
                Tab.History -> HistoryScreen(vm)
                Tab.Settings -> SettingsScreen(vm)
            }
        }
    }
}

/**
 * 底部下载进度条，对应网页端的 .dl-bar。
 *
 * 三种形态：
 *   - 进行中：标签 + 百分比/速度/剩余
 *   - 还没拿到百分比（解析、建连阶段）：不确定态，条闪动 ——
 *     否则空条看起来像卡死
 *   - 完成 / 出错：显示结果，右侧给个关闭按钮让它消失
 */
@Composable
private fun DownloadBar(state: Download, onDismiss: () -> Unit) {
    val visible = state.active || state.isDone || state.isError
    if (!visible) return

    val accent = when {
        state.isError -> tone.danger
        else -> tone.accent
    }
    val label = when (state.type) {
        "starting" -> "正在准备下载…"
        "progress" -> "正在下载"
        "processing" -> "正在合并音视频…"
        "done" -> "下载完成"
        "error" -> "下载失败"
        else -> ""
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(tone.surface)
            .padding(horizontal = Dim.screenPadding, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dim.gapSm),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    fontSize = Font.meta,
                    fontWeight = if (state.isDone) FontWeight.SemiBold else FontWeight.Normal,
                    color = when {
                        state.isError -> tone.danger
                        else -> tone.text
                    },
                )
                // 第二行给出「还差什么信息」：出错给原因、完成给文件名、
                // 进行中给速度与剩余时间
                val detail = when {
                    state.isError -> state.message.lineSequence().firstOrNull().orEmpty()
                    state.isDone -> state.path.substringAfterLast('/')
                    state.active -> listOfNotNull(
                        percentLabel(state.percent).takeIf { it.isNotBlank() },
                        formatSpeed(state.speed).takeIf { it.isNotBlank() },
                        formatEta(state.eta).takeIf { it.isNotBlank() },
                    ).joinToString(" · ")
                    else -> ""
                }
                if (detail.isNotBlank()) {
                    Text(
                        detail,
                        fontSize = Font.hint,
                        color = if (state.isError) tone.danger else tone.textMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!state.active) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = tone.textMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        // 进度条本体
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(tone.surfaceHover)
        ) {
            if (state.active && state.percent == null) {
                // 不确定态：整条左右脉冲，表示「在干活但还不知进度」
                val transition = rememberInfiniteTransition(label = "dl")
                val alpha by transition.animateFloat(
                    initialValue = 0.3f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1100),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "dl-alpha",
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .alpha(alpha)
                        .background(accent)
                )
            } else {
                val fraction = ((state.percent ?: if (state.isDone) 100.0 else 0.0) / 100.0)
                    .coerceIn(0.0, 1.0).toFloat()
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(999.dp))
                        .background(accent)
                )
            }
        }
    }
}
