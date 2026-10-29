# -*- coding: utf-8 -*-
"""P1 平台/内容形态的单元测试（不打网络）。

运行：
    .venv\\Scripts\\python.exe -m unittest tests.test_p1_platforms -v
"""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
if str(BASE_DIR) not in sys.path:
    sys.path.insert(0, str(BASE_DIR))

import douyin_fallback  # noqa: E402
import engine as eng  # noqa: E402
import x_fallback  # noqa: E402


class TestPlatformRules(unittest.TestCase):
    def test_new_platforms(self) -> None:
        self.assertEqual("快手", eng.detect_platform("https://v.kuaishou.com/JoV746vn"))
        self.assertEqual("快手", eng.detect_platform(
            "https://v.m.chenzhongtech.com/fw/photo/3xabc"))
        self.assertEqual("微信公众号", eng.detect_platform(
            "https://mp.weixin.qq.com/s/dCg1AbYfNPtn2KYMqbQutw"))
        self.assertEqual("微信视频号", eng.detect_platform(
            "https://weixin.qq.com/sph/AKczRTNiWa"))
        self.assertEqual("微信视频号", eng.detect_platform(
            "https://channels.weixin.qq.com/finder-preview/pages/sph?id=x"))

    def test_known_platforms_still_work(self) -> None:
        self.assertEqual("YouTube", eng.detect_platform("https://youtu.be/abc"))
        self.assertEqual("YouTube", eng.detect_platform("https://www.youtube.com/watch?v=x"))
        self.assertEqual("B站", eng.detect_platform("https://www.bilibili.com/video/BV1x"))
        self.assertEqual("小红书", eng.detect_platform("https://xhslink.cn/o/abc"))

    def test_lookalike_domain_is_not_matched(self) -> None:
        """恶意子域名不能蹭平台识别（域名后必须紧跟分隔符或结束）。"""
        self.assertEqual("其他", eng.detect_platform("https://xhslink.cn.attacker.com/o/x"))
        self.assertEqual("其他", eng.detect_platform("https://mp.weixin.qq.com.attacker.com/s/x"))
        self.assertEqual("其他", eng.detect_platform("https://youtube.com.attacker.com/watch"))

    def test_looks_like_url(self) -> None:
        self.assertTrue(eng.looks_like_url("https://v.douyin.com/abc/"))
        self.assertFalse(eng.looks_like_url("这不是链接"))
        self.assertFalse(eng.looks_like_url("abc://def"))


class TestTextNorm(unittest.TestCase):
    def test_title_truncated_to_limit(self) -> None:
        raw = "标题" * 40
        title = eng._norm_title(raw)
        self.assertEqual(eng.TITLE_MAX_CHARS, len(title) - 1)
        self.assertTrue(title.endswith("…"))

    def test_empty_title_falls_back_to_body(self) -> None:
        # 与安卓端同一条规则：正文会被压成一行，不是只取第一段
        self.assertEqual("第一段正文 第二段", eng._norm_title("", "第一段正文\n\n第二段"))

    def test_description_dropped_when_same_as_short_title(self) -> None:
        self.assertEqual("", eng._norm_description("同一段话", "同一段话"))

    def test_description_kept_when_title_is_long(self) -> None:
        body = "很长的一段话" * 20
        self.assertTrue(eng._norm_description(body, body))


class TestDouyinTiers(unittest.TestCase):
    VIDEO = {
        "play_addr": {"url_list": ["https://d/default.mp4"], "height": 576,
                      "data_size": 5_000_000, "file_hash": "h-default"},
        "bit_rate": [
            {"is_h265": 1, "format": "mp4", "bit_rate": 1000, "data_size": 3_000_000,
             "play_addr": {"height": 720, "url_list": ["https://d/720h265.mp4"],
                           "file_hash": "h-720-265"}},
            {"is_h265": 0, "format": "mp4", "bit_rate": 800,
             "play_addr": {"height": 720, "url_list": ["https://d/720h264.mp4"],
                           "file_hash": "h-720-264"}},
            {"is_h265": 1, "format": "mp4", "bit_rate": 2000,
             "play_addr": {"height": 1080, "url_list": ["https://d/1080h265.mp4"],
                           "file_hash": "h-1080-265"}},
        ],
    }

    def test_order_is_resolution_then_h264(self) -> None:
        tiers = eng.douyin_collect_tiers(self.VIDEO)
        self.assertEqual("https://d/1080h265.mp4", tiers[0]["url"])
        self.assertEqual("https://d/720h264.mp4", tiers[1]["url"])
        self.assertEqual("https://d/720h265.mp4", tiers[2]["url"])
        # 默认档不在 bit_rate 里，必须补成单独一档
        self.assertEqual("https://d/default.mp4", tiers[3]["url"])
        self.assertIn("平台默认档", tiers[3]["label"])

    def test_platform_hash_exposed(self) -> None:
        tiers = eng.douyin_collect_tiers(self.VIDEO)
        self.assertEqual("h-1080-265", tiers[0]["hash"])

    def test_play_picks_h264(self) -> None:
        play, download = eng.douyin_pick_tiers(self.VIDEO)
        self.assertEqual("https://d/720h264.mp4", play)
        self.assertEqual("https://d/1080h265.mp4", download)


