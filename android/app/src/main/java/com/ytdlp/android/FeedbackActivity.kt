package com.ytdlp.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.ytdlp.android.ui.FeedbackScreen
import com.ytdlp.android.ui.YtdlpTheme
import com.ytdlp.android.ui.tone

/**
 * 意见反馈页的宿主。
 *
 * 单独开一个 Activity，而不是在 MainScreen 里加第四个页签：反馈是「从设置
 * 点进去、办完就退出来」的支线操作，做成常驻页签会让底部导航多一个几乎不用
 * 的入口。这样处理还有个好处 —— 返回手势/返回键直接就是系统行为，不用自己
 * 维护返回栈。引擎自检页当初也是这么处理的。
 */
class FeedbackActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            YtdlpTheme {
                Scaffold(containerColor = tone.bg) { inner ->
                    FeedbackScreen(
                        onBack = { finish() },
                        modifier = Modifier.fillMaxSize().padding(inner),
                    )
                }
            }
        }
    }
}
