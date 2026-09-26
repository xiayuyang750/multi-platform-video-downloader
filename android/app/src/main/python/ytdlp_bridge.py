"""安卓端 Python 桥接层（阶段 1 验证）。

这里放的都是「必须先在真机上拿到事实」的探针，不掺业务逻辑。

当前重点验证两件事：
  1. 内嵌 CPython + yt-dlp 能否真的解析出视频信息；
  2. 子进程能力 —— yt-dlp 的 ffmpeg 合流、JS 运行时调用全都走子进程。
     基础测试显示 subprocess 可用，但解析过程中偶发 SIGSEGV，
     所以要记录「yt-dlp 到底尝试执行了哪些子进程命令」，定位崩溃来源。

日志用 print() 输出，Chaquopy 会转到 logcat 的 python 标签下，便于用 adb 抓。
"""

import json
import sys


def selftest() -> str:
    """子进程能力体检。每一项都单独 try/except，避免一项失败掩盖其他项。"""
    import os

    result = {
        "python": _python_version(),
        "has_fork": hasattr(os, "fork"),
        "has_posix_spawn": hasattr(os, "posix_spawn"),
    }
    result["echo"] = _run(["/system/bin/echo", "hello"])
    result["missing_exe"] = _run(["/definitely/not/here"])
    result["shell"] = _run(["/system/bin/sh", "-c", "echo shell-ok"])

    print("[selftest] " + json.dumps(result, ensure_ascii=False))
    return json.dumps(result, ensure_ascii=False)


def probe(url: str) -> str:
    """解析链接，并记录 yt-dlp 在过程中尝试过的所有子进程命令。"""
    import subprocess

    import yt_dlp

    # 拦一层 Popen，把 yt-dlp 想执行的命令全记下来 —— 崩溃就发生在这些命令上，
    # 不记录的话只能看到「某个子进程崩了」，无法定位是哪条。
    calls = []
    original_init = subprocess.Popen.__init__

    def traced_init(self, args, *rest, **kwargs):
        calls.append(" ".join(str(a) for a in args) if isinstance(args, (list, tuple)) else str(args))
        return original_init(self, args, *rest, **kwargs)

    subprocess.Popen.__init__ = traced_init

    options = {
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        "ignoreconfig": True,
    }
    try:
        with yt_dlp.YoutubeDL(options) as ydl:
            info = ydl.extract_info(url, download=False)
        formats = info.get("formats") or []
        payload = {
            "ok": True,
            "yt_dlp_version": yt_dlp.version.__version__,
            "extractor": info.get("extractor_key") or info.get("extractor"),
            "id": info.get("id"),
            "title": info.get("title"),
            "format_count": len(formats),
            "python": _python_version(),
            "subprocess_calls": calls,
        }
    except Exception as exc:  # noqa: BLE001
        payload = {
            "ok": False,
            "error": f"{type(exc).__name__}: {exc}",
            "python": _python_version(),
            "subprocess_calls": calls,
        }
    finally:
        subprocess.Popen.__init__ = original_init

    print("[probe] " + json.dumps(payload, ensure_ascii=False))
    return json.dumps(payload, ensure_ascii=False)


def _run(argv: list) -> str:
    import subprocess

    try:
        proc = subprocess.run(argv, capture_output=True, text=True, timeout=15)
        return f"rc={proc.returncode} out={(proc.stdout or '').strip()[:60]!r} err={(proc.stderr or '').strip()[:60]!r}"
    except Exception as exc:  # noqa: BLE001
        return f"{type(exc).__name__}: {str(exc)[:80]}"


def _python_version() -> str:
    info = sys.version_info
    return f"{info.major}.{info.minor}.{info.micro}"
