package com.ytdlp.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.ytdlp.android.engine.DownloadItem
import java.io.File

/**
 * 「大小」那一栏用的橙红。
 *
 * 刻意不并进 Tone：那套 token 是按「背景 / 文字 / 交互色」的角色分的，而这个橙红只
 * 是 MD5 页信息表里「大小」这一栏的强调色。同一个值在浅底和深底上都看得出，够用了。
 */
private val SizeAccent = Color(0xFFE8590C)

/**
 * MD5 页：选一份本机媒体 → 看信息 → 改 MD5 → 预览并保存。
 *
 * 与 v1.0.4 那版的根本区别：那时只有一份「本应用下载过的视频」列表，改也是**原地改**；
 * 现在主流程是「从相册里挑一份」，已下载列表退成次要分区（点一条也能进同一条流程）。
 *
 * 三条贯穿整页的规矩：
 *  1. **原件全程不动** —— 挑中的东西先整份复制进缓存，改的是这份副本，保存时另存新文件。
 *  2. **选媒体走系统相册/视频选择器**（PickVisualMedia），不是文件管理器那种目录浏览器 ——
 *     用户要的就是「进相册 + 顶部分组下拉」的那套界面，系统自带；用 ACTION_OPEN_DOCUMENT
 *     会落到另一个长得完全不同的界面去。
 *  3. **视频和图片都能选**：一个 launcher 只能限定一种媒体类型，所以各挂一个。
 */
@Composable
fun Md5Screen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val state by vm.md5.collectAsStateWithLifecycle()

    // 切进来刷一次列表。MD5 页是「我已经下过什么」的视图，没必要在启动时就白读一遍。
    LaunchedEffect(Unit) { vm.refreshDownloads() }

    // 一次性提示显示完就清掉（与设置页同一套做法），避免转屏后又弹一次
    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            kotlinx.coroutines.delay(4000)
            vm.consumeMd5Notice()
        }
    }

    val onPicked: (android.net.Uri?) -> Unit = { uri -> uri?.let(vm::pickMd5) }
    val pickVideo = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { onPicked(it) }
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { onPicked(it) }

    // ---- 权限门 ----
    // 没有「文件管理权限」（Android 11 起叫「所有文件访问权限」）时，这个功能是做不成的：
    // 读不到你选中的媒体、也写不进公共下载目录（没授权时引擎会把产物退到应用私有目录，
    // 文件管理器里根本找不到）。所以「选视频 / 选图片 / 保存文件」三个动作前面都加一道门：
    // 缺权限就先弹窗把用户引到系统设置页，授权回来再点一次即可。
    val needPerm by vm.needStoragePermission.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showGate by rememberSaveable { mutableStateOf(false) }
    fun gated(go: () -> Unit) {
        if (needPerm) showGate = true else go()
    }

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dim.screenPadding)
            .padding(top = Dim.gapLg, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(Dim.gapLg),
    ) {
        when (state.stage) {
            Md5Stage.Entry -> EntryStage(
                state, vm,
                onPickVideo = {
                    gated {
                        pickVideo.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                        )
                    }
                },
                onPickImage = {
                    gated {
                        pickImage.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                },
            )
            Md5Stage.Detail -> DetailStage(state, vm)
            Md5Stage.Result -> ResultStage(state, vm, onSave = { gated { vm.saveMd5Result() } })
        }
    }

    if (showGate) {
        AlertDialog(
            onDismissRequest = { showGate = false },
            title = { Text("需要文件管理权限", fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "选好的媒体要读写和处理，改完的文件还要存进公共下载目录 —— 这两件事都需要" +
                        "系统的「文件管理权限」（Android 11 起叫「所有文件访问权限」）。\n\n" +
                        "没开这个权限时，产物只能落到应用私有目录，文件管理器里看不到。" +
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
                TextButton(onClick = { showGate = false }) { Text("取消", color = tone.textMuted) }
            },
        )
    }
}

