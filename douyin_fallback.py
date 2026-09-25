# -*- coding: utf-8 -*-
"""抖音备用解析链路：驱动真实浏览器，监听接口响应拿无水印直链。

为什么必须单独做一条：
    yt-dlp 用 HTTP 客户端直连抖音接口，需要 a_bogus 签名和登录态，两样都拿不到，
    所以必然失败（报错是 403 / "Fresh cookies are needed"）。而真实浏览器会自动
    完成这两件事 —— 我们只要监听它自己发出的 aweme/detail 响应即可。
    这是"搭便车"，不需要逆向签名算法，因此比自行实现签名更耐用。

代价与边界：
    - 会弹出一个浏览器窗口（不弹窗就得 headless，而 headless 更容易被检测，
      正好抵消它最大的优势，所以选择可见窗口）
    - 比 yt-dlp 慢（数秒到十几秒）
    - 只对抖音有效，无法推广到其他平台
    因此它只作为兜底：yt-dlp 失败时才启用。
"""

from __future__ import annotations

import atexit
import os
import shutil
import threading
from pathlib import Path

# 浏览器数据目录：放在用户数据区，而不是项目目录里。
# 项目目录将来可能被复制给别人或上传，而这里面存着登录态的 Cookie，
# 放在项目里等于把「备用钥匙」放进要送人的盒子。
DATA_DIR = Path(os.environ.get("APPDATA") or Path.home()) / "yt-dlp-gui"
PROFILE_DIR = DATA_DIR / "browser-profile"

# 旧版本把 profile 放在项目目录下；这里做一次迁移，避免用户白登录一趟
_LEGACY_PROFILE = Path(__file__).resolve().parent / ".browser-profile"
if _LEGACY_PROFILE.is_dir() and not PROFILE_DIR.exists():
    try:
        PROFILE_DIR.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(_LEGACY_PROFILE), str(PROFILE_DIR))
    except Exception:  # noqa: BLE001
        # 迁移失败不影响使用，只是要重新登录一次
        pass

# 按优先级找可用浏览器
BROWSER_CANDIDATES = [
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
]

# 单条视频的详情接口；用子串匹配，兼容不同版本路径
DETAIL_API = "aweme/detail/"


class DouyinFallbackError(RuntimeError):
    """备用链路不可用（缺浏览器、缺依赖、或取不到数据）。"""


_lock = threading.Lock()
_page = None


def _find_browser() -> str:
    for path in BROWSER_CANDIDATES:
        if os.path.exists(path):
            return path
    raise DouyinFallbackError("没有找到 Edge 或 Chrome，浏览器模式无法使用")


def _get_page():
    """复用常驻浏览器实例。

    每次解析都重启浏览器要好几秒，体验太差，所以保持一个实例。
    浏览器被用户关掉时，下一次调用会自动重建。
    """
    global _page
    if _page is not None:
        try:
            _ = _page.url  # 存活探测：实例已死时这里会抛异常
            return _page
        except Exception:  # noqa: BLE001
            _page = None

    try:
        from DrissionPage import ChromiumOptions, ChromiumPage
    except ImportError as exc:
        raise DouyinFallbackError(f"缺少 DrissionPage 依赖：{exc}") from exc

    options = ChromiumOptions()
    options.set_browser_path(_find_browser())
    options.set_user_data_path(str(PROFILE_DIR))
    options.set_argument("--disable-blink-features=AutomationControlled")
    # 防声音双保险：--mute-audio 静音标签页，autoplay-policy 直接禁止自动播放。
    # 抖音页面会自动播放视频，窗口被关掉后声音仍在后台响，用户找不到地方关。
    options.set_argument("--mute-audio")
    options.set_argument("--autoplay-policy=user-gesture-required")
    try:
        _page = ChromiumPage(options)
    except Exception as exc:  # noqa: BLE001
        raise DouyinFallbackError(f"启动浏览器失败：{exc}") from exc
    return _page


def _shutdown_browser() -> None:
    """程序退出时关掉浏览器实例。

    不加这个的话，关掉启动窗口后浏览器进程可能还在后台活着
    （表现为「窗口不见了但声音还在放」，用户找不到地方关）。
    """
    global _page
    if _page is None:
        return
    try:
        _page.quit()
    except Exception:  # noqa: BLE001
        pass
    _page = None


atexit.register(_shutdown_browser)


