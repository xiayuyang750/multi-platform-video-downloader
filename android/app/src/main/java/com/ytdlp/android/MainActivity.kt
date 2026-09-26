package com.ytdlp.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ytdlp.android.ui.AppViewModel
import com.ytdlp.android.ui.MainScreen
import com.ytdlp.android.ui.YtdlpTheme

/**
 * 正式界面的入口。
 *
 * enableEdgeToEdge 让内容铺到状态栏/导航栏下方，配合 Scaffold 的
 * contentWindowInsets 自动留出安全边距 —— Android 15 起状态栏颜色 API
 * 已废弃，这是当前推荐做法。
 */
class MainActivity : ComponentActivity() {

    /** 待处理的分享链接。onNewIntent 和 onCreate 都可能写入，用 State 让界面感知。 */
    private var sharedUrl by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        sharedUrl = extractUrl(intent)

        setContent {
            YtdlpTheme {
                val vm: AppViewModel = viewModel()

                // 别人分享链接过来时，直接填进输入框并自动解析 ——
                // 用户点「分享」的意图本来就是「我要处理这个链接」，
                // 再多点一次「解析」是多余的。
                LaunchedEffect(sharedUrl) {
                    sharedUrl?.let { url ->
                        vm.onUrlChange(url)
                        vm.parse()
                        sharedUrl = null
                    }
                }

                MainScreen(vm)
            }
        }
    }

    /** singleTop 模式下再次分享会走这里，而不是重建 Activity。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedUrl = extractUrl(intent)
    }

    private fun extractUrl(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        return firstUrl(text)
    }
}

/**
 * 从一段文本里挑出第一个链接。
 *
 * 为什么不能直接用整段文本：分享出来的内容常常是
 * 「【标题】 作者 https://...」这种带前后缀的形式，
 * 直接当 URL 解析必然失败。也不能简单按空格切 —— 分享文本常带换行。
 */
internal fun firstUrl(text: String): String? {
    val match = Regex("""https?://\S+""").find(text) ?: return null
    // 中文标点常被连着抓进来（例如「看这个 https://x.com/a/status/1，很有意思」），
    // 它们不是 URL 的一部分，要剪掉；英文句末的点同理，但域名里的点要留，
    // 所以只从末尾逐个剥掉标点，剥到非标点为止。
    return match.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '"', '\'',
        '。', '，', '、', '；', '：', '！', '？', '）', '】', '」', '』', '《', '》')
}
