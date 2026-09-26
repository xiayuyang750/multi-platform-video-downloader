package com.ytdlp.android.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ytdlp.android.DouyinActivity

/** 解析页：贴链接 → 出结果 → 下载。对应网页端的第一个视图。 */
@Composable
fun ParseScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val url by vm.url.collectAsStateWithLifecycle()
    val parse by vm.parse.collectAsStateWithLifecycle()
    val download by vm.download.collectAsStateWithLifecycle()
    val needStoragePerm by vm.needStoragePermission.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current

    // 抖音的浏览器模式解析页。它是个独立 Activity（WebView 需要窗口），
    // 解析完通过 setResult 把数据交回来。
    val douyinLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        vm.onDouyinResult(
            result.data?.getStringExtra(DouyinActivity.EXTRA_PAYLOAD),
            result.data?.getStringExtra(DouyinActivity.EXTRA_REASON).orEmpty(),
        )
    }

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dim.screenPadding)
            .padding(top = Dim.gapLg, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(Dim.gapLg),
    ) {
        // ---- 存储权限引导（只在未授权时出现）----
        if (needStoragePerm) {
            Card {
                Text(
                    "建议授予存储权限",
                    fontSize = Font.cardTitle,
                    fontWeight = FontWeight.SemiBold,
                    color = tone.text,
                )
                Spacer12()
                Hint(
                    "授予后，下载的视频会保存到系统的「下载」目录，用文件管理器就能找到。\n" +
                        "不授予也能正常使用 —— 但文件会存进应用私有目录，文件管理器看不到，" +
                        "只能通过应用内分享导出。"
                )
                Spacer12()
                GhostButton(
                    text = "去授权",
                    onClick = { openStorageSettings(context) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ---- 输入区 ----
        Column(verticalArrangement = Arrangement.spacedBy(Dim.gap)) {
            // 输入框 + 一键粘贴，对应 Windows 端的 .input-row。
            // 手机上长按输入框才能粘贴，多一步操作；给个显式按钮更顺手。
            Row(
                horizontalArrangement = Arrangement.spacedBy(Dim.gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = vm::onUrlChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("粘贴视频链接…", fontSize = Font.body) },
                    singleLine = true,
                    shape = RoundedCornerShape(Dim.radiusSm),
                    trailingIcon = {
                        if (url.isNotEmpty()) {
                            IconButton(onClick = { vm.onUrlChange("") }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "清空",
                                    tint = tone.textMuted,
                                )
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
                GhostButton(text = "粘贴", onClick = vm::pasteFromClipboard)
            }

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

            Hint(
                "支持直接粘贴整段分享文案，会自动提取其中的链接。\n" +
                    "支持 YouTube / B站 / 抖音 / TikTok / X / Instagram，境外站点需要开着 VPN。"
            )
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

            is ParseUi.NeedBrowser -> {
                // 抖音要走浏览器模式：立刻拉起那个 WebView 页面。
                // 用 url 做 key，避免重组时反复拉起。
                LaunchedEffect(state.url) {
                    douyinLauncher.launch(DouyinActivity.intent(context, state.url))
                }
                Card {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dim.gap),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = tone.accent,
                        )
                        Text("正在打开浏览器模式…", fontSize = Font.body, color = tone.textMuted)
                    }
                    Spacer12()
                    Hint(
                        "抖音的接口需要签名和登录态，纯 HTTP 拿不到，所以改用内置浏览器解析。\n" +
                            "如果页面提示登录，登录后稍等几秒即可。登录状态会保留，下次不用再登。"
                    )
                }
            }

            is ParseUi.Done -> ResultCard(
                video = state.video,
                download = download,
                onDownload = vm::startDownload,
                onCopy = { vm.copyText(state.video.sourceUrl) },
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
    onCopy: () -> Unit,
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
                text = "复制链接",
                onClick = onCopy,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(Dim.gap))

        // 打开原视频单独占一行：三个按钮并排时中文会被挤到换行
        GhostButton(
            text = "打开原视频",
            onClick = onOpenSource,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 跳到系统的「所有文件访问权限」设置页。
 *
 * 这是特殊权限，不能用 requestPermissions 申请，只能让用户手动开。
 * 部分国产 ROM 没有这个页面会抛 ActivityNotFoundException —— 那时退回
 * 应用详情页，用户也能在那里找到权限入口。
 */
private fun openStorageSettings(context: Context) {
    val pkg = Uri.parse("package:${context.packageName}")
    val direct = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    runCatching { context.startActivity(direct) }
        .onFailure { runCatching { context.startActivity(fallback) } }
}
