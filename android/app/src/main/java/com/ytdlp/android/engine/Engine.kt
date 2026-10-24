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

    /**
     * 解析超时（外层兜底）。与 Windows 端 `engine.py` 的 PARSE_TIMEOUT 保持一致。
     *
     * 取 30 秒而不是更短，是因为 YouTube 这类平台要解 JS 挑战（n-sig），
     * 正常也可能用掉 8~15 秒；压到 12 秒会把能解析的作品误判成失败。
     * 国内平台不用等这么久：各自有自己的失败判据（抖音 12 s、小红书 6.4 s、
     * 微博 5.6 s、快手 1~2 s），这个 30 秒只是最后一道保险。
     */
    const val TIMEOUT_MS = 30_000L

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
         * @param needWebview 真表示这个平台要走浏览器模式。
         *   引擎自己取不到数，需要界面拉起 WebView 解析页 —— 这是两边
         *   约定的信号，见 ytdlp_engine._parse_douyin 与 parse_url 里
         *   小红书那段。
         * @param webviewKind 用哪个浏览器解析页：`douyin` / `xhs`。
         *   空串按 douyin 处理（老行为不变）。
         */
        data class Err(
            val message: String,
            val needWebview: Boolean = false,
            val webviewKind: String = "",
        ) : ParseResult
    }

    fun parseUrl(context: Context, url: String): ParseResult {
        val json = module(context).callAttr("parse_url", url).toString()
        val obj = JSONObject(json)
        if (!obj.optBoolean("ok")) {
            return ParseResult.Err(
                message = obj.optString("error").ifBlank { "解析失败" },
                needWebview = obj.optBoolean("need_webview"),
                webviewKind = obj.optString("webview_kind"),
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

    /** 同 [saveDouyin]，只是换成小红书那条入库入口（多一种「图集」内容形态）。 */
    fun saveXhs(context: Context, payloadJson: String): ParseResult {
        val obj = JSONObject(module(context).callAttr("save_xhs", payloadJson).toString())
        if (!obj.optBoolean("ok")) {
            return ParseResult.Err(obj.optString("error").ifBlank { "小红书数据入库失败" })
        }
        return ParseResult.Ok(Video.fromParseResult(obj))
    }

    /** 微博的浏览器模式取数结果入库（图文 / 图集 / 实况图 / 动图）。 */
    fun saveWeibo(context: Context, payloadJson: String): ParseResult {
        val obj = JSONObject(module(context).callAttr("save_weibo", payloadJson).toString())
        if (!obj.optBoolean("ok")) {
            return ParseResult.Err(obj.optString("error").ifBlank { "微博数据入库失败" })
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
     * @param spec  用户在结果区选中的下载档位（[Tier.specJson]）。
     *              空串表示用引擎默认档（档位表第一项），行为与选档功能上线前一致。
     */
    fun startDownload(
        context: Context,
        url: String,
        index: Int = 0,
        spec: String = "",
    ): String? {
        val obj = JSONObject(
            module(context).callAttr("start_download", url, index, spec).toString()
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

    /** 设置「下载完成时自动改写 MD5」，返回最新设置。 */
    fun setRewriteMd5(context: Context, enabled: Boolean): Settings = Settings.fromJson(
        JSONObject(module(context).callAttr("set_rewrite_md5", enabled).toString())
    )

    /** 已下载、且文件还在本地的产物列表（MD5 页的数据源）。 */
    fun downloads(context: Context): List<DownloadItem> {
        val arr = JSONArray(module(context).callAttr("list_downloads").toString())
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(DownloadItem::fromJson) }
    }

    /** 现算某个文件的 MD5（不读库里的缓存值，用户可能自己动过这个文件）。 */
    fun fileMd5(context: Context, path: String): Md5Result = Md5Result.fromJson(
        JSONObject(module(context).callAttr("get_md5", path).toString())
    )

    /**
     * 另存副本式改写 MD5：`src`（缓存里的副本）全程不动，成品写到 `dst`。
     *
     * 全应用只有这一条改 MD5 的对外接口 —— 不论改的是刚下载的产物还是用户从相册
     * 挑来的东西，用户的原文件都不该被动到（下载收尾那次自动改写是引擎内部的另一
     * 条路，见 ytdlp_engine._finish_download）。
     */
    fun editMd5To(context: Context, src: String, dst: String): Md5Result = Md5Result.fromJson(
        JSONObject(module(context).callAttr("edit_md5_to", src, dst).toString())
    )
}

/**
 * 一条视频记录。字段与 Python 侧 history 表的列一一对应，
 * 解析结果和历史记录共用同一个类（两端字段本来就是对齐的）。
 */
data class Video(
    val id: String,
    val platform: String,
    val title: String,
    /**
     * 正文（纯文本）。各平台能拿到原文的都填：公众号文章 / 微博正文与头条文章 /
     * 小红书描述 / B站·YouTube 简介等。原文与标题是同一段话时（抖音、X）
     * 引擎会置空，界面按「没有就不显示」处理。
     */
    val description: String = "",
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
    /**
     * 可选档位全集（存的是 JSON 文本，用 tierList 取解析结果）。
     *
     * 只有视频结果才有：图片 / 图集 / 实况图每张只有一个地址，没有「选规格」的
     * 概念。存 JSON 文本而不是对象，理由与 mediaJson 相同 —— 这个类既要跨语言
     * 传递，又要写进历史库，一路用字符串最省事，只在要展示时才解析这一次。
     */
    val tiersJson: String = "",
    /**
     * 平台在解析结果里**自报**的 MD5（抖音每个档位都带 file_hash）。
     *
     * 有它，界面就能在**下载之前**把 MD5 显示出来，不必等文件下来。它是平台说的
     * 值，不保证等于成品实际算出来的那个（改名、重封装都会让 MD5 变）。
     */
    val sourceMd5: String = "",
    /** 成品文件实际算出来的 MD5；只在下载完成后回填，重新解析不会冲掉它 */
    val fileMd5: String = "",
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
    /**
     * 可选的下载档位，按「好 → 差」排列 —— 第一项即默认选中项。
     *
     * 排序规则由引擎（Python 侧）统一决定，界面不再算一遍，避免两处各排各的。
     * 只有多于一个档位时界面才显示选择器：一个档位等于没得选。
     */
    val tierList: List<Tier> by lazy {
        if (tiersJson.isBlank()) return@lazy emptyList()
        runCatching {
            val arr = JSONArray(tiersJson)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val u = o.optString("url")
                val sel = o.optString("selector")
                // 直链型与格式表达式型至少要有一个，否则这条档位根本没法下载
                if (u.isBlank() && sel.isBlank()) return@mapNotNull null
                Tier(
                    label = o.optString("label").ifBlank { u.ifBlank { sel } },
                    height = o.optInt("height"),
                    vcodec = o.optString("vcodec"),
                    // optLong 在键缺失时返回 0，会把「不知道体积」说成 0 字节
                    filesize = if (o.isNull("filesize")) null else o.optLong("filesize"),
                    url = u,
                    selector = sel,
                    hash = o.optString("hash"),
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
            description = o.optString("description"),
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
            // 档位在解析结果里就是 JSON **数组**，转成文本交给界面。
            // 历史记录（fromRow）没有这一项 —— 档位是解析期的信息，不落库。
            tiersJson = o.optJSONArray("tiers")?.toString() ?: "",
        )

        fun fromRow(o: JSONObject) = Video(
            id = o.optString("id"),
            platform = o.optString("platform"),
            title = o.optString("title"),
            description = o.optString("description"),
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

/**
 * 一个可选的下载档位。
 *
 * 两种形态**二选一**，取决于平台给的是哪种流：
 *   url      直链型 —— 抖音这类接口直接给一条完整 mp4 地址，「选哪个下哪个」
 *   selector yt-dlp 型 —— YouTube / B站 是 DASH 分离流（视频轨 + 音频轨），
 *            单给一条 URL 拿不到完整视频，必须把规格翻译成 yt-dlp 的格式表达式
 *
 * 界面不关心二者差别，只把 [specJson] 交给引擎。
 */
data class Tier(
    /** 直接展示给用户的文案，如「720p · H.265 · 37.4 MB」—— 由引擎侧拼好 */
    val label: String,
    /** 分辨率高度，供「逐条回退」判断用；拿不到就是 0 */
    val height: Int = 0,
    /** "h264" / "h265"；拿不到就是空串 */
    val vcodec: String = "",
    /** 字节数；接口没给就是 null */
    val filesize: Long? = null,
    val url: String = "",
    val selector: String = "",
    /**
     * 平台自报的 MD5（抖音的 `play_addr.file_hash`）；拿不到就是空串。
     *
     * 用它是为了在下载**之前**就能显示 MD5。真伪要等文件下来再核对 ——
     * 平台报的是它自己那份流的摘要。
     */
    val hash: String = "",
) {
    /**
     * 交给引擎的下载规格（JSON 文本）。
     *
     * 用 JSONObject 而不是手拼字符串：直链里带 `&`、`?` 这类字符，
     * 手拼转义很容易漏掉一处，出了错要到下载失败才暴露。
     */
    val specJson: String
        get() = JSONObject().apply {
            if (url.isNotBlank()) put("url", url)
            if (selector.isNotBlank()) put("selector", selector)
        }.toString()
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
    /** 成品文件的 MD5（下载完成时算出）；改过 MD5 时它是**改之前**的值 */
    val md5: String = "",
    /** 改写后的 MD5。空串表示没改（开关关着，或改写失败） */
    val md5After: String = "",
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
            md5 = o.optString("md5"),
            md5After = o.optString("md5_after"),
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
    /** 下载完成时是否自动改写 MD5。默认关（见引擎 set_rewrite_md5 的说明） */
    val rewriteMd5OnDownload: Boolean = false,
) {
    companion object {
        fun fromJson(o: JSONObject) = Settings(
            outputDir = o.optString("output_dir"),
            outputDirIsFallback = o.optBoolean("output_dir_is_fallback"),
            preferredDir = o.optString("preferred_dir"),
            cookiesFile = o.optString("cookies_file"),
            cookiesPresent = o.optBoolean("cookies_present"),
            rewriteMd5OnDownload = o.optBoolean("rewrite_md5_on_download"),
        )
    }
}

/**
 * MD5 页列表里的一条：一个已下载、且文件还在本地的产物。
 *
 * 只列**文件**，不列图文/图集那种目录 —— 那类产物是一整个文件夹，
 * 谈不上「这一个文件的 MD5」（用户要的也只是视频）。
 */
data class DownloadItem(
    val id: String,
    val title: String,
    val platform: String,
    val path: String,
    val filename: String,
    val size: Long,
    /** 平台自报的 MD5；抖音才有，其余平台为空 */
    val sourceMd5: String = "",
    /** 库里记的成品 MD5（下载完成时算的） */
    val fileMd5: String = "",
) {
    companion object {
        fun fromJson(o: JSONObject) = DownloadItem(
            id = o.optString("id"),
            title = o.optString("title"),
            platform = o.optString("platform"),
            path = o.optString("path"),
            filename = o.optString("filename"),
            size = o.optLong("size"),
            sourceMd5 = o.optString("source_md5"),
            fileMd5 = o.optString("file_md5"),
        )
    }
}

/**
 * MD5 操作的结果。查看（get_md5）与改写（edit_md5_to）共用：
 * 查看只用 md5，改写还会带 before / after 与 changed。
 */
data class Md5Result(
    val ok: Boolean,
    val error: String = "",
    val path: String = "",
    /** 查看时是当前值 */
    val md5: String = "",
    /** 改写前的值 */
    val before: String = "",
    /** 改写后的值 */
    val after: String = "",
    /**
     * 字节是否真的变了。
     *
     * 需要它是因为「跑完没报错」不等于「MD5 变了」——实测图片走 ffmpeg 重封装就会产出
     * 与原件逐字节相同的结果。界面据此把这种情况判成失败，而不是显示一个和原值一模
     * 一样的新值让用户以为改成功了。
     */
    val changed: Boolean = false,
) {
    companion object {
        fun fromJson(o: JSONObject) = Md5Result(
            ok = o.optBoolean("ok"),
            error = o.optString("error"),
            path = o.optString("path"),
            md5 = o.optString("md5"),
            before = o.optString("before"),
            after = o.optString("after"),
            changed = o.optBoolean("changed"),
        )
    }
}

/** JSONObject.optInt 在键缺失时返回 0，会把「没有时长」和「0 秒」混为一谈。 */
private fun JSONObject.optIntOrNull(key: String): Int? =
    if (isNull(key)) null else optInt(key)
