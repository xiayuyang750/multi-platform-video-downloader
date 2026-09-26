package com.ytdlp.android

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.system.Os
import android.system.OsConstants
import android.widget.ScrollView
import android.widget.TextView
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File

/**
 * 阶段 1 的验证页：不接业务，只回答两个必须先确认的问题。
 *
 *   1. 设备侧条件 —— Android 版本、ABI、**内存页大小**。Android 15 起部分设备用
 *      16KB 页，原生库（libpython / ffmpeg / JS 运行时）没做 16KB 对齐会直接崩，
 *      所以这个值必须先看到。
 *   2. 引擎侧条件 —— 嵌入的 CPython 能否起来、yt-dlp 能否真的解析出视频信息。
 *      这一条不成立，后面的界面和业务都是空中楼阁。
 */
class MainActivity : Activity() {

    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        logView = TextView(this).apply {
            textSize = 14f
            setPadding(48, 110, 48, 48)
        }
        setContentView(ScrollView(this).apply { addView(logView) })

        append(deviceInfo())
        append("\n正在启动内嵌 Python 引擎…\n")

        // Python 初始化和网络解析都不能在主线程做，否则界面直接卡死。
        Thread {
            try {
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(this))
                }
                append("CPython 已启动\n")
                val module = Python.getInstance().getModule("ytdlp_bridge")

                append("【子进程能力体检】")
                append(module.callAttr("selftest").toString())

                // 自带的 ffmpeg 被 AGP 打进 jniLibs，安装后落在应用原生库目录，
                // 只有这个位置在 Android 10+ 上允许执行二进制（应用数据目录是 noexec）。
                val ffmpeg = File(applicationInfo.nativeLibraryDir, "libffmpeg.so").absolutePath
                val outDir = File(filesDir, "dl").absolutePath

                append("【阶段1c/1d】已单独验证通过\nffmpeg=$ffmpeg")

                // 自带的 QuickJS 同样从原生库目录调用；名字是 libqjs.so 而非 qjs，
                // 所以必须把路径显式告诉 yt-dlp，不能靠它去 PATH 上找。
                val qjs = File(applicationInfo.nativeLibraryDir, "libqjs.so").absolutePath
                append("【阶段1b：YouTube + QuickJS】\n$YOUTUBE_URL\nqjs=$qjs")
                append(module.callAttr("youtube_test", YOUTUBE_URL, ffmpeg, qjs).toString())
            } catch (exc: Exception) {
                append("失败：${exc.javaClass.simpleName}: ${exc.message}")
            }
        }.start()
    }

    private fun deviceInfo(): String {
        val pageSizeKb = Os.sysconf(OsConstants._SC_PAGESIZE) / 1024
        return buildString {
            appendLine("环境自检")
            appendLine("Android：${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
            appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("ABI：${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine("内存页：${pageSizeKb} KB")
            append(
                if (pageSizeKb >= 16) "→ 16KB 页设备，原生库必须 16KB 对齐"
                else "→ 4KB 页设备"
            )
        }
    }

    private fun append(text: String) {
        runOnUiThread { logView.append("\n$text") }
    }

    private companion object {
        const val TEST_URL = "https://www.bilibili.com/video/BV1ckhW6DErb/"

        // YouTube 上长期稳定的公开测试片（Big Buck Bunny），用于验证 JS 运行时
        const val YOUTUBE_URL = "https://www.youtube.com/watch?v=aqz-KE-bpKQ"
    }
}
