"""安卓端 Python 桥接层（阶段 1 验证）。

这里放的都是「必须先在真机上拿到事实」的探针，不掺业务逻辑。

阶段 1 的三件事：
  1a 内嵌 CPython + yt-dlp 能否解析出视频信息 —— 已通过；
  1b 自带 JS 运行时后 YouTube 能否拿到完整画质档 —— 另做；
  1c 自带 ffmpeg 后 DASH 音视频合流能否成功 —— 本文件重点。

1c 之所以关键：安卓上 subprocess 属「可用但官方不支持」，实测 yt-dlp 探测 ffmpeg
（`ffmpeg -bsfs`）时 fork 出的子进程会 SIGSEGV。合流同样要走子进程，
所以必须实测「真下载 + 真合流」 quantify 它到底会不会让合流失效。

日志用 print() 输出，Chaquopy 会转到 logcat 的 python 标签下。
"""

import json
import os
import sys


def selftest() -> str:
    """子进程能力体检。"""
    import os as _os

    result = {
        "python": _python_version(),
        "has_fork": hasattr(_os, "fork"),
    }
    result["echo"] = _run(["/system/bin/echo", "hello"])
    result["missing_exe"] = _run(["/definitely/not/here"])
    print("[selftest] " + json.dumps(result, ensure_ascii=False))
    return json.dumps(result, ensure_ascii=False)


def download_and_merge(url: str, out_dir: str, ffmpeg_path: str) -> str:
    """下载一个 DASH 流并合流，然后校验产物。

    刻意选最小画质档，是因为这里要验证的是**合流链路**能不能跑通，
    不是画质 —— 小档位体积小、跑得快，但走的是完全相同的
    「视频轨 + 音频轨分别下载 → ffmpeg 合并」流程。
    """
    import yt_dlp

    os.makedirs(out_dir, exist_ok=True)
    result = {"out_dir": out_dir, "ffmpeg": ffmpeg_path, "ffmpeg_exists": os.path.exists(ffmpeg_path)}

    # 先单独确认自带的 ffmpeg 在 App 私有目录下真的能执行
    result["ffmpeg_version"] = _run([ffmpeg_path, "-hide_banner", "-version"], first_line_only=True)

    options = {
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        "ignoreconfig": True,
        "format": "worstvideo+worstaudio/worst",
        "paths": {"home": out_dir},
        "outtmpl": "%(id)s.%(ext)s",
        "merge_output_format": "mp4",
        "ffmpeg_location": ffmpeg_path,
    }

    try:
        with yt_dlp.YoutubeDL(options) as ydl:
            result["download_rc"] = ydl.download([url])
    except Exception as exc:  # noqa: BLE001
        result["download_error"] = f"{type(exc).__name__}: {exc}"

    files = []
    merged = None
    for name in sorted(os.listdir(out_dir)):
        path = os.path.join(out_dir, name)
        if not os.path.isfile(path):
            continue
        size = os.path.getsize(path)
        files.append({"name": name, "mb": round(size / 1048576, 2)})
        if name.lower().endswith(".mp4"):
            merged = path
    result["files"] = files
    result["merged_file"] = os.path.basename(merged) if merged else None

    # 用自带的 ffmpeg 读一遍合并产物的流信息：能读出视频轨+音频轨才算真的合流成功
    if merged:
        result["merged_probe"] = _run([ffmpeg_path, "-hide_banner", "-i", merged], grep_streams=True)
    else:
        result["merged_probe"] = "没有找到 mp4 产物"

    print("[merge] " + json.dumps(result, ensure_ascii=False))
    return json.dumps(result, ensure_ascii=False)


def subprocess_stress(ffmpeg_path: str, times: int = 30) -> str:
    """反复调用同一个子进程，量化安卓 fork 竞态到底有多频繁。

    这决定架构选型：yt-dlp 的合流、JS 运行时都依赖子进程。
    如果崩溃率很低（偶发且被容错），可以直接沿用 yt-dlp 的合流；
    如果很高，就得改成进程内的原生方案（MediaMuxer），不能赌。
    子进程被 SIGSEGV 杀掉时，父进程看到的 returncode 是 -11。
    """
    import subprocess

    result = {"times": times, "abs_path": {"codes": {}, "crashed": 0}, "bare_name": {"codes": {}, "crashed": 0}}

    for label, argv in (
        ("abs_path", [ffmpeg_path, "-hide_banner", "-version"]),
        ("bare_name", ["ffmpeg", "-hide_banner", "-version"]),
    ):
        bucket = result[label]
        for _ in range(times):
            try:
                proc = subprocess.run(argv, capture_output=True, timeout=30)
                code = proc.returncode
            except FileNotFoundError:
                code = "FileNotFoundError"
            except Exception as exc:  # noqa: BLE001
                code = type(exc).__name__
            bucket["codes"][str(code)] = bucket["codes"].get(str(code), 0) + 1
            if code == -11:
                bucket["crashed"] += 1

    print("[stress] " + json.dumps(result, ensure_ascii=False))
    return json.dumps(result, ensure_ascii=False)


def _run(argv: list, first_line_only: bool = False, grep_streams: bool = False) -> str:
    import subprocess

    try:
        proc = subprocess.run(argv, capture_output=True, text=True, timeout=180)
        text = (proc.stderr or "") + (proc.stdout or "")
        if first_line_only:
            lines = [ln for ln in text.splitlines() if ln.strip()]
            return f"rc={proc.returncode} | {lines[0][:80] if lines else ''}"
        if grep_streams:
            lines = [ln.strip() for ln in text.splitlines() if "Stream #" in ln]
            return f"rc={proc.returncode} | " + " || ".join(lines)[:200]
        return f"rc={proc.returncode}"
    except Exception as exc:  # noqa: BLE001
        return f"{type(exc).__name__}: {str(exc)[:80]}"


def _python_version() -> str:
    info = sys.version_info
    return f"{info.major}.{info.minor}.{info.micro}"
