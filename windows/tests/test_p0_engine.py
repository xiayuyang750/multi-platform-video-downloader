# -*- coding: utf-8 -*-
"""P0 数据层与下载收尾的单元测试（不打网络、不碰真实用户数据）。

运行：
    .venv\\Scripts\\python.exe -m unittest tests.test_p0_engine -v
"""

from __future__ import annotations

import os
import sqlite3
import sys
import tempfile
import time
import unittest
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
if str(BASE_DIR) not in sys.path:
    sys.path.insert(0, str(BASE_DIR))

import engine as eng  # noqa: E402


class TempDbCase(unittest.TestCase):
    """每个用例独立一份 sqlite，避免污染 %APPDATA% 里的真实历史。"""

    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.db_path = Path(self.tmp.name) / "history.db"
        self._old_db = eng.DB_FILE
        eng.DB_FILE = self.db_path
        self.addCleanup(self._restore)

    def _restore(self) -> None:
        eng.DB_FILE = self._old_db


class TestStoreMigration(TempDbCase):
    def test_old_table_gets_new_columns(self) -> None:
        """老库（12 列）打开后必须自动补齐 6 列，且原有数据不丢。"""
        conn = sqlite3.connect(self.db_path)
        conn.execute(
            """
            CREATE TABLE history (
                id TEXT PRIMARY KEY, platform TEXT NOT NULL, title TEXT,
                uploader TEXT, duration INTEGER, thumbnail TEXT,
                source_url TEXT NOT NULL, resolved_url TEXT, resolved_at INTEGER,
                play_kind TEXT, uploader_id TEXT, download_url TEXT
            )
            """
        )
        conn.execute(
            "INSERT INTO history (id, platform, source_url, title) "
            "VALUES ('a', '抖音', 'https://douyin.com/x', '老记录')"
        )
        conn.commit()
        conn.close()

        store = eng.Store()
        self.addCleanup(store.conn.close)
        cols = {row["name"] for row in store.conn.execute("PRAGMA table_info(history)")}
        for name in ("local_path", "content_type", "media_json",
                     "source_md5", "file_md5", "description"):
            self.assertIn(name, cols, f"缺少列 {name}")

        rows = store.all()
        self.assertEqual(1, len(rows))
        self.assertEqual("老记录", rows[0]["title"])
        self.assertIsNone(rows[0]["local_path"])


class TestStoreUpsert(TempDbCase):
    def test_description_kept_when_new_is_empty(self) -> None:
        """备用链路只给标题、不给正文时，不能把上次存的正文冲掉。"""
        store = eng.Store()
        self.addCleanup(store.conn.close)
        store.upsert({
            "id": "1", "platform": "微博", "title": "t", "description": "完整正文",
            "uploader": "", "uploader_id": "", "duration": None, "thumbnail": "",
            "source_url": "https://weibo.com/1", "resolved_url": "", "download_url": "",
            "resolved_at": 1, "play_kind": "none",
        })
        store.upsert({
            "id": "1", "platform": "微博", "title": "t", "description": "",
            "uploader": "", "uploader_id": "", "duration": None, "thumbnail": "",
            "source_url": "https://weibo.com/1", "resolved_url": "", "download_url": "",
            "resolved_at": 2, "play_kind": "none",
        })
        self.assertEqual("完整正文", store.all()[0]["description"])

    def test_upsert_does_not_wipe_file_md5_or_local_path(self) -> None:
        """重新解析同一条时，已经算好的成品 MD5 与产物路径必须留着。"""
        store = eng.Store()
        self.addCleanup(store.conn.close)
        rec = {
            "id": "2", "platform": "抖音", "title": "t", "description": "",
            "uploader": "", "uploader_id": "", "duration": None, "thumbnail": "",
            "source_url": "https://v.douyin.com/2", "resolved_url": "", "download_url": "",
            "resolved_at": 1, "play_kind": "none",
        }
        store.upsert(rec)
        store.set_local_path(rec["source_url"], r"D:\Download\a.mp4")
        store.set_file_md5(r"D:\Download\a.mp4", "deadbeef")
        store.upsert({**rec, "resolved_at": 2})

        row = store.all()[0]
        self.assertEqual(r"D:\Download\a.mp4", row["local_path"])
        self.assertEqual("deadbeef", row["file_md5"])


