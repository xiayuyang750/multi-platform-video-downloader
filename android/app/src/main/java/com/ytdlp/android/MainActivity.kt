package com.ytdlp.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ytdlp.android.ui.YtdlpTheme
import com.ytdlp.android.ui.tone

/**
 * 正式界面的入口。阶段 3 会在这里搭出解析/历史/设置三页。
 *
 * 当前是工具链自检版：只验证 Compose 能编译、主题能生效、真机能跑起来。
 * 三种颜色分别来自设计令牌的 bg / text / accent，若它们显示正常，
 * 说明「从 style.css 抽出的配色」这条链路是通的。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            YtdlpTheme {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(tone.bg)
                        .padding(24.dp)
                ) {
                    Text(
                        "Compose 工具链自检",
                        style = MaterialTheme.typography.titleLarge,
                        color = tone.text,
                    )
                    Text(
                        "这行字能出现，说明 Compose 编译、主题注入、真机运行三件事都成立。\n" +
                            "背景色 = tone.bg，正文色 = tone.text，下行 = tone.accent。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = tone.textMuted,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Text(
                        "accent 色测试",
                        style = MaterialTheme.typography.titleMedium,
                        color = tone.accent,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }
}
