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

import json
import os
import re
import shutil
import sqlite3
import threading
import time
import urllib.request
from pathlib import Path
from urllib.parse import urlparse

import yt_dlp

BASE_DIR = Path(__file__).resolve().parent
CONFIG_FILE = BASE_DIR / "defaults.json"

# 直链拿到的是裸地址（没有元数据），文件名要据此改用「库里标题」拼。
# 抖音这几个域名是实测出来的：采样 160 条直链，全部落在 365yg.com / amemv.com 上，
# 一条 douyinvod.com 都没有 —— 只认 douyinvod.com 会让文件名退化成 URL 片段
# （实测会变成「内部路径token [整个查询串].mp4」）。
# 与 Windows 端 engine.DIRECT_URL_HOSTS 对应，改一处要同步另一处。
DIRECT_URL_HOSTS = ("douyinvod.com", "365yg.com", "amemv.com", "video.twimg.com")

PLATFORM_RULES = [
    ("YouTube", r"(^|//)([a-z0-9-]+\.)*youtu\.?be"),
    ("B站", r"(^|//)([a-z0-9-]+\.)*(bilibili\.com|b23\.tv)"),
    ("抖音", r"(^|//)([a-z0-9-]+\.)*(douyin\.com|iesdouyin\.com)"),
    # TikTok 与国内抖音在 yt-dlp 里是两个独立提取器，可用性也完全不同
    # （TikTok 通常能解析，抖音常因境外 IP + 签名拦截失败），所以分开归类。
    ("TikTok", r"(^|//)([a-z0-9-]+\.)*tiktok\.com"),
    ("X", r"(^|//)([a-z0-9-]+\.)*(x\.com|twitter\.com)"),
    ("Instagram", r"(^|//)([a-z0-9-]+\.)*instagram\.com"),
]

# 安卓文件名硬上限是 255 **字节**（ext4 / FUSE 的限制都是按字节算的）。
# 中文一个字 3 字节、emoji 4 字节，所以不能像 Windows 端那样按字符数估算。
MAX_NAME_BYTES = 200


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


