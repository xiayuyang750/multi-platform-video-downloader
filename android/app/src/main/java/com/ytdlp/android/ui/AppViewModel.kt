package com.ytdlp.android.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ytdlp.android.BuildConfig
import com.ytdlp.android.engine.Download
import com.ytdlp.android.engine.DownloadItem
import com.ytdlp.android.engine.Engine
import com.ytdlp.android.engine.Settings
import com.ytdlp.android.engine.UpdateChecker
import com.ytdlp.android.engine.Video
import com.ytdlp.android.engine.WeiboParser
import com.ytdlp.android.firstUrl
import com.ytdlp.android.license.DeviceId
import com.ytdlp.android.license.LicenseApi
import com.ytdlp.android.license.LicenseStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** 转 JPEG 时要带过去的 EXIF 字段：拍摄参数、机型、方向，以及宽高。 */
private val EXIF_COPY_TAGS = listOf(
    ExifInterface.TAG_MAKE,
    ExifInterface.TAG_MODEL,
    ExifInterface.TAG_LENS_MAKE,
    ExifInterface.TAG_LENS_MODEL,
    ExifInterface.TAG_DATETIME,
    ExifInterface.TAG_DATETIME_ORIGINAL,
    ExifInterface.TAG_EXPOSURE_TIME,
    ExifInterface.TAG_F_NUMBER,
    ExifInterface.TAG_ISO_SPEED_RATINGS,
    ExifInterface.TAG_EXPOSURE_PROGRAM,
    ExifInterface.TAG_EXPOSURE_MODE,
    ExifInterface.TAG_FOCAL_LENGTH,
    ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
    ExifInterface.TAG_METERING_MODE,
    ExifInterface.TAG_FLASH,
    ExifInterface.TAG_WHITE_BALANCE,
    ExifInterface.TAG_SCENE_CAPTURE_TYPE,
    ExifInterface.TAG_COLOR_SPACE,
    ExifInterface.TAG_SENSING_METHOD,
    ExifInterface.TAG_SOFTWARE,
    ExifInterface.TAG_ORIENTATION,
    ExifInterface.TAG_IMAGE_WIDTH,
    ExifInterface.TAG_IMAGE_LENGTH,
    ExifInterface.TAG_PIXEL_X_DIMENSION,
    ExifInterface.TAG_PIXEL_Y_DIMENSION,
)

/** 解析页的状态。各态互斥，用 sealed 比「一个 result + 一堆 bool」更难写错。 */
sealed interface ParseUi {
    data object Idle : ParseUi
    /**
     * 正在解析。
     *
     * @param hint 这一轮走的是哪条链路。三条链路的耗时和失败原因完全不同，
     *   统一说「正在解析」会让人误以为卡住：X 走的是第三方服务（要出网），
     *   抖音/小红书/微博要拉内置浏览器，其余走内置引擎。
     */
    data class Loading(val hint: String = "") : ParseUi
    data class Done(val video: Video) : ParseUi
    data class Failed(val message: String) : ParseUi

    /**
     * 需要走浏览器模式（抖音、小红书）。
     * 界面收到它就去拉起对应的 WebView 解析页，解析完再回调
     * [onDouyinResult] 或 [onXhsResult]。
     *
     * @param kind 用哪个解析页：`douyin` / `xhs`，来自引擎的 `webview_kind`。
     */
    data class NeedBrowser(
        val url: String,
        val message: String,
        val kind: String = "douyin",
    ) : ParseUi
}

/** 历史页的状态。platform 为 null 表示「全部」标签。 */
data class HistoryUi(
    val loading: Boolean = true,
    val items: List<Video> = emptyList(),
    val platform: String? = null,
) {
    /** 按平台分组计数，用于页签上的徽章 */
    val counts: Map<String, Int> get() = items.groupingBy { it.platform }.eachCount()

    /** 当前标签下要显示的条目 */
    val visible: List<Video>
        get() = if (platform == null) items else items.filter { it.platform == platform }
}

data class SettingsUi(
    val loading: Boolean = true,
    val settings: Settings? = null,
    /** 一次性提示（如「目录已更新」），显示后由界面调 consumeNotice 清掉 */
    val notice: String? = null,
)

/**
 * 授权状态。
 *
 * 未激活时给 3 次免费解析；用完后 [dialog] 变 true，界面弹输入密钥的框。
 * 已激活时 [trialLeft] 不再有意义，界面按 [activated] 显示。
 */
data class LicenseUi(
    val activated: Boolean = false,
    val trialLeft: Int = LicenseStore.TRIAL_TOTAL,
    val dialog: Boolean = false,
    val working: Boolean = false,
    val error: String = "",
    /** 上次成功用过的密钥。弹框时预填，省得用户重新翻聊天记录 */
    val savedKey: String = "",
    val notice: String? = null,
)

/** MD5 流程的三个阶段：选媒体 → 详情 → 结果。 */
enum class Md5Stage { Entry, Detail, Result }

/** 详情页 / 结果页要展示的那一份媒体。 */
data class Md5FileUi(
    /** 详情页里是缓存中的原件副本；结果页里是改后的成品 */
    val path: String,
    /** video / image */
    val kind: String,
    val name: String,
    val size: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    /** 详情页 = 原件的 MD5；结果页 = 改后的 MD5 */
    val md5: String,
    /** 改之前的 MD5。只有结果页非空 */
    val md5Before: String = "",
    /** 改之前的大小。结果页要把旧值划掉、新值跟在后面，所以得记住旧的那一份 */
    val sizeBefore: Long = 0,
)

/**
 * MD5 页的状态：一条「选媒体 → 详情 → 结果」的流程，外加一份「已下载视频」次要列表。
 *
 * 流程状态放 ViewModel 而不是界面里：一是改写要等几秒到十几秒，转屏或界面重建
 * 不该把它打断；二是「选中的是哪一份、改到哪一步了」本来就不属于某一屏的临时状态。
 */
data class Md5Ui(
    val loading: Boolean = true,
    /** 已下载、且文件还躺在原地的产物。点一条会进同一条流程 */
    val items: List<DownloadItem> = emptyList(),
    val stage: Md5Stage = Md5Stage.Entry,
    val picked: Md5FileUi? = null,
    val result: Md5FileUi? = null,
    /** 复制 / 探测 / 改写 / 保存进行中，界面据此禁用按钮 */
    val working: Boolean = false,
    /** 出错说明（读不到文件、改写失败…），界面直接显示原文 */
    val error: String = "",
    /** 一次性提示（如「已保存到 …」），显示后由界面调 consumeMd5Notice 清掉 */
    val notice: String? = null,
)

/**
 * 「实况」页的状态：一张图 + 一段视频 + 输出格式开关。
 *
 * 页面只做三件事（选图 / 选视频 / 保存），所以状态很浅；真正的工作在
 * [LivePhotoSynth] 里，全部是纯文件操作，不依赖网络也不依赖引擎。
 */
