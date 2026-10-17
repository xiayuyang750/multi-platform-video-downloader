# -*- coding: utf-8 -*-
"""安卓端引擎层：解析 / 下载 / 历史 / 设置。

和 windows/engine.py 的关系
--------------------------
两端是同一份规格的两个实现，行为要对齐：

  一致的 —— 平台识别、Cookie 规则、报错翻译、历史表结构与字段、
            「备用链路拿到的裸直链要用库里标题命名文件名」这条策略。
  不同的 —— 只有「怎么调 yt-dlp」。Windows 端拼命令行 + subprocess；
            安卓端只能进程内调 yt_dlp 的 Python API：Chaquopy 里的 CPython
            不是可执行文件，根本没有 python 可 fork，而且实测安卓上 fork
            出的子进程偶发 SIGSEGV，能少一次 fork 就少一次。

剥掉的 Windows 专有部分：--windows-filenames（改 ydl 参数）、%APPDATA%、
tkinter 文件夹选择框、explorer 打开目录。

日志统一用 print()，Chaquopy 会转发到 logcat 的 python 标签下。
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import sqlite3
import subprocess
import threading
import time
import urllib.request
import zlib
from pathlib import Path
from urllib.parse import urlparse

import yt_dlp

BASE_DIR = Path(__file__).resolve().parent
CONFIG_FILE = BASE_DIR / "defaults.json"

# 直链拿到的是裸地址（没有元数据），文件名要据此改用「库里标题」拼。
# 抖音这几个域名是实测出来的：采样 160 条直链，全部落在 365yg.com / amemv.com 上，
# 一条 douyinvod.com 都没有 —— 只认 douyinvod.com 会让文件名退化成 URL 片段
# （实测会变成「内部路径token [整个查询串].mp4」）。
# 小红书（xhscdn.com / rednotecdn.com）是后加的：它的视频直链同样是「一个完整 mp4」，
# 交给 yt-dlp 只会走通用提取器并报 Unsupported URL —— 界面上的表现就是
# 「下载失败：不支持这种链接」（用户实测反馈）。走这条路还能顺带拿到 Range 分块并行。
# 与 Windows 端 engine.DIRECT_URL_HOSTS 对应，改一处要同步另一处。
DIRECT_URL_HOSTS = ("douyinvod.com", "365yg.com", "amemv.com", "video.twimg.com",
                    "xhscdn.com", "rednotecdn.com")

# 直链分块下载的读取粒度。太小会让线程调度开销盖过收益，1 MiB 是实测的折中。
_DIRECT_READ = 1 << 20

PLATFORM_RULES = [
    ("YouTube", r"(^|//)([a-z0-9-]+\.)*youtu\.?be"),
    ("B站", r"(^|//)([a-z0-9-]+\.)*(bilibili\.com|b23\.tv)"),
    ("抖音", r"(^|//)([a-z0-9-]+\.)*(douyin\.com|iesdouyin\.com)"),
    # TikTok 与国内抖音在 yt-dlp 里是两个独立提取器，可用性也完全不同
    # （TikTok 通常能解析，抖音常因境外 IP + 签名拦截失败），所以分开归类。
    ("TikTok", r"(^|//)([a-z0-9-]+\.)*tiktok\.com"),
    ("X", r"(^|//)([a-z0-9-]+\.)*(x\.com|twitter\.com)"),
    ("Instagram", r"(^|//)([a-z0-9-]+\.)*instagram\.com"),
    # 小红书。App 里「复制链接」给的是 xhslink.cn 短链（实测 6 条真实分享链接
    # 全部是 .cn；.com 也存在、同样跳向 xiaohongshu.com，所以两个都认）。
    # 带 Cookie 时 yt-dlp 跟随跳转到 www.xiaohongshu.com/(explore|discovery/item)/<id>
    # 再交给它的 XiaoHongShu 提取器 —— 实测视频类笔记 3/3 能解析。
    # 这一条比上面几条多一道「域名后面必须是分隔符或结束」的检查：不加的话
    # xhslink.cn.attacker.com 这种也会被判成小红书。上面几条没这道检查是
    # 既有问题（见 docs/1.0.4-测试与安全演练报告.md 的 A1），不在此处一并改。
    ("小红书", r"(^|//)([a-z0-9-]+\.)*(xiaohongshu\.com|xhslink\.(?:cn|com))(?![a-z0-9.-])"),
    # 微博。实测（用 yt-dlp 自带的三个官方样例）：weibo.com/tv/show/<id>、
    # m.weibo.cn/status/<id>、weibo.com/<uid>/<mid> 三种形态**都能开箱解析**
    # （1080p / 720p 档位齐全，不需要登录态）。
    # 注意：微博分享里的 `t.cn` 短链**没认** —— t.cn 是新浪的通用短链，
    # 认了会把非微博内容也判成微博；等拿到真实分享链接再决定。
    ("微博", r"(^|//)([a-z0-9-]+\.)*(weibo\.com|weibo\.cn)(?![a-z0-9.-])"),
]

# 安卓文件名硬上限是 255 **字节**（ext4 / FUSE 的限制都是按字节算的）。
# 中文一个字 3 字节、emoji 4 字节，所以不能像 Windows 端那样按字符数估算。
MAX_NAME_BYTES = 200

# 「链接」的形状：scheme://主机，主机必须带点。
# 主机要求带点，是为了不把 "abc://def" 这种随手打的当成链接放过去。
_URL_SHAPE = re.compile(r"^[a-z][a-z0-9+.\-]*://[^\s/?#]+\.[^\s/?#]+", re.I)


def looks_like_url(text: str) -> bool:
    """判断输入是不是一条链接。

    用户嘴里说的「链接」就是 scheme://主机 这个形状。不拦的话，一段随手打的字
    会一路送到 yt-dlp，最后报出 `ERROR: [generic] '……' is not a valid URL` ——
    用户看不懂，还会以为是站点出了问题（实测就是这么被反馈上来的）。
    """
    return bool(_URL_SHAPE.match((text or "").strip()))


# 图片扩展名。MD5 改写对图片要另走一条路（见 Engine._rewrite_image_md5）：
# ffmpeg -c copy 对 jpg/png/webp 产出的字节与原件完全相同，改不动 MD5。
IMAGE_EXTS = {
    ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".avif", ".heic", ".heif",
}


def _copy_partial(src_file, dst_file, length: int) -> None:
    """把 src 开头 length 个字节拷到 dst。分块拷，不整份读进内存。"""
    left = length
    while left > 0:
        block = src_file.read(min(left, _DIRECT_READ))
        if not block:
            break
        dst_file.write(block)
        left -= len(block)


def file_md5(path: str) -> str:
    """流式算一个文件的 MD5。

    必须分块读：抖音高清档动辄上百 MB，整份读进内存会直接把应用撑爆
    （安卓上给 Python 的堆本来就紧）。1 MiB 块是实测的折中。
    """
    h = hashlib.md5()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def detect_platform(url: str) -> str:
    u = (url or "").lower()
    for name, pattern in PLATFORM_RULES:
        if re.search(pattern, u, re.I):
            return name
    return "其他"


# ==================== 抖音：直接调公开接口 ====================
# 这条路径的发现过程：对比一个能稳定解析抖音的参考实现，从它的 dex 里
# 提取字符串后发现它调的是 aweme.snssdk.com 这个接口，并把 UA 伪装成
# 抖音 App 自身。实测这条接口不需要 a_bogus 签名、不需要登录态，直连即可。

DOUYIN_APP_UA = (
    "com.ss.android.ugc.aweme/260201 (Linux; U; Android 12; zh_CN; Pixel 4; "
    "Build/SP1A.210812.016; Cronet/TTNetVersion)"
)

DOUYIN_FEED_API = "https://aweme.snssdk.com/aweme/v1/feed/?aweme_id={id}"

# 展开分享短链时用的普通浏览器 UA
_BROWSER_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)


def douyin_video_id(url: str) -> str:
    """从抖音分享链接里取出作品 ID。

    两种形态都要支持：
      - 长链 https://www.douyin.com/video/7689396203758521646 → 直接正则
      - 短链 https://v.douyin.com/XiRYwhL6CnQ/                → 先跟随跳转
    """
    text = url or ""
    m = (
        re.search(r"/(?:video|note)/(\d{10,25})", text)
        or re.search(r"modal_id=(\d{10,25})", text)
    )
    if m:
        return m.group(1)

    # 短链要跟随一次跳转才能看到真实地址（实测 302 到 www.douyin.com/video/<id>）
    if "v.douyin.com" in text or "iesdouyin.com" in text:
        try:
            req = urllib.request.Request(text, headers={"User-Agent": _BROWSER_UA})
            with urllib.request.urlopen(req, timeout=15) as resp:
                final = resp.geturl()
        except Exception as exc:  # noqa: BLE001
            print(f"[douyin] 展开短链失败：{exc}")
            return ""
        m = re.search(r"/(?:video|note)/(\d{10,25})", final or "")
        if m:
            return m.group(1)
    return ""


def _tier_label(height: int, vcodec: str, filesize: int | None) -> str:
    """拼一条档位的展示文案，如「720p · H.265 · 37.4 MB」。

    在 Python 侧拼好、界面直接显示 —— 两端（安卓 / Windows）与 Kotlin 侧
    都不必各写一份格式化逻辑，避免三处各算出不一样的字符串。
    """
    parts = []
    if height:
        parts.append(f"{height}p")
    codec = {
        "h264": "H.264", "h265": "H.265", "vp9": "VP9", "av1": "AV1",
    }.get(vcodec or "", "")
    if codec:
        parts.append(codec)
    if filesize:
        parts.append(f"{filesize / 1024 / 1024:.1f} MB")
    return " · ".join(parts) or "原始画质"


def douyin_collect_tiers(video: dict) -> list[dict]:
    """把 bit_rate 数组整理成「给界面用的档位全集」，按好 → 差排序。

    与 Kotlin 侧 DouyinParser.collectTiers 是同构实现，改一处要同步另一处。

    每条：{label, height, vcodec, filesize, url, hash}
      label    给用户看的（如「720p · H.265 · 37.4 MB」）
      height   分辨率 —— 排序和「逐条回退」都靠它
      vcodec   "h264" / "h265"
      filesize 字节数；接口没给就是 None
      url      该档位的直链
      hash     平台自报的 MD5（play_addr.file_hash）；没给就是空串

    排序 key = (分辨率, 是否 H.264, 码率)，**与默认下载档的选取规则完全一致**，
    所以列表第一项就是默认选中项 —— 界面不必再算一遍。

    分辨率排第一位：宁可要 720p 的 H.265，也不退回 576p 的 H.264 —— 实测同一支
    视频，默认档给的是 1024x576 H.264@995kbps，而 bit_rate 里有 1280x720 H.265，
    按分辨率优先后者才是画质更好的那个（体积还只有一半）。
    同分辨率下优先 H.264：H.265 同画质体积小，但兼容性差（部分播放器 / 相册 /
    剪辑软件不认），而下载产物是要长期留着的。
    """
    rows: list[dict] = []
    for item in video.get("bit_rate") or []:
        play = item.get("play_addr") or {}
        urls = play.get("url_list") or []
        if not urls or not urls[0]:
            continue
        size = item.get("data_size") or play.get("data_size")
        rows.append({
            "height": int(play.get("height") or 0),
            "vcodec": "h265" if int(item.get("is_h265") or 0) != 0 else "h264",
            "fmt": str(item.get("format") or ""),
            "kbps": int(item.get("bit_rate") or 0),
            "filesize": int(size) if size else None,
            "url": urls[0],
            # 平台自报的 MD5（网页版实测每个档位都带 file_hash，32 位十六进制）。
            # 有它就能在**下载前**先把 MD5 显示出来；真伪等文件下来再核对。
            "hash": str(play.get("file_hash") or ""),
        })

    # 默认档（video.play_addr）不一定出现在 bit_rate 里 —— 实测那条 ASMR 视频就是：
    # bit_rate 里全是 H.265 档，而默认档另有一条 1024x576 的流（旧工具下载到的
    # 84MB H.264 文件正是来自它）。漏掉它，用户会以为「这条视频只能选 H.265」。
    # 所以：默认地址不与任何已收集档位重复时，作为额外一档补进来。
    # 同一段逻辑也顺带覆盖「bit_rate 整个为空」（老作品 / 接口降级）的情况 ——
    # 那时 rows 是空的，这个地址自然就成了唯一一档。
    #
    # 编码**不猜**：play_addr 字段本身不带 vcodec，猜错等于标签撒谎，
    # 所以留空 —— 标签里就只显示分辨率。
    pa = video.get("play_addr") or {}
    pa_urls = pa.get("url_list") or []
    default_url = pa_urls[0] if pa_urls else ""
    if default_url and not any(r["url"] == default_url for r in rows):
        rows.append({
            "height": int(pa.get("height") or 0),
            "vcodec": "",
            "fmt": "mp4",
            "kbps": 0,
            "filesize": None,
            "url": default_url,
            "hash": str(pa.get("file_hash") or ""),
        })

    rows.sort(
        key=lambda r: (r["height"], 1 if r["vcodec"] == "h264" else 0, r["kbps"]),
        reverse=True,
    )
    for r in rows:
        r["label"] = _tier_label(r["height"], r["vcodec"], r["filesize"])
    return rows


def douyin_pick_tiers(video: dict) -> tuple:
    """从档位全集里挑两条地址：能直接播的、默认下载的。

    与 Kotlin 侧 DouyinParser.pickTiers 逻辑一致（那边是浏览器模式兜底用的），
    改一处要同步另一处。

    抖音的 video.play_addr 只是「默认档」，bit_rate 数组才是全部档位。

    - 默认下载档 = 档位全集的第一项（规则见 collect_tiers）
    - 播放档 = 第一条 H.264 + 完整 mp4；没有就退回默认地址
      播放比下载更保守：H.265 在浏览器和部分设备上放不出来。
    """
    tiers = douyin_collect_tiers(video)
    best_any = tiers[0]["url"] if tiers else ""
    best_play = next(
        (t["url"] for t in tiers if t["vcodec"] == "h264" and t["fmt"] == "mp4"),
        "",
    )

    # 没有符合的档位就退回默认地址，保证至少有个能用的
    if not best_play:
        urls = ((video.get("play_addr") or {}).get("url_list")) or []
        best_play = urls[0] if urls else ""
    if not best_any:
        best_any = best_play
    return best_play, best_any


# ==================== yt-dlp 平台：把 formats 归并成档位 ====================
# 与抖音那条的**关键区别**：yt-dlp 平台给的是「流」，不是「文件」。
# YouTube / B站 早已全面 DASH 化（实测 YouTube 53 个格式里 0 个音视频合一，
# B站 也全是 audio only + video only），画面和声音是两条分开的流 ——
# 单给一条 URL 拿不到完整视频。所以这里的档位不是「一条地址」，而是一个
# **分辨率档** + 一条交给 yt-dlp 的选流表达式（selector），由它去取流并合流。


def _ytdlp_vcodec_name(raw: str) -> str:
    """把 yt-dlp 的 vcodec 串归成档位标签用的短名。

    yt-dlp 给的是编码全名（avc1.640028 / hev1.1.6.L93.B0 / vp09.00.51 / av01.0.05M），
    这里只保留人看得懂的那一层：H.264 / H.265 / VP9 / AV1。
    认不出来就返回空串 —— 标签里只显示分辨率，不硬塞一个错的编码名。
    """
    v = (raw or "").lower()
    if v.startswith(("avc1", "avc3")):
        return "h264"
    if v.startswith(("hev1", "hvc1")):
        return "h265"
    if v.startswith(("vp9", "vp09")):
        return "vp9"
    if v.startswith("av01"):
        return "av1"
    return ""


def ytdlp_collect_tiers(info: dict) -> list[dict]:
    """把 yt-dlp 的 formats 归并成「给界面用的档位全集」，按好 → 差排序。

    每条：{label, height, vcodec, filesize, selector}

    归并规则：
      - 按**分辨率高度**分档（1080p / 720p / …），同一高度只保留一条
      - 纯音频流不参与选择 —— 它是 `+ba` 自动带上的那部分，用户不需要单独挑
      - 同一高度优先 H.264：兼容性更好（部分播放器 / 剪辑软件不认 H.265 / AV1），
        与抖音那边的规则保持一致。实测 B站 每个高度都有 avc1 / hvc1 / av01 三种，
        按码率也是 H.264 最高，所以这条规则同时也等于「该高度画质最好的那条」
      - **selector 钉住具体格式**（`<视频格式ID>+<音频格式ID>`），而不是用
        `bv*[height<=H]+ba` 这种范围表达式 —— 实测踩过坑：范围表达式等于把编码的
        决定权交给 yt-dlp，它会挑 AV1，于是标签上写着 H.264、实际下到的却是 AV1，
        体积也对不上（88MB vs 标签 147MB）。钉住之后标签写的才真是会下到的那条。
        尾部仍留两级回退（`<视频ID>+ba` / `b[height<=H]`），免得格式 ID 变了直接失败
      - 体积 = 该档视频流 + 最好的那条音频流；**拿不到就省略**，不编一个数字出来
    """
    formats = info.get("formats") or []

    # 音频只用于估体积和被 selector 引用：合并后的成品 = 视频流 + 音频流。
    # 按码率挑最好的一条（与 yt-dlp 的 `ba` 取法一致，这样估算才对得上实际）。
    audio_id, audio_size, audio_tbr = "", 0, -1
    for f in formats:
        if f.get("vcodec") == "none" and f.get("acodec") != "none":
            tbr = int(f.get("tbr") or 0)
            if tbr > audio_tbr:
                audio_tbr = tbr
                audio_id = str(f.get("format_id") or "")
                audio_size = int(f.get("filesize") or f.get("filesize_approx") or 0)

    by_height: dict[int, dict] = {}
    for f in formats:
        if not f.get("url") or f.get("vcodec") == "none":
            continue
        height = int(f.get("height") or 0)
        if not height:
            continue
        name = _ytdlp_vcodec_name(f.get("vcodec"))
        # 同高度优先 H.264（rank=1），其余排后面；再比码率
        key = (1 if name == "h264" else 0, int(f.get("tbr") or f.get("vbr") or 0))
        cur = by_height.get(height)
        if cur is None or key > cur["_key"]:
            video_size = int(f.get("filesize") or f.get("filesize_approx") or 0)
            vid_id = str(f.get("format_id") or "")
            if vid_id and audio_id:
                selector = f"{vid_id}+{audio_id}/{vid_id}+ba/b[height<={height}]"
            elif vid_id:
                selector = f"{vid_id}+ba/b[height<={height}]"
            else:
                selector = f"b[height<={height}]"
            by_height[height] = {
                "_key": key,
                "height": height,
                "vcodec": name,
                "filesize": (video_size + audio_size) or None,
                "selector": selector,
            }

    rows = sorted(by_height.values(),
                  key=lambda r: (r["height"], r["_key"]), reverse=True)
    for r in rows:
        r.pop("_key", None)
        r["label"] = _tier_label(r["height"], r["vcodec"], r["filesize"])
    return rows


# ==================== 报错翻译 ====================
# 与 windows/engine.py 的 ERROR_HINTS 保持一致：yt-dlp 的报错是英文且面向
# 开发者，普通用户看不懂。这里翻译成「说明 + 该怎么办」，原始报错附在后面。

ERROR_HINTS = [
    (
        "No video could be found in this tweet",
        "这条推文里没有视频（只有文字或图片），无法解析。",
    ),
    (
        "Fresh cookies",
        "该平台需要「新鲜的 Cookie」才能解析。请重新导出 Cookie 并导入；"
        "导出前要确保手机浏览器或电脑浏览器里处于登录状态。",
    ),
    (
        "Sign in to confirm your age",
        "该视频需要登录并确认年龄后才能访问，请提供 Cookie。",
    ),
    (
        "Private video",
        "这是私享视频，需要登录该平台后才能访问。",
    ),
    (
        "This video is unavailable",
        "该视频不可用：可能已被删除、设为私享，或有地区限制。",
    ),
    (
        "Video unavailable",
        "该视频不可用：可能已被删除或有地区限制。",
    ),
    (
        "Requested format is not available",
        "没有找到可用的视频格式。",
    ),
    (
        "HTTP Error 403",
        "被平台拒绝（403）。可能需要提供 Cookie，或换个网络 —— "
        "部分平台（如抖音）对境外 IP 有限制。",
    ),
    (
        "HTTP Error 404",
        "链接已失效（404），视频可能已被删除。",
    ),
    (
        "timed out",
        "网络超时。境外站点（YouTube / Instagram / X）需要开着 VPN 才能访问。",
    ),
    (
        # 实测在没开代理时解析 Instagram 会抛这个，比 timed out 更常见
        "Network is unreachable",
        "连不上网络。请检查：\n"
        "① 代理是不是「全局」模式 —— 全局会把国内站点的流量也送出境，"
        "抖音 / B站 会被拒；这种情况请改成「规则 / 智能分流」或「直连」模式；\n"
        "② 如果解析的是境外站点（YouTube / Instagram / X / TikTok），"
        "则需要代理确实在生效。",
    ),
    (
        # 断网时实测抛这个（TLS 握手被截断）。位置很关键：必须排在
        # "Please report this issue" 之前 —— yt-dlp 会把那句附在所有非预期
        # 错误后面，排到它后面就会被抢走，于是"没网"被说成"站点改版"。
        "UNEXPECTED_EOF_WHILE_READING",
        "连不上网络（连接被中断）。请检查：\n"
        "① 手机是否连着 Wi-Fi 或流量；\n"
        "② 代理是不是「全局」模式 —— 全局会把国内站点的流量也送出境，"
        "抖音 / B站 会被拒；这种情况请改成「规则 / 智能分流」或「直连」模式；\n"
        "③ 如果解析的是境外站点（YouTube / Instagram / X / TikTok），"
        "则需要代理确实在生效。",
    ),
    (
        "Unsupported URL",
        "不支持这种链接。",
    ),
    (
        "Unable to extract",
        "无法从页面中提取视频信息。可能是该站点改版了，建议更新应用内置的解析引擎。",
    ),
    # ---- 下面几条是实测各平台失败后补的，都是用户真实会撞到的 ----
    (
        "Unexpected response from webpage request",
        "TikTok 拒绝了这次请求。常见原因有三种：\n"
        "① 该视频有地区限制（需要对应地区的网络节点）；\n"
        "② TikTok 要求登录态，可到「设置」导入 Cookie 后重试；\n"
        "③ 站点改版，当前内置的解析引擎还处理不了。",
    ),
    (
        "empty media response",
        "Instagram 需要登录才能解析。\n"
        "请到「设置 → Cookie 文件」导入 Cookie（导出前确保浏览器里已登录 Instagram）。\n"
        "若导入后仍失败，可能是这条帖子本身不可访问（已删除或仅作者可见）。",
    ),
    (
        "login required",
        "该平台需要登录才能解析，请到「设置」导入 Cookie 后重试。",
    ),
    (
        "Please report this issue",
        "该站点可能改版了，当前内置的解析引擎还处理不了。\n"
        "可以先试试其他方式打开原视频，或等应用更新。",
    ),
    (
        "Unsupported URL",
        "不支持这种链接。请确认粘贴的是单条视频的分享链接，而不是主页或合集。",
    ),
]


def _brief(text: str, limit: int = 200) -> str:
    """把 yt-dlp 的多行英文报错压成一行并截断。

    界面空间有限：原始报错只是留给排查用的线索，不需要全文。
    不压的话会出现「一个报错占满整屏、用户还看不懂」的情况。
    """
    one_line = " ".join((text or "").split())
    return one_line[:limit] + "…" if len(one_line) > limit else one_line


# ==================== 按平台细化的错误提示 ====================
# 同一个错误码在不同平台上成因往往完全不同，只给通用说明等于没说：
#   HTTP 403 → 抖音是「境外 IP 被拦」，X 是「没带 Cookie」，TikTok 是「地区限制」
# 所以这里按平台 + 错误特征做二级匹配，命中的话优先于上面的通用表。

PLATFORM_ERROR_HINTS = {
    "抖音": {
        "403": "抖音拒绝了这次请求。最常见的原因是网络出口不对 —— "
               "抖音是国内平台，开着代理/VPN 走境外节点反而会被拦，请先关掉代理再试。",
        "Fresh cookies": "抖音需要登录态。若开着代理请先关掉，抖音对境外 IP 限制较严。",
    },
    "X": {
        "403": "X 对未登录访问限制很严，需要导入 Cookie（「设置 → Cookie 文件」）。"
               "导出前请确保浏览器里处于登录状态。",
        "No video could be found": "这条推文里没有视频（可能只有文字或图片）。"
                                   "如果你确认它有视频，那多半是 Cookie 过期了 —— 重新导出一次即可。",
        "429": "请求过于频繁，等几分钟再试。",
        "timed out": "连不上 X。境外站点需要代理，请检查网络。",
    },
    "Instagram": {
        "empty media response": "Instagram 需要登录。请到「设置」导入 Cookie"
                                "（导出前确保浏览器里已登录 Instagram）。",
        "login required": "Instagram 需要登录，请到「设置」导入 Cookie。",
        "429": "Instagram 限流了，等几分钟再试。",
        "timed out": "连不上 Instagram。境外站点需要代理，请检查网络。",
    },
    "TikTok": {
        "Unexpected response": "TikTok 拒绝了这次请求。常见原因："
                               "① 视频有地区限制，需要对应地区的网络节点；"
                               "② 需要登录态，可导入 Cookie 后重试。",
        "403": "TikTok 对部分地区限制访问，换一个网络节点试试。",
        "timed out": "连不上 TikTok。境外站点需要代理，请检查网络。",
    },
    "YouTube": {
        "Sign in to confirm": "YouTube 要求登录确认年龄。可以导入 Cookie，"
                              "但注意：实测给 YouTube 带 Cookie 会把画质从 1080p 降到 360p。",
        "Unable to extract": "YouTube 改版了，当前内置的解析引擎跟不上，需要等应用更新。",
        "timed out": "连不上 YouTube。境外站点需要代理，请检查网络。",
    },
    "B站": {
        "403": "B站拒绝了这次请求。可能是该视频需要大会员，或触发了风控，稍后再试。",
        "404": "视频不存在或已被删除。",
        "timed out": "连不上 B站，请检查网络。",
    },
    # 小红书。实测拿到的是两种完全不同的报错，成因也不同，所以分开写：
    #   ① 没导入 Cookie：分享短链会被 302 到 www.xiaohongshu.com/login?redirectPath=…
    #      那个地址没有对应提取器，yt-dlp 报 Unsupported URL；
    #   ② 已导入 Cookie 且笔记确实没有视频（type=normal 的图文 / 图集 / 实况图合集）：
    #      笔记数据能读到，但 video 段为空，报 No video formats found。
    #      ——同样的报错也可能是 Cookie 失效（未登录时它不下发笔记数据，
    #        实测 noteDetailMap 为空、loggedIn=false），所以文案里两句都要给。
    "小红书": {
        # 「不带 Cookie 时…」这种写法被实测证伪过一次：用户明明导入了 Cookie，
        # 提示却说他没导入（同一份会话被平台标记后，PC 与手机一样会被送到登录页）。
        # 后来又实测到第二种形态：**新登录的会话，第一条自动化请求就被送进验证码页**
        # （website-login/captcha）—— 所以这句不能把成因写死成「没导入」。
        "Unsupported URL": "小红书这次没过平台校验：请求被送到了登录页或验证码页。"
                           "① 还没导入 Cookie → 到「设置 → Cookie 文件」"
                           "导入一份已登录小红书的 cookies.txt；"
                           "② 已导入仍这样 → 平台把这次请求判成了自动化："
                           "先别连着试（试得越密越容易被拦），隔一段时间再来，"
                           "必要时重新登录小红书再重新导出 Cookie。",
        "No video formats found": "这条笔记没有视频（图文 / 图集 / 实况图类），"
                                  "本应用目前只解析视频。如果是视频笔记也报这句，"
                                  "那多半是 Cookie 没导入或已失效 —— 重新导入一次即可。",
        "403": "小红书拒绝了这次请求。常见原因是没带登录态（导入 Cookie 再试），"
               "或者开着全局代理 —— 它是国内站点，走境外节点反而会被拦。",
        "404": "这条笔记不存在或已被删除。",
        "timed out": "连不上小红书。它是国内站点，全局代理开着反而会被拦，"
                     "请改成「规则 / 智能分流」或直连模式。",
    },
    # 微博只写语义明确的两种（404 / 超时）。403 的具体成因还没实测过，
    # 不猜 —— 让它走通用兜底，那里已经把「需要登录 / 已删除 / 改版」都列了。
    "微博": {
        "404": "这条微博不存在或已被删除。",
        "timed out": "连不上微博，请检查网络。",
        # 实测：图文 / 图集 / 实况图 / 动图本来就没有视频轨，yt-dlp 只会报这一句。
        # 现在这类内容会自动转去浏览器模式；万一浏览器模式也没取到，至少别让用户
        # 以为是「站点改版」。
        "no video formats found":
            "这条微博里没有视频格式 —— 图文 / 图集 / 实况图本来就没有视频轨。"
            "如果这是视频帖，才可能是站点改版了。",
    },
}


def friendly_error(raw: str, platform: str = "") -> str:
    """把英文报错翻译成中文说明。

    匹配顺序（越具体越优先）：
      1. 平台 + 错误特征 —— 同一个 403 在不同平台成因完全不同，这个最有用；
      2. 通用错误特征；
      3. 都不匹配就给中文兜底，列出三种常见情况，至少让用户知道往哪试。
    """
    text = (raw or "").strip()
    if not text:
        return "解析失败，但没有拿到具体原因。可以打开「引擎自检页」看看细节。"

    if platform:
        for needle, hint in (PLATFORM_ERROR_HINTS.get(platform) or {}).items():
            if needle.lower() in text.lower():
                return f"{hint}\n\n原始报错：{_brief(text)}"

    for needle, hint in ERROR_HINTS:
        if needle.lower() in text.lower():
            return f"{hint}\n\n原始报错：{_brief(text)}"

    return (
        "解析失败。常见原因有以下几种：\n"
        "① 该视频需要登录（可到「设置」导入 Cookie）；\n"
        "② 视频有地区限制、已被删除或设为私享；\n"
        "③ 站点改版，当前内置的解析引擎还不支持。\n\n"
        f"原始报错：{_brief(text)}"
    )


# ==================== 文件名长度 ====================


def _shorten_name(path: str) -> str:
    """把文件名按字节截断到安卓能接受的长度。

    yt-dlp 的 trim_file_name 是按「字符数」截断的，在安卓上不管用 ——
    这里限制的是 255 字节。一个 90 字的中文标题就是 270 字节，照样顶爆，
    下载会以 ENAMETOOLONG 失败。所以自己按字节截，并且不切碎多字节字符。
    """
    if not path:
        return path
    parent, name = os.path.split(path)
    stem, dot, ext = name.rpartition(".")
    if not dot:  # 没有扩展名，整体当 stem
        stem, ext = name, ""
    data = stem.encode("utf-8")
    if len(data) <= MAX_NAME_BYTES:
        return path
    stem = data[:MAX_NAME_BYTES].decode("utf-8", "ignore")
    return os.path.join(parent, f"{stem}.{ext}" if ext else stem)


class _YDL(yt_dlp.YoutubeDL):
    """只在文件名上加一道安卓特有的限制，其余行为与原生完全一致。"""

    def _prepare_filename(self, *args, **kwargs):
        return _shorten_name(super()._prepare_filename(*args, **kwargs))


class _Logger:
    """接住 yt-dlp 的报错文本。

    命令行版能读子进程的 stderr；进程内没有 stderr 可读，所以挂一个 logger
    把最后一条 ERROR 留下来 —— 界面上的「原始报错」就来自这里。
    """

    def __init__(self) -> None:
        self.last_error = ""
        self.warnings: list[str] = []

    def debug(self, msg: str) -> None:
        # yt-dlp 把普通信息也走 debug，这里只挑 ERROR 开头的
        if isinstance(msg, str) and msg.startswith("ERROR:"):
            self.last_error = msg

    def info(self, msg: str) -> None:
        pass

    def warning(self, msg: str) -> None:
        if isinstance(msg, str) and msg.startswith("ERROR:"):
            self.last_error = msg
        else:
            self.warnings.append(str(msg))

    def error(self, msg: str) -> None:
        self.last_error = str(msg)


# ==================== 本地历史存储 ====================


class Store:
    """历史记录用 sqlite 存，零额外依赖。表结构与 Windows 端一致。"""

    def __init__(self, db_file: Path) -> None:
        db_file.parent.mkdir(parents=True, exist_ok=True)
        self.conn = sqlite3.connect(str(db_file), check_same_thread=False)
        self.conn.row_factory = sqlite3.Row
        # check_same_thread=False 只是关掉了检查，不提供线程安全：
        # 下载线程和界面线程可能同时写库（database is locked）。
        # 写操作统一走这把锁；读操作不加锁（读并发是安全的）。
        self._write_lock = threading.Lock()
        self.conn.execute(
            """
            CREATE TABLE IF NOT EXISTS history (
                id           TEXT PRIMARY KEY,
                platform     TEXT NOT NULL,
                title        TEXT,
                uploader     TEXT,
                duration     INTEGER,
                thumbnail    TEXT,
                source_url   TEXT NOT NULL,
                resolved_url TEXT,
                resolved_at  INTEGER,
                play_kind    TEXT,
                uploader_id  TEXT,
                download_url TEXT,
                local_path   TEXT
            )
            """
        )
        self._migrate()
        self.conn.commit()

    def _migrate(self) -> None:
        cols = {row["name"] for row in self.conn.execute("PRAGMA table_info(history)")}
        # 下载产物的落地路径。有它历史页才能直接播本地文件 —— 这一点比
        # 「在线播放直链」更有价值：B站/YouTube 早已全面 DASH 化（实测
        # YouTube 53 个格式里 0 个音视频合一，B站 也全是 audio only +
        # video only），拿不到能直连播放的地址，于是「下载后在本机看」
        # 才是这两类站点唯一的播放途径。
        if "local_path" not in cols:
            self.conn.execute("ALTER TABLE history ADD COLUMN local_path TEXT")
        # 内容类型与媒体列表。视频是单一文件，而图文/图集/实况图是**多条**记录，
        # 塞不进 resolved_url 那一个字段，所以单独存一份 JSON。
        #   content_type: video / images / live
        #   media_json  : [{"url":..., "width":..., "height":..., "live":...}, ...]
        if "content_type" not in cols:
            self.conn.execute("ALTER TABLE history ADD COLUMN content_type TEXT")
        if "media_json" not in cols:
            self.conn.execute("ALTER TABLE history ADD COLUMN media_json TEXT")
        # 两个 MD5：
        #   source_md5 —— 平台在解析结果里自报的（抖音每个档位都带 file_hash），
        #                 所以「下载前」就能显示，不必等文件下来
        #   file_md5   —— 成品文件实际算出来的。它只在下载收尾时回填，**不进 upsert**，
        #                 这样重新解析同一条不会把已经算好的值冲掉
        if "source_md5" not in cols:
            self.conn.execute("ALTER TABLE history ADD COLUMN source_md5 TEXT")
        if "file_md5" not in cols:
            self.conn.execute("ALTER TABLE history ADD COLUMN file_md5 TEXT")

    def upsert(self, rec: dict) -> None:
        # 图文类没有 resolved_url / download_url / play_kind，这里补默认值，
        # 免得每条调用点都要自己填一遍。
        row = {
            "resolved_url": "", "download_url": "", "play_kind": "none",
            "content_type": "video", "media_json": "", "source_md5": "",
            **rec,
        }
        with self._write_lock:
            self.conn.execute(
                """
                INSERT INTO history (id, platform, title, uploader, uploader_id, duration,
                                     thumbnail, source_url, resolved_url, download_url,
                                     resolved_at, play_kind, content_type, media_json,
                                     source_md5)
                VALUES (:id, :platform, :title, :uploader, :uploader_id, :duration,
                        :thumbnail, :source_url, :resolved_url, :download_url,
                        :resolved_at, :play_kind, :content_type, :media_json,
                        :source_md5)
                ON CONFLICT(id) DO UPDATE SET
                    platform     = excluded.platform,
                    title        = excluded.title,
                    uploader     = excluded.uploader,
                    uploader_id  = excluded.uploader_id,
                    duration     = excluded.duration,
                    thumbnail    = excluded.thumbnail,
                    source_url   = excluded.source_url,
                    resolved_url = excluded.resolved_url,
                    download_url = excluded.download_url,
                    resolved_at  = excluded.resolved_at,
                    play_kind    = excluded.play_kind,
                    content_type = excluded.content_type,
                    media_json   = excluded.media_json,
                    source_md5   = excluded.source_md5
                """,
                row,
            )
            self.conn.commit()

    def all(self) -> list[dict]:
        rows = self.conn.execute(
            "SELECT * FROM history ORDER BY resolved_at DESC"
        ).fetchall()
        return [dict(r) for r in rows]

    def delete(self, vid: str) -> None:
        with self._write_lock:
            self.conn.execute("DELETE FROM history WHERE id = ?", (vid,))
            self.conn.commit()

    def clear_platform(self, platform: str) -> None:
        with self._write_lock:
            self.conn.execute("DELETE FROM history WHERE platform = ?", (platform,))
            self.conn.commit()

    def clear_all(self) -> None:
        with self._write_lock:
            self.conn.execute("DELETE FROM history")
            self.conn.commit()

    def set_local_path(self, source_url: str, path: str) -> None:
        """记下某条历史的下载产物路径，供界面直接播放本地文件。

        按 source_url 匹配而不是按下载用的地址：走备用链路时（抖音直链、
        X 直链）真正下载的是直链，而库里存的是用户最初贴的那个分享链接。
        """
        with self._write_lock:
            self.conn.execute(
                "UPDATE history SET local_path = ? WHERE source_url = ?",
                (path, source_url),
            )
            self.conn.commit()

    def set_file_md5(self, path: str, md5: str) -> None:
        """按产物路径回填成品 MD5（历史页与 MD5 页都读它）。"""
        if not path or not md5:
            return
        with self._write_lock:
            self.conn.execute(
                "UPDATE history SET file_md5 = ? WHERE local_path = ?", (md5, path)
            )
            self.conn.commit()


# ==================== 下载状态（供界面轮询） ====================


class DownloadState:
    """下载状态快照。

    进程内调用不能用子进程那套「读 stdout 抓进度」，改成 yt-dlp 的
    progress_hooks 直接更新这个快照，界面按固定间隔来读。
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._seq = 0
        self._data: dict = {"type": "idle", "active": False, "seq": 0}

    def update(self, **kw) -> None:
        with self._lock:
            self._seq += 1
            self._data = {**self._data, **kw, "seq": self._seq}

    def snapshot(self) -> dict:
        with self._lock:
            return dict(self._data)


