package com.ytdlp.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 「实况」页：手动把一张图片 + 一段视频合成实况图。
 *
 * 为什么单独开一页而不是塞进设置：这是一件「拿着两个文件动手做点什么」的事，
 * 和开关类设置不是一个性质；而且它要占底部导航里的固定位置，用户才找得到。
 *
 * 两个选项对应两类系统实现（详见 [LivePhotoSynth]）：
 *  - 默认（开关关）：同名配对 + 写 `live_photo`，vivo / 红米实测可用
 *  - 开关开：单文件（JPEG 内嵌 MP4），小米 / OPPO / 华为 / 原生走这条
 */
@Composable
fun LiveScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val state by vm.live.collectAsStateWithLifecycle()

    // 一次性提示显示完自己消失（与 MD5 页同一套做法）
    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            delay(4000)
            vm.consumeLiveNotice()
        }
    }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let(vm::pickLiveImage) }
    val pickVideo = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let(vm::pickLiveVideo) }

    // 产物要写进公共下载目录并让相册收录，没「文件管理权限」时做不成，
    // 所以三个动作前都加一道门（与 MD5 页一致）。
    val needPerm by vm.needStoragePermission.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showGate by rememberSaveable { mutableStateOf(false) }
    val gated: (() -> Unit) -> Unit = { go -> if (needPerm) showGate = true else go() }

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dim.screenPadding)
            .padding(top = Dim.gapLg, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(Dim.gapLg),
    ) {
        Text(
            "实况图合成",
            fontSize = Font.viewTitle,
            fontWeight = FontWeight.SemiBold,
            color = tone.text,
        )
        Hint("选一段视频，再可选配一张同画幅的图片，合成实况图。原件全程不动，成品另存到下载目录。")

        Card {
            FieldLabel("图片（可选）")
            PickRow(
                path = state.imagePath,
                picked = state.imageLabel,
                rev = state.imageRev,
                enabled = !state.working,
                isImage = true,
                buttonText = if (state.imageLabel.isBlank()) "选择图片" else "重选图片",
                onClear = if (state.imagePath.isBlank()) null else ({ vm.clearLiveImage() }),
            ) {
                gated { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            }
            Hint("不选也可以：会自动从视频中间抽一帧当封面（和手机实况一样取在中段，播放时才有「从静图动起来」的过渡）。")
        }

        Card {
            FieldLabel("视频（必选）")
            PickRow(
                path = state.videoPath,
                picked = state.videoLabel,
                rev = state.videoRev,
                enabled = !state.working,
                isImage = false,
                buttonText = if (state.videoLabel.isBlank()) "选择视频" else "重选视频",
                onClear = null,
            ) {
                gated { pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
            }
            Hint("实况图的「动」就是这段视频。选了图片时，画幅比例要和视频一致（分辨率不同没关系）。")
        }

        Card {
            FieldLabel("文件名")
            OutlinedTextField(
                value = state.namePrefix,
                onValueChange = vm::setLiveNamePrefix,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.working,
                singleLine = true,
                placeholder = {
                    val src = if (state.imageLabel.isBlank()) state.videoLabel else state.imageLabel
                    Text(
                        if (src.isBlank()) "留空则用「素材名_实况」"
                        else "${src.substringBeforeLast('.')}_实况",
                        fontSize = Font.body,
                    )
                },
                shape = RoundedCornerShape(Dim.radiusSm),
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
            Hint("最终文件名 = 上面的名字 + 保存时间，例如 xxx_20261024_101530.jpg")
        }

        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "生成单文件动态照片",
                        fontSize = Font.body,
                        fontWeight = FontWeight.Medium,
                        color = tone.text,
                    )
                    Hint(
                        if (state.singleFile) {
                            "会生成一个 .jpg 文件（视频嵌在图片末尾 + XMP 声明）。" +
                                "小米 HyperOS 官方支持这种；OPPO（含一加、realme）、" +
                                "华为 / 荣耀、原生 Android 按规范实现，尚未实测。" +
                                "vivo 相册不读 XMP，建议关掉这个开关。"
                        } else {
                            "默认生成同名的一图一视频（x.jpg + x.mp4）并登记到相册。" +
                                "vivo 实测可用；红米与小米同系统，按同一套实现。"
                        }
                    )
                }
                Switch(
                    checked = state.singleFile,
                    onCheckedChange = { vm.setLiveSingleFile(it) },
                    enabled = !state.working,
                )
            }
        }

        PrimaryButton(
            text = if (state.working) "处理中…" else "合成并保存",
            onClick = { gated { vm.saveLive() } },
            modifier = Modifier.fillMaxWidth(),
            // 只要求视频：图片是可选的，不选就自动抽首帧
            enabled = !state.working && state.videoPath.isNotBlank(),
        )

        // 成功之后给一个「查看」入口：交给系统相册/播放器，
        // 自己内嵌播放器等于重写相册的「静图封面 + 长按播放」交互，不划算。
        if (state.savedPath.isNotBlank()) {
            GhostButton(
                text = "查看合成结果",
                onClick = { vm.openLiveResult() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.working,
            )
        }

        if (state.error.isNotBlank()) {
            Text(state.error, fontSize = Font.meta, color = tone.danger)
        }
        state.notice?.let {
            Text(it, fontSize = Font.meta, color = tone.textMuted)
        }
    }

    if (showGate) {
        AlertDialog(
            onDismissRequest = { showGate = false },
            title = { Text("需要文件管理权限", fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "成品要写进公共下载目录、还要让相册收录它 —— 这两件事都需要系统的" +
                        "「文件管理权限」（Android 11 起叫「所有文件访问权限」）。\n\n" +
                        "没开这个权限时，产物只能落到应用私有目录，相册里看不到。" +
                        "到设置里打开它，再回来点一次就能继续。",
                    fontSize = Font.body,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showGate = false
                    openStorageSettings(context)
                }) { Text("去授权", color = tone.accent) }
            },
            dismissButton = {
                TextButton(onClick = { showGate = false }) {
                    Text("取消", color = tone.textMuted)
                }
            },
        )
    }
}

