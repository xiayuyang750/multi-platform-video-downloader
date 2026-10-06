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

        /**
         * @param needWebview 真表示这个平台要走浏览器模式（抖音）。
         *   引擎自己取不到数，需要界面拉起 WebView 解析页 —— 这是两边
         *   约定的信号，见 ytdlp_engine._parse_douyin。
         */
        data class Err(
            val message: String,
            val needWebview: Boolean = false,
        ) : ParseResult
    }

    fun parseUrl(context: Context, url: String): ParseResult {
        val json = module(context).callAttr("parse_url", url).toString()
        val obj = JSONObject(json)
        if (!obj.optBoolean("ok")) {
            return ParseResult.Err(
                message = obj.optString("error").ifBlank { "解析失败" },
                needWebview = obj.optBoolean("need_webview"),
            )
        }
        return ParseResult.Ok(Video.fromParseResult(obj))
    }

    /**
     * 把 Kotlin 侧 WebView 取到的抖音数据交给引擎入库，返回统一记录。
     *
     * 分工的理由：WebView 只有 Kotlin 侧能用，而历史库和后续下载都在
     * Python 层，所以由 Kotlin 取数、Python 落库。
     */
    fun saveDouyin(context: Context, payloadJson: String): ParseResult {
        val obj = JSONObject(module(context).callAttr("save_douyin", payloadJson).toString())
        if (!obj.optBoolean("ok")) {
            return ParseResult.Err(obj.optString("error").ifBlank { "抖音数据入库失败" })
        }
        return ParseResult.Ok(Video.fromParseResult(obj))
    }

    // ---- 下载 ----

    /**
     * 发起下载。返回错误说明（null 表示已受理）。
     *
     * 注意「已受理」不等于「下载成功」：真正的结果要靠 downloadState() 轮询。
     *
     * @param index 只对图文/图集有意义：0 = 全部，N = 只下第 N 张。
     */
    fun startDownload(context: Context, url: String, index: Int = 0): String? {
        val obj = JSONObject(
            module(context).callAttr("start_download", url, index).toString()
        )
        return if (obj.optBoolean("ok")) null else obj.optString("error")
    }

    fun downloadState(context: Context): Download = Download.fromJson(
        JSONObject(module(context).callAttr("get_download_state").toString())
    )

    // ---- 历史 ----

    fun history(context: Context): List<Video> {
        val arr = JSONArray(module(context).callAttr("get_history").toString())
        return (0 until arr.length()).map { i ->
            val v = Video.fromRow(arr.getJSONObject(i))
            // 本地文件可能已被用户用文件管理器删掉。在这里（IO 线程）统一
            // 校验一次并清掉失效路径，界面层就不必反复 stat 磁盘。
            if (v.localPath.isNotBlank() && !File(v.localPath).exists()) {
                v.copy(localPath = "")
            } else {
                v
            }
        }
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
    /** 下载产物的本地路径。有它就能直接播本地文件 ——
     *  对 B站/YouTube 这类纯 DASH 站点这是唯一能看的方式。 */
    val localPath: String = "",
    /** 以下两项只有解析结果有，历史记录里为空 */
    val quality: String = "",
    val filesize: String = "",
    /** 非空表示走的是备用链路（抖音浏览器模式 / X 第三方服务） */
    val via: String = "",
    /** 内容类型：video（普通视频）/ images（图文·图集）/ live（实况图） */
    val contentType: String = "video",
    /** 图文类的媒体列表（存的是 JSON 文本，用 mediaList 取解析结果） */
    val mediaJson: String = "",
) {
    /** 图文 / 图集 / 实况图：播放器用不上，界面上走图片浏览 */
    val isGallery: Boolean get() = contentType == "images" || contentType == "live"
    /**
     * 解析后的媒体列表。
     *
     * 存 JSON 文本而不是直接存对象，是因为这个类既要从 Python 传回的 JSON 构造，
     * 又要写回历史库 —— 一路都是字符串最省事，只在要展示时才解析这一次。
     */
    val mediaList: List<Media> by lazy {
        if (mediaJson.isBlank()) return@lazy emptyList()
        runCatching {
            val arr = JSONArray(mediaJson)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val u = o.optString("url")
                if (u.isBlank()) return@mapNotNull null
                Media(
                    url = u,
                    width = o.optInt("width"),
                    height = o.optInt("height"),
                    live = o.optString("live"),
                    // X 的媒体列表里可能混着视频，得靠它区分「翻页看图」还是「播放这段」
                    kind = o.optString("kind").ifBlank { "image" },
                )
            }
        }.getOrElse { emptyList() }
    }
    /** 有没有可以直连播放的在线地址。空的话界面要说明为什么播不了。 */
    val playable: Boolean get() = resolvedUrl.isNotBlank() && playKind == "progressive"

    /**
     * 本地文件是否还在。
     *
     * 这里只做「有没有记录」的判断，不 stat 磁盘 —— 历史记录在
     * Engine.history() 里已经统一校验过一遍（那里在 IO 线程），
     * 界面层不该再做文件 IO。
     */
    val hasLocalFile: Boolean get() = localPath.isNotBlank()

    /** 最优先的播放源：本地文件优先于在线直链。
     *
     * 本地文件不怕「直链过期」——B站等站点的直链带时效，隔一段时间就失效，
     * 而本地文件一直在。 */
    val playSource: String?
        get() = when {
            // 图文类的产物是个目录、也没有在线视频流，播放器无从下手
            isGallery -> null
            hasLocalFile -> localPath
            playable -> resolvedUrl
            else -> null
        }

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
            // 解析结果也会带 local_path：先下载过、之后又解析同一条时，
            // 引擎会从历史库把它补回来，这样播放区直接播本地文件，
            // 而不是又提示「需下载后才能播放」。
            localPath = o.optString("local_path"),
            quality = o.optString("quality"),
            filesize = o.optString("filesize"),
            via = o.optString("via"),
            contentType = o.optString("content_type").ifBlank { "video" },
            mediaJson = o.optString("media_json"),
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
            localPath = o.optString("local_path"),
            contentType = o.optString("content_type").ifBlank { "video" },
            mediaJson = o.optString("media_json"),
        )
    }
}

