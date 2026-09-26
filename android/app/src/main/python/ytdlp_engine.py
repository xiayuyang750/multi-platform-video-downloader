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
import sqlite3
import threading
import time
from pathlib import Path
from urllib.parse import urlparse

import yt_dlp

BASE_DIR = Path(__file__).resolve().parent
CONFIG_FILE = BASE_DIR / "defaults.json"

# 备用链路拿到的是裸直链（没有元数据），文件名要据此改用「库里标题」拼
DIRECT_URL_HOSTS = ("douyinvod.com", "video.twimg.com")

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


def friendly_error(raw: str) -> str:
    """把英文报错翻译成中文说明。

    两条原则：
      1. 已知原因 → 给出「说人话的原因 + 该怎么办」；
      2. 未知原因 → 也要给一句中文兜底，而不是把 yt-dlp 的英文原样丢给用户。
         实测遇到的绝大多数失败都落在三种情况里（要登录 / 地区限制或已删除 /
         站点改版），把它们列出来，用户至少知道该往哪个方向试。
    """
    text = (raw or "").strip()
    if not text:
        return "解析失败，但没有拿到具体原因。可以打开「引擎自检页」看看细节。"
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

    def upsert(self, rec: dict) -> None:
        with self._write_lock:
            self.conn.execute(
                """
                INSERT INTO history (id, platform, title, uploader, uploader_id, duration,
                                     thumbnail, source_url, resolved_url, download_url,
                                     resolved_at, play_kind)
                VALUES (:id, :platform, :title, :uploader, :uploader_id, :duration,
                        :thumbnail, :source_url, :resolved_url, :download_url,
                        :resolved_at, :play_kind)
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
                    play_kind    = excluded.play_kind
                """,
                rec,
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

        opts = self._base_opts(url)
        opts["format"] = "bv*+ba/b"
        logger = _Logger()
        opts["logger"] = logger

        try:
            with _YDL(opts) as ydl:
                info = ydl.extract_info(url, download=False)
        except Exception as exc:  # noqa: BLE001
            raw = logger.last_error or f"{type(exc).__name__}: {exc}"
            platform = detect_platform(url)
            # 抖音走不了 yt-dlp：它需要 a_bogus 签名和登录态，HTTP 客户端
            # 两样都拿不到，所以必然 403。改走备用链路（见下方说明）。
            if platform == "抖音":
                return self._parse_douyin(url, raw)
            # X 也留一条备用链路：yt-dlp 的 X 提取器依赖官方接口，
            # 改版或限流时会失效
            if platform == "X":
                return self._parse_x(url, raw)
            return {
                "ok": False,
                "error": friendly_error(raw) + self._cookie_hint(url, raw),
            }

        if not info:
            return {"ok": False, "error": "解析失败：没有拿到视频信息"}

        rec = self._to_record(info, url)
        if not rec["id"]:
            return {"ok": False, "error": "未获取到视频 ID，解析结果不完整"}

        self.store.upsert(rec)
        return {
            "ok": True,
            **rec,
            "quality": self._describe_quality(info),
            "filesize": self._describe_size(info),
        }

    def _parse_douyin(self, url: str, ytdlp_err: str) -> dict:
        """抖音：本层只负责「举手」，真正取数交给 Kotlin 侧的 WebView。

        为什么必须换实现：Windows 端靠 DrissionPage 驱动本机浏览器监听
        aweme/detail 接口响应，安卓上没有这个能力。但 WebView 本身就是浏览器，
        用它的 shouldInterceptRequest 拦下同一个接口、再用原生 HTTP 补一次
        请求即可拿到响应体 —— 分工是「Kotlin 取数、Python 落库」，
        因为 WebView 只有 Kotlin 侧能用，而库和后续下载都在这一层。

        need_webview 就是两边约定的信号：界面看到它就拉起抖音解析页。
        """
        return {
            "ok": False,
            "need_webview": True,
            "platform": "抖音",
            "source_url": url,
            "error": (
                "抖音需要「浏览器模式」解析。yt-dlp 走不通的原因是它需要 a_bogus "
                "签名和登录态，HTTP 客户端两样都拿不到。\n\n"
                f"原始报错：{friendly_error(ytdlp_err)}"
            ),
        }

    def save_douyin(self, payload_json: str) -> dict:
        """接收 Kotlin 侧 WebView 取到的抖音数据，转成统一记录并入库。

        返回结构与 parse_url 成功时一致，界面无需区别对待这两条路径。
        """
        data = json.loads(payload_json)
        url = (data.get("source_url") or "").strip()
        rec = {
            "id": str(data.get("id") or url),
            "platform": "抖音",
            "title": data.get("title") or "",
            "uploader": data.get("uploader") or "",
            "uploader_id": data.get("uploader_id") or "",
            "duration": data.get("duration"),
            "thumbnail": data.get("thumbnail") or "",
            "source_url": url,
            # 能直接放进播放器的那条（H.264 + 完整 mp4）
            "resolved_url": data.get("play_url") or "",
            # 画质最高的那条（可能是 H.265，能下不能播）
            "download_url": data.get("download_url") or "",
            "resolved_at": int(time.time()),
            # 抖音直链实测返回 206 + video/mp4，支持 Range，可直接内联播放
            "play_kind": "progressive",
        }
        self.store.upsert(rec)
        return {
            "ok": True,
            **rec,
            "quality": "原始画质（无水印）",
            "filesize": "",
            # 界面据此提示用户「用的是浏览器模式」，解释为什么要多等一会儿
            "via": "browser",
        }

    def _parse_x(self, url: str, ytdlp_err: str) -> dict:
        """X 备用链路：调第三方 FxTwitter 服务拿直链。

        纯 HTTP，不需要浏览器，所以安卓上能和 Windows 端做到完全一致。
        """
        try:
            from x_fallback import XFallbackError, resolve

            data = resolve(url)
        except XFallbackError as exc:
            return {
                "ok": False,
                "error": (f"X 备用解析失败：{exc}\n"
                          f"（主方案 yt-dlp 的错误：{friendly_error(ytdlp_err)}）"
                          # 这里必须补上 Cookie 提示：X 一旦 yt-dlp 失败就必进备用链路，
                          # 而上面那句翻译会把「缺 Cookie」说成「这条推文里没有视频」，
                          # 用户会照着错的方向去查。注意 windows/engine.py 目前没补，
                          # 属于两端的已知差异。
                          + self._cookie_hint(url, ytdlp_err)),
            }
        except Exception as exc:  # noqa: BLE001
            return {"ok": False, "error": f"X 备用解析异常：{type(exc).__name__}: {exc}"}

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
            "play_kind": "progressive",
        }
        self.store.upsert(rec)
        return {
            "ok": True,
            **rec,
            "quality": "最高画质（第三方服务解析）",
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

    def start_download(self, url: str) -> dict:
        url = (url or "").strip()
        if not url:
            return {"ok": False, "error": "链接为空"}

        with self._dl_lock:
            if self._dl_busy:
                return {"ok": False, "error": "已有下载任务正在进行，请等它完成"}
            self._dl_busy = True

        self.dl_state.update(type="starting", active=True, percent=None,
                             path="", message="")
        threading.Thread(target=self._download_worker, args=(url,), daemon=True).start()
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
                safe = re.sub(r'[\\/:*?"<>|]', "_", title)
                # 标题里若含 %，会被 yt-dlp 当模板再解析一次，转义成字面量
                safe = safe.replace("%", "%%")
                vid = (rec.get("id") or "").strip()
                # 按字节留出余量：安卓文件名上限 255 字节，这里让标题只用 200，
                # 剩下的留给「 [id].mp4」
                budget = MAX_NAME_BYTES - 8
                encoded = safe.encode("utf-8")
                if len(encoded) > budget:
                    safe = encoded[:budget].decode("utf-8", "ignore").strip()
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

    def _download_worker(self, url: str) -> None:
        logger = _Logger()
        try:
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


def start_download(url: str) -> str:
    return json.dumps(_get().start_download(url), ensure_ascii=False)


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
