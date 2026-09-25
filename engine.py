"""引擎层：调用 yt-dlp 与本地历史存储。

这一层完全不涉及界面与 HTTP，可以被冒烟测试直接调用。
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import threading
import time
from pathlib import Path
from urllib.parse import urlparse

BASE_DIR = Path(__file__).resolve().parent
CONFIG_FILE = BASE_DIR / "config" / "defaults.json"

DATA_DIR = Path(os.environ.get("APPDATA") or Path.home()) / "yt-dlp-gui"
DB_FILE = DATA_DIR / "history.db"
SETTINGS_FILE = DATA_DIR / "settings.json"

# 解析超时（秒）。境外站点在 VPN 未开时会一直重试到超时
PARSE_TIMEOUT = 75

PROGRESS_MARK = "__PROG__"
DONE_MARK = "__DONE__"

# 备用链路拿到的是裸直链（没有元数据），下载命令要据此改用「库里标题」拼文件名
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


def detect_platform(url: str) -> str:
    u = (url or "").lower()
    for name, pattern in PLATFORM_RULES:
        if re.search(pattern, u, re.I):
            return name
    return "其他"


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
                download_url TEXT
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


# ==================== 业务逻辑 ====================


class Engine:
    def __init__(self) -> None:
        self.defaults = json.loads(CONFIG_FILE.read_text(encoding="utf-8"))
        self.store = Store()
        self.dl_state = DownloadState()
        self._dl_lock = threading.Lock()
        self._dl_busy = False

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

    def parse_url(self, url: str) -> dict:
        """解析链接，返回元数据并写入历史（不下载文件）。"""
        url = (url or "").strip()
        if not url:
            return {"ok": False, "error": "链接为空"}

        try:
            cmd = build_command(self.get_defaults(), ["-J", "-f", "bv*+ba/b", url], url)
        except EngineError as exc:
            return {"ok": False, "error": str(exc)}

        try:
            proc = subprocess.run(
                cmd,
                capture_output=True,
                timeout=PARSE_TIMEOUT,
                env=subprocess_env(),
            )
        except subprocess.TimeoutExpired:
            return {
                "ok": False,
                "error": f"解析超时（{PARSE_TIMEOUT} 秒）。境外站点（YouTube/Instagram/X）需要开着 VPN。",
            }
        except OSError as exc:
            return {"ok": False, "error": f"无法启动 yt-dlp：{exc}"}

        stdout = decode_output(proc.stdout or b"")
        stderr = decode_output(proc.stderr or b"")

        if proc.returncode != 0 or not stdout.strip():
            lines = [ln for ln in stderr.splitlines() if ln.strip()]
            ytdlp_err = lines[-1] if lines else "yt-dlp 未返回数据"
            # 抖音走不了 yt-dlp：它需要 a_bogus 签名和登录态，HTTP 客户端拿不到，
            # 所以必然 403。改走浏览器备用链路（实测可拿到无水印直链）。
            if detect_platform(url) == "抖音":
                return self._parse_douyin_via_browser(url, ytdlp_err)
            # X 也留一条备用链路：yt-dlp 的 X 提取器依赖官方接口，改版或限流时会失效
            if detect_platform(url) == "X":
                return self._parse_x_via_service(url, ytdlp_err)
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

        self.store.upsert(rec)

        return {
            "ok": True,
            **rec,
            "quality": self._describe_quality(info),
            "filesize": self._describe_size(info),
        }

    # ---- 下载 ----

    def _parse_douyin_via_browser(self, url: str, ytdlp_err: str) -> dict:
        """抖音备用链路：用真实浏览器监听接口响应，拿无水印直链。

        走通后返回的字段与 _to_record 一致，界面与下载逻辑无需区别对待。
        """
        try:
            from douyin_fallback import DouyinFallbackError, resolve

            data = resolve(url)
        except DouyinFallbackError as exc:
            return {
                "ok": False,
                "error": (
                    f"抖音需要「浏览器模式」解析，但没能成功：{exc}\n"
                    f"（yt-dlp 的错误：{friendly_error(ytdlp_err)}）"
                ),
            }
        except Exception as exc:  # noqa: BLE001
            return {"ok": False, "error": f"抖音浏览器模式异常：{type(exc).__name__}: {exc}"}

        rec = {
            "id": data.get("id") or url,
            "platform": "抖音",
            "title": data.get("title") or "",
            "uploader": data.get("uploader") or "",
            "uploader_id": data.get("uploader_id") or "",
            "duration": data.get("duration"),
            "thumbnail": data.get("thumbnail") or "",
            "source_url": url,
            "resolved_url": data.get("play_url") or "",
            # 最高画质档（可能是 H.265，浏览器播不了但下载可用）
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

    def _parse_x_via_service(self, url: str, ytdlp_err: str) -> dict:
        """X 备用链路：调第三方 FxTwitter 服务拿直链。

        和抖音那条的关键区别：这里不需要浏览器。第三方已经替我们完成了
        「调 X 接口 + 挑最高画质」的工作，我们只发一个普通 HTTP 请求，
        所以失败原因可以清晰归类（见 x_fallback._fetch 的分支）。
        """
        try:
            from x_fallback import XFallbackError, resolve

            data = resolve(url)
        except XFallbackError as exc:
            return {
                "ok": False,
                "error": (
                    f"X 备用解析失败：{exc}\n"
                    f"（主方案 yt-dlp 的错误：{friendly_error(ytdlp_err)}）"
                ),
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

    def _download_target(self, url: str) -> str:
        """决定真正要下载的地址。

        抖音的分享链接 yt-dlp 解析不了，但它的直链已经由备用链路拿到并入库，
        所以下载时直接用那条直链；其他平台保持原样。
        """
        if detect_platform(url) not in ("抖音", "X"):
            return url
        for rec in self.store.all():
            if rec.get("source_url") == url:
                # 备用链路拿到的直链（可能是 H.265，仅供下载）；没有则退回可播放地址
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

        self.dl_state.update(type="starting", active=True, value="", path="", message="")
        threading.Thread(target=self._download_worker, args=(url,), daemon=True).start()
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
                # Windows 完整路径上限 260 字符。不能只截标题——要按
                # 「输出目录 + 分隔符 + ID + 扩展名」倒推还剩多少可用，
                # 否则长标题 + 深目录会把整个路径顶爆，下载直接失败。
                out_dir = str(
                    (self.get_defaults().get("download") or {}).get("output_dir") or ""
                )
                reserve = len(out_dir) + len(vid) + len(" [].%(ext)s") + 8
                safe = safe[: max(24, 250 - reserve)].strip()
                return f"{safe} [{vid}].%(ext)s" if vid else f"{safe}.%(ext)s"
        return ""

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
            eng.get("progress_template") or f"download:{PROGRESS_MARK}%(progress._percent_str)s",
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

    def _download_worker(self, url: str) -> None:
        try:
            cmd = self.build_download_command_with_tuning(
                self.get_defaults(), self._download_target(url)
            )
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

        try:
            assert proc.stdout is not None
            for raw in proc.stdout:
                line = decode_output(raw).rstrip()
                if not line:
                    continue
                if line.startswith(PROGRESS_MARK):
                    self.dl_state.update(
                        type="progress", active=True, value=line[len(PROGRESS_MARK):]
                    )
                elif line.startswith(DONE_MARK):
                    self.dl_state.update(
                        type="done", active=True, path=line[len(DONE_MARK):]
                    )
                elif "ERROR:" in line:
                    self.dl_state.update(type="error", active=True, message=line)
            proc.wait()
        finally:
            # 只把 active 置回 False，保留最后一条有意义的状态供界面展示
            self.dl_state.update(active=False)
            self._dl_finish()

    def _dl_finish(self) -> None:
        with self._dl_lock:
            self._dl_busy = False

    # ---- 系统操作 ----

    def open_folder(self, path: str = "") -> bool:
        target = path if path and Path(path).exists() else ""
        try:
            if target:
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