/** 图文类作品里的一条媒体。 */
data class Media(
    val url: String,
    val width: Int = 0,
    val height: Int = 0,
    /** 实况图的「动」那一段视频地址；普通图集为空串 */
    val live: String = "",
    /**
     * "image" 或 "video"。
     *
     * 抖音的图文/图集/实况图都是图片（视频在 live 里），但 X 的一条推文可能
     * 既有多张图又有多个视频 —— 那些视频项要靠这个字段才能被正确播放，
     * 而不是拿去当图片解码。
     */
    val kind: String = "image",
)

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
    /**
     * 这条状态属于哪个视频（原链接）。
     *
     * 下载状态是全局的 —— 同一时刻只有一个任务，且完成后的状态会一直留到
     * 下一条任务开始。界面要拿产物路径去播放时，必须靠这个字段确认
     * 「产物确实属于当前这条视频」，否则会把上一次下载的文件播出来。
     */
    val url: String = "",
    /**
     * Python 侧每次 update 自增的序号。
     *
     * 下载状态是**常驻**的：一条任务完成后，"done" 会一直留在快照里，直到下一条
     * 任务开始。界面必须能分清「这是刚发生的一次完成」还是「早就完成、我只是
     * 又读到一遍」—— 否则会踩两个坑（都实测过）：
     *   1. 用户点掉底部的完成提示条，轮询又把同一个 done 读回来 → 提示条又冒出来；
     *   2. 短任务（如实况图只有 1 张图）两次轮询之间就跑完了，界面从头到尾
     *      没看见过 active=true，于是"完成时通知相册收录"这步被跳过 → 相册里没有。
     * 靠 seq 变化来判定"新的一次完成"，两个问题一起解决。
     */
    val seq: Long = 0L,
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
            url = o.optString("url"),
            seq = o.optLong("seq"),
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
