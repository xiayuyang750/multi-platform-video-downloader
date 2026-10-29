# -*- coding: utf-8 -*-
"""小红书 / 微博的浏览器取数（Windows 端）。

安卓端靠应用内 WebView 完成这件事；Windows 端没有 WebView 可用，
所以复用 douyin_fallback 已经维护好的「真实浏览器 + 独立 profile」这一套：
同一个浏览器实例、同一份登录态，只是脚本不同。

两条链路都需要页面自己的 JS 把数据吐出来：
  · 小红书：`window.__INITIAL_STATE__.note.noteDetailMap[<笔记id>]`
  · 微博：  `window.$render_data.status`（移动版详情页会把这条微博直出在页面上）
取数规则与安卓端 XhsParser / WeiboParser 一一对应，改一处要同步另一处。
"""

from __future__ import annotations

import json
import re
import time
from urllib.parse import unquote

from douyin_fallback import DouyinFallbackError, _get_page, _pause_page_media


class WebFallbackError(RuntimeError):
    """浏览器取数失败。消息已写成给用户看的话。"""


class WebNeedLogin(WebFallbackError):
    """页面明确显示未登录 —— 需要用户在浏览器里登录一次。"""


# ==================== 小红书 ====================

_XHS_JS = """
(function(){
  try{
    var s = window.__INITIAL_STATE__;
    var loggedIn = !!(s && s.user && s.user.loggedIn);
    var id = '__EXPECTED_ID__';
    var e = (s && s.note && s.note.noteDetailMap) ? s.note.noteDetailMap[id] : null;
    var n = e ? e.note : null;
    if(!n) return JSON.stringify({note:null, loggedIn:loggedIn});
    function pickImage(i){
      return { urlDefault:i.urlDefault, urlPre:i.urlPre, url:i.url,
               width:i.width, height:i.height, infoList:i.infoList,
               video:i.video, stream:i.stream, livePhoto:i.livePhoto };
    }
    return JSON.stringify({ loggedIn: loggedIn, note: {
      noteId:n.noteId, type:n.type, title:n.title, desc:n.desc,
      user:{ nickname:(n.user||{}).nickname, userId:(n.user||{}).userId,
             redId:(n.user||{}).redId },
      imageList:(n.imageList||[]).map(pickImage),
      video:n.video
    }});
  }catch(e){ return ''; }
})()
"""


def _xhs_note_id(url: str) -> str:
    m = re.search(r"/(?:explore|discovery/item|item)/([0-9a-f]{16,32})", url or "", re.I)
    return m.group(1) if m else ""


def _xhs_image_url(img: dict) -> str:
    for key in ("urlDefault", "urlPre", "url"):
        v = img.get(key) or ""
        if v:
            return v
    for scene in ("WB_DFT", "WB_PRV"):
        for one in img.get("infoList") or []:
            if (one or {}).get("imageScene") == scene and (one.get("url") or ""):
                return one["url"]
    return ""


def _stream_best(stream) -> str:
    """从 `{编码编号: [候选…]}` 里挑直链：带音轨优先，再比高度/体积。"""
    if not isinstance(stream, dict):
        return ""
    all_rows = []
    for rows in stream.values():
        if isinstance(rows, list):
            all_rows.extend(i for i in rows if isinstance(i, dict) and i.get("masterUrl"))
    if not all_rows:
        return ""
    muxed = [i for i in all_rows if _has_audio(i)]
    pool = muxed or all_rows
    best = sorted(pool, key=lambda i: (int(i.get("height") or 0),
                                       int(i.get("size") or 0)), reverse=True)[0]
    return best.get("masterUrl") or ""


def _has_audio(entry: dict) -> bool:
    if int(entry.get("audioChannels") or 0) > 0:
        return True
    if int(entry.get("audioBitrate") or 0) > 0:
        return True
    codec = str(entry.get("audioCodec") or "").lower()
    return codec not in ("", "none")


def _xhs_live_url(img: dict) -> str:
    video = img.get("video") or {}
    if isinstance(video, dict):
        st = ((video.get("media") or {}).get("stream")) or {}
        url = _stream_best(st)
        if url:
            return url
        play = video.get("playAddr") or []
        if isinstance(play, list) and play and (play[0] or {}).get("src"):
            return play[0]["src"]
    return _stream_best(img.get("stream") or {})


