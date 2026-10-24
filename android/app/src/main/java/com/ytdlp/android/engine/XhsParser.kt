package com.ytdlp.android.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * 小红书笔记数据的解析（Kotlin 侧）。
 *
 * 与抖音那条的区别：抖音是在 WebView 里**拦主文档**、再从 HTML 里解析；小红书
 * 这边直接用页面自己的 JS 把 `window.__INITIAL_STATE__.note.noteDetailMap` 取出来
 * （见 [com.ytdlp.android.XhsActivity] 里的 EXTRACT_JS），拿到的是**结构化 JSON**，
 * 所以这里只做「字段搬运 + 候选回退」，不做 HTML 解析。
 *
 * 三态设计沿用抖音那套：`None`（还没拦到，继续等）与 `Unavailable`（服务端明确
 * 说这条看不了）对用户的意义完全不同，混成一个 null 会让界面无从判断。
 */
sealed class XhsDetail {
    /** 拿到笔记数据，payload 交给引擎的 `save_xhs` 入库。 */
    data class Note(val payload: JSONObject) : XhsDetail()

    /** 这份数据里没有我们要的东西 —— 继续等，不是结论。 */
    data object None : XhsDetail()

    /** 服务端明确说这条看不了（已删除 / 仅自己可见）。 */
    data class Unavailable(val message: String) : XhsDetail()
}

object XhsParser {

    /**
     * 把 WebView 取回的那段 JSON 变成入库 payload。
     *
     * @param pulled     EXTRACT_JS 的返回值，形如 `{"note": {...}, "loggedIn": true}`；
     *                   取不到时是空串
     * @param shareUrl   用户给的那条分享链接（落库用）
     * @param expectedId 目标笔记编号。**必须锁定**：页面加载时会顺带请求别的笔记，
     *                   不锁定就可能把别人的内容当成结果交出去 —— 抖音那边踩过
     *                   这个坑（把推荐位的作品当成果交付）。
     */
    fun buildPayload(pulled: String, shareUrl: String, expectedId: String): XhsDetail {
        if (pulled.isBlank()) return XhsDetail.None
        val root = runCatching { JSONObject(pulled) }.getOrNull() ?: return XhsDetail.None
        val note = root.optJSONObject("note") ?: return XhsDetail.None

        val id = note.optString("noteId").ifBlank { expectedId }
        // 编号对不上就不是我们要的那条，宁可继续等
        if (expectedId.isNotBlank() && id.isNotBlank() && id != expectedId) return XhsDetail.None

        val type = note.optString("type")
        val user = note.optJSONObject("user") ?: JSONObject()
        val desc = note.optString("desc")
        val title = note.optString("title").ifBlank { desc.lineSequence().firstOrNull().orEmpty() }

        val media = JSONArray()
        var cover = ""
        var liveCount = 0
        val images = note.optJSONArray("imageList") ?: JSONArray()
        for (i in 0 until images.length()) {
            val img = images.optJSONObject(i) ?: continue
            val url = imageUrl(img)
            if (url.isBlank()) continue
            val live = liveUrl(img)
            if (live.isNotBlank()) liveCount++
            if (cover.isBlank()) cover = url
            media.put(JSONObject().apply {
                put("url", url)
                put("width", img.optInt("width"))
                put("height", img.optInt("height"))
                put("live", live)
                put("kind", "image")
            })
        }

        // 内容形态：视频 → video；图里有动效且**真的取到了动效地址**才算 live（否则
        // 界面上会写「实况」却播不出动的，比如实标成图文更糟）；其余是 images。
        // 注：「带一整组图的视频笔记」（合集类）在这里仍标成 video，由 Python 侧
        // 按「≥2 张图」改判为图集 —— 那条规则放在 Python 是为了有离线断言守着。
        val ctype = when {
            type == "video" -> "video"
            liveCount > 0 -> "live"
            media.length() > 0 -> "images"
            else -> "video"   // 什么都没有时保持老行为，让下游按视频链路过
        }

        val video = note.optJSONObject("video")
        val payload = JSONObject().apply {
            put("id", id)
            put("source_url", shareUrl)
            put("title", title)
            // 完整描述（含话题、换行）。标题只取首行，正文才是用户要读的内容。
            put("description", desc)
            put("uploader", user.optString("nickname"))
            // 用户编号（小红书叫 redId / userId），界面作为「作者唯一标识」显示
            put("uploader_id", user.optString("userId").ifBlank { user.optString("redId") })
            put("thumbnail", cover)
            put("content_type", ctype)
            put("media", media)
            if (ctype == "video" && video != null && media.length() < 2) {
                // 视频只把 video 段原样交给 Python，由它挑直链（规则只保留一份）。
                // 「合集类」（带 ≥2 张图）不带 video_raw —— Python 侧会把它当图集，
                // 而那个对象的 mediaV2 是一大串 JSON，没必要跨语言搬过去。
                put("video_raw", video.toString())
                put("duration", streamDurationSeconds(video))
            } else {
                put("video_raw", "")
            }
        }
        return XhsDetail.Note(payload)
    }