/** 入口页：虚线选择框 + 一句说明 + 已下载视频（次要分区）。 */
@Composable
private fun EntryStage(
    state: Md5Ui,
    vm: AppViewModel,
    onPickVideo: () -> Unit,
    onPickImage: () -> Unit,
) {
    Text("MD5 修改", fontSize = Font.viewTitle, fontWeight = FontWeight.SemiBold, color = tone.text)

    Hint(
        "MD5 是整份文件的指纹。平台常拿它判断「这个文件是不是已经有人传过」，" +
            "所以改掉它可以让同一个视频在平台上被当成新文件。\n" +
            "改写用的是 ffmpeg 无损重封装（不重编码，画质不变），只会在元数据里" +
            "写一个随机串。代价是文件要被完整重写一遍，大文件会等几秒到十几秒。"
    )

    PickBox(enabled = !state.working, onPickVideo = onPickVideo, onPickImage = onPickImage)

    // 复制一份大视频要几秒，期间选择框已禁用 —— 这里必须让用户看到「在读」，
    // 否则点完按钮没反应，看着就像卡住了
    if (state.working) {
        Card {
            Text("正在读取这份文件…", fontSize = Font.meta, color = tone.text)
        }
    }

    NoticeArea(state)

    if (state.items.isNotEmpty()) {
        Text(
            "已下载的视频",
            fontSize = Font.cardTitle,
            fontWeight = FontWeight.SemiBold,
            color = tone.text,
        )
        Hint("点一条也能改它的 MD5。改的是副本，原文件不会被改动。")
        state.items.forEach { item ->
            DownloadCard(
                item = item,
                enabled = !state.working,
                onClick = { vm.pickMd5FromPath(item.path) },
            )
        }
    }
}

/** 虚线选择框。 */
@Composable
private fun PickBox(enabled: Boolean, onPickVideo: () -> Unit, onPickImage: () -> Unit) {
    val line = tone.border
    val radius = Dim.radiusLg
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(radius))
            .background(tone.surface)
            // 虚线描边：drawRoundRect 自带的是实线，得自己给个 PathEffect
            .drawBehind {
                drawRoundRect(
                    color = line,
                    cornerRadius = CornerRadius(radius.toPx()),
                    style = Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)),
                    ),
                )
            }
            .padding(horizontal = Dim.cardPadding, vertical = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dim.gapLg),
        ) {
            Text(
                "从手机里选择要修改 MD5 的视频或图片",
                fontSize = Font.body,
                color = tone.text,
                textAlign = TextAlign.Center,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dim.gap),
            ) {
                PrimaryButton("选择视频", onPickVideo, Modifier.weight(1f), enabled = enabled)
                GhostButton("选择图片", onPickImage, Modifier.weight(1f), enabled = enabled)
            }
        }
    }
}

/** 已下载产物的一条：点它进详情页（走的还是「复制副本再改」这条路）。 */
@Composable
private fun DownloadCard(item: DownloadItem, enabled: Boolean, onClick: () -> Unit) {
    Card(Modifier.clickable(enabled = enabled, onClick = onClick)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dim.gapSm),
        ) {
            PlatformBadge(item.platform)
            Text(
                item.title.ifBlank { item.filename },
                fontSize = Font.body,
                fontWeight = FontWeight.Medium,
                color = tone.text,
                maxLines = 1,
            )
        }
        Spacer12()
        Text(item.filename, fontSize = Font.meta, color = tone.textMuted, maxLines = 1)
        Text(formatBytes(item.size), fontSize = Font.meta, color = tone.textMuted)
        if (item.fileMd5.isNotBlank()) {
            Text("MD5：${item.fileMd5}", fontSize = Font.meta, color = tone.textMuted, maxLines = 1)
        }
    }
}

/** 详情页：预览 + 信息表 + 「修改 MD5」。 */
@Composable
private fun DetailStage(state: Md5Ui, vm: AppViewModel) {
    val item = state.picked ?: return

    Header(onBack = vm::resetMd5Flow)

    PreviewBox(item, controls = false) {
        // 「重选」直接盖在预览上（参考界面就是这么放的），右上角，不抢画面
        OverlayChip(
            reselectLabel(item),
            onClick = vm::resetMd5Flow,
            modifier = Modifier.align(Alignment.TopEnd).padding(Dim.gapSm),
        )
    }

    DetailInfoCard(item)

    NoticeArea(state)

    PrimaryButton(
        text = if (state.working) "正在改写…" else "修改 MD5",
        onClick = vm::runMd5Edit,
        enabled = !state.working,
        modifier = Modifier.fillMaxWidth(),
    )
    if (state.working) {
        Hint("无损重封装：不重编码、画质不变，但整个文件要重写一遍，大文件要等十几秒。")
    }
    Hint("改出来的是一份新文件，你选的原始文件不会被改动。")
}

/** 结果页：可播放的预览 + 新旧两个 MD5 + 重选 / 保存。 */
@Composable
private fun ResultStage(state: Md5Ui, vm: AppViewModel, onSave: () -> Unit) {
    val item = state.result ?: return

    Header(onBack = vm::resetMd5Flow)

    PreviewBox(item, controls = true)

    ResultInfoCard(item)

    NoticeArea(state)

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dim.gap)) {
        GhostButton(
            text = reselectLabel(item),
            onClick = vm::resetMd5Flow,
            enabled = !state.working,
            modifier = Modifier.weight(1f),
        )
        PrimaryButton(
            text = if (state.working) "保存中…" else "保存文件",
            // 走权限门：没有文件管理权限就先弹窗，别让用户以为存好了、其实存进了私有目录
            onClick = onSave,
            enabled = !state.working,
            modifier = Modifier.weight(1f),
        )
    }
    Hint("保存会另存一份新文件到下载目录，原文件保持不动。")
}