def _xhs_duration(video: dict) -> int | None:
    stream = ((video.get("media") or {}).get("stream")) or {}
    if not isinstance(stream, dict):
        return None
    ms = 0
    for rows in stream.values():
        if isinstance(rows, list):
            for one in rows:
                if isinstance(one, dict):
                    ms = max(ms, int(one.get("duration") or 0))
    return ms // 1000 if ms > 0 else None


def resolve_xhs(share_url: str) -> dict:
    """打开小红书笔记页，取出结构化笔记数据。"""
    page = _get_page()
    page.get(share_url)
    time.sleep(2.0)

    # 没登录时小红书会把地址重定向到 /login?redirectPath=<编码后的笔记地址>，
    # 笔记编号藏在编码串里，所以先解码再取。
    if "/login" in page.url:
        raise WebNeedLogin(
            "小红书需要登录态才能读到笔记内容。\n"
            "请运行「登录小红书.bat」，在弹出的浏览器里登录一次；之后解析不必再登录。"
        )
    expected = (_xhs_note_id(unquote(page.url))
                or _xhs_note_id(unquote(share_url)))
    if not expected:
        raise WebFallbackError("没能从链接里识别出小红书笔记编号，请检查链接。")

    # 注意：脚本开头有换行时 `return` 后会被 ASI 自动插分号（变成 return undefined），
    # 所以必须先 strip 再拼。
    pulled = page.run_js("return " + _XHS_JS.replace("__EXPECTED_ID__", expected).strip())
    _pause_page_media(page)
    try:
        root = json.loads(pulled or "{}")
    except json.JSONDecodeError:
        raise WebFallbackError("小红书页面没有返回可识别的数据，可能页面改版了。")

    note = root.get("note")
    if not note:
        if not root.get("loggedIn"):
            raise WebNeedLogin(
                "小红书需要登录态才能读到笔记内容。\n"
                "请运行「登录小红书.bat」，在弹出的浏览器里登录一次；之后解析不必再登录。"
            )
        raise WebFallbackError(
            "小红书页面没有给出这条笔记的数据（可能已删除 / 仅自己可见）。"
        )

    note_id = str(note.get("noteId") or expected)
    if note_id != expected:
        raise WebFallbackError("页面返回的笔记不是链接指向的那一条，已中止。")

    desc = str(note.get("desc") or "")
    title = str(note.get("title") or "").strip()
    if not title and desc:
        title = desc.splitlines()[0].strip()
    user = note.get("user") or {}

    media: list[dict] = []
    live_count = 0
    for img in note.get("imageList") or []:
        if not isinstance(img, dict):
            continue
        url = _xhs_image_url(img)
        if not url:
            continue
        live = _xhs_live_url(img)
        if live:
            live_count += 1
        media.append({
            "url": url,
            "width": int(img.get("width") or 0),
            "height": int(img.get("height") or 0),
            "live": live,
            "kind": "image",
        })

    ctype = "video"
    if str(note.get("type") or "") != "video" and live_count > 0:
        ctype = "live"
    elif str(note.get("type") or "") != "video" and media:
        ctype = "images"

    video = note.get("video") or {}
    payload = {
        "id": note_id,
        "source_url": share_url,
        "title": title,
        "description": desc,
        "uploader": user.get("nickname") or "",
        "uploader_id": str(user.get("userId") or user.get("redId") or ""),
        "thumbnail": media[0]["url"] if media else "",
        "content_type": ctype,
        "media": media,
        "video_url": "",
        # 视频只把 video 段原样交回引擎：挑流规则（优先带音轨）只保留一份
        "video_raw": json.dumps(video, ensure_ascii=False) if (ctype == "video" and len(media) < 2) else "",
        "duration": _xhs_duration(video) if ctype == "video" else None,
    }
    return payload


# ==================== 微博 ====================