    /** 图片地址：按「展示质量 → 预览」的顺序取第一个非空的。 */
    private fun imageUrl(img: JSONObject): String {
        for (key in listOf("urlDefault", "urlPre", "url")) {
            val v = img.optString(key)
            if (v.isNotBlank()) return v
        }
        // infoList 里 WB_DFT 是展示图、WB_PRV 是预览图；两个场景都试
        val info = img.optJSONArray("infoList") ?: return ""
        for (scene in listOf("WB_DFT", "WB_PRV")) {
            for (i in 0 until info.length()) {
                val one = info.optJSONObject(i) ?: continue
                if (one.optString("imageScene") == scene) {
                    val v = one.optString("url")
                    if (v.isNotBlank()) return v
                }
            }
        }
        return ""
    }

    /**
     * 实况图「动的那一段」的地址。
     *
     * **实测结论（真机抓到的字段）**：动效就挂在**图片自己的 `stream` 里**：
     *
     *     "stream": { "EF4": [ {masterUrl, backupUrls, qualityType, …} ], "EF5": [], … }
     *
     * 两个坑都在这里踩过：
     *   ① `stream` 是**对象**（按平台自己的编码编号分组的字典），不是数组 ——
     *      早先按数组解析，两个分支都落空，所以实况图一直只拿到静态图；
     *   ② 键名是 EF4 / EF5 这种平台编号，不是 h264 —— 按编码名挑没有意义。
     *
     * 挑法与视频那条一致：优先合流条（有音轨的），再比高度。
     * 非实况的普通图文这里是 `{}`，取不到就返回空串（界面按图文走）。
     */
    private fun liveUrl(img: JSONObject): String {
        img.optJSONObject("video")?.let { v ->
            v.optJSONObject("media")?.optJSONObject("stream")?.let { st ->
                bestInStream(st)?.let { return it }
            }
            val play = v.optJSONArray("playAddr")
            if (play != null && play.length() > 0) {
                val src = play.optJSONObject(0)?.optString("src").orEmpty()
                if (src.isNotBlank()) return src
            }
        }
        img.optJSONObject("stream")?.let { st ->
            bestInStream(st)?.let { return it }
        }
        return ""
    }

    /**
     * 从 `{编码编号: [候选…]}` 这种字典里挑一条直链。
     *
     * 排序依据：**带音轨的优先**（实测同一支视频里既有 `format:"mp4"` + aac/2ch
     * 的合流条，也有 `format:"fmp4"` + `audioChannels:0` 的纯画面分片；只按高度挑
     * 会挑中纯画面的那条，用户拿到的是无声文件），再比高度、最后比体积。
     */
    private fun bestInStream(stream: JSONObject): String? {
        val all = ArrayList<JSONObject>()
        for (codec in stream.keys()) {
            val arr = stream.optJSONArray(codec) ?: continue
            for (i in 0 until arr.length()) {
                val one = arr.optJSONObject(i) ?: continue
                if (one.optString("masterUrl").isNotBlank()) all.add(one)
            }
        }
        if (all.isEmpty()) return null
        val muxed = all.filter { hasAudio(it) }
        val picked = (if (muxed.isNotEmpty()) muxed else all).maxWithOrNull(
            compareBy({ it.optInt("height") }, { it.optInt("size") })
        )
        return picked?.optString("masterUrl")?.takeIf { it.isNotBlank() }
    }

    /** 这条候选是不是「画面+声音」合流的（判据与 Python 侧 _xhs_is_muxed 一致）。 */
    private fun hasAudio(entry: JSONObject): Boolean {
        if (entry.optInt("audioChannels") > 0) return true
        if (entry.optInt("audioBitrate") > 0) return true
        val codec = entry.optString("audioCodec").lowercase()
        return codec.isNotBlank() && codec != "none"
    }

    /** 时长（秒）。stream 里给的是毫秒；取所有候选里最大的那个（同一条内容各编码时长相同）。 */
    private fun streamDurationSeconds(video: JSONObject): Int? {
        val stream = video.optJSONObject("media")?.optJSONObject("stream") ?: return null
        var ms = 0
        for (codec in stream.keys()) {
            val arr = stream.optJSONArray(codec) ?: continue
            for (i in 0 until arr.length()) {
                val one = arr.optJSONObject(i) ?: continue
                ms = maxOf(ms, one.optInt("duration"))
            }
        }
        return if (ms > 0) ms / 1000 else null
    }
}
