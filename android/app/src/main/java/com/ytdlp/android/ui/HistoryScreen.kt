package com.ytdlp.android.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ytdlp.android.engine.Video
import kotlinx.coroutines.launch

/** 历史页：按平台筛选 + 列表 + 点开展开播放。对应网页端的第二个视图。 */
@Composable
fun HistoryScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val history by vm.history.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    // 只允许展开一条：每条展开都会起一个 ExoPlayer 实例，同时展开多个
    // 会白占解码器（而且用户也不可能同时看两个）
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current

    // 展开时要把这条滚到列表顶部。展开后的内容（标题 + 信息行 + 播放器 + 按钮）
    // 比一屏还高，若用户点的是靠下的条目，展开出来的东西有一半在屏幕外，
    // 想看标题得手动往上划 —— 实测就是这个手感问题。
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

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
                    SelectChip(
                        label = "全部",
                        badge = history.items.size,
                        selected = history.platform == null,
                        onClick = { vm.selectPlatform(null) },
                    )
                }
                // 按平台名排序，保证每次进来的顺序稳定
                items(history.counts.keys.sorted()) { platform ->
                    SelectChip(
                        label = platform,
                        badge = history.counts[platform] ?: 0,
                        selected = history.platform == platform,
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
                EmptyState(
                    // 两种空态要分开说：一个是「从来没用过」，一个是「这个筛选下没有」。
                    // 后者还提示用户下一步往哪走，否则会以为记录丢了。
                    icon = if (history.items.isEmpty()) Icons.Default.Search else Icons.Default.List,
                    title = if (history.items.isEmpty()) "还没有解析过视频" else "这个平台下没有记录",
                    hint = if (history.items.isEmpty()) "去解析页粘贴一个链接试试"
                    else "换个平台标签看看",
                )
            }

            else -> LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
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
                            val expanding = expandedId != video.id
                            expandedId = if (expanding) video.id else null
                            if (expanding) {
                                val index = history.visible.indexOfFirst { it.id == video.id }
                                if (index >= 0) {
                                    scope.launch { listState.animateScrollToItem(index) }
                                }
                            }
                        },
                        onCopy = { vm.copyText(video.sourceUrl) },
                        onReanalyze = { vm.reanalyze(video) },
                        onDownload = { vm.startDownload(video) },
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

/**
 * 空态。原来是一行灰色小字居中，看着像「加载失败」而不是「还没东西」。
 *
 * 换成品牌渐变方块 + 图标 + 主副文案：一是填满整屏的空白，二是让品牌色
 * 在这里出现一次 —— 空列表是用户最容易盯着看几秒的界面。
 */
@Composable
private fun EmptyState(icon: ImageVector, title: String, hint: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dim.gap),
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(brandBrush),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(30.dp),
            )
        }
        Text(title, fontSize = Font.body, fontWeight = FontWeight.Medium, color = tone.text)
        Text(hint, fontSize = Font.hint, color = tone.textMuted)
    }
}

/**
 * 一条历史记录。
 *
 * 折叠态刻意做得极窄：单行、只有「图标 + 标题 + 时间」，一屏能多塞几条。
 * 作者、平台账号 ID、原始链接、播放器这些全部收进展开态 —— 折叠态本来也放不下。
 */
@Composable
private fun HistoryItem(
    video: Video,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCopy: () -> Unit,
    onReanalyze: () -> Unit,
    onDownload: () -> Unit,
    onOpenSource: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            // 展开/收起平滑过渡。原来是瞬变：点一下，底下「唰」地多出半屏内容，
            // 用户根本来不及看清是新展开了什么。配合外层的 clip，长出来的过程
            // 是被卡片边框裁着的，不会溢出到相邻条目上。
            .animateContentSize()
            .clip(RoundedCornerShape(Dim.radius))
            .background(tone.surface)
            .border(1.dp, tone.border, RoundedCornerShape(Dim.radius))
    ) {
        CompactRow(
            video = video,
            expanded = expanded,
            onToggle = onToggle,
            trailing = {
                // 折叠态就给出操作，与 Windows 端一致。
                // 播放和下载不收在这一排：手机屏窄，五个图标挨一起容易误触。
                MiniAction(Icons.Default.ContentCopy, "复制链接", onCopy)
                MiniAction(Icons.Default.Refresh, "重新解析", onReanalyze)
                MiniAction(Icons.Default.Delete, "删除这条记录", onDelete)
            },
        )

        if (expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = Dim.cardPadding,
                        end = Dim.cardPadding,
                        bottom = Dim.cardPadding,
                    ),
                verticalArrangement = Arrangement.spacedBy(Dim.gapSm),
            ) {
                // 这里刻意不再重复标题和平台：
                //   - 标题就在紧邻的上方那一行，展开时它自己会展开成完整的多行标题；
                //   - 平台由旁边的品牌图标说明，不需要再写一遍文字。
                // 早先在这里又画了一遍「图标 + 完整标题」，结果和上面折叠行的截断标题
                // 上下并排共存 —— 看起来就是同一个标题写了两遍。
                //
                // 解析时间也不在这里重复：它就写在标题下方那一行，展开后依然在。
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    DetailLine("作者", video.uploader)
                    DetailLine("账号 ID", video.uploaderId)
                    DetailLine("时长", formatDuration(video.duration))
                    // 原始解析链接：之前界面上完全没有，用户想核对「当初贴的是哪个链接」
                    // 时无从下手。链接实测能铺到 14 行，所以默认折成 2 行、点击展开。
                    DetailLine("原始链接", video.sourceUrl, collapsible = true)
                    if (video.hasLocalFile) DetailLine("本地文件", "已下载")
                }

                PlayerBox(video)
                if (video.playSource == null) {
                    Hint(
                        "该站点解析出的是音视频分离的流（B站、YouTube 等都已全面 DASH 化），" +
                            "没有可直连播放的地址。下载后这里就能直接播放本地文件。"
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Dim.gap)) {
                    PrimaryButton(
                        text = "下载",
                        onClick = onDownload,
                        modifier = Modifier.weight(1f),
                    )
                    GhostButton(
                        text = "打开原视频",
                        onClick = onOpenSource,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** 折叠态里的小操作按钮。比常规 48dp 触控目标小一圈，换取更紧的行高。 */
@Composable
private fun RowScope.MiniAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(icon, contentDescription = label, tint = tone.textMuted, modifier = Modifier.size(17.dp))
    }
}

