package com.ytdlp.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.ytdlp.android.engine.XhsDetail
import com.ytdlp.android.engine.XhsParser
import java.io.ByteArrayInputStream

/**
 * 小红书的浏览器模式解析页。
 *
 * 为什么必须有这一层：小红书**必须登录态**，而且实测「用户从浏览器导出 Cookie →
 * 应用拿去发裸 HTTP 请求」会被平台判成自动化（先验证码、后送登录页，最严重时把
 * 用户自己的账号登出）。浏览器这条路不一样 —— 走的是真实 Chromium 环境，平台
 * 看到的就是正常用户。所以：
 *
 *   · 正常情况（已登录）：窗口保持**全透明 + 不可触摸**，用户只看到底下解析页转圈；
 *   · 需要登录时：**窗口变成可见、可交互**，让用户就地登录（登录后页面自己会跳回
 *     笔记页，数据到手就自动继续）。登录一次之后长期有效 —— 用户不必再导出/导入
 *     cookies.txt。
 *
 * 数据怎么取：小红书笔记页是**服务端渲染**的，作品数据就在页面里的
 * `window.__INITIAL_STATE__.note.noteDetailMap`。这里不拦主文档、也不解析 HTML，
 * 而是页面加载完后用 `evaluateJavascript` 把那一小段对象**取出来**（见 [EXTRACT_JS]）
 * —— 拿到的是结构化 JSON，比在 Kotlin 里抠 HTML 稳得多。
 *
 * 与抖音那条的关系：骨架同构（透明窗口 / 静音 / 主线程 Handler 超时 / 销毁 WebView），
 * 两处关键差别：
 *   ① **WebView 不挂进视图树**（与抖音一致）。抖音那边实测过：不挂载同样能加载页面；
 *      而挂进去的话，页面一渲染就在屏幕上画出几帧 —— 用户反馈「解析时会闪一下
 *      浏览器界面」，就是这么来的。只有需要用户登录时才挂上去。
 *   ② 需要登录时窗口要**露出来**让用户操作，抖音没有这个形态。
 */
class XhsActivity : Activity() {

    @Volatile
    private var delivered = false

    /** 目标笔记编号（[noteIdFrom]）。锁定它才不会把推荐位的别的笔记当成结果。 */
    @Volatile
    private var expectedId = ""

    private var web: WebView? = null
    private var hintBar: LinearLayout? = null
    private var shareUrl = ""

    /** 是否已经切到「让用户登录」的可见模式。 */
    @Volatile
    private var loginMode = false

    /** 已重试取数的次数：页面加载完到数据就绪之间可能有个空档。 */
    private var extractAttempts = 0

