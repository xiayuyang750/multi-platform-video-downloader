"""视频解析下载 — 本地服务入口。

架构（本次重构后，已去掉 pywebview 外壳）：
  - 界面：纯网页（web/ 目录），由本进程在本机起 HTTP 服务，用浏览器打开
  - 后端：本进程，负责调 yt-dlp、读写数据库、系统文件夹对话框

安全：只监听 127.0.0.1（不对外网开放）；所有 /api/ 接口都要求启动时生成的
随机 token。这样即使你在浏览器里同时打开别的网页，那些页面也无法调用本接口。
"""

from __future__ import annotations

import ipaddress
import json
import mimetypes
import secrets
import shutil
import sys
import threading
import urllib.request
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlparse

from engine import Engine

# 代理取流时伪装成浏览器，很多 CDN 会校验 UA 和 Referer
MEDIA_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)

BASE_DIR = Path(__file__).resolve().parent
WEB_DIR = BASE_DIR / "web"

HOST = "127.0.0.1"
BASE_PORT = 8787
PORT_TRIES = 20

# /media 是个「你给地址、我替你去取」的接口，要防的是「被当成跳板去探测内网」
# （SSRF）。策略是「只拦内网/本机，外部一律放行」——不用白名单，是因为 yt-dlp
# 支持上千个站点：将来新增平台的 CDN 不在名单里，会变成「能下载却播不了」。
FEEDBACK_MAIL = "xiayuyang750@gmail.com"

TOKEN = secrets.token_urlsafe(16)
ENGINE = Engine()

# 白名单：只允许界面调用这些方法
API = {
    "get_defaults": ENGINE.get_defaults,
    "get_history": ENGINE.get_history,
    "get_download_state": ENGINE.get_download_state,
    "parse_url": ENGINE.parse_url,
    "start_download": ENGINE.start_download,
    "delete_history": ENGINE.delete_history,
    "clear_history": ENGINE.clear_history,
    "clear_all_history": ENGINE.clear_all_history,
    "open_folder": ENGINE.open_folder,
    "pick_folder": ENGINE.pick_folder,
    "set_output_dir": ENGINE.set_output_dir,
    "pick_cookie_file": ENGINE.pick_cookie_file,
    "set_cookies_file": ENGINE.set_cookies_file,
    "get_feedback_mail": lambda: FEEDBACK_MAIL,
    "get_build": ENGINE.get_build,
}


