package com.ytdlp.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ytdlp.android.engine.Download
import com.ytdlp.android.engine.Engine
import com.ytdlp.android.engine.Settings
import com.ytdlp.android.engine.Video
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

/** 解析页的状态。四态互斥，用 sealed 比「一个 result + 一堆 bool」更难写错。 */
sealed interface ParseUi {
    data object Idle : ParseUi
    data object Loading : ParseUi
    data class Done(val video: Video) : ParseUi
    data class Failed(val message: String) : ParseUi
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

    private var parseJob: Job? = null

    init {
        // 先把 Python 起来，免得用户点「解析」时先白等 1 秒
        viewModelScope.launch(Dispatchers.IO) { Engine.warmUp(ctx) }
        refreshHistory()
        refreshSettings()
        startPollingDownload()
    }

    // ---- 输入 ----

    fun onUrlChange(value: String) {
        _url.value = value
    }

    // ---- 解析 ----

    fun parse() {
        val target = _url.value.trim()
        if (target.isEmpty()) {
            _parse.value = ParseUi.Failed("请先粘贴视频链接")
            return
        }
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
}