    /** 登录模式下，为了把用户带回笔记页，最多重载几次分享链接。 */
    private var reloads = 0

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
            // **桌面版** UA。
            //
            // 试过移动版 UA（想让它天然适配手机）：页面内容确实能正常显示、也确实
            // 不再横向溢出，但**数据读不到** —— 移动版页面的 `window.__INITIAL_STATE__`
            // 里没有 `note.noteDetailMap`（实测：截图能看到笔记标题/正文，我们的取数
            // 却只能拿到空），所以取数必须用桌面版页面。
            // 登录页因此仍是桌面布局：靠 overview 模式缩放到屏宽 + 双指缩放兜底，
            // 我们自己的提示条也已按状态栏留好内边距。
            settings.userAgentString = DESKTOP_UA
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            // 仍保留双指缩放：万一某个页面偏宽，用户还能自己拉一下
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            // 缩放按钮会挡住页面，交给手势即可
            settings.displayZoomControls = false
            // 别自动播放：这个窗口正常情况下是隐形+不可触摸的，响了用户也关不掉
            settings.mediaPlaybackRequiresUserGesture = true
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) {
                    super.onPageStarted(v, url, favicon)
                    noteIdFrom(url)?.let { expectedId = it }
                    v.evaluateJavascript(SILENCE_JS, null)
                }

                override fun onPageFinished(v: WebView, url: String) {
                    super.onPageFinished(v, url)
                    v.evaluateJavascript(SILENCE_JS, null)
                    extractAttempts = 0
                    // 落到登录页就把窗口露出来，让用户就地登录
                    if (url.contains("/login") || url.contains("website-login")) {
                        enterLoginMode()
                    }
                    v.postDelayed({ extract() }, 300)
                }

                /**
                 * 拦掉「拉起 App」这类跳转。
                 *
                 * 实测：移动版笔记页会自己尝试跳 `xhsdiscover://…` / `xiaohongshu://…`
                 * 去打开小红书 App。WebView 处理不了这种 scheme，会**直接把整页换成
                 * 一屏 `net::ERR_UNKNOWN_URL_SCHEME`** —— 页面里的数据也就没了。
                 * 我们只需要页面里的数据，不需要拉起 App，所以非 http(s) 一律拦下。
                 */
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val scheme = request.url.scheme?.lowercase()
                    return scheme != "http" && scheme != "https"
                }

                override fun shouldInterceptRequest(
                    v: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    // 音/视频媒体一律不加载：这层窗口大多数时候用户看不到，
                    // 自动播放既没必要又会在后台出声。图片（图文/图集的内容）放行。
                    if (looksLikeMedia(request.url.toString())) {
                        return WebResourceResponse(
                            "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
                        )
                    }
                    return null
                }
            }
            loadUrl(shareUrl)
        }
        web = view

        // 我们自己的提示条只在**需要登录**时才出现。
        // 顶部要留出状态栏的高度：这个 Activity 是透明主题（会画到状态栏下方），
        // 不留的话第一行字会被状态栏盖住（实测就是这个表现）。
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(0xF2FFFFFF.toInt())
            val top = statusBarHeightPx() + 20
            setPadding(28, top, 28, 20)
            visibility = View.GONE
            addView(TextView(this@XhsActivity).apply {
                text = "请先登录小红书"
                textSize = 16f
                gravity = Gravity.CENTER
            })
            addView(TextView(this@XhsActivity).apply {
                text = "登录完成后会自动继续解析这条笔记，之后不必再登录"
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, 8, 0, 4)
            })
            // 代价必须当场说清：小红书限制同时登录的设备数，在这里登录会把用户
            // 电脑网页端的会话挤下线（实测：PC 端提示「电脑设备登录超限」）。
            addView(TextView(this@XhsActivity).apply {
                text = "注意：小红书限制同时登录的设备数，在应用里登录后，" +
                    "你电脑上的网页版会被挤下线。"
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 8)
            })
            addView(Button(this@XhsActivity).apply {
                text = "取消"
                setOnClickListener { deliver(null, "已取消：小红书需要先登录才能解析。") }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
        hintBar = bar

        // 必须挂进视图树，但先做成**全透明**（alpha=0）。
        //
        // 为什么不能像抖音那样「不挂载」：抖音靠拦请求（shouldInterceptRequest）取数，
        // 不依赖页面里的 JS；而小红书这条路是**用 evaluateJavascript 去读页面变量**，
        // 实测（A/B 对比）WebView 未 attach 时该回调根本不执行 —— 表现就是一直取不到
        // 数据、最后超时。挂载之后数据立刻就能读到。
        //
        // 为什么用 alpha=0 而不是不挂：页面一渲染就会画出几帧（用户反馈「解析时会闪
        // 一下浏览器界面」）。alpha=0 时视图照常渲染（Chromium 认为它可见，JS 正常跑），
        // 但一个像素都不会画到屏幕上 —— 既闪不了，又不影响取数。
        val root = FrameLayout(this)
        root.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            bar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        setContentView(root)
        view.alpha = 0f

        // 默认不打扰用户：窗口透明 + 点击穿透到底下的解析页（否则「点哪儿都没反应」
        // 会被当成卡死 —— 抖音那边实测踩过）。
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)

        // 超时兜底：必须挂主线程 Handler，不能写成 view.postDelayed ——
        // 抖音那边实测过：未 attached 的 View 会把动作塞进 RunQueue 等 attach，
        // 于是永不执行，界面就一直卡在转圈。
        Handler(Looper.getMainLooper()).postDelayed({ timeoutTick() }, TIMEOUT_MS)
    }

    /** 透明主题下 Activity 会画到状态栏下方，取一次状态栏高度用于内边距。 */
    private fun statusBarHeightPx(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    /** 超时到了但用户正在登录 → 再给一段时间，别把人家的登录过程掐断。 */
    private fun timeoutTick() {
        if (delivered) return
        if (loginMode) {
            Handler(Looper.getMainLooper()).postDelayed({
                if (!delivered) {
                    deliver(
                        null,
                        "还没等到登录完成。登录成功后请留在小红书页面几秒，" +
                            "让它把这条笔记打开；实在不行重试一次。",
                    )
                }
            }, LOGIN_EXTRA_MS)
            return
        }
        deliver(
            null,
            "${TIMEOUT_MS / 1000} 秒内没能取到这条笔记的数据。常见原因：\n" +
                "① 笔记已被删除，或被作者设为「仅自己可见」—— 这种情况谁都解析不了；\n" +
                "② 网络较慢，页面这次没正常加载 —— 重试一次通常就好。",
        )
    }

    /**
     * 把页面里的笔记数据取出来。
     *
     * 取不到就隔一会儿再试几次：SSR 数据理论上随主文档就到了，但页面可能还在
     * 走脚本 hydration。重试有上限，避免无限试探。
     */
    private fun extract() {
        val w = web ?: return
        if (delivered) return
        val js = EXTRACT_JS.replace("__EXPECTED_ID__", expectedId)
        w.evaluateJavascript(js) { result ->
            if (delivered) return@evaluateJavascript
            val pulled = decodeJsString(result)
            if (pulled.isNotBlank()) {
                when (val detail = XhsParser.buildPayload(pulled, shareUrl, expectedId)) {
                    is XhsDetail.Note -> {
                        deliver(detail.payload.toString())
                        return@evaluateJavascript
                    }
                    is XhsDetail.Unavailable -> {
                        deliver(null, detail.message)
                        return@evaluateJavascript
                    }
                    XhsDetail.None -> Unit
                }
            }
            // 数据还没到：如果是「没登录」就露窗口让用户登录，否则稍后重试
            if (!loggedIn(result)) enterLoginMode()
            if (extractAttempts < MAX_EXTRACT_ATTEMPTS) {
                extractAttempts++
                w.postDelayed({ extract() }, 800L * extractAttempts)
            } else if (loginMode && reloads < MAX_RELOADS) {
                // 登录完了但页面停在首页/其它页：把分享链接再打开一次，
                // 否则用户登录后一直等不到结果（实测反馈过这个担心）。
                reloads++
                extractAttempts = 0
                w.postDelayed({
                    if (!delivered) w.loadUrl(shareUrl)
                }, 1500L)
            }
        }
    }

    /** 未登录时把窗口露出来，用户在其中登录一次即可（之后长期有效）。 */
    private fun enterLoginMode() {
        if (loginMode || delivered) return
        loginMode = true
        runOnUiThread {
            // 让用户看得见、点得到：把全透明的 WebView 显形，并露出提示条
            web?.alpha = 1f
            hintBar?.visibility = View.VISIBLE
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        }
    }

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

    /** 从页面地址里取笔记编号（形如 `…/discovery/item/6acc7d9b00000000180154ec`）。 */
    private fun noteIdFrom(url: String): String? =
        Regex("/(?:explore|discovery/item|item)/([0-9a-fA-F]{16,})")
            .find(url)?.groupValues?.getOrNull(1)

    /**
     * `evaluateJavascript` 的返回值是**被 JSON 编码过**的字符串（外层带引号、
     * 内部转义），先解一层再当 JSON 用。
     */
    private fun decodeJsString(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text == "null") return ""
        return runCatching {
            org.json.JSONTokener(text).nextValue() as? String ?: ""
        }.getOrDefault("")
    }

    /** 从同一份返回值里读 loggedIn（拿不到就按「未知」当成已登录，继续重试）。 */
    private fun loggedIn(raw: String?): Boolean {
        val pulled = decodeJsString(raw)
        if (pulled.isBlank()) return true
        return runCatching { org.json.JSONObject(pulled).optBoolean("loggedIn", true) }
            .getOrDefault(true)
    }

    /** 判断一个请求是不是音/视频媒体（小红书的视频也走 xhscdn，且不带扩展名的情况少）。 */
    private fun looksLikeMedia(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        if (path.endsWith(".mp4") || path.endsWith(".m4s") || path.endsWith(".m4a") ||
            path.endsWith(".mp3") || path.endsWith(".aac") || path.endsWith(".webm") ||
            path.endsWith(".mov")
        ) {
            return true
        }
        return path.contains("/video/tos/") || path.contains("sns-video")
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

        /** 正常链路（已登录）给 20 秒；进入登录模式后再宽限 3 分钟。 */
        private const val TIMEOUT_MS = 20_000L
        private const val LOGIN_EXTRA_MS = 180_000L
        private const val MAX_EXTRACT_ATTEMPTS = 6
        private const val MAX_RELOADS = 3

        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        /** 静音补丁：与抖音那边同一份（改写 play、钉住音量、拦掉 Web Audio）。 */
        private const val SILENCE_JS = """
            (function(){
              try{
                if(window.__xhsSilenced) return; window.__xhsSilenced=1;
                var P=HTMLMediaElement.prototype;
                P.play=function(){ try{this.muted=true;this.volume=0;this.pause();}catch(e){} };
                if(window.AudioContext){ AudioContext.prototype.resume=function(){ return Promise.resolve(); }; }
              }catch(e){}
            })()
        """

        /**
         * 取笔记数据。**必须在 JS 里按 `__EXPECTED_ID__` 锁定**目标笔记 ——
         * 页面加载时会顺带请求推荐位，只取「唯一那一条」这种做法在抖音那边
         * 已经出过事（把别人的作品当成果交付）。
         *
         * 同时回传 `loggedIn`，让 Kotlin 侧判断「是没登录，还是数据还没到」。
         */
        private const val EXTRACT_JS = """
            (function(){
              try{
                var s = window.__INITIAL_STATE__;
                var loggedIn = !!(s && s.user && s.user.loggedIn);
                var id = '__EXPECTED_ID__';
                var e = (s && s.note && s.note.noteDetailMap) ? s.note.noteDetailMap[id] : null;
                var n = e ? e.note : null;
                if(!n) return JSON.stringify({note: null, loggedIn: loggedIn});
                function pickImage(i){
                  return { urlDefault:i.urlDefault, urlPre:i.urlPre, url:i.url,
                           width:i.width, height:i.height, infoList:i.infoList,
                           video:i.video, stream:i.stream, livePhoto:i.livePhoto };
                }
                return JSON.stringify({ loggedIn: loggedIn, note: {
                  noteId:n.noteId, type:n.type, title:n.title, desc:n.desc,
                  user:{ nickname:(n.user||{}).nickname, userId:(n.user||{}).userId,
                         redId:(n.user||{}).redId },
                  imageList:(n.imageList||[]).map(pickImage),
                  video:n.video
                }});
              }catch(e){ return ''; }
            })()
        """

        fun intent(context: Context, url: String): Intent =
            Intent(context, XhsActivity::class.java).putExtra(EXTRA_URL, url)
    }
}