class Handler(BaseHTTPRequestHandler):
    server_version = "ytdlp-gui"

    def log_message(self, fmt, *args) -> None:  # noqa: A003
        """屏蔽默认访问日志，避免刷屏。"""

    # ---------- 响应工具 ----------

    def _json(self, obj, status: int = 200) -> None:
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _static(self, rel: str) -> None:
        target = (WEB_DIR / rel).resolve()
        web_root = WEB_DIR.resolve()
        # 防路径穿越
        if target != web_root and web_root not in target.parents:
            self.send_error(403)
            return
        if not target.is_file():
            self.send_error(404)
            return

        ctype = mimetypes.guess_type(str(target))[0] or "application/octet-stream"
        if ctype.startswith("text/") or ctype in ("application/javascript", "application/json"):
            ctype += "; charset=utf-8"

        data = target.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        # 开发期禁用缓存：避免改了前端却还看到旧界面（之前踩过这个坑）
        self.send_header("Cache-Control", "no-store, must-revalidate")
        self.end_headers()
        self.wfile.write(data)

    @staticmethod
    def _token_ok(query: dict) -> bool:
        return query.get("t", [""])[0] == TOKEN

    @staticmethod
    def _host_ok(host_header: str) -> bool:
        """只接受来自本机的请求，防 DNS rebinding 攻击。

        攻击者可以让自己的域名解析到 127.0.0.1：浏览器认为「还是同一个站点」，
        于是能读到本地接口的响应（包括 /api/token 返回的 token），进而操控本程序。
        校验 Host 头能挡住这类请求——正常访问只会是 127.0.0.1 / localhost。
        """
        host = (host_header or "").split(":")[0].strip().lower()
        return host in ("127.0.0.1", "localhost", "[::1]")

    @staticmethod
    def _media_host_allowed(media_url: str) -> bool:
        """只拦内网/本机地址，外部地址一律放行。

        目的是防止 /media 被当成跳板去探测内网（SSRF），
        而不是限制能播哪些平台 —— 所以用「黑名单内网」而不是「白名单域名」。
        """
        parsed = urlparse(media_url)
        if parsed.scheme not in ("http", "https"):
            return False
        host = (parsed.hostname or "").lower()
        if host == "localhost":
            return False
        try:
            ip = ipaddress.ip_address(host)
        except ValueError:
            return True  # 是域名，放行
        return not (ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_reserved)

    def _proxy_media(self, media_url: str, referer: str) -> None:
        """代理取流：由本地服务带着正确的 Referer/UA 去上游取，再转发给浏览器。

        为什么必须代理：B站/X 等 CDN 会校验 Referer，
        浏览器直接 <video src="直链"> 会被 403，表现就是「一直转圈 + 黑屏」。
        顺带透传 Range，让播放器能拖动进度条。
        """
        # 只放行已知媒体域名，避免这个接口被当成任意地址取数的跳板
        if not self._media_host_allowed(media_url):
            self.send_error(403, "media host not allowed")
            return
        headers = {
            "User-Agent": MEDIA_UA,
            "Referer": referer or media_url,
            "Accept": "*/*",
        }
        rng = self.headers.get("Range")
        if rng:
            headers["Range"] = rng

        request = urllib.request.Request(media_url, headers=headers)
        try:
            upstream = urllib.request.urlopen(request, timeout=30)
        except Exception as exc:  # noqa: BLE001
            self.send_error(502, f"upstream error: {exc}")
            return

        with upstream:
            self.send_response(upstream.status)
            for key in ("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges"):
                value = upstream.headers.get(key)
                if value:
                    self.send_header(key, value)
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            try:
                shutil.copyfileobj(upstream, self.wfile, 64 * 1024)
            except (BrokenPipeError, ConnectionResetError, OSError):
                pass  # 用户拖动进度条/关页面时会断开，正常现象

    # ---------- 路由 ----------

    def do_GET(self) -> None:  # noqa: N802
        if not self._host_ok(self.headers.get("Host")):
            self.send_error(403, "host not allowed")
            return
        parsed = urlparse(self.path)
        path = unquote(parsed.path)

        if path == "/api/token":
            # 供界面自行获取 token，避免把 token 只绑在启动时那一次网址上
            # （手动输入地址、从历史记录点开、服务重启后旧标签页 —— 都会丢 token）。
            # 这里不返回 CORS 响应头，所以其它网站的页面即使发出请求也读不到内容，
            # 「防止别的网页偷调本地接口」这个目的仍然成立。
            self._json({"token": TOKEN})
            return

        if path == "/media":
            query = parse_qs(parsed.query)
            if not self._token_ok(query):
                self.send_error(403)
                return
            media_url = query.get("u", [""])[0]
            if not media_url:
                self.send_error(400)
                return
            self._proxy_media(media_url, query.get("ref", [""])[0])
            return

        if path.startswith("/api/"):
            if not self._token_ok(parse_qs(parsed.query)):
                self._json({"error": "token 无效"}, 403)
                return
            self._json({"error": f"未知接口 {path}"}, 404)
            return

        self._static(path.lstrip("/") or "index.html")

    def do_POST(self) -> None:  # noqa: N802
        if not self._host_ok(self.headers.get("Host")):
            self.send_error(403, "host not allowed")
            return
        parsed = urlparse(self.path)
        if not self._token_ok(parse_qs(parsed.query)):
            self._json({"error": "token 无效"}, 403)
            return
        if parsed.path != "/api/call":
            self._json({"error": "未知接口"}, 404)
            return

        try:
            length = int(self.headers.get("Content-Length") or 0)
            payload = json.loads(self.rfile.read(length) or b"{}")
        except (ValueError, json.JSONDecodeError):
            self._json({"error": "请求体不是有效 JSON"}, 400)
            return

        method = payload.get("method") or ""
        args = payload.get("args") or []
        func = API.get(method)
        if func is None:
            self._json({"error": f"未知方法：{method}"}, 404)
            return

        try:
            self._json(func(*args))
        except TypeError as exc:
            self._json({"error": f"参数不匹配：{exc}"}, 400)
        except Exception as exc:  # 把异常回传给界面，而不是静默失败
            self._json({"error": f"{type(exc).__name__}: {exc}"}, 500)


class LocalServer(ThreadingHTTPServer):
    """本地服务。

    allow_reuse_address 必须关掉。Windows 上开启它会让**多个进程同时绑定同一个
    端口**，请求随机被其中一个接走 —— 于是出现「界面带的 token 对不上」「改了
    代码却没生效」这类看起来毫无规律的问题。关掉之后，重复启动会各自拿到不同
    端口，彼此不干扰。
    """

    allow_reuse_address = False


def make_server() -> ThreadingHTTPServer:
    """端口被占用时自动往后试，避免启动失败。"""
    for port in range(BASE_PORT, BASE_PORT + PORT_TRIES):
        try:
            srv = LocalServer((HOST, port), Handler)
        except OSError:
            continue
        if port != BASE_PORT:
            print(f"  注意：{BASE_PORT} 已被占用，说明之前已经启动过一个实例。")
            print(f"        本实例改用 {port}，两个界面互不影响；")
            print("        如果不需要旧的那个，请把它的窗口关掉。")
        return srv
    raise SystemExit(
        f"{BASE_PORT}~{BASE_PORT + PORT_TRIES} 之间没有可用端口。\n"
        "请先关掉之前启动的窗口，或结束残留的 python 进程，再重新启动。"
    )


def main() -> None:
    server = make_server()
    port = server.server_address[1]
    url = f"http://{HOST}:{port}/?t={TOKEN}"

    print("=" * 56)
    print("  视频解析下载 已启动")
    print(f"  界面地址：{url}")
    print("  关闭这个窗口（或按 Ctrl+C）即停止服务")
    print("=" * 56)
    sys.stdout.flush()  # 确保地址立刻显示出来

    # 稍等一下再开浏览器，确保服务已经进入监听。
    # --no-browser 用于调试/自动化：只起服务、不打开浏览器。
    if "--no-browser" not in sys.argv:
        threading.Timer(0.6, lambda: webbrowser.open(url)).start()

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止。")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()