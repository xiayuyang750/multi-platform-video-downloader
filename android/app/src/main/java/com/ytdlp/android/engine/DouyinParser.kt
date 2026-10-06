package com.ytdlp.android.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * 抖音 aweme/detail 接口响应的解析。
 *
 * 逻辑与 windows/douyin_fallback.py 的 _pick_tiers 一一对应 —— 两端面对的是
 * 同一个接口、同一套字段，所以提取规则必须一致，改一处要同步另一处。
 *
 * 这里只做「从一段 JSON 里挑出要的字段」这一件事，不碰网络、不碰 WebView，
 * 因此可以脱离设备单独验证。
 */
object DouyinParser {

    /** 单条视频的详情接口。用子串匹配，兼容不同版本的路径前缀。 */
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
     * 从响应体里提取视频信息。
     *
     * @return 结构化数据；返回 null 表示这条响应里没有视频数据
     *         （比如链接指向的是用户主页或合集，而不是单条视频）。
     */
    fun parse(body: String, sourceUrl: String): JSONObject? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val detail = root.optJSONObject("aweme_detail") ?: return null
        val video = detail.optJSONObject("video") ?: return null

        val (playUrl, downloadUrl) = pickTiers(video)
        if (playUrl.isBlank()) return null

        val author = detail.optJSONObject("author") ?: JSONObject()
        val cover = video.optJSONObject("cover")
            ?.optJSONArray("url_list")
            ?.optString(0)
            .orEmpty()
        val durationMs = video.optLong("duration", 0L)

        return JSONObject().apply {
            put("id", detail.optString("aweme_id"))
            put("title", detail.optString("desc"))
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
            put("play_url", playUrl)
            put("download_url", downloadUrl)
            put("source_url", sourceUrl)
        }
    }

    /**
     * 从 bit_rate 里挑出两个地址：能播的最高档、画质最高的档。
     *
     * 抖音的 video.play_addr 只是「默认档」，bit_rate 数组才是全部档位
     * （实测同一视频有 23 档）。关键差异：最高码率档通常是 H.265 编码，
     * 而 H.265 在浏览器和部分安卓设备上放不出来；能播的最高档是
     * H.264 + 完整 mp4。
     *
     * 所以分开选，各自用在合适的地方：
     *   play_url     → 放进播放器直接看
     *   download_url → 下载用，画质最好
     */
    private fun pickTiers(video: JSONObject): Pair<String, String> {
        var bestPlay = ""
        var bestPlayKey = -1L to -1L
        var bestAny = ""
        var bestAnyKey = -1L to -1L

        // (清晰度, 码率) 的字典序比较。Kotlin 的 Pair 没有现成的比较运算符，
        // 这里自己写清楚，免得依赖隐式扩展而编译不过。
        fun isBetter(a: Pair<Long, Long>, b: Pair<Long, Long>): Boolean =
            a.first > b.first || (a.first == b.first && a.second > b.second)

        val bitRates = video.optJSONArray("bit_rate")
        if (bitRates != null) {
            for (i in 0 until bitRates.length()) {
                val item = bitRates.optJSONObject(i) ?: continue
                val play = item.optJSONObject("play_addr") ?: continue
                val urls = play.optJSONArray("url_list") ?: continue
                if (urls.length() == 0) continue
                val url = urls.optString(0)
                if (url.isBlank()) continue

                val key = play.optLong("height", 0L) to item.optLong("bit_rate", 0L)
                if (isBetter(key, bestAnyKey)) {
                    bestAnyKey = key
                    bestAny = url
                }

                // 只有 H.264 且是完整 mp4 的才适合直接播放
                val isH265 = item.optInt("is_h265", 0) != 0
                if (!isH265 && item.optString("format") == "mp4" && isBetter(key, bestPlayKey)) {
                    bestPlayKey = key
                    bestPlay = url
                }
            }
        }

        // 没有符合的档位就退回默认地址，保证至少有个能用的
        if (bestPlay.isBlank()) {
            bestPlay = video.optJSONObject("play_addr")
                ?.optJSONArray("url_list")
                ?.optString(0)
                .orEmpty()
        }
        if (bestAny.isBlank()) bestAny = bestPlay

        return bestPlay to bestAny
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
            put("uploader", pick("nickname", "nickname"))
            put("uploader_id", pick("unique_id", "unique_id"))
            put("duration", JSONObject.NULL)
            put("thumbnail", cover)
            put("source_url", sourceUrl)
            put("content_type", if (liveCount > 0) "live" else "images")
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
