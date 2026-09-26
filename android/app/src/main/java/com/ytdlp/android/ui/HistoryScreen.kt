package com.ytdlp.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ytdlp.android.engine.Video

/** 历史页：按平台筛选 + 列表 + 点开展开播放。对应网页端的第二个视图。 */
@Composable
fun HistoryScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val history by vm.history.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    // 只允许展开一条：每条展开都会起一个 ExoPlayer 实例，同时展开多个
    // 会白占解码器（而且用户也不可能同时看两个）
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current

    Column(modifier.fillMaxSize()) {

        // ---- 标题行 ----
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = Dim.screenPadding, end = Dim.screenPadding, top = Dim.gapLg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "历史记录",
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                color = tone.text,
            )
            if (history.items.isNotEmpty()) {
                TextButton(onClick = { confirmClear = true }) {
                    Text(
                        // 在有筛选时，清空只清当前平台 —— 避免「只想清掉抖音，
                        // 结果全没了」这种误操作
                        if (history.platform == null) "清空全部" else "清空「${history.platform}」",
                        fontSize = Font.meta,
                        color = tone.danger,
                    )
                }
            }
        }

        // ---- 平台筛选 ----
        if (history.counts.isNotEmpty()) {
            LazyRow(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = Dim.gap),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = Dim.screenPadding
                ),
                horizontalArrangement = Arrangement.spacedBy(Dim.gapSm),
            ) {
                item {
                    FilterChip(
                        label = "全部",
                        count = history.items.size,
                        active = history.platform == null,
                        onClick = { vm.selectPlatform(null) },
                    )
                }
                // 按平台名排序，保证每次进来的顺序稳定
                items(history.counts.keys.sorted()) { platform ->
                    FilterChip(
                        label = platform,
                        count = history.counts[platform] ?: 0,
                        active = history.platform == platform,
                        onClick = { vm.selectPlatform(platform) },
                    )
                }
            }
        }

        // ---- 列表 ----
        when {
            history.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("正在读取…", fontSize = Font.meta, color = tone.textMuted)
            }

            history.visible.isEmpty() -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (history.items.isEmpty()) "还没有解析过视频"
                    else "这个平台下没有记录",
                    fontSize = Font.meta,
                    color = tone.textMuted,
                )
            }

            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = Dim.screenPadding,
                    end = Dim.screenPadding,
                    bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(Dim.gapSm),
            ) {
                items(history.visible, key = { it.id }) { video ->
                    HistoryItem(
                        video = video,
                        expanded = expandedId == video.id,
                        onToggle = {
                            expandedId = if (expandedId == video.id) null else video.id
                        },
                        onOpenSource = { uriHandler.openUriSafe(video.sourceUrl) },
                        onDelete = { vm.deleteHistory(video) },
                    )
                }
            }
        }
    }

    if (confirmClear) {
        val scope = history.platform
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("确认清空？", fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    if (scope == null) "将删除全部 ${history.items.size} 条解析记录，此操作不可撤销。"
                    else "将删除「$scope」下的 ${history.counts[scope] ?: 0} 条记录，此操作不可撤销。",
                    fontSize = Font.body,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearHistory(scope)
                    confirmClear = false
                }) { Text("清空", color = tone.danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消", color = tone.textMuted) }
            },
        )
    }
}

/** 筛选标签，对应网页端的 .tab（带数量徽章）。 */
@Composable
private fun FilterChip(label: String, count: Int, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (active) tone.accentSoft else tone.surface)
            .border(1.dp, if (active) tone.accent else tone.border, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            label,
            fontSize = Font.meta,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) tone.accent else tone.textMuted,
        )
        Text(
            count.toString(),
            fontSize = Font.tabBadge,
            color = if (active) tone.accent else tone.textMuted,
        )
    }
}

/** 一条历史记录：折叠时只有一行，点开后向下展开播放区。 */
@Composable
private fun HistoryItem(
    video: Video,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenSource: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dim.radius))
            .background(tone.surface)
            .border(1.dp, tone.border, RoundedCornerShape(Dim.radius))
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(start = Dim.cardPadding, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dim.gap),
        ) {
            PlatformBadge(video.platform)

            Column(Modifier.weight(1f)) {
                Text(
                    video.title.ifBlank { "（无标题）" },
                    fontSize = Font.body,
                    fontWeight = FontWeight.Medium,
                    color = tone.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        if (video.uploader.isNotBlank()) append(video.uploader).append(" · ")
                        append(formatDuration(video.duration).ifBlank { "时长未知" })
                        append(" · ")
                        append(formatRelativeTime(video.resolvedAt))
                        // 已下载的标注一下，用户一眼能看出哪些能离线看
                        if (video.hasLocalFile) append(" · 已下载")
                    },
                    fontSize = Font.hint,
                    color = if (video.hasLocalFile) tone.accent else tone.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            IconButton(onClick = onDelete, modifier = Modifier.size(Dim.touchTarget)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除这条记录",
                    tint = tone.textMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        if (expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = Dim.cardPadding, end = Dim.cardPadding, bottom = Dim.cardPadding),
                verticalArrangement = Arrangement.spacedBy(Dim.gap),
            ) {
                PlayerBox(video)
                if (video.playSource == null) {
                    Hint(
                        "该站点解析出的是音视频分离的流（B站、YouTube 等都已全面 DASH 化），" +
                            "没有可直连播放的地址。下载后这里就能直接播放本地文件。"
                    )
                }
                GhostButton(
                    text = "打开原视频",
                    onClick = onOpenSource,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