/**
 * 折叠态那一行：第一行 `[图标] 标题…… [操作图标]`，第二行 `解析时间`。
 *
 * 时间为什么单独占一行（而不是挤在第一行右侧）：算过宽度 —— 行内可用约 1085px，
 * 平台图标 + 三个操作图标 + 各处间隙就吃掉约 630px，再塞一个 `2026-10-01 10:06`
 * （约 310px），标题只剩 5 个字。先前的「放得下才显示时间」实测下来更糟：
 * 8 条记录一条都没显示出时间。标题和时间都不能丢，那就多占一行。
 *
 * 展开后这一行的标题**就地展开成完整标题**（不再截断），而不是在下面另起一个
 * 完整标题 —— 那样上下会并排出现两个标题，看着就是写重了。
 */
@Composable
private fun CompactRow(
    video: Video,
    expanded: Boolean,
    onToggle: () -> Unit,
    trailing: @Composable RowScope.() -> Unit,
) {
    val time = formatDateTime(video.resolvedAt)
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = Dim.cardPadding, end = 2.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            // 标题展开成多行后，图标和操作按钮贴顶对齐才不会悬在标题中间
            verticalAlignment = if (expanded) Alignment.Top else Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dim.gapSm),
        ) {
            PlatformBadge(video.platform)
            Text(
                video.title.ifBlank { "（无标题）" },
                fontSize = Font.body,
                fontWeight = FontWeight.Medium,
                lineHeight = 20.sp,
                color = tone.text,
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            trailing()
        }

        if (time.isNotEmpty() || video.hasLocalFile) {
            Text(
                buildString {
                    append(time)
                    // 已下载的标注一下，用户一眼能看出哪些能离线看
                    if (video.hasLocalFile) {
                        if (time.isNotEmpty()) append(" · ")
                        append("已下载")
                    }
                },
                fontSize = Font.hint,
                color = if (video.hasLocalFile) tone.accent else tone.textMuted,
                maxLines = 1,
                // 与标题左对齐：让过平台图标那一列（18dp 图标 + 6dp 间隙）
                modifier = Modifier.padding(start = 24.dp, top = 2.dp),
            )
        }
    }
}

/** 展开态里的一行「标签 值」。标签定宽，值换行 —— 长链接不会把布局撑破。
 *
 * 行高压到 15sp（字号 12.5sp 的 1.2 倍）：默认行距会把每行撑到近 18sp，
 * 五六行加起来就是几十像素的「空档」，观感上像是有意留的白。
 *
 * @param collapsible 默认只显示 2 行、点一下展开，末尾跟一个箭头图标。
 *   只有「原始链接」需要它：B站那种分享链接带一堆跟踪参数（buvid / spmid /
 *   share_session_id…），完整铺出来实测有 14 行、742px，占掉四分之一屏。
 *   值本身很短的行不该跟着变成可点的 —— 点一下什么都没发生比不可点更让人困惑，
 *   所以做成显式开关而不是按长度猜。
 *
 *   箭头是必须的：一开始只靠文本末尾的「…」，实测确实没人意识到这里能点开 ——
 *   省略号只说明「被截断了」，不等于「可展开」。
 */
@Composable
private fun DetailLine(label: String, value: String, collapsible: Boolean = false) {
    if (value.isBlank()) return
    // 用 value 做 key：换一条记录展开时，折叠状态要跟着重置
    var expanded by rememberSaveable(value) { mutableStateOf(false) }
    Row(
        Modifier.then(if (collapsible) Modifier.clickable { expanded = !expanded } else Modifier),
        horizontalArrangement = Arrangement.spacedBy(Dim.gapSm),
        // 展开后内容有很多行，箭头贴顶才不会悬在整段文字的中间
        verticalAlignment = if (expanded) Alignment.Top else Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = Font.hint,
            lineHeight = 15.sp,
            color = tone.textMuted,
            modifier = Modifier.width(58.dp),
        )
        Text(
            value,
            fontSize = Font.hint,
            lineHeight = 15.sp,
            color = tone.text,
            maxLines = if (collapsible && !expanded) 2 else Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (collapsible) {
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "收起完整链接" else "展开完整链接",
                tint = tone.textMuted,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
