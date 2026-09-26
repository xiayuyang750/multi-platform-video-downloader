package com.ytdlp.android.engine

import android.content.Context
import android.os.Environment
import android.util.Log
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 引擎的 Kotlin 门面：把 Python 侧的 ytdlp_engine 包成类型安全的方法。
 *
 * 三条必须遵守的约定（都是实测踩出来的）：
 *
 * 1. **所有方法都是阻塞的**，必须从 IO 线程调。解析一条境外链接可能要几十秒，
 *    放主线程就是 ANR 弹窗。
 * 2. **参数不能传 java.io.File**。Chaquopy 不会把 File 转成 Python 的 str，
 *    误传会在 Python 侧报 "argument should be a str or an os.PathLike object"。
 *    所以下面的路径一律是 String。
 * 3. **parseUrl 不可中断**。进程内没有子进程可 kill，界面的「取消」只能做到
 *    「我不等了」，Python 那边会自己跑完。界面必须自己收住超时（见 TIMEOUT_MS）。
 */
object Engine {

    private const val TAG = "Engine"

    /** 解析超时。与 Windows 端对齐（engine.py 的 PARSE_TIMEOUT）。 */
    const val TIMEOUT_MS = 75_000L

    @Volatile
    private var module: PyObject? = null

    /** 启动内嵌 Python 并把只有安卓侧才知道的路径交给引擎。
     *
     * 用 @Synchronized 保证只初始化一次：Python.start 不能并发调，
     * 而首次解析和首次读历史可能同时触发初始化。
     */
    @Synchronized
    private fun module(context: Context): PyObject {
        module?.let { return it }

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context.applicationContext))
        }
        val m = Python.getInstance().getModule("ytdlp_engine")

        // 应用原生库目录：只有这里的文件允许被执行（应用数据目录是 noexec）。
        // 自带的 ffmpeg / qjs 都是可执行二进制，只能放在这。
        val libDir = context.applicationInfo.nativeLibraryDir
        val downloads = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        ).absolutePath
        val fallback = File(context.filesDir, "Download").absolutePath

        val settings = m.callAttr(
            "configure",
            context.filesDir.absolutePath,
            File(libDir, "libffmpeg.so").absolutePath,
            File(libDir, "libqjs.so").absolutePath,
            downloads,
            fallback,
        ).toString()
        Log.i(TAG, "引擎已就绪，设置：$settings")

        module = m
        return m
    }

    /** 预热：提前把 Python 起来，避免用户点「解析」时先干等 1 秒。 */
    fun warmUp(context: Context) {
        runCatching { module(context) }
            .onFailure { Log.w(TAG, "引擎预热失败（不影响后续使用）", it) }
    }

    // ---- 解析 ----

    sealed interface ParseResult {
        data class Ok(val info: Video) : ParseResult
        data class Err(val message: String) : ParseResult
    }

    fun parseUrl(context: Context, url: String): ParseResult {
        val json = module(context).callAttr("parse_url", url).toString()
        val obj = JSONObject(json)
        if (!obj.optBoolean("ok")) {
            return ParseResult.Err(obj.optString("error").ifBlank { "解析失败" })
        }
        return ParseResult.Ok(Video.fromParseResult(obj))
    }

    // ---- 下载 ----

    /** 发起下载。返回错误说明（null 表示已受理）。
     *
     * 注意「已受理」不等于「下载成功」：真正的结果要靠 downloadState() 轮询。
     */
    fun startDownload(context: Context, url: String): String? {
        val obj = JSONObject(module(context).callAttr("start_download", url).toString())
        return if (obj.optBoolean("ok")) null else obj.optString("error")
    }

    fun downloadState(context: Context): Download = Download.fromJson(
        JSONObject(module(context).callAttr("get_download_state").toString())
    )

    // ---- 历史 ----

    fun history(context: Context): List<Video> {
        val arr = JSONArray(module(context).callAttr("get_history").toString())
        return (0 until arr.length()).map { Video.fromRow(arr.getJSONObject(it)) }
    }

    fun deleteHistory(context: Context, id: String) {
        module(context).callAttr("delete_history", id)
    }

    fun clearHistory(context: Context, platform: String) {
        module(context).callAttr("clear_history", platform)
    }

    fun clearAllHistory(context: Context) {
        module(context).callAttr("clear_all_history")
    }

    // ---- 设置 ----

    fun settings(context: Context): Settings = Settings.fromJson(
        JSONObject(module(context).callAttr("get_settings").toString())
    )

    /** 设置输出目录。返回错误说明（null 表示成功）。 */
    fun setOutputDir(context: Context, path: String): String? {
        val obj = JSONObject(module(context).callAttr("set_output_dir", path).toString())
        return if (obj.optBoolean("ok")) null else obj.optString("error")
    }

    fun setCookiesFile(context: Context, path: String) {
        module(context).callAttr("set_cookies_file", path)
    }
}

