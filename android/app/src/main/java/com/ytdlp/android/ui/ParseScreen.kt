package com.ytdlp.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ytdlp.android.DouyinActivity
import com.ytdlp.android.WeiboActivity
import com.ytdlp.android.XhsActivity

/** 解析页：贴链接 → 出结果 → 下载。对应网页端的第一个视图。 */
@Composable
fun ParseScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val url by vm.url.collectAsStateWithLifecycle()
    val parse by vm.parse.collectAsStateWithLifecycle()
    val download by vm.download.collectAsStateWithLifecycle()
    val needStoragePerm by vm.needStoragePermission.collectAsStateWithLifecycle()
    // 下载页那个「下载时自动改 MD5」开关读的就是这份设置（默认关）
    val settings by vm.settings.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current

    // 输入框聚焦柔光的强度，0→1。不做成瞬时切换，150ms 过渡看着才不像闪一下。
    var focused by remember { mutableStateOf(false) }
    val glow by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(150),
        label = "input-glow",
    )
    // 颜色也要先在 composable 作用域里取出来：drawBehind 的 lambda 是绘制阶段的
    // 普通 lambda，在里面读 tone（@Composable 属性）编译不过。
    //
    // 为什么不用网页端那个 accent-soft（#EEF2FE）：实测过，它铺在 #F5F6F8 的页面底上
    // 只让颜色从 F5F6F8 变到约 F8F9FA —— 肉眼看不出有任何变化，这一圈等于白画。
    // 改用 accent 蓝的 15% 透明度：浅色下约 #D8E0F6、深色下约 #1E263C，都能看到
    // 一圈柔和的蓝色轮廓，但仍然不是硬边。
    val glowColor = tone.accent.copy(alpha = 0.15f)

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

    // 小红书的浏览器模式解析页。与抖音那条的区别：需要登录时这个窗口会**露出来**
    // 让用户就地登录一次（之后长期有效），所以它不只是个后台窗口。
    val xhsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        vm.onXhsResult(
            result.data?.getStringExtra(XhsActivity.EXTRA_PAYLOAD),
            result.data?.getStringExtra(XhsActivity.EXTRA_REASON).orEmpty(),
        )
    }

    // 微博的浏览器模式解析页：图文 / 图集 / 实况图 / 动图走这里。
    // 它和抖音一样是**完全隐形**的（alpha=0，不露给用户），因为微博不需要登录；
    // 真实浏览器会自动过掉微博的「访客系统」。
    val weiboLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        vm.onWeiboResult(
            result.data?.getStringExtra(WeiboActivity.EXTRA_PAYLOAD),
            result.data?.getStringExtra(WeiboActivity.EXTRA_REASON).orEmpty(),
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
                Box(
                    Modifier
                        .weight(1f)
                        // 聚焦柔光：网页端 .input:focus 有 `box-shadow: 0 0 0 3px accent-soft`，
                        // 安卓侧之前漏了，输入框获得焦点只是边框变个色，不够明确。
                        //
                        // 用 drawBehind 往外画一圈、而不是「加 padding 再铺底色」：
                        // 后者会把这个 Row 撑高 6dp，输入框和下面的按钮间距跟着变；
                        // drawBehind 画在布局边界之外，不占任何空间，零布局影响。
                        .drawBehind {
                            if (glow > 0f) {
                                val r = 3.dp.toPx()
                                drawRoundRect(
                                    color = glowColor.copy(alpha = glowColor.alpha * glow),
                                    topLeft = Offset(-r, -r),
                                    size = Size(size.width + r * 2, size.height + r * 2),
                                    cornerRadius = CornerRadius(Dim.radiusSm.toPx() + r),
                                )
                            }
                        }
                ) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = vm::onUrlChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focused = it.isFocused },
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
                }
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
                    // 加「等」是必要的：除这几家之外还有不少站点能认，
                    // 写死成「只支持这几个」不属实。
                    "支持 YouTube / B站 / 抖音 / TikTok / X / Instagram / 小红书 / 微博 等平台。" +
                    "境外站点需要开着 VPN。"
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
                // 抖音 / 小红书这类需要走内置浏览器的内容：拉起对应的解析页去取数据，
                // 界面上就停在这里转圈，取到后直接换成结果卡片。
                // 用 url 做 key，避免重组时反复拉起。
                LaunchedEffect(state.url, state.kind) {
                    when (state.kind) {
                        "xhs" -> xhsLauncher.launch(XhsActivity.intent(context, state.url))
                        "weibo" -> weiboLauncher.launch(WeiboActivity.intent(context, state.url))
                        else -> douyinLauncher.launch(DouyinActivity.intent(context, state.url))
                    }
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
                        Text(
                            when (state.kind) {
                                "xhs" -> "正在用内置浏览器解析这条笔记…"
                                "weibo" -> "正在用内置浏览器读取这条微博…"
                                else -> "正在解析该视频…"
                            },
                            fontSize = Font.body,
                            color = tone.textMuted,
                        )
                    }
                    Spacer12()
                    Hint(
                        when (state.kind) {
                            "xhs" ->
                                "小红书需要登录态 —— 首次会弹出登录页，登录一次即可（之后不必再登录）。"
                            "weibo" ->
                                "微博的图文 / 图集 / 实况图需要多等几秒，请稍候。"
                            else -> "这条内容需要多等几秒，请稍候。"
                        }
                    )
                }
            }

            // 结果卡片淡入 + 轻微上移。key 用原始链接：换了个视频才重播，
            // 同一个结果因重组重新绘制时不闪。
            is ParseUi.Done -> key(state.video.sourceUrl) {
                val appear = remember {
                    MutableTransitionState(false).apply { targetState = true }
                }
                AnimatedVisibility(
                    visibleState = appear,
                    enter = fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 12 },
                ) {
                    ResultCard(
                        video = state.video,
                        download = download,
                        rewriteMd5 = settings.settings?.rewriteMd5OnDownload == true,
                        onToggleRewriteMd5 = { vm.setRewriteMd5(it) },
                        onDownload = { spec -> vm.startDownload(state.video, spec = spec) },
                        onDownloadPage = { index, spec ->
                            vm.startDownload(state.video, index, spec)
                        },
                        onCopy = { vm.copyText(state.video.sourceUrl) },
                        onOpenSource = { uriHandler.openUriSafe(state.video.sourceUrl) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultCard(
    video: com.ytdlp.android.engine.Video,
    download: com.ytdlp.android.engine.Download,
    rewriteMd5: Boolean,
    onToggleRewriteMd5: (Boolean) -> Unit,
    onDownload: (String) -> Unit,
    onDownloadPage: (Int, String) -> Unit,
    onCopy: () -> Unit,
    onOpenSource: () -> Unit,
) {
    // 图集当前看到第几张（0 起）。「下载这张」要下的是它。
    var page by remember(video.id) { mutableStateOf(0) }
    // 选中的下载档位（0 起）。引擎已把档位按「好 → 差」排好，所以第一项就是默认
    // 的最高档 —— 界面不必再算一遍「哪个最高」。
    // key 用 video.id：换一条作品要回到默认档，不能把上一条的选择带过来。
    var tierIndex by remember(video.id) { mutableStateOf(0) }
    // 交给引擎的下载规格。没有档位（图文 / 图集 / 实况图）时是空串，
    // 由引擎自己决定下什么；有档位时就是用户选的那一条。
    // 名字不能叫 spec：下面 Card 块里已有一个同名局部变量（画质/体积那行），
    // 会把它遮蔽成 List<String>，编译器只报「类型不匹配」而不告诉你原因。
    val tierSpec = video.tierList.getOrNull(tierIndex)?.specJson.orEmpty()
    // 视频号只有预览卡片、纯文字文章没有媒体：这两种都不给下载按钮
    val canDownload = video.contentType != "preview" && video.contentType != "text"
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

        // 正文（目前只有微信公众号会带）。默认折 4 行，长文不把下载按钮顶出屏幕。
        if (video.description.isNotBlank()) {
            Spacer(Modifier.height(Dim.gap))
            BodyBlock(video.description)
        }

        Spacer(Modifier.height(Dim.gapLg))

        if (video.isGallery) {
            // 图文 / 图集 / 实况图：逐张浏览，播放器在这里没有意义
            GalleryBox(video, onPageChange = { page = it })
        } else {
            // 封面 + 播放器。刚下载完这一条时直接播产物文件 —— 对 B站/YouTube
            // 这类纯 DASH 站点，在线直链根本不存在，本地文件是唯一能看的方式。
            //
            // 必须比对 download.url：download 是全局状态（同一时刻只有一个任务，
            // 完成后的状态会一直留着）。不比对就会出现这个实测到的 bug ——
            // 下完 A 再去解析 B，B 的播放区会拿 A 的产物文件来播。
            val justDownloaded = download.path.takeIf {
                download.isDone && it.isNotBlank() && download.url == video.sourceUrl
            }
            PlayerBox(video, source = justDownloaded ?: video.playSource)
        }

        // MD5 一行 + 「下载时自动改 MD5」开关，都放在下载按钮**上方** ——
        // 它们描述的是「这次下载会怎么处理文件」，属于下载前的设定。
        // 图文 / 图集不显示：那类产物是一个目录，没有「单个文件的 MD5」这回事。
        if (canDownload && !video.isGallery) {
            Spacer(Modifier.height(Dim.gapLg))
            Text(
                md5Line(video, tierIndex),
                fontSize = Font.meta,
                color = tone.textMuted,
            )
            Spacer(Modifier.height(Dim.gapSm))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dim.gapSm),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("下载时自动改 MD5", fontSize = Font.meta, color = tone.text)
                    Text(
                        if (rewriteMd5) {
                            "下载完用 ffmpeg 无损重封装，换一个 MD5"
                        } else {
                            "关闭：文件按原样保存"
                        },
                        fontSize = Font.hint,
                        color = tone.textMuted,
                    )
                }
                Switch(
                    checked = rewriteMd5,
                    onCheckedChange = onToggleRewriteMd5,
                    enabled = !download.active,
                )
            }
        }

        Spacer(Modifier.height(Dim.gapLg))

        if (!canDownload) {
            Text(
                if (video.contentType == "preview") {
                    // 视频号：说清楚为什么没有下载按钮。微信内通常也不提供「保存视频」，
                    // 所以不给用户一个做不到的指引。
                    "视频号内容由微信客户端加密分发，本应用无法直接下载" +
                        "（微信内一般也没有「保存视频」入口）。"
                } else {
                    "这篇公众号是纯文字，没有可下载的图片或视频；正文可以直接复制。"
                },
                fontSize = Font.meta,
                lineHeight = 20.sp,
                color = tone.textMuted,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(Dim.gap)) {
                PrimaryButton(
                    text = if (download.active) "下载中…" else if (video.isGallery) "下载全部" else "下载",
                    onClick = { onDownload(tierSpec) },
                    enabled = !download.active,
                    modifier = Modifier.weight(1f),
                )
                // 图集才给「下载这张」：视频没有"第几张"的概念
                if (video.isGallery) {
                    GhostButton(
                        text = "下载第 ${page + 1} 张",
                        onClick = { onDownloadPage(page + 1, tierSpec) },
                        enabled = !download.active,
                        modifier = Modifier.weight(1f),
                    )
                }
                // 档位选择器放最右：它是次要操作，靠边不抢下载按钮的位置。
                // 只有一个档位等于没得选，不显示；图文 / 图集 / 实况图本身没有档位。
                if (video.tierList.size > 1) {
                    TierSelector(
                        tiers = video.tierList,
                        index = tierIndex,
                        enabled = !download.active,
                        onSelect = { tierIndex = it },
                    )
                }
            }
        }

        Spacer(Modifier.height(Dim.gap))

        // 「复制链接」单独占一整行（与「打开原视频」同款）。
        // 原先它挤在上面那一行里，档位选择器一出现（抖音这类多档位平台）就被压得
        // 只剩很窄一条，中文被折成两行 —— 用户反复反馈过。放成整行就不存在被挤的问题。
        GhostButton(
            text = "复制链接",
            onClick = onCopy,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(Dim.gap))

        // 打开原视频也单独占一行，理由同上
        GhostButton(
            text = "打开原视频",
            onClick = onOpenSource,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 档位选择器：紧挨下载按钮的一个紧凑触发器，点开才是下拉列表。
 *
 * 为什么不做成一排常驻胶囊：档位文案是「720p · H.265 · 11.3 MB」这么长，
 * 两三枚就把一行占满，下载按钮会被挤得没地方。触发器上只放短的那一段
 * （如「720p ▾」），完整文案留在菜单项里 —— 细节只在真要选的时候才需要看。
 *
 * 半透明 + 主色文字：它是一个**次要**操作，视觉上要比下载主按钮安静，
 * 不该跟主按钮争注意力。
 */
@Composable
private fun TierSelector(
    tiers: List<com.ytdlp.android.engine.Tier>,
    index: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val fg = if (enabled) tone.accent else tone.textMuted
    Box {
        Row(
            Modifier
                // 宽度必须封顶：档位文案（如「1080p · H.265 · 88.0 MB」）偶尔会很长，
                // 不封顶它会把同一行的「复制链接」挤到换行、变成两行显示。
                // 这里只给触发器一个上限，完整文案照旧留给下拉菜单。
                .widthIn(max = 110.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(tone.accentSoft.copy(alpha = 0.5f))
                .clickable(enabled = enabled) { open = true }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                shortTierLabel(tiers.getOrNull(index)?.label),
                fontSize = Font.meta,
                fontWeight = FontWeight.SemiBold,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("▾", fontSize = Font.meta, color = fg)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            tiers.forEachIndexed { i, tier ->
                DropdownMenuItem(
                    text = {
                        Text(
                            tier.label,
                            fontSize = Font.meta,
                            color = if (i == index) tone.accent else tone.text,
                        )
                    },
                    onClick = {
                        onSelect(i)
                        open = false
                    },
                )
            }
        }
    }
}

/**
 * 「MD5」那一行显示什么。
 *
 * 抖音的解析结果里带平台自报的 file_hash，所以**下载前**就能显示；其他平台
 * （YouTube / B站 这类走 yt-dlp 的）不提供文件级摘要，只能等下载时算。
 * 优先显示**当前选中档位**那条 hash —— 用户选了哪一档，就该看哪一档的摘要。
 */
private fun md5Line(video: com.ytdlp.android.engine.Video, tierIndex: Int): String {
    val hash = video.tierList.getOrNull(tierIndex)?.hash.orEmpty().ifBlank { video.sourceMd5 }
    return if (hash.isNotBlank()) "MD5（平台自报）：$hash" else "MD5：下载完成后计算"
}

/**
 * 触发器上只放档位的短标签：完整文案「720p · H.265 · 11.3 MB」在按钮旁放不下。
 * 文案由引擎侧按「 · 」分段拼好，这里取第一段（也就是分辨率）。
 */
private fun shortTierLabel(label: String?): String =
    (label ?: "").substringBefore(" · ").ifBlank { "规格" }

/**
 * 正文块：默认折 4 行，长文可展开/收起，并支持一键复制。
 *
 * 微信文章的正文动辄上千字，全量铺开会把播放区和下载按钮顶出屏幕，所以默认折叠。
 * 复制按钮只复制正文本身（不含标题），和用户对「复制正文」的直觉一致。
 */
@Composable
private fun BodyBlock(text: String) {
    var expanded by remember(text) { mutableStateOf(false) }
    val context = LocalContext.current
    Column {
        Text(
            text,
            fontSize = Font.meta,
            lineHeight = 20.sp,
            color = tone.textMuted,
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Dim.gap)) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起" else "展开", fontSize = Font.meta, color = tone.accent)
            }
            TextButton(onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("正文", text))
            }) {
                Text("复制正文", fontSize = Font.meta, color = tone.accent)
            }
        }
    }
}

/**
 * 跳到系统的「所有文件访问权限」设置页。
 *
 * 这是特殊权限，不能用 requestPermissions 申请，只能让用户手动开。
 * 部分国产 ROM 没有这个页面会抛 ActivityNotFoundException —— 那时退回
 * 应用详情页，用户也能在那里找到权限入口。
 */
internal fun openStorageSettings(context: Context) {
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
