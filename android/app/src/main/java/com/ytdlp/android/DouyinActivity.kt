package com.ytdlp.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import com.ytdlp.android.engine.DouyinParser
import java.net.HttpURLConnection
import java.net.URL

/**
 * 抖音的浏览器模式解析页。
 *
 * 为什么必须用 WebView：抖音的接口要求 a_bogus 签名和登录态，纯 HTTP 客户端
 * 两样都拿不到，所以 yt-dlp 必然 403。而 WebView 本身就是浏览器，它会自己
 * 完成签名和登录。我们只需要在它请求 aweme/detail 时拦下这一条，
 * 顺手把响应体读出来。
 *
 * 与 Windows 端的区别：Windows 端用 DrissionPage 驱动本机浏览器、监听响应；
 * 安卓上没这个库，换成 WebView 的 shouldInterceptRequest。效果一样，
 * 而且用户能亲眼看到浏览器在做什么。
 *
 * 首次使用可能需要在页面里登录抖音 —— 登录态会存在 WebView 的 Cookie 里，
 * 之后解析就不用再登了。
 */
class DouyinActivity : Activity() {

    /** 是否已经把结果交回给调用方。成功和超时都可能触发，只认第一次。 */
    @Volatile
    private var delivered = false

    private lateinit var statusView: TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val shareUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (shareUrl.isBlank()) {
            finish()
            return
        }

        statusView = TextView(this).apply {
            text = "正在用浏览器模式解析抖音…\n如果页面提示登录，登录后再等几秒即可。"
            textSize = 13f
            setPadding(32, 40, 32, 32)
            setTextColor(Color.parseColor("#1A1D21"))
        }

        val web = WebView(this).apply {
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
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val url = request.url.toString()
                    // 只拦详情接口这一条：页面还会加载几十个静态资源，
                    // 全拦下来既慢又没必要
                    if (delivered || !url.contains(DouyinParser.DETAIL_API)) return null

                    val body = fetchBody(url) ?: return null
                    val payload = DouyinParser.parse(body, shareUrl) ?: return null
                    deliver(payload.toString())
                    // 原样交回给 WebView，页面行为不受影响
                    return WebResourceResponse(
                        "application/json", "utf-8", body.byteInputStream()
                    )
                }
            }
            loadUrl(shareUrl)
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    statusView,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
                // WebView 占满剩余高度（weight=1）
                addView(
                    web,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1f,
                    ),
                )
            }
        )

        // 超时兜底：和 Windows 端一致（douyin_fallback.resolve 的 timeout=45）。
        // 没有这道保护，页面一直转圈时用户就只能看到个不会关的界面。
        statusView.postDelayed({
            if (!delivered) {
                deliver(null, "45 秒内没等到视频数据。可能是链接不是单条视频，" +
                    "或者需要先在页面里登录抖音再重试。")
            }
        }, TIMEOUT_MS)
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
