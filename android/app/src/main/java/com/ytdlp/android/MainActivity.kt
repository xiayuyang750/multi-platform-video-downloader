package com.ytdlp.android

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.system.Os
import android.system.OsConstants
import android.widget.TextView

/**
 * 阶段 1 的自检页：不接任何业务，只把与兼容性判断相关的设备信息显示出来。
 *
 * 之所以先做这一屏，是因为整个安卓端方案有两个前提必须先确认：
 *   1. 构建链路（AGP / Gradle / SDK / JDK）能在本机跑通并装到真机；
 *   2. 真机的 ABI 与**内存页大小**符合预期 —— Android 15 起部分设备用
 *      16KB 页，原生库（libpython、ffmpeg、JS 运行时）没做 16KB 对齐会直接崩。
 * 这两点没确认之前，写界面和业务都是空中楼阁。
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pageSizeKb = Os.sysconf(OsConstants._SC_PAGESIZE) / 1024

        val info = buildString {
            appendLine("环境自检")
            appendLine()
            appendLine("Android 版本：${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
            appendLine("设备型号：${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("支持 ABI：${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine("内存页大小：${pageSizeKb} KB")
            appendLine()
            appendLine(
                if (pageSizeKb >= 16) {
                    "注意：这是 16KB 页设备，所有原生库必须做 16KB 对齐。"
                } else {
                    "这是 4KB 页设备，原生库对齐压力较小。"
                }
            )
        }

        setContentView(
            TextView(this).apply {
                text = info
                textSize = 15f
                setPadding(56, 120, 56, 56)
            }
        )
    }
}
