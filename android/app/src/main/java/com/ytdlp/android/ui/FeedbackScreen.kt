package com.ytdlp.android.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ytdlp.android.engine.FeedbackSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 意见反馈页。
 *
 * 三项输入对应原始设计：问题描述（必填）、图片（选填）、联系方式（选填）。
 * 提交后直接进开发者的企业微信群，不需要用户跳出去用邮件客户端。
 *
 * 状态全部留在本页，不进 AppViewModel —— 它是自成一体的表单，
 * 和解析/历史/设置没有任何交集，塞进共享 VM 只会让那边多一堆无关字段。
 */
@Composable
fun FeedbackScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var message by rememberSaveable { mutableStateOf("") }
    var contact by rememberSaveable { mutableStateOf("") }
    // 用 ArrayList 而不是 listOf()：rememberSaveable 要把值塞进 Bundle，
    // ArrayList 是明确可序列化的类型，而 listOf() 的实现类不一定能被保存。
    var images by rememberSaveable { mutableStateOf(ArrayList<Uri>()) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }

    // 系统相册选择器（Photo Picker）。它走的是系统进程，不需要任何存储权限，
    // 也不会让用户面对「允许访问所有照片」这种吓人的授权弹窗。
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(FeedbackSender.MAX_IMAGES)
    ) { picked ->
        if (picked.isEmpty()) return@rememberLauncherForActivityResult
        val merged = ArrayList(images)
        for (u in picked) {
            if (merged.size >= FeedbackSender.MAX_IMAGES) break
            if (!merged.contains(u)) merged.add(u)
        }
        images = merged
    }

    Column(modifier.fillMaxSize()) {
        // ---- 顶栏：返回 + 标题 ----
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = Dim.screenPadding, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = tone.text)
            }
            Text("意见反馈", style = MaterialTheme.typography.titleLarge, color = tone.text)
        }

        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dim.screenPadding)
                .padding(top = Dim.gap, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(Dim.gapLg),
        ) {
            if (done) {
                // 提交成功后不再留着表单，避免用户以为没发出去又点一次
                Card {
                    FieldLabel("已提交")
                    Spacer12()
                    Hint("反馈已经发出去了，感谢你花时间写这一条。\n如果留了联系方式，我看完会回复你。")
                    Spacer12()
                    PrimaryButton(
                        text = "完成",
                        onClick = onBack,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                return@Column
            }

            // ---- 问题或建议（必填）----
            Card {
                FieldLabel("问题或建议")
                Spacer12()
                OutlinedTextField(
                    value = message,
                    onValueChange = {
                        // 直接截断而不是弹错误提示：写到一半被告知「太长了」很打断人，
                        // 而且这里本来就是纯摘要，写不了那么长
                        message = it.take(FeedbackSender.MAX_MESSAGE_CHARS)
                    },
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    placeholder = {
                        Text(
                            "报错的话：哪个平台、哪个链接、报了什么错？\n" +
                                "有想法的话：想加什么功能、哪里不好用，也直接说。",
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
                Spacer12()
                Hint("必填。${message.length} / ${FeedbackSender.MAX_MESSAGE_CHARS}")
            }

            // ---- 图片（选填）----
            Card {
                FieldLabel("截图（选填）")
                Spacer12()
                Row(horizontalArrangement = Arrangement.spacedBy(Dim.gap)) {
                    images.forEach { uri ->
                        Box(Modifier.size(72.dp)) {
                            AsyncImage(
                                model = uri,
                                contentDescription = "已选图片",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(Dim.radiusSm))
                                    .background(tone.surfaceHover),
                            )
                            // 右上角的小叉，点掉这张
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .size(22.dp)
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(tone.surface)
                                    .clickable {
                                        images = ArrayList(images).apply { remove(uri) }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "移除这张图",
                                    tint = tone.text,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                    if (images.size < FeedbackSender.MAX_IMAGES) {
                        Box(
                            Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(Dim.radiusSm))
                                .background(tone.surfaceHover)
                                .clickable {
                                    pickImages.launch(
                                        PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly
                                        )
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("＋", fontSize = 24.sp, color = tone.textMuted)
                        }
                    }
                }
                Spacer12()
                Hint(
                    "最多 ${FeedbackSender.MAX_IMAGES} 张。报错信息、卡住的界面截个图，" +
                        "定位问题会快很多。图片会自动压缩后发送。"
                )
            }

            // ---- 联系方式（选填）----
            Card {
                FieldLabel("联系方式（选填）")
                Spacer12()
                OutlinedTextField(
                    value = contact,
                    onValueChange = { contact = it.take(100) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("邮箱 / QQ / 微信，随便哪个都行", fontSize = Font.body) },
                    singleLine = true,
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
                Spacer12()
                Hint("填了才能回复你。不想留也完全可以，反馈照样收得到。")
            }

            // ---- 提交 ----
            if (error != null) {
                Card {
                    Text(
                        error!!,
                        fontSize = Font.meta,
                        lineHeight = 20.sp,
                        color = tone.danger,
                    )
                }
            }

            PrimaryButton(
                text = if (sending) "发送中…" else "提交反馈",
                enabled = message.isNotBlank() && !sending,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    sending = true
                    error = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                FeedbackSender.send(
                                    context = context,
                                    message = message.trim(),
                                    contact = contact.trim(),
                                    images = images,
                                )
                            }.getOrElse {
                                FeedbackSender.Result.Failed(
                                    "发送出错：${it.message ?: it.javaClass.simpleName}"
                                )
                            }
                        }
                        sending = false
                        when (result) {
                            is FeedbackSender.Result.Sent -> done = true
                            is FeedbackSender.Result.Failed -> error = result.reason
                        }
                    }
                },
            )

            if (sending) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = tone.accent,
                        strokeWidth = 2.dp,
                    )
                    Text("  正在发送，请稍候…", fontSize = Font.meta, color = tone.textMuted)
                }
            }

            Hint("提交后直接发到开发者这里，不用跳出去用邮件客户端。")
        }
    }
}