_WEIBO_JS = """
(function(){
  try{
    var d = window.$render_data;
    var s = d && d.status;
    if(!s) return JSON.stringify({status: null});
    function pic(p){
      var large = p.large || {};
      var geo = large.geo || {};
      var v = p.videoSrc;
      var live = '';
      if (typeof v === 'string') live = v;
      else if (v && typeof v === 'object') live = v.url || v.mp4 || '';
      return {
        url: large.url || p.url || '',
        width: Number(geo.width || p.width || 0) || 0,
        height: Number(geo.height || p.height || 0) || 0,
        live: live
      };
    }
    function pics(arr){ return (arr || []).map(pic); }
    return JSON.stringify({
      status: {
        id: String(s.id || ''),
        text: s.text || '',
        pics: pics(s.pics),
        page_info: s.page_info || {},
        user: { name: (s.user || {}).screen_name || '', id: (s.user || {}).id || '' },
        retweeted: s.retweeted_status ? {
          pics: pics(s.retweeted_status.pics),
          page_info: s.retweeted_status.page_info || {}
        } : null
      }
    });
  }catch(e){ return ''; }
})()
"""

_WEIBO_ARTICLE_JS = """
(function(){
  try{
    var title = '';
    var sels = ['.article-title', '.WBAttr', '.title', 'h1', 'h2'];
    for (var k = 0; k < sels.length; k++) {
      var el = document.querySelector(sels[k]);
      if (el && (el.innerText || '').trim()) { title = el.innerText.trim(); break; }
    }
    if (!title) title = document.title || '';
    var body = '';
    var boxSels = ['#article_content', '.WB_article_content', '.article-content',
                   '.article_content', '.WB_article_text', 'article'];
    for (var b = 0; b < boxSels.length; b++) {
      var box = document.querySelector(boxSels[b]);
      var bt = box ? (box.innerText || '').trim() : '';
      if (bt.length > 20) { body = bt; break; }
    }
    if (!body) {
      var ps = document.querySelectorAll('p'), parts = [];
      for (var j = 0; j < ps.length; j++) {
        var pt = (ps[j].innerText || '').trim();
        if (pt) parts.push(pt);
      }
      body = parts.join('\\n');
    }
    var imgs = [];
    document.querySelectorAll('img').forEach(function(img){
      var s = img.currentSrc || img.src || img.getAttribute('data-src') || '';
      if (s.indexOf('sinaimg.cn') < 0) return;
      if (s.indexOf('face.t.sinaimg.cn') >= 0) return;
      if (s.indexOf('/thumb') >= 0 || s.indexOf('/avatar') >= 0) return;
      if (imgs.indexOf(s) < 0) imgs.push(s);
    });
    return JSON.stringify({article:{title:title, content:body, images:imgs}});
  }catch(e){ return ''; }
})()
"""

_WEIBO_VIDEO_IN_TEXT = re.compile(r"video\.weibo\.com/show\?fid=([0-9A-Za-z:_-]+)")


def _weibo_mid(url: str) -> str:
    """从微博链接里取 mid：/status/<id>、/detail/<id>、weibo.com/<uid>/<mid>。"""
    m = re.search(r"/(?:status|detail)/([0-9A-Za-z]+)", url or "")
    if m:
        return m.group(1)
    m = re.search(r"weibo\.com/\d+/(?:detail/)?([0-9A-Za-z]{6,})", url or "")
    return m.group(1) if m else ""


def _weibo_plain_text(html_text: str) -> str:
    text = re.sub(r"(?i)<br\s*/?>", "\n", html_text or "")
    text = re.sub(r"<[^>]+>", "", text)
    return text.strip()


