package com.ytdlp.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.ytdlp.android.engine.DouyinDetail
import com.ytdlp.android.engine.DouyinParser
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 抖音的浏览器模式解析页 —— 一个**用户完全看不到**的后台窗口。
 *
 * 为什么必须用 WebView：抖音的接口要求 a_bogus 签名和登录态，纯 HTTP 客户端
 * 两样都拿不到，所以 yt-dlp 必然 403。而 WebView 本身就是浏览器，它会自己
 * 完成签名。取数据有三条路，按优先级：
 *
 *   ① **钩子**（优先）：HOOK_JS 包住页面自己的 fetch / XHR，详情接口的响应体
 *      通过 JS bridge 直接回传（Bridge.onHookResponse）。请求由页面亲自发出，
 *      签名、Cookie、登录态全是真的，因此不受「重放被风控回空」影响。
 *   ② **重放**（兜底）：shouldInterceptRequest 只拿得到请求，就用原生 HTTP
 *      把同一条请求再发一次。历史上经常回 HTTP 200 + 空响应体，只作 ① 的备份。
 *   ③ **主文档**：图文 / 图集 / 实况图的数据是服务端直出在页面里的，这条一直可用。
 *
 * 与 Windows 端的区别：Windows 端用 DrissionPage 驱动本机浏览器、监听响应；
 * 安卓上没这个库，换成 WebView 的 shouldInterceptRequest。Windows 端还多一条
 * 优势：它的浏览器可以登录抖音，因此「仅自己可见」的作品在那边有机会拿到；
 * 安卓这边没有登录入口，只能如实报出服务端给的原因。
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
     * 目标作品编号，从页面地址里取（形如 `www.douyin.com/video/<id>`）。
     *
     * 页面在加载过程中可能顺带请求**别的**作品（推荐位、合集里的下一条…），
     * 那些响应里也带作品数据。没有这个判据就会把别人的视频当成果交出去 ——
     * 界面显示「解析成功」，用户却下走了不属于自己的内容。
     */
    @Volatile
    private var expectedId = ""

    /**
     * 持有引用只是为了在 onDestroy 里销毁它。
     *
     * 为什么必须销毁：WebView 内部有原生资源，只让 Activity 被回收是不够的。
     * 实测（真机）不销毁时，一次解析后 PSS 从 160 MB 涨到 290 MB 且**不回落**。
     */
    private var web: WebView? = null

    /** 待解析的分享链接。桥方法在别的线程上也要用它，所以存成字段而不是局部变量。 */
    private var shareUrl = ""

    /**
     * 作品页是否**加载完成**过。
     *
     * 超时时要靠它区分两种完全不同的原因：
     *   页面加载完成 → 网络没问题，是平台不给数据；
     *   页面没加载完 → 就是网络/加载失败。
     * 不记这个就只能把两种可能一起写进提示里，等于什么都没说。
     */
    @Volatile
    private var pageLoaded = false

    /** 是否收到过详情接口的**空响应体**（抖音对限流作品的既有行为）。 */
    @Volatile
    private var emptyDetail = false

    /** 空响应体连续出现几次。达到阈值就可以直接给结论，不必空等超时。 */
    @Volatile
    private var emptyDetailCount = 0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        shareUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
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
            // 优先路径的数据入口：页面自己的 fetch / XHR 拿到响应体后，通过这个
            // 桥回传过来（见 HOOK_JS / onHookResponse）。它比 shouldInterceptRequest
            // 更可靠 —— 后者只能拿到请求，响应体得用原生 HTTP 重放一次，
            // 而重放带着「不是页面自己发的」痕迹，容易被风控回空。
            addJavascriptInterface(Bridge(), BRIDGE_NAME)
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
                    Log.i(TAG, "page started: $url")
                    // 作品已不存在时，抖音不是回错误页，而是把请求**重定向到精选推荐流**，
                    // 地址上带着 `previous_page=web_video_404_link` 这个标记
                    // （实测 7693090141682334373）。此时详情接口对页面自己也回空响应体，
                    // 再等下去只会熬满 15 秒；看到标记就直接给结论。
                    if (url.contains("web_video_404_link")) {
                        Log.i(TAG, "video 404, abort early")
                        deliver(null, "这条作品已被作者删除，或已设为私密作品。")
                        return
                    }
                    // 短链跳转后的地址里就带着作品编号（实测 v.douyin.com 会 302 到
                    // www.douyin.com/video/<id>）。趁早记下来，后面拦到响应时用它
                    // 判断「这条数据是不是我要的那条」。
                    videoIdFrom(url)?.let { expectedId = it }
                    view.evaluateJavascript(SILENCE_JS, null)
                    // 钩子必须在页面脚本之前装好，否则页面那次详情请求已经发完了。
                    view.evaluateJavascript(HOOK_JS, null)
                }

                /** 页面加载完再做一次兜底：把已存在的媒体全部暂停并静音。 */
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    Log.i(TAG, "page finished: $url")
                    if (videoIdFrom(url) != null) pageLoaded = true
                    view.evaluateJavascript(SILENCE_JS, null)
                    // 有些站内跳转不重新触发 onPageStarted，重复装一次更稳（JS 侧有幂等判断）
                    view.evaluateJavascript(HOOK_JS, null)
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
                    // 拦截只有两个目标：详情接口和页面主文档。其余请求
                    // （静态资源、推荐流、媒体）一律放行，不做处理。
                    if (delivered) return null

                    // ① 详情接口 —— 视频类作品的数据本应在这里。
                    //
                    //    视频页的主文档实测只是个 JS 虚拟机壳（72914 字节、零数据），
                    //    真正的数据由页面脚本算好签名后再请求这条接口取回。
                    //
                    //    取响应体有两条路，这里用的是第二条：
                    //      a) **优先**：HOOK_JS 钩住页面自己的 fetch/XHR，直接把响应体
                    //         通过 Bridge 回传（见 onHookResponse）。请求由页面亲自发出，
                    //         签名和登录态都是真的。
                    //      b) 兜底：本分支用原生 HTTP 把这条请求**重放**一次拿响应体。
                    //         历史上这条路经常回 **HTTP 200 + 空响应体**（抖音把无头
                    //         WebView 判成可疑环境），所以它只作为 a 的备份，不再承担
                    //         主责。
                    //
                    //    图文 / 图集 / 实况图不受影响：它们的数据是服务端直出在主
                    //    文档里的，走下面第 ② 条。
                    if (url.contains(DouyinParser.DETAIL_API)) {
                        val body = fetchBody(url, request.requestHeaders)
                        Log.i(TAG, "detail via replay: len=${body?.length ?: -1}")
                        if (body == null) return null
                        val r = DouyinParser.parseDetail(body, shareUrl, expectedId)
                        when (r) {
                            is DouyinDetail.Video -> deliver(r.payload.toString())
                            // 服务端明确说这条作品看不了（仅自己可见 / 已删除）：
                            // 这是定论，立刻如实回给用户，不让他干等超时
                            is DouyinDetail.Unavailable -> deliver(null, r.message)
                            DouyinDetail.None -> Unit
                        }
                        return null
                    }

                    // ② 页面主文档 —— 图文 / 图集 / 实况图的数据直出在里面
                    //    （实测这三种页面有服务端渲染，而视频页没有）。
                    if (!url.contains("www.douyin.com/note/") &&
                        !url.contains("www.douyin.com/video/")
                    ) {
                        return null
                    }

                    val body = fetchBody(url, request.requestHeaders) ?: return null
                    val payload = DouyinParser.parseMainDocument(
                        body, shareUrl, videoIdFrom(url).orEmpty(),
                    )
                    Log.i(
                        TAG,
                        "main doc: len=${body.length} images=" + body.contains("images") +
                            " playAddr=" + body.contains("playAddr") +
                            " parsed=" + (payload != null),
                    )
                    if (payload == null) return null
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

        // 等待期间不能让这一层吞掉点击。这个 Activity 全透明，用户看到的是底下那个
        // 解析页，会理所当然地去点它 —— 而窗口在这一层，点击其实全被这里吃掉了，
        // 于是「点哪儿都没反应」，看着就像卡死（实测就是这么被报上来的）。
        // 标成不可触摸，事件就穿透到底下的解析页，等待期间用户仍能正常操作。
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)

        // 超时兜底。上限取 12 秒：走这条链路本来就是「主链路没拿到数据」，
        // 实测正常几秒内就该有结果；剩下能判的（404 / 空详情）都已提前给结论，
        // 再久基本等于这条作品拿不到，不如早点如实说明。
        //
        // 必须挂在主线程 Handler 上，**不能**写成 view.postDelayed：这个 WebView
        // 从来没被挂进视图树（加载页面不需要窗口），而未 attached 的 View 会把
        // post 进去的动作塞进 RunQueue，等它 attach 才执行 —— 永远等不到。
        // 实测就是这么卡住的：解析页一直停在自己的转圈状态，既不报错也不结束。
        Handler(Looper.getMainLooper()).postDelayed({
            if (!delivered) deliver(null, timeoutReason())
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

    /** 从页面地址里取作品编号（形如 `…/video/7691571086429128811`）。 */
    private fun videoIdFrom(url: String): String? =
        Regex("/(?:video|note)/(\\d+)").find(url)?.groupValues?.getOrNull(1)

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
     * 拿不到响应内容。而这条请求的 URL 里已经带了 WebView 算好的签名参数
     * （a_bogus / x-secsdk-web-signature），照原样重放一次即可拿到数据。
     *
     * 头必须照搬原请求（[original]）：抖音的网关会校验它们 —— 光是补上
     * Cookie 和 Referer 不够。实测漏掉原请求里的 `uifid` 头时，接口回的是
     * **HTTP 200 但响应体为空**，看着像请求成功了，实际什么都没拿到
     * （纯 HTTP 直连时那句报错就是「Blocked by ArgusSecurityPlugin Uifid Not Found」）。
     */
    private fun fetchBody(url: String, original: Map<String, String>?): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            // Cookie 由 WebView 自己管，原请求的头里没有它（会被 WebView 剥掉）
            original?.forEach { (k, v) ->
                if (!k.equals("Cookie", true)) setRequestProperty(k, v)
            }
            // 原请求没带的才补默认值：UA 有可能被页面自己改过，Referer 同理
            if (original?.keys?.none { it.equals("User-Agent", true) } != false) {
                setRequestProperty("User-Agent", DESKTOP_UA)
            }
            if (original?.keys?.none { it.equals("Referer", true) } != false) {
                setRequestProperty("Referer", "https://www.douyin.com/")
            }
            // 登录态在 WebView 的 Cookie 里，必须带上，否则接口会返回未登录
            CookieManager.getInstance().getCookie(url)?.let {
                setRequestProperty("Cookie", it)
            }
        }
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        text
    }.getOrNull()

    /** 从详情接口地址的查询串里取 `aweme_id`（页面还没跳转完时它比 expectedId 可靠）。 */
    private fun awemeIdFrom(url: String): String =
        Regex("aweme_id=(\\d+)").find(url)?.groupValues?.getOrNull(1).orEmpty()

    /**
     * 超时的确切原因。
     *
     * 三条判据都来自本项目的真实观测，不是穷举可能性：
     *   ① 页面根本没加载完 → 网络/加载失败。此时连页面都打不开，谈不上平台给不给数据。
     *   ② 页面加载完成，且**页面自己**请求详情接口拿到空响应体 → 平台不开放这条作品的数据。
     *      被限流的作品正是这样：页面能打开、App 里能看，但接口对任何人回空。
     *   ③ 页面加载完成，但详情接口一次都没被请求 → 页面结构变了（罕见），需要更新解析。
     */
    private fun timeoutReason(): String = when {
        !pageLoaded ->
            "页面没能加载完成，请检查网络后重试。"
        emptyDetail ->
            "抖音没有开放这条作品的公开数据（这类作品通常处于限流状态），只能在抖音 App 内观看。"
        else ->
            "页面已加载，但没发出可用的作品数据请求 —— 可能是抖音改版，欢迎用「意见反馈」告诉我们。"
    }

    /**
     * 页面内 fetch / XHR 的响应回调（JS 侧见 [HOOK_JS]）。
     *
     * 这是抖音兜底链路的**优先路径**：详情请求由页面自己带着签名和登录态发出，
     * 我们只是旁听它拿回来的响应体 —— 不新增任何凭据、不需要我们另外登录，
     * 也就没有「原生 HTTP 重放被风控回空」的问题。
     *
     * 作品编号做双保险：优先用页面地址里的 [expectedId]；地址还没跳转完时退回
     * 从请求 URL 的 `aweme_id=` 取。两者都拿不到就丢弃 —— 宁可继续等，
     * 也不把推荐位里别人的作品当结果交出去（那会造成「伪成功」）。
     */
    inner class Bridge {
        /**
         * 诊断用：页面每发一个 XHR/fetch 就报一次 URL（不带响应体）。
         *
         * 加它是为了回答「钩子到底有没有装上、页面的详情请求走不走 XHR」——
         * 只靠「没收到详情响应」分不清是钩子没生效还是页面根本没发这个请求。
         */
        @JavascriptInterface
        fun onHookSeen(url: String) {
            // 只记抖音的业务接口：页面的埋点、扩展图标、静态资源占绝大多数，
            // 全记下来会把日志冲满，反而看不到真正想看的请求。
            if (!url.contains("/aweme/")) return
            Log.i(TAG, "hook seen: ${url.take(110)}")
        }

        @JavascriptInterface
        fun onHookResponse(url: String, body: String) {
            if (delivered) return
            if (!url.contains(DouyinParser.DETAIL_API)) return
            Log.i(TAG, "hook detail: len=${body.length} url=${url.take(120)}")
            // 空响应体是抖音对可疑环境的既有行为（历史实测），直接放弃这一份
            if (body.length < 200) {
                emptyDetail = true
                emptyDetailCount += 1
                // 页面已经渲染完，接口又连续回空 —— 结论已经明确，没有继续等的理由。
                // 取 2 次是为了躲掉「第一次请求赶在 Cookie/环境就绪之前」的偶发情况。
                if (pageLoaded && emptyDetailCount >= 2) {
                    Log.i(TAG, "detail empty x$emptyDetailCount after load, abort early")
                    deliver(null, timeoutReason())
                }
                return
            }
            val id = expectedId.ifBlank { awemeIdFrom(url) }
            // 两个来源都拿不到作品编号就丢弃：宁可等超时，也不盲收别人的作品
            if (id.isBlank()) {
                Log.i(TAG, "hook detail: no aweme id, dropped")
                return
            }
            val r = runCatching { DouyinParser.parseDetail(body, shareUrl, id) }
                .getOrNull() ?: return
            when (r) {
                is DouyinDetail.Video -> deliver(r.payload.toString())
                is DouyinDetail.Unavailable -> deliver(null, r.message)
                DouyinDetail.None -> Unit
            }
        }
    }

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

        private const val TIMEOUT_MS = 12_000L

        private const val TAG = "ytdy"

        /** JS 桥的全局对象名，与 [HOOK_JS] 里 `window.__ytBridge` 对应。 */
        private const val BRIDGE_NAME = "__ytBridge"

        /**
         * 钩住页面自己的 `fetch` 与 `XMLHttpRequest`，把**详情接口**的响应体回传原生。
         *
         * 为什么只挑详情接口：页面还会拉推荐流、静态资源，那些响应又大又没用；
         * 按「URL 含 `aweme/detail` 或响应体里有 `aweme_detail`」过滤，噪音趋近于零。
         *
         * 为什么用 `clone()`：fetch 的响应体是一次性流，直接 `.text()` 会把它吃掉，
         * 页面自己就读不到了（页面会白屏）。复制一份读，页面行为完全不受影响。
         *
         * 全部包在 try 里：钩子本身绝不能让页面报错。
         * `window.__ytHook` 做幂等 —— onPageStarted 与 onPageFinished 都会注入。
         */
        private const val HOOK_JS = """
(function(){
  try{
    if (window.__ytHook) return;
    window.__ytHook = 1;
    function seen(u){
      try{
        if (!u) return;
        window.__ytBridge.onHookSeen(String(u));
      }catch(e){}
    }
    function emit(u, t){
      try{
        var s = (typeof t === 'string') ? t : '';
        var uu = String(u || '');
        // 空响应也要往上报：否则「钩子没拿到响应体」和「钩子根本没被调用」
        // 在日志里长得一模一样，没法定位。
        if (uu.indexOf('aweme/detail') < 0 && s.indexOf('aweme_detail') < 0) return;
        window.__ytBridge.onHookResponse(uu, s);
      }catch(e){}
    }
    try{
      var open = XMLHttpRequest.prototype.open;
      var send = XMLHttpRequest.prototype.send;
      XMLHttpRequest.prototype.open = function(m, u){
        try{ this.__ytUrl = u; seen(u); }catch(e){}
        return open.apply(this, arguments);
      };
      XMLHttpRequest.prototype.send = function(){
        var x = this;
        try{
          x.addEventListener('load', function(){
            try{
              var rt = x.responseType;
              if (rt === '' || rt === 'text') emit(x.__ytUrl, x.responseText);
              else if (rt === 'json' && x.response) emit(x.__ytUrl, JSON.stringify(x.response));
            }catch(e){}
          });
        }catch(e){}
        return send.apply(this, arguments);
      };
    }catch(e){}
    try{
      var f = window.fetch;
      if (f) {
        window.fetch = function(input, init){
          var u = (typeof input === 'string') ? input : ((input && input.url) || '');
          seen(u);
          return f.apply(this, arguments).then(function(r){
            try{
              r.clone().text().then(function(t){ emit(u, t); }).catch(function(){});
            }catch(e){}
            return r;
          });
        };
      }
    }catch(e){}
  }catch(e){}
})();
"""

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
