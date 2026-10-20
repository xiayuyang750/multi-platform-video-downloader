package com.ytdlp.android.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * 微博正文的数据解析（Kotlin 侧）。
 *
 * 为什么微博要单开一条链路：yt-dlp 的微博提取器**只产出视频**，图文 / 图集 /
 * 实况图 / 动图这些没有独立视频流，一律报 `No video formats found!`（用户实测
 * 12 条里 9 条失败）。而这些内容的图片数据在移动版详情页里是现成的。
 *
 * 数据来源：[com.ytdlp.android.WeiboActivity] 从 `window.$render_data.status`
 * 取出来的结构化对象（字段名以真机抓到的为准，见 EXTRACT_JS 的注释）。
 */
sealed class WeiboDetail {
    data class Note(val payload: JSONObject) : WeiboDetail()

    /**
     * 正文里挂的是一个视频链接，需要拿这条 URL 再解析一次。
     *
     * 实测样本 `weibo.com/2/detail/5325042800918817`：页面 `pics` 为空、
     * `page_info` 里没有视频流，但正文文本里写着
     * `https://video.weibo.com/show?fid=1034:4612557713899533`。
     * 这类帖子（微博很常见）只能先捞链接、再交给视频链路。
     */
    data class Redirect(val url: String) : WeiboDetail()

    data object None : WeiboDetail()
    data class Unavailable(val message: String) : WeiboDetail()
}

object WeiboParser {

    /**
     * @param pulled   EXTRACT_JS 的返回值，形如 `{"status": {...}, "_picKeys": "..."}`
     * @param shareUrl 用户给的原始链接
     */
    fun buildPayload(pulled: String, shareUrl: String): WeiboDetail {
        if (pulled.isBlank()) return WeiboDetail.None
        val root = runCatching { JSONObject(pulled) }.getOrNull() ?: return WeiboDetail.None

        // 头条文章是另一条数据形态：没有 status，只有标题 + 正文里的图
        root.optJSONObject("article")?.let { art ->
            val imgs = art.optJSONArray("images") ?: JSONArray()
            // 0 张不立刻下结论：文章页的图是懒加载的，调用方还会滚屏后重试
            if (imgs.length() == 0) return WeiboDetail.None
            val media = JSONArray()
            for (i in 0 until imgs.length()) {
                val url = imgs.optString(i)
                if (url.isBlank()) continue
                media.put(JSONObject().apply {
                    put("url", url)
                    put("width", 0)
                    put("height", 0)
                    put("live", "")
                    put("kind", "image")
                    put("name", (i + 1).toString())
                })
            }
            val title = art.optString("title").trim()
            return WeiboDetail.Note(JSONObject().apply {
                put("id", shareUrl.hashCode().toString())
                put("source_url", shareUrl)
                put("title", title.ifBlank { "微博头条文章" }.take(120))
                put("uploader", "")
                put("uploader_id", "")
                put("thumbnail", media.optJSONObject(0)?.optString("url") ?: "")
                put("content_type", "images")
                put("media", media)
                put("video_url", "")
            })
        }

        val status = root.optJSONObject("status") ?: return WeiboDetail.None

        val media = JSONArray()
        var liveCount = 0
        var cover = ""

        // 微博的图可能在正文里，也可能在被转发的微博里（转发时正文往往没图）。
        // 两处都收，正文优先 —— 用户要的是「这条帖子里能看到的图」。
        fun collect(pics: JSONArray?, prefix: String) {
            if (pics == null) return
            for (i in 0 until pics.length()) {
                val p = pics.optJSONObject(i) ?: continue
                val url = p.optString("url")
                if (url.isBlank()) continue
                val live = p.optString("live")
                if (live.isNotBlank()) liveCount++
                if (cover.isBlank()) cover = url
                media.put(JSONObject().apply {
                    put("url", url)
                    put("width", p.optInt("width"))
                    put("height", p.optInt("height"))
                    put("live", live)
                    put("kind", "image")
                    put("name", prefix + (i + 1))
                })
            }
        }
        collect(status.optJSONArray("pics"), "")
        val retweeted = status.optJSONObject("retweeted")
        if (retweeted != null) collect(retweeted.optJSONArray("pics"), "转发_")

        val pageInfo = status.optJSONObject("page_info") ?: JSONObject()
        val videoUrl = pageInfo.optString("stream_url")
            .ifBlank { pageInfo.optString("stream_url_hd") }

        val ctype = when {
            // 只有一张图且带视频 → 按视频给（正文里的单图多半是封面）
            videoUrl.isNotBlank() && media.length() <= 1 -> "video"
            liveCount > 0 -> "live"
            media.length() > 0 -> "images"
            else -> "none"
        }
        if (ctype == "none") {
            // 正文里可能挂着一个视频（长视频帖常见）。`$render_data.status.text`
            // 是 HTML 转义后的字符串，但 URL 本身是明文，直接正则即可。
            videoLinkInText(status.optString("text"))?.let { return WeiboDetail.Redirect(it) }

            val text = status.optString("text").replace(Regex("<[^>]+>"), "").trim()
            return WeiboDetail.Unavailable(
                if (text.isBlank())
                    "这条微博里没有可下载的图片或视频。"
                else
                    "这条微博没有图片也没有视频，只有文字，没有可下载的内容。"
            )
        }

        val user = status.optJSONObject("user") ?: JSONObject()
        val text = status.optString("text").replace(Regex("<[^>]+>"), "").trim()
        val payload = JSONObject().apply {
            put("id", status.optString("id"))
            put("source_url", shareUrl)
            put("title", text.lineSequence().firstOrNull().orEmpty().take(120))
            put("uploader", user.optString("name"))
            put("uploader_id", user.optString("id"))
            put("thumbnail", cover)
            put("content_type", ctype)
            put("media", media)
            put("video_url", if (ctype == "video") videoUrl else "")
        }
        return WeiboDetail.Note(payload)
    }

    /**
     * 从正文文本里捞视频链接，返回可再次解析的完整 URL。
     *
     * 微博正文里的视频有两种写法：
     *   · `https://video.weibo.com/show?fid=1034:4612557713899533`（常见）
     *   · 少数是 `https://weibo.com/tv/show/1034:...`
     * 只认前者：后者实测极少出现在正文里，多一种正则就多一份误判风险。
     */
    private val VIDEO_LINK = Regex(
        """video\.weibo\.com/show\?fid=([0-9A-Za-z:_-]+)"""
    )

    private fun videoLinkInText(text: String): String? {
        val fid = VIDEO_LINK.find(text)?.groupValues?.getOrNull(1) ?: return null
        return "https://video.weibo.com/show?fid=$fid"
    }

    /** 回传「需要再解析一次」的 URL 时用的 payload 字段名（解析页与 ViewModel 共用）。 */
    const val PAYLOAD_REDIRECT = "redirect_url"
}
