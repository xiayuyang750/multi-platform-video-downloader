package com.ytdlp.android.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ytdlp.android.BuildConfig
import com.ytdlp.android.engine.Download
import com.ytdlp.android.engine.Engine
import com.ytdlp.android.engine.Settings
import com.ytdlp.android.engine.UpdateChecker
import com.ytdlp.android.engine.Video
import com.ytdlp.android.firstUrl
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

/** 解析页的状态。各态互斥，用 sealed 比「一个 result + 一堆 bool」更难写错。 */
sealed interface ParseUi {
    data object Idle : ParseUi
    data object Loading : ParseUi
    data class Done(val video: Video) : ParseUi
    data class Failed(val message: String) : ParseUi

    /**
     * 需要走浏览器模式（目前只有抖音）。
     * 界面收到它就去拉起 DouyinActivity，解析完再回调 onDouyinResult。
     */
    data class NeedBrowser(val url: String, val message: String) : ParseUi
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

    // 注意：上面这些 StateFlow 必须声明在 init 块**之前**。
    // Kotlin 按代码顺序初始化属性，而 init 里会调用 refreshStoragePermission()，
    // 若把 _needStoragePermission 写在文件末尾，init 执行时它还是 null，
    // 会抛 "Attempt to invoke ... setValue on a null object reference"。
    private var parseJob: Job? = null

    init {
        // 先把 Python 起来，免得用户点「解析」时先白等 1 秒
        viewModelScope.launch(Dispatchers.IO) { Engine.warmUp(ctx) }
        refreshHistory()
        refreshSettings()
        refreshStoragePermission()
        startPollingDownload()
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
                        // 解析成功会把记录写进历史库，这里顺手刷新，
                        // 用户切到历史页就能看到，不用手动下拉
                        refreshHistory()
                        ParseUi.Done(r.info)
                    }
                    is Engine.ParseResult.Err -> if (r.needWebview) {
                        // 抖音：交给界面的 WebView 解析页继续
                        ParseUi.NeedBrowser(target, r.message)
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
    fun onDouyinResult(payloadJson: String?, reason: String) {
        if (payloadJson == null) {
            _parse.value = ParseUi.Failed(
                reason.ifBlank { "浏览器模式没能取到视频数据。" }
            )
            return
        }
        _parse.value = ParseUi.Loading
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { Engine.saveDouyin(ctx, payloadJson) }
            }
            _parse.value = when {
                result.isFailure -> ParseUi.Failed(
                    "入库失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                )
                else -> when (val r = result.getOrThrow()) {
                    is Engine.ParseResult.Ok -> {
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

    fun startDownload() {
        val video = (_parse.value as? ParseUi.Done)?.video ?: return
        startDownload(video)
    }

    /** 从历史页也能直接发起下载，所以按 Video 重载一份。 */
    fun startDownload(video: Video) {
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching { Engine.startDownload(ctx, video.sourceUrl) }
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
                    val prev = _download.value
                    _download.value = state
                    // 刚下载完 -> 把「保存到哪」告诉设置页不必了，
                    // 下载条自己会显示路径
                    if (prev.active && !state.active && state.isDone) {
                        refreshHistory()
                    }
                }
                delay(if (_download.value.active) 700 else 2000)
            }
        }
    }

    /** 用户点掉下载条。 */
    fun dismissDownload() {
        _download.value = _download.value.copy(type = "idle", active = false)
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

    // ---- 检查更新 ----

    // ---- 存储权限 ----

    /**
     * 检查存储权限。
     *
     * 没有这个权限也能用（引擎会自动回退到应用私有目录），但下载的文件
     * 用文件管理器看不到，用户找不到。所以要主动引导一次。
     */
    fun refreshStoragePermission() {
        _needStoragePermission.value = !hasAllFilesAccess()
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