def _with_dup_suffix(template: str, n: int) -> str:
    """给 yt-dlp 的输出模板插一个重名编号：`…%(ext)s` -> `… (1).%(ext)s`。

    插在扩展名之前，而不是整个名字之后 —— 否则 `xxx.mp4 (1)` 这种尾巴
    在文件管理器里认不出是视频。
    """
    marker = ".%(ext)s"
    if marker in template:
        return template.replace(marker, f" ({n}){marker}")
    return f"{template} ({n})"


def _ext_from_url(url: str, default: str) -> str:
    """从媒体地址里认出扩展名。

    图文/图集/实况图本来都是图片（jpg / webp），但 X 的「媒体列表」里可能
    混着视频，写死 jpg 会把一段 mp4 存成 .jpg —— 相册和播放器都认不出来。
    """
    m = re.search(r"\.(jpg|jpeg|png|webp|gif|mp4|mov|webm)(?:\?|$)", (url or "").lower())
    return f".{m.group(1)}" if m else default


def _writable(path: str) -> bool:
    """真去写一个探针文件来判断能不能写。

    安卓的分区存储（scoped storage）下 os.access 会给出错误答案，
    只有真写一次才作数。
    """
    if not path:
        return False
    try:
        d = Path(path)
        d.mkdir(parents=True, exist_ok=True)
        probe = d / ".write_probe"
        probe.write_text("", encoding="utf-8")
        probe.unlink()
        return True
    except OSError:
        return False


