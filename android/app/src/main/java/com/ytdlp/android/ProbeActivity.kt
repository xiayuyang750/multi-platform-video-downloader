package com.ytdlp.android

import android.app.Activity
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File

/**
 * 引擎自检页：回答一个问题 —— 移植过来的引擎在真机上能不能跑通
 * 「解析 / 下载 / 历史 / 设置」四条链路。
 *
 * 它**不是**给用户看的界面，而是诊断入口。之所以保留而不是随阶段 2 一起删掉：
 * 引擎（Python 层）以后每次改动都需要一个不依赖界面的验证手段，否则一出问题
 * 就得先怀疑「是引擎坏了还是界面写错了」。正式界面见 MainActivity。
 *
 * 本页刻意做得又丑又直白（按钮 + 裸日志），因为它的唯一职责是把引擎的返回值
 * 原样摊在屏幕上，方便对着 logcat 排查。启动方式：
 *   adb shell am start -n com.ytdlp.android/.ProbeActivity
 */
class ProbeActivity : Activity() {

    private lateinit var logView: TextView
    private lateinit var actions: LinearLayout
    private lateinit var engine: com.chaquo.python.PyObject

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        logView = TextView(this).apply {
            textSize = 13f
            setPadding(40, 40, 40, 40)
            setTextIsSelectable(true)
        }
        actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(actions, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(
                ScrollView(this@ProbeActivity).apply { addView(logView) },
                ViewGroup.LayoutParams.MATCH_PARENT, 0
            )
        }
        // 日志占满剩余空间
        (root.getChildAt(1).layoutParams as LinearLayout.LayoutParams).weight = 1f
        setContentView(root)

        append("阶段 2 验证页：引擎链路自检。阶段 3 会换成正式界面。\n")
        call("启动内嵌 Python 引擎") {
            if (!Python.isStarted()) Python.start(AndroidPlatform(this@ProbeActivity))
            engine = Python.getInstance().getModule("ytdlp_engine")
            // 只有安卓侧才知道的路径在这里注入：原生库目录 + 系统下载目录。
            // 五个参数必须是 String：Chaquopy 不会把 java.io.File 自动转成
            // Python 的 str，误传 File 对象会在 Python 侧报
            // "argument should be a str or an os.PathLike object ... not 'File'"。
            val settings = engine.callAttr(
                "configure",
                filesDir.absolutePath,
                File(applicationInfo.nativeLibraryDir, "libffmpeg.so").absolutePath,
                File(applicationInfo.nativeLibraryDir, "libqjs.so").absolutePath,
                Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                ).absolutePath,
                File(filesDir, "Download").absolutePath,
            ).toString()
            // 引擎就绪后再排自动探针。不能按固定延时排：它们依赖 engine 已初始化，
            // 而 Python 启动到底要多久是不确定的 —— 实测要 1 秒上下，
            // 先前按 800ms 排期就踩空了，第一个探针报
            // 「lateinit property engine has not been initialized」。
            runAutoProbes()
            settings
        }
    }

    /** 每个按钮对应一条要独立验证的链路。 */
    private val probes: List<Pair<String, () -> Any?>> = listOf(
        "解析 B站" to { py("parse_url", BILIBILI_URL) },
        "解析 YouTube" to { py("parse_url", YOUTUBE_URL) },
        "解析 X（未导入 Cookie，验证报错翻译）" to { py("parse_url", X_URL) },
        "下载 B站并等它结束（验证进程内合流）" to { downloadAndWait() },
        "读取历史" to { py("get_history") },
        "读取设置" to { py("get_settings") },
        "清空历史" to { py("clear_all_history") },
    )

    override fun onStart() {
        super.onStart()
        if (actions.childCount > 0) return
        probes.forEach { (label, action) ->
            actions.addView(Button(this).apply {
                text = label
                setOnClickListener { call(label, action) }
            })
        }
    }

    /** 发起下载并轮询到结束，返回最后一条状态。
     *
     * 进程内下载是异步的（引擎里开了后台线程），光调 start_download 只拿到
     * 「已受理」，拿不到成败。必须轮询到 active 变回 false 才算验完这一条。
     */
    private fun downloadAndWait(): String {
        py("start_download", BILIBILI_URL)
        var prev = ""
        // 这条链接的最高画质约 300MB，DASH 双轨 + 合流，给足 10 分钟
        val deadline = System.currentTimeMillis() + 600_000
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(1000)
            val st = py("get_download_state")
            // 状态每秒都在变（percent 在动），只在整条变了时才记一行，避免刷屏
            if (st != prev) {
                Log.i(TAG, "  ↳ $st")
                prev = st
            }
            if (!st.contains("\"active\": true")) break
        }
        return prev
    }

    /** 自动探针只跑一次（onStart 可能被多次调用）。 */
    private var autoRan = false

    private fun runAutoProbes() {
        if (autoRan) return
        autoRan = true
        runOnUiThread {
            // 轻量探针自动跑一遍：都只读数据、不改状态，一次就能拿到主要证据。
            // 下载（又慢又落文件）仍留给手动点，避免每次装包都白下几百 MB。
            autoProbes.forEachIndexed { i, (label, action) ->
                logView.postDelayed({ call(label, action) }, 300L * i)
            }
        }
    }

    /** 自动跑的探针：都是只读且秒回的，不会拖慢启动。 */
    private val autoProbes: List<Pair<String, () -> Any?>> = listOf(
        "解析 B站" to { py("parse_url", BILIBILI_URL) },
        "解析 YouTube" to { py("parse_url", YOUTUBE_URL) },
        "解析 X（未导入 Cookie，验证报错翻译）" to { py("parse_url", X_URL) },
        "读取历史" to { py("get_history") },
    )

    /** 调一个引擎函数并把返回的 JSON 打出来。 */
    private fun py(name: String, vararg args: String): String =
        engine.callAttr(name, *args).toString()

    /** 引擎调用一律放后台线程：解析和下载都是纯阻塞 IO，放主线程会直接卡死界面。 */
    private fun call(label: String, block: () -> Any?) {
        append("\n▶ $label")
        Thread {
            val text = try {
                block()?.toString() ?: ""
            } catch (exc: Exception) {
                "失败：${exc.javaClass.simpleName}: ${exc.message}"
            }
            append("◀ $label\n$text")
        }.start()
    }

    private fun append(text: String) {
        // 同时写 logcat：界面日志区会自动滚到底，前面的输出会被顶掉；
        // 走 logcat 才能把整轮探针的完整结果一次性取回来做比对。
        Log.i(TAG, text)
        runOnUiThread {
            logView.append("$text\n")
            // 自动滚到底，省得每次手动翻
            (logView.parent as? ScrollView)?.post {
                (logView.parent as? ScrollView)?.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    private companion object {
        const val TAG = "probe"
        const val BILIBILI_URL = "https://www.bilibili.com/video/BV1ckhW6DErb/"
        const val YOUTUBE_URL = "https://www.youtube.com/watch?v=aqz-KE-bpKQ"

        // 这条探针验的不是「能不能解析 X」，而是「需要 Cookie 却没导入时，
        // 界面上有没有出现明确提示」。所以编号不必真实存在 —— 平台识别按
        // 域名走，use_cookies=true，必然走到「你还没导入 Cookie」那条分支。
        const val X_URL = "https://x.com/i/status/1234567890123456789"
    }
}
