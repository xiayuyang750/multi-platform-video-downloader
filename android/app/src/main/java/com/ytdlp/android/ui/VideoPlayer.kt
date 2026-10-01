package com.ytdlp.android.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.alpha
import androidx.media3.common.Player
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.ytdlp.android.engine.Video

/**
 * 播放区：默认显示封面 + 播放按钮，点了才换成真正的播放器。
 *
 * 为什么不自动起播：网页端也是「先封面、点击才播」。自动播放会立刻拉视频流
 * （B站一个 1080p 档就是几百 MB 的连接），用户可能只想下载，白白耗流量。
 *
 * @param source 播放源。默认取 video.playSource（本地文件优先于在线直链）；
 *               解析页传「刚下载完的产物路径」，因为那份解析结果里还没有
 *               localPath（要等重新读历史才有）。
 *               传 null 表示确实不可播，界面上会说明原因。
 */
@Composable
fun PlayerBox(
    video: Video,
    modifier: Modifier = Modifier,
    source: String? = video.playSource,
) {
    // 换视频或换播放源都重置成「未播放」，否则会拿旧状态去播新链接
    var playing by remember(video.id, source) { mutableStateOf(false) }
    // 是否已经渲染出第一帧。
    // 这一条是被用户指出来才发现的：原来点播放就立刻把播放器换到前台，
    // 而 ExoPlayer 从 prepare 到出画面有一两秒，PlayerView 底色是黑的，
    // 于是用户看到「黑屏一闪」。正确做法是让封面一直垫在下面，
    // 等首帧真正出来再把播放器淡入 —— 观感上就是「封面停留片刻后开始播放」。
    var firstFrameReady by remember(video.id, source) { mutableStateOf(false) }

    // 视频实际宽高比。竖屏（抖音很常见）不能按 16:9 硬塞进横条里 ——
    // 那样画面会被压得很小。播放器出画面之前拿不到尺寸，所以先用 16:9 占位，
    // 拿到真实尺寸再切过去。
    var aspect by remember(video.id, source) { mutableStateOf(16f / 9f) }

    val playerAlpha by animateFloatAsState(
        targetValue = if (firstFrameReady) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "player-alpha",
    )
    // 封面与播放器交叉淡出：首帧到位的同一时刻，封面才开始退场。
    // 「开始播放就立刻隐藏封面」是不行的 —— 那又回到了黑屏。
    val coverAlpha by animateFloatAsState(
        targetValue = if (firstFrameReady) 0f else 1f,
        animationSpec = tween(durationMillis = 220),
        label = "cover-alpha",
    )

    // 播放区限高。
    //
    // 不设上限会踩一个实测出来的坑：竖屏视频按真实比例算，高度能到 1500px 以上，
    // 而播放器处在可滚动列表里、下方还有按钮，于是它的**下边缘会落到应用底部
    // 导航栏后面**；Media3 的控制器（进度条、时间）恰恰贴在播放器底部，正好被
    // 导航栏盖住 —— 实测点击播放器后 `exo_progress` 节点根本读不到。
    // 压到屏高的 40% 后，播放器连同上下内容能一起落在可视区内，进度条始终够得着。
    // 横屏视频算出来本来就不高，不受这个上限影响。
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val boxHeight = (maxWidth / aspect).coerceAtMost(screenHeight * 0.40f)

        Box(
            Modifier
                .fillMaxWidth()
                .height(boxHeight)
                .clip(RoundedCornerShape(Dim.radiusSm))
                .background(tone.surfaceHover)
        ) {
            // 封面垫在底层。首帧到位后它淡出；在那之前「播放中」也不该露出
            // 「需下载才能播放」那类提示，所以把两种状态分开传下去。
            if (coverAlpha > 0f) {
                CoverLayer(
                    video = video,
                    playable = source != null,
                    started = playing,
                    isLocal = source != null && source.startsWith("/"),
                    onPlay = { playing = true },
                    modifier = Modifier.fillMaxSize().alpha(coverAlpha),
                )
            }

            if (playing && source != null) {
                ExoPlayerView(
                    source = source,
                    onFirstFrame = { firstFrameReady = true },
                    onVideoAspect = { aspect = it },
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(playerAlpha),
                )
            }
        }
    }
}

/**
 * 封面层。
 *
 * @param playable 有没有可播源。没有就把原因写在封面上，别让用户对着封面点半天没反应。
 * @param started  是否已经点过播放。点过之后就只剩封面图本身 —— 播放按钮和
 *                 「在线播放」角标都该收起来，播放器接管画面。
 */
