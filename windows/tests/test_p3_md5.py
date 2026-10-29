# -*- coding: utf-8 -*-
"""P3：MD5 改写（视频重封装 / PNG tEXt / JPEG COM）的单元测试。

依赖 ffmpeg（PATH 或 config 的 engine.ffmpeg_dir）；找不到就自动跳过。
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
if str(BASE_DIR) not in sys.path:
    sys.path.insert(0, str(BASE_DIR))

import engine as eng  # noqa: E402

FFMPEG = shutil.which("ffmpeg") or ""


def _run(cmd: list[str]) -> int:
    return subprocess.run(cmd, capture_output=True).returncode


def make_mp4(dst: Path) -> bool:
    cmd = [FFMPEG, "-y", "-hide_banner", "-loglevel", "error",
           "-f", "lavfi", "-i", "testsrc=size=160x90:rate=15",
           "-t", "1", "-c:v", "mpeg4", "-pix_fmt", "yuv420p", str(dst)]
    return _run(cmd) == 0 and dst.is_file()


def make_image(dst: Path) -> bool:
    cmd = [FFMPEG, "-y", "-hide_banner", "-loglevel", "error",
           "-f", "lavfi", "-i", "color=c=red:s=64x64", "-frames:v", "1", str(dst)]
    return _run(cmd) == 0 and dst.is_file()


def decodes(path: Path) -> bool:
    """用 ffmpeg 真解码一遍（0 退出码 = 容器与编码都还正常）。"""
    if path.suffix.lower() in (".png", ".jpg", ".jpeg"):
        cmd = [FFMPEG, "-v", "error", "-i", str(path), "-frames:v", "1", "-f", "null", "-"]
    else:
        cmd = [FFMPEG, "-v", "error", "-i", str(path), "-f", "null", "-"]
    return _run(cmd) == 0


@unittest.skipUnless(FFMPEG, "没有 ffmpeg，跳过 MD5 改写测试")
class TestRewrite(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dir = Path(self.tmp.name)
        self._old = (eng.DB_FILE, eng.DATA_DIR, eng.SETTINGS_FILE)
        eng.DB_FILE = self.dir / "history.db"
        eng.DATA_DIR = self.dir / "data"
        eng.SETTINGS_FILE = eng.DATA_DIR / "settings.json"
        self.addCleanup(self._restore)
        self.e = eng.Engine()
        self.addCleanup(lambda: self.e.store.conn.close())
        self.e.get_defaults = lambda: {
            "download": {"output_dir": str(self.dir)},
            "engine": {"ffmpeg_dir": str(Path(FFMPEG).parent)},
        }

    def _restore(self) -> None:
        eng.DB_FILE, eng.DATA_DIR, eng.SETTINGS_FILE = self._old

    def _rewrite(self, src: Path) -> dict:
        before = eng.compute_file_md5(str(src))
        r = self.e.rewrite_md5(str(src), str(self.dir / f"out{src.suffix}"))
        self.assertEqual(before, eng.compute_file_md5(str(src)), "原件被改动了")
        self.assertTrue(r["ok"], r.get("error"))
        self.assertNotEqual(r["before"], r["after"], "MD5 没有变化")
        out = Path(r["path"])
        self.assertTrue(decodes(out), "改写后的文件无法解码")
        return r

    def test_png(self) -> None:
        src = self.dir / "a.png"
        self.assertTrue(make_image(src))
        self._rewrite(src)

    def test_jpeg(self) -> None:
        src = self.dir / "a.jpg"
        self.assertTrue(make_image(src))
        self._rewrite(src)

    def test_video(self) -> None:
        src = self.dir / "a.mp4"
        self.assertTrue(make_mp4(src))
        self._rewrite(src)

    def test_png_chunk_structure(self) -> None:
        """tEXt 的长度字段只算数据段，CRC 覆盖「类型 + 数据」。"""
        src = self.dir / "b.png"
        make_image(src)
        r = self.e.rewrite_md5(str(src), str(self.dir / "b2.png"))
        data = Path(r["path"]).read_bytes()
        pos = 8
        first = data[pos + 4:pos + 8]
        self.assertEqual(b"IHDR", first)
        # IHDR 之后应当紧跟 tEXt
        length = int.from_bytes(data[pos:pos + 4], "big")
        nxt = pos + 12 + length          # 下一个块的起点（它自己的长度字段）
        self.assertEqual(b"tEXt", data[nxt + 4:nxt + 8])

    def test_finish_download_rewrites_when_enabled(self) -> None:
        src = self.dir / "v.mp4"
        self.assertTrue(make_mp4(src))
        eng.save_user_settings({"rewrite_md5_on_download": True})
        self.e.store.upsert({
            "id": "1", "platform": "抖音", "title": "t", "description": "",
            "uploader": "", "uploader_id": "", "duration": None, "thumbnail": "",
            "source_url": "https://v/1", "resolved_url": "", "download_url": "",
            "resolved_at": 1, "play_kind": "none",
        })
        info = self.e._finish_download("https://v/1", str(src))
        snap = self.e.dl_state.snapshot()
        self.assertTrue(snap["md5"])
        self.assertTrue(snap["md5_after"])
        self.assertNotEqual(snap["md5"], snap["md5_after"])
        self.assertEqual(snap["md5_after"], info["md5"])
        self.assertEqual(snap["md5_after"], self.e.store.all()[0]["file_md5"])
        self.assertTrue(decodes(Path(snap["path"])))

    def test_media_info_probes_dimensions(self) -> None:
        src = self.dir / "m.mp4"
        self.assertTrue(make_mp4(src))
        info = self.e.media_info(str(src))
        self.assertTrue(info["ok"])
        self.assertEqual(160, info["width"])
        self.assertEqual(90, info["height"])
        self.assertGreater(info["duration"] or 0, 0)
        self.assertEqual("video", info["kind"])
        self.assertTrue(self.e.is_playable_path(str(src)), "挑中的文件应允许预览")

    def test_md5_edit_writes_cache_copy(self) -> None:
        src = self.dir / "c.png"
        make_image(src)
        r = self.e.md5_edit(str(src))
        self.assertTrue(r["ok"], r.get("error"))
        self.assertNotEqual(r["before"], r["after"])
        self.assertTrue(Path(r["path"]).is_file())
        self.assertIn("md5", str(r["path"]))
        self.assertTrue(self.e.is_playable_path(r["path"]))

    def test_image_not_present(self) -> None:
        r = self.e.rewrite_md5(str(self.dir / "missing.png"))
        self.assertFalse(r["ok"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