# ==================== 引擎 ====================


def _xhs_stream_entries(video_json: str) -> list:
    """把 `video.media.stream` 摊平成候选列表（保留原始字段）。"""
    try:
        video = json.loads(video_json or "{}")
    except Exception:  # noqa: BLE001
        return []
    stream = ((video.get("media") or {}).get("stream")) or {}
    if not isinstance(stream, dict):
        return []
    flat = []
    for rows in stream.values():
        if isinstance(rows, list):
            flat.extend(i for i in rows if isinstance(i, dict) and i.get("masterUrl"))
    return flat


def _xhs_is_muxed(entry: dict) -> bool:
    """这条候选是不是「画面+声音」合流的。

    实测判据（真机抓到的字段）：合流条 `format:"mp4"` 且 audioCodec="aac"、
    audioChannels=2、audioBitrate=64010；纯视频条（DASH 分片）`format:"fmp4"`、
    audioChannels=0、audioBitrate=0。
    """
    if int(entry.get("audioChannels") or 0) > 0:
        return True
    if int(entry.get("audioBitrate") or 0) > 0:
        return True
    codec = str(entry.get("audioCodec") or "").lower()
    return codec not in ("", "none")


def _xhs_pick_video(video_json: str) -> tuple:
    """从小红书笔记的 `video` 段里挑一条直链，返回 (播放地址, 下载地址, 是否带声音)。

    结构（真机实测）：
        video.media.stream = {"EF4": [ {masterUrl, width, height, size, format,
                                       audioCodec, audioChannels, audioBitrate,
                                       videoCodec, qualityType, streamDesc, …} ], …}
    注意键名是**平台自己的编码编号**（EF4 / EF5 / …），不是 h264 / h265，
    所以按编码名挑是挑不中的。

    挑法（按实测结论，不是推测）：
      1. **优先「带音轨」的那条**（[_xhs_is_muxed]）。同一支视频里既有合流的
         mp4，也有纯画面的 fmp4 分片；只看「高度最高」会挑中纯画面的那条 ——
         用户下载下来是个**没声音**的文件（实测 ffprobe 只有一条 hevc 视频流）。
      2. 合流条里取高度最大的。
      3. 一条合流的都没有时，退回「高度最大」的纯画面条 —— 有画面总比没有好，
         但会把「没声音」如实回报给界面（第三个返回值）。
    """
    entries = _xhs_stream_entries(video_json)
    if not entries:
        return "", "", False

    def by_height(rows: list) -> dict:
        return sorted(rows, key=lambda i: (int(i.get("height") or 0),
                                           int(i.get("size") or 0)), reverse=True)[0]

    muxed = [e for e in entries if _xhs_is_muxed(e)]
    if muxed:
        url = by_height(muxed).get("masterUrl") or ""
        return url, url, True
    url = by_height(entries).get("masterUrl") or ""
    return url, url, False


