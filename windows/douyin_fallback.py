# -*- coding: utf-8 -*-
"""抖音解析链路：优先调公开接口，失败才驱动真实浏览器。

两条路：
  1. resolve_via_api()（主）—— 纯 HTTP 调 aweme.snssdk.com 的公开接口，把 UA
     伪装成抖音 App 自身。不需要签名、不需要登录，秒回。与安卓端
     ytdlp_engine._parse_douyin_via_api 同构，改一处要同步另一处。
  2. resolve()（兜底）—— 驱动真实浏览器，监听它自己发出的 aweme/detail 响应，
     不需要逆向签名算法（"搭便车"）。接口哪天被关掉时顶上。

为什么 yt-dlp 走不通、要单独做：
    yt-dlp 用 HTTP 客户端直连抖音，需要 a_bogus 签名和登录态，两样都拿不到，
    所以必然失败（报错是 403 / "Fresh cookies are needed"）。

浏览器那条的代价与边界：
    - 会弹出一个浏览器窗口（不弹窗就得 headless，而 headless 更容易被检测，
      正好抵消它最大的优势，所以选择可见窗口）
    - 比接口方式慢（数秒到十几秒）
    - 只对抖音有效，无法推广到其他平台
"""

from __future__ import annotations

import atexit
import json
import os
import re
import shutil
import socket
import threading
import urllib.error
import urllib.request
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
    """解析链路不可用（接口失败、缺浏览器、缺依赖、或取不到数据）。"""


# ==================== 公开接口方案（主路径）====================
# 这条接口的来源：对比一个能稳定解析抖音的参考实现，从它的 dex 里提取字符串后
# 发现的。它不需要 a_bogus 签名、不需要登录态，直连即 200 —— 只需把 UA 伪装成
# 抖音 App 自身。以下三个常量与安卓端 ytdlp_engine.py 保持一致，改一处要同步另一处。
DOUYIN_APP_UA = (
    "com.ss.android.ugc.aweme/260201 (Linux; U; Android 12; zh_CN; Pixel 4; "
    "Build/SP1A.210812.016; Cronet/TTNetVersion)"
)
FEED_API = "https://aweme.snssdk.com/aweme/v1/feed/?aweme_id={id}"

# 展开分享短链时用的普通浏览器 UA
_BROWSER_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)


def video_id(url: str) -> str:
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