data class LiveUi(
    val imagePath: String = "",
    val imageLabel: String = "",
    val videoPath: String = "",
    val videoLabel: String = "",
    /**
     * 每次重选自增。缩略图必须靠它判断「素材换过了」——
     * 缓存副本按槽位固定命名（永远是 `video.mp4`），路径不变时 Compose 会认
     * 两次参数完全相同而**跳过重组**，抽帧的 key 也就不会重新计算，旧缩略图会一直留着。
     */
    val imageRev: Int = 0,
    val videoRev: Int = 0,
    /**
     * 输出文件名前缀，用户可改。留空时用「原图名_实况」——
     * 比纯时间戳可读：用户导出后一眼知道它来自哪张图。
     */
    val namePrefix: String = "",
    /**
     * false（默认）= 同名配对 + 写 `live_photo` 字段，vivo / 红米这条已实测；
     * true = 单文件（JPEG 内嵌 MP4 + XMP），小米 / OPPO / 华为 / 原生走这条。
     */
    val singleFile: Boolean = false,
    val working: Boolean = false,
    val error: String = "",
    val notice: String? = null,
    /** 上一次成功的产物路径。非空时界面显示「查看」按钮。 */
    val savedPath: String = "",
)

/** 检查更新的状态。失败原因由 UpdateChecker 侧翻译成中文，界面直接用。 */
sealed interface UpdateUi {
    data object Idle : UpdateUi
    data object Checking : UpdateUi
    data class Available(val version: String, val url: String, val notes: String) : UpdateUi
    data object UpToDate : UpdateUi
    data class Failed(val reason: String) : UpdateUi
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Application get() = getApplication()

    private val _parse = MutableStateFlow<ParseUi>(ParseUi.Idle)
    val parse: StateFlow<ParseUi> = _parse.asStateFlow()

    private val _history = MutableStateFlow(HistoryUi())
    val history: StateFlow<HistoryUi> = _history.asStateFlow()

    private val _settings = MutableStateFlow(SettingsUi())
    val settings: StateFlow<SettingsUi> = _settings.asStateFlow()

    private val _md5 = MutableStateFlow(Md5Ui())
    val md5: StateFlow<Md5Ui> = _md5.asStateFlow()

    private val _live = MutableStateFlow(LiveUi())
    val live: StateFlow<LiveUi> = _live.asStateFlow()

    private val _download = MutableStateFlow(
        Download("idle", false, null, "", "", 0, 0, 0.0, null)
    )
    val download: StateFlow<Download> = _download.asStateFlow()

    private val _url = MutableStateFlow("")
    val url: StateFlow<String> = _url.asStateFlow()

    private val _update = MutableStateFlow<UpdateUi>(UpdateUi.Idle)
    val update: StateFlow<UpdateUi> = _update.asStateFlow()

    private val _needStoragePermission = MutableStateFlow(false)

    /** 真表示还没拿到「所有文件访问权限」，界面据此显示引导条。 */
    val needStoragePermission: StateFlow<Boolean> = _needStoragePermission.asStateFlow()

    /** 外观模式。初值直接读持久化，所以重建界面时不会先闪一下默认色再跳成用户的偏好。 */
    private val _themeMode = MutableStateFlow(ThemePrefs.load(ctx))
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val licenseStore = LicenseStore(ctx)

    private val _license = MutableStateFlow(
        LicenseUi(
            activated = licenseStore.activated,
            trialLeft = licenseStore.trialLeft,
        )
    )
    val license: StateFlow<LicenseUi> = _license.asStateFlow()

    /**
     * 本次解析是否已经扣过试用次数。
     *
     * 一次解析可能分两段完成（先 NeedBrowser、再 WebView 回来入库），
     * 不能两段各扣一次，否则用户点一次就少两次。
     */
    private var trialCharged = false

    // 注意：上面这些 StateFlow 必须声明在 init 块**之前**。
    // Kotlin 按代码顺序初始化属性，而 init 里会调用 refreshStoragePermission()，
    // 若把 _needStoragePermission 写在文件末尾，init 执行时它还是 null，
    // 会抛 "Attempt to invoke ... setValue on a null object reference"。
    private var parseJob: Job? = null

    /**
     * 已经通知过相册收录的那次「完成」（用 Download.seq 标识）。
     *
     * 不能靠「上一轮还 active、这一轮变 done」来判断完成 —— 短任务（如实况图只有
     * 一张图）在两次轮询之间就跑完了，界面从未见过 active=true，扫描就被整段跳过，
     * 结果就是文件明明下好了、相册里却没有（实测：图集能进相册，实况图不能）。
     * 改看 seq：只要是没见过的新 seq 且已完成，就收录一次。
     */
    private var scannedSeq: Long = -1L

    /**
     * 用户点掉过的下载条对应的 seq。
     *
     * 下载状态是常驻的，轮询会把同一条 done 反复读回来；不记这一笔，用户点掉
     * 提示条后过一两秒它又会冒出来（实测到的问题）。
     */
    private var dismissedSeq: Long = -1L

    init {
        // 先把 Python 起来，免得用户点「解析」时先白等 1 秒
        viewModelScope.launch(Dispatchers.IO) { Engine.warmUp(ctx) }
        refreshHistory()
        refreshSettings()
        refreshStoragePermission()
        startPollingDownload()
        verifyLicenseInBackground()
    }

    // ---- 输入 ----

    fun onUrlChange(value: String) {
        _url.value = value
    }

    /**
     * 一键粘贴：从剪贴板取文本并挑出其中的链接。
     *
     * 与 Windows 端行为一致。抖音/B站 的「复制链接」给的是整段分享文案
     * （「4.69 :7pm ... https://v.douyin.com/xxx/ 复制此链接...」），
     * 不提取的话直接去解析必然失败。
     */
    fun pasteFromClipboard() {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
        if (text.isBlank()) return
        _url.value = firstUrl(text) ?: text.trim()
    }

    // ---- 解析 ----

    fun parse() {
        val raw = _url.value.trim()
        if (raw.isEmpty()) {
            _parse.value = ParseUi.Failed("请先粘贴视频链接")
            return
        }
        // 支持粘贴整段分享文案：先从里面挑出链接。用户很可能直接复制抖音
        // 的分享文本，里面混着标题和引导语，不提取就会被当成非法 URL。
        val target = firstUrl(raw) ?: raw
        if (target != raw) _url.value = target
        if (parseJob?.isActive == true) return
        // 授权门禁：试用次数用完且未激活时，直接弹输入密钥的框，不发起解析
        if (!licenseGate()) return
        trialCharged = false
        launchParse(target)
    }

