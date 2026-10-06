package com.ytdlp.android.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ytdlp.android.BuildConfig
import com.ytdlp.android.FeedbackActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 设置页：保存位置 + Cookie + 诊断入口。对应网页端的第三个视图。 */
@Composable
fun SettingsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val state by vm.settings.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    // Cookie 用系统文件选择器挑，但**不**把 SAF 的 URI 交给引擎 ——
    // yt-dlp 走的是普通文件 API，读不了 content:// 这种 URI。
    // 所以选完立刻把内容拷进应用私有目录，再把这个真实路径交给引擎。
    val pickCookie = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val copied = withContext(Dispatchers.IO) {
                runCatching {
                    val dest = File(context.filesDir, "cookies.txt")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    dest.absolutePath
                }.getOrNull()
            }
            if (copied == null) {
                vm.setCookiesFile("") // 读失败就保持原样，提示会由空路径触发
            } else {
                vm.setCookiesFile(copied)
            }
        }
    }

    // 一次性提示显示完就清掉，避免转屏后又弹一次
    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            kotlinx.coroutines.delay(2500)
            vm.consumeNotice()
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dim.screenPadding)
            .padding(top = Dim.gapLg, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(Dim.gapLg),
    ) {
        Text(
            "设置",
            style = MaterialTheme.typography.titleLarge,
            color = tone.text,
        )

        if (state.notice != null) {
            Card {
                Text(state.notice!!, fontSize = Font.body, color = tone.accent)
            }
        }

        // ---- 外观 ----
        // 放在「正在读取设置」那个提前 return 之前：外观是纯 UI 偏好，
        // 不依赖引擎，没道理等 Python 那趟往返回来才让用户看到。
        Card {
            FieldLabel("外观")
            Spacer12()
            Row(horizontalArrangement = Arrangement.spacedBy(Dim.gapSm)) {
                ThemeMode.entries.forEach { m ->
                    SelectChip(
                        label = m.label,
                        selected = themeMode == m,
                        onClick = { vm.setThemeMode(m) },
                    )
                }
            }
        }

        val s = state.settings
        if (s == null) {
            Text("正在读取设置…", fontSize = Font.meta, color = tone.textMuted)
            return@Column
        }

        // ---- 保存位置 ----
        Card {
            FieldLabel("保存位置")
            Spacer12()
            Text(
                s.outputDir,
                fontSize = Font.meta,
                color = tone.text,
            )
            // 正常情况只给路径，不再解释一遍：解析页的授权引导已经把「存哪、
            // 为什么」说完了，这里再说就是重复。
            // 只有真落到私有目录时才说明原因 —— 那正是用户会疑惑「文件去哪了」的时候。
            if (s.outputDirIsFallback) {
                Spacer12()
                Hint(
                    "现在用的是应用私有目录：系统「下载」目录（${s.preferredDir}）" +
                        "要存储权限才写得进去。\n" +
                        "私有目录里的文件用文件管理器看不到，只能通过应用内分享导出。"
                )
            }
        }

        // ---- Cookie ----
        Card {
            FieldLabel("Cookie 文件")
            Spacer12()
            Text(
                if (s.cookiesPresent) "已导入：${s.cookiesFile}" else "未导入",
                fontSize = Font.meta,
                color = if (s.cookiesPresent) tone.text else tone.textMuted,
            )
            Spacer12()
            Hint(
                "Instagram、TikTok 需要它 —— 这两个不登录就解析不了。\n" +
                    "X（推特）不必导入 —— 官方接口走不通时会自动走备用链路，不登录也能解析；" +
                    "导入则优先走官方接口。\n" +
                    "YouTube 反而不能用（会把画质从 1080p 降到 360p）。\n" +
                    "导出方法：用浏览器扩展导出 Netscape 格式的 cookies.txt，" +
                    "导出前确保处于登录状态。"
            )
            Spacer12()
            Row(horizontalArrangement = Arrangement.spacedBy(Dim.gap)) {
                GhostButton(
                    text = if (s.cookiesPresent) "重新导入" else "导入 Cookie",
                    onClick = { pickCookie.launch(arrayOf("text/plain", "*/*")) },
                    modifier = Modifier.weight(1f),
                )
                if (s.cookiesPresent) {
                    GhostButton(
                        text = "清除",
                        onClick = { vm.setCookiesFile("") },
                        danger = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // ---- 反馈 ----
        // 引擎自检页的入口从这里删掉了：它是调试页，一进去会自动跑三条网络解析，
        // 用户看了只会以为应用卡死或偷跑流量。页面本身保留（调试版/adb 仍可打开），
        // 详见 AndroidManifest 里 ProbeActivity 的注释。
        Card {
            FieldLabel("遇到问题？")
            Spacer12()
            Hint(
                "解析不了、下载报错、界面不对劲，这些当然可以反馈；" +
                    "但不只是报错 —— 觉得哪里不好用、想加什么功能、有更好的做法，也都可以说。\n" +
                    "平台和链接写清楚的话，我复现会快很多。"
            )
            Spacer12()
            PrimaryButton(
                text = "意见反馈",
                onClick = {
                    runCatching {
                        context.startActivity(Intent(context, FeedbackActivity::class.java))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ---- 版本更新 ----
        Card {
            FieldLabel("版本更新")
            Spacer12()
            Text(
                "当前版本 ${BuildConfig.VERSION_NAME}",
                fontSize = Font.meta,
                color = tone.textMuted,
            )

            when (val u = update) {
                is UpdateUi.Checking -> {
                    Spacer12()
                    Text("正在检查…", fontSize = Font.meta, color = tone.textMuted)
                }

                is UpdateUi.Available -> {
                    Spacer12()
                    Text(
                        "发现新版本 ${u.version}",
                        fontSize = Font.body,
                        fontWeight = FontWeight.SemiBold,
                        color = tone.accent,
                    )
                    if (u.notes.isNotBlank()) {
                        Spacer12()
                        // 版本说明可能很长，截断到 300 字，避免把设置页撑爆
                        Hint(u.notes.take(300))
                    }
                    Spacer12()
                    GhostButton(
                        text = "前往下载",
                        onClick = { uriHandler.openUriSafe(u.url) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                is UpdateUi.UpToDate -> {
                    Spacer12()
                    Text(
                        "已是最新版本",
                        fontSize = Font.meta,
                        color = tone.accent,
                    )
                }

                is UpdateUi.Failed -> {
                    Spacer12()
                    // 网络失败也如实说明原因（用户明确要求），不要吞掉
                    Text(
                        u.reason,
                        fontSize = Font.meta,
                        lineHeight = 20.sp,
                        color = tone.danger,
                    )
                }

                is UpdateUi.Idle -> Unit
            }

            Spacer12()
            GhostButton(
                text = if (update is UpdateUi.Checking) "检查中…" else "检查更新",
                onClick = vm::checkUpdate,
                enabled = update !is UpdateUi.Checking,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ---- 关于 ----
        Card {
            FieldLabel("关于")
            Spacer12()
            // 这里刻意写全：解析下载只是 yt-dlp，但整个链路远不止它 ——
            // 合流、画质解锁、抖音、X 备用链路各有各的实现，只说「由 yt-dlp 完成」
            // 既不准确，也让用户遇到问题时不知道该往哪个方向想。
            Hint(
                // 支持范围放最前面：这是用户最需要先知道的一条，比技术栈重要得多。
                "抖音的图文、图集、实况图都已支持（实况图会存成「图片 + 同名视频」两个文件）。\n" +
                    "关于实况图在相册里的显示：目前只在一台 vivo 手机上实测通过 —— " +
                    "相册能不能把它认成会动的实况图，取决于各品牌系统里那个「动态照片」字段叫什么，" +
                    "各家并不统一，所以其他品牌不保证相册能识别。" +
                    "但图片和视频两个文件一定会完整保存，任何手机都能正常查看和播放。\n" +
                    "另外：实况图要在相册里生效，需要把文件存进系统公共目录（也就是要授予存储权限）；" +
                    "没有权限时会存进应用私有目录，那里相册看不到。\n" +
                    "解析与下载由 yt-dlp 完成；音视频合流用随包分发的自编译 ffmpeg；" +
                    "YouTube 的画质解锁靠自编译的 JS 运行时。\n" +
                    "抖音走公开接口直取 —— 无需签名与登录，浏览器模式仅在接口失效时兜底；" +
                    "X 在官方接口失效时走备用链路。\n" +
                    "反馈渠道打不开时可以发邮件到 ${FEEDBACK_MAIL}，记得备注主题或来意。"
            )
        }
    }
}

/** 与 Windows 端保持一致（main.py 的 FEEDBACK_MAIL）。 */
private const val FEEDBACK_MAIL = "xiayuyang750@gmail.com"