class Engine:
    def __init__(self, data_dir: Path, ffmpeg_path: str, qjs_path: str,
                 preferred_dir: str, fallback_dir: str) -> None:
        self.defaults = json.loads(CONFIG_FILE.read_text(encoding="utf-8"))
        self.data_dir = data_dir
        self.data_dir.mkdir(parents=True, exist_ok=True)
        self.store = Store(self.data_dir / "history.db")
        self.dl_state = DownloadState()
        self._dl_lock = threading.Lock()
        self._dl_busy = False
        # 直链下载的速率采样点（近几秒的 (时间, 已下字节)），见 _direct_report
        self._rate_lock = threading.Lock()
        self._rate_marks: list = []
        self._user = self._load_user()

        # 这三个路径只有安卓侧知道（应用原生库目录 + 系统下载目录），
        # 由 Kotlin 在启动时注入，不写死在配置里。
        self._ffmpeg = ffmpeg_path
        self._qjs = qjs_path
        self._preferred_dir = preferred_dir
        self._fallback_dir = fallback_dir
        self._output_dir = self._pick_output_dir()
        print(f"[engine] ffmpeg={ffmpeg_path}\n[engine] qjs={qjs_path}\n"
              f"[engine] output_dir={self._output_dir}")

    # ---- 设置 ----

    def _load_user(self) -> dict:
        path = self.data_dir / "settings.json"
        if path.exists():
            try:
                return json.loads(path.read_text(encoding="utf-8"))
            except (json.JSONDecodeError, OSError):
                return {}
        return {}

    def _save_user(self) -> None:
        (self.data_dir / "settings.json").write_text(
            json.dumps(self._user, ensure_ascii=False, indent=2), encoding="utf-8"
        )

    def _pick_output_dir(self) -> str:
        """按优先级挑一个真能写的输出目录。

        首选系统「下载」目录（用户用文件管理器/数据线都能找到），
        但它要求存储权限；没授权时退回应用私有目录，并如实告诉界面 ——
        总比下载直接失败强。
        """
        override = (self._user.get("output_dir") or "").strip()
        for cand in (override, self._preferred_dir, self._fallback_dir):
            if cand and _writable(cand):
                return cand
        return self._fallback_dir

    @property
    def cookie_file(self) -> str:
        return (self._user.get("cookies_file") or "").strip()

    def get_settings(self) -> dict:
        # 每次都重新探测一次输出目录，而不是读 __init__ 里缓存的那个值。
        #
        # 为什么：能不能写进系统「下载」目录完全取决于存储权限，而权限是用户随时
        # 可以授予或撤销的。缓存住会造出一个假象 —— 用户先开应用（无权限，落到
        # 私有目录），再去系统设置里授权，回到设置页却仍然显示私有目录，
        # 而界面上写着「授权后会自动改回去」。实测就是这个表现。
        #
        # 探测本身只是往候选目录写一个空文件再删掉，代价可以忽略；
        # 用户手动指定的目录依然优先（_pick_output_dir 先看 override）。
        self._output_dir = self._pick_output_dir()
        return {
            "output_dir": self._output_dir,
            "output_dir_is_fallback": self._output_dir != self._preferred_dir,
            "preferred_dir": self._preferred_dir,
            "cookies_file": self.cookie_file,
            "cookies_present": bool(self.cookie_file) and os.path.isfile(self.cookie_file),
            # 下载时是否自动改写 MD5。默认关（见 set_rewrite_md5 的说明）
            "rewrite_md5_on_download": self.rewrite_md5_on_download,
        }

    def set_output_dir(self, path: str) -> dict:
        path = (path or "").strip()
        if path and not _writable(path):
            # 选了写不进去的目录就说清楚，别让用户以为设置成功了
            return {"ok": False, "error": f"这个位置写不进去：{path}"}
        self._user["output_dir"] = path
        self._save_user()
        self._output_dir = self._pick_output_dir()
        return {"ok": True, **self.get_settings()}

    def set_cookies_file(self, path: str) -> str:
        """设置 cookies.txt 路径（Netscape 格式）。传空字符串表示清除。"""
        self._user["cookies_file"] = (path or "").strip()
        self._save_user()
        return self._user["cookies_file"]

    # ---- 历史 ----

    def get_history(self) -> list[dict]:
        return self.store.all()

    def delete_history(self, vid: str) -> bool:
        self.store.delete(vid)
        return True

    def clear_history(self, platform: str) -> bool:
        self.store.clear_platform(platform)
        return True

    def clear_all_history(self) -> bool:
        self.store.clear_all()
        return True

    # ---- yt-dlp 参数 ----

    def platform_rule(self, url: str) -> dict:
        """按域名匹配平台规则（defaults.json 里的 platform_rules）。"""
        host = urlparse(url or "").netloc.lower()
        if not host:
            return {}
        for key, rule in (self.defaults.get("platform_rules") or {}).items():
            key = key.lower()
            if host == key or host.endswith("." + key):
                return rule or {}
        return {}

    def _base_opts(self, url: str) -> dict:
        eng = self.defaults.get("engine") or {}
        dl = self.defaults.get("download") or {}
        opts: dict = {
            # 进程内没有 stdout 要静默，但 quiet 仍然要开：否则 yt-dlp 的
            # 屏幕输出会走 logger，把有用的报错淹掉
            "quiet": True,
            "no_warnings": True,
            "noprogress": True,
            "ignoreconfig": True,
            "socket_timeout": int(eng.get("socket_timeout") or 20),
            "noplaylist": bool(dl.get("single_video_no_playlist", True)),
            # 安卓上这个开关是有效的（只在 Windows 上无效）。开着它，
            # 中文标题会保留，但 ? * : " < > | \ 这些会被替换掉 ——
            # 不换的话，文件用数据线拷到电脑或 FAT32 卡上会出问题。
            "windowsfilenames": True,
        }
        if self._qjs:
            # 不指定这个，YouTube 拿不到画质档：n-sig 挑战解不了，
            # 表现就是只有 360p。yt-dlp 只认 deno/node/bun/quickjs 四个键，
            # 而我们的二进制叫 libqjs.so，所以路径必须显式给。
            opts["js_runtimes"] = {"quickjs": {"path": self._qjs}}
        if self._ffmpeg:
            # 可以直接给文件路径，yt-dlp 能从一个叫 libffmpeg.so 的文件认出 ffmpeg
            opts["ffmpeg_location"] = self._ffmpeg

        # Cookie 按平台决定是否带：X/Instagram 不带会失败，
        # 而 YouTube 带了会把画质从 1080p 降到 360p，所以不能一律带上。
        if self.platform_rule(url).get("use_cookies"):
            path = self.cookie_file
            if path and os.path.isfile(path):
                opts["cookiefile"] = path
        return opts

    def _cookie_hint(self, url: str, err: str = "") -> str:
        """平台需要 Cookie 却拿不到时，把原因明确告诉用户。

        静默不加 Cookie 会让用户往网络问题上找，白绕一圈 —— Cookie 过期
        尤其容易被误判成「这条推文没有视频」。
        """
        if not self.platform_rule(url).get("use_cookies"):
            return ""
        path = self.cookie_file
        if not path:
            return "\n提示：该平台需要 Cookie 才能解析，但你还没导入 Cookie 文件（可在「设置」里选择）。"
        if not os.path.isfile(path):
            return f"\n提示：已导入的 Cookie 文件找不到了（{path}），请重新导入。"
        low = (err or "").lower()
        # 平台把请求送进登录页 / 验证码页时，成因是「这次请求被判成了自动化」，
        # 而不是「Cookie 过期」——此时再附一句「Cookie 可能已过期」会把人带偏
        # （实测：用户刚重新登录、导入的是全新 Cookie，界面照样收到这句）。
        # 这里让位给平台专属提示，不再叠加。
        if "website-login/captcha" in low or "/login?redirectpath" in low:
            return ""
        # 文件在、仍然失败，多半就是里面的登录态过期了
        if any(k in low for k in ("no video could be found", "not authorized",
                                 "401", "403", "login", "fresh cookies")):
            return ("\n提示：Cookie 可能已过期。请重新导出并导入 —— "
                    "导出前要确保浏览器里处于登录状态。")
        return ""

    # ---- 解析 ----

    def parse_url(self, url: str) -> dict:
        """解析链接，返回元数据并写入历史（不下载文件）。"""
        url = (url or "").strip()
        if not url:
            return {"ok": False, "error": "链接为空"}

        # 压根不像链接的输入在这儿就挡掉，给一句人话（原因见 looks_like_url）
        if not looks_like_url(url):
            return {
                "ok": False,
                "error": "这看起来不是一条链接。\n"
                         "请粘贴完整的分享链接（要以 http:// 或 https:// 开头）—— "
                         "就是作品页点「复制链接」得到的那一段。",
            }

        platform = detect_platform(url)

        # 抖音直接走自己的链路，不再先跑一遍 yt-dlp：抖音的接口要 a_bogus 签名
        # 加登录态，HTTP 客户端两样都拿不到，yt-dlp 必然 403 —— 先跑纯粹是白等。
        if platform == "抖音":
            return self._parse_douyin(url)

        # 小红书同理，直接交给**应用内置浏览器**，不再先跑 yt-dlp。
        # 曾经它是「先 yt-dlp（带用户导出的 Cookie）、失败才浏览器」，现在去掉前半段，
        # 两个理由：
        #   ① 继续拿那份导出 Cookie 去打小红书，会让**用户的账号**被平台判成自动化
        #      （实测：验证码 → 送登录页 → 连 web 端和手机 App 一起被登出）；
        #   ② 浏览器链路本来就全覆盖（视频 / 图文 / 图集 / 实况图），先跑一遍
        #      yt-dlp 只是白等几秒。
        if platform == "小红书":
            return {
                "ok": False,
                "need_webview": True,
                "webview_kind": "xhs",
                "platform": "小红书",
                "source_url": url,
                "error": "小红书走内置浏览器解析（首次需要在小红书窗口里登录一次，"
                         "之后不用再登录）。",
            }

        # 微博头条文章是另一种页面（`weibo.com/ttarticle/...`，文章 id 还在 `#`
        # 后面）—— yt-dlp 没有任何提取器认它，直接交给浏览器模式。
        if platform == "微博" and "ttarticle" in url:
            return {
                "ok": False,
                "need_webview": True,
                "webview_kind": "weibo",
                "platform": "微博",
                "source_url": url,
                "error": "正在用浏览器模式读取这条头条文章。",
            }

        # X 把顺序反过来：**先**走第三方链路，yt-dlp 退居兜底。
        # 原因：FxTwitter 一次能把整条推文里的所有媒体都拿到（多张图、多个视频、
        # 图文混排），给的还是能直接播放/下载的直链；而 yt-dlp 只能拿到其中一个
        # 视频，还经常是音视频分轨（界面上根本播不了）。
        # 早先是「yt-dlp 失败才走第三方」，于是多视频/图文混排永远轮不到第三方，
        # 图片类更是直接被判成「这条推文里没有视频」。
        x_err = ""
        if platform == "X":
            result = self._parse_x(url)
            if result.get("ok"):
                return result
            # fatal = 这条推文本身没有媒体（纯文字/投票/文章）：换 yt-dlp 也不会
            # 有结果，直接如实告诉用户，免得白等一轮还看到一堆互相矛盾的提示
            if result.get("fatal"):
                return result
            x_err = result.get("error") or ""

        opts = self._base_opts(url)
        opts["format"] = "bv*+ba/b"
        logger = _Logger()
        opts["logger"] = logger

        try:
            with _YDL(opts) as ydl:
                info = ydl.extract_info(url, download=False)
        except Exception as exc:  # noqa: BLE001
            raw = logger.last_error or f"{type(exc).__name__}: {exc}"
            # X 也留一条兜底链路（现在它是兜底：第三方优先，见上面）
            if platform == "X":
                # 走到这儿说明第三方和 yt-dlp 都失败了，两段原因都要给出来
                tip = friendly_error(raw, platform) + self._cookie_hint(url, raw)
                return {"ok": False, "error": (x_err + "\n" if x_err else "") + tip}
            if platform == "小红书":
                # 正常走不到这里了（小红书在上面就短路成浏览器模式）。
                # 留着是因为 yt-dlp 那条路的**报错翻译**（PLATFORM_ERROR_HINTS）与
                # 「登录页 / 验证码页」的判据在排查时仍有参考价值。
                return {
                    "ok": False,
                    "need_webview": True,
                    "webview_kind": "xhs",
                    "platform": "小红书",
                    "source_url": url,
                    "error": "小红书已自动改用内置浏览器模式解析"
                             "（首次需要在小红书窗口里登录一次，之后不用再登录）。",
                }
            if platform == "微博" and "no video formats" in raw.lower():
                # 微博的图文 / 图集 / 实况图 / 动图本来就没有视频流，yt-dlp 只会报
                # `No video formats found!`（用户实测 12 条里 9 条都是这个）。
                # 这类内容交给浏览器模式去读页面数据 —— 不是「站点改版」，是
                # 这条 yt-dlp 链路本来就只覆盖视频。
                return {
                    "ok": False,
                    "need_webview": True,
                    "webview_kind": "weibo",
                    "platform": "微博",
                    "source_url": url,
                    "error": "这条微博是图文 / 图集类内容，正在改用浏览器模式读取。",
                }
            return {
                "ok": False,
                "error": friendly_error(raw, platform) + self._cookie_hint(url, raw),
            }

        if not info:
            return {"ok": False, "error": "解析失败：没有拿到视频信息"}

        rec = self._to_record(info, url)
        if not rec["id"]:
            return {"ok": False, "error": "未获取到视频 ID，解析结果不完整"}
        # 档位全集（分辨率档 + 交给 yt-dlp 的选流表达式），供界面做下载规格选择。
        # 界面不选（spec 为空串）时下面的下载链路行为与以前**完全一致**。
        rec["tiers"] = ytdlp_collect_tiers(info)

        self.store.upsert(rec)
        # 补上历史库里已记录的本地产物路径：先下过再解析同一条时，
        # 界面要据此直接播本地文件，而不是再提示「需下载才能播放」
        rec = self._with_local_path(rec)
        # 规格行与档位选择器必须给同一个数字。_describe_quality/_describe_size 读的是
        # yt-dlp 自己挑的那条流（format=bv*+ba 的 requested_formats），而界面默认档是
        # tiers[0] —— 两者未必是同一条（实测同一个 1080p 能差出两倍多），
        # 两个数字打架会让人以为哪里算错了。所以有档位时统一以默认档为准，
        # 档位文案里已经带了体积，filesize 不必再重复一次。
        default_tier = rec["tiers"][0] if rec["tiers"] else None
        return {
            "ok": True,
            **rec,
            "quality": default_tier["label"] if default_tier
            else self._describe_quality(info),
            "filesize": "" if default_tier else self._describe_size(info),
        }

    def _parse_douyin(self, url: str, ytdlp_err: str = "") -> dict:
        """抖音：优先直接调公开接口；接口不可用才降级到 WebView。

        为什么改掉原来的纯 WebView 方案：那条路要等页面渲染、再拦截它发出的
        接口请求，而且结果受 UA 影响 —— 实测移动版 UA 会跳到分享页，
        数据由服务端直出，全程不触发接口，拦截器只能干等到超时，必须用
        桌面版 UA 才成。又慢又脆，用户那次失败就是这么来的。

        对比参考实现后找到了根因：抖音有一个不用签名、不用登录的公开接口，
        只需把 UA 伪装成抖音 App 自身：
            https://aweme.snssdk.com/aweme/v1/feed/?aweme_id=<作品ID>
        实测直连 HTTP 200、秒回，返回的 aweme_list 里就有标题/作者/直链/码率档位。
        所以把它作为主方案，WebView 退居兜底（接口哪天被关掉还能顶上）。
        """
        try:
            return self._parse_douyin_via_api(url)
        except Exception as exc:  # noqa: BLE001
            api_err = f"{type(exc).__name__}: {exc}"
            print(f"[douyin] 接口方案失败，降级到浏览器模式：{api_err}")

        # ytdlp_err 为空表示没跑过 yt-dlp（抖音现在直接走这条链路），此时不提它
        detail = f"接口方式失败原因：{api_err}"
        if ytdlp_err:
            detail += f"\n（yt-dlp 的错误：{friendly_error(ytdlp_err, '抖音')}）"
        return {
            "ok": False,
            "need_webview": True,
            "platform": "抖音",
            "source_url": url,
            "error": "抖音解析失败，已自动改用浏览器模式重试。\n" + detail,
        }

    def _parse_douyin_via_api(self, url: str) -> dict:
        """直接调抖音公开接口取作品数据。

        全程纯 HTTP，不需要 WebView、不需要登录、不需要签名。
        失败时抛异常，由调用方决定是否降级到浏览器模式。
        """
        vid = douyin_video_id(url)
        if not vid:
            raise RuntimeError("没能从链接里识别出作品编号")

        req = urllib.request.Request(
            DOUYIN_FEED_API.format(id=vid),
            headers={"User-Agent": DOUYIN_APP_UA},
        )
        with urllib.request.urlopen(req, timeout=20) as resp:
            body = json.loads(resp.read().decode("utf-8", "replace"))

        items = body.get("aweme_list") or []
        if not items:
            raise RuntimeError("接口没有返回作品数据（可能作品已删除或设为私享）")

        # 这个接口是「推荐流」性质的：只有**视频**会被原样带回，图文/图集/实况图
        # 这类「笔记」它不服务 —— 此时 items 里全是推荐作品，目标 id 不在其中。
        #
        # 绝不能退回 items[0]：实测 7 条不同的图集/实况图链接会拿到**同一条无关视频**，
        # 而界面显示「解析成功」，用户会把别人的视频当成自己的内容下走（伪成功）。
        # 取不到就明确失败，交给上层降级到 WebView（那里能从页面主文档里拿到笔记数据）。
        target = next(
            (x for x in items if str(x.get("aweme_id") or "") == vid),
            None,
        )
        if target is None:
            raise RuntimeError(
                f"接口返回的 {len(items)} 条里没有这条作品（疑似图文/图集类内容），转浏览器模式"
            )

        video = target.get("video") or {}
        play_url, download_url = douyin_pick_tiers(video)
        if not play_url:
            raise RuntimeError("接口返回里没有可用的视频地址")

        # 档位全集交给界面做「下载规格选择」；第一项即默认选中项
        tiers = douyin_collect_tiers(video)
        # 「当前播放的是多少分辨率」——按播放档那条去档位表里取文案。
        # 播放档是 H.264 里最好的那条，未必等于档位表第一项（第一项可能是
        # 分辨率更高的 H.265），所以这里按 url 反查，不能直接用 tiers[0]。
        quality = next(
            (t["label"] for t in tiers if t["url"] == play_url),
            tiers[0]["label"] if tiers else "原始画质（无水印）",
        )

        author = target.get("author") or {}
        cover = ((video.get("cover") or {}).get("url_list") or [""])[0]
        duration_ms = video.get("duration") or 0

        rec = {
            "id": str(target.get("aweme_id") or vid),
            "platform": "抖音",
            "title": target.get("desc") or "",
            "uploader": author.get("nickname") or "",
            "uploader_id": author.get("unique_id") or author.get("short_id") or "",
            "duration": int(duration_ms // 1000) if duration_ms else None,
            "thumbnail": cover or "",
            "source_url": url,
            # 能直接放进播放器的那条（H.264 + 完整 mp4）
            "resolved_url": play_url,
            # 默认下载档（档位表第一项）
            "download_url": download_url,
            "resolved_at": int(time.time()),
            # 抖音直链实测支持 Range，可直接内联播放
            "play_kind": "progressive",
            # 可选档位全集。放在 rec 上是为了让它自动出现在解析结果里
            # （返回处是 `**self._with_local_path(rec)`），界面据此渲染选择器。
            "tiers": tiers,
            # 平台自报的 MD5：取默认下载档（第一档）那条。有它，界面就能在
            # **下载之前**把 MD5 显示出来，不必等文件下来
            "source_md5": (tiers[0].get("hash") or "") if tiers else "",
        }
        self.store.upsert(rec)
        return {
            "ok": True,
            **self._with_local_path(rec),
            "quality": quality,
            "filesize": "",
        }

    def save_xhs(self, payload_json: str) -> dict:
        """小红书：接收 Kotlin 侧 WebView 取到的笔记数据并入库。"""
        return self._save_webview_note(payload_json, "小红书")

    def save_weibo(self, payload_json: str) -> dict:
        """微博：接收 Kotlin 侧 WebView 取到的正文数据并入库。

        微博**不做档位选择**（移动版详情页只给一份图/视频），tiers 为空。
        """
        return self._save_webview_note(payload_json, "微博")

    def _save_webview_note(self, payload_json: str, platform: str) -> dict:
        """浏览器取数的统一入库口（小红书 / 微博共用）。

        返回结构与 parse_url 成功时一致，界面无需区别对待。

        两条链路的差别只有「视频直链从哪来」：
          · 小红书：`video_raw` 里是一堆候选流，要按「优先带音轨」挑（见 _xhs_pick_video）；
          · 微博：`video_url` 就是页面给的那一条。
        """
        data = json.loads(payload_json)
        url = (data.get("source_url") or "").strip()
        media = data.get("media") or []
        ctype = data.get("content_type") or "video"
        count = len(media)

        # 「合集类」笔记（仅小红书）：平台把它自己做成了一个视频（type=video），
        # 但笔记里还带着一整组图。用户要的是那组图，不是平台合成的视频，所以
        # **优先按图集给**。判据（实测）：真正的视频笔记 imageList 恰好只有 1 张
        # （就是封面），所以「带 ≥2 张图」就能把合集和普通视频分开。
        if platform == "小红书" and ctype == "video" and count >= 2:
            ctype = "live" if any((m or {}).get("live") for m in media) else "images"

        play_url = download_url = ""
        has_audio = True
        if ctype == "video":
            if platform == "小红书":
                play_url, download_url, has_audio = _xhs_pick_video(
                    data.get("video_raw") or ""
                )
            else:
                play_url = download_url = data.get("video_url") or ""

        rec = {
            "id": str(data.get("id") or url),
            "platform": platform,
            "title": data.get("title") or "",
            "uploader": data.get("uploader") or "",
            "uploader_id": data.get("uploader_id") or "",
            "duration": data.get("duration"),
            # 图文类的封面就是第一张图
            "thumbnail": data.get("thumbnail") or "",
            "source_url": url,
            "resolved_url": play_url,
            "download_url": download_url,
            "resolved_at": int(time.time()),
            "play_kind": "progressive" if play_url else "none",
            "content_type": ctype,
            # 图文/图集/实况图是**多条**媒体，塞不进 resolved_url，单独存一份 JSON
            "media_json": json.dumps(media, ensure_ascii=False) if media else "",
            "tiers": [],
            "source_md5": "",
        }
        self.store.upsert(rec)
        if ctype == "images":
            quality = f"图文 · 共 {count} 张"
        elif ctype == "live":
            quality = f"实况图 · 共 {count} 张"
        else:
            # 如实说清「有没有声音」：小红书这类内容里的纯画面流（DASH 分片）
            # 本身就没有音轨，用户看到「无声」才不会被当成播放器坏了。
            quality = ("原始画质（浏览器取数）" if has_audio
                       else "原始画质（浏览器取数 · 该条只有画面，无音轨）")
        return {
            "ok": True,
            **self._with_local_path(rec),
            "quality": quality,
            "filesize": "",
            "tiers": [],
        }

    def save_douyin(self, payload_json: str) -> dict:
        """接收 Kotlin 侧 WebView 取到的抖音数据，转成统一记录并入库。

        返回结构与 parse_url 成功时一致，界面无需区别对待这两条路径。
        """
        data = json.loads(payload_json)
        url = (data.get("source_url") or "").strip()
        media = data.get("media") or []
        ctype = data.get("content_type") or "video"
        count = len(media)
        # 视频才有档位；图文/图集/实况图每张只有一个地址，没有「选规格」的概念
        tiers = data.get("tiers") or []
        play_url = data.get("play_url") or ""
        download_url = data.get("download_url") or ""

        # 视频类作品走详情接口时，WebView 侧只把 video 对象原样带过来（video_raw），
        # 档位与默认档的挑选在这儿做 —— 这套规则只保留一份实现
        # （douyin_pick_tiers / douyin_collect_tiers），免得两处各算各的。
        if data.get("video_raw"):
            video = json.loads(data["video_raw"])
            tiers = douyin_collect_tiers(video)
            play_url, download_url = douyin_pick_tiers(video)

        # 平台自报的 MD5：抖音每个档位都带 file_hash，取默认下载档（第一档）那条。
        # 图文 / 图集没有档位，自然是空 —— 那类内容本来也没有「单文件 MD5」。
        source_md5 = (tiers[0].get("hash") or "") if tiers else ""

        rec = {
            "id": str(data.get("id") or url),
            "platform": "抖音",
            "title": data.get("title") or "",
            "uploader": data.get("uploader") or "",
            "uploader_id": data.get("uploader_id") or "",
            "duration": data.get("duration"),
            # 图文类的封面就是第一张图
            "thumbnail": data.get("thumbnail") or "",
            "source_url": url,
            # 能直接放进播放器的那条（H.264 + 完整 mp4）；图文类没有，为空
            "resolved_url": play_url,
            # 默认下载档（档位表第一项）；图文类为空
            "download_url": download_url,
            "resolved_at": int(time.time()),
            # 抖音直链实测返回 206 + video/mp4，支持 Range，可直接内联播放
            "play_kind": "progressive" if play_url else "none",
            "content_type": ctype,
            # 图文/图集/实况图是**多条**媒体，塞不进 resolved_url，单独存一份 JSON
            "media_json": json.dumps(media, ensure_ascii=False) if media else "",
            "tiers": tiers,
            "source_md5": source_md5,
        }
        self.store.upsert(rec)
        if ctype == "images":
            quality = f"图文 · 共 {count} 张"
        elif ctype == "live":
            quality = f"实况图 · 共 {count} 张"
        else:
            # 显示「当前播放的是多少分辨率」：按播放档那条去档位表里取文案，
            # 而不是直接取第一项 —— 第一项可能是分辨率更高的 H.265，而播放走的
            # 是 H.264 那条，两者未必同一档。
            quality = next(
                (t["label"] for t in tiers if (t or {}).get("url") == play_url),
                tiers[0]["label"] if tiers else "原始画质（无水印）",
            )
        return {
            "ok": True,
            **self._with_local_path(rec),
            "quality": quality,
            "filesize": "",
            # 界面据此提示用户「用的是浏览器模式」，解释为什么要多等一会儿
            "via": "browser",
        }

    def _parse_x(self, url: str, ytdlp_err: str = "") -> dict:
        """X 的第三方链路：调 FxTwitter 拿直链。

        纯 HTTP，不需要浏览器，所以安卓上能和 Windows 端做到完全一致。

        注意这条链路现在是 X 的**首选**（不是兜底）：它一次能把整条推文里的
        所有媒体都拿到，而 yt-dlp 只能拿到其中一个视频。见 parse_url 里的说明。
        """
        try:
            from x_fallback import XFallbackError, XNoMediaError, resolve

            data = resolve(url)
        except XNoMediaError as exc:
            # 「这条推文本就没有媒体」是定论：换 yt-dlp、补 Cookie 都不会有用。
            # 标成 fatal，让 parse_url 别再往下试、也别提示去查 Cookie。
            return {"ok": False, "fatal": True, "error": f"X 解析失败：{exc}"}
        except XFallbackError as exc:
            # ytdlp_err 为空表示本来就是首选链路，没有"主方案的错误"可拼
            base = f"X 解析失败：{exc}"
            if ytdlp_err:
                base += (f"\n（yt-dlp 的错误：{friendly_error(ytdlp_err, 'X')}）"
                         # 走这儿说明两条链路都失败，补上 Cookie 提示 —— 否则那句翻译
                         # 会把「缺 Cookie」说成「这条推文里没有视频」，用户会查错方向
                         + self._cookie_hint(url, ytdlp_err))
            return {"ok": False, "error": base}
        except Exception as exc:  # noqa: BLE001
            return {"ok": False, "error": f"X 解析异常：{type(exc).__name__}: {exc}"}

        # 一条推文里可能有好几样（图集、多视频、图文混排），和抖音那边一样
        # 存成「媒体列表 + 内容类型」，下游的展示/下载都按这个列表走。
        media = data.get("media") or []
        ctype = data.get("content_type") or "video"
        rec = {
            "id": data.get("id") or url,
            "platform": "X",
            "title": data.get("title") or "",
            "uploader": data.get("uploader") or "",
            "uploader_id": data.get("uploader_id") or "",
            "duration": data.get("duration"),
            "thumbnail": data.get("thumbnail") or "",
            "source_url": url,
            "resolved_url": data.get("play_url") or "",
            "download_url": data.get("download_url") or "",
            "resolved_at": int(time.time()),
            "play_kind": "progressive" if (data.get("play_url") or "") else "none",
            "content_type": ctype,
            "media_json": json.dumps(media, ensure_ascii=False) if media else "",
        }
        self.store.upsert(rec)
        if ctype == "images":
            quality = f"图集 · 共 {len(media)} 项"
        elif ctype == "live":
            quality = f"实况图 · 共 {len(media)} 项"
        else:
            quality = "最高画质（第三方服务解析）"
        return {
            "ok": True,
            **rec,
            "quality": quality,
            "filesize": "",
            # 界面据此提示用的是备用通道，便于用户理解为什么偶尔会慢/失败
            "via": "service",
        }

    # ---- 下载 ----

    def _download_target(self, url: str, spec: dict | None = None) -> str:
        """决定真正要下载的地址。

        优先级：
          1. 界面选中的档位（spec 里的直链）—— 用户在结果区选了规格就用它；
          2. 备用链路已入库的默认档直链（抖音 / X）；
          3. 原链接。
        """
        # 选中的档位优先。这条路径是「选哪个下哪个」，不需要 yt-dlp 参与 ——
        # 直链本身就是完整 mp4。
        if spec and spec.get("url"):
            return spec["url"]

        # 小红书必须在这里：它的分享链接（xhslink.cn 短链）yt-dlp 已经解析不了了
        # （要么被风控、要么只有图文），但**直链已经由浏览器取数入库**。
        # 漏掉它的后果实测过：下载会拿原始短链去喂 yt-dlp，报 Unsupported URL，
        # 界面上的表现就是「下载失败：不支持这种链接」。
        if detect_platform(url) not in ("抖音", "X", "小红书"):
            return url
        for rec in self.store.all():
            if rec.get("source_url") == url:
                # 没选档位就用默认档（档位表第一项）；没有则退回可播放地址
                return rec.get("download_url") or rec.get("resolved_url") or url
        return url

    def start_download(self, url: str, index: int = 0, spec: str = "") -> dict:
        """开始下载。

        [index] 只对图文/图集有意义：0 = 全部，N = 只下第 N 张。
        [spec]  界面选中的下载档位，JSON 字符串，两种形态之一：
                  {"url": "…"}                          直链型（抖音等）
                  {"selector": "bv*[height<=720]+ba"}   yt-dlp 型（DASH 分离流平台）
                空字符串 = 用引擎默认档，行为与以前完全一致。
        """
        url = (url or "").strip()
        if not url:
            return {"ok": False, "error": "链接为空"}

        spec_dict: dict = {}
        if spec:
            try:
                spec_dict = json.loads(spec) or {}
            except json.JSONDecodeError:
                return {"ok": False, "error": "下载规格参数不是合法 JSON"}

        with self._dl_lock:
            if self._dl_busy:
                return {"ok": False, "error": "已有下载任务正在进行，请等它完成"}
            self._dl_busy = True

        # url 是给界面用的：下载状态是全局的（同一时刻只有一个任务），
        # 界面只有知道「这条状态属于哪个作品」，才不会把上一次的产物
        # 拿去播当前这条解析结果。update 是合并写入，所以只在这里给一次。
        self.dl_state.update(type="starting", active=True, percent=None,
                             path="", message="", url=url, index=index)
        threading.Thread(target=self._download_worker,
                         args=(url, index, spec_dict), daemon=True).start()
        return {"ok": True}

    def get_download_state(self) -> dict:
        return self.dl_state.snapshot()

    def _direct_stem(self, rec: dict | None) -> str:
        """直链下载用的文件名主干 —— 不含扩展名，也还没转义 `%`。

        走直链时 yt-dlp 用的是通用提取器，拿不到任何元数据，%(title)s /
        %(id)s 只能从 URL 里猜：抖音会变成一串 a=6383&br=6185 查询参数，
        X 的 video.twimg.com 会变成内部文件名。所以直接用库里存的标题和 id。
        """
        title = ((rec or {}).get("title") or "").strip()
        if not title:
            return ""
        vid = ((rec or {}).get("id") or "").strip()
        # 预算必须**先给「 [id].%(ext)s」留够**，剩下的才给标题。
        # 原先是 MAX_NAME_BYTES - 8，而「 [19 位 id].mp4」要 26 字节 ——
        # 留少了，长标题时 [id] 会被截掉，连右括号都不剩（实测文件名卡在
        # 204 字节，结尾是 `... @DOU+小助手 [7692598.mp4`）。
        # [id] 是保证文件名唯一的那部分，丢了会让两个标题相近的视频重名，
        # 后一个会被 yt-dlp 当成「已下载」直接跳过。
        suffix = f" [{vid}].%(ext)s" if vid else ".%(ext)s"
        budget = max(16, MAX_NAME_BYTES - len(suffix.encode("utf-8")))
        # 标题超长时用和图片同一套规则：先丢末尾 #话题，再按字节裁加省略号
        safe = self._clip_title(title, budget)
        if not safe:
            safe = vid or "douyin"
        return f"{safe} [{vid}]" if vid else safe

    def _direct_output_name_for(self, rec: dict | None) -> str:
        """按一条历史记录拼「直链下载」用的文件名模板（交给 yt-dlp 填扩展名）。"""
        stem = self._direct_stem(rec)
        if not stem:
            return ""
        # 标题里若含 %，会被 yt-dlp 当模板再解析一次，转义成字面量
        return stem.replace("%", "%%") + ".%(ext)s"

    def _direct_output_name(self, direct_url: str) -> str:
        """按 URL 反查历史记录，再拼文件名。

        只对「引擎自己选的那条直链」（默认档）有效 —— 用户在界面上选了别的档位时
        那个 URL 不在库里，反查不到；那种情况由 _download_worker 直接用记录来拼，
        否则文件名会退化成 URL 片段（实测就是 `...a=0&ch=5&...&q.mp4` 这样）。
        """
        for rec in self.store.all():
            if direct_url in (rec.get("download_url"), rec.get("resolved_url")):
                return self._direct_output_name_for(rec)
        return ""

    # ---- 直链分块并行下载 ----

    def _direct_output_path(self, target: str, rec: dict | None) -> str:
        """直链下载的落盘路径；拼不出文件名时返回空串。

        命名规则与 yt-dlp 那条路一致（库里的标题 + [id]），重名同样另存一份
        `(1)/(2)…`。这一步必须自己做：yt-dlp 碰到同名文件是直接跳过，而这条路由
        我们自己写盘，不处理就会把上一份**覆盖**掉。
        """
        if not rec:
            rec = next(
                (r for r in self.store.all()
                 if target in (r.get("download_url"), r.get("resolved_url"))),
                None,
            )
        stem = self._direct_stem(rec)
        if not stem:
            return ""
        for n in range(100):
            tag = f" ({n})" if n else ""
            cand = os.path.join(self._output_dir, f"{stem}{tag}.mp4")
            if not os.path.exists(cand):
                return cand
        return os.path.join(self._output_dir, f"{stem}.mp4")

    # 算瞬时速率用的滑动窗口长度（秒）。取 4 秒：再短会被一次卡顿带得跳，
    # 再长就又退回成累计平均值了。
    _RATE_WINDOW = 4.0

    def _direct_report(self, got: int, total: int, started: float) -> None:
        """把「已下多少」和**当前**速率写进下载状态。

        进度必须自己报：引擎里原来只有 yt-dlp 的 progress_hooks 在更新界面上的
        进度条，自己写的下载器不补这一步，界面会一直停在 0，看着像卡死了。

        速率用近几秒的**滑动窗口**算，而不是「已下字节 / 总耗时」那个累计平均值：
        累计平均会被中途一次卡顿永久拉低，之后只能慢慢往回爬 —— 用户看到的就是
        「一开始几 MB、突然掉到几十 KB、再慢慢涨回几 MB」，像速度在乱跳
        （实测被反馈上来的就是这个现象）。窗口速率反映的是「此刻多快」。
        """
        now = time.time()
        with self._rate_lock:
            marks = self._rate_marks
            marks.append((now, got))
            # 只留窗口内的采样点；至少留两个，否则算不出时间跨度
            while len(marks) > 2 and marks[0][0] < now - self._RATE_WINDOW:
                marks.pop(0)
            t0, g0 = marks[0]
        span = now - t0
        if span > 0.2:
            # 窗口够长就用窗口内的增量算「此刻多快」。这段时间没进展就是 0 ——
            # 界面把 0 显示成「不显示速度」（formatSpeed 对 0 返回空串），
            # 比报一个正在缓慢下滑 / 回升的累计平均值诚实。
            speed = max(0, int((got - g0) / span))
        else:
            # 刚开始，窗口还没跨度，退回累计平均，总比显示 0 强
            speed = int(got / max(0.001, now - started))
        self.dl_state.update(
            type="progress", active=True,
            percent=round(got / total * 100, 1) if total else None,
            downloaded=got, total=total, speed=speed,
            eta=int((total - got) / speed) if total and speed else None,
        )

    def _download_direct(self, target: str, path: str) -> None:
        """直链下载：HTTP Range 分块并行。

        为什么不让 yt-dlp 代劳：直链是「一个完整的 mp4」，yt-dlp 只会用通用
        提取器走**单连接**整文件拉，引擎里的 concurrent_fragment_downloads
        对整文件传输完全不起作用。实测抖音 CDN 支持 Range（Accept-Ranges: bytes、
        206 且带 Content-Range），分块并行能把带宽吃满。

        服务端忽略 Range（回 200）或给不出总长度时退回单连接 —— 这条回退必须有：
        那种情况下响应体是整个文件，仍按分块偏移去写会把文件写坏。
        """
        # 每次下载都从干净的速率采样开始：留着上一次的采样点会把这次的均速算歪
        with self._rate_lock:
            self._rate_marks.clear()
        headers = {"User-Agent": _BROWSER_UA}
        if "douyinvod.com" in target:
            # 抖音直链来自浏览器监听，其 CDN 会校验 Referer，不带会被拒
            headers["Referer"] = "https://www.douyin.com/"
        eng = self.defaults.get("engine") or {}
        timeout = int(eng.get("socket_timeout") or 20)
        workers = max(1, int(eng.get("direct_threads") or 6))

        try:
            total, ranged = self._probe_range(target, headers, timeout)
            if ranged and total > 0 and workers > 1:
                self._ranged_get(target, path, total, headers, timeout, workers)
            else:
                print(f"[engine] 直链不支持分块（总长度 {total}），改用单连接")
                self._stream_get(target, path, headers, timeout)
        except Exception:
            # 失败不留半截文件：那东西留在磁盘上，用户下次下载还会撞进重名逻辑
            try:
                os.remove(path)
            except OSError:
                pass
            raise

    @staticmethod
    def _probe_range(target: str, headers: dict, timeout: int) -> tuple:
        """问一次「总长度」和「支不支持 Range」。

        用带 Range 的 GET 而不是 HEAD：不少 CDN 对 HEAD 不给 Content-Range，
        而 `Range: bytes=0-0` 一定会回 `Content-Range: bytes 0-0/<总长>`。
        """
        req = urllib.request.Request(target, headers={**headers, "Range": "bytes=0-0"})
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                if resp.status != 206:
                    return 0, False
                m = re.match(
                    r"bytes\s+\d+-\d+/(\d+)", resp.headers.get("Content-Range") or ""
                )
                return (int(m.group(1)) if m else 0), True
        except Exception as exc:  # noqa: BLE001
            print(f"[engine] Range 探测失败，改用单连接：{exc}")
            return 0, False

    def _stream_get(self, target: str, path: str, headers: dict, timeout: int) -> None:
        """单连接整文件下载（不支持分块时的回退）。"""
        req = urllib.request.Request(target, headers=headers)
        started = time.time()
        got = 0
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            length = resp.headers.get("Content-Length") or ""
            # 服务端不给长度时 total 为 0，进度就只报「已下多少」（percent=None）
            total = int(length) if length.isdigit() else 0
            with open(path, "wb") as f:
                while True:
                    buf = resp.read(_DIRECT_READ)
                    if not buf:
                        break
                    f.write(buf)
                    got += len(buf)
                    self._direct_report(got, total, started)

    def _ranged_get(self, target: str, path: str, total: int,
                    headers: dict, timeout: int, workers: int) -> None:
        """把 total 个字节切成 workers 块，各线程按自己的偏移并行写。"""
        # 先把文件按总长度撑开：各线程往自己的偏移上写，不预分配的话，
        # 先写完的那个线程会把文件截断，后面写的偏移就全丢了。
        with open(path, "wb") as f:
            f.truncate(total)

        spans = []
        span_size = max(_DIRECT_READ, -(-total // workers))
        start = 0
        while start < total:
            end = min(start + span_size, total) - 1
            spans.append((start, end))
            start = end + 1

        lock = threading.Lock()
        abort = threading.Event()
        state = {"got": 0, "err": None}
        started = time.time()

        def worker(span: tuple) -> None:
            s, e = span
            if abort.is_set():
                return
            try:
                req = urllib.request.Request(
                    target, headers={**headers, "Range": f"bytes={s}-{e}"}
                )
                with urllib.request.urlopen(req, timeout=timeout) as resp:
                    if resp.status != 206:
                        # 探测说支持、真拉时又回 200：此时响应体是整个文件，
                        # 照偏移写会写坏，宁可整个失败
                        raise RuntimeError("服务端未按 Range 返回（期望 206）")
                    # 每个线程开自己的句柄：共用一个 file 对象时各线程的
                    # seek 与 write 会互相穿插，写出来的内容会错位
                    f = open(path, "r+b")
                    try:
                        f.seek(s)
                        pos = s
                        while pos <= e:
                            buf = resp.read(min(_DIRECT_READ, e - pos + 1))
                            if not buf:
                                break
                            f.write(buf)
                            pos += len(buf)
                            with lock:
                                state["got"] += len(buf)
                                got = state["got"]
                            self._direct_report(got, total, started)
                    finally:
                        f.close()
            except Exception as exc:  # noqa: BLE001
                with lock:
                    if state["err"] is None:
                        state["err"] = exc
                abort.set()

        threads = [threading.Thread(target=worker, args=(sp,), daemon=True)
                   for sp in spans]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        if state["err"] is not None:
            raise state["err"]
        # 文件是被 truncate 撑开的，所以大小恒等于 total —— 只有累计写入的字节数
        # 才能证明每一块都真写全了
        if state["got"] != total:
            raise RuntimeError(f"分块下载不完整：{state['got']}/{total}")

    def _on_progress(self, d: dict) -> None:
        status = d.get("status")
        if status == "downloading":
            total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
            got = d.get("downloaded_bytes") or 0
            percent = round(got / total * 100, 1) if total else None
            self.dl_state.update(
                type="progress", active=True, percent=percent,
                downloaded=got, total=total,
                speed=d.get("speed") or 0, eta=d.get("eta"),
            )
        elif status == "finished":
            # 单个文件下完了，但可能还要合流/转封装，所以不是 done
            self.dl_state.update(type="processing", active=True,
                                 percent=100.0, message="正在合并音视频…")

    def _download_media_list(self, url: str, rec: dict, only_index: int = 0) -> None:
        """图文 / 图集 / 实况图：逐张存进「作者名」的子目录。

        与视频不同，这类内容没有可合流的音视频，就是一个地址列表 —— 直接用
        urllib 取回即可，没必要也不该走 yt-dlp（yt-dlp 面对一条图片地址只会失败）。

        命名规则（用户定的）：
          目录 = 作者名；文件 = 标题_序号（实况图另有 **同名** 的 .mp4）。
          目录同名不加编号，重名只在文件名上加 (1)(2)…

        实况图那段「动」的视频必须和图片**同名**（`01.jpg` 配 `01.mp4`）：
          实测过，系统相册是靠「同名的图片 + 视频」来配对的；只要名字对不上，
          相册就当两张不相干的文件，哪怕内容、目录全都对。

        [only_index]：0 = 全部；N = 只下第 N 张（用户滑到某一张后点「下载这张」）。
        """
        media = json.loads(rec.get("media_json") or "[]")
        if not media:
            self.dl_state.update(type="error", active=False, message="这条记录里没有图片地址")
            return

        items = list(enumerate(media, start=1))
        if only_index > 0:
            items = [(i, it) for i, it in items if i == only_index]
            if not items:
                self.dl_state.update(
                    type="error", active=False,
                    message=f"这条作品里没有第 {only_index} 张，可能内容已变化，重新解析一次。",
                )
                return

        # 目录固定用作者名：同一个作者的作品都收在同一个目录里。
        # 原先是重下就给目录加编号（「作者(1)」），用户反馈那不对 —— 他要的是
        # **文件名**重名才加后缀，目录不该因为重下就多出来一份。
        folder = os.path.join(self._output_dir, self._dir_name(rec))
        os.makedirs(folder, exist_ok=True)
        # 文件名前缀 = 标题（超长时保核心、按字节裁并加省略号）
        prefix = self._clip_title(rec.get("title") or "", MAX_NAME_BYTES - 24)
        stem = (prefix + "_") if prefix else ""
        # 这一批文件名若已被占用，就整批加一个 (1)/(2)…，规矩和视频那边一致
        dup = self._dup_index(folder, stem, items)
        tag = f" ({dup})" if dup else ""

        total = len(items)
        done = 0
        # Referer 按平台给：抖音的图片 CDN 会校验它，而推特那边不需要、
        # 带上别人的 Referer 反而可能被 CDN 拒掉。
        headers = {"User-Agent": _BROWSER_UA}
        if rec.get("platform") == "抖音":
            headers["Referer"] = "https://www.douyin.com/"
        for i, item in items:
            src = (item or {}).get("url") or ""
            if not src:
                continue
            kind = (item or {}).get("kind") or "image"
            ext = _ext_from_url(src, ".mp4" if kind == "video" else ".jpg")
            try:
                req = urllib.request.Request(src, headers=headers)
                with urllib.request.urlopen(req, timeout=30) as resp:
                    with open(os.path.join(folder, f"{stem}{i:02d}{tag}{ext}"), "wb") as f:
                        shutil.copyfileobj(resp, f)
            except Exception as exc:  # noqa: BLE001
                # 单张失败不该让整条任务断掉：能下几张是几张，最后如实报数
                print(f"[douyin] 第 {i} 张下载失败：{exc}")
                continue

            live = (item or {}).get("live") or ""
            if live:
                try:
                    req = urllib.request.Request(live, headers=headers)
                    with urllib.request.urlopen(req, timeout=60) as resp:
                        with open(os.path.join(folder, f"{stem}{i:02d}{tag}.mp4"), "wb") as f:
                            shutil.copyfileobj(resp, f)
                except Exception as exc:  # noqa: BLE001
                    print(f"[douyin] 第 {i} 张的实况视频下载失败：{exc}")

            done += 1
            self.dl_state.update(
                type="progress", active=True,
                percent=round(done / total * 100, 1),
                downloaded=done, total=total, speed=0, eta=None,
            )

        if done == 0:
            self.dl_state.update(
                type="error", active=False,
                message="图片一张都没下下来，可能是直链已过期，重新解析一次再试。",
            )
            return

        # 产物是一个目录：历史页据此提供「打开本地文件所在位置」
        self.store.set_local_path(url, folder)
        self.dl_state.update(type="done", active=False, path=folder,
                             message="", percent=100.0)

    @staticmethod
    def _sanitize(raw: str) -> str:
        """把一段文案变成能安全当文件名用的样子。

        控制字符（标题里的换行、Tab 等）必须一并换掉：实测在 Android 的 FUSE 上，
        目录名带换行时 mkdir 能成功，但往里写文件会直接 EPERM
        （Operation not permitted），最后表现为「一张图都没下下来」。
        """
        return re.sub(r'[\x00-\x1f\x7f\\/:*?"<>|]+', "_", raw or "").strip(" ._")

    @classmethod
    def _dir_name(cls, rec: dict) -> str:
        """产物目录名：用作者名。作者拿不到才退回标题 / id。"""
        base = (
            (rec.get("uploader") or "").strip()
            or (rec.get("title") or "").strip()
            or str(rec.get("id") or "douyin")
        )
        safe = cls._sanitize(base) or "douyin"
        encoded = safe.encode("utf-8")
        if len(encoded) > MAX_NAME_BYTES - 16:
            safe = encoded[: MAX_NAME_BYTES - 16].decode("utf-8", "ignore").strip()
        return safe

    @classmethod
    def _clip_title(cls, raw: str, budget: int) -> str:
        """把标题裁到 budget 字节以内，尽量保住「核心标题」。

        实测抖音的标题常常是「正文 + 一长串 #话题」，真正用来认内容的是前面的
        正文，所以超长时先丢掉结尾那串话题标签；这样还不够才按字节硬裁，末尾用
        省略号，让人一眼看出被裁过。真的整条都是正文（没有话题标签）时也一样：
        尽量多留，留不下才裁。
        """
        s = cls._sanitize(raw)
        if not s:
            return ""
        if len(s.encode("utf-8")) <= budget:
            return s
        trimmed = re.sub(r"(\s*#[^\s#]+)+\s*$", "", s).strip()
        src = trimmed or s
        if len(src.encode("utf-8")) <= budget:
            return src
        ell = "…"
        room = max(1, budget - len(ell.encode("utf-8")))
        cut = src.encode("utf-8")[:room].decode("utf-8", "ignore").strip()
        return (cut or src[:1]) + ell

    @staticmethod
    def _dup_index(folder: str, stem: str, items) -> int:
        """这一批文件名有没有被占用的；占了就返回下一个可用编号（0 = 不用加）。

        按「整批」判断、而不是逐个文件各自决定 —— 否则同一批里会出现
        「有的带 (1)、有的不带」，看着像出错了。规矩和视频那边一样：
        重名只在扩展名前加编号，目录本身不动。
        """
        if not os.path.isdir(folder):
            return 0
        # 只比「不含扩展名」的名字：同一张图可能是 .jpg，也可能是 .webp
        taken = {os.path.splitext(x)[0] for x in os.listdir(folder)}
        for n in range(0, 100):
            tag = f" ({n})" if n else ""
            if not any(f"{stem}{i:02d}{tag}" in taken for i, _ in items):
                return n
        return 0

    def _avoid_overwrite(self, opts: dict, target: str, template: str) -> str:
        """同名文件已存在时，把模板改成带 `(1)/(2)…` 的版本，这次另存一份。

        为什么要在外面做：yt-dlp 碰到同名文件是**直接跳过**（实测：对同一个视频
        连点两次下载，文件时间戳和数量都不变），它没有「另存」开关。用户要的是
        「不覆盖、每次留一份」，所以只能先问它「这次打算写哪个文件名」，冲突就换模板。

        探测用的是 download=False，不写盘；拿到的 info 还能复用给候选名重算，
        所以循环里不会再发网络请求。
        """
        probe = {k: v for k, v in opts.items() if k != "logger"}
        probe["quiet"] = True
        try:
            with _YDL({**probe, "outtmpl": template}) as ydl:
                info = ydl.extract_info(target, download=False) or {}
                planned = ydl.prepare_filename(info)
        except Exception:  # noqa: BLE001
            # 探测失败不该影响正常下载：退回原模板，行为跟以前一致
            return template

        if not planned or not os.path.exists(planned):
            return template

        for n in range(1, 100):
            cand_tmpl = _with_dup_suffix(template, n)
            try:
                with _YDL({**probe, "outtmpl": cand_tmpl}) as ydl:
                    cand = ydl.prepare_filename(info)
            except Exception:  # noqa: BLE001
                return cand_tmpl
            if not cand or not os.path.exists(cand):
                print(f"[engine] 同名已存在，这次另存为：{os.path.basename(cand)}")
                return cand_tmpl
        return template

    def _download_worker(self, url: str, index: int = 0,
                         spec: dict | None = None) -> None:
        logger = _Logger()
        try:
            # 图文 / 图集 / 实况图走单独路径
            rec = next(
                (r for r in self.store.all() if r.get("source_url") == url), None
            )
            if rec and rec.get("media_json") and rec.get("content_type") in ("images", "live"):
                self._download_media_list(url, rec, index)
                return

            target = self._download_target(url, spec)

            # 直链（抖音 / X 的作品地址）：自己用 Range 分块并行下。
            # 交给 yt-dlp 只会走单连接整文件拉，concurrent_fragment_downloads
            # 对整文件传输不起作用 —— 这是这条路上唯一能真正提速的口子。
            # 拼不出文件名（库里没有这条记录）时不动它，退回 yt-dlp，行为与以前一致。
            if any(host in target for host in DIRECT_URL_HOSTS):
                direct_path = self._direct_output_path(target, rec)
                if direct_path:
                    self._download_direct(target, direct_path)
                    self._finish_download(url, direct_path)
                    return

            opts = self._base_opts(target)
            # yt-dlp 型档位：把用户选的规格翻译成格式选择表达式。
            # DASH 平台（YouTube / B站）拿不到「一条完整直链」，只能这样选。
            # 直链型档位不走这里 —— 它的地址已经在上一行由 _download_target 定好了。
            selector = (spec or {}).get("selector")
            if selector:
                opts["format"] = selector
            dl = self.defaults.get("download") or {}
            eng = self.defaults.get("engine") or {}
            template = dl.get("filename_template") or "%(title)s [%(id)s].%(ext)s"
            if spec and spec.get("url"):
                # 界面选了档位：目标是一条裸直链，而它**不在库里**（库里存的是默认
                # 档），按 URL 反查必然查不到 → 文件名会退化成 URL 片段。所以直接用
                # 这条记录来拼（rec 在函数开头已按 source_url 查好）。
                template = self._direct_output_name_for(rec) or template
            elif any(host in target for host in DIRECT_URL_HOSTS):
                template = self._direct_output_name(target) or template

            opts.update({
                "paths": {"home": self._output_dir},
                "outtmpl": template,
                "merge_output_format": "mp4",
                "progress_hooks": [self._on_progress],
                "concurrent_fragment_downloads": max(
                    1, int(eng.get("concurrent_fragments") or 1)
                ),
            })
            if "douyinvod.com" in target:
                # 抖音直链来自浏览器监听，其 CDN 会校验 Referer，不带会被拒
                opts["http_headers"] = {"Referer": "https://www.douyin.com/"}

            opts["logger"] = logger

            # 同名文件避让：yt-dlp 自己碰到同名会直接跳过（不重下、不覆盖），
            # 用户要的是「另存一份」，所以这里先探一下文件名、冲突就把模板改成 (1)/(2)…
            opts["outtmpl"] = self._avoid_overwrite(opts, target, template)

            with _YDL(opts) as ydl:
                info = ydl.extract_info(target, download=True) or {}
                path = self._final_path(info, ydl)

            # 收尾：算 MD5（开关打开就先改写），再把**最终产物**的路径记进历史 ——
            # 历史页据此提供「播放本地文件」；对 B站/YouTube 这类纯 DASH 站点，
            # 那是唯一能看的方式
            self._finish_download(url, path)
        except Exception as exc:  # noqa: BLE001
            raw = logger.last_error or f"{type(exc).__name__}: {exc}"
            self.dl_state.update(type="error", active=False,
                                 message=friendly_error(raw))
        finally:
            with self._dl_lock:
                self._dl_busy = False

    # ---- MD5 ----

    @property
    def rewrite_md5_on_download(self) -> bool:
        return bool((self._user or {}).get("rewrite_md5_on_download") or False)

    def set_rewrite_md5(self, enabled: bool) -> dict:
        """设置「下载时自动改写 MD5」。

        默认关：改过的文件 MD5 与平台自报值对不上（那正是它的目的），但这是
        「改变文件内容」的动作，该由用户显式打开，而不是默默替他做掉。
        """
        self._user["rewrite_md5_on_download"] = bool(enabled)
        self._save_user()
        return self.get_settings()

    def rewrite_md5(self, path: str, dst: str = "") -> dict:
        """把文件的 MD5 改掉。dst 为空 = 原地改写；dst 非空 = **另存副本**（原件不动）。

        视频走 ffmpeg 无损重封装（-c copy + 随机 comment 元数据）。为什么不用「末尾
        追加几个字节」那种便宜做法：追加虽然瞬间完成，但产出的是**非规范容器**
        （尾部挂着游离数据），个别播放器 / 相册 / 剪辑软件会拒收。`-c copy` 不重编码、
        画质零损失，产出的仍是一份完全规范的 mp4。代价是整个文件要重写一遍
        （大文件几秒到十几秒、磁盘瞬时占用翻倍）—— 这是选「最稳」那条路的固有成本。

        图片走不了这条路：实测 jpg / png / webp 用 `-c copy -metadata comment=…`
        产出的字节与原件**完全相同**，MD5 一点没变（这些容器里没有 moov-udta 那样
        可写元数据的位置）。所以图片改成在末尾追加一段标记：解码器读到 EOI / IEND
        就收工，尾部多出的字节不影响解码，而字节确实变了 —— 这正是需求要的。

        任何一步失败都**原样保留用户的文件**，并把原因如实返回：绝不能留下半成品
        把文件毁了。
        """
        if not path or not os.path.isfile(path):
            return {"ok": False, "error": "文件不存在，可能已被移动或删除"}
        target = dst or path
        # 随机串才是文件字节真正变化的原因
        stamp = hashlib.md5(os.urandom(16)).hexdigest()[:16]
        try:
            before = file_md5(path)
            if os.path.splitext(target)[1].lower() in IMAGE_EXTS:
                self._rewrite_image_md5(path, target, stamp)
            else:
                self._remux_video_md5(path, target, stamp)
            after = file_md5(target)
        except Exception as exc:  # noqa: BLE001
            return {"ok": False, "error": f"改写失败：{exc}"}
        return {"ok": True, "before": before, "after": after,
                "changed": before != after, "path": target}

    def _remux_video_md5(self, src: str, dst: str, stamp: str) -> None:
        """ffmpeg 无损重封装。dst 允许等于 src（原地改写）。失败抛异常、不留 dst。"""
        if not self._ffmpeg or not os.path.isfile(self._ffmpeg):
            raise RuntimeError("这一端没有可用的 ffmpeg，改不了 MD5")
        # 扩展名要留给 ffmpeg 判断容器：xx.mp4 -> xx.md5tmp.mp4
        ext = os.path.splitext(dst)[1].lower()
        tmp = f"{dst}.md5tmp{ext}"
        cmd = [
            self._ffmpeg, "-y", "-hide_banner", "-loglevel", "error",
            "-i", src, "-c", "copy",
            "-metadata", f"comment=ytdlp-md5-{stamp}",
            tmp,
        ]
        try:
            proc = subprocess.run(cmd, capture_output=True, timeout=600)
            if proc.returncode != 0:
                err = (proc.stderr or b"").decode("utf-8", "replace").strip()
                raise RuntimeError(err[-200:] or f"ffmpeg 退出码 {proc.returncode}")
            if not os.path.isfile(tmp) or os.path.getsize(tmp) == 0:
                raise RuntimeError("ffmpeg 没有产出文件")
            # 同目录内替换是原子操作：中途断电也不会留下一个写坏的成品
            os.replace(tmp, dst)
        except Exception:
            try:
                os.remove(tmp)
            except OSError:
                pass
            raise

    def _rewrite_image_md5(self, src: str, dst: str, stamp: str) -> None:
        """图片：照抄一份，再往里加一段标记写到 dst。失败不留半个文件。

        三种加法（结果都是**规范**图片，只是插的位置不同）：
          png      → 在 IEND 之前插一个标准 tEXt 文本块
          jpg/jpeg → 在 SOI（有 APP0 就排它后面）之后插一个标准 COM 注释段
          其余格式 → 末尾追加字节（webp / gif / heic 没有这么好插的位置，而它们
                     的解码器读到结束标记就收工，尾部字节不影响解码）

        为什么图片不像视频那样直接追加字节：实测追加会让标准工具报
        「Invalid PNG signature / Decoding error」—— png 的解析器会去读尾部多出来的
        那截。png / jpg 本来就有现成的元数据位可插，那就正经插进去。
        """
        ext = os.path.splitext(src)[1].lower()
        marker = f"ytdlp-md5-{stamp}".encode("utf-8")
        tmp = f"{dst}.md5tmp{os.path.splitext(dst)[1].lower()}"
        try:
            with open(src, "rb") as fin, open(tmp, "wb") as fout:
                if ext == ".png" and self._png_insert(fin, fout, marker):
                    pass
                elif ext in (".jpg", ".jpeg") and self._jpeg_insert(fin, fout, marker):
                    pass
                else:
                    shutil.copyfileobj(fin, fout, _DIRECT_READ)
                    fout.write(b"\n<!-- " + marker + b" -->\n")
            os.replace(tmp, dst)
        except Exception:
            try:
                os.remove(tmp)
            except OSError:
                pass
            raise

    @staticmethod
    def _png_insert(fin, fout, marker: bytes) -> bool:
        """在 IHDR 之后插一个 tEXt 块。结构认不出来就返回 False（调用方退回追加）。

        为什么插在头部而不是 IEND 之前：实测把 tEXt 放在 IDAT **之后**，ffmpeg 会报
        「chunk too big」并判为解码失败 —— 流式解码器对 IDAT 之后的块处理得很糙。
        IHDR 一定是第一个块（png 规范硬性要求），所以插入点在文件头就能算出来，
        连「从尾部找 IEND」都不必做。
        """
        head = fin.read(8)
        if head != b"\x89PNG\r\n\x1a\n":
            return False
        len_raw = fin.read(4)
        ctype = fin.read(4)
        if ctype != b"IHDR":
            return False
        ihdr_len = int.from_bytes(len_raw, "big")
        fout.write(head + len_raw + ctype)
        _copy_partial(fin, fout, ihdr_len + 4)  # IHDR 的数据 + 它自己的 CRC
        # 块结构：长度(4) | 类型(4) | 数据 | CRC(4)。**长度只算数据那一段**（不含
        # 类型名，也不含长度自己），CRC 则覆盖「类型 + 数据」—— 这两条不一样，
        # 混了就会让解析器读串（实测把长度算成「类型+数据」时 ffmpeg 报 chunk too big）
        payload = b"Comment\x00" + marker
        chunk = b"tEXt" + payload
        fout.write(len(payload).to_bytes(4, "big") + chunk
                   + (zlib.crc32(chunk) & 0xFFFFFFFF).to_bytes(4, "big"))
        shutil.copyfileobj(fin, fout, _DIRECT_READ)
        return True

    @staticmethod
    def _jpeg_insert(fin, fout, marker: bytes) -> bool:
        """在 SOI 之后插一个 COM 注释段。不是 JPEG 就返回 False（调用方退回追加）。"""
        head = fin.read(2)
        if head != b"\xff\xd8":
            return False
        fout.write(head)
        # JFIF 要求 APP0 紧跟 SOI，所以有 APP0 就让它留在前面，COM 插在它后面
        nxt = fin.read(2)
        app0 = b""
        if len(nxt) == 2 and nxt[0:1] == b"\xff" and nxt[1:2] == b"\xe0":
            seg_len = int.from_bytes(fin.read(2), "big")
            app0 = nxt + seg_len.to_bytes(2, "big") + fin.read(max(seg_len - 2, 0))
        payload = marker[:65533]  # 段长字段只有 2 字节，而且含它自己那 2 字节
        fout.write(app0)
        fout.write(b"\xff\xfe" + (len(payload) + 2).to_bytes(2, "big") + payload)
        if not app0:
            # 那两字节不是 APP0 的开头，得原样补回去（只有在文件异常截断时才会走到）
            fout.write(nxt)
        shutil.copyfileobj(fin, fout, _DIRECT_READ)
        return True

    def _finish_download(self, url: str, path: str) -> None:
        """下载收尾：算 MD5 →（开关打开则）改写 → 记路径 → 报完成。

        顺序不能换：写进库的 local_path 必须是**最终成品**，否则历史页放的是那份
        已经被替换掉的旧文件，播放会失败、看着像下载坏了。

        另外：**磁盘上没有产物就不能报「完成」**。实测反馈过一种情况（微博的
        「音视频分轨」内容）：界面显示下载完成，但相册里没有新文件、播放区也一直
        白屏 —— 根因就是这里以前无条件报 done、并且把 local_path 记成了空串。
        现在改成如实报错，用户至少知道「这次没下来」而不是以为成功了。
        """
        if not path or not os.path.isfile(path):
            self.dl_state.update(
                type="error", active=False,
                message="下载结束了，但磁盘上没有找到成品文件。\n"
                        "常见原因：这条内容的音频和视频是分开给的、合流失败，"
                        "或选中的档位已经失效 —— 换个档位或重试一次通常就好。",
            )
            return

        before = after = ""
        try:
            if path and os.path.isfile(path):
                before = file_md5(path)
                if self.rewrite_md5_on_download:
                    self.dl_state.update(type="processing", active=True,
                                         percent=100.0, message="正在改写 MD5…")
                    r = self.rewrite_md5(path)
                    if r.get("ok"):
                        after = r["after"]
                    else:
                        # 改写失败不该把整个下载判成失败：文件本身已经下来了，
                        # 如实记一笔日志就好
                        print(f"[engine] 改写 MD5 失败：{r.get('error')}")
        except Exception as exc:  # noqa: BLE001
            print(f"[engine] 算 MD5 失败：{exc}")

        self.store.set_local_path(url, path)
        # 库里存「最终那个文件」的 MD5：改过就存改后的，没改或改失败就是原值
        self.store.set_file_md5(path, after or before)
        self.dl_state.update(type="done", active=False, path=path, message="",
                             percent=100.0, md5=before, md5_after=after)

    def list_downloads(self) -> list[dict]:
        """已下载、且文件还躺在原地的**文件**记录 —— MD5 页的数据源。

        只列文件、不列目录：图文/图集的产物是一个文件夹，没有「单一文件的 MD5」
        这一说（用户要的也只是视频）。
        """
        out = []
        for rec in self.store.all():
            path = (rec.get("local_path") or "").strip()
            if not path or not os.path.isfile(path):
                continue
            out.append({
                "id": rec.get("id") or "",
                "title": rec.get("title") or "",
                "platform": rec.get("platform") or "",
                "path": path,
                "filename": os.path.basename(path),
                "size": os.path.getsize(path),
                "source_md5": rec.get("source_md5") or "",
                "file_md5": rec.get("file_md5") or "",
            })
        return out

    def get_file_md5(self, path: str) -> dict:
        """按需现算某个文件的 MD5。

        不读库里的 file_md5：用户可能在文件管理器里动过这个文件（改名、替换、
        编辑），缓存下来的值会变成谎话。
        """
        if not path or not os.path.isfile(path):
            return {"ok": False, "error": "文件不存在，可能已被移动或删除"}
        try:
            return {"ok": True, "path": path, "md5": file_md5(path),
                    "size": os.path.getsize(path)}
        except OSError as exc:
            return {"ok": False, "error": f"读文件失败：{exc}"}

    def edit_file_md5_to(self, src: str, dst: str) -> dict:
        """另存副本式改写：原件全程不动，改后成品写到 dst。

        src 是「用户从相册/文件里挑中的东西」在缓存里的临时副本，它不在历史库里，
        所以这里不回填任何记录。

        只提供这一条对外接口：改自己的产物也走「另存副本」，用户的原文件永远不动
        （in-place 那条路留给下载收尾，见 _finish_download）。
        """
        return self.rewrite_md5(src, dst)

    @staticmethod
    def _final_path(info: dict, ydl) -> str:
        """取出下载产物的最终路径。

        踩过的坑：顶层 info 上**没有** filepath 这个键（实测恒为 None，
        即便 --print after_move 在命令行版能拿到值）。yt-dlp 把路径挂在
        requested_downloads 的每条记录上；合流场景下这个列表会收敛成一条
        （format_id 形如 "30016+30216"），其 filepath 就是最终 mp4。
        直接读 info['filepath'] 会永远拿到空字符串 —— 文件明明下载成功，
        界面却说不出来存到哪了，也就没法「打开所在目录」。
        """
        path = info.get("filepath") or ""
        if path and os.path.exists(path):
            return path
        for f in info.get("requested_downloads") or []:
            cand = f.get("filepath") or f.get("_filename") or ""
            # 中间产物（视频轨/音频轨）在合流后会被 yt-dlp 删掉，
            # 用 exists 能把它们筛掉，只留下真正还躺在磁盘上的那个
            if cand and os.path.exists(cand):
                return cand
        # 兜底：按模板重算。合流后扩展名已变，这一步能算成 mp4
        try:
            return ydl.prepare_filename(info)
        except Exception:  # noqa: BLE001
            return ""

    # ---- 内部工具（与 windows/engine.py 保持一致） ----

    @staticmethod
    def _to_record(info: dict, fallback_url: str) -> dict:
        source = info.get("webpage_url") or fallback_url
        play_url = Engine._pick_playable_url(info)
        play_kind = "progressive" if play_url else "none"
        if not play_url:
            # 纯音频：没有合流轨，但音频本身可以直接播（播放器会给进度条）
            audio_url = Engine._pick_audio_url(info)
            if audio_url:
                play_url, play_kind = audio_url, "audio"
        return {
            "id": str(info.get("id") or ""),
            "platform": detect_platform(source),
            "title": info.get("title") or "",
            "uploader": info.get("uploader") or info.get("channel") or "",
            # uploader_id 是平台的唯一标识（推特号 / B站 UID / 抖音号等），
            # 部分平台没有这个字段，界面按「没有就不显示」处理
            "uploader_id": info.get("uploader_id") or "",
            "duration": int(info["duration"]) if info.get("duration") else None,
            "thumbnail": info.get("thumbnail") or "",
            "source_url": source,
            "resolved_url": play_url,
            "download_url": "",  # yt-dlp 路径自己会选格式，不需要单独指定
            "resolved_at": int(time.time()),
            "play_kind": play_kind,
        }

    def _with_local_path(self, rec: dict) -> dict:
        """把历史库里已记录的产物路径补进解析结果。

        解析结果原本不带 local_path —— 它来自 yt-dlp 的 info，而产物路径是下载
        完成后才由我们自己记进历史的。于是「先下过某个视频、之后又解析同一条」时，
        界面不知道文件已经存在，仍然提示「需下载后才能播放」（实测出来的 bug）。

        这里按 id 优先、source_url 兜底去历史库找一次；文件已被用户删掉就忽略，
        免得指向一个不存在的路径。
        """
        match = None
        for old in self.store.all():
            if rec.get("id") and old.get("id") == rec["id"]:
                match = old
                break
            if match is None and old.get("source_url") == rec.get("source_url"):
                match = old
        path = ((match or {}).get("local_path") or "").strip()
        if path and os.path.exists(path):
            rec["local_path"] = path
        return rec

    @staticmethod
    def _pick_audio_url(info: dict) -> str:
        """纯音频内容：挑一条能直接喂给播放器的音频地址。

        为什么单独做：[_pick_playable_url] 要求「音视频都在」，纯音频会被排除，
        于是解析完拿不到在线地址、界面只能说「需下载后才能播放」—— 其实它**本来
        就能直接播**，只是被那条规则挡掉了（用户实测反馈过这类内容）。

        判据要严：必须 `vcodec == "none"` 且 acodec 是真实编码。`vcodec` 为 null
        表示「未知」（X 的渐进式 mp4 就是这种），不能当成纯音频。
        """
        for fmt in info.get("formats") or []:
            if not fmt.get("url"):
                continue
            if fmt.get("vcodec") != "none":
                continue
            if fmt.get("acodec") in (None, "none"):
                continue
            if str(fmt.get("protocol") or "").startswith("m3u8"):
                continue
            return fmt.get("url") or ""
        return ""

    @staticmethod
    def _pick_playable_url(info: dict) -> str:
        """挑一个能直接喂给播放器的链接。

        实测踩出来的条件（安卓端用 Media3，规则和浏览器一致）：
        - 排除纯音频（vcodec == 'none'）和纯视频（acodec == 'none'）
        - 排除 HLS（m3u8）
        - **vcodec/acodec 为 null 的不能排除**：X(Twitter) 的渐进式 mp4
          就是这种，编码信息要下载后才知道，但本身音视频合一，可以直连播放
        """
        candidates = []
        for fmt in info.get("formats") or []:
            if not fmt.get("url"):
                continue
            if fmt.get("vcodec") == "none" or fmt.get("acodec") == "none":
                continue
            if str(fmt.get("protocol") or "").startswith("m3u8"):
                continue
            candidates.append(fmt)

        if not candidates:
            return ""
        best = max(candidates, key=lambda f: (f.get("height") or 0))
        return best.get("url") or ""

    @staticmethod
    def _describe_quality(info: dict) -> str:
        wanted = info.get("requested_formats") or []
        if wanted:
            height = max((f.get("height") or 0) for f in wanted) or None
            ext = wanted[0].get("ext") or ""
        else:
            height = info.get("height")
            ext = info.get("ext") or ""
        parts = [f"{height}p"] if height else []
        if ext:
            parts.append(ext)
        return " · ".join(parts)

    @staticmethod
    def _describe_size(info: dict) -> str:
        wanted = info.get("requested_formats") or []
        if wanted:
            total = sum(
                (f.get("filesize") or f.get("filesize_approx") or 0) for f in wanted
            )
        else:
            total = info.get("filesize") or info.get("filesize_approx") or 0
        if not total:
            return ""
        return f"预计 {total / 1024 / 1024:.0f} MB"


# ==================== Chaquopy 入口 ====================
# MainActivity 只能调模块级函数，所以这里把 Engine 包一层。
# 返回值统一用 JSON 字符串：跨语言边界传结构化数据时，字符串比让
# Chaquopy 去猜 Python 对象怎么映射成 Java 对象要可预测得多。
#
# 调用约定（阶段 3 写界面时必须遵守）：
#   * 所有函数都是**阻塞**的，必须从后台线程调 —— 解析一条境外链接可能
#     要几十秒，放主线程就是 ANR。
#   * parse_url 不可中断。进程内没有子进程可 kill，界面的「取消」只能做到
#     「我不等了」，Python 那边会自己跑完再把结果丢掉。所以界面必须自己
#     收住超时（建议 75 秒，与 Windows 端一致），不能指望引擎返回。
#   * 下载进度用 get_download_state() 轮询，只在 active 期间轮询。


_engine: Engine | None = None


def _get() -> Engine:
    if _engine is None:
        raise RuntimeError("引擎还没初始化，请先调用 configure()")
    return _engine


def configure(data_dir: str, ffmpeg_path: str, qjs_path: str,
              preferred_dir: str, fallback_dir: str) -> str:
    """由 Kotlin 在启动时调一次，把只有安卓侧才知道的路径交进来。"""
    global _engine
    _engine = Engine(Path(data_dir), ffmpeg_path, qjs_path,
                     preferred_dir, fallback_dir)
    return json.dumps(_engine.get_settings(), ensure_ascii=False)


def get_settings() -> str:
    return json.dumps(_get().get_settings(), ensure_ascii=False)


def set_output_dir(path: str) -> str:
    return json.dumps(_get().set_output_dir(path), ensure_ascii=False)


def set_cookies_file(path: str) -> str:
    return json.dumps(_get().set_cookies_file(path), ensure_ascii=False)


def set_rewrite_md5(enabled: bool) -> str:
    """设置「下载时自动改写 MD5」，返回最新设置。"""
    return json.dumps(_get().set_rewrite_md5(enabled), ensure_ascii=False)


def list_downloads() -> str:
    """已下载、且文件还在本地的记录（含平台自报 MD5 与实际 MD5）—— MD5 页用。"""
    return json.dumps(_get().list_downloads(), ensure_ascii=False)


def get_md5(path: str) -> str:
    """现算某个文件的 MD5（不读库里的缓存值）。"""
    return json.dumps(_get().get_file_md5(path), ensure_ascii=False)


def edit_md5_to(src: str, dst: str) -> str:
    """另存副本式改写 MD5（原件全程不动），返回改前 / 改后的值。"""
    return json.dumps(_get().edit_file_md5_to(src, dst), ensure_ascii=False)


def parse_url(url: str) -> str:
    return json.dumps(_get().parse_url(url), ensure_ascii=False)


def start_download(url: str, index: int = 0, spec: str = "") -> str:
    """[spec] 是界面选中的下载档位（JSON 字符串）；空串表示用引擎默认档。"""
    return json.dumps(_get().start_download(url, index, spec), ensure_ascii=False)


def get_download_state() -> str:
    return json.dumps(_get().get_download_state(), ensure_ascii=False)


def get_history() -> str:
    return json.dumps(_get().get_history(), ensure_ascii=False)


def delete_history(vid: str) -> str:
    return json.dumps(_get().delete_history(vid), ensure_ascii=False)


def clear_history(platform: str) -> str:
    return json.dumps(_get().clear_history(platform), ensure_ascii=False)


def clear_all_history() -> str:
    return json.dumps(_get().clear_all_history(), ensure_ascii=False)


def save_douyin(payload_json: str) -> str:
    """由 Kotlin 侧的 WebView 解析页调用：把取到的抖音数据入库并回传记录。"""
    return json.dumps(_get().save_douyin(payload_json), ensure_ascii=False)


def save_xhs(payload_json: str) -> str:
    """由 Kotlin 侧的 WebView 解析页调用：把取到的小红书笔记入库并回传记录。"""
    return json.dumps(_get().save_xhs(payload_json), ensure_ascii=False)


def save_weibo(payload_json: str) -> str:
    """由 Kotlin 侧的 WebView 解析页调用：把取到的微博正文入库并回传记录。"""
    return json.dumps(_get().save_weibo(payload_json), ensure_ascii=False)