/**
 * 一行「缩略图 + 文件名 + 选择按钮」。
 *
 * 缩略图是必需的：两个选择槽的显示名常常是同一批文件（比如 1000669319.jpg 和
 * 1000669318.mp4），光看名字分不清哪张图配哪段视频。缩略图固定 56dp，不随
 * 文件名换行而变高，所以整体高度可控、不会把页面撑长。
 */
@Composable
private fun PickRow(
    path: String,
    picked: String,
    rev: Int,
    enabled: Boolean,
    isImage: Boolean,
    buttonText: String,
    onClear: (() -> Unit)?,
    onPick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = Dim.gapSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dim.gap),
    ) {
        Thumb(path = path, isImage = isImage, rev = rev)
        Column(Modifier.weight(1f)) {
            Text(
                picked.ifBlank { "未选择" },
                fontSize = Font.meta,
                color = if (picked.isBlank()) tone.textMuted else tone.text,
                maxLines = 2,
            )
            // 清空入口只在该槽确实有内容时出现
            onClear?.let {
                Text(
                    "清除",
                    fontSize = Font.hint,
                    color = tone.accent,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .clickable(enabled = enabled, onClick = it),
                )
            }
        }
        GhostButton(text = buttonText, onClick = onPick, enabled = enabled)
    }
}

/**
 * 56dp 缩略图。图片交给 Coil；视频用 MediaMetadataRetriever 取首帧。
 *
 * 为什么视频不能直接用 Coil：Coil2 默认的 ImageDecoder 不认视频文件，要额外引
 * `coil-video` 解码器才行。为一个缩略图加一个依赖不划算，而系统自带的
 * MediaMetadataRetriever 本来就能取帧 —— 抽一次缓存在 remember 里，不会反复解。
 */
@Composable
private fun Thumb(
    path: String,
    isImage: Boolean,
    rev: Int,
) {
    val boxMod = Modifier
        .size(56.dp)
        .clip(RoundedCornerShape(Dim.radiusSm))
        .background(tone.border.copy(alpha = 0.4f))

    if (path.isBlank()) {
        Box(boxMod, contentAlignment = Alignment.Center) {
            Text(
                if (isImage) "图" else "视",
                fontSize = Font.hint,
                color = tone.textMuted,
            )
        }
        return
    }

    if (isImage) {
        AsyncImage(
            model = File(path),
            contentDescription = "已选图片",
            modifier = boxMod,
            contentScale = ContentScale.Crop,
        )
        return
    }

    // 视频：在 IO 线程抽首帧，抽到之前显示占位。
    // key 不能只看 path：缓存副本固定叫 video.mp4，重选后路径不变；而 Compose 在
    // 参数完全相同时会直接跳过重组，连这里的 key 都不会重算。所以由外部传入
    // 每次重选自增的 rev，参数一变必然重组、必然重抽。
    val frame by produceState<android.graphics.Bitmap?>(initialValue = null, path, rev) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                android.media.MediaMetadataRetriever().use { mmr ->
                    mmr.setDataSource(path)
                    // 取 0.1s 而不是 0：部分视频第一帧是全黑的（封面帧还没出来）
                    mmr.getFrameAtTime(100_000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
            }.getOrNull()
        }
    }
    Box(boxMod, contentAlignment = Alignment.Center) {
        if (frame != null) {
            Image(
                bitmap = frame!!.asImageBitmap(),
                contentDescription = "已选视频",
                modifier = Modifier.size(56.dp),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text("视", fontSize = Font.hint, color = tone.textMuted)
        }
    }
}
