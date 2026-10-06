package com.ytdlp.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.ytdlp.android.engine.DouyinParser
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 抖音的浏览器模式解析页 —— 一个**用户完全看不到**的后台窗口。
 *
 * 为什么必须用 WebView：抖音的接口要求 a_bogus 签名和登录态，纯 HTTP 客户端
 * 两样都拿不到，所以 yt-dlp 必然 403。而 WebView 本身就是浏览器，它会自己
 * 完成签名。我们只需要拦下它请求的页面主文档，把响应体读出来。
 *
 * 与 Windows 端的区别：Windows 端用 DrissionPage 驱动本机浏览器、监听响应；
 * 安卓上没这个库，换成 WebView 的 shouldInterceptRequest。效果一样。
 *
 * 界面上一律不出现（主题是全透明的，见 Theme.Ytdlp.Transparent）：
 * 用户始终停在解析页看转圈，解析完直接出结果。早先这里是一整页白底 + 一行状态
 * 文字，等于把「正在解析」从原界面搬到一个空页面上，观感更像卡住了。
 */
class DouyinActivity : Activity() {

    /** 是否已经把结果交回给调用方。成功和超时都可能触发，只认第一次。 */
    @Volatile
    private var delivered = false

    /**
     * 持有引用只是为了在 onDestroy 里销毁它。
     *
     * 为什么必须销毁：WebView 内部有原生资源，只让 Activity 被回收是不够的。
     * 实测（真机）不销毁时，一次解析后 PSS 从 160 MB 涨到 290 MB 且**不回落**。
     */
    private var web: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val shareUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (shareUrl.isBlank()) {
            finish()
            return
        }

        val view = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // 关键：必须用**桌面版** UA。
            // 实测用移动版 UA 时，v.douyin.com 会跳到 iesdouyin.com/share/video/xxx
            // 这种分享页，数据是服务端直出在 HTML 里的，全程不请求 aweme/detail ——
            // 拦截器一直等不到东西，最后只能超时。换成桌面 UA 后才会跳转到
            // www.douyin.com/video/xxx，走真实接口（Windows 端用桌面浏览器正是
            // 因此才拦得到）。
            settings.userAgentString = DESKTOP_UA
            // 配合桌面 UA：按桌面宽度渲染，否则窄视口会让页面切回移动版结构
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            // 页面会自动播放带声音的视频。而这个 WebView 是**藏在后台**的 ——
            // 用户既看不到画面，也找不到地方关声音（比旧版「把网页摆在眼前」更糟）。
            // 实测：不加限制时本应用确实创建了 AudioTrack。
            // 要求「用户手势」才允许播放，从源头掐掉自动播放。
            settings.mediaPlaybackRequiresUserGesture = true
            webViewClient = object : WebViewClient() {
                /**
                 * 尽量早地注入「静音」补丁。
                 *
                 * 为什么不能等页面加载完再静音：实测（真机）只靠
                 * `mediaPlaybackRequiresUserGesture` + onPageFinished 里 pause，
                 * 页面**仍然**创建并启动了声轨（logcat 出现
                 * `AudioTrack: start(calling package is:com.ytdlp.android)`）。
                 * 抖音页面大概走的是 Web Audio 或在手势上下文里播放，绕过了那两条限制。
                 * 所以在文档刚开始加载时就改写 `HTMLMediaElement.play` 与
                 * `AudioContext.resume`，并把音量钉在 0。
                 */
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    view.evaluateJavascript(SILENCE_JS, null)
                }