def _weibo_status_payload(root: dict, share_url: str) -> dict:
    """按 WeiboParser.buildPayload 的规则把页面数据整理成入库 payload。"""
    status = root.get("status") or {}

    media: list[dict] = []
    live_count = 0
    cover = ""

    def collect(pics, prefix: str) -> None:
        nonlocal live_count, cover
        for i, p in enumerate(pics or []):
            if not isinstance(p, dict):
                continue
            url = p.get("url") or ""
            if not url:
                continue
            if p.get("live"):
                live_count += 1
            if not cover:
                cover = url
            media.append({
                "url": url,
                "width": int(p.get("width") or 0),
                "height": int(p.get("height") or 0),
                "live": p.get("live") or "",
                "kind": "image",
                "name": f"{prefix}{i + 1}",
            })

    collect(status.get("pics"), "")
    retweeted = status.get("retweeted") or {}
    collect(retweeted.get("pics"), "转发_")

    page_info = status.get("page_info") or {}
    video_url = page_info.get("stream_url") or page_info.get("stream_url_hd") or ""

    if video_url and len(media) <= 1:
        ctype = "video"
    elif live_count > 0:
        ctype = "live"
    elif media:
        ctype = "images"
    else:
        ctype = "none"

    text = _weibo_plain_text(status.get("text"))
    if ctype == "none":
        m = _WEIBO_VIDEO_IN_TEXT.search(status.get("text") or "")
        if m:
            return {"redirect_url": f"https://video.weibo.com/show?fid={m.group(1)}"}
        raise WebFallbackError(
            "这条微博没有图片也没有视频，只有文字，没有可下载的内容。"
            if text else "这条微博里没有可下载的图片或视频。"
        )

    user = status.get("user") or {}
    title = text.splitlines()[0][:120] if text else ""
    return {
        "id": str(status.get("id") or ""),
        "source_url": share_url,
        "title": title,
        "description": text,
        "uploader": user.get("name") or "",
        "uploader_id": str(user.get("id") or ""),
        "thumbnail": cover,
        "content_type": ctype,
        "media": media,
        "video_url": video_url if ctype == "video" else "",
        "video_raw": "",
        "duration": None,
    }


def resolve_weibo(share_url: str, article: bool = False) -> dict:
    """打开微博正文 / 头条文章页，取出结构化数据。

    **正文页必须走移动版 `m.weibo.cn`**：桌面版 weibo.com 的页面里没有
    `window.$render_data`（实测 HTML 里搜不到），服务器渲染的数据只在移动版有。
    """
    page = _get_page()
    target = share_url
    if not article:
        mid = _weibo_mid(share_url)
        if not mid:
            raise WebFallbackError("没能从链接里识别出微博编号，请检查链接。")
        target = f"https://m.weibo.cn/status/{mid}"
    page.get(target)
    time.sleep(1.6)

    script = _WEIBO_ARTICLE_JS if article else _WEIBO_JS
    # DrissionPage 的 run_js 会把脚本包进 function(){}，不加 return 就永远拿不到值；
    # 而脚本开头的换行会让 `return` 被 ASI 补成 `return;`，所以还要 strip。
    pulled = page.run_js("return " + script.strip())
    _pause_page_media(page)
    try:
        root = json.loads(pulled or "{}")
    except json.JSONDecodeError:
        raise WebFallbackError("微博页面没有返回可识别的数据，可能页面改版了。")

    if article:
        art = root.get("article") or {}
        imgs = [u for u in art.get("images") or [] if u]
        if not imgs:
            raise WebFallbackError("这篇头条文章里没有取到图片（可能还在加载，稍后重试）。")
        media = [{"url": u, "width": 0, "height": 0, "live": "", "kind": "image",
                  "name": str(i + 1)} for i, u in enumerate(imgs)]
        return {
            "id": str(abs(hash(share_url))),
            "source_url": share_url,
            "title": (art.get("title") or "微博头条文章")[:120],
            "description": art.get("content") or "",
            "uploader": "",
            "uploader_id": "",
            "thumbnail": media[0]["url"],
            "content_type": "images",
            "media": media,
            "video_url": "",
            "video_raw": "",
            "duration": None,
        }

    return _weibo_status_payload(root, share_url)

# ==================== 登录助手（供「登录小红书.bat」调用）====================


def _login_session() -> None:
    """打开小红书并停住，让用户扫码/登录一次（登录态写进浏览器 profile）。"""
    page = _get_page()
    page.get("https://www.xiaohongshu.com/explore")
    time.sleep(3)

    print("")
    print("=" * 58)
    print("  浏览器已打开，请在这个窗口里登录小红书（扫码即可）")
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


if __name__ == "__main__":
    _login_session()
