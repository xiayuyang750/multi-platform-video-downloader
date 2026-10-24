package com.ytdlp.android.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * 详情接口响应解析出来的三种结果。
 *
 * 分成三态、而不是像以前那样「拿不到就返回 null」，是因为「服务端明确说这条
 * 作品看不了」和「这份响应里根本没有我们要的东西」对用户的意义完全不同：
 * 前者是定论（该立刻如实告知），后者只是还没拦到（该继续等）。
 */
sealed class DouyinDetail {
    /** 拿到作品数据，payload 直接交给引擎的 save_douyin 入库。 */
    data class Video(val payload: JSONObject) : DouyinDetail()

    /** 服务端明确说这条作品看不了（仅自己可见 / 已删除 / 审核中）。 */
    data class Unavailable(val message: String) : DouyinDetail()

    /** 这份响应里没有目标作品的数据。 */
    object None : DouyinDetail()
}

/**
 * 抖音作品数据的解析。
 *
 * 这里只做「从一段 JSON 里挑出要的字段」这一件事，不碰网络、不碰 WebView，
 * 因此可以脱离设备单独验证。字段规范与 windows/douyin_fallback.py 一一对应 ——
 * 两端面对的是同一个接口、同一套字段，改一处要同步另一处。
 *
 * 档位（分辨率 / 编码 / 体积）的**挑选规则不在这里**：详情接口拿到的 video
 * 对象原样交给引擎，由 Python 侧的 douyin_pick_tiers / douyin_collect_tiers 去挑。
 * 这里原本还有一份 Kotlin 实现，同一套规则放两处迟早会算出不一样的结果，已删。
 */
object DouyinParser {

    /**
     * 单条作品的详情接口。用子串匹配，兼容不同版本的路径前缀
     * （网页版是 `aweme/v1/web/aweme/detail/`）。
     */
    const val DETAIL_API = "aweme/detail/"

    /**
     * 主文档里图文数据的定位串（文档中引号是转义的）。
     *
     * 用整个文档里第一个 `images` 字段作为锚点：同一条作品对象里
     * 标题 / 作者都在它前面，所以字段一律从锚点往前找，避免抓到
     * 推荐流里别的对象。
     */
    private const val IMAGES_KEY = "\\\"images\\\":"

    /**
     * 解析详情接口的响应体。
     *
     * 这是**视频类作品**唯一能拿到数据的路：抖音的视频页主文档只是一个 JS
     * 虚拟机壳（实测 72914 字节、零数据），真正的数据是页面里的脚本算好签名后
     * 再去请求 `aweme/v1/web/aweme/detail/` 拿回来的。所以视频必须靠拦这条请求。
     * 图文 / 图集 / 实况图不同 —— 那些页面服务端直出数据，走 parseMainDocument。
     *
     * @param expectedId 目标作品编号（取自页面地址）。页面可能顺带请求别的作品，
     *                   编号对不上的一律丢弃 —— 否则会把别人的视频当成果下走。
     */
    fun parseDetail(body: String, sourceUrl: String, expectedId: String): DouyinDetail {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return DouyinDetail.None
        val detail = root.optJSONObject("aweme_detail")

        if (detail == null) {
            // 服务端明确说这条作品看不了（仅自己可见 / 已删除 / 审核中）。
            // 这是定论：换链路、补 Cookie 都不会有结果，所以把它的原话直接回给
            // 用户，不必让人干等到 45 秒超时再看一句含糊的「网络较慢」。
            val filter = root.optJSONObject("filter_detail") ?: return DouyinDetail.None
            val id = filter.optString("aweme_id")
            if (expectedId.isNotBlank() && id.isNotBlank() && id != expectedId) {
                return DouyinDetail.None
            }
            val msg = filter.optString("detail_msg")
            return if (msg.isBlank()) DouyinDetail.None else DouyinDetail.Unavailable(msg)
        }

        val id = detail.optString("aweme_id")
        if (expectedId.isNotBlank() && id.isNotBlank() && id != expectedId) {
            return DouyinDetail.None
        }
        val video = detail.optJSONObject("video") ?: return DouyinDetail.None

        val author = detail.optJSONObject("author") ?: JSONObject()
        val cover = video.optJSONObject("cover")
            ?.optJSONArray("url_list")
            ?.optString(0)
            .orEmpty()
        val durationMs = video.optLong("duration", 0L)

        return DouyinDetail.Video(
            JSONObject().apply {
                put("id", id.ifBlank { expectedId })
                put("title", detail.optString("desc"))
                // 抖音的 desc 同时也是标题；引擎侧只在标题为空时才把正文留下
                put("description", detail.optString("desc"))
                put("uploader", author.optString("nickname"))
                put(
                    "uploader_id",
                    author.optString("unique_id").ifBlank { author.optString("short_id") },
                )
                put(
                    "duration",
                    if (durationMs > 0) durationMs / 1000 else JSONObject.NULL,
                )
                put("thumbnail", cover)
                put("source_url", sourceUrl)
                put("content_type", "video")
                // 档位与默认档的挑选交给引擎（见类注释），这里把 video 原样带过去。
                // 网页版这个对象实测自带 bit_rate（本次样本 11 档），够用。
                put("video_raw", video.toString())
            }
        )
    }