def _escaped_page(payload: dict) -> str:
    """把一份 JSON 按抖音页面里的形态写进 <script>（引号是转义的）。"""
    raw = json.dumps(payload, ensure_ascii=False).replace('"', '\\"')
    return f'<html><script>self.__next_f.push([1,"{raw}"])</script></html>'


class TestDouyinMainDocument(unittest.TestCase):
    PAYLOAD = {
        "desc": "一年要有365套衣服才可以没烦恼",
        "nickname": "穿搭博主",
        "unique_id": "outfit_uid",
        "images": [
            {"width": 1080, "height": 1440, "urlList": ["https://p/a.jpeg"], "video": None},
            {"width": 1080, "height": 1440, "urlList": ["https://p/b.webp"],
             "video": {"playAddr": [{"src": "https://v/live.mp4"}]}},
        ],
    }

    def test_gallery_and_live(self) -> None:
        payload = douyin_fallback.parse_main_document(
            _escaped_page(self.PAYLOAD), "https://v.douyin.com/x/", "7674197857957071025"
        )
        self.assertIsNotNone(payload)
        self.assertEqual("7674197857957071025", payload["id"])
        self.assertEqual("一年要有365套衣服才可以没烦恼", payload["title"])
        self.assertEqual("穿搭博主", payload["uploader"])
        self.assertEqual("outfit_uid", payload["uploader_id"])
        self.assertEqual(2, len(payload["images"]))
        self.assertEqual("https://p/a.jpeg", payload["images"][0]["url_list"][0])
        self.assertEqual("", payload["images"][0]["video"]
                         and payload["images"][0]["video"]["play_addr"]["url_list"][0] or "")
        self.assertEqual("https://v/live.mp4",
                         payload["images"][1]["video"]["play_addr"]["url_list"][0])

    def test_placeholder_desc_is_skipped(self) -> None:
        """React 负载里的 `"$undefined"` 占位符不能被当成标题。"""
        payload = dict(self.PAYLOAD)
        payload["desc"] = "真实标题"
        escaped = json.dumps(payload, ensure_ascii=False).replace('"', '\\"')
        escaped = escaped.replace(
            '\\"images\\":', '\\"desc\\":\\"$undefined\\",\\"images\\":', 1
        )
        out = douyin_fallback.parse_main_document(
            f"<html><script>{escaped}</script></html>", "https://v.douyin.com/y/", "1"
        )
        self.assertEqual("真实标题", out["title"])

    def test_plain_video_page_returns_none(self) -> None:
        self.assertIsNone(douyin_fallback.parse_main_document(
            "<html><script>var x=1</script></html>", "https://v.douyin.com/y/", "1"
        ))


class TestXMedia(unittest.TestCase):
    def test_photos_become_gallery(self) -> None:
        data = {
            "code": 200,
            "status": {
                "id": "1", "text": "看图", "author": {"name": "A", "screen_name": "a"},
                "media": {"photos": [
                    {"url": "https://p/1.jpg", "width": 1200, "height": 800},
                    {"url": "https://p/2.jpg", "width": 1200, "height": 800},
                ], "videos": []},
            },
        }
        out = x_fallback._extract(data)
        self.assertEqual("images", out["content_type"])
        self.assertEqual(2, len(out["media"]))
        self.assertEqual("https://p/1.jpg", out["thumbnail"])

    def test_mixed_media_keeps_both_kinds(self) -> None:
        data = {
            "code": 200,
            "status": {
                "id": "2", "text": "图文混排", "author": {"name": "B", "screen_name": "b"},
                "media": {
                    "photos": [{"url": "https://p/1.jpg", "width": 900, "height": 900}],
                    "videos": [{"url": "https://v/x.mp4", "width": 720, "height": 1280}],
                },
            },
        }
        out = x_fallback._extract(data)
        kinds = [m["kind"] for m in out["media"]]
        self.assertEqual(["image", "video"], kinds)

    def test_no_media_is_fatal_kind(self) -> None:
        data = {"code": 200, "status": {"id": "3", "text": "纯文字", "media": {}}}
        with self.assertRaises(x_fallback.XNoMediaError):
            x_fallback._extract(data)