class TestEngineHistory(TempDbCase):
    def _engine(self) -> eng.Engine:
        e = eng.Engine()
        self.addCleanup(lambda: e.store.conn.close())
        return e

    def test_missing_local_file_is_cleared_in_result(self) -> None:
        """产物被用户手动删掉后，历史接口不能仍报「已下载」。"""
        e = self._engine()
        gone = Path(self.tmp.name) / "gone.mp4"
        e.store.upsert({
            "id": "3", "platform": "B站", "title": "t", "description": "",
            "uploader": "", "uploader_id": "", "duration": None, "thumbnail": "",
            "source_url": "https://bilibili.com/3", "resolved_url": "", "download_url": "",
            "resolved_at": 1, "play_kind": "none",
        })
        e.store.set_local_path("https://bilibili.com/3", str(gone))
        rows = e.get_history()
        self.assertEqual("", rows[0]["local_path"])

    def test_existing_local_file_is_kept(self) -> None:
        e = self._engine()
        alive = Path(self.tmp.name) / "alive.mp4"
        alive.write_bytes(b"x")
        e.store.upsert({
            "id": "4", "platform": "B站", "title": "t", "description": "",
            "uploader": "", "uploader_id": "", "duration": None, "thumbnail": "",
            "source_url": "https://bilibili.com/4", "resolved_url": "", "download_url": "",
            "resolved_at": 1, "play_kind": "none",
        })
        e.store.set_local_path("https://bilibili.com/4", str(alive))
        rows = e.get_history()
        self.assertEqual(str(alive), rows[0]["local_path"])


class TestFinishDownload(TempDbCase):
    def test_records_path_and_md5(self) -> None:
        """下载收尾：按原链接回填 local_path，并算出成品 MD5。"""
        e = eng.Engine()
        self.addCleanup(lambda: e.store.conn.close())
        src = "https://v.douyin.com/5"
        e.store.upsert({
            "id": "5", "platform": "抖音", "title": "t", "description": "",
            "uploader": "", "uploader_id": "", "duration": None, "thumbnail": "",
            "source_url": src, "resolved_url": "", "download_url": "",
            "resolved_at": 1, "play_kind": "none",
        })
        out = Path(self.tmp.name) / "成品.mp4"
        out.write_bytes(b"hello")

        info = e._finish_download(src, str(out))
        self.assertEqual("5d41402abc4b2a76b9719d911017c592", info["md5"])
        row = e.store.all()[0]
        self.assertEqual(str(out), row["local_path"])
        self.assertEqual("5d41402abc4b2a76b9719d911017c592", row["file_md5"])

        snap = e.dl_state.snapshot()
        self.assertEqual(str(out), snap["path"])
        self.assertEqual("5d41402abc4b2a76b9719d911017c592", snap["md5"])


class TestProgressParsing(unittest.TestCase):
    def test_normal(self) -> None:
        got = eng.parse_progress("45.6%|1234567.0|00:12|1234567|9876543")
        self.assertAlmostEqual(45.6, got["percent"], places=1)
        self.assertEqual(1234567, got["speed"])
        self.assertEqual(12, got["eta"])
        self.assertEqual(1234567, got["downloaded"])
        self.assertEqual(9876543, got["total"])

    def test_long_eta(self) -> None:
        got = eng.parse_progress("10.0%|1024|1:02:03|1024|10240")
        self.assertEqual(3723, got["eta"])

    def test_na_fields(self) -> None:
        got = eng.parse_progress("NA|NA|NA|NA|NA")
        self.assertIsNone(got["percent"])
        self.assertEqual(0, got["speed"])
        self.assertIsNone(got["eta"])
        self.assertEqual(0, got["downloaded"])
        self.assertEqual(0, got["total"])

    def test_garbage_does_not_raise(self) -> None:
        got = eng.parse_progress("")
        self.assertIsNone(got["percent"])