    // ==================== 主文档（页面）解析 ====================
    // 抖音的图文 / 图集 / 实况图属于「笔记」，那个免签名的 feed 接口不服务它们
    // （只会返回一堆无关的推荐视频）。但桌面版页面的**主文档**里，
    // 服务端把这条作品的数据直出在了 React 流式负载里，形如（引号是转义的）：
    //
    //   ...\"desc\":\"标题...\",\"images\":[{\"width\":2160,\"height\":2870,
    //        \"urlList\":[\"https://...~tplv-dy-aweme-images:q75.webp?...\"],
    //        \"video\":null}, ...]
    //
    // 实况图的动效在每条 media 的 \"video\":{\"playAddr\":[{\"src\":\"...\"}]}。
    // 下面这几个函数就是把这段转义 JSON 抠出来。

    /**
     * 从页面主文档里解析出作品数据。
     *
     * @return 结构化数据；返回 null 表示文档里没有图文类数据（可能是普通视频页）
     */
    fun parseMainDocument(html: String, sourceUrl: String, expectedId: String): JSONObject? {
        val keyAt = html.indexOf(IMAGES_KEY)
        if (keyAt < 0) return null
        val raw = extractEscapedArrayAt(html, keyAt) ?: return null
        val images = runCatching { JSONArray(unescape(raw)) }.getOrNull() ?: return null
        if (images.length() == 0) return null

        val media = JSONArray()
        var cover = ""
        var liveCount = 0
        for (i in 0 until images.length()) {
            val item = images.optJSONObject(i) ?: continue
            val url = pickImageUrl(item) ?: continue
            if (cover.isBlank()) cover = url

            val live = item.optJSONObject("video")
                ?.optJSONArray("playAddr")
                ?.optJSONObject(0)
                ?.optString("src")
                .orEmpty()
            if (live.isNotBlank()) liveCount++

            media.put(
                JSONObject().apply {
                    put("url", url)
                    put("width", item.optInt("width"))
                    put("height", item.optInt("height"))
                    // 普通图集这里是空串；实况图是那一段「动」的视频地址
                    put("live", live)
                }
            )
        }
        if (media.length() == 0) return null

        val scope = enclosingObject(html, keyAt)
        // 把整个对象按 JSON 解析一遍：标题里的 `\n`、`\u0026` 这类转义交给它处理，
        // 比按字符抠字符串准确。解析不了就退回「在原文本里抠字段」。
        val scopeJson = scope?.let { runCatching { JSONObject(unescape(it)) }.getOrNull() }

        fun pick(jsonKey: String, rawKey: String): String {
            val fromJson = scopeJson?.optString(jsonKey).orEmpty()
            if (fromJson.isNotBlank() && fromJson != "\$undefined") return fromJson
            val fromScope = scope?.let { extractEscapedString(it, rawKey) }
            return (fromScope ?: extractEscapedStringBefore(html, rawKey, keyAt)).orEmpty()
        }

        return JSONObject().apply {
            put("id", expectedId)
            put("title", pick("desc", "desc"))
            put("description", pick("desc", "desc"))
            put("uploader", pick("nickname", "nickname"))
            put("uploader_id", pick("unique_id", "unique_id"))
            put("duration", JSONObject.NULL)
            put("thumbnail", cover)
            put("source_url", sourceUrl)
            // 判定口径是「**每一张**都带动效地址」才叫实况图，不是「有一张带动效」。
            // 真机实测：一条 26 张的普通图集里只有 1 张带了 playAddr，按「任一张」
            // 会被整条判成实况图（用户反馈的现象）；而实况合集 4/4、单张实况 1/1
            // 都是全带，所以「全部」这个阈值能同时满足两边。
            put("content_type", if (liveCount > 0 && liveCount == media.length()) "live" else "images")
            put("media", media)
        }
    }

    /**
     * 从一条 media 里挑一个可用地址。
     *
     * 只用 `urlList`（模板 `tplv-dy-aweme-images`，无水印）。
     * **不要用 `downloadUrlList`** —— 它的模板是 `tplv-dy-water-v2`，
     * 参数是对应的"抖音号：xxxx"，会把水印烙在图上（实测解码确认）。
     */
    private fun pickImageUrl(item: JSONObject): String? {
        val urls = item.optJSONArray("urlList") ?: return null
        var fallback = ""
        for (k in 0 until urls.length()) {
            val u = urls.optString(k)
            if (u.isBlank()) continue
            if (fallback.isBlank()) fallback = u
            // jpeg 兼容性最好，优先
            if (u.contains(".jpeg") || u.contains(".jpg")) return u
        }
        return fallback.ifBlank { null }
    }

