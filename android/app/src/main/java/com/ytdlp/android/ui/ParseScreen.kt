package com.ytdlp.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** 解析页：贴链接 → 出结果 → 下载。对应网页端的第一个视图。 */
@Composable
fun ParseScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val url by vm.url.collectAsStateWithLifecycle()
    val parse by vm.parse.collectAsStateWithLifecycle()
    val download by vm.download.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dim.screenPadding)
            .padding(top = Dim.gapLg, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(Dim.gapLg),
    ) {
        // ---- 输入区 ----
        Column(verticalArrangement = Arrangement.spacedBy(Dim.gap)) {
            OutlinedTextField(
                value = url,
                onValueChange = vm::onUrlChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("粘贴视频链接…", fontSize = Font.body) },
                singleLine = true,
                shape = RoundedCornerShape(Dim.radiusSm),
                trailingIcon = {
                    if (url.isNotEmpty()) {
                        IconButton(onClick = { vm.onUrlChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "清空", tint = tone.textMuted)
                        }
                    }
                },
                // 键盘上直接把「回车」变成「解析」，少一次点击
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { vm.parse() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = tone.accent,
                    unfocusedBorderColor = tone.border,
                    focusedContainerColor = tone.surface,
                    unfocusedContainerColor = tone.surface,
                    focusedTextColor = tone.text,
                    unfocusedTextColor = tone.text,
                    cursorColor = tone.accent,
                ),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(Dim.gap)) {
                PrimaryButton(
                    text = if (parse is ParseUi.Loading) "解析中…" else "解析",
                    onClick = vm::parse,
                    enabled = parse !is ParseUi.Loading,
                    modifier = Modifier.weight(1f),
                )
                if (parse !is ParseUi.Idle) {
                    GhostButton(
                        text = "清空结果",
                        onClick = vm::clearParse,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Hint("支持 YouTube / B站 / 抖音 / TikTok / X / Instagram。境外站点需要开着 VPN。")
        }

        // ---- 结果区 ----
        when (val state = parse) {
            is ParseUi.Idle -> Unit

            is ParseUi.Loading -> Card {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dim.gap),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = tone.accent,
                    )
                    Text("正在解析，请稍候…", fontSize = Font.body, color = tone.textMuted)
                }
                Spacer12()
                Hint("境外站点可能要几十秒。若一直卡住，多半是 VPN 没开。")
            }

            is ParseUi.Failed -> Card {
                Text(
                    "解析失败",
                    fontSize = Font.cardTitle,
                    fontWeight = FontWeight.SemiBold,
                    color = tone.danger,
                )
                Spacer12()
                // 报错正文保留换行：引擎返回的是「说明 + 该怎么办 + 原始报错」三段，
                // 压成一行会看不清结构
                Text(
                    state.message,
                    fontSize = Font.meta,
                    lineHeight = 22.sp,
                    color = tone.danger,
                )
            }

            is ParseUi.Done -> ResultCard(
                video = state.video,
                download = download,
                onDownload = vm::startDownload,
                onOpenSource = { uriHandler.openUriSafe(state.video.sourceUrl) },
            )
        }
    }
}

@Composable
private fun ResultCard(
    video: com.ytdlp.android.engine.Video,
    download: com.ytdlp.android.engine.Download,
    onDownload: () -> Unit,
    onOpenSource: () -> Unit,
) {
    Card {
        // 平台 + 作者一行
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dim.gapSm),
        ) {
            PlatformBadge(video.platform)
            Text(video.platform, fontSize = Font.meta, color = tone.textMuted)
            if (video.via.isNotBlank()) {
                // 走备用链路时说明一句，否则用户不理解为什么慢/偶尔失败
                Text(
                    "· " + if (video.via == "browser") "浏览器模式" else "第三方服务",
                    fontSize = Font.hint,
                    color = tone.accent,
                )
            }
        }

        Spacer(Modifier.height(Dim.gap))

        Text(
            video.title,
            fontSize = Font.cardTitle,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 23.sp,
            color = tone.text,
        )

        val meta = buildList {
            if (video.uploader.isNotBlank()) add(video.uploader)
            if (video.uploaderId.isNotBlank()) add(video.uploaderId)
            formatDuration(video.duration).takeIf { it.isNotBlank() }?.let { add(it) }
        }
        if (meta.isNotEmpty()) {
            Spacer(Modifier.height(Dim.gapSm))
            Text(meta.joinToString(" · "), fontSize = Font.meta, color = tone.textMuted)
        }

        val spec = buildList {
            video.quality.takeIf { it.isNotBlank() }?.let { add(it) }
            video.filesize.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        if (spec.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(spec.joinToString(" · "), fontSize = Font.meta, color = tone.textMuted)
        }

        Spacer(Modifier.height(Dim.gapLg))

        // 封面 + 播放器。下载完成后直接用产物文件播放 —— 对 B站/YouTube
        // 这类纯 DASH 站点，在线直链根本不存在，本地文件是唯一能看的方式。
        val justDownloaded = download.path.takeIf { download.isDone && it.isNotBlank() }
        PlayerBox(video, source = justDownloaded ?: video.playSource)

        Spacer(Modifier.height(Dim.gapLg))

        Row(horizontalArrangement = Arrangement.spacedBy(Dim.gap)) {
            PrimaryButton(
                text = if (download.active) "下载中…" else "下载",
                onClick = onDownload,
                enabled = !download.active,
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