                /** 页面加载完再做一次兜底：把已存在的媒体全部暂停并静音。 */
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    view.evaluateJavascript(SILENCE_JS, null)
                }
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val url = request.url.toString()
                    // 媒体文件（音/视频）一律不加载：这个 WebView 只需要主文档里
                    // 已带的作品数据，页面自动播放的视频既没必要，又会在后台发出
                    // 声音 —— 而它藏在后台，用户看不到、也找不到地方关。
                    // 实测光靠 JS 静音补丁仍会创建声轨；把媒体请求直接掐掉，
                    // 才从根上没有了可播放的内容。
                    if (looksLikeMedia(url)) {
                        return WebResourceResponse(
                            "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
                        )
                    }
                    // 只认页面主文档：目标数据就直出在里面（服务端渲染），
                    // 其余请求（静态资源、接口、媒体）一律放行或掐掉，不做处理。
                    if (delivered) return null
                    if (!url.contains("www.douyin.com/note/") &&
                        !url.contains("www.douyin.com/video/")
                    ) {
                        return null
                    }

                    val body = fetchBody(url) ?: return null
                    // 作品 id 直接从主文档地址里取（形如 /note/7674197857957071025）
                    val id = Regex("/(?:note|video)/(\\d+)")
                        .find(url)?.groupValues?.getOrNull(1).orEmpty()
                    val payload = DouyinParser.parseMainDocument(body, shareUrl, id) ?: return null
                    deliver(payload.toString())
                    // 返回 null：让 WebView 用它自己那份响应，页面行为不受影响
                    return null
                }
            }
            loadUrl(shareUrl)
        }
        web = view

        // 界面上什么都不放：这个 Activity 是全透明的（见 Theme.Ytdlp.Transparent），
        // 用户看到的是底下那个解析页在转圈。它存在的唯一理由是 WebView 需要一个
        // 宿主窗口 —— WebView 本身也**不**挂进来，实测不挂载同样能加载页面并触发
        // shouldInterceptRequest。
        setContentView(FrameLayout(this))

        // 超时兜底：和 Windows 端一致（douyin_fallback.resolve 的 timeout=45）。
        // 没有这道保护，页面一直加载不出来时，用户就只能干看着转圈。
        view.postDelayed({
            if (!delivered) {
                deliver(
                    null,
                    "45 秒内没取到内容。多半是网络较慢，或页面这次没正常加载，重试一次通常就好；" +
                        "如果反复失败，欢迎用「意见反馈」把这条链接告诉我们。"
                )
            }
        }, TIMEOUT_MS)
    }

    /**
     * 销毁 WebView。
     *
     * 必须显式做这一步：WebView 持有原生资源，仅靠 Activity 结束并不会释放。
     * 实测（真机）不做销毁时，一次解析后 PSS 从 160 MB 涨到 290 MB 且不回落 ——
     * 连解析几次就会明显吃内存。
     *
     * 顺序也有讲究：先停加载、再切到空白页，最后 destroy，避免还在跑的页面
     * 在销毁过程中继续解码。
     */
    override fun onDestroy() {
        web?.let { w ->
            runCatching {
                w.stopLoading()
                w.loadUrl("about:blank")
                (w.parent as? ViewGroup)?.removeView(w)
                w.destroy()
            }
        }
        web = null
        super.onDestroy()
    }

    /**
     * 判断一个请求是不是音/视频媒体。
     *
     * 抖音的视频地址**不带扩展名**（形如
     * `https://v26-webf.douyinvod.com/<hash>/video/tos/cn/...?a=6383&...`），
     * 所以只看后缀不够，还要认它的 CDN 域名与路径特征。
     */
    private fun looksLikeMedia(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        if (path.endsWith(".mp4") || path.endsWith(".m4s") || path.endsWith(".m4a") ||
            path.endsWith(".mp3") || path.endsWith(".aac") || path.endsWith(".webm") ||
            path.endsWith(".mov")
        ) {
            return true
        }
        return path.contains("/video/tos/") || path.contains("douyinvod.com")
    }

    /**
     * 用原生 HTTP 把同一条请求再发一次，拿到响应体。
     *
     * 为什么要重发：WebView 的 shouldInterceptRequest 只能拿到请求信息，
     * 拿不到响应内容。而这条请求的 URL 里已经带了 WebView 算好的签名参数，
     * 照原样重放一次即可拿到数据。
     */
    private fun fetchBody(url: String): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", DESKTOP_UA)
            // 抖音的 CDN 与接口都会校验 Referer
            setRequestProperty("Referer", "https://www.douyin.com/")
            // 登录态在 WebView 的 Cookie 里，必须带上，否则接口会返回未登录
            CookieManager.getInstance().getCookie(url)?.let {
                setRequestProperty("Cookie", it)
            }
        }
        conn.inputStream.bufferedReader().use { it.readText() }
    }.getOrNull()

    /** 把结果交回调用方并关闭。payload 为 null 表示失败。 */
    private fun deliver(payload: String?, reason: String = "") {
        if (delivered) return
        delivered = true
        runOnUiThread {
            setResult(
                if (payload != null) RESULT_OK else RESULT_CANCELED,
                Intent().apply {
                    putExtra(EXTRA_PAYLOAD, payload)
                    putExtra(EXTRA_REASON, reason)
                },
            )
            finish()
        }
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_PAYLOAD = "payload"
        const val EXTRA_REASON = "reason"

        private const val TIMEOUT_MS = 45_000L

        /**
         * 注入页面的「静音补丁」。
         *
         * 这个 WebView 是藏在后台的：用户看不到画面，也就没有任何办法关掉声音。
         * 所以这里不是"降低音量"，而是从能力上让页面**无法发声**：
         *   ① 改写 HTMLMediaElement.play —— 保留"能播"的语义（页面逻辑不被破坏），
         *      但强制 muted + volume=0；
         *   ② 拦掉 AudioContext.resume —— 防止走 Web Audio 路线绕过 ①；
         *   ③ 对已存在的媒体立即暂停静音。
         * 全部包在 try 里：补丁本身绝不该让页面报错。
         */
        private const val SILENCE_JS = """
(function(){
  try{
    var proto = HTMLMediaElement.prototype;
    var origPlay = proto.play;
    proto.play = function(){
      try{ this.muted = true; this.volume = 0; }catch(e){}
      try{ return origPlay.apply(this); }catch(e){ return Promise.resolve(); }
    };
  }catch(e){}
  try{
    var AC = window.AudioContext || window.webkitAudioContext;
    if (AC && AC.prototype && AC.prototype.resume) {
      AC.prototype.resume = function(){ return Promise.resolve(); };
    }
  }catch(e){}
  try{
    document.querySelectorAll('video,audio').forEach(function(el){
      try{ el.pause(); el.muted = true; el.volume = 0; }catch(e){}
    });
  }catch(e){}
})();
"""

        /**
         * 与页面同款的桌面版 UA。
         *
         * 补发请求时必须与 WebView 用同一个 UA：抖音的接口会校验 UA 一致性，
         * 而且签名参数（a_bogus）是按桌面版页面算的，用移动版 UA 去请求会被拒。
         * 这个值也与 Windows 端 douyin_fallback.py 里浏览器用的 UA 保持一致。
         */
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        fun intent(context: Context, url: String): Intent =
            Intent(context, DouyinActivity::class.java).putExtra(EXTRA_URL, url)
    }
}
