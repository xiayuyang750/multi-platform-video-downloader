package com.ytdlp.android.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ytdlp.android.BuildConfig
import com.ytdlp.android.engine.Download
import com.ytdlp.android.engine.DownloadItem
import com.ytdlp.android.engine.Engine
import com.ytdlp.android.engine.Settings
import com.ytdlp.android.engine.UpdateChecker
import com.ytdlp.android.engine.Video
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
import java.util.concurrent.atomic.AtomicInteger
import java.io.File

/** 解析页的状态。各态互斥，用 sealed 比「一个 result + 一堆 bool」更难写错。 */
sealed interface ParseUi {
    data object Idle : ParseUi
    data object Loading : ParseUi
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

        _parse.value = ParseUi.Loading
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
     * 与上面两条的差别只在「交给引擎哪个入库入口」。
     */
    fun onWeiboResult(payloadJson: String?, reason: String) = onWebviewResult(
        payloadJson, reason, "浏览器模式没能取到这条微博的数据。",
    ) { Engine.saveWeibo(ctx, it) }

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
        _parse.value = ParseUi.Loading
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
        runCatching {
            val f = File(path)
            // 图文类的产物是一个目录：要把里面的每一张都收录进去，
            // scanFile 对目录本身不起作用。
            val targets = if (f.isDirectory) {
                (f.listFiles() ?: emptyArray()).filter { it.isFile }.map { it.absolutePath }
            } else {
                listOf(path)
            }
            if (targets.isEmpty()) return
            // 扫完（每个文件回调一次）再登记实况图：登记要改 MediaStore 里的记录，
            // 而记录是扫描之后才存在的，所以必须等它扫完。
            val left = AtomicInteger(targets.size)
            MediaScannerConnection.scanFile(ctx, targets.toTypedArray(), null) { _, _ ->
                if (left.decrementAndGet() == 0 && f.isDirectory) markLivePhotos(f)
            }
        }
    }

    /**
     * 把目录里「同名的图片 + 视频」登记成系统相册的**动态照片（实况图）**。
     *
     * 为什么光有同名文件还不够：实测（vivo）发现，相册读的是 MediaStore 里一个
     * 厂商私有字段 `live_photo`，而且**图片记录和视频记录两边都要有值**才算数 ——
     * 只写一边，相册照样显示成普通图片。抖音下载的实况图就是这么做的
     * （它的图片和视频两条记录里都带着这个字段）。
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
    private fun markLivePhotos(dir: File) {
        runCatching {
            val stamp = "${System.currentTimeMillis()}000000000000000"
            val imageExts = setOf("jpg", "jpeg", "webp", "png")
            dir.listFiles()
                ?.filter { it.isFile && it.extension.lowercase() in imageExts }
                ?.forEach { image ->
                    // 配对靠「同名」：图片与那段动效视频同名，只有扩展名不同
                    val mp4 = File(dir, image.nameWithoutExtension + ".mp4")
                    if (!mp4.exists()) return@forEach
                    val imageId = mediaId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, image)
                        ?: return@forEach
                    val mp4Id = mediaId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, mp4)
                        ?: return@forEach
                    putLivePhoto(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageId, stamp)
                    putLivePhoto(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, mp4Id, stamp)
                }
        }
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