    /**
     * 这一轮解析大概会走哪条链路，用来决定转圈时显示哪句话。
     *
     * 判断只能在**发起前**按域名猜：真正的路由是引擎在 Python 里决定的，
     * 而那边是阻塞调用、中途不会回调。所以这里只做「最可能的那条」，
     * 猜错的代价也只是文案不精确，不影响结果。
     */
    private fun loadingHint(url: String): String {
        val u = url.lowercase()
        return when {
            // X 走的是 FxTwitter 这类第三方服务：要出网、和内置引擎无关，
            // 失败原因也不一样（服务限流 / 401），文案不能混在一起说
            u.contains("x.com/") || u.contains("twitter.com/") ->
                "正在通过第三方解析服务…"
            // 视频号只调一个公开接口读预览信息，没有视频流可下
            u.contains("weixin.qq.com/sph") ->
                "正在读取视频号公开信息…"
            // 抖音**不走 yt-dlp**：它调的是 aweme.snssdk.com 的公开作品接口，
            // 拿的是平台直链。这里要是也说「内置解析引擎」，用户会以为和
            // B站/YouTube 是同一条路，失败时的排查方向也就不一样了。
            u.contains("douyin.com") || u.contains("iesdouyin.com") ->
                "正在直接读取抖音作品数据…"
            // 快手同理：走的是移动版分享页的服务端数据，和 yt-dlp 无关。
            // 用「间接」而不是「直接」是有意的：抖音那条是一次接口调用直接拿到
            // mp4 地址，快手要先把整个网页抓下来、再从页面里挖数据 —— 两条路
            // 的脆弱程度和失败原因都不一样，文案不该混为一谈。
            u.contains("kuaishou.com") || u.contains("chenzhongtech.com") ->
                "正在间接读取快手作品数据…"
            // 小红书 / 微博：引擎会立刻回「要用浏览器」，真正干活的是 WebView
            u.contains("xhslink") || u.contains("xiaohongshu.com") ||
                u.contains("weibo.com") || u.contains("weibo.cn") ->
                "正在打开内置浏览器…"
            else -> "正在使用内置解析引擎…"
        }
    }

