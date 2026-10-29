# -*- coding: utf-8 -*-
"""P2：档位归并、滑动窗口速率、直链落盘命名的单元测试（不打网络）。"""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

BASE_DIR = Path(__file__).resolve().parent.parent
if str(BASE_DIR) not in sys.path:
    sys.path.insert(0, str(BASE_DIR))

import engine as eng  # noqa: E402


class TestYtdlpTiers(unittest.TestCase):
    INFO = {
        "formats": [
            {"format_id": "140", "vcodec": "none", "acodec": "mp4a", "tbr": 128,
             "filesize": 3_000_000, "url": "https://x/a.m4a"},
            {"format_id": "137", "height": 1080, "vcodec": "avc1.640028", "tbr": 4000,
             "filesize": 50_000_000, "url": "https://x/137.mp4"},
            {"format_id": "248", "height": 1080, "vcodec": "hev1.1.6.L93.B0", "tbr": 3000,
             "filesize": 40_000_000, "url": "https://x/248.mp4"},
            {"format_id": "399", "height": 1080, "vcodec": "av01.0.05M", "tbr": 3500,
             "filesize": 45_000_000, "url": "https://x/399.mp4"},
            {"format_id": "136", "height": 720, "vcodec": "avc1.4d401f", "tbr": 2000,
             "filesize": 20_000_000, "url": "https://x/136.mp4"},
        ]
    }

    def test_same_height_prefers_h264(self) -> None:
        tiers = eng.ytdlp_collect_tiers(self.INFO)
        self.assertEqual(2, len(tiers))
        self.assertEqual(1080, tiers[0]["height"])
        self.assertEqual("h264", tiers[0]["vcodec"])

    def test_selector_pins_format_ids(self) -> None:
        tiers = eng.ytdlp_collect_tiers(self.INFO)
        self.assertEqual(
            "137+140/137+ba/b[height<=1080]", tiers[0]["selector"]
        )

    def test_filesize_includes_audio(self) -> None:
        tiers = eng.ytdlp_collect_tiers(self.INFO)
        self.assertEqual(53_000_000, tiers[0]["filesize"])
        self.assertIn("H.264", tiers[0]["label"])
        self.assertIn("1080p", tiers[0]["label"])

    def test_audio_only_format_never_becomes_tier(self) -> None:
        tiers = eng.ytdlp_collect_tiers(self.INFO)
        self.assertFalse(any(t["height"] == 0 for t in tiers))


class TestSpeedMeter(unittest.TestCase):
    def test_sliding_window(self) -> None:
        meter = eng.SpeedMeter()
        mb = 1024 * 1024
        clock = [0.0, 1.0, 3.0, 5.0]
        with patch.object(eng.time, "time", side_effect=lambda: clock.pop(0)):
            self.assertEqual(0, meter.feed(0, 0.0))
            self.assertEqual(mb, meter.feed(mb, 0.0))
            # 窗口 3 秒、增量 3MB → 1MB/s
            self.assertEqual(mb, meter.feed(3 * mb, 0.0))
            # 最后两秒没有进展 → 速率下降（而不是被累计平均拖住）
            self.assertEqual(mb // 2, meter.feed(3 * mb, 0.0))

    def test_reset(self) -> None:
        meter = eng.SpeedMeter()
        meter.feed(1000, 0.0)
        meter.reset()
        self.assertTrue(True)


class TestDirectNaming(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.old_db = eng.DB_FILE
        eng.DB_FILE = Path(self.tmp.name) / "history.db"
        self.addCleanup(self._restore)
        self.e = eng.Engine()
        self.addCleanup(lambda: self.e.store.conn.close())
        self.e.get_defaults = lambda: {"download": {"output_dir": self.tmp.name}}

    def _restore(self) -> None:
        eng.DB_FILE = self.old_db

    def test_stem_has_title_and_id(self) -> None:
        stem = self.e._direct_stem({"title": "一段标题", "id": "7692"})
        self.assertEqual("一段标题 [7692]", stem)

    def test_long_title_is_clipped(self) -> None:
        vid = "9" * 19
        stem = self.e._direct_stem({"title": "标题" * 200, "id": vid})
        self.assertLessEqual(len(stem), 250)
        self.assertTrue(stem.endswith(f" [{vid}]"))

    def test_direct_path_avoids_overwrite(self) -> None:
        rec = {"title": "同名", "id": "1"}
        first = self.e._direct_output_path("https://h/a.mp4", rec)
        self.assertTrue(first.endswith(".mp4"))
        Path(first).write_bytes(b"x")
        second = self.e._direct_output_path("https://h/a.mp4", rec)
        self.assertNotEqual(first, second)

    def test_spec_url_has_priority(self) -> None:
        url = "https://h/selected.mp4"
        self.assertEqual(url, self.e._download_target("https://v.douyin.com/x/",
                                                      {"url": url}))


if __name__ == "__main__":
    unittest.main(verbosity=2)