class TestPlayablePath(TempDbCase):
    def test_inside_output_dir_allowed(self) -> None:
        root = Path(self.tmp.name) / "Download"
        root.mkdir()
        f = root / "a.mp4"
        f.write_bytes(b"x")
        self.assertTrue(eng.path_in_dir(str(f), str(root)))

    def test_outside_denied(self) -> None:
        root = Path(self.tmp.name) / "Download"
        root.mkdir()
        other = Path(self.tmp.name) / "secret.txt"
        other.write_bytes(b"x")
        self.assertFalse(eng.path_in_dir(str(other), str(root)))

    def test_sibling_prefix_not_confused(self) -> None:
        """Download2 不能被当成 Download 的子目录。"""
        root = Path(self.tmp.name) / "Download"
        root.mkdir()
        sibling = Path(self.tmp.name) / "Download2" / "a.mp4"
        sibling.parent.mkdir()
        sibling.write_bytes(b"x")
        self.assertFalse(eng.path_in_dir(str(sibling), str(root)))

    def test_history_local_path_allowed_outside_output_dir(self) -> None:
        """用户改过输出目录后，老产物仍要能播（以历史库记录为准）。"""
        e = eng.Engine()
        self.addCleanup(lambda: e.store.conn.close())
        f = Path(self.tmp.name) / "old" / "a.mp4"
        f.parent.mkdir()
        f.write_bytes(b"x")
        e.store.upsert({
            "id": "6", "platform": "抖音", "title": "t", "description": "",
            "uploader": "", "uploader_id": "", "duration": None, "thumbnail": "",
            "source_url": "https://v.douyin.com/6", "resolved_url": "", "download_url": "",
            "resolved_at": 1, "play_kind": "none",
        })
        e.store.set_local_path("https://v.douyin.com/6", str(f))
        e.get_defaults = lambda: {"download": {"output_dir": str(Path(self.tmp.name) / "new")}}
        self.assertTrue(e.is_playable_path(str(f)))


class TestDownloadState(unittest.TestCase):
    def test_structured_defaults(self) -> None:
        st = eng.DownloadState()
        d = st.snapshot()
        for key in ("type", "active", "percent", "speed", "eta",
                    "downloaded", "total", "url", "path", "message"):
            self.assertIn(key, d, f"下载状态缺少字段 {key}")
        self.assertIsNone(d["percent"])

    def test_seq_increments(self) -> None:
        st = eng.DownloadState()
        st.update(type="progress", active=True, percent=12.5)
        st.update(type="done", active=False)
        snap = st.snapshot()
        self.assertEqual(2, snap["seq"])
        self.assertEqual("done", snap["type"])


class TestParseTask(TempDbCase):
    def test_start_and_poll_fake_parse(self) -> None:
        """解析任务化：start_parse 立即返回，结果通过轮询拿到。"""
        e = eng.Engine()
        self.addCleanup(lambda: e.store.conn.close())

        fake = {"ok": True, "id": "x", "title": "假结果"}
        e.parse_url = lambda url: fake
        res = e.start_parse("https://example.com/x")
        self.assertTrue(res["ok"])

        deadline = time.time() + 5
        snap = e.get_parse_state()
        while time.time() < deadline:
            snap = e.get_parse_state()
            if not snap["active"]:
                break
            time.sleep(0.05)
        self.assertTrue(snap["result"]["ok"])
        self.assertEqual("假结果", snap["result"]["title"])

    def test_second_start_rejected_while_busy(self) -> None:
        e = eng.Engine()
        self.addCleanup(lambda: e.store.conn.close())

        def slow(url: str):
            time.sleep(0.6)
            return {"ok": True}

        e.parse_url = slow
        self.assertTrue(e.start_parse("https://example.com/a")["ok"])
        second = e.start_parse("https://example.com/b")
        self.assertFalse(second["ok"])
        self.assertIn("正在", second["error"])

    def test_cancel_sets_state(self) -> None:
        e = eng.Engine()
        self.addCleanup(lambda: e.store.conn.close())

        def slow(url: str):
            for _ in range(20):
                if e._parse_cancel:
                    return {"ok": False, "error": "已取消"}
                time.sleep(0.05)
            return {"ok": True}

        e.parse_url = slow
        e.start_parse("https://example.com/c")
        time.sleep(0.1)
        e.cancel_parse()
        time.sleep(0.6)
        self.assertEqual("cancelled", e.get_parse_state()["state"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
