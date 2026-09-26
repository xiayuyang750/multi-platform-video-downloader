package com.ytdlp.android.engine

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * 检查更新：读取 GitHub 仓库的最新 Release，与本机版本比较。
 *
 * 用 GitHub 的公开 API，不需要任何凭据（仓库是公开的）。
 * 所有失败路径都翻译成中文说明 —— 用户要求「网络原因也要明确告知」，
 * 所以这里不吞异常，每种情况都给一句能看懂的话。
 */
object UpdateChecker {

    /**
     * 仓库地址。**要改成你自己的仓库**（owner/repo）。
     * 检查更新功能就是读这里，改完立即生效，不需要动别的代码。
     */
    private const val OWNER = "XCM"
    private const val REPO = "multi-platform-video-downloader"

    private const val API = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    /** 仓库主页，用于「查看所有版本」。 */
    val releasesPage: String get() = "https://github.com/$OWNER/$REPO/releases"

    sealed interface Result {
        /** 有新版本可用 */
        data class Available(
            val version: String,
            val downloadUrl: String,
            val notes: String,
        ) : Result

        /** 已是最新 */
        data object UpToDate : Result

        /** 检查失败，reason 已经是给用户看的中文 */
        data class Failed(val reason: String) : Result
    }

    /**
     * 发起检查。**阻塞调用**，必须在 IO 线程执行。
     *
     * @param currentVersion 本机版本号（BuildConfig.VERSION_NAME）
     */
    fun check(currentVersion: String): Result {
        val json = try {
            fetch()
        } catch (e: SocketTimeoutException) {
            return Result.Failed(
                "连接 GitHub 超时。国内直连通常不通，请开启代理后重试。"
            )
        } catch (e: Exception) {
            return Result.Failed(
                "连不上 GitHub（${e.javaClass.simpleName}）。请检查网络或代理设置。"
            )
        }

        return when (json) {
            is FetchResult.HttpError -> when (json.code) {
                404 -> Result.Failed("仓库还没有发布过任何版本，或仓库地址填错了。")
                403 -> Result.Failed("GitHub 接口访问过于频繁，请等几分钟再试。")
                else -> Result.Failed("GitHub 返回了异常状态码 ${json.code}，请稍后重试。")
            }

            is FetchResult.Ok -> compare(currentVersion, json.body)
        }
    }

    private sealed interface FetchResult {
        data class Ok(val body: JSONObject) : FetchResult
        data class HttpError(val code: Int) : FetchResult
    }

    private fun fetch(): FetchResult {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            // GitHub 的 API 强制要求带 User-Agent，不带会直接被拒
            setRequestProperty("User-Agent", "ytdlp-android-update-check")
            setRequestProperty("Accept", "application/vnd.github+json")
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        try {
            val code = conn.responseCode
            if (code != 200) return FetchResult.HttpError(code)
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return FetchResult.Ok(JSONObject(text))
        } finally {
            conn.disconnect()
        }
    }

    private fun compare(current: String, body: JSONObject): Result {
        val tag = body.optString("tag_name").trim()
        if (tag.isBlank()) {
            return Result.Failed("最新版本没有版本号信息，无法比较。")
        }
        val latest = tag.removePrefix("v").removePrefix("V")
        val mine = current.removePrefix("v").removePrefix("V")

        if (compareVersions(latest, mine) <= 0) return Result.UpToDate

        // 优先给 apk 直链；没有附件的 release 就退回 release 页面
        var downloadUrl = body.optString("html_url")
        val assets = body.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    downloadUrl = a.optString("browser_download_url").ifBlank { downloadUrl }
                    break
                }
            }
        }

        return Result.Available(
            version = latest,
            downloadUrl = downloadUrl,
            notes = body.optString("body").trim(),
        )
    }

    /**
     * 按点分段的数字比较版本号。
     *
     * 不用字符串比较：那样 "1.10" 会小于 "1.9"。
     * 段数不同时短的补 0（"1.0" 与 "1.0.1" 中前者视为 1.0.0）。
     */
    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }
}