@Composable
private fun CoverLayer(
    video: Video,
    playable: Boolean,
    started: Boolean,
    isLocal: Boolean,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (video.thumbnail.isNotBlank()) {
            AsyncImage(
                model = video.thumbnail,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            // 没有封面就退回平台色块，至少能看出是哪个平台
            Box(Modifier.fillMaxSize().background(PlatformColor.of(video.platform).copy(alpha = 0.18f)))
        }

        when {
            !playable -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Dim.gapSm),
                        modifier = Modifier.padding(horizontal = 24.dp),
                    ) {
                        PlatformBadge(video.platform, size = 36)
                        androidx.compose.material3.Text(
                            "该站点是音视频分轨流，需下载后才能播放",
                            color = Color.White,
                            fontSize = Font.hint,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            !started -> {
                // 播放按钮压在封面中央，对应网页端的 .player-play
                Box(
                    Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable(onClick = onPlay),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "播放",
                        tint = Color.White,
                        modifier = Modifier.size(30.dp),
                    )
                }
                // 标明播的是本地文件还是在线流：本地文件不受直链过期影响，
                // 在线流隔一段时间可能就播不了了，用户有权知道区别
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(Dim.gapSm)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    androidx.compose.material3.Text(
                        if (isLocal) "播放已下载的文件" else "在线播放",
                        color = Color.White,
                        fontSize = Font.tabBadge,
                    )
                }
            }
        }
    }
}

/**
 * ExoPlayer 的 Compose 封装。
 *
 * 注意 player 用 remember(source) 绑定：播放源变了要重建，否则会继续播旧的
 * （在线直链带时效，重新解析后地址会变；本地文件与在线流之间切换也是同理）。
 */
@Composable
private fun ExoPlayerView(
    source: String,
    onFirstFrame: () -> Unit,
    onVideoAspect: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // remember 块只执行一次，直接引用 onFirstFrame 会捕获首次组合时的 lambda；
    // rememberUpdatedState 保证回调始终指向最新的那个
    val firstFrameCallback by rememberUpdatedState(onFirstFrame)
    val aspectCallback by rememberUpdatedState(onVideoAspect)

    val player = remember(source) {
        ExoPlayer.Builder(context).build().apply {
            val item = MediaItem.fromUri(toPlayableUri(source))
            // 抖音的直链 CDN 会校验 Referer，不带会被拒。下载路径由 Python 侧
            // 单独加了头，播放这条路径得在这里自己加。
            if (source.contains("douyinvod.com")) {
                val factory = DefaultHttpDataSource.Factory()
                    .setDefaultRequestProperties(mapOf("Referer" to "https://www.douyin.com/"))
                setMediaSource(
                    ProgressiveMediaSource.Factory(factory).createMediaSource(item)
                )
            } else {
                setMediaItem(item)
            }
            // 让 ExoPlayer 处理音频焦点：别的应用正在放音乐时应该暂停它、
            // 我们播完再把焦点还回去。不处理会出现两路声音一起响。
            setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            // 首帧真正渲染出来后再让界面把播放器淡入，避免露出黑底
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    firstFrameCallback()
                }

                /**
                 * 把视频真实宽高比报给界面，用来定播放区的高度。
                 *
                 * 竖屏视频录下来常常带旋转标记（unappliedRotationDegrees），
                 * 这时 VideoSize 里的宽高还是「转之前」的，得自己换过来 ——
                 * 不换的话一条竖屏视频会被当成横屏来排版，画面又被压小。
                 */
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width <= 0 || videoSize.height <= 0) return
                    val rotated = videoSize.unappliedRotationDegrees % 180 != 0
                    val w = if (rotated) videoSize.height else videoSize.width
                    val h = if (rotated) videoSize.width else videoSize.height
                    if (h <= 0) return
                    // 夹一下范围：畸形值（0、极端长宽比）会把布局算爆
                    aspectCallback((w.toFloat() / h).coerceIn(0.4f, 3f))
                }
            })
            playWhenReady = true
            prepare()
        }
    }

    // 离开界面就把播放器释放掉，否则会继续在后台占着解码器和网络连接
    DisposableEffect(player) {
        onDispose { player.release() }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
                // 播放器背景保持黑色，与网页端 .player-video 一致
                setShutterBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        modifier = modifier,
        onRelease = { view -> view.player = null },
    )
}

/**
 * 把播放源整理成 ExoPlayer 认的 URI。
 *
 * 本地产物给的是绝对路径（/storage/emulated/0/Download/xxx.mp4），
 * 它不是合法 URI（没有 scheme），直接丢给 MediaItem.fromUri 会被当成
 * 相对地址而解析失败，所以要先转成 file:// 形式。
 */
private fun toPlayableUri(source: String): String =
    if (source.startsWith("/")) {
        android.net.Uri.fromFile(java.io.File(source)).toString()
    } else {
        source
    }
