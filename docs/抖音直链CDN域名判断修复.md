# 抖音直链 CDN 域名判断修复

- **日期**：2026-10-03
- **类型**：缺陷修复（静默失效 —— 不报错、不断流程，只是产物不对）
- **影响端**：Windows 端、Android 端
- **涉及文件**：
  - `windows/engine.py`
  - `android/app/src/main/python/ytdlp_engine.py`

---

## 一、这个判断是干什么用的

抖音解析拿到的是**裸直链**，形如：

```
https://v11-default.365yg.com/{…}/video/tos/cn/{…}/?a=0&ch=5&br=426&…
```

之后把它交给 yt-dlp 去下载。平常下载传的是**页面链接**，yt-dlp 能从页面里读出标题、作品 ID 等元数据，再按模板 `%(title)s [%(id)s].%(ext)s` 生成文件名。

但裸直链没有页面，yt-dlp 只能用「通用提取器」去猜。所以代码里有一个开关 `DIRECT_URL_HOSTS`：

- **命中**该列表 → 判定为「裸直链」，改用**历史库里已存的标题**来拼文件名；
- **未命中** → 按普通链接处理，文件名交给 yt-dlp 自己推导。

**同一份域名列表**还被用于决定「要不要给这个请求加 `Referer` 头」。

---

## 二、缺陷是什么

列表里只写了 `douyinvod.com` 和 `video.twimg.com`：

```python
DIRECT_URL_HOSTS = ("douyinvod.com", "video.twimg.com")
```

但抖音公开接口（`aweme.snssdk.com/aweme/v1/feed/`）实际下发的直链域名是 `365yg.com` / `amemv.com`，**从来不是 `douyinvod.com`**。

实测数据 —— 一次接口响应的 7 条作品、全部码率档位的地址，共 160 条：

| 域名 | 条数 |
|---|---|
| `v5-se-ex-mc-default.365yg.com` | 40 |
| `v11-default.365yg.com` | 40 |
| `api-play.amemv.com` | 40 |
| `api.amemv.com` | 40 |
| 含 `douyinvod.com` 的 | **0** |

于是这个判断**恒为 False**，分支永远进不去。

> 注意：这不是「两套方案冲突」。是**判断条件写死了旧域名，与现实脱节**。

---

## 三、修了之后会有什么影响

### 3.1 有实际影响的部分：下载文件名

判断失效 → 不走「用库里标题」这条路 → yt-dlp 只能猜。用同一条直链实测：

库里存的标题：

```
2026运动智能手环大横评：能干翻手表吗？ #运动手环 #智能手环 #华为手环
```

yt-dlp 用默认模板猜出来的文件名：

```
oQBFYi3IaswEAiU3mrPwP1PiQAaWBjaQ23gIA [a=0&ch=5&cr=3&dr=0&lr=all&cd=0003&cv=1&br=426&bt=426&cs=2&ds=3&ft=4TMyjf…].mp4
```

标题变成了一串**内部路径 token**，作品 ID 变成了**整个查询字符串**。修复后，文件名恢复为「真实标题 + 作品 ID」。

### 3.2 恰好没产生影响的部分：Referer

同一处判断还负责「加不加 `Referer` 头」。实测这三个域名**不带 Referer 也返回 HTTP 200**：

```
v5-se-ex-mc-default.365yg.com  无 Referer -> HTTP 200, video/mp4
v11-default.365yg.com          无 Referer -> HTTP 200, video/mp4
api-play.amemv.com             无 Referer -> HTTP 200, video/mp4
```

所以播放与下载功能一直是正常的 —— 这处判断同样是错的，但暂时不产生后果。
**本次不修改它**：实测不需要 Referer，改它没有验证收益，属于无谓改动。

### 3.3 明确不受影响的

- 画质：不受影响，下载的仍是最高的那一档
- 播放：不受影响
- 解析成功率：不受影响

**本缺陷的特征是「静默失效」——不报错、不中断、不降画质，只是落地的文件名不对。**
这也是它一直没被发现的原因：功能看起来一切正常。

---

## 四、具体改了什么

### 4.1 `windows/engine.py`

```diff
-DIRECT_URL_HOSTS = ("douyinvod.com", "video.twimg.com")
+DIRECT_URL_HOSTS = ("douyinvod.com", "365yg.com", "amemv.com", "video.twimg.com")
```

### 4.2 `android/app/src/main/python/ytdlp_engine.py`

```diff
-DIRECT_URL_HOSTS = ("douyinvod.com", "video.twimg.com")
+DIRECT_URL_HOSTS = ("douyinvod.com", "365yg.com", "amemv.com", "video.twimg.com")
```

两处常量保持一致 —— 这两端是同一份规格的两个实现，改一处必须同步另一处（两侧注释里已写明）。

新增的 `365yg.com`、`amemv.com` 两个域名，是上面那张实测表里采到的全部域名，`douyinvod.com` 予以保留（旧数据、浏览器链路可能仍会产出）。

---

## 五、验证记录

### 5.1 修复前的现象（复现）

用真实直链跑 yt-dlp 默认模板，得到上面 3.1 那串乱码文件名。

### 5.2 修复后（Windows 端，端到端实测）

| 项目 | 结果 |
|---|---|
| 解析 | `ok=True`，标题 / 时长 / 直链齐全 |
| 下载命令的 `-o` | `2026运动智能手环大横评…[7689396203758521646].%(ext)s`（真实标题 + 作品 ID） |
| 真实下载 | 退出码 0，落地 **54.51 MiB** |
| 产物校验（ffprobe） | 合法 mp4：`hevc 1280x720` + `aac`，时长 1046.5s，与接口返回的 1046s 一致 |

### 5.3 修复后（常量与匹配逻辑校验）

```
安卓端 DIRECT_URL_HOSTS = ('douyinvod.com', '365yg.com', 'amemv.com', 'video.twimg.com')
Windows 端              = ('douyinvod.com', '365yg.com', 'amemv.com', 'video.twimg.com')
两端一致: True

用实测域名逐一检验（修复前全部为 False）:
   命中=True   https://v5-se-ex-mc-default.365yg.com/...
   命中=True   https://v11-default.365yg.com/...
   命中=True   https://api-play.amemv.com/...
   命中=True   https://api.amemv.com/...
   命中=False  https://some-unknown-cdn.example.com/...   （不误伤其他域名）
```

> 说明：Android 端未在真机上跑完整下载（需重新构建 APK 并装机）。
> 已完成的验证是：文件语法编译通过、常量与 Windows 端一致、匹配逻辑对真实域名全部命中。

---

## 六、回滚方式

改回原值即可，无副作用、无数据迁移：

```python
DIRECT_URL_HOSTS = ("douyinvod.com", "video.twimg.com")
```

回滚后唯一变化是：抖音直链下载的文件名会再次退化为乱码，功能与画质不受影响。

---

## 七、遗留 / 待观察

1. **域名列表可能再次过期。** 抖音换 CDN 时这个列表会重现同类问题。若将来又出现「文件名乱码」，第一件事就是打一条接口、看返回的直链域名，然后补进这个列表。
2. **`VideoPlayer.kt` 里的 Referer 判断**（`android/.../ui/VideoPlayer.kt`，`if (source.contains("douyinvod.com"))`）与本缺陷同源，同样只认 `douyinvod.com`。当前不产生后果（实测不需要 Referer），未改动。若将来出现「抖音视频能下但播不了」，应优先检查这里。
3. Android 端建议在下次构建装包后，实际下载一条抖音视频，确认文件名已恢复为标题。
