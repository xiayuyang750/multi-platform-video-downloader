package com.ytdlp.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.ytdlp.android.engine.WeiboDetail
import com.ytdlp.android.engine.WeiboParser
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * 微博正文的浏览器模式解析页（图文 / 图集 / 实况图 / 动图）。
 *
 * 为什么需要它：yt-dlp 的微博提取器只产出视频，其余形态一律报
 * `No video formats found!`（用户实测 12 条里 9 条失败）。而微博移动版详情页
 * 会把这条微博的数据直接写在页面里（`window.$render_data.status`），用 WebView
 * 读出来即可 —— 关键是**真实浏览器会自动过掉微博的「访客系统」**，而纯 HTTP
 * 会被 `Sina Visitor System` 拦下（已实测：拿到 SUB/SUBP 后接口仍返回验证页）。
 *
 * 与小红书那条的区别：微博不需要登录，所以这个窗口**永远不露给用户**
 * （`alpha=0`），也就没有登录提示条。
 */
class WeiboActivity : Activity() {

    @Volatile
    private var delivered = false

    private var web: WebView? = null
    private var shareUrl = ""
    private var attempts = 0
    private var dumped = false

    /** 文章页的图片是懒加载的：先滚到底把图催出来，再取数。 */
    private var articleScrolls = 0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        shareUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        val article = isArticle(shareUrl)
        val mid = if (article) articleId(shareUrl) else midFrom(shareUrl)
        if (mid.isBlank()) {
            finish()
            return
        }

