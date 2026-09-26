package com.ytdlp.android.engine

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
}