class TestWeiboMapping(unittest.TestCase):
    def test_mid_parsing(self) -> None:
        import web_fallback
        self.assertEqual("5355038384068164", web_fallback._weibo_mid(
            "https://weibo.com/7780564347/5355038384068164"))
        self.assertEqual("5355038384068164", web_fallback._weibo_mid(
            "https://m.weibo.cn/status/5355038384068164"))
        self.assertEqual("5355038384068164", web_fallback._weibo_mid(
            "https://weibo.com/7780564347/detail/5355038384068164"))

    def test_live_photos(self) -> None:
        import web_fallback
        root = {"status": {
            "id": "1", "text": "实况 <br> 两行",
            "user": {"name": "作者", "id": "42"},
            "pics": [
                {"url": "https://wx1.sinaimg.cn/a.jpg", "live": "https://v/live1.mp4"},
                {"url": "https://wx2.sinaimg.cn/b.jpg", "live": "https://v/live2.mp4"},
            ],
            "page_info": {},
        }}
        payload = web_fallback._weibo_status_payload(root, "https://weibo.com/1/2")
        self.assertEqual("live", payload["content_type"])
        self.assertEqual(2, len(payload["media"]))
        self.assertEqual("实况 \n 两行", payload["description"])
        self.assertEqual("作者", payload["uploader"])

    def test_video_with_single_cover_is_video(self) -> None:
        import web_fallback
        root = {"status": {
            "id": "2", "text": "视频", "user": {"name": "u"},
            "pics": [{"url": "https://wx/c.jpg", "live": ""}],
            "page_info": {"stream_url": "https://v/a.mp4"},
        }}
        payload = web_fallback._weibo_status_payload(root, "https://weibo.com/2")
        self.assertEqual("video", payload["content_type"])
        self.assertEqual("https://v/a.mp4", payload["video_url"])

    def test_text_only_fails(self) -> None:
        import web_fallback
        root = {"status": {"id": "3", "text": "只有文字", "user": {}, "pics": [],
                           "page_info": {}}}
        with self.assertRaises(web_fallback.WebFallbackError):
            web_fallback._weibo_status_payload(root, "https://weibo.com/3")

    def test_video_link_in_text_redirects(self) -> None:
        import web_fallback
        root = {"status": {
            "id": "4", "text": "看 https://video.weibo.com/show?fid=1034:4612557713899533",
            "user": {}, "pics": [], "page_info": {},
        }}
        payload = web_fallback._weibo_status_payload(root, "https://weibo.com/4")
        self.assertEqual("https://video.weibo.com/show?fid=1034:4612557713899533",
                         payload.get("redirect_url"))


class TestXhsMapping(unittest.TestCase):
    def test_image_url_fallbacks(self) -> None:
        import web_fallback
        self.assertEqual("https://a", web_fallback._xhs_image_url({"urlDefault": "https://a"}))
        self.assertEqual("https://b", web_fallback._xhs_image_url({"urlPre": "https://b"}))
        self.assertEqual("https://c", web_fallback._xhs_image_url({
            "infoList": [{"imageScene": "WB_PRV", "url": "https://c"}]}))

    def test_stream_prefers_muxed(self) -> None:
        import web_fallback
        stream = {
            "EF4": [{"masterUrl": "https://v/1080_nosound.mp4", "height": 1920,
                     "audioChannels": 0}],
            "EF5": [{"masterUrl": "https://v/720_sound.mp4", "height": 1280,
                     "audioChannels": 2, "audioCodec": "aac"}],
        }
        self.assertEqual("https://v/720_sound.mp4", web_fallback._stream_best(stream))

    def test_live_url_from_image_video(self) -> None:
        import web_fallback
        img = {"video": {"playAddr": [{"src": "https://v/live.mp4"}]}}
        self.assertEqual("https://v/live.mp4", web_fallback._xhs_live_url(img))

    def test_note_id_from_urls(self) -> None:
        import web_fallback
        self.assertEqual("6acc7d9b00000000180154ec", web_fallback._xhs_note_id(
            "https://www.xiaohongshu.com/discovery/item/6acc7d9b00000000180154ec?xsec_token=x"))

if __name__ == "__main__":
    unittest.main(verbosity=2)
