package com.ytdlp.android.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile

/**
 * 「图片 + 视频 → 实况图」的两种产出方式。
 *
 * 这两种方式对应两类完全不同的系统实现，不能互相替代（各品牌相册认的不一样）：
 *
 *  1. **同名配对**（[pair]）：磁盘上留两个文件（`x.jpg` + `x.mp4`），再由
 *     `AppViewModel.prepareLivePhotos` 往媒体库写厂商私有字段 `live_photo`，
 *     并在 JPEG 尾部追加 vivo 的私有段（[appendVivoTail]）。
 *     vivo / iQOO 实测可用，用户的红米也实测可用。
 *
 *  2. **单文件**（[embed]）：把 MP4 原样追加到 JPEG 末尾，并在 JPEG 里插一段
 *     XMP 声明「末尾那 N 个字节是视频」。这是 Google 的 Motion Photo 规范，
 *     小米（HyperOS 官方文档写明相册扫 XMP 里的 `MicroVideo` / `MotionPhoto`）、
 *     OPPO / 一加、华为 / 荣耀、原生 Android 都按这条认。
 *
 * 两条路都不重新编码，画质与原件一致；也都只读不写原件。
 * 注意：单文件那条**只支持 JPEG**，PNG / WebP / HEIC 没有可靠的追加位置。
 */
object LivePhotoSynth {

    /** 选中的一份素材：缓存副本 + 原始显示名 + 是图还是视频。 */
    data class Picked(val file: File, val label: String, val isImage: Boolean)