def resolve_via_api(share_url: str, timeout: int = 20) -> dict:
    """调抖音公开接口拿无水印直链。

    返回的键与 resolve() 对齐，便于复用同一套入库与界面逻辑。
    失败时抛 DouyinFallbackError。
    """
    vid = video_id(share_url)
    if not vid:
        raise DouyinFallbackError("没能从链接里识别出作品编号")

    request = urllib.request.Request(
        FEED_API.format(id=vid), headers={"User-Agent": DOUYIN_APP_UA}
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as resp:
            body = json.loads(resp.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as exc:
        raise DouyinFallbackError(f"接口返回 HTTP {exc.code}") from exc
    except (socket.timeout, TimeoutError) as exc:
        raise DouyinFallbackError("连接抖音接口超时，请检查网络。") from exc
    except urllib.error.URLError as exc:
        raise DouyinFallbackError(f"连不上抖音接口：{exc.reason}") from exc
    except (ValueError, json.JSONDecodeError) as exc:
        raise DouyinFallbackError("接口返回的内容无法识别，可能已改版。") from exc

    items = body.get("aweme_list") or []
    if not items:
        raise DouyinFallbackError("接口没有返回作品数据（可能作品已删除或设为私享）")

    # 这个接口是「推荐流」性质的：只有**视频**会被原样带回，图文/图集/实况图
    # 这类「笔记」它不服务 —— 此时 items 里全是推荐作品，目标 id 不在其中。
    #
    # 绝不能退回 items[0]：实测 7 条不同的图集/实况图链接会拿到**同一条无关视频**，
    # 而界面显示「解析成功」，用户会把别人的视频当成自己的内容下走（伪成功）。
    # 取不到就明确失败，交给上层降级到浏览器模式。与安卓端同构，改一处要同步另一处。
    target = next(
        (x for x in items if str(x.get("aweme_id") or "") == vid),
        None,
    )
    if target is None:
        raise DouyinFallbackError(
            f"接口返回的 {len(items)} 条里没有这条作品（疑似图文/图集类内容），转浏览器模式"
        )

    video = target.get("video") or {}
    images = target.get("images") or []
    play_url, download_url = _pick_tiers(video)
    if not play_url and not images:
        raise DouyinFallbackError("接口返回里没有可用的视频地址")

    author = target.get("author") or {}
    cover = ((video.get("cover") or {}).get("url_list") or [""])[0]
    duration_ms = video.get("duration") or 0

    return {
        "id": str(target.get("aweme_id") or vid),
        "title": target.get("desc") or "",
        "uploader": author.get("nickname") or "",
        "uploader_id": author.get("unique_id") or author.get("short_id") or "",
        "duration": int(duration_ms // 1000) if duration_ms else None,
        "thumbnail": cover or "",
        "play_url": play_url,
        "download_url": download_url,
        # 原始数据：档位、图文/图集/实况图的媒体列表都只在原始对象里，
        # 交给 engine 统一按 douyin_collect_tiers / _douyin_image_url 处理，
        # 避免这里再写一份挑档逻辑。
        "video": video,
        "images": images,
    }


_lock = threading.Lock()
_page = None


# ==================== 主文档解析（图文 / 图集 / 实况图）====================
# 抖音的「笔记」类作品（图文/图集/实况图）不会被免签名的 feed 接口服务，
# 但桌面版页面的主文档里，服务端把这条作品的数据直出在 React 流式负载里。
# 与安卓端 DouyinParser.parseMainDocument 同一条思路：以 `"images":` 为锚点，
# 往前找标题/作者，往后抠出整段数组。


def _id_from_url(url: str) -> str:
    m = re.search(r"/(?:video|note)/(\d{10,25})", url or "")
    return m.group(1) if m else ""


def _unescape_js(raw: str) -> str:
    """把页面里 JS 字符串的一层转义还原（`\\"` → `"`，`\\\\` → `\\`）。"""
    return raw.replace('\\"', '"').replace("\\\\", "\\")


def _extract_array(text: str, key: str) -> str | None:
    """从 key 之后抠出配对的 JSON 数组原文（正确处理字符串里的括号）。"""
    i = text.find(key)
    if i < 0:
        return None
    j = text.find("[", i)
    if j < 0:
        return None
    depth, in_str, esc = 0, False, False
    for k in range(j, len(text)):
        ch = text[k]
        if in_str:
            if esc:
                esc = False
            elif ch == "\\":
                esc = True
            elif ch == '"':
                in_str = False
        else:
            if ch == '"':
                in_str = True
            elif ch == "[":
                depth += 1
            elif ch == "]":
                depth -= 1
                if depth == 0:
                    return text[j:k + 1]
    return None


def _last_json_str(text: str, key: str) -> str:
    """取锚点之前**最近一次**有效出现的 `key: "..."`。

    页面里同名字段会出现多次，其中不少是 React 流式负载的占位符
    `"$undefined"`（实测：直接取最后一次会拿到它当标题）。所以从近到远扫，
    跳过占位符与空串，返回第一个真正有内容的值。
    """
    matches = list(re.finditer(rf'"{key}"\s*:\s*"((?:\\.|[^"\\])*)"', text))
    for m in reversed(matches):
        raw = m.group(1)
        try:
            value = json.loads(f'"{raw}"')
        except Exception:  # noqa: BLE001
            value = raw
        if value and value != "$undefined":
            return value
    return ""


def _pick_doc_image(item: dict) -> str:
    urls = item.get("urlList") or item.get("url_list") or []
    fallback = ""
    for u in urls:
        if not u:
            continue
        if not fallback:
            fallback = u
        low = str(u).lower()
        if ".jpeg" in low or ".jpg" in low:
            return u
    return fallback


def parse_main_document(html_text: str, source_url: str, expected_id: str = "") -> dict | None:
    """从页面主文档里解析图文/图集/实况图；返回 None 表示不是这类页面。"""
    text = _unescape_js(html_text or "")
    raw = _extract_array(text, '"images":')
    if not raw:
        return None
    try:
        images = json.loads(raw)
    except json.JSONDecodeError:
        return None
    if not isinstance(images, list) or not images:
        return None

    anchor = text.find('"images":')
    scope = text[max(0, anchor - 20000):anchor]
    desc = _last_json_str(scope, "desc")
    nickname = _last_json_str(scope, "nickname")
    unique_id = _last_json_str(scope, "unique_id")

    media: list[dict] = []
    for item in images:
        if not isinstance(item, dict):
            continue
        url = _pick_doc_image(item)
        if not url:
            continue
        live = ""
        video = item.get("video") or {}
        play = video.get("playAddr") or video.get("play_addr") or []
        if isinstance(play, list) and play:
            first = play[0] or {}
            live = first.get("src") or ""
        media.append({
            "url_list": [url],
            "width": int(item.get("width") or 0),
            "height": int(item.get("height") or 0),
            "video": {"play_addr": {"url_list": [live]}} if live else None,
            "kind": "image",
        })
    if not media:
        return None

    return {
        "id": expected_id or source_url,
        "title": desc,
        "uploader": nickname,
        "uploader_id": unique_id,
        "duration": None,
        "thumbnail": media[0]["url_list"][0],
        "play_url": "",
        "download_url": "",
        "video": {},
        "images": media,
    }


def _pause_page_media(page) -> None:
    """把页面上正在播的视频停掉（否则窗口关掉后声音还在后台响）。"""
    try:
        page.run_js(
            "document.querySelectorAll('video,audio').forEach(function(e){"
            "try{e.pause();e.muted=true;e.currentTime=0}catch(_){}})"
        )
    except Exception:  # noqa: BLE001
        pass


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

    与安卓端 ytdlp_engine.douyin_pick_tiers / Kotlin 侧 DouyinParser.pickTiers
    是同一份规格，改一处要同步另两处。

    抖音的 video.play_addr 只是「默认档」，bit_rate 数组才是全部档位
    （实测同一视频有 23 档）。

    选档 key = (分辨率, 是否 H.264, 码率) 的字典序最大者，**分辨率排第一位**：
    宁可要 720p 的 H.265，也不退回 576p 的 H.264 —— 实测同一支视频，默认档给的是
    1024x576 H.264@995kbps，而 bit_rate 里有 1280x720 H.265，按分辨率优先
    后者才是画质更好的那个（体积还只有一半）。

    同分辨率下优先 H.264：H.265 同画质体积小，但兼容性差（部分播放器 / 相册 /
    剪辑软件不认），而下载产物是要长期留着的；只是**不以牺牲分辨率为代价**。

    所以分开选，各自用在合适的地方：
      - play_url     → 放进 <video> 直接播（还要求 H.264 + 完整 mp4）
      - download_url → 下载用，分辨率最高、同档优先 H.264
    """
    best_play, best_play_key = "", (-1, -1, -1)
    best_any, best_any_key = "", (-1, -1, -1)

    for item in video.get("bit_rate") or []:
        play = item.get("play_addr") or {}
        urls = play.get("url_list") or []
        if not urls:
            continue

        is_h265 = int(item.get("is_h265") or 0) != 0
        # 三元 key 直接按字典序比较：(分辨率, 编码偏好, 码率)
        key = (int(play.get("height") or 0),
               0 if is_h265 else 1,
               int(item.get("bit_rate") or 0))

        if key > best_any_key:
            best_any_key, best_any = key, urls[0]

        # 只有 H.264 且是完整 mp4 的才适合直接放进浏览器 <video>
        if not is_h265 and str(item.get("format") or "") == "mp4":
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


def resolve(share_url: str, timeout: int = 15) -> dict:
    """用浏览器打开分享链接，返回统一的记录字段。

    两条路都走：
      ① 主文档 —— 图文/图集/实况图是服务端直出的，打开页面就有；
      ② 详情接口 —— 视频页主文档只是 JS 壳，数据要等页面自己请求回来。
    与安卓端 DouyinActivity 的处理顺序一致。
    """
    with _lock:  # 同一个浏览器实例不能并发操作
        page = _get_page()
        page.listen.start(DETAIL_API)
        page.get(share_url)

        expected = _id_from_url(page.url) or _id_from_url(share_url)
        try:
            payload = parse_main_document(page.html, share_url, expected)
        except Exception as exc:  # noqa: BLE001
            print(f"[douyin] 主文档解析失败：{exc}")
            payload = None
        if payload:
            _pause_page_media(page)
            return payload

        try:
            res = page.listen.wait(timeout=timeout, raise_err=False)
        except TypeError:
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
        images = detail.get("images") or []
        play_url, download_url = _pick_tiers(video)
        if not play_url and not images:
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
            "video": video,
            "images": images,
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