def _pick_tiers(video: dict) -> tuple[str, str]:
    """从 bit_rate 里挑出两个地址：能播的最高档、画质最高的档。

    抖音的 video.play_addr 只是「默认档」，bit_rate 数组才是全部档位
    （实测同一视频有 23 档）。关键差异：最高码率档是 H.265 编码
    （1080p / 1065kbps / 127.9MB），而浏览器普遍播不了 H.265；
    能播的最高档是 H.264 + 完整 mp4（1080p / 964kbps / 115.8MB）。

    所以分开选，各自用在合适的地方：
      - play_url     → 放进 <video> 直接播
      - download_url → 下载用，画质最好
    """
    best_play, best_play_key = "", (-1, -1)
    best_any, best_any_key = "", (-1, -1)

    for item in video.get("bit_rate") or []:
        play = item.get("play_addr") or {}
        urls = play.get("url_list") or []
        if not urls:
            continue
        key = (int(play.get("height") or 0), int(item.get("bit_rate") or 0))

        if key > best_any_key:
            best_any_key, best_any = key, urls[0]

        # 只有 H.264 且是完整 mp4 的才适合直接放进浏览器 <video>
        if int(item.get("is_h265") or 0) == 0 and str(item.get("format") or "") == "mp4":
            if key > best_play_key:
                best_play_key, best_play = key, urls[0]

    if not best_play:  # 没有符合的档位就退回默认地址
        default_urls = ((video.get("play_addr") or {}).get("url_list")) or []
        best_play = default_urls[0] if default_urls else ""
    if not best_any:
        best_any = best_play
    return best_play, best_any


def _diag(page) -> str:
    """收集浏览器当前状态，附到错误信息里。

    纯靠"失败"两个字没法定位问题：不知道浏览器到底起来了没有、卡在哪个页面。
    把地址和标题带出来，用户一看就能判断是哪一环。
    """
    try:
        return f"（浏览器当前地址：{page.url}；页面标题：{page.title}）"
    except Exception:  # noqa: BLE001
        return "（浏览器已不可用，可能已被关闭）"


def resolve(share_url: str, timeout: int = 45) -> dict:
    """用浏览器打开分享链接，返回统一的记录字段。

    返回的键与 engine._to_record 对齐，便于直接复用入库与界面渲染逻辑。
    失败时抛 DouyinFallbackError（由调用方翻译给用户）。
    """
    with _lock:  # 同一个浏览器实例不能并发操作
        page = _get_page()
        page.listen.start(DETAIL_API)
        page.get(share_url)
        try:
            res = page.listen.wait(timeout=timeout, raise_err=False)
        except TypeError:
            # 兼容不同版本的 wait 签名
            res = page.listen.wait(timeout=timeout)
        except Exception as exc:  # noqa: BLE001
            raise DouyinFallbackError(f"等待接口响应时出错：{exc}") from exc

        if not res:
            raise DouyinFallbackError(
                f"{timeout} 秒内没等到接口响应{_diag(page)}"
            )

        body = res.response.body
        if not isinstance(body, dict):
            raise DouyinFallbackError("接口返回的不是预期结构")

        detail = body.get("aweme_detail") or {}
        if not detail:
            raise DouyinFallbackError(
                f"接口返回里没有视频数据（可能该链接不是单条视频）{_diag(page)}"
            )

        video = detail.get("video") or {}
        play_url, download_url = _pick_tiers(video)
        if not play_url:
            raise DouyinFallbackError("没有取到可播放的视频地址")

        author = detail.get("author") or {}
        cover = ((video.get("cover") or {}).get("url_list") or [""])[0]
        duration_ms = video.get("duration") or 0

        # 需要的字段已经拿到，立刻停掉页面上正在播的视频。
        # 否则窗口被关掉后声音还在后台响，用户找不到能关的地方。
        try:
            page.run_js(
                "document.querySelectorAll('video,audio').forEach(function(e){"
                "try{e.pause();e.muted=true;e.currentTime=0}catch(_){}})"
            )
        except Exception:  # noqa: BLE001
            pass

        return {
            "id": str(detail.get("aweme_id") or ""),
            "title": detail.get("desc") or "",
            "uploader": author.get("nickname") or "",
            "uploader_id": author.get("unique_id") or author.get("short_id") or "",
            "duration": int(duration_ms // 1000) if duration_ms else None,
            "thumbnail": cover,
            "play_url": play_url,
            "download_url": download_url,
        }


def _login_session() -> None:
    """供「登录抖音.bat」调用：打开浏览器并停住，让用户登录。

    为什么需要单独一个入口：正常解析时窗口会在任务结束后随进程关闭，
    用户根本来不及登录。这里用 input() 让进程保持存活，浏览器就一直开着。
    登录状态会写进 .browser-profile，之后解析抖音自动复用。
    """
    import time

    page = _get_page()
    page.get("https://www.douyin.com/")
    time.sleep(3)

    print("")
    print("=" * 58)
    print("  浏览器已打开，请在这个窗口里登录抖音（扫码或账号密码均可）")
    print("  注意：这是本项目专用的独立浏览器环境，与你日常的 Edge 互不影响")
    print("")
    try:
        print(f"  浏览器地址：{page.url}")
        print(f"  页面标题：{page.title}")
    except Exception as exc:  # noqa: BLE001
        print(f"  警告：浏览器似乎已经被关闭（{exc}）")
    print("")
    print("  登录完成后，回到本窗口按回车即可")
    print("=" * 58)

    try:
        input()
    except (EOFError, KeyboardInterrupt):
        pass

    try:
        page.quit()
    except Exception:  # noqa: BLE001
        pass
    print("已关闭浏览器。登录状态已保存，以后解析抖音会自动使用它。")


if __name__ == "__main__":
    _login_session()