    /**
     * 真正发起解析。
     *
     * 拆出来是因为「正文里挂视频」那条路要**接着上一次解析**再跑一遍：那属于同一次
     * 用户操作，不能再过一次授权门禁、也不能把 `trialCharged` 重置后重复扣次数。
     */
    private fun launchParse(target: String) {
        _parse.value = ParseUi.Loading(loadingHint(target))
        parseJob = viewModelScope.launch {
            // Python 调用是阻塞的，withTimeoutOrNull 拦不住它 —— 超时后界面
            // 会立刻收到「超时」并恢复可用，而 Python 那边仍会跑完再把结果丢掉。
            // 这是进程内调用无法避免的代价（没有子进程可 kill）。
            val result = withTimeoutOrNull(Engine.TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    runCatching { Engine.parseUrl(ctx, target) }
                }
            }
            _parse.value = when {
                result == null -> ParseUi.Failed(
                    "解析超时（${Engine.TIMEOUT_MS / 1000} 秒）。" +
                        "境外站点（YouTube / Instagram / X）需要开着 VPN。"
                )
                result.isFailure -> ParseUi.Failed(
                    "引擎异常：${result.exceptionOrNull()?.message ?: "未知错误"}"
                )
                else -> when (val r = result.getOrThrow()) {
                    is Engine.ParseResult.Ok -> {
                        // 解析成功才扣试用次数：失败不扣，避免用户白白损失一次
                        chargeTrialOnce()
                        // 解析成功会把记录写进历史库，这里顺手刷新，
                        // 用户切到历史页就能看到，不用手动下拉
                        refreshHistory()
                        ParseUi.Done(r.info)
                    }
                    is Engine.ParseResult.Err -> if (r.needWebview) {
                        // 抖音 / 小红书：交给界面的 WebView 解析页继续
                        chargeTrialOnce()
                        ParseUi.NeedBrowser(
                            target,
                            r.message,
                            r.webviewKind.ifBlank { "douyin" },
                        )
                    } else {
                        ParseUi.Failed(r.message)
                    }
                }
            }
        }
    }

    /**
     * 抖音 WebView 解析页返回后的回调。
     *
     * @param payloadJson 取到的数据（null 表示没取到）
     * @param reason 没取到时的原因，由解析页给出
     */
    fun onDouyinResult(payloadJson: String?, reason: String) = onWebviewResult(
        payloadJson, reason, "浏览器模式没能取到视频数据。",
    ) { Engine.saveDouyin(ctx, it) }

    /**
     * 小红书 WebView 解析页返回后的回调。
     *
     * 与抖音那条的区别只有「交给引擎哪个入库入口」（小红书那条多一种图集形态），
     * 后续处理完全一样，所以两边共用一个实现。
     */
    fun onXhsResult(payloadJson: String?, reason: String) = onWebviewResult(
        payloadJson, reason, "浏览器模式没能取到这条笔记的数据。",
    ) { Engine.saveXhs(ctx, it) }

    /**
     * 微博 WebView 解析页返回后的回调。
     *
     * 与上面两条的差别有两处：
     *   1. 交给引擎的入库入口是 `saveWeibo`；
     *   2. 多一种返回值 —— 正文里挂的是视频链接时，解析页会回传目标 URL，
     *      这里接着用这条 URL 再解析一次（视频页走 yt-dlp，已验证可用）。
     */
    fun onWeiboResult(payloadJson: String?, reason: String) {
        val redirect = payloadJson
            ?.let {
                runCatching {
                    org.json.JSONObject(it).optString(WeiboParser.PAYLOAD_REDIRECT)
                }.getOrNull()
            }
            .orEmpty()
        if (redirect.isNotBlank()) {
            _url.value = redirect
            launchParse(redirect)
            return
        }
        onWebviewResult(payloadJson, reason, "浏览器模式没能取到这条微博的数据。") {
            Engine.saveWeibo(ctx, it)
        }
    }

    private fun onWebviewResult(
        payloadJson: String?,
        reason: String,
        fallbackReason: String,
        save: (String) -> Engine.ParseResult,
    ) {
        if (payloadJson == null) {
            _parse.value = ParseUi.Failed(reason.ifBlank { fallbackReason })
            return
        }
        // 浏览器已经取到数据，这一步只是入库（图片/视频地址整理、写历史），
        // 不再走网络，所以文案直说「正在整理解析结果」而不是继续转圈。
        _parse.value = ParseUi.Loading("正在整理解析结果…")
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { save(payloadJson) }
            }
            _parse.value = when {
                result.isFailure -> ParseUi.Failed(
                    "入库失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                )
                else -> when (val r = result.getOrThrow()) {
                    is Engine.ParseResult.Ok -> {
                        chargeTrialOnce()
                        refreshHistory()
                        ParseUi.Done(r.info)
                    }
                    is Engine.ParseResult.Err -> ParseUi.Failed(r.message)
                }
            }
        }
    }

    /** 清掉解析结果（回到初始态）。 */
    fun clearParse() {
        parseJob?.cancel()
        parseJob = null
        _parse.value = ParseUi.Idle
    }

    // ---- 下载 ----

    /**
     * 发起下载。
     *
     * @param index 只对图文/图集有意义：0 = 全部，N = 只下第 N 张。
     * @param spec  用户在结果区选中的下载档位（Tier.specJson）。
     *              空串表示用引擎默认档（档位表第一项）。历史记录里的条目没有档位
     *              （档位是解析期的信息、不落库），所以走历史页下载时它必然是空串。
     */
    fun startDownload(video: Video, index: Int = 0, spec: String = "") {
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching { Engine.startDownload(ctx, video.sourceUrl, index, spec) }
                    .getOrElse { "引擎异常：${it.message}" }
            }
            if (error != null) {
                _download.value = _download.value.copy(
                    type = "error", active = false, message = error
                )
            }
        }
    }

    /**
     * 常驻轮询下载状态。
     *
     * 为什么不用回调：Python 侧是在子线程里跑下载的，Chaquopy 没有
     * 「从 Python 反调 Kotlin」的现成通道；而 get_download_state 只是读一个
     * 内存里的字典，跨语言调用开销极小，轮询是最省事且最好排查的做法
     * （Windows 端也是轮询，两端行为一致）。
     *
     * 空闲时把间隔拉长到 2 秒：绝大多数时间没有下载任务，没必要每秒都过界。
     */
    private fun startPollingDownload() {
        viewModelScope.launch {
            while (isActive) {
                val state = withContext(Dispatchers.IO) {
                    runCatching { Engine.downloadState(ctx) }.getOrNull()
                }
                if (state != null) {
                    // 用户点掉过的那条状态（或更早的）不再显示；常驻的 done 被
                    // 反复读回来也不该让提示条复活。
                    if (state.seq > dismissedSeq) {
                        _download.value = state
                    }
                    // 新的「完成」→ 通知相册收录一次（详见 scannedSeq 的说明）
                    if (state.isDone && state.seq != scannedSeq) {
                        scannedSeq = state.seq
                        refreshHistory()
                        notifyMediaScanner(state.path)
                    }
                }
                delay(if (_download.value.active) 700 else 2000)
            }
        }
    }

    /** 用户点掉下载条。记下 seq：否则轮询会把常驻的同一条状态又显示回来。 */
    fun dismissDownload() {
        dismissedSeq = _download.value.seq
        _download.value = _download.value.copy(type = "idle", active = false)
    }

    /**
     * 下载完成后通知系统媒体扫描器收录这个文件。
     *
     * 为什么必须有这一步：yt-dlp 是拿普通文件 API 直接写盘的，系统并不知道
     * 多了一个媒体文件 —— MediaStore 不会自动收录。结果就是文件明明在
     * 「下载」目录里、应用内也能播，但相册/图库（以及依赖 MediaStore 的应用）
     * 看不到它。实测发现的症状是「有的能看见、有的看不见」：能不能被收录
     * 取决于是否有别的事件（重启、挂载、别的应用扫描）碰巧触发了扫描。
     *
     * scanFile 只是发起一次扫描请求，是异步的且不阻塞；失败（比如文件落在
     * 应用私有目录、系统没权限扫）也只是不收录，不影响下载本身。
     */
    private fun notifyMediaScanner(path: String) {
        if (path.isBlank()) return
        // 目录里可能有几十张图要转码，不能在主线程做
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val f = File(path)
                val files = if (f.isDirectory) {
                    (f.listFiles() ?: emptyArray()).filter { it.isFile }
                } else {
                    listOf(f)
                }
                // WebP 先转成真 JPEG 再交给相册，原因见 convertWebpToJpeg
                val targets = files.map { file ->
                    if (file.extension.equals("webp", true)) {
                        runCatching { convertWebpToJpeg(file) }.getOrDefault(file).absolutePath
                    } else {
                        file.absolutePath
                    }
                }
                if (targets.isEmpty()) return@runCatching
                // 顺序不能换：先给配对图片追加 vivo 私有段（id 在这里定下），
                // 再扫描入库（_size 才对得上），最后拿同一批 id 写媒体库字段。
                val pairs = if (f.isDirectory) prepareLivePhotos(f) else emptyList()
                // 扫完（每个文件回调一次）再登记实况图：登记要改 MediaStore 里的记录，
                // 而记录是扫描之后才存在的，所以必须等它扫完。
                val left = AtomicInteger(targets.size)
                MediaScannerConnection.scanFile(ctx, targets.toTypedArray(), null) { _, _ ->
                    if (left.decrementAndGet() == 0) applyLivePhotos(pairs)
                }
            }
        }
    }

    /**
     * 把 WebP 图片转成真正的 JPEG，并把能读到的 EXIF 原样带过去。
     *
     * 为什么要多这一步：小红书下发的图是 WebP。存成 `.jpg` 时相册按扩展名当
     * JPEG 解析，分辨率显示 0×0；改存成 `.webp` 后分辨率正常了，但相册的参数
     * 面板不解析 WebP 里的 EXIF，机型、ISO 等信息变成空白。只有真正的 JPEG
     * 两头都对。
     *
     * 转换只发生在下载完成、通知相册收录之前：像素按 95 质量重新编码，宽高
     * 以解码结果为准写回 EXIF；方向字段原样保留，相册照旧会自动转正。
     * 转换失败就保留原 WebP，不影响下载结果。
     */
    private fun convertWebpToJpeg(src: File): File {
        val dst = File(src.parentFile, src.nameWithoutExtension + ".jpg")
        // 同名 JPEG 已存在说明这批名字被占用了，宁可不动，避免覆盖用户已有文件
        if (dst.exists()) return src
        val bmp = BitmapFactory.decodeFile(src.absolutePath) ?: return src
        try {
            FileOutputStream(dst).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            val srcExif = ExifInterface(src.absolutePath)
            val dstExif = ExifInterface(dst.absolutePath)
            EXIF_COPY_TAGS.forEach { tag ->
                srcExif.getAttribute(tag)?.let { dstExif.setAttribute(tag, it) }
            }
            // 宽高必须和实际像素一致 —— EXIF 里原来的值往往是平台压缩前的尺寸
            dstExif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, bmp.width.toString())
            dstExif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, bmp.height.toString())
            dstExif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, bmp.width.toString())
            dstExif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, bmp.height.toString())
            dstExif.saveAttributes()
        } catch (_: Exception) {
            dst.delete()
            return src
        } finally {
            bmp.recycle()
        }
        if (dst.length() <= 0) {
            dst.delete()
            return src
        }
        src.delete()
        return dst
    }


    /**
     * 扫描前把目录里「同名的图片 + 视频」准备好：每一对生成独立 id，并把 id 写进
     * 图片尾部的 vivo 私有段；返回配对表，等扫描入库后再拿同一批 id 写媒体库字段。
     *
     * 为什么光有同名文件还不够：实测（vivo）发现相册读的是 MediaStore 里一个厂商
     * 私有字段 `live_photo`，而且**图片记录和视频记录两边都要有值**才算数 ——
     * 只写一边，相册照样显示成普通图片。
     *
     * 2026-10-25 实测补充：vivo 的实况是**双写**的，除了媒体库字段，JPEG 尾部还有
     * 一段厂商私有数据（见 [LivePhotoSynth.appendVivoTail]）。只写字段时相册能播，
     * 但详情页没有「动态照片 / 关闭动态照片」菜单，微信/抖音/小红书也认不出来。
     * 所以这里先把文件尾写好，再去扫描。
     *
     * 图片扩展名**不能只认 `.jpg`**：实况图的图片扩展名是从抖音地址里推出来的，
     * 抖音给的是 `.jpg` 还是 `.jpeg` 并不固定（实测同一个账号下两种都有）。
     * 原先这里写死 `== "jpg"`，于是拿到 `.jpeg` 的实况图会被整条跳过 ——
     * 表现就是「相册里只剩一张静态图 + 一段视频」，而且**不报错**（静默失效）。
     * 所以这里放开成 JPEG 家族 + 抖音地址模板里出现过的 webp。
     *
     * 这个字段名是厂商私有的，换品牌未必叫这个，所以整段是「能写就写」：
     * 写不上就安静跳过 —— 文件本身该在还在，不影响任何别的功能。
     */
    private fun prepareLivePhotos(dir: File): List<Triple<File, File, String>> {
        val pairs = mutableListOf<Triple<File, File, String>>()
        runCatching {
            val imageExts = setOf("jpg", "jpeg", "webp", "png")
            dir.listFiles()
                ?.filter { it.isFile && it.extension.lowercase() in imageExts }
                ?.forEach { image ->
                    // 配对靠「同名」：图片与那段动效视频同名，只有扩展名不同
                    val mp4 = File(dir, image.nameWithoutExtension + ".mp4")
                    if (!mp4.exists()) return@forEach
                    val id = nextLivePhotoId()
                    // 先写文件尾的私有段，再交给扫描：媒体库的 _size 是扫描时读的
                    LivePhotoSynth.appendVivoTail(image, id)
                    pairs += Triple(image, mp4, id)
                }
        }
        return pairs
    }

    /** 扫描完成后再写媒体库字段：id 必须与图片尾部那段私有数据里的完全一致。 */
    private fun applyLivePhotos(pairs: List<Triple<File, File, String>>) {
        pairs.forEach { (image, mp4, id) -> stampLivePhotoPair(image, mp4, id) }
    }

    /**
     * 每一对必须有**各不相同**的 id。
     *
     * vivo 相册是按「图片与视频同名」配对的，整个目录共用一个值它也能正常播；
     * 但微信等第三方是按这个字段去找配对视频的 —— 同目录十几张共用一个值，
     * 它们就全部指向第一条视频（真机现象：发出去的实况图都播第一张的动效）。
     */
    private var livePhotoSeed = System.currentTimeMillis()

    private fun nextLivePhotoId(): String = "${livePhotoSeed++}000000000000000"

    private fun stampLivePhotoPair(image: File, mp4: File, stamp: String) {
        val imageId = mediaId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, image)
            ?: return
        val mp4Id = mediaId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, mp4)
            ?: return
        putLivePhoto(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageId, stamp)
        putLivePhoto(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, mp4Id, stamp)
    }

    /** 按文件路径查出它在 MediaStore 里的 _id。查不到（还没扫进来）返回 null。 */
    private fun mediaId(collection: Uri, file: File): Long? {
        ctx.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DATA} = ?",
            arrayOf(file.absolutePath),
            null,
        )?.use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        return null
    }

    private fun putLivePhoto(collection: Uri, id: Long, stamp: String) {
        val values = ContentValues().apply { put("live_photo", stamp) }
        ctx.contentResolver.update(
            collection,
            values,
            "${MediaStore.MediaColumns._ID} = ?",
            arrayOf(id.toString()),
        )
    }

    // ---- 历史 ----

    fun refreshHistory() {
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                runCatching { Engine.history(ctx) }.getOrElse { emptyList() }
            }
            _history.value = _history.value.copy(loading = false, items = items)
        }
    }

    fun selectPlatform(platform: String?) {
        _history.value = _history.value.copy(platform = platform)
    }

    fun deleteHistory(video: Video) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { Engine.deleteHistory(ctx, video.id) }
            }
            refreshHistory()
        }
    }

    /**
     * 复制到剪贴板。历史页和结果卡片都要用。
     *
     * 手机上「复制链接」是很常用的动作 —— 想把链接发给别人、或换台设备再解析。
     */
    fun copyText(text: String) {
        if (text.isBlank()) return
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("链接", text))
    }

    /**
     * 重新解析一条历史记录 —— 直链是带时效的，过一段时间就播不了/下不动，
     * 需要重新取一次地址。对应 Windows 端的 reanalyze。
     *
     * 之所以切到解析页而不是原地刷新：解析结果和失败原因都需要完整展示，
     * 历史页那一行放不下这些信息。
     */
    fun reanalyze(video: Video) {
        _url.value = video.sourceUrl
        _openParse.value = true
        parse()
    }

    // 界面消费完就清掉，避免转屏后又自动跳一次
    private val _openParse = MutableStateFlow(false)
    val openParse: StateFlow<Boolean> = _openParse.asStateFlow()

    fun consumeOpenParse() {
        _openParse.value = false
    }

    fun clearHistory(platform: String?) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    if (platform == null) Engine.clearAllHistory(ctx)
                    else Engine.clearHistory(ctx, platform)
                }
            }
            if (platform != null && _history.value.platform == platform) {
                _history.value = _history.value.copy(platform = null)
            }
            refreshHistory()
        }
    }

    // ---- 设置 ----

    fun refreshSettings() {
        viewModelScope.launch {
            val s = withContext(Dispatchers.IO) {
                runCatching { Engine.settings(ctx) }.getOrNull()
            }
            _settings.value = SettingsUi(loading = false, settings = s)
        }
    }

    fun setOutputDir(path: String) {
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching { Engine.setOutputDir(ctx, path) }
                    .getOrElse { "引擎异常：${it.message}" }
            }
            val fresh = withContext(Dispatchers.IO) {
                runCatching { Engine.settings(ctx) }.getOrNull()
            }
            _settings.value = SettingsUi(
                loading = false,
                settings = fresh,
                notice = error ?: "保存位置已更新",
            )
        }
    }

    fun setCookiesFile(path: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { Engine.setCookiesFile(ctx, path) }
            }
            val fresh = withContext(Dispatchers.IO) {
                runCatching { Engine.settings(ctx) }.getOrNull()
            }
            _settings.value = SettingsUi(
                loading = false,
                settings = fresh,
                notice = if (path.isBlank()) "已清除 Cookie 文件" else "Cookie 文件已导入",
            )
        }
    }

    fun consumeNotice() {
        _settings.value = _settings.value.copy(notice = null)
    }

    // ---- MD5 ----

    /** 刷新已下载产物列表。切到 MD5 页时调一次即可（不必在启动时白做一遍）。 */
    fun refreshDownloads() {
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                runCatching { Engine.downloads(ctx) }.getOrDefault(emptyList())
            }
            _md5.value = _md5.value.copy(loading = false, items = items)
        }
    }

    /**
     * 从相册 / 文件选择器选中的一项开始处理。
     *
     * 三步都在 IO 线程上做：复制整份文件、读时长分辨率、算一次 MD5 —— 相册里一段
     * 几十秒的 4K 视频就够让主线程卡住。
     */
    fun pickMd5(uri: Uri) {
        loadIntoEditor { Md5Media.pickFromUri(ctx, uri) }
    }

    /** 从「已下载」列表里点的一条开始处理。同样先复制进缓存，原文件不动。 */
    fun pickMd5FromPath(path: String) {
        loadIntoEditor { Md5Media.pickFromFile(ctx, File(path)) }
    }

    /**
     * 复制 → 探测 → 算原 MD5，成功后进详情页。
     *
     * [pick] 是个「把选中项变成缓存副本」的动作，两条入口（相册选择器 / 已下载列表）
     * 只在这件事上不同，后面的处理完全一样，所以两条入口共用这一段。
     */
    private fun loadIntoEditor(pick: () -> Md5Media.Picked) {
        if (_md5.value.working) return
        viewModelScope.launch {
            _md5.value = _md5.value.copy(working = true, error = "", notice = null)
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val picked = pick()
                    val info = Md5Media.probe(picked.file, picked.kind)
                    val md5 = Engine.fileMd5(ctx, picked.file.absolutePath)
                    Md5FileUi(
                        path = picked.file.absolutePath,
                        kind = picked.kind,
                        name = picked.file.name,
                        size = info.size,
                        durationMs = info.durationMs,
                        width = info.width,
                        height = info.height,
                        md5 = if (md5.ok) md5.md5 else "",
                    )
                }
            }
            _md5.value = r.fold(
                onSuccess = {
                    _md5.value.copy(
                        working = false, error = "", stage = Md5Stage.Detail,
                        picked = it, result = null,
                    )
                },
                onFailure = {
                    _md5.value.copy(working = false, error = it.message ?: "读不到这个文件")
                },
            )
        }
    }

    /**
     * 改写 MD5：原件（picked.path，即缓存里那份副本）不动，成品写到工作目录的 out/ 下。
     *
     * 详情页的「原 MD5」与结果页的「改后 MD5」都来自引擎的返回值 —— 不自己再算一遍，
     * 免得界面显示的值和实际写进文件的值对不上。
     */
    fun runMd5Edit() {
        val picked = _md5.value.picked ?: return
        if (_md5.value.working) return
        viewModelScope.launch {
            _md5.value = _md5.value.copy(working = true, error = "", notice = null)
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val dst = Md5Media.outFile(ctx, picked.name)
                    val res = Engine.editMd5To(ctx, picked.path, dst.absolutePath)
                    if (!res.ok) throw IllegalStateException(res.error.ifBlank { "改写失败" })
                    if (!res.changed) throw IllegalStateException("没能改变这份文件的 MD5")
                    // 成品的大小 / 时长 / 分辨率要重新探一次：改写过一遍，这几个值
                    // 本来就可能和原件差一点点（重封装会让容器多出几个字节）
                    val info = Md5Media.probe(dst, picked.kind)
                    picked.copy(
                        path = dst.absolutePath,
                        size = info.size,
                        durationMs = info.durationMs,
                        width = info.width,
                        height = info.height,
                        md5 = res.after,
                        md5Before = res.before.ifBlank { picked.md5 },
                        sizeBefore = picked.size,
                    )
                }
            }
            _md5.value = r.fold(
                onSuccess = {
                    _md5.value.copy(
                        working = false, error = "", stage = Md5Stage.Result, result = it,
                    )
                },
                onFailure = { _md5.value.copy(working = false, error = it.message ?: "改写失败") },
            )
        }
    }

    /**
     * 保存成品：复制进输出目录，并让相册收录它。
     *
     * 输出目录问引擎要，不自己拼路径 —— 没拿到存储权限时引擎会把目录退到应用私有
     * 目录，界面照它说的显示，用户才知道该去哪儿找。
     */
    fun saveMd5Result() {
        val result = _md5.value.result ?: return
        if (_md5.value.working) return
        viewModelScope.launch {
            _md5.value = _md5.value.copy(working = true, error = "", notice = null)
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = Engine.settings(ctx).outputDir
                    Md5Media.save(ctx, File(result.path), dir)
                }
            }
            _md5.value = r.fold(
                onSuccess = {
                    _md5.value.copy(working = false, notice = "已保存到 ${it.absolutePath}")
                },
                onFailure = { _md5.value.copy(working = false, error = it.message ?: "保存失败") },
            )
        }
    }

    /** 回到入口页（重选视频 / 从结果页退回）。缓存里的副本留着，下一轮开始时统一清。 */
    fun resetMd5Flow() {
        _md5.value = _md5.value.copy(
            stage = Md5Stage.Entry, picked = null, result = null,
            working = false, error = "",
        )
    }

    /** 一次性提示显示完就清掉，避免转屏后又弹一次。 */
    fun consumeMd5Notice() {
        _md5.value = _md5.value.copy(notice = null)
    }

    // ==================== 实况图合成 ====================
    // 与 MD5 流程完全独立：自己用 cacheDir/live 存副本，不调用 Md5Media 的那些
    // 「开始新任务就清空缓存」的入口（否则先选图再选视频会把图清掉）。

    fun pickLiveImage(uri: Uri) = pickLive(uri, video = false)

    fun pickLiveVideo(uri: Uri) = pickLive(uri, video = true)

    private fun pickLive(uri: Uri, video: Boolean) {
        if (_live.value.working) return
        viewModelScope.launch {
            _live.value = _live.value.copy(working = true, error = "", notice = null)
            val r = withContext(Dispatchers.IO) {
                runCatching { LivePhotoSynth.pick(ctx, uri, if (video) "video" else "image") }
            }
            _live.value = r.fold(
                onSuccess = { p ->
                    if (video) {
                        _live.value.copy(
                            working = false,
                            videoPath = p.file.absolutePath,
                            videoLabel = p.label,
                            videoRev = _live.value.videoRev + 1,
                        )
                    } else {
                        _live.value.copy(
                            working = false,
                            imagePath = p.file.absolutePath,
                            imageLabel = p.label,
                            imageRev = _live.value.imageRev + 1,
                        )
                    }
                },
                onFailure = {
                    _live.value.copy(working = false, error = it.message ?: "读不到这个文件")
                },
            )
        }
    }

    /** 切换输出格式。不清已选的素材，用户改主意时不用重新选一遍。 */
    fun setLiveSingleFile(enabled: Boolean) {
        _live.value = _live.value.copy(singleFile = enabled, error = "")
    }

    /** 改输出文件名前缀。空串表示回到默认（原图名_实况）。 */
    fun setLiveNamePrefix(text: String) {
        // 文件名里不能出现路径分隔符，顺手挡掉；长度也要收一收，
        // 否则加上时间戳后会超过部分文件系统的单段上限
        val safe = text.replace('/', '_').replace('\\', '_').take(40)
        _live.value = _live.value.copy(namePrefix = safe)
    }

    /**
     * 清空已选的图片。
     *
     * 必须有这个入口：用户选错图后如果只能「重选」，就得再翻一遍相册；
     * 而「不选图片」本身是合法状态（会自动抽首帧），所以得能退回这个状态。
     */
    fun clearLiveImage() {
        _live.value = _live.value.copy(imagePath = "", imageLabel = "", error = "")
    }

    fun consumeLiveNotice() {
        _live.value = _live.value.copy(notice = null)
    }

    /**
     * 打开上一次的产物。
     *
     * 不自己做播放器：实况图的「预览」本来就是相册的交互（静图封面 + 长按播放），
     * 自己重写一套既费事又不一致。交给系统：先按路径查出媒体库的 content:// 地址
     * （扫描过就有了），再用 ACTION_VIEW 拉起，相册认得出这是动态照片。
     */
    fun openLiveResult() {
        val path = _live.value.savedPath
        if (path.isBlank()) return
        val f = File(path)
        if (!f.exists()) {
            _live.value = _live.value.copy(error = "产物不在了，可能已被移动或删除")
            return
        }
        val isImage = f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp")
        val collection = if (isImage) {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val id = mediaId(collection, f) ?: run {
            _live.value = _live.value.copy(error = "相册还没收录这个文件，稍等一下再点")
            return
        }
        val uri = android.content.ContentUris.withAppendedId(collection, id)
        runCatching {
            ctx.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, if (isImage) "image/*" else "video/*")
                    addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            )
        }.onFailure {
            _live.value = _live.value.copy(error = "没有能打开这个文件的应用")
        }
    }

    /**
     * 图片与视频是不是同一画幅。
     *
     * 比的是**宽高比**，不是像素尺寸：手机拍的 2160×3840 与下载/导出的 720×1280
     * 都是 9:16，属于同一画幅，配一起完全正常，只是清晰度不同。
     *
     * 容差 1%：覆盖 1080×1920 与 1078×1918 这类编码取整产生的微小偏差。
     * 同时接受「互为倒数」：图片带 EXIF 旋转、视频带 rotation 标记时，宽高会被互换。
     * 拿不到宽高（0）时放行 —— 不能因为探测失败就挡住用户。
     */
    private fun sameAspect(w1: Int, h1: Int, w2: Int, h2: Int): Boolean {
        if (w1 <= 0 || h1 <= 0 || w2 <= 0 || h2 <= 0) return true
        val a = w1.toDouble() / h1
        val b = w2.toDouble() / h2
        return abs(a - b) / b <= 0.01 || abs(a * b - 1.0) <= 0.01
    }

    /**
     * 合成并保存。
     *
     * 产物落到与下载相同的目录（`settings.outputDir`），用户在一个地方就能找齐。
     * 原件（缓存副本）不动；目录里重名时自动换名字，绝不覆盖已有文件。
     */
    fun saveLive() {
        val s = _live.value
        val vid = File(s.videoPath)
        // 视频是**必需**的：实况图的动效就是这段视频，没有它就没有「动」。
        // 图片可选 —— 不选时从视频抽首帧当封面（尺寸天然一致）。
        if (!vid.exists()) {
            _live.value = s.copy(error = "先选择一段视频")
            return
        }
        if (s.working) return

        viewModelScope.launch {
            _live.value = s.copy(working = true, error = "", notice = null)
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(Engine.settings(ctx).outputDir).apply { mkdirs() }

                    // 封面：用户选了图就用它，但**必须与视频同画幅**。
                    // 注意是画幅比例，不是像素尺寸：手机拍的 2160×3840 和导出/下载的
                    // 720×1280 都是 9:16，配在一起完全正常，只是清晰度不同。
                    // 比例不同才会出问题 —— 相册切到视频那一刻画面会跳变、变形。
                    val cover: File = if (s.imagePath.isNotBlank()) {
                        val img = File(s.imagePath)
                        if (!img.exists()) throw IllegalStateException("选中的图片不在了")
                        val imgInfo = Md5Media.probe(img, Md5Media.IMAGE)
                        val vidInfo = Md5Media.probe(vid, Md5Media.VIDEO)
                        if (imgInfo.width > 0 && vidInfo.width > 0 &&
                            !sameAspect(imgInfo.width, imgInfo.height, vidInfo.width, vidInfo.height)
                        ) {
                            throw IllegalStateException(
                                "图片是 ${imgInfo.width}×${imgInfo.height}（宽高比 " +
                                    "%.2f），视频是 ${vidInfo.width}×${vidInfo.height}（宽高比 %.2f），"
                                        .format(
                                            imgInfo.width.toDouble() / imgInfo.height,
                                            vidInfo.width.toDouble() / vidInfo.height,
                                        ) +
                                    "两者画幅比例不同。实况图的封面必须和视频同画幅，否则相册播放时画面会跳变 —— " +
                                    "分辨率不同没关系，比例一致就行。" +
                                    "请换一张同比例的图，或者清空图片让系统自动抽首帧。"
                            )
                        }
                        img
                    } else {
                        // 自动抽帧：取**时长中点**，不是首帧。
                        // 首帧与视频第 0 帧像素级相同，相册播放时完全无缝衔接，
                        // 观感就是「播了一段视频」；手机拍的实况图封面本来就落在视频
                        // 中段，长按时会先看到静图、再跳回开头播放，才有「动起来」的过渡。
                        val coverPath = File(LivePhotoSynth.workDir(ctx), "cover.jpg")
                        val durSec = Md5Media.probe(vid, Md5Media.VIDEO).durationMs / 1000.0
                        val res = Engine.extractCover(
                            ctx, vid.absolutePath, coverPath.absolutePath, durSec / 2,
                        )
                        if (!res.ok) throw IllegalStateException(
                            "从视频抽取封面失败：${res.error.ifBlank { "未知原因" }}"
                        )
                        coverPath
                    }

                    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    // 默认名取自「用户实际选的那个素材」的**原始显示名**：
                    // 选了图就用图名，只选视频就用视频名。
                    // 不能用磁盘上的 name —— 缓存副本按槽位固定命名（video.mp4 / cover.jpg），
                    // 生成出来就全叫「video_实况_…」「cover_实况_…」，用户看不出是哪条素材。
                    val base = s.namePrefix.trim().ifBlank {
                        val src = if (s.imagePath.isNotBlank()) s.imageLabel else s.videoLabel
                        src.substringBeforeLast('.', src) + "_实况"
                    }
                    val stem = "${base}_$stamp"
                    if (s.singleFile) {
                        val out = LivePhotoSynth.uniqueOut(dir, stem)
                        LivePhotoSynth.embed(cover, vid, out)
                        MediaScannerConnection.scanFile(
                            ctx, arrayOf(out.absolutePath), null, null,
                        )
                        out
                    } else {
                        val files = LivePhotoSynth.pair(cover, vid, dir, stem)
                        // 三步顺序不能换：写文件尾私有段（id 在这里定）→ 扫描入库 →
                        // 写媒体库字段。只登记本次这一对，不扫全目录。
                        val id = nextLivePhotoId()
                        LivePhotoSynth.appendVivoTail(files[0], id)
                        val left = AtomicInteger(files.size)
                        MediaScannerConnection.scanFile(
                            ctx, files.map { it.absolutePath }.toTypedArray(), null,
                        ) { _, _ ->
                            if (left.decrementAndGet() == 0) {
                                stampLivePhotoPair(files[0], files[1], id)
                            }
                        }
                        files.first()
                    }
                }
            }
            _live.value = r.fold(
                onSuccess = {
                    _live.value.copy(
                        working = false,
                        notice = "已保存到 ${it.absolutePath}",
                        savedPath = it.absolutePath,
                    )
                },
                onFailure = {
                    _live.value.copy(working = false, error = it.message ?: "合成失败")
                },
            )
        }
    }

    /**
     * 开关「下载完成时自动改写 MD5」。
     *
     * 不给一次性提示：这个开关本身就显示在解析页上，拨动后状态自己就说明了结果，
     * 再弹一句话属于多余。
     */
    fun setRewriteMd5(enabled: Boolean) {
        viewModelScope.launch {
            val fresh = withContext(Dispatchers.IO) {
                runCatching { Engine.setRewriteMd5(ctx, enabled) }.getOrNull()
            }
            _settings.value = SettingsUi(loading = false, settings = fresh)
        }
    }

    // ---- 授权 ----

    /**
     * 启动时做一次静默校验。
     *
     * 三种情况要分开处理：
     *   - 服务器说「授权仍然有效」→ 顺手更新 token（快到期时会换新的）；
     *   - 网络不通 → 什么都不做，本地凭证继续用（离线不能把人锁在门外）；
     *   - 服务器明确拒绝（停用 / 换绑 / token 过期）→ 先拿原密钥自动重激活一次
     *     （同一台设备重激活本来就合法，能救回「只是 token 过期」的人），
     *     重激活也失败才清掉凭证并弹框。
     */
    private fun verifyLicenseInBackground() {
        val key = licenseStore.key
        val token = licenseStore.token
        if (key.isBlank() || token.isBlank()) return

        viewModelScope.launch {
            val checked = withContext(Dispatchers.IO) {
                runCatching { LicenseApi.verify(key, DeviceId.get(ctx), token) }
            }

            checked.onSuccess { res ->
                licenseStore.save(key, res.token.ifBlank { token }, res.exp)
                refreshLicense()
            }

            val fatal = (checked.exceptionOrNull() as? LicenseApi.LicenseException)?.fatal == true
            if (!fatal) return@launch

            val retry = withContext(Dispatchers.IO) {
                runCatching { LicenseApi.activate(key, DeviceId.get(ctx)) }
            }
            retry.fold(
                onSuccess = { res ->
                    licenseStore.save(res.key, res.token, res.exp)
                    refreshLicense()
                },
                onFailure = { e ->
                    licenseStore.clearToken()
                    _license.value = _license.value.copy(
                        activated = false,
                        trialLeft = licenseStore.trialLeft,
                        savedKey = licenseStore.key,
                        error = e.message ?: "授权已失效，请重新输入密钥",
                        dialog = true,
                    )
                },
            )
        }
    }

    fun refreshLicense() {
        _license.value = _license.value.copy(
            activated = licenseStore.activated,
            trialLeft = licenseStore.trialLeft,
            savedKey = licenseStore.key,
        )
    }

    fun openLicenseDialog() {
        // 先刷新一次，保证 savedKey 跟上本地存储（预填上次用过的密钥）
        refreshLicense()
        _license.value = _license.value.copy(dialog = true, error = "")
    }

    fun dismissLicenseDialog() {
        if (_license.value.working) return
        _license.value = _license.value.copy(dialog = false, error = "")
    }

    fun consumeLicenseNotice() {
        _license.value = _license.value.copy(notice = null)
    }

    fun activateLicense(input: String) {
        if (_license.value.working) return
        val typed = input.trim()
        if (typed.isBlank()) {
            _license.value = _license.value.copy(error = "请先输入密钥")
            return
        }
        _license.value = _license.value.copy(working = true, error = "")
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { LicenseApi.activate(typed, DeviceId.get(ctx)) }
            }
            _license.value = result.fold(
                onSuccess = { res ->
                    licenseStore.save(res.key, res.token, res.exp)
                    LicenseUi(
                        activated = true,
                        trialLeft = licenseStore.trialLeft,
                        savedKey = res.key,
                        notice = "激活成功，已解除次数限制",
                    )
                },
                onFailure = { e ->
                    _license.value.copy(working = false, error = e.message ?: "激活失败")
                },
            )
        }
    }

    /** 开发/测试用：清掉授权与试用计数，回到「刚装好」的状态。 */
    fun resetLicenseForTest() {
        licenseStore.clearAll()
        trialCharged = false
        _license.value = LicenseUi(
            activated = false,
            trialLeft = LicenseStore.TRIAL_TOTAL,
            notice = "已清除授权与试用次数（测试）",
        )
    }

    /** 解析前的门禁。返回 false 时对话框已经弹好，调用方直接退出即可。 */
    private fun licenseGate(): Boolean {
        refreshLicense()
        if (licenseStore.activated) return true
        if (licenseStore.trialLeft > 0) return true
        _license.value = _license.value.copy(dialog = true, error = "")
        return false
    }

    /** 一次成功的解析只扣一次，WebView 两段式流程也只扣一次。 */
    private fun chargeTrialOnce() {
        if (trialCharged) return
        trialCharged = true
        if (licenseStore.activated) return
        licenseStore.addTrial()
        refreshLicense()
    }

    // ---- 外观 ----

    /**
     * 切换外观模式。改完立刻落盘 —— 这里没有「保存」按钮，点了就该生效并记住。
     * 不落盘的话，用户下次冷启动会被打回跟随系统，看起来就像设置没生效。
     */
    fun setThemeMode(mode: ThemeMode) {
        if (_themeMode.value == mode) return
        _themeMode.value = mode
        ThemePrefs.save(ctx, mode)
    }

    // ---- 检查更新 ----

    // ---- 存储权限 ----

    /**
     * 检查存储权限。
     *
     * 没有这个权限也能用（引擎会自动回退到应用私有目录），但下载的文件
     * 用文件管理器看不到，用户找不到。所以要主动引导一次。
     */
    fun refreshStoragePermission() {
        val missing = !hasAllFilesAccess()
        val changed = missing != _needStoragePermission.value
        _needStoragePermission.value = missing
        // 权限状态变了 = 输出目录很可能也跟着变了（刚授权就该从私有目录切回
        // 系统「下载」目录）。引擎那边每次读设置都会重新探测，所以这里只要重读一次。
        // 只在真的变了时才读，否则每次切回前台都要白跑一趟引擎。
        if (changed) refreshSettings()
    }

    private fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ 用「所有文件访问」这个特殊权限，不是普通的运行时权限，
            // 只能跳系统设置页让用户手动开，所以没法用 requestPermissions 申请
            Environment.isExternalStorageManager()
        } else {
            ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    /**
     * 检查 GitHub 上的最新版本。
     *
     * 网络失败不视为「异常」而是要如实告知的信息 —— 用户要求「因为网络原因
     * 无法检查同样也是提示告知」，所以每种失败都由 UpdateChecker 给出
     * 具体的中文原因，这里原样透传给界面。
     */
    fun checkUpdate() {
        if (_update.value is UpdateUi.Checking) return
        _update.value = UpdateUi.Checking
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { UpdateChecker.check(BuildConfig.VERSION_NAME) }
                    .getOrElse {
                        UpdateChecker.Result.Failed(
                            "检查更新时出错：${it.message ?: it.javaClass.simpleName}"
                        )
                    }
            }
            _update.value = when (result) {
                is UpdateChecker.Result.Available -> UpdateUi.Available(
                    version = result.version,
                    url = result.downloadUrl,
                    notes = result.notes,
                )
                is UpdateChecker.Result.UpToDate -> UpdateUi.UpToDate
                is UpdateChecker.Result.Failed -> UpdateUi.Failed(result.reason)
            }
        }
    }

    fun dismissUpdate() {
        _update.value = UpdateUi.Idle
    }
}
