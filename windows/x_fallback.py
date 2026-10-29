# -*- coding: utf-8 -*-
"""X（推特）备用解析链路：调用第三方 FxTwitter 服务拿直链。

为什么需要它：
    yt-dlp 的 X 提取器依赖 X 官方接口，X 改版或限流时会失效
    （我们实测过：不带 cookie 会报 "No video could be found in this tweet"）。
    备用链路把「解析」这件事外包给第三方服务，我们只发一个普通 HTTP 请求
    拿 JSON，不需要浏览器、不需要本地算签名、不需要登录态。

和抖音那条链路的区别（很重要）：
    抖音没有公开接口，且要求本地算签名，所以只能驱动真实浏览器（重、易残留）；
    推特这条路第三方已经替我们解决了，纯 HTTP 即可 —— 更轻，且失败原因
    可以清晰归类（超时 / 状态码 / 结构变化），用户能看懂该做什么。

服务选型说明：
    实测 api.fxtwitter.com 正常（200，0.4s）；api.vxtwitter.com 三种路径
    均返回 403，当前不可用，因此只启用 fxtwitter。将来若恢复，
    在 SERVICES 里加一条即可，无需改动下面的逻辑。
"""

from __future__ import annotations

import json
import re
import socket
import urllib.error
import urllib.request

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)

# 按顺序尝试，前面失败自动试下一个。
# 目前只有 fxtwitter 可用（vxtwitter 实测 403），所以列表里只放它。
SERVICES = [
    ("fxtwitter", "https://api.fxtwitter.com/2/status/{id}"),
]


class XFallbackError(RuntimeError):
    """备用链路失败。消息本身已写成「给用户看的话」，调用方直接展示即可。"""


class XNoMediaError(XFallbackError):
    """这条推文**本身**就没有媒体（纯文字 / 投票 / 文章）。

    与上面那类的区别很关键：这类失败说明「换条链路、补 Cookie 也没用」，
    所以调用方不该再往下去试 yt-dlp、更不该提示用户去查 Cookie。
    """


def _tweet_id(url: str) -> str:
    """从推文链接里取出数字编号。"""
    m = re.search(r"(?:x\.com|twitter\.com)/[^/]*/status(?:es)?/(\d+)", url or "")
    if m:
        return m.group(1)
    m = re.search(r"\b(\d{15,25})\b", url or "")
    return m.group(1) if m else ""


def _fetch(api_url: str, timeout: int) -> dict:
    request = urllib.request.Request(
        api_url, headers={"User-Agent": UA, "Accept": "application/json"}
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as exc:
        if exc.code == 429:
            raise XFallbackError(
                "解析服务提示「请求过于频繁」，请等几分钟再试。"
            ) from exc
        if exc.code == 404:
            raise XFallbackError("这条推文不存在或已被删除。") from exc
        if 500 <= exc.code < 600:
            raise XFallbackError(
                f"解析服务暂时故障（HTTP {exc.code}），请稍后重试。"
            ) from exc
        raise XFallbackError(
            f"解析服务拒绝了这个请求（HTTP {exc.code}），可能已限制访问。"
        ) from exc
    except (socket.timeout, TimeoutError) as exc:
        raise XFallbackError(
            "连接解析服务超时。境外站点需要代理，请检查网络。"
        ) from exc
    except urllib.error.URLError as exc:
        raise XFallbackError(
            "连不上解析服务 fxtwitter.com。国内网络通常需要代理，"
            "请检查代理规则是否放行该域名。"
        ) from exc
    except (ValueError, json.JSONDecodeError) as exc:
        raise XFallbackError("解析服务返回的内容无法识别，可能已改版。") from exc


def _wh_from_url(url: str) -> tuple[int, int]:
    """从推特视频地址里抠宽高：形如 `.../vid/avc1/1308x2326/xxx.mp4`。"""
    m = re.search(r"/(\d{2,5})x(\d{2,5})/", url or "")
    return (int(m.group(1)), int(m.group(2))) if m else (0, 0)


def _extract(data: dict) -> dict:
    """把 fxtwitter 的返回整理成统一记录。

    一条推文里的媒体可能有几种：单个视频、多个视频、若干张图、图文混排。
    接口全都给了（media.videos / media.photos，图片还是 ?name=orig 原图），
    所以全部收进 media 列表、每项带 kind，下游按列表走。

    早先只读 media.videos 的第一条，于是「图片/图集」被判成「没有视频」，
    「多视频」「图文混排」都只拿到其中一个。
    """
    code = data.get("code")
    if code is not None and code != 200:
        raise XFallbackError(
            f"解析服务返回异常：{data.get('message') or code}。"
        )

    status = data.get("status") or {}
    if not status:
        raise XFallbackError("解析服务没有返回推文内容。")

    media_obj = status.get("media") or {}
    raw_videos = media_obj.get("videos") or []
    raw_photos = media_obj.get("photos") or []

    media: list[dict] = []
    for p in raw_photos:
        url = (p.get("url") or "").strip()
        if url:
            media.append({
                "kind": "image",
                "url": url,
                "width": int(p.get("width") or 0),
                "height": int(p.get("height") or 0),
            })
    for v in raw_videos:
        url = (v.get("url") or "").strip()
        if not url:
            continue
        w, h = int(v.get("width") or 0), int(v.get("height") or 0)
        if not (w and h):
            w, h = _wh_from_url(url)
        media.append({"kind": "video", "url": url, "width": w, "height": h})

    if not media:
        raise XNoMediaError(
            "这条推文里没有可下载的内容 —— 纯文字、投票、文章类推文本身不含媒体文件。"
        )

    author = status.get("author") or {}
    tid = str(status.get("id") or "")
    text = (status.get("text") or "").strip()
    base = {
        "id": tid,
        "title": text or f"推文 {tid}",
        "uploader": author.get("name") or "",
        "uploader_id": author.get("screen_name") or "",
    }

    # 只有一条视频、且没有图片时，仍按「普通视频」走（播放器直接播，最省事）
    if len(media) == 1 and media[0]["kind"] == "video":
        v = raw_videos[0]
        duration = v.get("duration")
        return {
            **base,
            "duration": int(duration) if duration else None,
            "thumbnail": v.get("thumbnail_url") or "",
            "play_url": media[0]["url"],
            "download_url": media[0]["url"],
            "content_type": "video",
        }

    # 其余情况（纯图片 / 图集 / 多视频 / 图文混排）统一按「媒体列表」处理
    cover = (raw_videos[0].get("thumbnail_url") if raw_videos else "") or ""
    if not cover and raw_photos:
        cover = raw_photos[0].get("url") or ""
    return {
        **base,
        "duration": None,
        "thumbnail": cover,
        "play_url": "",
        "download_url": "",
        "content_type": "images",
        "media": media,
    }


def resolve(tweet_url: str, timeout: int = 20) -> dict:
    """解析一条推文，返回统一的记录字段；失败抛 XFallbackError。"""
    tid = _tweet_id(tweet_url)
    if not tid:
        raise XFallbackError("没能从链接里识别出推文编号，请检查链接。")

    last_error = "备用解析服务全部失败。"
    for name, template in SERVICES:
        try:
            return _extract(_fetch(template.format(id=tid), timeout))
        except XNoMediaError:
            # 「本来就没有媒体」是结论、不是服务故障：调用方需要原样看到这个类型
            raise
        except XFallbackError as exc:
            last_error = f"{name}：{exc}"
        except Exception as exc:  # noqa: BLE001
            last_error = f"{name}：{type(exc).__name__}: {exc}"

    raise XFallbackError(last_error)