        val view = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = MOBILE_UA
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.mediaPlaybackRequiresUserGesture = true
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) {
                    super.onPageStarted(v, url, favicon)
                    v.evaluateJavascript(SILENCE_JS, null)
                }

                override fun onPageFinished(v: WebView, url: String) {
                    super.onPageFinished(v, url)
                    v.evaluateJavascript(SILENCE_JS, null)
                    attempts = 0
                    v.postDelayed({ extract() }, 400)
                }

                // 微博移动页同样会尝试拉起 App；WebView 处理不了这种 scheme，
                // 会把整页换成一屏 net::ERR_UNKNOWN_URL_SCHEME（小红书那边实测过）
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
                    // 音视频媒体不加载：这层窗口用户看不到，没必要耗流量
                    if (looksLikeMedia(request.url.toString())) {
                        return WebResourceResponse(
                            "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
                        )
                    }
                    return null
                }
            }
            // 头条文章走**文章页**，不是微博正文页：
            //   `weibo.com/ttarticle/x/m/show#/id=...` 的那个 `x/m/show` 只是个
            //   前端壳，文章 id 在 `#` 后面（服务端收不到），真实内容在
            //   `card.weibo.com/article/m/show/id/<id>`（移动版文章页，SSR）。
            loadUrl(
                if (article) "https://card.weibo.com/article/m/show/id/$mid"
                else "https://m.weibo.cn/detail/$mid"
            )
        }
        web = view

        val root = android.widget.FrameLayout(this)
        root.addView(view)
        setContentView(root)
        view.alpha = 0f
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)

        // 必须用主线程 Handler（未 attached 的 View.postDelayed 在抖音那边踩过坑）
        Handler(Looper.getMainLooper()).postDelayed({ timeoutTick() }, TIMEOUT_MS)
    }

    private fun timeoutTick() {
        if (delivered) return
        deliver(
            null,
            "${TIMEOUT_MS / 1000} 秒内没能取到这条微博的数据。常见原因：\n" +
                "① 这条微博已删除，或设为仅自己可见；\n" +
                "② 微博的访客验证这次没走完 —— 重试一次通常就好。",
        )
    }

    private fun extract() {
        val w = web ?: return
        if (delivered) return
        val article = isArticle(shareUrl)
        // 文章页的图是**懒加载**的：实测直接取只能拿到 0 张（截图里明明有图）。
        // 所以先分两次滚到底，触发图片加载，再取。
        if (article && articleScrolls < 2) {
            articleScrolls++
            w.evaluateJavascript(SCROLL_JS, null)
            w.postDelayed({ extract() }, 1200)
            return
        }
        val js = if (article) ARTICLE_JS else EXTRACT_JS
        w.evaluateJavascript(js) { result ->
            if (delivered) return@evaluateJavascript
            val pulled = decodeJsString(result)
            if (pulled.isNotBlank() && !dumped) {
                // 临时诊断：确认微博图片/实况图的真实字段名，定位完即删
                dumped = true
                Log.i("weibodiag", pulled.take(1500))
            }
            if (pulled.isNotBlank()) {
                when (val d = WeiboParser.buildPayload(pulled, shareUrl)) {
                    is WeiboDetail.Note -> {
                        deliver(d.payload.toString())
                        return@evaluateJavascript
                    }
                    is WeiboDetail.Redirect -> {
                        // 正文里挂的是视频链接：把目标 URL 回传给 ViewModel，
                        // 由它用这条 URL 再跑一次解析（视频页走 yt-dlp）。
                        deliver(JSONObject().put(WeiboParser.PAYLOAD_REDIRECT, d.url).toString())
                        return@evaluateJavascript
                    }
                    is WeiboDetail.Unavailable -> {
                        deliver(null, d.message)
                        return@evaluateJavascript
                    }
                    WeiboDetail.None -> Unit
                }
            }
            if (attempts < MAX_ATTEMPTS) {
                attempts++
                // 固定 700ms 一轮。原来按 700ms×n 递增，8 轮理论要 25.2 秒 ——
                // 比 15 秒的超时还长，等于「重试用尽」那条分支永远走不到，
                // 用户每次都在等超时。
                w.postDelayed({ extract() }, 700L)
            } else {
                // 取数重试用完了：文章页和正文页的结论不一样，分开说清楚
                deliver(
                    null,
                    if (article) "这篇文章里没有图片，没有可下载的内容。"
                    else "没能从这条微博里取到图片或视频。可能已被删除、设为私密，" +
                        "或这次访客验证没走完 —— 重试一次通常就好。",
                )
            }
        }
    }

    private fun decodeJsString(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text == "null") return ""
        return runCatching {
            org.json.JSONTokener(text).nextValue() as? String ?: ""
        }.getOrDefault("")
    }

    private fun looksLikeMedia(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return path.endsWith(".mp4") || path.endsWith(".m4s") || path.endsWith(".m4a") ||
            path.endsWith(".mp3") || path.endsWith(".aac") ||
            path.contains("/video/") || path.contains("f.video.weibocdn.com")
    }

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

    /** 从链接里取微博 mid：`/status/<id>`、`/detail/<id>`、`weibo.com/<uid>/<mid>`。 */
    private fun midFrom(url: String): String =
        Regex("/(?:status|detail)/([0-9A-Za-z]+)").find(url)?.groupValues?.getOrNull(1).orEmpty()
            .ifBlank {
                Regex("weibo\\.com/[^/]+/([0-9A-Za-z]+)").find(url)?.groupValues?.getOrNull(1).orEmpty()
            }

    /** 是不是头条文章（`ttarticle` 或移动版文章页）。 */
    private fun isArticle(url: String): Boolean =
        url.contains("ttarticle") || url.contains("card.weibo.com/article")

    /**
     * 取文章 id。
     *
     * 两种形态都要认：
     *   · `weibo.com/ttarticle/x/m/show#/id=2309404932023138517320&_wb_client_=1`
     *     —— id 在 `#` 后面（所以只能用正则从整串里捞，不能靠服务端路由）
     *   · `card.weibo.com/article/m/show/id/2309404932023138517320`
     */
    private fun articleId(url: String): String =
        Regex("id[=/](\\d{10,})").find(url)?.groupValues?.getOrNull(1).orEmpty()
            .ifBlank {
                Regex("/(\\d{10,})").find(url)?.groupValues?.getOrNull(1).orEmpty()
            }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_PAYLOAD = "payload"
        const val EXTRA_REASON = "reason"

        private const val TIMEOUT_MS = 15_000L
        private const val MAX_ATTEMPTS = 8

        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        private const val SILENCE_JS = """
            (function(){
              try{
                if(window.__wbSilenced) return; window.__wbSilenced=1;
                var P=HTMLMediaElement.prototype;
                P.play=function(){ try{this.muted=true;this.volume=0;this.pause();}catch(e){} };
              }catch(e){}
            })()
        """

        /**
         * 取微博正文数据。
         *
         * 微博移动版把这条微博挂在 `window.$render_data.status` 上（服务端渲染）。
         * 图片可能是正文的，也可能是被转发微博的 —— 两种都带回去。
         *
         * `_picKeys` / `_pageKeys` 是临时诊断用的字段名清单：实况图字段叫什么
         * 各家说法不一，先如实抓一次再定（定位完从返回值里删掉即可）。
         */
        private const val EXTRACT_JS = """
            (function(){
              try{
                var d = window.${'$'}render_data;
                var s = d && d.status;
                if(!s) return JSON.stringify({status: null});
                function pic(p){
                  var large = p.large || {};
                  var geo = large.geo || {};
                  // 实况图的动效在 `videoSrc` 里（真机抓到的字段名：
                  // pid|url|size|geo|large|videoSrc|type）—— 微博**没有**
                  // live_photo 这个字段，按小红书那套字段名找会永远为空。
                  var v = p.videoSrc;
                  var live = '';
                  if (typeof v === 'string') live = v;
                  else if (v && typeof v === 'object') live = v.url || v.mp4 || '';
                  return {
                    url: large.url || p.url || '',
                    // 微博把宽高给成字符串（"1079"），这里统一转成数字
                    width: Number(geo.width || p.width || 0) || 0,
                    height: Number(geo.height || p.height || 0) || 0,
                    live: live,
                    type: p.type || '',
                    pid: p.pid || ''
                  };
                }
                function pics(arr){ return (arr || []).map(pic); }
                var first = (s.pics || [])[0] || null;
                return JSON.stringify({
                  status: {
                    id: String(s.id || ''),
                    text: s.text || '',
                    pics: pics(s.pics),
                    page_info: s.page_info || {},
                    user: {
                      name: (s.user || {}).screen_name || '',
                      id: (s.user || {}).id || ''
                    },
                    retweeted: s.retweeted_status ? {
                      pics: pics(s.retweeted_status.pics),
                      page_info: s.retweeted_status.page_info || {}
                    } : null
                  },
                  _picKeys: first ? Object.keys(first).join('|') : '',
                  _liveSample: first ? String(first.videoSrc || '').slice(0, 120) : '',
                  _pageKeys: s.page_info ? Object.keys(s.page_info).join('|') : ''
                });
              }catch(e){ return ''; }
            })()
        """

        /**
         * 渐进滚屏：文章页的图片要进入视口才会被加载（懒加载）。
         *
         * 不能只 `scrollTo(bottom)` 一次 —— 那样中间的图可能来不及触发。这里每
         * 120ms 往下走两屏，直到到底；调用方隔 1.2s 再来看一眼，通常就齐了。
         */
        private const val SCROLL_JS = """
            (function(){
              try{
                var y = 0;
                var t = setInterval(function(){
                  y += window.innerHeight * 2;
                  window.scrollTo(0, y);
                  if (y > document.body.scrollHeight) clearInterval(t);
                }, 120);
              }catch(e){}
            })()
        """

        /**
         * 头条文章的取数：文章页是普通 DOM，直接收正文标题 + 正文里的图。
         *
         * 为什么要过滤：页面里还混着头像、表情、图标等小图（`face.t.sinaimg.cn`
         * / `/thumb` / `/avatar`），这些不该进「下载全部」。
         */
        private const val ARTICLE_JS = """
            (function(){
              try{
                var title = '';
                // 文章标题不在 <h1> 上（实测取到的是页面名「头条文章」），
                // 按类名逐个试；都没有才退回 document.title
                var sels = ['.article-title', '.WBAttr', '.title', 'h1', 'h2'];
                for (var k = 0; k < sels.length; k++) {
                  var el = document.querySelector(sels[k]);
                  if (el && (el.innerText || '').trim()) { title = el.innerText.trim(); break; }
                }
                if (!title) title = document.title || '';
                // 正文：优先按内容容器取（不同版式的类名都试一遍），
                // 都取不到就退回「把所有段落拼起来」—— 文章正文一定是段落结构。
                var body = '';
                var boxSels = ['#article_content', '.WB_article_content',
                               '.article-content', '.article_content',
                               '.WB_article_text', 'article'];
                for (var b = 0; b < boxSels.length; b++) {
                  var box = document.querySelector(boxSels[b]);
                  var bt = box ? (box.innerText || '').trim() : '';
                  if (bt.length > 20) { body = bt; break; }
                }
                if (!body) {
                  var ps = document.querySelectorAll('p'), parts = [];
                  for (var j = 0; j < ps.length; j++) {
                    var pt = (ps[j].innerText || '').trim();
                    if (pt) parts.push(pt);
                  }
                  body = parts.join('\n');
                }
                var seen = {}, out = [];
                var imgs = document.querySelectorAll('img');
                for (var i = 0; i < imgs.length; i++) {
                  var s = imgs[i].getAttribute('src') ||
                          imgs[i].getAttribute('data-src') || '';
                  if (!s || s.indexOf('sinaimg.cn') < 0) continue;
                  if (s.indexOf('face.t.sinaimg.cn') >= 0) continue;
                  if (s.indexOf('/thumb') >= 0 || s.indexOf('/avatar') >= 0) continue;
                  if (seen[s]) continue;
                  seen[s] = 1;
                  out.push(s);
                }
                return JSON.stringify({ article: { title: title, content: body, images: out } });
              }catch(e){ return ''; }
            })()
        """

        fun intent(context: Context, url: String): Intent =
            Intent(context, WeiboActivity::class.java).putExtra(EXTRA_URL, url)
    }
}
