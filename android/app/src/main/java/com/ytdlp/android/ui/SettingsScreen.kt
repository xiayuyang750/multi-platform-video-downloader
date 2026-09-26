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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ytdlp.android.ProbeActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 设置页：保存位置 + Cookie + 诊断入口。对应网页端的第三个视图。 */
@Composable
fun SettingsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val state by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
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
            Spacer12()
            if (s.outputDirIsFallback) {
                // 如实说明为什么没用系统下载目录：用户找不到文件时要有据可查
                Hint(
                    "当前用的是应用私有目录。系统「下载」目录（${s.preferredDir}）" +
                        "需要存储权限才能写入，授权后会自动改回去。\n" +
                        "私有目录里的文件无法用文件管理器直接看到，只能通过应用内分享导出。"
                )
            } else {
                Hint("文件会保存在系统的「下载」目录，用文件管理器就能找到。")
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
                "只有 X（推特）和 Instagram 需要它 —— 这两个平台不登录就解析不了。\n" +
                    "YouTube 不要导入：实测带 Cookie 会把画质从 1080p 降到 360p。\n" +
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

        // ---- 诊断 ----
        Card {
            FieldLabel("遇到问题？")
            Spacer12()
            Hint(
                "如果某个链接解析不了、或下载报错，可以打开自检页跑一遍。" +
                    "它会如实列出引擎状态和原始报错，便于定位问题。"
            )
            Spacer12()
            GhostButton(
                text = "打开引擎自检页",
                onClick = {
                    runCatching {
                        context.startActivity(Intent(context, ProbeActivity::class.java))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ---- 关于 ----
        Card {
            FieldLabel("关于")
            Spacer12()
            Hint(
                "解析与下载由 yt-dlp 完成，音视频合流用内置的 ffmpeg，" +
                    "YouTube 的画质解锁靠内置的 JS 运行时。\n" +
                    "反馈：${FEEDBACK_MAIL}"
            )
        }
    }
}

/** 与 Windows 端保持一致（main.py 的 FEEDBACK_MAIL）。 */
private const val FEEDBACK_MAIL = "xiayuyang750@gmail.com"