    /**
     * 从 keyIndex（某个 `\"key\":` 的位置）往回找到键名，再抠出它对应的数组原文。
     * 数组必须紧跟在这个键之后。
     */
    private fun extractEscapedArrayAt(src: String, keyIndex: Int): String? {
        var i = src.indexOf(':', keyIndex)
        if (i < 0) return null
        while (i < src.length && src[i] != '[') i++
        if (i >= src.length) return null
        return balanced(src, i)
    }

    /**
     * 框出「装着 images 的那个 JSON 对象」的原文。
     *
     * 从 images 数组的位置往前做括号配平，找到所属对象的 `{`，再正向配平到 `}`。
     * 返回 null 表示找不到（调用方退回「从 images 往前找」的兜底策略）。
     */
    private fun enclosingObject(src: String, fromIndex: Int): String? {
        var i = src.indexOf(':', fromIndex)
        if (i < 0) return null
        while (i < src.length && src[i] != '[') i++
        if (i >= src.length) return null
        val arrStart = i

        var depth = 0
        var inStr = false
        i = arrStart - 1
        while (i >= 0) {
            val c = src[i]
            if (c == '"') {
                // 往左数连续反斜杠；与 balanced 同一套规则（n % 4 == 1 才是结构引号）
                var k = i - 1
                var n = 0
                while (k >= 0 && src[k] == '\\') {
                    n++
                    k--
                }
                if (n % 4 == 1) {
                    inStr = !inStr
                    i = k
                    continue
                }
            } else if (!inStr) {
                when (c) {
                    '}', ']' -> depth++
                    '[', '{' -> {
                        if (depth == 0) return balanced(src, i)
                        depth--
                    }
                }
            }
            i--
        }
        return null
    }

    /**
     * 从 start（'[' 或 '{'）开始做括号配平。
     *
     * 引号在这段文档里是 JS 字符串转义过的，判断「这个引号是不是结构引号」不能只看
     * 前面有没有反斜杠 —— 实测踩过坑：实况图的 video 里带
     * `\"audio_bitrate_set\":\"[\\\"128k_unknown\\\"]\"`，这里的 `\\\"` 是
     * 「反斜杠 + 转义引号」，不是结构引号。按 `\"` 一刀切会把字符串状态搞反，
     * 于是数组被提前收尾，整条解析失败（也就看不到实况图）。
     *
     * 正确规则：数一段连续反斜杠的长度 n，只有 n % 4 == 1 时，它后面那个引号
     * 才是结构引号（n 个反斜杠在 JS 解码后剩 n/2 个，偶数个反斜杠 + 引号 = 转义引号）。
     */
    private fun balanced(src: String, start: Int): String? {
        var depth = 0
        var inStr = false
        var i = start
        while (i < src.length) {
            if (src[i] == '\\') {
                var j = i
                while (j < src.length && src[j] == '\\') j++
                val n = j - i
                if (j < src.length && src[j] == '"' && n % 4 == 1) inStr = !inStr
                i = j
                continue
            }
            if (!inStr) {
                when (src[i]) {
                    '[', '{' -> depth++
                    ']', '}' -> {
                        depth--
                        if (depth == 0) return src.substring(start, i + 1)
                    }
                }
            }
            i++
        }
        return null
    }

    /**
     * JS 字符串转义 → JSON 文本：`\\` 还原成 `\`，`\"` 还原成 `"`，
     * 其余（如 `\u0026`）原样留给 JSON 解析器。
     */
    private fun unescape(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val n = s[i + 1]
                sb.append(if (n == '\\' || n == '"') n else c)
                if (n != '\\' && n != '"') sb.append(n)
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** 抠出 `\"key\":\"值\"` 里的值（从 src 里第一个匹配处取）。 */
    private fun extractEscapedString(src: String, key: String): String? {
        val needle = "\\\"" + key + "\\\":\\\""
        val at = src.indexOf(needle)
        if (at < 0) return null
        return readEscapedValue(src, at + needle.length)
    }

    /** 抠出 `\"key\":\"值\"` 里的值；只在该位置之前找**最近**的那个键。 */
    private fun extractEscapedStringBefore(src: String, key: String, before: Int): String? {
        val needle = "\\\"" + key + "\\\":\\\""
        val at = src.lastIndexOf(needle, before)
        if (at < 0) return null
        return readEscapedValue(src, at + needle.length)
    }

    /** 从值的起始位置读到结束的（转义的）引号为止，并顺手还原 `\\` 与转义引号。 */
    private fun readEscapedValue(src: String, from: Int): String {
        val sb = StringBuilder()
        var i = from
        while (i < src.length) {
            val c = src[i]
            if (c == '\\') {
                var j = i
                while (j < src.length && src[j] == '\\') j++
                val n = j - i
                if (j < src.length && src[j] == '"') {
                    if (n % 4 == 1) return sb.toString() // 真正的结束引号
                    repeat(n / 2) { sb.append('\\') }
                    sb.append('"')
                    i = j + 1
                    continue
                }
                sb.append(src, i, j)
                i = j
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}
