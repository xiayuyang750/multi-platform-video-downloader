package com.ytdlp.android.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * MD5 流程里「选中的东西」在缓存里的落点、以及怎么看 / 怎么存这一层。
 *
 * 三条贯穿始终的规矩：
 *  1. **原件全程不动**：挑中的媒体先整份复制进缓存，后面所有处理都只碰这份副本，
 *     保存时再产出新文件（用户要的就是「另存副本」）。
 *  2. **扩展名必须留住**：引擎按扩展名决定走 ffmpeg（视频）还是走追加标记（图片），
 *     名字里没有扩展名就会选错处理路径，所以取不到名字时按 MIME 补一个。
 *  3. 相册 / 文件选择器给的 content:// 只在本次会话有效，不能指望它长期可读 ——
 *     这也是必须立刻复制一份的原因。
 */
object Md5Media {

    const val VIDEO = "video"
    const val IMAGE = "image"

    /** 一份挑中的媒体：缓存里的副本 + 是视频还是图片。 */
    data class Picked(val file: File, val kind: String)

    /** 探测出来的信息：大小必给，时长 / 分辨率拿不到就是 0。 */
    data class Info(val size: Long, val durationMs: Long, val width: Int, val height: Int)

    /**
     * 图片扩展名。与 ytdlp_engine.py 的 IMAGE_EXTS 保持一致 ——
     * 那边决定用哪条改写路径，这边决定用播放器还是图片控件来预览。
     */
    private val IMAGE_EXTS = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic", "heif",
    )

    /** 副本都放这里；每开始一轮新任务就整个清掉，免得越攒越多。 */
    private fun workDir(ctx: Context): File = File(ctx.cacheDir, "md5").apply { mkdirs() }

    fun clearWorkDir(ctx: Context) {
        workDir(ctx).listFiles()?.forEach { runCatching { it.deleteRecursively() } }
    }

    /**
     * 改后成品的落点：工作目录下的 out/ 子目录，名字与原文件相同。
     *
     * 单独放一层子目录，是为了不和「原件副本」撞名 —— 保存时用的就是原文件名，
     * 撞名的话成品会被叫成「xxx (1).mp4」，存进下载目录后名字就难看了。
     */
    fun outFile(ctx: Context, name: String): File {
        val dir = File(workDir(ctx), "out").apply { mkdirs() }
        return uniqueFile(dir, name)
    }

    /** 相册 / 文件选择器选中的那一项：复制进缓存。 */
    fun pickFromUri(ctx: Context, uri: Uri): Picked {
        clearWorkDir(ctx)
        val name = nameOf(ctx, uri)
        val dst = uniqueFile(workDir(ctx), name)
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            dst.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("读不到这个文件，可能已被移动或删除")
        // MIME 优先：相册给的 type 比扩展名可靠（.jpg 也可能是 HEIC 改名来的）
        val mime = runCatching { ctx.contentResolver.getType(uri) }.getOrNull().orEmpty()
        val kind = when {
            mime.startsWith("image/") -> IMAGE
            mime.startsWith("video/") -> VIDEO
            else -> kindOf(name)
        }
        return Picked(dst, kind)
    }

    /** 「已下载」列表里的那一条：同样先复制进缓存，原文件不动。 */
    fun pickFromFile(ctx: Context, src: File): Picked {
        clearWorkDir(ctx)
        val dst = uniqueFile(workDir(ctx), src.name)
        src.copyTo(dst, overwrite = false)
        return Picked(dst, kindOf(src.name))
    }

    fun kindOf(name: String): String =
        if (name.substringAfterLast('.', "").lowercase() in IMAGE_EXTS) IMAGE else VIDEO

    /** 时长 / 分辨率 / 大小。 */
    fun probe(file: File, kind: String): Info {
        val size = file.length()
        if (kind == IMAGE) {
            // inJustDecodeBounds 只读文件头，不把整张图解码进内存（相册里的图动辄几千万像素）
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            return Info(size, 0L, opts.outWidth.coerceAtLeast(0), opts.outHeight.coerceAtLeast(0))
        }
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            val rotation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            var w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            var h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            // 竖屏视频常常带旋转标记，这里不换的话会显示成横屏的宽高
            if (rotation % 180 != 0) {
                val t = w; w = h; h = t
            }
            Info(
                size = size,
                durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L,
                width = w,
                height = h,
            )
        } catch (_: Exception) {
            // 取不到就算了：界面把拿不到的那几行留空，不编一个假值
            Info(size, 0L, 0, 0)
        } finally {
            runCatching { mmr.release() }
        }
    }

    /**
     * 把成品存进输出目录，并让相册立刻收录它。
     *
     * 与下载链路落到同一个目录（settings.outputDir），这样用户在一个地方就能找齐
     * 所有产物。重名不覆盖，改成「名字 (1).ext」。
     */
    fun save(ctx: Context, src: File, outputDir: String): File {
        val dir = File(outputDir).apply { mkdirs() }
        val dst = uniqueFile(dir, src.name)
        src.copyTo(dst, overwrite = false)
        // 不扫这一下，媒体库里不会出现这个文件（相册/文件管理器看不到）
        runCatching {
            MediaScannerConnection.scanFile(ctx, arrayOf(dst.absolutePath), null, null)
        }
        return dst
    }

    /** 从 uri 取显示名，并保证带扩展名。 */
    private fun nameOf(ctx: Context, uri: Uri): String {
        val queried = runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull().orEmpty()

        // 名字里可能带路径分隔符（少数文件管理器会给），去掉以免写到别的目录
        val safe = queried.replace('/', '_').replace('\\', '_').ifBlank { "md5_pick" }
        if (safe.substringAfterLast('.', "").isNotEmpty()) return safe
        return "$safe.${fallbackExt(ctx, uri)}"
    }

    /** 取不到名字时按 MIME 兜一个扩展名 —— 引擎按扩展名选处理路径，不能瞎给。 */
    private fun fallbackExt(ctx: Context, uri: Uri): String {
        val mime = runCatching { ctx.contentResolver.getType(uri) }.getOrNull().orEmpty()
        return when {
            mime == "image/png" -> "png"
            mime == "image/webp" -> "webp"
            mime == "image/gif" -> "gif"
            mime == "image/heic" || mime == "image/heif" -> "heic"
            mime.startsWith("image/") -> "jpg"
            mime == "video/quicktime" -> "mov"
            mime == "video/webm" -> "webm"
            mime == "video/3gpp" -> "3gp"
            else -> "mp4"
        }
    }

    /** 同名文件已存在就让位加序号，绝不覆盖已有的东西。 */
    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (f.exists()) {
            f = File(dir, "$base ($i)" + if (ext.isEmpty()) "" else ".$ext")
            i++
        }
        return f
    }
}