def douyin_pick_tiers(video: dict) -> tuple:
    """从 bit_rate 里挑两条地址：能播的最高档、画质最高的档。

    与 Kotlin 侧 DouyinParser.pickTiers 逻辑一致（那边是浏览器模式兜底用的），
    改一处要同步另一处。

    抖音的 video.play_addr 只是「默认档」，bit_rate 数组才是全部档位。
    最高码率档通常是 H.265，浏览器和部分设备放不出来；能播的最高档是
    H.264 + 完整 mp4。所以分开选：play_url 用于播放，download_url 用于下载。
    """
    best_play, best_play_key = "", (-1, -1)
    best_any, best_any_key = "", (-1, -1)

    def better(a: tuple, b: tuple) -> bool:
        return a[0] > b[0] or (a[0] == b[0] and a[1] > b[1])

    for item in video.get("bit_rate") or []:
        play = item.get("play_addr") or {}
        urls = play.get("url_list") or []
        if not urls:
            continue
        url = urls[0]
        if not url:
            continue
        key = (int(play.get("height") or 0), int(item.get("bit_rate") or 0))

        if better(key, best_any_key):
            best_any_key, best_any = key, url

        is_h265 = int(item.get("is_h265") or 0) != 0
        if not is_h265 and str(item.get("format") or "") == "mp4" and better(key, best_play_key):
            best_play_key, best_play = key, url

    # 没有符合的档位就退回默认地址，保证至少有个能用的
    if not best_play:
        urls = ((video.get("play_addr") or {}).get("url_list")) or []
        best_play = urls[0] if urls else ""
    if not best_any:
        best_any = best_play
    return best_play, best_any


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

    def upsert(self, rec: dict) -> None:
        # 图文类没有 resolved_url / download_url / play_kind，这里补默认值，
        # 免得每条调用点都要自己填一遍。
        row = {
            "resolved_url": "", "download_url": "", "play_kind": "none",
            "content_type": "video", "media_json": "",
            **rec,
        }
        with self._write_lock:
            self.conn.execute(
                """
                INSERT INTO history (id, platform, title, uploader, uploader_id, duration,
                                     thumbnail, source_url, resolved_url, download_url,
                                     resolved_at, play_kind, content_type, media_json)
                VALUES (:id, :platform, :title, :uploader, :uploader_id, :duration,
                        :thumbnail, :source_url, :resolved_url, :download_url,
                        :resolved_at, :play_kind, :content_type, :media_json)
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
                    media_json   = excluded.media_json
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
        # 文件在、仍然失败，多半就是里面的登录态过期了
        low = (err or "").lower()
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

        platform = detect_platform(url)

        # 抖音直接走自己的链路，不再先跑一遍 yt-dlp：抖音的接口要 a_bogus 签名
        # 加登录态，HTTP 客户端两样都拿不到，yt-dlp 必然 403 —— 先跑纯粹是白等。
        if platform == "抖音":
            return self._parse_douyin(url)

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
            return {
                "ok": False,
                "error": friendly_error(raw, platform) + self._cookie_hint(url, raw),
            }

        if not info:
            return {"ok": False, "error": "解析失败：没有拿到视频信息"}

        rec = self._to_record(info, url)
        if not rec["id"]:
            return {"ok": False, "error": "未获取到视频 ID，解析结果不完整"}

        self.store.upsert(rec)
        # 补上历史库里已记录的本地产物路径：先下过再解析同一条时，
        # 界面要据此直接播本地文件，而不是再提示「需下载才能播放」
        rec = self._with_local_path(rec)
        return {
            "ok": True,
            **rec,
            "quality": self._describe_quality(info),
            "filesize": self._describe_size(info),
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
            # 画质最高的那条（可能是 H.265，能下不能播）
            "download_url": download_url,
            "resolved_at": int(time.time()),
            # 抖音直链实测支持 Range，可直接内联播放
            "play_kind": "progressive",
        }
        self.store.upsert(rec)
        return {
            "ok": True,
            **self._with_local_path(rec),
            "quality": "原始画质（无水印）",
            "filesize": "",
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
            "resolved_url": data.get("play_url") or "",
            # 画质最高的那条（可能是 H.265，能下不能播）
            "download_url": data.get("download_url") or "",
            "resolved_at": int(time.time()),
            # 抖音直链实测返回 206 + video/mp4，支持 Range，可直接内联播放
            "play_kind": "progressive" if (data.get("play_url") or "") else "none",
            "content_type": ctype,
            # 图文/图集/实况图是**多条**媒体，塞不进 resolved_url，单独存一份 JSON
            "media_json": json.dumps(media, ensure_ascii=False) if media else "",
        }
        self.store.upsert(rec)
        if ctype == "images":
            quality = f"图文 · 共 {count} 张"
        elif ctype == "live":
            quality = f"实况图 · 共 {count} 张"
        else:
            quality = "原始画质（无水印）"
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

    def _download_target(self, url: str) -> str:
        """决定真正要下载的地址。

        抖音/X 的分享链接 yt-dlp 解析不了，但直链已经由备用链路拿到并入库，
        所以下载时直接用那条直链；其他平台保持原样。
        """
        if detect_platform(url) not in ("抖音", "X"):
            return url
        for rec in self.store.all():
            if rec.get("source_url") == url:
                # 备用链路拿到的直链（抖音可能是 H.265，仅供下载）
                return rec.get("download_url") or rec.get("resolved_url") or url
        return url

    def start_download(self, url: str, index: int = 0) -> dict:
        """开始下载。[index] 只对图文/图集有意义：0 = 全部，N = 只下第 N 张。"""
        url = (url or "").strip()
        if not url:
            return {"ok": False, "error": "链接为空"}

        with self._dl_lock:
            if self._dl_busy:
                return {"ok": False, "error": "已有下载任务正在进行，请等它完成"}
            self._dl_busy = True

        # url 是给界面用的：下载状态是全局的（同一时刻只有一个任务），
        # 界面只有知道「这条状态属于哪个视频」，才不会把上一次的产物
        # 拿去播当前这条解析结果。update 是合并写入，所以只在这里给一次。
        self.dl_state.update(type="starting", active=True, percent=None,
                             path="", message="", url=url, index=index)
        threading.Thread(target=self._download_worker, args=(url, index), daemon=True).start()
        return {"ok": True}

    def get_download_state(self) -> dict:
        return self.dl_state.snapshot()

    def _direct_output_name(self, direct_url: str) -> str:
        """给「直链下载」拼一个具体文件名。

        走直链时 yt-dlp 用的是通用提取器，拿不到任何元数据，%(title)s /
        %(id)s 只能从 URL 里猜：抖音会变成一串 a=6383&br=6185 查询参数，
        X 的 video.twimg.com 会变成内部文件名。所以直接用库里存的标题和 id。
        """
        for rec in self.store.all():
            if direct_url in (rec.get("download_url"), rec.get("resolved_url")):
                title = (rec.get("title") or "").strip()
                if not title:
                    return ""
                vid = (rec.get("id") or "").strip()
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
                # 标题里若含 %，会被 yt-dlp 当模板再解析一次，转义成字面量
                safe = safe.replace("%", "%%")
                if not safe:
                    safe = vid or "douyin"
                return f"{safe} [{vid}].%(ext)s" if vid else f"{safe}.%(ext)s"
        return ""

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

    def _download_worker(self, url: str, index: int = 0) -> None:
        logger = _Logger()
        try:
            # 图文 / 图集 / 实况图走单独路径
            rec = next(
                (r for r in self.store.all() if r.get("source_url") == url), None
            )
            if rec and rec.get("media_json") and rec.get("content_type") in ("images", "live"):
                self._download_media_list(url, rec, index)
                return

            target = self._download_target(url)
            opts = self._base_opts(target)
            dl = self.defaults.get("download") or {}
            eng = self.defaults.get("engine") or {}
            template = dl.get("filename_template") or "%(title)s [%(id)s].%(ext)s"
            if any(host in target for host in DIRECT_URL_HOSTS):
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

            # 把产物路径记进历史：历史页据此提供「播放本地文件」——
            # 对 B站/YouTube 这类纯 DASH 站点，这是唯一能看的方式
            if path:
                self.store.set_local_path(url, path)

            self.dl_state.update(type="done", active=False, path=path,
                                 message="", percent=100.0)
        except Exception as exc:  # noqa: BLE001
            raw = logger.last_error or f"{type(exc).__name__}: {exc}"
            self.dl_state.update(type="error", active=False,
                                 message=friendly_error(raw))
        finally:
            with self._dl_lock:
                self._dl_busy = False

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


def parse_url(url: str) -> str:
    return json.dumps(_get().parse_url(url), ensure_ascii=False)


def start_download(url: str, index: int = 0) -> str:
    return json.dumps(_get().start_download(url, index), ensure_ascii=False)


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