/**
 * 一条视频记录。字段与 Python 侧 history 表的列一一对应，
 * 解析结果和历史记录共用同一个类（两端字段本来就是对齐的）。
 */
data class Video(
    val id: String,
    val platform: String,
    val title: String,
    val uploader: String,
    val uploaderId: String,
    val duration: Int?,
    val thumbnail: String,
    val sourceUrl: String,
    /** 能直接喂给播放器的直链；为空表示这条没有可直连播放的格式 */
    val resolvedUrl: String,
    /** 下载专用地址（抖音的最高画质可能是 H.265，能下不能播） */
    val downloadUrl: String,
    val resolvedAt: Long,
    val playKind: String,
    /** 以下两项只有解析结果有，历史记录里为空 */
    val quality: String = "",
    val filesize: String = "",
    /** 非空表示走的是备用链路（抖音浏览器模式 / X 第三方服务） */
    val via: String = "",
) {
    /** 有没有可以直连播放的地址。空的话界面要说明为什么播不了。 */
    val playable: Boolean get() = resolvedUrl.isNotBlank() && playKind == "progressive"

    companion object {
        fun fromParseResult(o: JSONObject) = Video(
            id = o.optString("id"),
            platform = o.optString("platform"),
            title = o.optString("title"),
            uploader = o.optString("uploader"),
            uploaderId = o.optString("uploader_id"),
            duration = o.optIntOrNull("duration"),
            thumbnail = o.optString("thumbnail"),
            sourceUrl = o.optString("source_url"),
            resolvedUrl = o.optString("resolved_url"),
            downloadUrl = o.optString("download_url"),
            resolvedAt = o.optLong("resolved_at"),
            playKind = o.optString("play_kind"),
            quality = o.optString("quality"),
            filesize = o.optString("filesize"),
            via = o.optString("via"),
        )

        fun fromRow(o: JSONObject) = Video(
            id = o.optString("id"),
            platform = o.optString("platform"),
            title = o.optString("title"),
            uploader = o.optString("uploader"),
            uploaderId = o.optString("uploader_id"),
            duration = o.optIntOrNull("duration"),
            thumbnail = o.optString("thumbnail"),
            sourceUrl = o.optString("source_url"),
            resolvedUrl = o.optString("resolved_url"),
            downloadUrl = o.optString("download_url"),
            resolvedAt = o.optLong("resolved_at"),
            playKind = o.optString("play_kind"),
        )
    }
}

/** 下载状态快照。type 取值：idle / starting / progress / processing / done / error */
data class Download(
    val type: String,
    val active: Boolean,
    val percent: Double?,
    val path: String,
    val message: String,
    val downloaded: Long,
    val total: Long,
    val speed: Double,
    val eta: Int?,
) {
    val isError: Boolean get() = type == "error"
    val isDone: Boolean get() = type == "done"
    /** 还没拿到百分比（解析、建连阶段）：界面上进度条要显示成不确定态，
     *  否则空条看起来像卡死。 */
    val isIndeterminate: Boolean get() = active && percent == null

    companion object {
        fun fromJson(o: JSONObject) = Download(
            type = o.optString("type", "idle"),
            active = o.optBoolean("active"),
            percent = if (o.isNull("percent")) null else o.optDouble("percent"),
            path = o.optString("path"),
            message = o.optString("message"),
            downloaded = o.optLong("downloaded"),
            total = o.optLong("total"),
            speed = o.optDouble("speed", 0.0),
            eta = o.optIntOrNull("eta"),
        )
    }
}

data class Settings(
    val outputDir: String,
    /** 真表示用的是应用私有目录（系统下载目录没权限），界面上要如实说明 */
    val outputDirIsFallback: Boolean,
    val preferredDir: String,
    val cookiesFile: String,
    val cookiesPresent: Boolean,
) {
    companion object {
        fun fromJson(o: JSONObject) = Settings(
            outputDir = o.optString("output_dir"),
            outputDirIsFallback = o.optBoolean("output_dir_is_fallback"),
            preferredDir = o.optString("preferred_dir"),
            cookiesFile = o.optString("cookies_file"),
            cookiesPresent = o.optBoolean("cookies_present"),
        )
    }
}

/** JSONObject.optInt 在键缺失时返回 0，会把「没有时长」和「0 秒」混为一谈。 */
private fun JSONObject.optIntOrNull(key: String): Int? =
    if (isNull(key)) null else optInt(key)
