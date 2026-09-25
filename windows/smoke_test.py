"""冒烟测试：不启动界面，直接验证后端能否解析链接并写入历史。

用法：
    .venv\\Scripts\\python.exe smoke_test.py            测 B站（不需要 VPN）
    .venv\\Scripts\\python.exe smoke_test.py <链接>     测指定链接
    .venv\\Scripts\\python.exe smoke_test.py --history  查看历史库里存了什么
"""

import sys

from engine import Engine

DEFAULT_URL = "https://www.bilibili.com/video/BV1ckhW6DErb/"


def show_history(engine: Engine) -> None:
    """打印历史记录的关键字段，用于排查「为什么播不了」。"""
    records = engine.get_history()
    print(f"共 {len(records)} 条")
    for r in records:
        print(
            f"  {str(r['platform']):10} play_kind={str(r['play_kind']):12} "
            f"has_url={bool(r['resolved_url'])}  {str(r['title'])[:28]}"
        )


def main() -> None:
    arg = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_URL

    engine = Engine()

    if arg == "--history":
        show_history(engine)
        return

    print("测试链接 =", arg)
    result = engine.parse_url(arg)

    print("ok          =", result.get("ok"))
    print("error       =", result.get("error"))
    print("id          =", result.get("id"))
    print("platform    =", result.get("platform"))
    print("quality     =", result.get("quality"))
    print("filesize    =", result.get("filesize"))
    print("title chars =", len(result.get("title") or ""))
    print("play_url    =", bool(result.get("resolved_url")))
    print("play_kind   =", result.get("play_kind"))

    print()
    print("--- 下载命令预览（只组装，不执行）---")
    try:
        cmd = engine.build_download_command(engine.get_defaults(), arg)
        print(" ".join(cmd))
    except Exception as exc:
        print("构建失败:", exc)


if __name__ == "__main__":
    main()