    private val IMAGE_EXTS = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic", "heif",
    )

    /** XMP 段的标准签名，后面紧跟 XML。少了它系统不认这段是 XMP。 */
    private const val XMP_SIG = "http://ns.adobe.com/xap/1.0/\u0000"

    /** vivo 私有段结尾的固定魔数（11 字节），自拍与抖音保存的完全一致。 */
    private const val VIVO_TAIL_MAGIC = "1B2A39485766758493A2B3"

    fun workDir(ctx: Context): File = File(ctx.cacheDir, "live").apply { mkdirs() }

    fun clearWorkDir(ctx: Context) {
        workDir(ctx).listFiles()?.forEach { runCatching { it.deleteRecursively() } }
    }

    /**
     * 把相册选择器给的一项复制进缓存。
     *
     * 必须立刻复制：`content://` 的授权只在本次会话有效，稍后再读可能就没了。
     * 同一槽位（image / video）重选时先删旧的，免得缓存越攒越多。
     */
    fun pick(ctx: Context, uri: Uri, slot: String): Picked {
        val display = displayName(ctx, uri)
        val ext = display.substringAfterLast('.', "").lowercase()
            .ifBlank { fallbackExt(ctx, uri) }
        workDir(ctx).listFiles()
            ?.filter { it.name.substringBefore('.') == slot }
            ?.forEach { runCatching { it.delete() } }
        val dst = File(workDir(ctx), "$slot.$ext")
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            dst.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("读不到这个文件，可能已被移动或删除")
        // 选中的可能是「单文件动态照片」：JPEG 的 EOI 后面还挂着一段 MP4。
        // 只留静态封面，否则后续会把别人的视频一起带进产物（详见 trimAppendedVideo）。
        if (ext in IMAGE_EXTS) trimAppendedVideo(dst)
        return Picked(dst, display, ext in IMAGE_EXTS)
    }

    /**
     * 如果这是一张「单文件动态照片」，把 JPEG 结束符之后的视频数据截掉。
     *
     * 为什么必须截：相册选择器允许选实况图（动态照片）。这类文件是把 MP4 直接
     * 接在 JPEG 的 `FF D9` 后面，再靠 XMP 声明「末尾 N 字节是视频」。
     * 如果整份复制当封面用：
     *  - 单文件模式会在这个已有 XMP 的文件里**再插一段 XMP、再追加一段视频**，
     *    两个声明互相冲突，产出基本必然坏掉；
     *  - 配对模式会得到一张体积虚高的「图片」，旁边还配着另一段 mp4，
     *    相册可能按文件内嵌的那段播放，而不是用户选的视频。
     *
     * vivo 那种「图片 + 视频分开存、靠媒体库 live_photo 配对」的实况图不受影响：
     * 它的 JPEG 本来就是完整文件，EOI 后面没有多余数据，这里什么都不做。
     */
    private fun trimAppendedVideo(file: File) {
        val end = jpegEnd(file) ?: return
        if (end <= 0 || end >= file.length()) return
        runCatching { RandomAccessFile(file, "rw").use { it.setLength(end) } }
    }

    /**
     * 找出 JPEG 真正的结束位置（`FF D9` 之后的下一个字节偏移）。
     * 不是 JPEG 或解析不出来时返回 null。
     *
     * 为什么不能直接找文件里的第一个 `FF D9`：APP1 里常嵌着 EXIF 缩略图，
     * 那本身就是一个完整 JPEG、带着自己的 EOI —— 盲扫会把图片截在缩略图结尾。
     * 所以先按段长跳过 APPn/DQT 等段，到 SOS 之后再扫熵编码数据；
     * 熵编码里 `FF` 后面只可能是 `00`（转义）或 `D0`~`D7`（重启），
     * 因此这时遇到的 `FF D9` 只可能是真正的 EOI。
     */
    private fun jpegEnd(file: File): Long? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            if (len < 4) return@use null
            val head = ByteArray(2)
            raf.read(head)
            if (head[0] != 0xFF.toByte() || head[1] != 0xD8.toByte()) return@use null

            var p = 2L
            while (p + 3 < len) {
                raf.seek(p)
                if (raf.read() != 0xFF) return@use null
                var marker = raf.read()
                while (marker == 0xFF) marker = raf.read()   // 段间填充字节
                if (marker < 0) return@use null
                if (marker == 0xD9) return@use (p + 2)       // 没有图像数据的空图
                val segLen = (raf.read() shl 8) or raf.read()
                if (segLen < 2) return@use null
                p += 2 + segLen
                if (marker == 0xDA) break                    // SOS：以下是熵编码数据
            }
            if (p >= len) return@use null

            raf.seek(p)
            val buf = ByteArray(64 * 1024)
            var base = p
            var prev = -1
            while (true) {
                val n = raf.read(buf)
                if (n <= 0) break
                for (i in 0 until n) {
                    val b = buf[i].toInt() and 0xFF
                    if (prev == 0xFF && b == 0xD9) return@use (base + i + 1)
                    prev = b
                }
                base += n
            }
            null
        }
    }.getOrNull()

    /**
     * 给 JPEG 尾部追加 vivo 的实况私有段。
     *
     * 实测（vivo 10AF1M1Z030018H，逐字节比对）：vivo 的实况是**双写**的 ——
     *
     *  ① 媒体库列 `live_photo`：图片记录和视频记录各写一次（[pair] 产出的文件靠这个配对）；
     *  ② JPEG 尾部的这段私有数据：vivo 自拍写 566 字节，抖音 App 保存写 144 字节。
     *
     * 只写 ① 时，vivo 相册能认出实况、能播，但详情页**没有**「动态照片 / 关闭动态照片」
     * 菜单；微信、抖音、小红书读的是文件，文件里什么都没有，于是朋友圈提示
     * 「部分实况照片格式不标准，将作为静态照片发表」，抖音/小红书的相册里也没有实况分类。
     *
     * 补上 ② 之后相册菜单立刻出现（真机验证），结构照抄抖音保存的那份，
     * 只把里面的 id 换成我们自己写入媒体库的值。
     *
     * 只用于「同名配对」模式。单文件模式（XMP + 尾部视频）里，视频偏移是从文件末尾
     * 往前算的，再往后面接一段会破坏 `MicroVideoOffset`，两者不能混用。
     */
    fun appendVivoTail(image: File, id: String) {
        if (!isJpeg(image)) return
        val end = jpegEnd(image) ?: return
        runCatching {
            RandomAccessFile(image, "rw").use { raf ->
                // 已有旧段先截掉，避免重复处理时越叠越长
                if (end < raf.length()) raf.setLength(end)
                raf.seek(raf.length())
                raf.write(vivoTail(id))
            }
        }
    }

    /**
     * 构造那段私有数据（144 字节，与抖音保存的一致）。
     *
     * 布局：`vivo` + JSON + JSON 长度(4, 大端) + `cameralbum!` + 段长(4, 大端)
     *      + id + `FF FF FF FF` + 11 字节固定魔数。
     * 段长 = 4 + id 长度 + 4 + 11，实测值 47。
     */
    private fun vivoTail(id: String): ByteArray {
        val json = "{\"com.android.camera.livephoto\":\"$id\",\"version\":2014}"
        val out = java.io.ByteArrayOutputStream()
        out.write("vivo".toByteArray(Charsets.US_ASCII))
        out.write(json.toByteArray(Charsets.US_ASCII))
        out.write(be32(json.length))
        out.write("cameralbum!".toByteArray(Charsets.US_ASCII))
        out.write(be32(4 + id.length + 4 + 11))
        out.write(id.toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
        out.write(hexToBytes(VIVO_TAIL_MAGIC))
        return out.toByteArray()
    }

    private fun be32(v: Int) = byteArrayOf(
        ((v shr 24) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte(),
    )

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    fun isJpeg(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(3)
            raf.read(head) == 3 &&
                head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() &&
                head[2] == 0xFF.toByte()
        }
    }.getOrDefault(false)

    /** 认 `ftyp` box：MP4 / MOV 都有，这是「能当 MP4 塞进去」的最低判据。 */
    fun isMp4(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(12)
            if (raf.read(head) != 12) return@use false
            String(head, 4, 4, Charsets.US_ASCII) == "ftyp"
        }
    }.getOrDefault(false)

    /**
     * 生成单文件动态照片：JPEG 里插 XMP + 末尾追加 MP4。
     *
     * 全程流式读写，不把视频读进内存 —— 一段 4K 视频几百 MB，读进来就直接 OOM。
     */
    fun embed(image: File, video: File, out: File): File {
        require(isJpeg(image)) { "单文件动态照片只支持 JPEG 图片，请选择 jpg / jpeg" }
        require(isMp4(video)) { "视频不是标准 MP4 容器，无法塞进动态照片" }

        val videoLen = video.length()
        val insertAt = findInsertPos(image)
        val segment = buildXmpSegment(videoLen)

        out.parentFile?.mkdirs()
        FileInputStream(image).use { fin ->
            out.outputStream().use { os ->
                copyExactly(fin, os, insertAt)
                os.write(segment)
                fin.copyTo(os)
            }
        }
        // 视频必须原样接在最后，且顺序不能反：XMP 里的长度是按「文件末尾往前的字节数」算的
        FileInputStream(video).use { vin ->
            java.io.FileOutputStream(out, true).use { os -> vin.copyTo(os) }
        }
        return out
    }

    /**
     * 同名配对：两个文件同名前缀，扩展名各用自己的。
     *
     * 前缀必须一致 —— 相册是靠「同名」把图片和视频配成一张的，名字对不上就当成
     * 两个不相干的文件。目录里已有同名时自动加 (1)(2)…，绝不覆盖。
     */
    fun pair(image: File, video: File, dir: File, stem: String): List<File> {
        dir.mkdirs()
        val base = uniqueStem(dir, stem)
        val imgExt = if (isJpeg(image)) "jpg" else image.extension.lowercase().ifBlank { "jpg" }
        val img = File(dir, "$base.$imgExt")
        val vid = File(dir, "$base.mp4")
        image.copyTo(img, overwrite = false)
        video.copyTo(vid, overwrite = false)
        return listOf(img, vid)
    }

    /**
     * 单文件模式的落点：目录里已存在同名时加 (1)(2)…，绝不覆盖。
     *
     * 不能直接把 `File(dir, "名字.jpg")` 交给 embed 写 —— 那会悄悄盖掉上一次的
     * 产物，用户重复合成时前一份就没了。
     */
    fun uniqueOut(dir: File, stem: String): File {
        dir.mkdirs()
        return File(dir, uniqueStem(dir, stem) + ".jpg")
    }

    // ==================== 内部实现 ====================

    /**
     * 找出 XMP 段该插的位置：跳过 SOI 之后的连续 APPn / COM 段，插在第一个
     * 非这类段之前（也就是 Exif 之后、DQT 之前）。
     *
     * 为什么不直接插在 SOI 后面：Exif 规范要求 Exif APP1 紧跟 SOI，把 XMP 塞在
     * 它前面会让部分严格的解析器认不出 Exif（拍摄参数就丢了）。
     */
    private fun findInsertPos(image: File): Int {
        val buf = ByteArray(1024 * 1024)
        val read = RandomAccessFile(image, "r").use { it.read(buf) }
        if (read < 4) return 2
        if (buf[0] != 0xFF.toByte() || buf[1] != 0xD8.toByte()) return 2

        var p = 2
        while (p + 4 <= read) {
            if (buf[p] != 0xFF.toByte()) break
            val marker = buf[p + 1].toInt() and 0xFF
            // 填充字节（连续 FF）跳过
            if (marker == 0xFF) {
                p++
                continue
            }
            // 到 SOS（图像数据开始）或 EOI 还找不到落点，说明结构异常，退回 SOI 之后
            if (marker == 0xDA || marker == 0xD9) break
            val segLen = ((buf[p + 2].toInt() and 0xFF) shl 8) or
                (buf[p + 3].toInt() and 0xFF)
            if (segLen < 2) break
            val isAppOrCom = marker in 0xE0..0xEF || marker == 0xFE
            if (!isAppOrCom) break
            p += 2 + segLen
        }
        // 段比预读缓冲还大（极少见）时就别冒险了，插在 SOI 之后仍然合法
        return if (p in 2..read) p else 2
    }

    /**
     * 拼一个 APP1 段：`FF E1` + 长度 + XMP 签名 + XML。
     *
     * 长度字段按 JPEG 规范只算「长度字段自身 + 内容」，不含 marker 那两字节。
     */
    private fun buildXmpSegment(videoLen: Long): ByteArray {
        val xml = """
            <?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
             <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description rdf:about=""
                xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
                xmlns:Container="http://ns.google.com/photos/1.0/container/"
                xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
                GCamera:MotionPhoto="1"
                GCamera:MotionPhotoVersion="1"
                GCamera:MotionPhotoPresentationTimestampUs="0"
                GCamera:MicroVideo="1"
                GCamera:MicroVideoVersion="1"
                GCamera:MicroVideoOffset="$videoLen"
                GCamera:MicroVideoPresentationTimestampUs="0">
               <Container:Directory>
                <rdf:Seq>
                 <rdf:li rdf:parseType="Resource">
                  <Container:Item
                    Item:Mime="video/mp4"
                    Item:Semantic="MotionPhoto"
                    Item:Length="$videoLen"
                    Item:Padding="0"/>
                 </rdf:li>
                </rdf:Seq>
               </Container:Directory>
              </rdf:Description>
             </rdf:RDF>
            </x:xmpmeta>
            <?xpacket end="w"?>
        """.trimIndent()

        val payload = XMP_SIG.toByteArray(Charsets.UTF_8) + xml.toByteArray(Charsets.UTF_8)
        val seg = ByteArray(4 + payload.size)
        seg[0] = 0xFF.toByte()
        seg[1] = 0xE1.toByte()
        val total = payload.size + 2
        seg[2] = ((total shr 8) and 0xFF).toByte()
        seg[3] = (total and 0xFF).toByte()
        System.arraycopy(payload, 0, seg, 4, payload.size)
        return seg
    }

    /** 只搬运前 n 个字节（图片头部），剩下的交给调用方继续 copyTo。 */
    private fun copyExactly(fin: FileInputStream, out: java.io.OutputStream, n: Int) {
        val chunk = ByteArray(16 * 1024)
        var left = n
        while (left > 0) {
            val want = minOf(chunk.size, left)
            val got = fin.read(chunk, 0, want)
            if (got <= 0) break
            out.write(chunk, 0, got)
            left -= got
        }
    }

    /** 让文件名前缀不和目录里已有的重名。 */
    private fun uniqueStem(dir: File, stem: String): String {
        val names = dir.listFiles()?.filter { it.isFile }?.map { it.nameWithoutExtension }
            ?: emptyList()
        if (stem !in names) return stem
        var i = 1
        while ("$stem ($i)" in names) i++
        return "$stem ($i)"
    }

    /** 从 uri 取显示名，去掉可能带进来的路径分隔符。 */
    private fun displayName(ctx: Context, uri: Uri): String {
        val queried = runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull().orEmpty()
        return queried.replace('/', '_').replace('\\', '_').ifBlank { "live_pick" }
    }

    private fun fallbackExt(ctx: Context, uri: Uri): String {
        val mime = runCatching { ctx.contentResolver.getType(uri) }.getOrNull().orEmpty()
        return when {
            mime == "image/png" -> "png"
            mime == "image/webp" -> "webp"
            mime == "image/heic" || mime == "image/heif" -> "heic"
            mime.startsWith("image/") -> "jpg"
            mime == "video/quicktime" -> "mov"
            mime == "video/webm" -> "webm"
            else -> "mp4"
        }
    }
}