/** 详情页信息表：时长（只有视频）/ 大小 / 分辨率 / MD5。 */
@Composable
private fun DetailInfoCard(item: Md5FileUi) {
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(Dim.gap)) {
            // 时长只有视频才有。图片那一行不显示 —— 表格里多一行「—」是噪音
            if (item.kind == Md5Media.VIDEO) {
                PlainRow("时长", mmss(item.durationMs))
            }
            PlainRow("大小", sizeLabel(item.size), SizeAccent)
            PlainRow("分辨率", resolution(item))
            PlainRow("MD5", item.md5.ifBlank { "—" })
        }
    }
}

/**
 * 结果页信息表：改过的两行（大小、MD5）用「旧值划掉 + 新值跟在后面」呈现。
 *
 * MD5 只保留一个标题 —— 不再单独占一行「原 MD5」，旧值就和大小一样划在同行里。
 */
@Composable
private fun ResultInfoCard(item: Md5FileUi) {
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(Dim.gap)) {
            if (item.kind == Md5Media.VIDEO) {
                PlainRow("时长", mmss(item.durationMs))
            }
            ChangedRow(
                label = "大小",
                before = if (item.sizeBefore > 0) sizeLabel(item.sizeBefore) else "",
                after = sizeLabel(item.size),
                afterColor = SizeAccent,
            )
            PlainRow("分辨率", resolution(item))
            ChangedRow(
                label = "MD5",
                before = item.md5Before,
                after = item.md5.ifBlank { "—" },
                afterColor = tone.text,
            )
        }
    }
}

/** 一行：左边固定宽度的标签，右边是内容（内容自己决定怎么排）。 */
@Composable
private fun InfoRow(label: String, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dim.gap),
    ) {
        Text(
            label,
            fontSize = Font.hint,
            color = tone.textMuted,
            // 44dp：够放「分辨率」三个字，又给右边留出足够宽度。实测 64dp 时一个
            // 32 位 MD5 就差几个 dp 排不进一行、被折成两行 —— 用户要的是「旧值一行、
            // 新值一行」，那就得让每个值都排得下
            modifier = Modifier.width(44.dp),
        )
        // weight(1f) 是给下面的内容一个确定的宽度 —— 不然它撑到屏幕外，就不会折行
        Box(Modifier.weight(1f)) { content() }
    }
}

/** 一行普通值（详情页用）。 */
@Composable
private fun PlainRow(label: String, value: String, valueColor: Color = tone.text) {
    InfoRow(label) {
        Text(value, fontSize = Font.hint, color = valueColor)
    }
}

/**
 * 一行「改过」的值：旧值划掉、新值跟在右边；一行放不下就折到下一行。
 *
 * 用 FlowRow 而不是 Row：Row 不折行，MD5 两个 32 位十六进制串排在一起会直接顶出屏幕。
 * FlowRow 放不下的就往下一行排 —— 这正是要的效果：大小两个值一行放得下（新值就在
 * 右边），MD5 放不下（旧值一行、新值一行）。
 *
 * 旧值即使与显示出来的新值长得一样也照常划 —— 「划掉的那份作废」是这一行的意思所在，
 * 不是拿两个值比大小。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChangedRow(label: String, before: String, after: String, afterColor: Color) {
    InfoRow(label) {
        if (before.isBlank()) {
            Text(after, fontSize = Font.hint, color = afterColor)
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                StruckText(before)
                Text(after, fontSize = Font.hint, color = afterColor)
            }
        }
    }
}

/**
 * 淡色文字 + 一条**红色删除线**（穿过字中间）。
 *
 * 线为什么自己画：TextDecoration.LineThrough 那条线的颜色是跟着文字颜色走的，
 * 做不出「淡色字 + 红线」。
 *
 * 线的位置为什么按**基线**算、而不是按布局盒高度算：文本的布局盒含行高，比字形
 * 高一大截（十几 sp 的小字，盒子能高出一半）。按盒子高度取比例会把线画到字形下方，
 * 看着就成了下划线 —— 那不是删除线。改用「基线往上半个字高」这个与盒高无关的
 * 定位，字被线实实在在地穿过。
 */
