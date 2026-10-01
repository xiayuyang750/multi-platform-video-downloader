package com.ytdlp.android.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Base64
import com.ytdlp.android.BuildConfig
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 应用内反馈：把用户填写的内容推到开发者的企业微信群。
 *
 * **为什么不用「直接发邮件」**（这是本文件最需要说清楚的一点）：
 * 发邮件必须在客户端带上 SMTP 授权码，而本仓库是公开的 —— 授权码等于
 * 明文挂在网上，谁拿到都能冒充开发者发信，后果是账号被封、邮箱进黑名单。
 * 群机器人的 webhook 性质完全不同：它泄露了最多是别人往群里发消息，
 * 随手把机器人从群里移除就立刻作废，不存在可被冒用的身份。
 * **所以这里的 WEBHOOK_KEY 硬编码是刻意为之，不是疏忽。**
 *
 * 选企业微信还有一层现实原因：它的接口是腾讯自己的域名，国内不挂代理
 * 也能连上。本应用的用户里有大量抖音/B站用户，他们恰恰是不开代理的，
 * 反馈通道不能用境外服务（这是 Cloudflare Workers 那类方案被否掉的原因）。
 */
object FeedbackSender {

    /**
     * 「消息推送」（原群机器人）的 webhook key。留空表示还没配置。
     *
     * 获取方式：企业微信 → 建一个内部群 → 群设置 → 消息推送 → 添加 →
     * 复制 webhook 地址，取 `key=` 后面那一段填到这里。
     *
     * 这个值可以公开，不必藏（原因见类注释）。
     */
    private const val WEBHOOK_KEY = "7b79b3ac-a7be-48d1-b589-53d7d0d7eb64"

    private const val ENDPOINT = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key="

    /** 企业微信要求图片 base64 编码**前**不超过 2M，这里留出余量。 */
    private const val MAX_IMAGE_BYTES = 1_800_000

    /** 压缩后的最长边。再大对看清截图没有帮助，只会白白撑大请求体。 */
    private const val MAX_IMAGE_EDGE = 1920

    /** 一次最多带几张图。太多会刷屏，也容易撞「每分钟 20 条」的限制。 */
    const val MAX_IMAGES = 3

    /** 问题描述的字数上限，界面上也用它做提示。 */
    const val MAX_MESSAGE_CHARS = 1000

    sealed interface Result {
        data object Sent : Result
        data class Failed(val reason: String) : Result
    }

    /**
     * 发送一条反馈。**阻塞调用**，必须在 IO 线程执行。
     *
     * 文字和图分开发：企业微信一条消息只能是纯文字或纯图片，没有「一条里
     * 既有文字又有图」的选项（图文类型要求图片是外链，而我们手上只有本地图）。
     *
     * 顺序上先发文字 —— 让问题描述先送达，图片只是补充。
     * 这样即使后面某张图失败，用户的核心诉求也已经到了。
     */
    fun send(
        context: Context,
        message: String,
        contact: String,
        images: List<Uri>,
    ): Result {
        if (WEBHOOK_KEY.isBlank()) {
            return Result.Failed("反馈通道还没配置好，请先联系开发者。")
        }
        if (message.isBlank()) {
            return Result.Failed("请先填写问题描述。")
        }

        val textResult = post(
            JSONObject().apply {
                put("msgtype", "markdown")
                put("markdown", JSONObject().put("content", composeMarkdown(message, contact)))
            }
        )
        if (textResult is Result.Failed) return textResult

        for (uri in images.take(MAX_IMAGES)) {
            val bytes = readCompressed(context, uri)
                ?: return Result.Failed("有一张图片读不出来，请重新选一次。")
            val r = post(
                JSONObject().apply {
                    put("msgtype", "image")
                    put(
                        "image",
                        JSONObject()
                            // NO_WRAP：不加的话会插换行，base64 里有换行会被服务端拒绝
                            .put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                            .put("md5", md5(bytes))
                    )
                }
            )
            if (r is Result.Failed) return r
        }
        return Result.Sent
    }

