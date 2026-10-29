"""引擎层：调用 yt-dlp 与本地历史存储。

这一层完全不涉及界面与 HTTP，可以被冒烟测试直接调用。
"""

from __future__ import annotations

import hashlib
import html
import json
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
import uuid
import zlib
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urlparse

BASE_DIR = Path(__file__).resolve().parent
CONFIG_FILE = BASE_DIR / "config" / "defaults.json"

DATA_DIR = Path(os.environ.get("APPDATA") or Path.home()) / "yt-dlp-gui"
DB_FILE = DATA_DIR / "history.db"
SETTINGS_FILE = DATA_DIR / "settings.json"

# 解析超时（秒）。境外站点在 VPN 未开时会一直重试到超时
PARSE_TIMEOUT = 30

PROGRESS_MARK = "__PROG__"
DONE_MARK = "__DONE__"

# 进度行字段：百分比 | 字节/秒 | 剩余秒 | 已下字节 | 总字节 | 估算总字节
# 后两项都可能是 NA：服务端不给长度时只能报「已下多少」。
PROGRESS_TEMPLATE = (
    f"download:{PROGRESS_MARK}%(progress._percent_str)s|%(progress.speed)s|"
    "%(progress.eta)s|%(progress.downloaded_bytes)s|%(progress.total_bytes)s|"
    "%(progress.total_bytes_estimate)s"
)


def _to_int(raw: str) -> int:
    """把 yt-dlp 输出的数字字段转成 int。NA / 空串 / 小数都兜得住。"""
    text = (raw or "").strip()
    if not text or text in ("NA", "N/A", "None"):
        return 0
    try:
        return int(float(text))
    except (TypeError, ValueError):
        return 0


def _to_eta(raw: str) -> int | None:
    """剩余时间：支持 "00:12" / "1:02:03" / "NA"。"""
    text = (raw or "").strip()
    if not text or text in ("NA", "N/A", "None", "--:--"):
        return None
    parts = text.split(":")
    try:
        nums = [int(p) for p in parts]
    except ValueError:
        return None
    seconds = 0
    for n in nums:
        seconds = seconds * 60 + n
    return seconds


def parse_progress(value: str) -> dict:
    """把 PROGRESS_TEMPLATE 那行解析成结构化字段。

    前端不再拼 "12.3%|1.2MiB/s|00:05" 这种字符串 —— 缺字段时解析出来的
    永远是 0 / None，界面据此显示「不确定态」，而不是把 NA 当成 0% 卡住。
    """
    parts = (value or "").split("|")
    parts += [""] * (6 - len(parts))
    pct_raw = parts[0].replace("%", "").strip()
    try:
        percent = float(pct_raw) if pct_raw and pct_raw.upper() != "NA" else None
    except ValueError:
        percent = None
    total = _to_int(parts[4]) or _to_int(parts[5])
    return {
        "percent": percent,
        "speed": _to_int(parts[1]),
        "eta": _to_eta(parts[2]),
        "downloaded": _to_int(parts[3]),
        "total": total,
    }


def path_in_dir(path: str, root: str) -> bool:
    """判断 path 是否真的在 root 目录内（防 /file 被当成任意文件读取入口）。

    commonpath 是必须的：直接比较字符串前缀会把 Download2 当成 Download 的子目录。
    Windows 路径大小写不敏感，所以两边都先 normcase。
    """
    if not path or not root:
        return False
    try:
        target = os.path.normcase(os.path.realpath(path))
        base = os.path.normcase(os.path.realpath(root))
        return os.path.commonpath([target, base]) == base
    except (ValueError, OSError):
        return False


def compute_file_md5(path: str) -> str:
    """分块算整份文件的 MD5（大文件不能一次读进内存）。"""
    digest = hashlib.md5()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


# 图片类扩展名：改 MD5 走「插元数据块」而不是 ffmpeg 重封装
IMAGE_EXTS = (".jpg", ".jpeg", ".png", ".webp", ".gif", ".heic", ".bmp")


def _copy_partial(fin, fout, length: int) -> None:
    """从 fin 原样拷 length 个字节到 fout（不足则拷完为止）。"""
    remaining = length
    while remaining > 0:
        buf = fin.read(min(64 * 1024, remaining))
        if not buf:
            break
        fout.write(buf)
        remaining -= len(buf)


def resolve_ffmpeg_exe(cfg: dict) -> str:
    """返回 ffmpeg.exe 的完整路径；找不到就返回空串。"""
    candidates = [
        BASE_DIR / "bin" / "ffmpeg" / "ffmpeg.exe",
        Path((cfg.get("engine") or {}).get("ffmpeg_dir") or "") / "ffmpeg.exe",
    ]
    for cand in candidates:
        if str(cand) and cand.is_file():
            return str(cand)
    exe = shutil.which("ffmpeg")
    return exe or ""


def resolve_ffprobe_exe(cfg: dict) -> str:
    """返回 ffprobe.exe 的完整路径；找不到就返回空串。"""
    candidates = [
        BASE_DIR / "bin" / "ffmpeg" / "ffprobe.exe",
        Path((cfg.get("engine") or {}).get("ffmpeg_dir") or "") / "ffprobe.exe",
    ]
    for cand in candidates:
        if str(cand) and cand.is_file():
            return str(cand)
    exe = shutil.which("ffprobe")
    return exe or ""


# 直链拿到的是裸地址（没有元数据），下载命令要据此改用「库里标题」拼文件名。
# 抖音这几个域名是实测出来的：采样 160 条直链，全部落在 365yg.com / amemv.com 上，
# 一条 douyinvod.com 都没有 —— 只认 douyinvod.com 会让文件名退化成 URL 片段。
# 与安卓端 ytdlp_engine.DIRECT_URL_HOSTS 对应，改一处要同步另一处。
# 快手：视频在 ndcimgs.com，备用 CDN 在 *.kwimgs.com；公众号文章里的视频在
# mpvideo.qpic.cn。三者都是完整 mp4，交给 yt-dlp 只会报「不支持这种链接」。
DIRECT_URL_HOSTS = ("douyinvod.com", "365yg.com", "amemv.com", "video.twimg.com",
                    "xhscdn.com", "rednotecdn.com", "mpvideo.qpic.cn",
                    "ndcimgs.com", "kwimgs.com")

# 下载图片/图集时用的浏览器 UA（很多 CDN 会看 UA）
BROWSER_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)

# 直链分块下载的读取粒度（1 MiB 是实测的折中：太小线程调度开销盖过收益）
_DIRECT_READ = 1 << 20

# 正文 / 标题入库前的长度上限（与安卓端同名常量一致）。
DESC_MAX_CHARS = 20000
TITLE_MAX_CHARS = 50

# ==================== 文本归一化 ====================
# 与安卓端 _norm_description / _norm_title 同构（改一处要同步另一处）：
# 各平台给的是「原文」，抖音的 desc 带十几个话题、微博是整段正文，
# 原样入库会把卡片撑爆；标题限长、正文归一这一层只写一份。


def _norm_description(raw, title: str = "") -> str:
    """把各平台原文归一成可直接展示的纯文本（去标签、压空行、限长）。"""
    if not isinstance(raw, str) or not raw.strip():
        return ""
    text = raw.replace("\r\n", "\n").replace("\r", "\n")
    text = re.sub(r"<br\s*/?>", "\n", text, flags=re.I)
    text = re.sub(r"</(?:p|div|li|h[1-6]|figure|tr|blockquote)\s*>", "\n", text, flags=re.I)
    text = re.sub(r"<[^>]+>", "", text)
    text = html.unescape(text)
    text = text.replace("\u200b", "").replace("\ufeff", "").replace("\xa0", " ")
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r"\n[ \t]+", "\n", text)
    text = re.sub(r"\n{3,}", "\n\n", text).strip()
    # 只有标点/符号的「正文」没有阅读价值，按没有正文处理
    if not re.search(r"[^\W_]", text):
        return ""
    if len(text) > DESC_MAX_CHARS:
        text = text[:DESC_MAX_CHARS].rstrip() + "…"
    if title:
        flat = re.sub(r"\s+", " ", text).strip()
        same = re.sub(r"\s+", " ", str(title)).strip()
        # 只有标题短到能完整显示时，才把内容相同的正文丢掉
        if flat and flat == same and len(flat) <= TITLE_MAX_CHARS:
            return ""
    return text


def _norm_title(raw, body: str = "") -> str:
    """标题收成一行、限长 50 字；没标题就用正文第一段顶上。"""
    text = _norm_description(raw)
    if text:
        text = re.sub(r"\s*\n+\s*", " ", text).strip()
    if not text and body:
        text = re.sub(r"\s*\n+\s*", " ", _norm_description(body)).strip()
    if not text:
        return ""
    if len(text) > TITLE_MAX_CHARS:
        text = text[:TITLE_MAX_CHARS].rstrip() + "…"
    return text


PLATFORM_RULES = [
    ("YouTube", r"(^|//)([a-z0-9-]+\.)*youtu(?:\.be|be\.com)(?![a-z0-9.-])"),
    ("B站", r"(^|//)([a-z0-9-]+\.)*(bilibili\.com|b23\.tv)(?![a-z0-9.-])"),
    ("抖音", r"(^|//)([a-z0-9-]+\.)*(douyin\.com|iesdouyin\.com)(?![a-z0-9.-])"),
    # TikTok 与国内抖音在 yt-dlp 里是两个独立提取器，可用性也完全不同
    # （TikTok 通常能解析，抖音常因境外 IP + 签名拦截失败），所以分开归类。
    ("TikTok", r"(^|//)([a-z0-9-]+\.)*tiktok\.com(?![a-z0-9.-])"),
    ("X", r"(^|//)([a-z0-9-]+\.)*(x\.com|twitter\.com)(?![a-z0-9.-])"),
    ("Instagram", r"(^|//)([a-z0-9-]+\.)*instagram\.com(?![a-z0-9.-])"),
    # 小红书。分享出来的多是 xhslink.cn 短链（.com 也存在）。
    # 与安卓端 ytdlp_engine.PLATFORM_RULES 是同一份规格，改一处要同步另一处。
    ("小红书", r"(^|//)([a-z0-9-]+\.)*(xiaohongshu\.com|xhslink\.(?:cn|com))(?![a-z0-9.-])"),
    # 视频号：分享短链是 weixin.qq.com/sph/<id>，跳转后落在 channels.weixin.qq.com。
    # 视频本身拿不到（见 _parse_wechat_channels），这里只负责识别出来，
    # 好给用户一句明确提示，而不是「不支持这种链接」。
    ("微信视频号", r"(^|//)([a-z0-9-]+\.)*(channels\.weixin\.qq\.com|weixin\.qq\.com/sph/)"),
    # 微信公众号：文章与「贴图/图文」都是 mp.weixin.qq.com 下的公开网页。
    ("微信公众号", r"(^|//)([a-z0-9-]+\.)*mp\.weixin\.qq\.com(?![a-z0-9.-])"),
    # 微博。实测（yt-dlp 自带样例）：weibo.com/tv/show、m.weibo.cn/status、
    # weibo.com/<uid>/<mid> 三种形态都能开箱解析，不需要登录态。
    # 与安卓端 ytdlp_engine.PLATFORM_RULES 同步。
    ("微博", r"(^|//)([a-z0-9-]+\.)*(weibo\.com|weibo\.cn)(?![a-z0-9.-])"),
    # 快手。分享短链是 v.kuaishou.com/<code>，移动 UA 下会 302 到
    # v.m.chenzhongtech.com/fw/photo/<id>（同一家的分享页域名，服务端直出数据）。
    ("快手", r"(^|//)([a-z0-9-]+\.)*(kuaishou\.com|chenzhongtech\.com)(?![a-z0-9.-])"),
]


def detect_platform(url: str) -> str:
    u = (url or "").lower()
    for name, pattern in PLATFORM_RULES:
        if re.search(pattern, u, re.I):
            return name
    return "其他"


# 「链接」的形状：scheme://主机，主机必须带点。主机不要求带点的话，
# "abc://def" 这种随手打的也会被当成链接放过去。
_URL_SHAPE = re.compile(r"^[a-z][a-z0-9+.\-]*://[^\s/?#]+\.[^\s/?#]+", re.I)


def looks_like_url(text: str) -> bool:
    return bool(_URL_SHAPE.match((text or "").strip()))


# ==================== 档位（抖音）====================


def _tier_label(height: int, vcodec: str, filesize: int | None, note: str = "") -> str:
    """拼一条档位的展示文案，如「720p · H.265 · 37.4 MB」。"""
    parts = []
    if height:
        parts.append(f"{height}p")
    codec = {"h264": "H.264", "h265": "H.265", "vp9": "VP9", "av1": "AV1"}.get(vcodec or "", "")
    if codec:
        parts.append(codec)
    if note:
        parts.append(note)
    if filesize:
        parts.append(f"{filesize / 1024 / 1024:.1f} MB")
    return " · ".join(parts) or "原始画质"