@Composable
private fun StruckText(text: String) {
    val lineColor = tone.danger
    val textSizePx = with(LocalDensity.current) { Font.hint.toPx() }
    var baseline by remember { mutableStateOf(0f) }
    Box {
        Text(
            text,
            fontSize = Font.hint,
            color = tone.textMuted,
            onTextLayout = { baseline = it.getLineBaseline(0) },
        )
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    if (baseline <= 0f) return@drawBehind
                    // 十六进制串基本是等高字形，视觉中线约在基线上方半个字高处
                    val y = baseline - textSizePx * 0.36f
                    drawLine(
                        color = lineColor,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
        )
    }
}

/** 「重选」文案跟着媒体类型走：选的是图片就说重选图片。 */
private fun reselectLabel(item: Md5FileUi): String =
    if (item.kind == Md5Media.IMAGE) "重选图片" else "重选视频"

/** 页面头部：返回箭头 + 标题。 */
@Composable
private fun Header(onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.ArrowBack,
                contentDescription = "返回",
                tint = tone.text,
                modifier = Modifier.size(20.dp),
            )
        }
        Text("MD5 修改", fontSize = Font.viewTitle, fontWeight = FontWeight.SemiBold, color = tone.text)
    }
}

/**
 * 预览区。
 *
 * @param controls 传给播放器：详情页不要控件，结果页要（保存前得能确认这段真能播）。
 * @param overlay  盖在预览上的东西（目前只有那枚「重选视频」）。
 */
@Composable
private fun PreviewBox(
    item: Md5FileUi,
    controls: Boolean,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    // 拿不到真实宽高就按 9:16 竖屏占位 —— 这个流程里绝大多数是手机拍的竖屏内容
    val ratio = if (item.width > 0 && item.height > 0) {
        (item.width.toFloat() / item.height).coerceIn(0.4f, 3f)
    } else {
        9f / 16f
    }
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // 上限比 PlayerBox 的 40% 再紧一点：这一页预览下面还有一张信息表和两个按钮，
        // 实测竖屏视频（576x1024 那种）按 42% 算，结果页的「保存文件」正好落到
        // 底部导航栏后面 —— 主操作被挡住比预览小一点难受得多。
        val h = (maxWidth / ratio).coerceAtMost(screenHeight * 0.34f)
        Box(
            Modifier
                .fillMaxWidth()
                .height(h)
                .clip(RoundedCornerShape(Dim.radius))
                .background(Color.Black)
        ) {
            if (item.kind == Md5Media.IMAGE) {
                // 图片直接按文件路径加载（Coil 认 File），铺满、留边
                AsyncImage(
                    model = File(item.path),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                LocalPlayerView(item.path, controls = controls, modifier = Modifier.fillMaxSize())
            }
            overlay()
        }
    }
}

/** 盖在预览上的胶囊按钮（半透明黑底、白字，保证压在任何画面上都看得清）。 */
@Composable
private fun OverlayChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            Icons.Default.Refresh,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(14.dp),
        )
        Text(text, fontSize = Font.hint, color = Color.White)
    }
}

/** 出错说明与一次性提示。两者同时出现时以出错为准。 */
@Composable
private fun NoticeArea(state: Md5Ui) {
    val message = state.error.ifBlank { state.notice.orEmpty() }
    if (message.isBlank()) return
    Card {
        Text(
            message,
            fontSize = Font.meta,
            color = if (state.error.isNotBlank()) tone.danger else tone.textMuted,
        )
    }
}

/** 00:12 这种时长。比 formatDuration 多补了前导零 —— 参考界面的时长是两位分钟。 */
private fun mmss(durationMs: Long): String {
    if (durationMs <= 0) return "—"
    val total = (durationMs / 1000).toInt()
    return "%02d:%02d".format(total / 60, total % 60)
}

/** 576 x 1024 这种分辨率。拿不到就如实说拿不到，不编一个值。 */
private fun resolution(item: Md5FileUi): String =
    if (item.width > 0 && item.height > 0) "${item.width} x ${item.height}" else "—"

/**
 * MD5 页信息表里的大小：保留两位小数。
 *
 * 不用全局的 formatBytes —— 它只保留一位小数，而这一行要把「改前 / 改后」两个值并排
 * 给用户看，一位小数常把只差几 KB 的两个值显示成一模一样，看着像是没改成功。
 * 参考界面上那个「1.72 MB」也是两位小数。
 */
private fun sizeLabel(bytes: Long): String {
    if (bytes <= 0) return "—"
    val v = bytes.toDouble()
    return when {
        v >= 1024 * 1024 -> "%.2f MB".format(v / 1024 / 1024)
        v >= 1024 -> "%.2f KB".format(v / 1024)
        else -> "$bytes B"
    }
}