    /**
     * 组一条 markdown 消息。
     *
     * 固定带上机型和版本：排查问题时这两项最有用，而用户基本不会主动写，
     * 与其在界面上加一堆选填框，不如自动带上。
     */
    private fun composeMarkdown(message: String, contact: String): String {
        val header = buildString {
            append("**收到一条用户反馈**\n")
            append("> 机型：${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("> 系统：Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）\n")
            append("> 应用：v${BuildConfig.VERSION_NAME}\n")
            append("> 联系：${contact.ifBlank { "未填写" }}\n\n")
        }
        // markdown 消息整体上限 4096 字节。头信息占掉一部分，正文按剩余空间截断 ——
        // 不截的话超长反馈会被整条拒收，用户还以为自己发出去了。
        val budget = 4096 - header.toByteArray(Charsets.UTF_8).size - 64
        return header + truncateUtf8(message, budget)
    }

    /** 按 UTF-8 字节数截断，且不切碎多字节字符（中文一个字 3 字节）。 */
    private fun truncateUtf8(text: String, maxBytes: Int): String {
        var used = 0
        val sb = StringBuilder()
        for (ch in text) {
            val n = ch.toString().toByteArray(Charsets.UTF_8).size
            if (used + n > maxBytes) return sb.append("\n…（内容过长，已截断）").toString()
            used += n
            sb.append(ch)
        }
        return text
    }

    /**
     * 读取并压缩一张图片。
     *
     * 必须压缩：手机截图和照片动辄 3~5MB，base64 之后还要再涨三分之一，
     * 不压缩会直接撞上 2M 的限制被拒。
     * 先按尺寸采样（避免整张大图解码吃掉几十 MB 内存），再逐步降质兜底。
     */
    private fun readCompressed(context: Context, uri: Uri): ByteArray? {
        // 第一遍只读尺寸，不真正解码像素。
        // 注意：inJustDecodeBounds = true 时 decodeStream **必定返回 null** —— 它只
        // 负责往 Options 里填宽高。所以这里绝不能拿它的返回值判断成败，
        // 必须单独判断流有没有打开。（这个坑真机实测踩过一次：写成
        // `openInputStream(uri)?.use { decodeStream(...) } ?: return null`
        // 会让整个函数无条件返回 null，图片永远发不出去。）
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val probe = context.contentResolver.openInputStream(uri) ?: return null
        probe.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / (sample * 2) >= MAX_IMAGE_EDGE) sample *= 2

        val decoded = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(
                input,
                null,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        } ?: return null

        try {
            var quality = 85
            while (true) {
                val out = ByteArrayOutputStream()
                decoded.compress(Bitmap.CompressFormat.JPEG, quality, out)
                val bytes = out.toByteArray()
                // 降到 40 还是超标就认了：继续降画质也看不清截图，没有意义
                if (bytes.size <= MAX_IMAGE_BYTES || quality <= 40) return bytes
                quality -= 15
            }
        } finally {
            decoded.recycle()
        }
    }

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    /** POST 一条消息。所有失败都翻译成用户能看懂的中文。 */
    private fun post(payload: JSONObject): Result {
        val conn = try {
            (URL(ENDPOINT + WEBHOOK_KEY).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connectTimeout = 15_000
                readTimeout = 20_000
            }
        } catch (e: Exception) {
            return Result.Failed("反馈地址无效（${e.javaClass.simpleName}）。")
        }

        return try {
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val body = (if (code == 200) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (code != 200) {
                Result.Failed("发送失败（HTTP $code），请检查网络后重试。")
            } else {
                // 企业微信即使返回 200，也可能在 body 里带错误码，必须再查一层
                val json = runCatching { JSONObject(body) }.getOrNull()
                val errcode = json?.optInt("errcode", 0) ?: 0
                if (errcode == 0) Result.Sent
                else Result.Failed(explain(errcode, json?.optString("errmsg").orEmpty()))
            }
        } catch (e: Exception) {
            Result.Failed("连不上反馈服务器（${e.javaClass.simpleName}），请检查网络后重试。")
        } finally {
            conn.disconnect()
        }
    }

    /** 把企业微信的错误码翻译成人话。只列用户真会撞到的几个。 */
    private fun explain(errcode: Int, errmsg: String): String = when (errcode) {
        93000 -> "反馈通道的 key 无效，请联系开发者更新应用。"
        45009 -> "发送太频繁了，等一分钟再试。"
        40001, 40014 -> "反馈通道的凭证失效，请联系开发者。"
        else -> "发送被拒绝（$errcode $errmsg）。"
    }
}