def douyin_collect_tiers(video: dict) -> list[dict]:
    """把抖音的 bit_rate 数组整理成档位全集（好 → 差）。

    规则与安卓端 douyin_collect_tiers 完全一致，**只保留这一份实现**：
    分辨率优先；同高度优先 H.264（兼容性好）；最后看码率。
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
            "hash": str(play.get("file_hash") or ""),
        })

    # 默认档（video.play_addr）不一定出现在 bit_rate 里，漏掉它会让用户
    # 以为「这条只能选 H.265」。不与已有档位重复时补进来。
    pa = video.get("play_addr") or {}
    pa_urls = pa.get("url_list") or []
    default_url = pa_urls[0] if pa_urls else ""
    if default_url and not any(r["url"] == default_url for r in rows):
        pa_size = pa.get("data_size")
        rows.append({
            "height": int(pa.get("height") or 0),
            "vcodec": "",
            "fmt": "mp4",
            "kbps": 0,
            "filesize": int(pa_size) if pa_size else None,
            "url": default_url,
            "hash": str(pa.get("file_hash") or ""),
            "is_default": True,
        })

    rows.sort(key=lambda r: (r["height"], 1 if r["vcodec"] == "h264" else 0, r["kbps"]),
              reverse=True)
    for r in rows:
        r["label"] = _tier_label(
            r["height"], r["vcodec"], r["filesize"],
            note="平台默认档" if r.pop("is_default", False) else "",
        )
    return rows


def douyin_pick_tiers(video: dict) -> tuple[str, str]:
    """挑两条地址：能直接播放的（H.264 + mp4）、默认下载的（最高档）。"""
    tiers = douyin_collect_tiers(video)
    best_any = tiers[0]["url"] if tiers else ""
    best_play = next((t["url"] for t in tiers if t["vcodec"] == "h264" and t["fmt"] == "mp4"),
                     "")
    if not best_play:
        urls = ((video.get("play_addr") or {}).get("url_list")) or []
        best_play = urls[0] if urls else ""
    if not best_any:
        best_any = best_play
    return best_play, best_any


def _douyin_image_url(img: dict) -> str:
    """图文/图集挑一张图：优先 url_list（无水印），再优先 jpeg。"""
    urls = img.get("url_list") or []
    fallback = ""
    for u in urls:
        if not u:
            continue
        if not fallback:
            fallback = u
        low = u.lower()
        if ".jpeg" in low or ".jpg" in low:
            return u
    return fallback


def _douyin_live_url(img: dict) -> str:
    """实况图里「动的那一段」视频地址；普通图片返回空串。"""
    video = img.get("video") or {}
    play = ((video.get("play_addr") or {}).get("url_list") or [])
    if play:
        return play[0]
    for br in (video.get("bit_rate") or []):
        u = (((br or {}).get("play_addr") or {}).get("url_list") or [""])[0]
        if u:
            return u
    return ""


# ==================== 快手：移动版分享页的服务端数据 ====================
# 与安卓端同构。要点：短链跳转目标取决于 UA —— 桌面 UA 落到 JS 壳页（零数据），
# 移动 UA 落到 v.m.chenzhongtech.com/fw/photo/<id>，服务端直出完整 JSON。
# 页面数据在 <script> 里、根对象 key 被凯撒位移混淆，但内层字段名正常，
# 所以只能「按值找同时含 photoId 与 photoType 的对象」。
KUAISHOU_UA = (
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
)
KUAISHOU_MOBILE_PAGE = "https://v.m.chenzhongtech.com/fw/photo/{pid}"


def _ks_photo_id(url: str) -> str:
    m = re.search(r"(?:short-video|/photo|fw/photo)/([0-9A-Za-z_-]+)", url or "")
    return m.group(1) if m else ""


def _ks_fetch(url: str) -> tuple[str, str]:
    req = urllib.request.Request(url, headers={"User-Agent": KUAISHOU_UA})
    with urllib.request.urlopen(req, timeout=25) as resp:
        return resp.geturl(), resp.read().decode("utf-8", "replace")


def _ks_ssr_json(html_text: str):
    i = html_text.find('"photoType"')
    if i < 0:
        return None
    start = html_text.rfind("<script", 0, i)
    body_start = html_text.find(">", start)
    end = html_text.find("</script>", body_start)
    if body_start < 0 or end < 0:
        return None
    body = html_text[body_start + 1:end].strip()
    for candidate in (body, body[body.find("{"):body.rfind("}") + 1]):
        try:
            return json.loads(candidate)
        except Exception:  # noqa: BLE001
            continue
    return None


def _ks_find_photo(obj) -> dict | None:
    if isinstance(obj, dict):
        if "photoType" in obj and "photoId" in obj:
            return obj
        for v in obj.values():
            found = _ks_find_photo(v)
            if found:
                return found
    elif isinstance(obj, list):
        for v in obj:
            found = _ks_find_photo(v)
            if found:
                return found
    return None


def _ks_video_tiers(photo: dict) -> list[dict]:
    """manifest.adaptationSet → 档位。竖屏的 height 是长边，所以档位名读 qualityType。"""
    best: dict[int, tuple[int, str]] = {}
    for group in (photo.get("manifest") or {}).get("adaptationSet") or []:
        for rep in group.get("representation") or []:
            url = str(rep.get("url") or "")
            if not url:
                continue
            qt = str(rep.get("qualityType") or "")
            m = re.match(r"(\d+)p", qt)
            if m:
                height = int(m.group(1))
            else:
                height = min(int(rep.get("width") or 0), int(rep.get("height") or 0))
            bitrate = int(rep.get("avgBitrate") or 0)
            old = best.get(height)
            if old is None or bitrate > old[0]:
                best[height] = (bitrate, url)

    rows = [{"height": h, "vcodec": "", "filesize": None, "url": u, "selector": ""}
            for h, (_, u) in best.items()]
    if not rows:
        seen: set[str] = set()
        for item in photo.get("mainMvUrls") or []:
            url = str((item or {}).get("url") or "")
            if url and url not in seen:
                seen.add(url)
                rows.append({"height": 0, "vcodec": "", "filesize": None,
                             "url": url, "selector": ""})
    rows.sort(key=lambda r: r["height"], reverse=True)
    for r in rows:
        r["label"] = _tier_label(r["height"], r["vcodec"], r["filesize"])
    return rows


def _ks_atlas_media(photo: dict) -> list[dict]:
    """图集：`ext_params.atlas` 里是路径列表 + 尺寸表 + CDN 列表，地址要自己拼。"""
    atlas = (photo.get("ext_params") or {}).get("atlas") or {}
    paths = atlas.get("list") or []
    sizes = atlas.get("size") or []
    cdns = [c.get("cdn") for c in (atlas.get("cdnList") or []) if c.get("cdn")]
    if not cdns:
        cdns = atlas.get("cdn") or []
    if not cdns or not paths:
        return []
    media: list[dict] = []
    for i, path in enumerate(paths):
        if not path:
            continue
        size = sizes[i] if i < len(sizes) else {}
        media.append({
            "url": f"https://{cdns[0]}{path}",
            "width": int((size or {}).get("w") or 0),
            "height": int((size or {}).get("h") or 0),
            "live": "",
            "kind": "image",
        })
    return media


# ==================== yt-dlp 平台：把 formats 归并成档位 ====================
# 与安卓端 ytdlp_collect_tiers 同构。关键点：DASH 平台（YouTube / B站）拿不到
# 「一条完整直链」，所以档位不是 URL 而是一个分辨率档 + 交给 yt-dlp 的选流表达式。


def _ytdlp_vcodec_name(raw: str) -> str:
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
    """把 yt-dlp 的 formats 归并成档位全集（按好 → 差排序）。"""
    formats = info.get("formats") or []

    # 音频只用于估体积和被 selector 引用：成品 = 视频流 + 音频流
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
        # 同高度优先 H.264，再比码率
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

    rows = sorted(by_height.values(), key=lambda r: (r["height"], r["_key"]), reverse=True)
    for r in rows:
        r.pop("_key", None)
        r["label"] = _tier_label(r["height"], r["vcodec"], r["filesize"])
    return rows


class SpeedMeter:
    """近 4 秒滑动窗口的瞬时速率。

    累计平均值会被中途一次卡顿永久拉低、之后只能慢慢往回爬 —— 用户看到的就是
    「几 MB → 几十 KB → 慢慢涨回去」。窗口速率反映的是「此刻多快」。
    """

    WINDOW = 4.0

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._marks: list[tuple[float, int]] = []

    def reset(self) -> None:
        with self._lock:
            self._marks.clear()

    def feed(self, got: int, started: float) -> int:
        now = time.time()
        with self._lock:
            marks = self._marks
            marks.append((now, got))
            while len(marks) > 2 and marks[0][0] < now - self.WINDOW:
                marks.pop(0)
            t0, g0 = marks[0]
        span = now - t0
        if span > 0.2:
            return max(0, int((got - g0) / span))
        return int(got / max(0.001, now - started))


# ==================== 小红书的视频流挑选 ====================
# 与安卓端 _xhs_pick_video 同构：优先「画面+声音」合流的候选，再比高度/体积。
# 只看高度会挑中纯画面分片 —— 用户下到的是没声音的文件（实测踩过）。


def _xhs_stream_entries(video_json: str) -> list:
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
    if int(entry.get("audioChannels") or 0) > 0:
        return True
    if int(entry.get("audioBitrate") or 0) > 0:
        return True
    codec = str(entry.get("audioCodec") or "").lower()
    return codec not in ("", "none")


def _xhs_pick_video(video_json: str) -> tuple[str, str, bool]:
    """返回 (播放地址, 下载地址, 是否带声音)。"""
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


# 微博「视频页」：这些形态 yt-dlp 能直接解析，保持走 yt-dlp（快）。
_WEIBO_VIDEO_PAGE = re.compile(
    r"(^|//)([a-z0-9-]+\.)*(video\.weibo\.com|weibo\.com/tv/show)", re.I
)

# 微博「正文页」：`weibo.com/<uid>/<mid>` 与 `weibo.com/<uid>/detail/<mid>`。
# yt-dlp 对这类页面会误判（把 9 张实况图当成音视频分轨视频，下载后无产物），
# 所以先走浏览器读权威数据。
_WEIBO_POST_PAGE = re.compile(
    r"(^|//)([a-z0-9-]+\.)*"
    r"(weibo\.com/\d+/(detail/)?|m\.weibo\.cn/(status|detail)/)[0-9A-Za-z]{6,}",
    re.I,
)

# ==================== 微信公众号 / 视频号 ====================
WECHAT_UA = (
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 "
    "(KHTML, like Gecko) Mobile/15E148 MicroMessenger/8.0.49 NetType/WIFI Language/zh_CN"
)
WECHAT_REFERER = "https://mp.weixin.qq.com/"
WECHAT_CHANNELS_API = "https://channels.weixin.qq.com/finder-preview/api/feed/get_feed_info"


class _WxTextParser(HTMLParser):
    """公众号正文 HTML → 纯文本：块级标签换行，脚本/样式整段丢掉。"""

    _BLOCK = {"p", "div", "section", "br", "li", "h1", "h2", "h3", "h4",
              "blockquote", "tr", "table", "figure", "figcaption"}

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self._out: list[str] = []
        self._skip = 0

    def handle_starttag(self, tag, attrs):
        if tag in ("script", "style"):
            self._skip += 1
        if tag in self._BLOCK:
            self._out.append("\n")

    def handle_endtag(self, tag):
        if tag in ("script", "style") and self._skip:
            self._skip -= 1
        if tag in self._BLOCK:
            self._out.append("\n")

    def handle_data(self, data):
        if not self._skip:
            self._out.append(data)

    def text(self) -> str:
        raw = "".join(self._out)
        lines = [re.sub(r"[ \t\u3000]+", " ", ln).strip() for ln in raw.splitlines()]
        return "\n".join(ln for ln in lines if ln).strip()


def _wx_unescape(s: str) -> str:
    s = (s or "").replace("\\x26amp;", "&").replace("\\x26", "&")
    s = s.replace("\\x0a", "\n").replace("\\x3d", "=").replace("\\x3f", "?")
    return html.unescape(s)


def _wx_first(page: str, pattern: str) -> str:
    m = re.search(pattern, page)
    return _wx_unescape(m.group(1)).strip() if m else ""


def _wx_js_var(page: str, name: str) -> str:
    for q in ("'", '"'):
        m = re.search(rf"\b{name}\s*=\s*{q}([^{q}]*){q}", page)
        if m:
            v = _wx_unescape(m.group(1)).strip()
            v = re.sub(r"\.html\((?:false|true)\)$", "", v).strip()
            if v:
                return v
    return ""


def _wx_meta(page: str, prop: str) -> str:
    pat = re.escape(prop)
    m = re.search(rf'<meta[^>]+(?:property|name)="{pat}"[^>]+content="([^"]*)"', page, re.I)
    if not m:
        m = re.search(rf'<meta[^>]+content="([^"]*)"[^>]+(?:property|name)="{pat}"', page, re.I)
    return _wx_unescape(m.group(1)).strip() if m else ""


def _wx_fetch(url: str) -> str:
    req = urllib.request.Request(url, headers={
        "User-Agent": WECHAT_UA,
        "Referer": WECHAT_REFERER,
        "Accept-Language": "zh-CN,zh;q=0.9",
    })
    with urllib.request.urlopen(req, timeout=25) as resp:
        raw = resp.read()
        charset = "utf-8"
        m = re.search(r"charset=([\w-]+)", resp.headers.get("Content-Type") or "", re.I)
        if m:
            charset = m.group(1)
    try:
        return raw.decode(charset, "replace")
    except LookupError:
        return raw.decode("utf-8", "replace")


def _wx_block(s: str, start: int, open_ch: str, close_ch: str) -> str:
    """从 start 处的开括号开始返回配对的整段（跳过字符串里的括号）。"""
    depth, i, quote = 0, start, ""
    while i < len(s):
        ch = s[i]
        if quote:
            if ch == "\\":
                i += 2
                continue
            if ch == quote:
                quote = ""
        elif ch in "'\"":
            quote = ch
        elif ch == open_ch:
            depth += 1
        elif ch == close_ch:
            depth -= 1
            if depth == 0:
                return s[start + 1:i]
        i += 1
    return s[start + 1:]


def _wx_div_inner(page: str) -> str:
    i = page.find('id="js_content"')
    if i < 0:
        return ""
    start = page.find(">", i)
    if start < 0:
        return ""
    rest = page[start + 1:]
    depth = 1
    for m in re.finditer(r"<(/?)div\b[^>]*>", rest, re.I):
        depth += -1 if m.group(1) else 1
        if depth == 0:
            return rest[:m.start()]
    return rest[:300000]


def _wx_html_text(fragment: str) -> str:
    p = _WxTextParser()
    try:
        p.feed(fragment)
        p.close()
        return p.text()
    except Exception:  # noqa: BLE001
        return re.sub(r"<[^>]+>", "", fragment or "").strip()


def _wx_image_url(url: str) -> str:
    """微信图片统一取原图：末段尺寸号换成 0。"""
    u = (url or "").split("#", 1)[0]
    m = re.match(r"^(.*/)\d+(\?.*)?$", u)
    if m and "mmbiz.qpic.cn" in u:
        u = m.group(1) + "0" + (m.group(2) or "")
    return u


def _wx_images(fragment: str) -> list[str]:
    out: list[str] = []
    seen: set = set()
    for m in re.finditer(r"<img\b[^>]*>", fragment or "", re.I):
        tag = m.group(0)
        src = ""
        for attr in ("data-src", "src"):
            mm = re.search(attr + r'\s*=\s*["\']([^"\']+)["\']', tag, re.I)
            if mm:
                src = _wx_unescape(mm.group(1))
                if src:
                    break
        if not src.startswith("http"):
            continue
        src = _wx_image_url(src)
        if src not in seen:
            seen.add(src)
            out.append(src)
    return out


def _wx_video_tiers(page: str) -> list[dict]:
    """文章页 `mp_video_trans_info` 里的视频档位（按清晰度从高到低）。"""
    tiers: list[dict] = []
    seen: set = set()
    pat = (r"format_id:\s*'(\d+)'.*?width:\s*'(\d+)'.*?height:\s*'(\d+)'"
           r".*?filesize:\s*'(\d+)'.*?url:\s*'([^']+)'")
    for m in re.finditer(pat, page, re.S):
        _fid, w, h, size, raw = m.groups()
        url = _wx_unescape(raw)
        if "mpvideo.qpic.cn" not in url or ".mp4?" not in url or url in seen:
            continue
        seen.add(url)
        w, h, size = int(w), int(h), int(size)
        tiers.append({
            "url": url,
            "width": w,
            "height": h,
            "filesize": size,
            "label": f"{h}p · {size / 1024 / 1024:.1f} MB" if size else f"{h}p",
        })
    tiers.sort(key=lambda t: (t["height"], t["filesize"]), reverse=True)
    return tiers


def _wx_picture_items(page: str) -> list[dict]:
    """贴图 / 图文模板：解析 `picture_page_info_list`，优先取「查看原图」地址。"""
    i = page.find("picture_page_info_list")
    if i < 0:
        return []
    j = page.find("[", i)
    if j < 0:
        return []
    block = _wx_block(page, j, "[", "]")
    out: list[dict] = []
    pos = 0
    while pos < len(block):
        k = block.find("{", pos)
        if k < 0:
            break
        ent = _wx_block(block, k, "{", "}")
        pos = k + len(ent) + 2

        cdn = _wx_first(ent, r"cdn_url:\s*'([^']+)'")
        mo = re.search(r"original_info:\s*\{[^{}]*?cdn_url:\s*'([^']*)'", ent, re.S)
        orig = _wx_unescape(mo.group(1)) if mo else ""
        url = orig if orig.startswith("http") else cdn
        if not url.startswith("http"):
            continue
        w = _wx_first(ent, r"width:\s*'(\d+)'")
        h = _wx_first(ent, r"height:\s*'(\d+)'")
        out.append({
            "url": url,
            "width": int(w) if w.isdigit() else 0,
            "height": int(h) if h.isdigit() else 0,
            "kind": "image",
        })
    return out


def _wx_article_id(url: str) -> str:
    m = re.search(r"/s/([0-9A-Za-z_-]+)", url)
    if m:
        return "wx_" + m.group(1)
    m = re.search(r"[?&]sn=([0-9a-f]+)", url, re.I)
    if m:
        return "wx_" + m.group(1)
    return "wx_" + hashlib.md5(url.encode("utf-8")).hexdigest()[:16]


def _wx_sph_id(url: str) -> str:
    m = re.search(r"/sph/([0-9A-Za-z_-]+)", url)
    if m:
        return m.group(1)
    m = re.search(r"[?&]id=([0-9A-Za-z_-]+)", url)
    return m.group(1) if m else ""


def _wx_article_record(url: str, page: str) -> tuple[dict, str]:
    inner = _wx_div_inner(page)
    title = _wx_js_var(page, "msg_title") or _wx_meta(page, "og:title") or "公众号文章"
    author = _wx_js_var(page, "nickname") or _wx_first(page, r"nick_name:\s*'([^']*)'")
    body = _wx_html_text(inner)
    images = _wx_images(inner)
    tiers = _wx_video_tiers(page)

    media = [{"url": u, "kind": "image"} for u in images]
    if tiers:
        best = tiers[0]
        media.append({"url": best["url"], "width": best["width"],
                      "height": best["height"], "kind": "video"})

    if not media and not body:
        raise ValueError("这篇公众号里没有取到图片、视频或正文（可能已被删除或需要验证）。")

    ctype = "images" if images else ("video" if tiers else "text")
    rec = {
        "id": _wx_article_id(url),
        "platform": "微信公众号",
        "title": title,
        "uploader": author,
        "uploader_id": "",
        "duration": None,
        "thumbnail": images[0] if images else "",
        "source_url": url,
        "resolved_url": (tiers[0]["url"] if (tiers and not images) else ""),
        "download_url": (tiers[0]["url"] if (tiers and not images) else ""),
        "resolved_at": int(time.time()),
        "play_kind": "progressive" if (tiers and not images) else "none",
        "content_type": ctype,
        "media_json": json.dumps(media, ensure_ascii=False) if media else "",
        "description": body,
        "source_md5": "",
    }
    if images:
        video_count = sum(1 for m in media if m.get("kind") == "video")
        quality = (f"图文 · 共 {len(images)} 张"
                   + (f" + {video_count} 个视频" if video_count else ""))
    elif tiers:
        quality = tiers[0]["label"]
    else:
        quality = "纯文字 · 没有可下载的媒体"
    return rec, quality


def _wx_picture_record(url: str, page: str) -> tuple[dict, str]:
    title = _wx_meta(page, "og:title") or "微信图文"
    author = _wx_first(page, r"nick_name:\s*'([^']*)'")
    body = _wx_html_text(_wx_meta(page, "og:description"))
    media = _wx_picture_items(page)
    if not media:
        raise ValueError("这条图文里没有取到图片（可能已被删除或需要验证）。")

    rec = {
        "id": _wx_article_id(url),
        "platform": "微信公众号",
        "title": title,
        "uploader": author,
        "uploader_id": "",
        "duration": None,
        "thumbnail": media[0]["url"],
        "source_url": url,
        "resolved_url": "",
        "download_url": "",
        "resolved_at": int(time.time()),
        "play_kind": "none",
        "content_type": "images",
        "media_json": json.dumps(media, ensure_ascii=False),
        "description": body,
        "source_md5": "",
    }
    return rec, f"图文 · 共 {len(media)} 张"


# ==================== 图文/图集下载用的小工具 ====================


def _ext_from_url(url: str, default: str) -> str:
    """从地址末段取扩展名；取不到就用 default。"""
    path = urllib.parse.urlparse(url or "").path
    ext = os.path.splitext(path)[1].lower()
    known = (".jpg", ".jpeg", ".png", ".webp", ".gif", ".heic", ".mp4", ".mov")
    return ext if ext in known else default


def _sniff_image_ext(path: str) -> str | None:
    """按文件头判断图片真实扩展名（地址里常常没有扩展名）。"""
    try:
        with open(path, "rb") as fh:
            head = fh.read(16)
    except OSError:
        return None
    if head.startswith(b"\xff\xd8\xff"):
        return ".jpg"
    if head.startswith(b"\x89PNG\r\n\x1a\n"):
        return ".png"
    if head.startswith(b"GIF8"):
        return ".gif"
    if head[:4] == b"RIFF" and head[8:12] == b"WEBP":
        return ".webp"
    if head[4:8] == b"ftyp":
        return ".heic"
    return None


def _sanitize_name(raw: str) -> str:
    """把文案变成能安全当文件名用的样子（Windows 非法字符一并换掉）。"""
    return re.sub(r'[\x00-\x1f\x7f\\/:*?"<>|]+', "_", raw or "").strip(" ._")


def _clip_title(raw: str, budget: int) -> str:
    """把标题裁到 budget 字符以内；超长时先丢掉结尾那串话题标签。"""
    s = _sanitize_name(raw)
    if not s:
        return ""
    if len(s) <= budget:
        return s
    trimmed = re.sub(r"(\s*#[^\s#]+)+\s*$", "", s).strip()
    src = trimmed or s
    if len(src) <= budget:
        return src
    return src[: max(1, budget - 1)].rstrip() + "…"


def _dup_index(folder: str, stem: str, items) -> int:
    """这一批文件名有没有被占用；占了就返回下一个可用编号（0 = 不用加）。"""
    if not os.path.isdir(folder):
        return 0
    taken = {os.path.splitext(x)[0] for x in os.listdir(folder)}
    for n in range(0, 100):
        tag = f" ({n})" if n else ""
        if not any(f"{stem}{i:02d}{tag}" in taken for i, _ in items):
            return n
    return 0


class EngineError(RuntimeError):
    """引擎不可用时抛出，用于把原因回传给界面。"""


# ==================== 报错翻译 ====================
# yt-dlp 的报错是英文且面向开发者，普通用户看不懂。
# 这里把常见原因翻译成「说明 + 该怎么办」，原始报错附在后面便于排查。

ERROR_HINTS = [
    (
        "No video could be found in this tweet",
        "这条推文里没有视频（只有文字或图片），无法解析。",
    ),
    (
        "Fresh cookies",
        "该平台需要「新鲜的 Cookie」才能解析。请重新导出 Cookie；"
        "如果是抖音，还需先关掉 VPN、用国内网络打开该平台后再导出。",
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
    # ---- 下面两条是网络层失败。与安卓端 ytdlp_engine.ERROR_HINTS 对应，
    #      改一处要同步另一处（两端是同一份规格的两个实现）----
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
        # 断网时实测抛这个（TLS 握手被截断）。不补这条的话，用户断网时
        # 只能看到一串英文原文。
        "UNEXPECTED_EOF_WHILE_READING",
        "连不上网络（连接被中断）。请检查：\n"
        "① 电脑是否连着网；\n"
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
        "无法从页面中提取视频信息。可能是该站点改版了，建议先更新引擎（bin/yt-dlp.exe 可自行更新）。",
    ),
]


def friendly_error(raw: str) -> str:
    """把英文报错翻译成中文说明；没匹配到就原样返回。"""
    text = (raw or "").strip()
    for needle, hint in ERROR_HINTS:
        if needle.lower() in text.lower():
            return f"{hint}\n\n原始报错：{text[:240]}"
    return text


# ==================== 引擎与 ffmpeg 定位 ====================


def resolve_engine() -> list[str]:
    """找出可用的 yt-dlp 调用方式，按优先级尝试。"""
    local = BASE_DIR / "bin" / "yt-dlp.exe"
    if local.exists():
        return [str(local)]

    try:
        import yt_dlp  # noqa: F401

        return [sys.executable, "-m", "yt_dlp"]
    except ImportError:
        pass

    exe = shutil.which("yt-dlp")
    return [exe] if exe else []


def resolve_ffmpeg(cfg: dict) -> str:
    """返回 ffmpeg 所在目录（yt-dlp 要的是目录，不是文件）。"""
    candidates = [
        BASE_DIR / "bin" / "ffmpeg",
        Path(cfg.get("engine", {}).get("ffmpeg_dir") or ""),
    ]
    for cand in candidates:
        if cand and (cand / "ffmpeg.exe").exists():
            return str(cand)

    exe = shutil.which("ffmpeg")
    return str(Path(exe).parent) if exe else ""


def subprocess_env() -> dict:
    """强制 yt-dlp 用 UTF-8 输出。

    实测：yt-dlp 默认跟随系统 locale（中文 Windows 上是 GBK，日志里显示 out=gbk），
    而我们从 stdout 读文件名时按 UTF-8 解码，结果文件名里的中文全变成乱码方块。
    """
    env = dict(os.environ)
    env["PYTHONIOENCODING"] = "utf-8"
    env["PYTHONUTF8"] = "1"
    return env


def decode_output(raw: bytes) -> str:
    """解码 yt-dlp 的输出（含中文文件名时必须兜底）。

    实测：yt-dlp.exe（PyInstaller 打包版）在中文 Windows 上把 --print 的内容按 GBK 写出，
    而且不理会 PYTHONIOENCODING / PYTHONUTF8，所以只能读字节后自己判断：
    先按 UTF-8 试，遇到非法字节就按 GBK 解。
    """
    if not raw:
        return ""
    for enc in ("utf-8", "gbk"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            continue
    return raw.decode("utf-8", errors="replace")


def platform_rule(cfg: dict, url: str) -> dict:
    """按域名匹配平台规则（config 里的 platform_rules）。"""
    host = urlparse(url or "").netloc.lower()
    if not host:
        return {}
    for key, rule in (cfg.get("platform_rules") or {}).items():
        key = key.lower()
        if host == key or host.endswith("." + key):
            return rule or {}
    return {}


def build_command(cfg: dict, extra: list[str], url: str = "") -> list[str]:
    args = resolve_engine()
    if not args:
        raise EngineError("找不到 yt-dlp，请把 yt-dlp.exe 放到 bin/ 目录下")

    args += list(cfg.get("always_args") or [])

    js_runtime = (cfg.get("engine") or {}).get("js_runtimes")
    if js_runtime:
        args += ["--js-runtimes", js_runtime]

    ffmpeg_dir = resolve_ffmpeg(cfg)
    if ffmpeg_dir:
        args += ["--ffmpeg-location", ffmpeg_dir]

    # Cookie 按平台决定是否带：
    #   实测 X(Twitter) 不带 cookie 会报「No video could be found in this tweet」；
    #   而 YouTube 带 cookie 会把画质从 1080p 降到 360p，所以不能一律带上。
    if url and platform_rule(cfg, url).get("use_cookies"):
        cookie_file = (cfg.get("cookies") or {}).get("file") or ""
        if cookie_file and Path(cookie_file).is_file():
            args += ["--cookies", cookie_file]

    if (cfg.get("download") or {}).get("single_video_no_playlist", True):
        args.append("--no-playlist")

    return args + extra


# ==================== 本地历史存储 ====================


class Store:
    """历史记录用 sqlite 存，零额外依赖。"""

    def __init__(self) -> None:
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        self.conn = sqlite3.connect(DB_FILE, check_same_thread=False)
        self.conn.row_factory = sqlite3.Row
        # check_same_thread=False 只是关掉了检查，不提供线程安全：
        # HTTP 服务是多线程的，两个请求同时写库可能撞车（database is locked）。
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
                local_path   TEXT,
                content_type TEXT,
                media_json   TEXT,
                source_md5   TEXT,
                file_md5     TEXT,
                description  TEXT
            )
            """
        )
        self._migrate()
        self.conn.commit()

    def _migrate(self) -> None:
        cols = {row["name"] for row in self.conn.execute("PRAGMA table_info(history)")}
        if "play_kind" not in cols:
            self.conn.execute("ALTER TABLE history ADD COLUMN play_kind TEXT")
        if "uploader_id" not in cols:
            self.conn.execute("ALTER TABLE history ADD COLUMN uploader_id TEXT")
        # 下载专用地址：抖音那条链路里，「画质最高」的档位可能是 H.265（浏览器播不了），
        # 所以把它单独存下来供下载用，resolved_url 保留能直接播放的那条。
        if "download_url" not in cols:
            self.conn.execute("ALTER TABLE history ADD COLUMN download_url TEXT")
        # 以下 6 列与安卓端 ytdlp_engine.Store 对齐（改一处要同步另一处）：
        #   local_path   下载产物的落地路径。有它历史页才能直接播本地文件 ——
        #                B站/YouTube 全是分离流，下载下来才是唯一能看的方式。
        #   content_type video / images / live
        #   media_json   图文/图集的媒体列表（多条记录塞不进单个 resolved_url）
        #   source_md5   平台自报的 MD5（抖音每个档位都带 file_hash）
        #   file_md5     成品实际算出来的 MD5。只在下载收尾回填、**不进 upsert**
        #   description  正文，只存纯文本
        for name in ("local_path", "content_type", "media_json",
                     "source_md5", "file_md5", "description"):
            if name not in cols:
                self.conn.execute(f"ALTER TABLE history ADD COLUMN {name} TEXT")

    def upsert(self, rec: dict) -> None:
        # 标题在这里统一收口：各平台拿到的是原文（抖音带话题、微博是整段正文），
        # 限长与「没标题就拿正文顶上」的规则只写一份。就地改 rec，
        # 这样返回给界面的也是同一个短标题，历史库与解析结果不会不一致。
        rec["title"] = _norm_title(rec.get("title"), rec.get("description") or "")
        # 图文类没有 resolved_url / download_url / play_kind，这里补默认值，
        # 免得每条调用点都要自己填一遍。
        row = {
            "resolved_url": "",
            "download_url": "",
            "play_kind": "none",
            "content_type": "video",
            "media_json": "",
            "source_md5": "",
            "description": "",
            **rec,
        }
        with self._write_lock:
            self.conn.execute(
                """
                INSERT INTO history (id, platform, title, uploader, uploader_id, duration,
                                     thumbnail, source_url, resolved_url, download_url,
                                     resolved_at, play_kind, content_type, media_json,
                                     source_md5, description)
                VALUES (:id, :platform, :title, :uploader, :uploader_id, :duration,
                        :thumbnail, :source_url, :resolved_url, :download_url,
                        :resolved_at, :play_kind, :content_type, :media_json,
                        :source_md5, :description)
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
                    source_md5   = excluded.source_md5,
                    -- 正文「有就更新、没有就保留」：备用链路有时只给标题，
                    -- 不能让空串把上次存下的正文冲掉
                    description  = CASE WHEN excluded.description = ''
                                        THEN history.description
                                        ELSE excluded.description END
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

    # ---- 三个「定向回填」写入（与安卓端 Store 的同名方法一一对应）----

    def set_local_path(self, source_url: str, path: str) -> None:
        """记下某条历史的产物路径。

        按 source_url 而不是下载用的地址：走备用链路时真正下载的是直链，
        而库里存的是用户最初贴的那个分享链接。
        """
        with self._write_lock:
            self.conn.execute(
                "UPDATE history SET local_path = ? WHERE source_url = ?",
                (path, source_url),
            )
            self.conn.commit()

    def set_media_json(self, vid: str, media_json: str) -> None:
        """刷新图文/图集的媒体地址（部分平台的直链会过期，下载前换新的）。"""
        if not vid:
            return
        with self._write_lock:
            self.conn.execute(
                "UPDATE history SET media_json = ? WHERE id = ?", (media_json, vid)
            )
            self.conn.commit()

    def set_video_url(self, vid: str, url: str) -> None:
        """刷新单视频的直链。"""
        if not vid or not url:
            return
        with self._write_lock:
            self.conn.execute(
                "UPDATE history SET resolved_url = ?, download_url = ? WHERE id = ?",
                (url, url, vid),
            )
            self.conn.commit()

    def set_file_md5(self, path: str, md5: str) -> None:
        """按产物路径回填成品 MD5。"""
        if not path or not md5:
            return
        with self._write_lock:
            self.conn.execute(
                "UPDATE history SET file_md5 = ? WHERE local_path = ?", (md5, path)
            )
            self.conn.commit()


def load_user_settings() -> dict:
    if SETTINGS_FILE.exists():
        try:
            return json.loads(SETTINGS_FILE.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError):
            return {}
    return {}


def save_user_settings(data: dict) -> None:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    SETTINGS_FILE.write_text(
        json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8"
    )


# ==================== 事件广播（供 SSE 推送下载进度） ====================


class DownloadState:
    """下载状态快照，供前端轮询。

    本地服务场景下轮询比 SSE 简单得多，也更好排查（Network 面板里能直接看到）。

    字段与安卓端 Download 数据类对齐（`Engine.kt:508-567`）：
    percent / speed / eta / downloaded / total 都是**数值**，前端不再自己解析字符串。
    状态是常驻的：一条任务完成后 done 会一直留着，所以带 seq 让界面分辨
    「刚发生的一次完成」和「早就完成、我又读到一遍」。
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._seq = 0
        self._data: dict = {
            "type": "idle",
            "active": False,
            "percent": None,
            "speed": 0,
            "eta": None,
            "downloaded": 0,
            "total": 0,
            "url": "",
            "path": "",
            "message": "",
            "md5": "",
            "md5_after": "",
            "seq": 0,
        }

    def update(self, **kw) -> None:
        with self._lock:
            self._seq += 1
            self._data = {**self._data, **kw, "seq": self._seq}

    def snapshot(self) -> dict:
        with self._lock:
            return dict(self._data)


class ParseState:
    """解析任务状态（供界面轮询「实时跟进」）。

    为什么不再用一个阻塞 30 秒的 POST：
      1. 界面只能显示「解析中…」，看不到进行到哪一步（引擎 / 抖音接口 / 浏览器兜底）；
      2. 用户想放弃时没有出口 —— 请求还挂在浏览器里，后端还在跑。
    改成任务式后，start_parse 立即返回，界面按 phase/hint 显示阶段，
    取消时后端能直接 kill 子进程（安卓端进程内调用做不到这一点）。
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._seq = 0
        self._data: dict = {
            "state": "idle",     # idle / running / done / failed / cancelled
            "active": False,
            "phase": "",         # starting / engine / douyin / browser / service
            "hint": "",
            "url": "",
            "result": None,
            "error": "",
            "seq": 0,
        }

    def update(self, **kw) -> None:
        with self._lock:
            self._seq += 1
            self._data = {**self._data, **kw, "seq": self._seq}

    def snapshot(self) -> dict:
        with self._lock:
            return dict(self._data)


# ==================== 业务逻辑 ====================


class Engine:
    def __init__(self) -> None:
        self.defaults = json.loads(CONFIG_FILE.read_text(encoding="utf-8"))
        self.store = Store()
        self.dl_state = DownloadState()
        self._dl_lock = threading.Lock()
        self._dl_busy = False
        # 解析任务化：一次只跑一条，取消时直接 kill 子进程
        self.parse_state = ParseState()
        self._parse_lock = threading.Lock()
        self._parse_busy = False
        self._parse_cancel = False
        self._parse_proc: subprocess.Popen | None = None
        # 下载速率：近 4 秒滑动窗口（yt-dlp 与自研直链下载一起用）
        self._speed = SpeedMeter()
        # 允许 /file 预览的临时路径（MD5 页挑中的文件、改写后的成品）
        self._preview_paths: set[str] = set()

    # ---- 配置 ----

    def get_defaults(self) -> dict:
        cfg = json.loads(json.dumps(self.defaults))  # 深拷贝
        user = load_user_settings()
        if user.get("output_dir"):
            cfg["download"]["output_dir"] = user["output_dir"]
        if user.get("cookies_file"):
            cfg.setdefault("cookies", {})["file"] = user["cookies_file"]
        return cfg

    def set_output_dir(self, path: str) -> str:
        user = load_user_settings()
        user["output_dir"] = path
        save_user_settings(user)
        return path

    def set_cookies_file(self, path: str) -> str:
        """设置 cookies.txt 路径（Netscape 格式）。传空字符串表示清除。"""
        user = load_user_settings()
        user["cookies_file"] = (path or "").strip()
        save_user_settings(user)
        return user["cookies_file"]

    # ---- 设置（结构化返回，供界面一次读全）----

    def get_settings(self) -> dict:
        cfg = self.get_defaults()
        user = load_user_settings()
        cookie = (cfg.get("cookies") or {}).get("file") or ""
        ffmpeg = resolve_ffmpeg_exe(cfg)
        return {
            "output_dir": (cfg.get("download") or {}).get("output_dir") or "",
            "cookies_file": cookie,
            "cookies_present": bool(cookie) and os.path.isfile(cookie),
            # 下载时是否自动改写 MD5。默认关：改过的文件与平台自报值对不上，
            # 「改变文件内容」该由用户显式打开。
            "rewrite_md5_on_download": bool(user.get("rewrite_md5_on_download")),
            "ffmpeg_available": bool(ffmpeg),
            "ffmpeg_path": ffmpeg,
        }

    def set_rewrite_md5(self, enabled: bool) -> dict:
        user = load_user_settings()
        user["rewrite_md5_on_download"] = bool(enabled)
        save_user_settings(user)
        return self.get_settings()

    def pick_cookie_file(self) -> str:
        """弹出系统文件选择框挑 cookies.txt（浏览器做不到，交给本地进程）。"""
        try:
            import tkinter
            from tkinter import filedialog

            root = tkinter.Tk()
            root.withdraw()
            root.attributes("-topmost", True)
            chosen = filedialog.askopenfilename(
                title="选择 cookies.txt",
                filetypes=[("Cookie 文件", "*.txt"), ("所有文件", "*.*")],
            )
            root.destroy()
            if chosen:
                self.set_cookies_file(chosen)
            return chosen or ""
        except Exception:
            return ""

    # ---- 历史 ----

    def get_history(self) -> list[dict]:
        """历史列表。产物路径失效（被用户手动删掉）的，如实清成空串。

        与安卓端 Engine.history() 同一套做法（`Engine.kt:179-191`）：
        在这里统一 stat 一次，界面层不必反复碰磁盘，也不会把「已下载」标错。
        """
        rows = self.store.all()
        for row in rows:
            path = (row.get("local_path") or "").strip()
            # 图文/图集的产物是一个目录，所以这里用 exists 而不是 isfile
            if path and not os.path.exists(path):
                row["local_path"] = ""
        return rows

    def delete_history(self, vid: str) -> bool:
        self.store.delete(vid)
        return True

    def clear_history(self, platform: str) -> bool:
        self.store.clear_platform(platform)
        return True

    def clear_all_history(self) -> bool:
        self.store.clear_all()
        return True

    # ---- 解析 ----

    def _cookie_hint(self, url: str, err: str = "") -> str:
        """平台需要 Cookie 却拿不到时，把原因明确告诉用户。

        之前是「文件不存在就静默不加 --cookies」，于是 X 解析失败，
        而报错里完全没提 Cookie —— 用户会往网络问题上找，白绕一圈。
        Cookie 过期尤其容易被误判成「这条推文没有视频」。
        """
        cfg = self.get_defaults()
        if not platform_rule(cfg, url).get("use_cookies"):
            return ""
        path = (cfg.get("cookies") or {}).get("file") or ""
        if not path:
            return "\n提示：该平台需要 Cookie 才能解析，但你还没设置 Cookie 文件（可在「设置」里选择）。"
        if not Path(path).is_file():
            return f"\n提示：已设置的 Cookie 文件找不到了（{path}），请重新导出并设置。"
        # 文件在、仍然失败，多半就是里面的登录态过期了
        low = (err or "").lower()
        if any(k in low for k in ("no video could be found", "not authorized",
                                 "401", "403", "login", "fresh cookies")):
            return (
                "\n提示：Cookie 可能已过期。请重新导出 Cookie 覆盖原文件——"
                "导出前要确保浏览器里处于登录状态。"
            )
        return ""

    def _hint(self, hint: str, phase: str = "") -> None:
        """给正在进行的解析任务更新阶段文案（没有任务在跑时什么都不做）。"""
        if not self._parse_busy or self._parse_cancel:
            return
        if phase:
            self.parse_state.update(hint=hint, phase=phase)
        else:
            self.parse_state.update(hint=hint)

    @staticmethod
    def _kill_tree(proc: subprocess.Popen) -> None:
        """连同子进程一起杀掉（Windows 上必须用 taskkill /T）。

        yt-dlp 的 JS 运行时是**子进程**（node），只 kill 父进程的话，node 会继续
        持有 piped 的 stdout 句柄 —— communicate() 就永远等不到 EOF，解析线程
        会一直挂着（实测：YouTube 解析超时后界面一直停在「正在调用解析引擎…」）。
        """
        try:
            if os.name == "nt":
                subprocess.run(
                    ["taskkill", "/T", "/F", "/PID", str(proc.pid)],
                    capture_output=True, timeout=10,
                )
            else:
                proc.kill()
        except Exception:  # noqa: BLE001
            try:
                proc.kill()
            except Exception:  # noqa: BLE001
                pass

    def _run_engine(self, cmd: list[str]) -> tuple[int | None, bytes, bytes]:
        """跑一次 yt-dlp 解析，返回 (退出码, stdout, stderr)；退出码 None 表示超时。

        用 Popen 而不是 subprocess.run：后者拿不到进程句柄，用户点「取消」时
        只能干等（安卓端进程内调用更是连 kill 都做不到）。
        """
        proc = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=subprocess_env(),
        )
        self._parse_proc = proc
        try:
            out, err = proc.communicate(timeout=PARSE_TIMEOUT)
        except subprocess.TimeoutExpired:
            self._kill_tree(proc)
            try:
                out, err = proc.communicate(timeout=10)
            except subprocess.TimeoutExpired:
                # 仍有孙进程握着管道时不强求读到输出，如实按超时返回
                out, err = b"", b""
            return None, out or b"", err or b""
        finally:
            self._parse_proc = None
        return proc.returncode, out or b"", err or b""

    def parse_url(self, url: str) -> dict:
        """解析链接，返回元数据并写入历史（不下载文件）。"""
        url = (url or "").strip()
        if not url:
            return {"ok": False, "error": "链接为空"}
        if self._parse_cancel:
            return {"ok": False, "error": "已取消"}
        # 压根不像链接的输入在这儿就挡掉，给一句人话
        if not looks_like_url(url):
            return {
                "ok": False,
                "error": "这看起来不是一条链接。\n"
                         "请粘贴完整的分享链接（要以 http:// 或 https:// 开头）—— "
                         "就是作品页点「复制链接」得到的那一段。",
            }

        platform = detect_platform(url)

        # 抖音直接走自己的链路，不再先跑 yt-dlp：它要 a_bogus 签名 + 登录态，
        # yt-dlp 必然 403，先跑纯粹是白等。
        if platform == "抖音":
            self._hint("正在直接读取抖音作品数据…", "douyin")
            return self._parse_douyin(url)

        # 快手：移动版分享页直出数据，纯 HTTP（不需要登录 / 签名）。
        # yt-dlp 没有快手提取器，所以也不能先跑它。
        if platform == "快手":
            self._hint("正在间接读取快手作品数据…", "kuaishou")
            return self._parse_kuaishou(url)

        # 微信公众号 / 视频号都是公开网页，纯 HTTP 读取。
        if platform == "微信公众号":
            self._hint("正在读取公众号页面…", "wechat")
            return self._parse_wechat(url)
        if platform == "微信视频号":
            self._hint("正在读取视频号信息…", "channels")
            return self._parse_wechat_channels(url)

        # 小红书：直接交给浏览器（不再先跑 yt-dlp）。带着用户导出的 Cookie 去打
        # 小红书会让账号被判成自动化；浏览器链路本来也全覆盖（视频/图文/图集/实况）。
        if platform == "小红书":
            self._hint("正在用浏览器读取小红书笔记…", "xhs")
            return self._parse_xhs(url)

        # 微博头条文章与正文页：先走浏览器读权威数据（原因见 _WEIBO_POST_PAGE）。
        # 视频页（video.weibo.com / tv/show）仍走 yt-dlp，不受影响。
        if platform == "微博" and (
            "ttarticle" in url or "card.weibo.com/article" in url
            or (not _WEIBO_VIDEO_PAGE.search(url) and _WEIBO_POST_PAGE.search(url))
        ):
            self._hint("正在用浏览器读取这条微博…", "weibo")
            return self._parse_weibo(url)

        # X 反过来：**先**走第三方链路，yt-dlp 退居兜底。FxTwitter 一次能把
        # 整条推文的媒体全拿到（多图 / 多视频 / 图文混排），yt-dlp 只能拿一个。
        x_err = ""
        if platform == "X":
            self._hint("正在用第三方服务解析…", "service")
            result = self._parse_x(url)
            if result.get("ok") or result.get("fatal"):
                return result
            x_err = result.get("error") or ""

        try:
            cmd = build_command(self.get_defaults(), ["-J", "-f", "bv*+ba/b", url], url)
        except EngineError as exc:
            return {"ok": False, "error": str(exc)}

        self._hint("正在调用解析引擎…", "engine")
        try:
            returncode, out, err = self._run_engine(cmd)
        except OSError as exc:
            return {"ok": False, "error": f"无法启动 yt-dlp：{exc}"}

        if returncode is None:
            return {
                "ok": False,
                "error": f"解析超时（{PARSE_TIMEOUT} 秒）。境外站点（YouTube/Instagram/X）需要开着 VPN。",
            }

        stdout = decode_output(out)
        stderr = decode_output(err)

        if returncode != 0 or not stdout.strip():
            lines = [ln for ln in stderr.splitlines() if ln.strip()]
            ytdlp_err = lines[-1] if lines else "yt-dlp 未返回数据"
            if platform == "X":
                tip = friendly_error(ytdlp_err) + self._cookie_hint(url, ytdlp_err)
                return {"ok": False, "error": (x_err + "\n" if x_err else "") + tip}
            return {
                "ok": False,
                "error": friendly_error(ytdlp_err) + self._cookie_hint(url, ytdlp_err),
            }

        try:
            info = json.loads(stdout)
        except json.JSONDecodeError:
            return {"ok": False, "error": "解析结果不是有效 JSON"}

        rec = self._to_record(info, url)
        if not rec["id"]:
            return {"ok": False, "error": "未获取到视频 ID，解析结果不完整"}
        # 档位全集：DASH 平台（YouTube / B站）靠它选流；直链平台不经过这里。
        rec["tiers"] = ytdlp_collect_tiers(info)

        self.store.upsert(rec)

        # 规格行与档位选择器必须给同一个数字：yt-dlp 自己挑的那条流未必是
        # tiers[0]，两个数字打架会让人以为算错了。有档位时统一以默认档为准。
        default_tier = rec["tiers"][0] if rec["tiers"] else None
        return {
            "ok": True,
            # 补上历史库里已记录的本地产物路径：先下过再解析同一条时，
            # 界面要据此直接播本地文件，而不是再提示「需下载才能播放」
            **self._with_local_path(rec),
            "quality": default_tier["label"] if default_tier else self._describe_quality(info),
            "filesize": "" if default_tier else self._describe_size(info),
        }

    # ---- 解析任务（界面轮询用）----

    def start_parse(self, url: str) -> dict:
        """发起一次异步解析，立即返回；结果通过 get_parse_state 轮询。"""
        url = (url or "").strip()
        if not url:
            return {"ok": False, "error": "链接为空"}
        with self._parse_lock:
            if self._parse_busy:
                return {"ok": False, "error": "已有一条解析正在进行，请等它完成或先取消。"}
            self._parse_busy = True
            self._parse_cancel = False

        self.parse_state.update(
            state="running", active=True, phase="starting",
            hint="正在识别链接…", url=url, result=None, error="",
        )
        threading.Thread(target=self._parse_worker, args=(url,), daemon=True).start()
        return {"ok": True}

    def _parse_worker(self, url: str) -> None:
        try:
            result = self.parse_url(url)
        except Exception as exc:  # noqa: BLE001
            result = {"ok": False, "error": f"解析异常：{type(exc).__name__}: {exc}"}
        finally:
            if self._parse_cancel:
                self.parse_state.update(
                    state="cancelled", active=False, hint="已取消",
                    result=None, error="已取消",
                )
            elif result.get("ok"):
                self.parse_state.update(
                    state="done", active=False, phase="done",
                    hint="解析完成", result=result, error="",
                )
            else:
                self.parse_state.update(
                    state="failed", active=False, phase="failed",
                    hint="解析失败", result=None,
                    error=result.get("error") or "解析失败",
                )
            with self._parse_lock:
                self._parse_busy = False
                self._parse_cancel = False

    def get_parse_state(self) -> dict:
        return self.parse_state.snapshot()

    def cancel_parse(self) -> dict:
        """取消当前解析：能 kill 子进程就 kill，浏览器链路则在阶段之间生效。"""
        with self._parse_lock:
            busy = self._parse_busy
            self._parse_cancel = True
        proc = self._parse_proc
        if proc is not None and proc.poll() is None:
            self._kill_tree(proc)
        if busy:
            self.parse_state.update(
                state="cancelled", active=False, hint="已取消", error="已取消"
            )
        return {"ok": True}

    # ---- 下载 ----

    def _parse_douyin(self, url: str, ytdlp_err: str = "") -> dict:
        """抖音：优先直接调公开接口；接口不可用才降级到浏览器模式。

        与安卓端 ytdlp_engine._parse_douyin 同构 —— 改一处要同步另一处。
        """
        try:
            from douyin_fallback import resolve_via_api

            data = resolve_via_api(url)
        except Exception as exc:  # noqa: BLE001
            api_err = f"{type(exc).__name__}: {exc}"
            print(f"[douyin] 接口方案失败，降级到浏览器模式：{api_err}")
            self._hint("抖音接口没取到数据，正在用浏览器模式重试…", "browser")
            return self._parse_douyin_via_browser(url, ytdlp_err, api_err)

        return self._douyin_result(data, url)

    def _douyin_result(self, data: dict, url: str) -> dict:
        """抖音两条路径（公开接口 / 浏览器监听）共用的入库与返回逻辑。

        先看 images：图文 / 图集 / 实况图的 video 只是 BGM 占位
        （duration=0、地址是音频），按视频处理会「黑屏、有声、0 秒」。
        """
        images = data.get("images") or []
        media: list[dict] = []
        for img in images:
            src = _douyin_image_url(img)
            if not src:
                continue
            media.append({
                "url": src,
                "width": int(img.get("width") or 0),
                "height": int(img.get("height") or 0),
                "live": _douyin_live_url(img),
                "kind": "image",
            })

        if media:
            ctype = "live" if any(m["live"] for m in media) else "images"
            desc = data.get("title") or ""
            rec = {
                "id": data.get("id") or url,
                "platform": "抖音",
                "title": desc,
                "description": _norm_description(desc, desc),
                "uploader": data.get("uploader") or "",
                "uploader_id": data.get("uploader_id") or "",
                "duration": None,
                "thumbnail": media[0]["url"],
                "source_url": url,
                "resolved_url": "",
                "download_url": "",
                "resolved_at": int(time.time()),
                "play_kind": "none",
                "content_type": ctype,
                "media_json": json.dumps(media, ensure_ascii=False),
                "source_md5": "",
            }
            self.store.upsert(rec)
            quality = (f"实况图 · 共 {len(media)} 张" if ctype == "live"
                       else f"图文 · 共 {len(media)} 张")
            return {
                "ok": True,
                **self._with_local_path(rec),
                "quality": quality,
                "filesize": "",
            }

        # 视频：按原始 video 对象算档位全集（第一项即默认选中项）
        video = data.get("video") or {}
        tiers = douyin_collect_tiers(video)
        play_url = data.get("play_url") or ""
        download_url = data.get("download_url") or ""
        if not play_url:
            play_url, download_url = douyin_pick_tiers(video)
        if not play_url:
            return {"ok": False, "error": "接口返回里没有可用的视频地址"}

        # 「当前播放的是多少分辨率」按播放档那条反查档位表，不能直接用 tiers[0]
        quality = next((t["label"] for t in tiers if t["url"] == play_url),
                       tiers[0]["label"] if tiers else "原始画质（无水印）")
        desc = data.get("title") or ""

        rec = {
            "id": data.get("id") or url,
            "platform": "抖音",
            "title": desc,
            "description": _norm_description(desc, desc),
            "uploader": data.get("uploader") or "",
            "uploader_id": data.get("uploader_id") or "",
            "duration": data.get("duration"),
            "thumbnail": data.get("thumbnail") or "",
            "source_url": url,
            "resolved_url": play_url,
            "download_url": download_url,
            "resolved_at": int(time.time()),
            "play_kind": "progressive",
            "content_type": "video",
            # 档位全集交给界面做规格选择；它不进库，只随解析结果返回
            "tiers": tiers,
            # 平台自报的 MD5：取默认下载档（第一档）那条
            "source_md5": (tiers[0].get("hash") or "") if tiers else "",
        }
        self.store.upsert(rec)
        return {
            "ok": True,
            **self._with_local_path(rec),
            "quality": quality,
            "filesize": "",
        }

    def _parse_douyin_via_browser(self, url: str, ytdlp_err: str, api_err: str = "") -> dict:
        """抖音兜底链路：用真实浏览器监听接口响应，拿无水印直链。

        只有公开接口失败时才会走到这里（见 _parse_douyin）。
        """
        try:
            from douyin_fallback import DouyinFallbackError, resolve

            data = resolve(url)
        except DouyinFallbackError as exc:
            api_line = f"接口方式：{api_err}\n" if api_err else ""
            return {
                "ok": False,
                "error": (
                    f"抖音解析失败，两种方式都没成功。\n"
                    f"{api_line}"
                    f"浏览器模式：{exc}\n"
                    f"（yt-dlp 的错误：{friendly_error(ytdlp_err)}）"
                ),
            }
        except Exception as exc:  # noqa: BLE001
            return {"ok": False, "error": f"抖音浏览器模式异常：{type(exc).__name__}: {exc}"}

        result = self._douyin_result(data, url)
        # 界面据此提示用户「用的是浏览器模式」，解释为什么要多等一会儿
        result["via"] = "browser"
        return result

    def _parse_kuaishou(self, url: str) -> dict:
        """快手：移动版分享页的 SSR 数据，纯 HTTP，不需要登录。

        网页数据里拿不到实况的「动」那一段，所以实况一律按图文处理，
        不假装它是实况 —— 与安卓端同一条边界。
        """
        try:
            final, page = _ks_fetch(url)
        except Exception as exc:  # noqa: BLE001
            return {"ok": False, "error": f"打开快手页面失败：{type(exc).__name__}: {exc}"}

        pid = _ks_photo_id(final) or _ks_photo_id(url)
        # 桌面 UA / 直接贴 www 链接时落到的是 JS 壳页，换移动页再取一次
        if '"photoType"' not in page and pid:
            try:
                final, page = _ks_fetch(KUAISHOU_MOBILE_PAGE.format(pid=pid))
            except Exception as exc:  # noqa: BLE001
                return {"ok": False,
                        "error": f"读取快手作品页失败：{type(exc).__name__}: {exc}"}

        photo = _ks_find_photo(_ks_ssr_json(page) or {})
        if not photo:
            return {
                "ok": False,
                "error": "没从快手页面里读到作品数据。\n"
                         "常见原因：作品已删除 / 仅自己可见，或页面结构变了。",
            }

        ptype = str(photo.get("photoType") or "")
        caption = str(photo.get("caption") or "")
        covers = photo.get("coverUrls") or []
        cover = str((covers[0] or {}).get("url") or "") if covers else ""
        duration_ms = photo.get("duration") or 0

        base = {
            "id": str(photo.get("photoId") or pid or url),
            "platform": "快手",
            "title": caption,
            "description": _norm_description(caption, caption),
            "uploader": photo.get("userName") or "",
            "uploader_id": str(photo.get("userId") or photo.get("kwaiId") or ""),
            "duration": int(duration_ms // 1000) if duration_ms else None,
            "thumbnail": cover,
            "source_url": url,
            "resolved_at": int(time.time()),
            "source_md5": "",
        }

        if ptype == "VIDEO":
            tiers = _ks_video_tiers(photo)
            if not tiers:
                return {"ok": False, "error": "这条快手视频没有可用的播放地址（可能已删除）。"}
            # 播放保守取 ≤720p（兼容性最好），下载取最高档
            play = next((t["url"] for t in tiers if t["height"] and t["height"] <= 720),
                        tiers[-1]["url"])
            download = tiers[0]["url"]
            quality = next((t["label"] for t in tiers if t["url"] == play), tiers[0]["label"])
            rec = {
                **base,
                "resolved_url": play,
                "download_url": download,
                "play_kind": "progressive",
                "content_type": "video",
                "media_json": "",
                "tiers": tiers,
            }
            self.store.upsert(rec)
            return {"ok": True, **self._with_local_path(rec),
                    "quality": quality, "filesize": ""}

        if "ATLAS" in ptype:
            media = _ks_atlas_media(photo)
            if not media:
                return {"ok": False, "error": "这条快手图集里没有取到图片。"}
            rec = {
                **base,
                "resolved_url": "",
                "download_url": "",
                "play_kind": "none",
                "content_type": "images",
                "media_json": json.dumps(media, ensure_ascii=False),
            }
            self.store.upsert(rec)
            return {"ok": True, **self._with_local_path(rec),
                    "quality": f"图文 · 共 {len(media)} 张", "filesize": ""}

        if ptype == "SINGLE_PICTURE":
            if not cover:
                return {"ok": False, "error": "这条快手作品没有取到图片。"}
            media = [{
                "url": cover,
                "width": int(photo.get("width") or 0),
                "height": int(photo.get("height") or 0),
                "live": "",
                "kind": "image",
            }]
            rec = {
                **base,
                "resolved_url": "",
                "download_url": "",
                "play_kind": "none",
                "content_type": "images",
                "media_json": json.dumps(media, ensure_ascii=False),
            }
            self.store.upsert(rec)
            vol = ((photo.get("ext_params") or {}).get("single") or {}).get("volume")
            note = "（该作品含动效，当前仅支持静态图）" if vol else ""
            return {"ok": True, **self._with_local_path(rec),
                    "quality": f"图文 · 共 1 张{note}", "filesize": ""}

        return {
            "ok": False,
            "error": f"暂不支持这种快手内容（平台类型：{ptype or '未知'}）。",
        }

    def _parse_wechat(self, url: str) -> dict:
        """微信公众号：文章模板与「贴图/图文」模板两条分支（纯 HTTP）。"""
        try:
            page = _wx_fetch(url)
        except Exception as exc:  # noqa: BLE001
            return {"ok": False, "error": f"打开微信页面失败：{type(exc).__name__}: {exc}"}

        if not page or len(page) < 500:
            return {"ok": False, "error": "微信页面没有返回有效内容，可能已被删除或需要验证。"}
        if "环境异常" in page:
            return {"ok": False, "error": "微信要求人机验证，暂时取不到这篇内容，请稍后再试。"}

        try:
            # 顺序必须这样：文章页里也内嵌了贴图模板的代码，先判 js_content
            if 'id="js_content"' in page:
                rec, quality = _wx_article_record(url, page)
            elif "picture_page_info_list" in page:
                rec, quality = _wx_picture_record(url, page)
            else:
                return {"ok": False, "error": "没认出这个微信页面的类型（既不是文章也不是图文）。"}
        except ValueError as exc:
            return {"ok": False, "error": str(exc)}

        self.store.upsert(rec)
        return {
            "ok": True,
            **self._with_local_path(rec),
            "quality": quality,
            "filesize": "",
        }

    def _parse_wechat_channels(self, url: str) -> dict:
        """视频号：调分享预览接口取元数据，**不下载视频**（拿不到流）。"""
        sid = _wx_sph_id(url)
        if not sid:
            return {"ok": False, "error": "没能从这条链接里识别出微信视频号作品编号。"}

        page_url = f"https://channels.weixin.qq.com/finder-preview/pages/sph?id={sid}"
        qs = urllib.parse.urlencode({
            "_rid": f"{uuid.uuid4().hex[:8]}-{uuid.uuid4().hex[:8]}",
            "_pageUrl": page_url,
        })
        body = json.dumps({"baseReq": {"generalToken": ""}, "shortUri": sid}).encode("utf-8")
        req = urllib.request.Request(
            WECHAT_CHANNELS_API + "?" + qs, data=body, method="POST",
            headers={
                "User-Agent": WECHAT_UA,
                "Content-Type": "application/json",
                "Accept": "application/json, text/plain, */*",
                "Referer": page_url,
                "Origin": "https://channels.weixin.qq.com",
            },
        )
        try:
            with urllib.request.urlopen(req, timeout=20) as resp:
                data = json.loads(resp.read().decode("utf-8", "replace"))
        except Exception as exc:  # noqa: BLE001
            return {"ok": False, "error": f"读取微信视频号信息失败：{type(exc).__name__}: {exc}"}

        if data.get("errCode") not in (0, None):
            return {"ok": False,
                    "error": f"微信视频号接口返回错误：{data.get('errMsg') or data.get('errCode')}"}

        info = data.get("data") or {}
        feed = info.get("feedInfo") or {}
        author = (info.get("authorInfo") or {}).get("nickname") or ""
        desc = (feed.get("description") or "").strip()
        title = desc.splitlines()[0].strip() if desc else "微信视频号作品"

        rec = {
            "id": "sph_" + sid,
            "platform": "微信视频号",
            "title": title,
            "uploader": author,
            "uploader_id": "",
            "duration": None,
            "thumbnail": feed.get("coverUrl") or "",
            "source_url": url,
            "resolved_url": "",
            "download_url": "",
            "resolved_at": int(time.time()),
            "play_kind": "none",
            # preview：界面据此展示卡片、隐藏下载按钮
            "content_type": "preview",
            "media_json": "",
            "description": desc,
            "source_md5": "",
        }
        self.store.upsert(rec)
        return {
            "ok": True,
            **self._with_local_path(rec),
            "quality": "微信视频号 · 不支持直接下载",
            "filesize": "",
        }

    def _parse_x(self, url: str) -> dict:
        """X 的首选链路：FxTwitter 一次拿全整条推文的媒体。

        图片 / 图集 / 一推多视频 / 图文混排都收进 media 列表，每项带 kind；
        yt-dlp 只能拿到其中一个视频，所以它退居兜底（见 parse_url）。
        """
        try:
            from x_fallback import XFallbackError, XNoMediaError, resolve

            data = resolve(url)
        except XNoMediaError as exc:
            # 「这条推文本就没有媒体」是定论：换链路、补 Cookie 都没用
            return {"ok": False, "fatal": True, "error": f"X 解析失败：{exc}"}
        except XFallbackError as exc:
            return {"ok": False, "error": f"X 解析失败：{exc}"}
        except Exception as exc:  # noqa: BLE001
            return {"ok": False, "error": f"X 解析异常：{type(exc).__name__}: {exc}"}

        media = data.get("media") or []
        ctype = data.get("content_type") or "video"
        title = data.get("title") or ""
        rec = {
            "id": data.get("id") or url,
            "platform": "X",
            "title": title,
            "description": _norm_description(title, title),
            "uploader": data.get("uploader") or "",
            "uploader_id": data.get("uploader_id") or "",
            "duration": data.get("duration"),
            "thumbnail": data.get("thumbnail") or "",
            "source_url": url,
            "resolved_url": data.get("play_url") or "",
            "download_url": data.get("download_url") or "",
            "resolved_at": int(time.time()),
            "play_kind": "progressive" if data.get("play_url") else "none",
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
            **self._with_local_path(rec),
            "quality": quality,
            "filesize": "",
            # 界面据此提示用的是备用通道，便于用户理解为什么偶尔会慢/失败
            "via": "service",
        }

    def _parse_xhs(self, url: str) -> dict:
        """小红书：浏览器读取笔记数据（与安卓端 XhsActivity 同一条链路）。"""
        try:
            import web_fallback

            payload = web_fallback.resolve_xhs(url)
        except web_fallback.WebNeedLogin as exc:
            return {"ok": False, "need_login": True, "error": str(exc)}
        except web_fallback.WebFallbackError as exc:
            return {"ok": False, "error": f"小红书解析失败：{exc}"}
        except Exception as exc:  # noqa: BLE001
            return {"ok": False, "error": f"小红书浏览器模式异常：{type(exc).__name__}: {exc}"}
        return self._webview_note(payload, "小红书")

    def _parse_weibo(self, url: str) -> dict:
        """微博：浏览器读取正文数据；正文里挂视频链接时再转一次。"""
        article = "ttarticle" in url or "card.weibo.com/article" in url
        try:
            import web_fallback

            payload = web_fallback.resolve_weibo(url, article)
        except web_fallback.WebFallbackError as exc:
            return {"ok": False, "error": f"微博解析失败：{exc}"}
        except Exception as exc:  # noqa: BLE001
            return {"ok": False, "error": f"微博浏览器模式异常：{type(exc).__name__}: {exc}"}

        redirect = payload.get("redirect_url")
        if redirect:
            self._hint("正在解析微博正文里的视频链接…", "engine")
            return self.parse_url(redirect)

        return self._webview_note(payload, "微博")

    def _webview_note(self, data: dict, platform: str) -> dict:
        """浏览器取数的统一入库口（小红书 / 微博共用）。

        与安卓端 _save_webview_note 同构。两条链路的差别只有「视频直链从哪来」：
          · 小红书：video_raw 里是一堆候选流，要按「优先带音轨」挑；
          · 微博：video_url 就是页面给的那一条。
        """
        media = data.get("media") or []
        ctype = data.get("content_type") or "video"
        count = len(media)

        # 「合集类」笔记（仅小红书）：平台把它做成了一个视频，但笔记里还带着一整组图。
        # 用户要的是那组图，所以 ≥2 张图就按图集给。
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
            "id": str(data.get("id") or data.get("source_url") or ""),
            "platform": platform,
            "title": data.get("title") or "",
            "description": _norm_description(data.get("description"), data.get("title") or ""),
            "uploader": data.get("uploader") or "",
            "uploader_id": data.get("uploader_id") or "",
            "duration": data.get("duration"),
            "thumbnail": data.get("thumbnail") or "",
            "source_url": data.get("source_url") or "",
            "resolved_url": play_url,
            "download_url": download_url,
            "resolved_at": int(time.time()),
            "play_kind": "progressive" if play_url else "none",
            "content_type": ctype,
            "media_json": json.dumps(media, ensure_ascii=False) if media else "",
            "source_md5": "",
        }
        self.store.upsert(rec)
        if ctype == "images":
            quality = f"图文 · 共 {count} 张"
        elif ctype == "live":
            quality = f"实况图 · 共 {count} 张"
        else:
            quality = ("原始画质（浏览器取数）" if has_audio
                       else "原始画质（浏览器取数 · 该条只有画面，无音轨）")
        return {
            "ok": True,
            **self._with_local_path(rec),
            "quality": quality,
            "filesize": "",
        }

    def _with_local_path(self, rec: dict) -> dict:
        """把历史库里已记录的产物路径补进解析结果。

        与安卓端 ytdlp_engine._with_local_path 同构 —— 改一处要同步另一处。

        解析结果原本不带 local_path（它来自 yt-dlp 的 info，而产物路径是下载
        完成后才记进历史的）。于是「先下过某个视频、之后又解析同一条」时，
        界面不知道文件已经存在，仍然提示「需下载后才能播放」。
        这里按 id 优先、source_url 兜底去历史库找一次；文件已被删掉就忽略。
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

    def _download_target(self, url: str, spec: dict | None = None) -> str:
        """决定真正要下载的地址。

        优先级：
          1. 界面选中的档位（spec 里的直链）；
          2. 备用链路已入库的默认档直链（抖音 / 快手 / X / 公众号等）；
          3. 原链接。
        """
        if spec and isinstance(spec, dict) and spec.get("url"):
            return str(spec["url"])
        # 这些平台的分享链接 yt-dlp 解析不了，直链已由各自链路入库
        if detect_platform(url) not in ("抖音", "快手", "X", "小红书", "微博", "微信公众号"):
            return url
        for rec in self.store.all():
            if rec.get("source_url") == url:
                # 备用链路拿到的直链（可能是 H.265，仅供下载）；没有则退回可播放地址
                return rec.get("download_url") or rec.get("resolved_url") or url
        return url

    def start_download(self, url: str, index: int = 0, spec: str = "") -> dict:
        """发起下载。

        @param index 只对图文/图集有意义：0 = 全部，N = 只下第 N 张。
        @param spec  用户选中的档位（JSON 文本：{"url": ...} 或 {"selector": ...}）。
                      空串表示用引擎默认档。
        """
        url = (url or "").strip()
        if not url:
            return {"ok": False, "error": "链接为空"}

        spec_obj = None
        if spec:
            try:
                spec_obj = json.loads(spec) if isinstance(spec, str) else spec
            except json.JSONDecodeError:
                return {"ok": False, "error": "下载规格不是有效 JSON"}

        with self._dl_lock:
            if self._dl_busy:
                return {"ok": False, "error": "已有下载任务正在进行，请等它完成"}
            self._dl_busy = True

        self.dl_state.update(
            type="starting", active=True, url=url, percent=None,
            path="", message="", speed=0, eta=None, downloaded=0, total=0,
            md5="", md5_after="",
        )
        threading.Thread(
            target=self._download_worker, args=(url, index, spec_obj), daemon=True
        ).start()
        return {"ok": True}

    def get_download_state(self) -> dict:
        return self.dl_state.snapshot()

    def get_build(self) -> dict:
        """界面资源指纹（web 目录下文件的最新修改时间）。

        存在意义：排查「代码改了但界面没变」这类问题。
        浏览器里显示的指纹和服务端算出来的不一致，就说明加载的是旧代码。
        """
        web = Path(__file__).resolve().parent / "web"
        latest = 0.0
        for name in ("app.js", "style.css", "index.html"):
            p = web / name
            if p.exists():
                latest = max(latest, p.stat().st_mtime)
        return {"build": hashlib.sha1(str(latest).encode()).hexdigest()[:8],
                "mtime": int(latest)}

    def _direct_stem(self, rec: dict | None) -> str:
        """直链下载用的文件名主干（不含扩展名）。

        走直链时拿不到元数据，%(title)s / %(id)s 只能从 URL 里猜；所以用库里
        存的标题和 id。Windows 路径上限 260 字符，按「目录 + ID + 扩展名」倒推。
        """
        title = ((rec or {}).get("title") or "").strip()
        if not title:
            return ""
        vid = ((rec or {}).get("id") or "").strip()
        suffix = f" [{vid}].mp4" if vid else ".mp4"
        out_dir = str((self.get_defaults().get("download") or {}).get("output_dir") or "")
        reserve = len(out_dir) + len(suffix) + 8
        # 标题里若含 %，会被 yt-dlp 当模板再解析一次，转义成字面量
        safe = _sanitize_name(title).replace("%", "%%")
        safe = safe[: max(24, 250 - reserve)].strip()
        if not safe:
            safe = vid or "download"
        return f"{safe} [{vid}]" if vid else safe

    def _direct_output_name_for(self, rec: dict | None) -> str:
        """按一条记录拼「直链下载」的文件名模板（交给 yt-dlp 填扩展名）。"""
        stem = self._direct_stem(rec)
        return (stem + ".%(ext)s") if stem else ""

    def _direct_output_name(self, direct_url: str) -> str:
        """按 URL 反查历史记录，再拼文件名（只对库里存的默认档有效）。"""
        for rec in self.store.all():
            if direct_url in (rec.get("download_url"), rec.get("resolved_url")):
                return self._direct_output_name_for(rec)
        return ""

    def _direct_output_path(self, target: str, rec: dict | None = None) -> str:
        """直链下载的落盘路径；拼不出文件名时返回空串。

        重名处理与图片那套一致：只在文件名后加 (1)/(2)…，不覆盖上一份。
        """
        out_dir = str((self.get_defaults().get("download") or {}).get("output_dir") or "")
        if not rec:
            rec = next(
                (r for r in self.store.all()
                 if target in (r.get("download_url"), r.get("resolved_url"))),
                None,
            )
        stem = self._direct_stem(rec)
        if not stem or not out_dir:
            return ""
        for n in range(100):
            tag = f" ({n})" if n else ""
            cand = os.path.join(out_dir, f"{stem}{tag}.mp4")
            if not os.path.exists(cand):
                return cand
        return os.path.join(out_dir, f"{stem}.mp4")

    def _direct_report(self, got: int, total: int, started: float, url: str = "") -> None:
        """把「已下多少」与**当前**速率写进下载状态（4 秒滑动窗口）。"""
        speed = self._speed.feed(got, started)
        self.dl_state.update(
            type="progress", active=True, url=url,
            percent=round(got / total * 100, 1) if total else None,
            downloaded=got, total=total, speed=speed,
            eta=int((total - got) / speed) if total and speed else None,
        )

    def _download_direct(self, target: str, path: str, url: str = "") -> None:
        """直链下载：HTTP Range 分块并行。

        yt-dlp 对「整文件直链」只走单连接，concurrent_fragment_downloads
        完全不起作用 —— 分块并行是这条路上唯一能真正提速的口子。
        服务端忽略 Range 或给不出总长度时退回单连接。
        """
        self._speed.reset()
        headers = {"User-Agent": BROWSER_UA}
        if "douyinvod.com" in target:
            headers["Referer"] = "https://www.douyin.com/"
        elif "mpvideo.qpic.cn" in target:
            headers["Referer"] = WECHAT_REFERER
        eng = self.get_defaults().get("engine") or {}
        timeout = int(eng.get("socket_timeout") or 20)
        workers = max(1, int(eng.get("direct_threads") or 6))

        try:
            # 公众号视频的鉴权是短时一次性的：多一次请求就多一次踩 403 的机会
            if "mpvideo.qpic.cn" in target:
                self._stream_get(target, path, headers, timeout, url)
            else:
                total, ranged = self._probe_range(target, headers, timeout)
                if ranged and total > 0 and workers > 1:
                    self._ranged_get(target, path, total, headers, timeout, workers, url)
                else:
                    print(f"[engine] 直链不支持分块（总长度 {total}），改用单连接")
                    self._stream_get(target, path, headers, timeout, url)
        except Exception:
            # 失败不留半截文件：否则下次下载会撞进重名逻辑
            try:
                os.remove(path)
            except OSError:
                pass
            raise

    @staticmethod
    def _probe_range(target: str, headers: dict, timeout: int) -> tuple[int, bool]:
        """用带 Range 的 GET 问总长度与是否支持分块。"""
        req = urllib.request.Request(target, headers={**headers, "Range": "bytes=0-0"})
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                if resp.status != 206:
                    return 0, False
                m = re.match(r"bytes\s+\d+-\d+/(\d+)",
                             resp.headers.get("Content-Range") or "")
                return (int(m.group(1)) if m else 0), True
        except Exception as exc:  # noqa: BLE001
            print(f"[engine] Range 探测失败，改用单连接：{exc}")
            return 0, False

    def _stream_get(self, target: str, path: str, headers: dict,
                    timeout: int, url: str = "") -> None:
        """单连接整文件下载（不支持分块时的回退）。"""
        req = urllib.request.Request(target, headers=headers)
        started = time.time()
        got = 0
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            length = resp.headers.get("Content-Length") or ""
            total = int(length) if length.isdigit() else 0
            with open(path, "wb") as fh:
                while True:
                    buf = resp.read(_DIRECT_READ)
                    if not buf:
                        break
                    fh.write(buf)
                    got += len(buf)
                    self._direct_report(got, total, started, url)

    def _ranged_get(self, target: str, path: str, total: int, headers: dict,
                    timeout: int, workers: int, url: str = "") -> None:
        """把 total 个字节切成 workers 块，各线程按自己的偏移并行写。"""
        # 先按总长度撑开：不预分配的话，先写完的线程会把文件截断
        with open(path, "wb") as fh:
            fh.truncate(total)

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
                        raise RuntimeError("服务端未按 Range 返回（期望 206）")
                    # 每个线程开自己的句柄：共用文件对象的 seek/write 会互相穿插
                    fh = open(path, "r+b")
                    try:
                        fh.seek(s)
                        pos = s
                        while pos <= e:
                            buf = resp.read(min(_DIRECT_READ, e - pos + 1))
                            if not buf:
                                break
                            fh.write(buf)
                            pos += len(buf)
                            with lock:
                                state["got"] += len(buf)
                                got = state["got"]
                            self._direct_report(got, total, started, url)
                    finally:
                        fh.close()
            except Exception as exc:  # noqa: BLE001
                with lock:
                    if state["err"] is None:
                        state["err"] = exc
                abort.set()

        threads = [threading.Thread(target=worker, args=(sp,), daemon=True) for sp in spans]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        if state["err"] is not None:
            raise state["err"]
        if state["got"] != total:
            raise RuntimeError(f"分块下载不完整：{state['got']}/{total}")

    def build_download_command(self, cfg: dict, url: str) -> list[str]:
        """组装下载命令。抽出来是为了能被冒烟测试单独检查，不必真的下载。"""
        dl = cfg.get("download") or {}
        eng = cfg.get("engine") or {}
        template = dl.get("filename_template") or "%(title)s [%(id)s].%(ext)s"
        # 备用链路拿到的是裸直链，没有元数据，模板会退化成 URL 片段当文件名
        if any(host in url for host in DIRECT_URL_HOSTS):
            template = self._direct_output_name(url) or template
        extra = [
            "-P", dl.get("output_dir") or "",
            "-o", template,
            # --print 会隐式开启静默模式并吃掉进度输出，必须显式加 --progress
            "--progress",
            "--progress-template",
            eng.get("progress_template") or PROGRESS_TEMPLATE,
            "--progress-delta", str(eng.get("progress_delta") or 1),
            # 合并等后处理完成后输出最终文件路径（官方推荐做法）
            "--print", f"after_move:{DONE_MARK}%(filepath)s",
        ]
        # 抖音直链来自浏览器监听，其 CDN 会校验 Referer，不带会被拒
        if "douyinvod.com" in url:
            extra += ["--add-header", "Referer:https://www.douyin.com/"]
        extra.append(url)
        return build_command(cfg, extra, url)

    def build_download_command_with_tuning(self, cfg: dict, url: str) -> list[str]:
        """在下载命令基础上补上速度相关参数。

        单独一层是为了让「速度调优」集中在一处，便于后面继续加（例如外部下载器）。
        """
        cmd = self.build_download_command(cfg, url)
        fragments = (cfg.get("engine") or {}).get("concurrent_fragments")
        if fragments and int(fragments) > 1:
            # 插在 URL 之前（URL 是最后一个参数）
            cmd = cmd[:-1] + ["-N", str(int(fragments))] + cmd[-1:]
        return cmd

    def _download_worker(self, url: str, index: int = 0, spec: dict | None = None) -> None:
        """下载线程：图文/图集走图片链路；其余交给 yt-dlp 并转成结构化状态。"""
        # 图文 / 图集 / 实况图：没有音视频可合流，就是一组地址，直接取回
        rec = next((r for r in self.store.all() if r.get("source_url") == url), None)
        if rec and (rec.get("content_type") in ("images", "live") or rec.get("media_json")):
            try:
                self._download_media_list(url, rec, index)
            except Exception as exc:  # noqa: BLE001
                self.dl_state.update(
                    type="error", active=True, url=url,
                    message=f"图片下载失败：{type(exc).__name__}: {exc}",
                )
            finally:
                self._dl_finish()
            return

        target = url
        final_path = ""
        error = ""
        try:
            target = self._download_target(url, spec)
        except EngineError as exc:
            self.dl_state.update(type="error", active=True, message=str(exc))
            self._dl_finish()
            return

        # 直链（抖音 / 快手 / X / 小红书 / 公众号视频）：自己用 Range 分块并行下。
        # 交给 yt-dlp 只会走单连接整文件拉，分块是这条路上唯一的提速手段。
        # 拼不出文件名（库里没有这条记录）时退回 yt-dlp，行为与以前一致。
        if any(host in target for host in DIRECT_URL_HOSTS):
            direct_path = self._direct_output_path(target, rec)
            if direct_path:
                try:
                    self._download_direct(target, direct_path, url)
                    self._finish_download(url, direct_path)
                except Exception as exc:  # noqa: BLE001
                    self.dl_state.update(
                        type="error", active=True, url=url,
                        message=f"直链下载失败：{type(exc).__name__}: {exc}",
                    )
                finally:
                    self.dl_state.update(active=False, url=url)
                    self._dl_finish()
                return

        try:
            cmd = self.build_download_command_with_tuning(self.get_defaults(), target)
            # yt-dlp 型档位：把用户选的规格翻译成格式选择表达式
            selector = (spec or {}).get("selector")
            if selector:
                cmd = cmd[:-1] + ["-f", str(selector)] + cmd[-1:]
            # 界面选了直链型档位时，那条地址不在库里 → 文件名会退化成 URL 片段。
            # 这种情况下用这条记录（按 source_url 已查好）来拼。
            if (spec or {}).get("url") and rec:
                custom = self._direct_output_name_for(rec)
                if custom and "-o" in cmd:
                    cmd[cmd.index("-o") + 1] = custom
        except EngineError as exc:
            self.dl_state.update(type="error", active=True, message=str(exc))
            self._dl_finish()
            return

        try:
            proc = subprocess.Popen(
                cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                bufsize=0,
                env=subprocess_env(),
            )
        except OSError as exc:
            self.dl_state.update(
                type="error", active=True, message=f"无法启动 yt-dlp：{exc}"
            )
            self._dl_finish()
            return

        self._speed.reset()
        dl_started = time.time()
        last_got = 0
        try:
            assert proc.stdout is not None
            for raw in proc.stdout:
                line = decode_output(raw).rstrip()
                if not line:
                    continue
                if line.startswith(PROGRESS_MARK):
                    info = parse_progress(line[len(PROGRESS_MARK):])
                    got = info.get("downloaded") or 0
                    # 音视频分轨下载时 downloaded 会从头再来一轮，采样点跟着重置
                    if got < last_got:
                        self._speed.reset()
                    if got:
                        info["speed"] = self._speed.feed(got, dl_started)
                        if info.get("total") and info["speed"]:
                            info["eta"] = int((info["total"] - got) / info["speed"])
                        else:
                            info["eta"] = None
                        last_got = got
                    self.dl_state.update(
                        type="progress", active=True, url=url, **info
                    )
                elif line.startswith(DONE_MARK):
                    final_path = line[len(DONE_MARK):].strip()
                    self.dl_state.update(
                        type="done", active=True, url=url, path=final_path
                    )
                    self._speed.reset()
                elif any(tag in line for tag in
                         ("[Merger]", "[ExtractAudio]", "[VideoConvertor]", "[FixupM3u8]")):
                    # 音视频合流 / 转封装阶段没有百分比可报，界面显示成「合并中」
                    self.dl_state.update(
                        type="processing", active=True, url=url, percent=None
                    )
                    self._speed.reset()
                elif "ERROR:" in line:
                    error = line
                    self.dl_state.update(
                        type="error", active=True, url=url, message=line
                    )
            proc.wait()
        finally:
            if error:
                self.dl_state.update(active=False, url=url)
            elif final_path:
                try:
                    self._finish_download(url, final_path)
                except Exception as exc:  # noqa: BLE001
                    self.dl_state.update(
                        type="error", active=True, url=url,
                        message=f"下载完成但收尾失败：{type(exc).__name__}: {exc}",
                    )
                finally:
                    self.dl_state.update(active=False, url=url)
            else:
                self.dl_state.update(active=False, url=url)
            self._dl_finish()

    def _download_media_list(self, url: str, rec: dict, only_index: int = 0) -> None:
        """图文 / 图集 / 实况图：逐张存进「作者名」的子目录。

        命名与安卓端一致：目录 = 作者名；文件 = 标题_序号；实况图的视频与图片
        **同名**（01.jpg 配 01.mp4）。单张失败不让整条任务断掉，最后如实报数。
        """
        media = json.loads(rec.get("media_json") or "[]")
        if not media:
            self.dl_state.update(type="error", active=False, url=url,
                                 message="这条记录里没有图片地址")
            return

        items = list(enumerate(media, start=1))
        if only_index > 0:
            items = [(i, it) for i, it in items if i == only_index]
            if not items:
                self.dl_state.update(
                    type="error", active=False, url=url,
                    message=f"这条作品里没有第 {only_index} 张，可能内容已变化，重新解析一次。",
                )
                return

        out_dir = str((self.get_defaults().get("download") or {}).get("output_dir") or "")
        if not out_dir:
            self.dl_state.update(type="error", active=False, url=url,
                                 message="还没有设置下载保存路径")
            return
        folder = os.path.join(out_dir, self._dir_name(rec))
        os.makedirs(folder, exist_ok=True)

        prefix = _clip_title(rec.get("title") or "", 60)
        stem = (prefix + "_") if prefix else ""
        dup = _dup_index(folder, stem, items)
        tag = f" ({dup})" if dup else ""

        total = len(items)
        done = 0
        headers = {"User-Agent": BROWSER_UA}
        platform = rec.get("platform")
        if platform == "抖音":
            headers["Referer"] = "https://www.douyin.com/"
        elif platform == "微博":
            headers["Referer"] = "https://weibo.com/"
        elif platform == "快手":
            headers["Referer"] = "https://www.kuaishou.com/"

        for i, item in items:
            src = (item or {}).get("url") or ""
            if not src:
                continue
            kind = (item or {}).get("kind") or "image"
            ext = _ext_from_url(src, ".mp4" if kind == "video" else ".jpg")
            dst = os.path.join(folder, f"{stem}{i:02d}{tag}{ext}")
            item_headers = headers
            if "mpvideo.qpic.cn" in src:
                item_headers = {**headers, "Referer": WECHAT_REFERER}
            try:
                req = urllib.request.Request(src, headers=item_headers)
                with urllib.request.urlopen(req, timeout=30) as resp:
                    with open(dst, "wb") as fh:
                        shutil.copyfileobj(resp, fh)
            except Exception as exc:  # noqa: BLE001
                print(f"[media] 第 {i} 张下载失败：{exc}")
                continue

            # 地址里常常没有扩展名，下完按文件头改回真实扩展名（内容不动）
            if kind != "video":
                real = _sniff_image_ext(dst)
                if real and real != ext.lower():
                    os.replace(dst, os.path.join(folder, f"{stem}{i:02d}{tag}{real}"))

            live = (item or {}).get("live") or ""
            if live:
                try:
                    req = urllib.request.Request(live, headers=item_headers)
                    with urllib.request.urlopen(req, timeout=60) as resp:
                        with open(os.path.join(folder, f"{stem}{i:02d}{tag}.mp4"), "wb") as fh:
                            shutil.copyfileobj(resp, fh)
                except Exception as exc:  # noqa: BLE001
                    print(f"[media] 第 {i} 张的实况视频下载失败：{exc}")

            done += 1
            self.dl_state.update(
                type="progress", active=True, url=url,
                percent=round(done / total * 100, 1),
                downloaded=done, total=total, speed=0, eta=None,
            )

        if done == 0:
            self.dl_state.update(
                type="error", active=False, url=url,
                message="图片一张都没下下来，可能是直链已过期，重新解析一次再试。",
            )
            return

        # 产物是一个目录：历史页据此提供「打开本地文件所在位置」
        self.store.set_local_path(url, folder)
        self.dl_state.update(type="done", active=False, url=url, path=folder,
                             message="", percent=100.0, downloaded=done, total=total)

    @staticmethod
    def _dir_name(rec: dict) -> str:
        """产物目录名：作者名；作者拿不到才退回标题 / id。"""
        base = ((rec.get("uploader") or "").strip()
                or (rec.get("title") or "").strip()
                or str(rec.get("id") or "download"))
        return (_sanitize_name(base) or "download")[:60]

    # ---- MD5 ----

    VIDEO_EXTS = (".mp4", ".mov", ".m4v", ".mkv", ".webm", ".avi", ".flv")

    def allow_preview(self, path: str) -> None:
        """把某个路径加入 /file 的预览白名单（MD5 页挑的文件不在输出目录里）。"""
        if path:
            self._preview_paths.add(os.path.realpath(path))

    def _probe_media(self, path: str) -> dict:
        """用 ffprobe 读宽高与时长；拿不到就返回空字段（不编造）。"""
        ffprobe = resolve_ffprobe_exe(self.get_defaults())
        if not ffprobe:
            return {}
        cmd = [
            ffprobe, "-v", "error", "-select_streams", "v:0",
            "-show_entries", "stream=width,height:format=duration",
            "-of", "json", path,
        ]
        try:
            proc = subprocess.run(cmd, capture_output=True, timeout=30)
            if proc.returncode != 0:
                return {}
            data = json.loads(proc.stdout.decode("utf-8", "replace") or "{}")
        except Exception:  # noqa: BLE001
            return {}
        streams = data.get("streams") or []
        fmt = data.get("format") or {}
        width = int((streams[0] if streams else {}).get("width") or 0)
        height = int((streams[0] if streams else {}).get("height") or 0)
        try:
            duration = float(fmt.get("duration") or 0)
        except (TypeError, ValueError):
            duration = 0.0
        return {"width": width, "height": height,
                "duration": int(duration) if duration else None}

    def media_info(self, path: str) -> dict:
        """一份本机媒体的信息（MD5 / 大小 / 宽高 / 时长 / 类型）。"""
        if not path or not os.path.isfile(path):
            return {"ok": False, "error": "文件不存在，可能已被移动或删除"}
        ext = os.path.splitext(path)[1].lower()
        kind = "video" if ext in self.VIDEO_EXTS else "image"
        try:
            size = os.path.getsize(path)
            md5 = compute_file_md5(path)
        except OSError as exc:
            return {"ok": False, "error": f"读文件失败：{exc}"}
        meta = self._probe_media(path) if kind == "video" else {}
        self.allow_preview(path)
        return {
            "ok": True,
            "path": path,
            "filename": os.path.basename(path),
            "size": size,
            "md5": md5,
            "kind": kind,
            "duration": meta.get("duration"),
            "width": meta.get("width") or 0,
            "height": meta.get("height") or 0,
        }

    def pick_md5_media(self, kind: str = "all") -> dict:
        """弹系统文件选择框挑一份视频/图片（浏览器自己做不到）。"""
        try:
            import tkinter
            from tkinter import filedialog

            video_types = [("视频", "*.mp4 *.mov *.m4v *.mkv *.webm *.avi *.flv")]
            image_types = [("图片", "*.jpg *.jpeg *.png *.webp *.gif *.heic *.bmp")]
            if kind == "video":
                types = video_types + [("所有文件", "*.*")]
            elif kind == "image":
                types = image_types + [("所有文件", "*.*")]
            else:
                types = video_types + image_types + [("所有文件", "*.*")]

            root = tkinter.Tk()
            root.withdraw()
            root.attributes("-topmost", True)
            chosen = filedialog.askopenfilename(title="选择要修改 MD5 的文件",
                                                filetypes=types)
            root.destroy()
        except Exception:  # noqa: BLE001
            return {"ok": False, "error": "无法打开文件选择框"}
        if not chosen:
            return {"ok": False, "error": "没有选择文件"}
        return self.media_info(chosen)

    def md5_edit(self, src: str) -> dict:
        """另存副本式改写：原件全程不动，成品写到应用数据目录的缓存里。"""
        info = self.media_info(src)
        if not info.get("ok"):
            return info
        ext = os.path.splitext(src)[1].lower()
        stem = os.path.splitext(os.path.basename(src))[0]
        cache = DATA_DIR / "md5"
        cache.mkdir(parents=True, exist_ok=True)
        dst = cache / f"{stem}_md5{ext}"
        try:
            if dst.exists():
                dst.unlink()
        except OSError:
            pass
        result = self.rewrite_md5(src, str(dst))
        if not result.get("ok"):
            return result
        after_info = self.media_info(str(dst))
        if not after_info.get("ok"):
            return after_info
        return {
            **result,
            "before_size": info["size"],
            "size": after_info["size"],
            "kind": info["kind"],
            "duration": after_info.get("duration"),
            "width": after_info.get("width") or 0,
            "height": after_info.get("height") or 0,
        }

    def md5_save_as(self, path: str) -> dict:
        """把改写后的成品另存到用户选定的位置（默认输出目录）。"""
        if not path or not os.path.isfile(path):
            return {"ok": False, "error": "成品文件不在了，请重新改写"}
        try:
            import tkinter
            from tkinter import filedialog

            out_dir = str((self.get_defaults().get("download") or {}).get("output_dir") or "")
            root = tkinter.Tk()
            root.withdraw()
            root.attributes("-topmost", True)
            chosen = filedialog.asksaveasfilename(
                title="保存文件",
                initialdir=out_dir or None,
                initialfile=os.path.basename(path),
                defaultextension=os.path.splitext(path)[1],
            )
            root.destroy()
        except Exception:  # noqa: BLE001
            return {"ok": False, "error": "无法打开保存对话框"}
        if not chosen:
            return {"ok": False, "error": "没有选择保存位置"}
        try:
            if os.path.abspath(chosen) == os.path.abspath(path):
                return {"ok": True, "path": chosen}
            shutil.copy2(path, chosen)
        except OSError as exc:
            return {"ok": False, "error": f"保存失败：{exc}"}
        return {"ok": True, "path": chosen}

    def list_downloads(self) -> list[dict]:
        """已下载、且文件还在原地的**文件**记录（MD5 页的次要分区）。"""
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
        """现算某个文件的 MD5（不读库里的缓存值，用户可能自己动过文件）。"""
        if not path or not os.path.isfile(path):
            return {"ok": False, "error": "文件不存在，可能已被移动或删除"}
        try:
            return {"ok": True, "path": path, "md5": compute_file_md5(path),
                    "size": os.path.getsize(path)}
        except OSError as exc:
            return {"ok": False, "error": f"读文件失败：{exc}"}

    def edit_file_md5_to(self, src: str, dst: str) -> dict:
        """另存副本式改写（对外唯一入口）：原件不动，成品写到 dst。"""
        return self.rewrite_md5(src, dst)

    def rewrite_md5(self, path: str, dst: str = "") -> dict:
        """把文件的 MD5 改掉。dst 为空 = 原地改写；dst 非空 = 另存副本。

        视频走 ffmpeg 无损重封装（-c copy + 随机 comment 元数据）；图片走
        「插标准元数据块」（png tEXt / jpg COM / 其余尾部追加）。
        任何一步失败都不留半成品。
        """
        if not path or not os.path.isfile(path):
            return {"ok": False, "error": "文件不存在，可能已被移动或删除"}
        target = dst or path
        stamp = hashlib.md5(os.urandom(16)).hexdigest()[:16]
        try:
            before = compute_file_md5(path)
            if os.path.splitext(target)[1].lower() in IMAGE_EXTS:
                self._rewrite_image_md5(path, target, stamp)
            else:
                self._remux_video_md5(path, target, stamp)
            after = compute_file_md5(target)
        except Exception as exc:  # noqa: BLE001
            return {"ok": False, "error": f"改写失败：{exc}"}
        return {"ok": True, "before": before, "after": after,
                "changed": before != after, "path": target}

    def _remux_video_md5(self, src: str, dst: str, stamp: str) -> None:
        """ffmpeg 无损重封装；dst 允许等于 src（原地改写）。失败抛异常、不留 dst。"""
        ffmpeg = resolve_ffmpeg_exe(self.get_defaults())
        if not ffmpeg:
            raise RuntimeError("没有找到 ffmpeg，改不了视频的 MD5")
        ext = os.path.splitext(dst)[1].lower()
        tmp = f"{dst}.md5tmp{ext}"
        cmd = [
            ffmpeg, "-y", "-hide_banner", "-loglevel", "error",
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
            os.replace(tmp, dst)
        except Exception:
            try:
                os.remove(tmp)
            except OSError:
                pass
            raise

    def _rewrite_image_md5(self, src: str, dst: str, stamp: str) -> None:
        """图片：照抄一份并插入标记；打进规范容器，解码器照常识别。"""
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
        """在 IHDR 之后插一个标准 tEXt 块（IHDR 一定是第一个块）。"""
        head = fin.read(8)
        if head != b"\x89PNG\r\n\x1a\n":
            return False
        len_raw = fin.read(4)
        ctype = fin.read(4)
        if ctype != b"IHDR":
            return False
        ihdr_len = int.from_bytes(len_raw, "big")
        fout.write(head + len_raw + ctype)
        _copy_partial(fin, fout, ihdr_len + 4)  # IHDR 数据 + 它自己的 CRC
        # 块结构：长度只算数据段（不含类型、不含长度自己）；CRC 覆盖「类型+数据」
        payload = b"Comment\x00" + marker
        chunk = b"tEXt" + payload
        fout.write(len(payload).to_bytes(4, "big") + chunk
                   + (zlib.crc32(chunk) & 0xFFFFFFFF).to_bytes(4, "big"))
        shutil.copyfileobj(fin, fout, _DIRECT_READ)
        return True

    @staticmethod
    def _jpeg_insert(fin, fout, marker: bytes) -> bool:
        """在 SOI（有 APP0 则排它后面）之后插一个 COM 注释段。"""
        head = fin.read(2)
        if head != b"\xff\xd8":
            return False
        fout.write(head)
        nxt = fin.read(2)
        app0 = b""
        if len(nxt) == 2 and nxt[0:1] == b"\xff" and nxt[1:2] == b"\xe0":
            seg_len = int.from_bytes(fin.read(2), "big")
            app0 = nxt + seg_len.to_bytes(2, "big") + fin.read(max(seg_len - 2, 0))
        payload = marker[:65533]  # 段长字段只有 2 字节，且含它自己那 2 字节
        fout.write(app0)
        fout.write(b"\xff\xfe" + (len(payload) + 2).to_bytes(2, "big") + payload)
        if not app0:
            fout.write(nxt)
        shutil.copyfileobj(fin, fout, _DIRECT_READ)
        return True

    def _finish_download(self, source_url: str, path: str) -> dict:
        """下载收尾：记产物路径 + 算成品 MD5，再报 done。

        顺序不能换 —— 库里记的 local_path 必须是最终成品，否则历史页播的
        可能是已经被替换掉的旧文件（安卓端踩过这个坑）。
        与安卓端 ytdlp_engine._finish_download 同构。
        """
        if not path or not os.path.isfile(path):
            self.dl_state.update(
                type="error", active=False, url=source_url,
                message="下载结束了，但磁盘上没有找到成品文件。\n"
                        "常见原因：音频和视频是分开给的、合流失败，或选中的档位已失效 —— "
                        "换个档位或重试一次通常就好。",
            )
            return {"path": "", "md5": ""}

        before = ""
        after = ""
        try:
            before = compute_file_md5(path)
            if bool(load_user_settings().get("rewrite_md5_on_download")):
                self.dl_state.update(
                    type="processing", active=True, url=source_url,
                    percent=100.0, message="正在改写 MD5…",
                )
                result = self.rewrite_md5(path)
                if result.get("ok"):
                    after = result.get("after") or ""
                else:
                    # 改写失败不该把整个下载判成失败：文件本身已经下来了
                    print(f"[engine] 改写 MD5 失败：{result.get('error')}")
        except Exception as exc:  # noqa: BLE001
            print(f"[engine] 算 MD5 失败：{exc}")

        self.store.set_local_path(source_url, path)
        # 库里存「最终那个文件」的 MD5：改过就存改后的
        if after or before:
            self.store.set_file_md5(path, after or before)

        self.dl_state.update(
            type="done", active=True, url=source_url, path=path,
            percent=100.0, md5=before, md5_after=after, message="",
        )
        return {"path": path, "md5": after or before}

    def _dl_finish(self) -> None:
        with self._dl_lock:
            self._dl_busy = False

    # ---- 本地文件播放 ----

    def is_playable_path(self, path: str) -> bool:
        """`/file` 的准入判断：只放行输出目录内、或历史库里记过的产物。

        不能放开任意路径 —— 这个接口带 token 但仍是「你给路径、我读文件」，
        等于把整台电脑的文件读取能力挂到网页上。
        """
        if not path:
            return False
        try:
            if not os.path.isfile(path):
                return False
        except OSError:
            return False
        # MD5 页挑中的文件 / 改写后的成品（不在输出目录里，但用户明确选过）
        if os.path.realpath(path) in self._preview_paths:
            return True
        out_dir = str((self.get_defaults().get("download") or {}).get("output_dir") or "")
        if out_dir and path_in_dir(path, out_dir):
            return True
        for row in self.store.all():
            if (row.get("local_path") or "") == path:
                return True
        return False

    # ---- 系统操作 ----

    def open_folder(self, path: str = "") -> bool:
        target = path if path and Path(path).exists() else ""
        try:
            if target and Path(target).is_dir():
                # 图文/图集的产物本身就是一个目录，直接打开它
                subprocess.Popen(["explorer", target])
            elif target:
                subprocess.Popen(["explorer", "/select,", target])
            else:
                folder = (self.get_defaults().get("download") or {}).get("output_dir")
                if folder:
                    subprocess.Popen(["explorer", folder])
            return True
        except OSError:
            return False

    def pick_folder(self) -> str:
        """弹出系统原生文件夹选择框（由本地进程执行，浏览器自己做不到）。"""
        try:
            import tkinter
            from tkinter import filedialog

            root = tkinter.Tk()
            root.withdraw()
            root.attributes("-topmost", True)
            chosen = filedialog.askdirectory(title="选择下载保存位置")
            root.destroy()
            if chosen:
                self.set_output_dir(chosen)
            return chosen or ""
        except Exception:
            return ""

    # ---- 内部工具 ----

    @staticmethod
    def _to_record(info: dict, fallback_url: str) -> dict:
        source = info.get("webpage_url") or fallback_url
        play_url = Engine._pick_playable_url(info)
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
            "play_kind": "progressive" if play_url else "none",
        }

    @staticmethod
    def _pick_playable_url(info: dict) -> str:
        """挑一个能直接放进 <video> 播放的链接。

        实测踩出来的条件：
        - 排除纯音频（vcodec == 'none'）和纯视频（acodec == 'none'）
        - 排除 HLS（m3u8）：浏览器不能直接播
        - **vcodec/acodec 为 null 的不能排除**：X(Twitter) 的渐进式 mp4 就是这种，
          编码信息要下载后才知道，但本身音视频合一，可以直接播
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