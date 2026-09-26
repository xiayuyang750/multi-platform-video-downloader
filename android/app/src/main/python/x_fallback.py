# -*- coding: utf-8 -*-
"""X（推特）备用解析链路：调用第三方 FxTwitter 服务拿直链。

与 windows/x_fallback.py 是同一份实现的两个副本 —— 这段逻辑只用标准库
发 HTTP 请求，两端完全相同，改一处要同步另一处。

为什么需要它：
    yt-dlp 的 X 提取器依赖 X 官方接口，X 改版或限流时会失效
    （实测：不带 cookie 会报 "No video could be found in this tweet"）。
    备用链路把「解析」这件事外包给第三方服务，我们只发一个普通 HTTP 请求
    拿 JSON，不需要浏览器、不需要本地算签名、不需要登录态。

和抖音那条链路的区别（很重要）：
    抖音没有公开接口，且要求本地算签名，Windows 端只能驱动真实浏览器。
    推特这条路第三方已经替我们解决了，纯 HTTP 即可 —— 更轻，且失败原因
    可以清晰归类（超时 / 状态码 / 结构变化），用户能看懂该做什么。
"""

from __future__ import annotations

import json
import re
import socket
import urllib.error
import urllib.request

UA = (
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
)

# 按顺序尝试，前面失败自动试下一个。
# 目前只有 fxtwitter 可用（vxtwitter 实测 403），所以列表里只放它。
SERVICES = [
    ("fxtwitter", "https://api.fxtwitter.com/2/status/{id}"),
]


class XFallbackError(RuntimeError):
    """备用链路失败。消息本身已写成「给用户看的话」，调用方直接展示即可。"""


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


def _extract(data: dict) -> dict:
    """把 fxtwitter 的返回整理成与 engine._to_record 一致的字段。"""
    code = data.get("code")
    if code is not None and code != 200:
        raise XFallbackError(
            f"解析服务返回异常：{data.get('message') or code}。"
        )

    status = data.get("status") or {}
    if not status:
        raise XFallbackError("解析服务没有返回推文内容。")

    videos = ((status.get("media") or {}).get("videos")) or []
    if not videos:
        # 注意：这通常是真的没视频，而不是网络问题，要区分开告诉用户
        raise XFallbackError("这条推文里没有视频（可能只有文字或图片）。")

    video = videos[0]
    play_url = video.get("url") or ""
    if not play_url:
        raise XFallbackError("解析服务没有给出可下载的视频地址，可能已改版。")

    author = status.get("author") or {}
    duration = video.get("duration")
    tid = str(status.get("id") or video.get("id") or "")

    return {
        "id": tid,
        # 推文正文作为标题；没正文时用编号兜底，避免出现空标题
        "title": (status.get("text") or "").strip() or f"推文 {tid}",
        "uploader": author.get("name") or "",
        # @handle（例如 JjGoDJu4aL2efLM），界面上作为「作者唯一标识」显示
        "uploader_id": author.get("screen_name") or "",
        "duration": int(duration) if duration else None,
        "thumbnail": video.get("thumbnail_url") or "",
        "play_url": play_url,
        # 拿到的就是最高画质直链，播放和下载共用同一个地址
        "download_url": play_url,
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
        except XFallbackError as exc:
            last_error = f"{name}：{exc}"
        except Exception as exc:  # noqa: BLE001
            last_error = f"{name}：{type(exc).__name__}: {exc}"

    raise XFallbackError(last_error)
