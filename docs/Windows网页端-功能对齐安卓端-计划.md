# Windows 网页端 · 功能对齐安卓端 · 计划文档

> 本文档先回答「现在差在哪」，再回答「Windows 这边该怎么实现」，最后给出分阶段落地顺序。
> 代码位置一律用 `文件:行号` 标注，方便逐条对照。

---

## 0. 先说清楚我的理解（请确认）

用户原话里的「问答子端 / 这个网页」我按**Windows 端的网页界面**理解，即：

- 后端：`windows/engine.py`（Python，调 yt-dlp、写 sqlite）
- 服务：`windows/main.py`（127.0.0.1 本地 HTTP + token）
- 界面：`windows/web/index.html`、`app.js`、`style.css`

安卓端是 Kotlin + Compose + 内嵌 Python（`android/app/src/main/python/ytdlp_engine.py`）。

**结论一句话**：Windows 端目前只做到「解析 + 下载 + 简单历史」，
安卓端已经演进到「多平台 × 多内容形态 + 档位 + 图集 + 本地播放 + MD5 + 实况图 + 反馈/授权/更新」。
两者的**数据模型也已经分叉**（安卓库比 Windows 库多 6 列），不是补几个按钮就能对齐的。
因此建议按下面的 P0→P4 顺序做，先把「同一份数据 + 基础体验」对齐，再补平台与内容形态，最后补 MD5/实况。

### 0.1 已确认的产品决策（2026-10-29）

| 问题 | 结论 |
|---|---|
| 实况图 | **不做独立页签**。解析实况图时照常下载成「一图 + 一段同名视频」，不承诺相簿可动 |
| 意见反馈 | **保持邮件**，但模板化正文（带版本、构建指纹、系统信息） |
| 授权 / 版本更新 | **都不做**；Windows 端不引入密钥/试用体系，也不做自动更新 |
| 实施顺序 | **P0 → P4 逐步**，每阶段做完验收后再进下一阶段 |

### 0.2 当前进度

- **P0 已完成（2026-10-29）**，验收证据见 `windows/tests/test_p0_engine.py`（19 个用例全绿）
  以及本地服务的实测：异步解析（阶段文案 / 取消）、抖音真实解析入库、真实下载（结构化进度 / MD5 / local_path）、
  `/file` 的 Range 与越权拦截、无头浏览器渲染出的历史页与结果卡片。
- P0 中「下载收尾可选改写 MD5」这一项**顺延到 P3**：改写本身依赖 ffmpeg 探测与无损重封装，
  和 MD5 页是同一套实现，拆开做会重复写一遍。

- **P1 已完成（2026-10-29）**，单元测试 `windows/tests/test_p1_platforms.py`（26 个用例），
  全套 45 个用例全绿。实测通过：
  - 抖音图文（26 张）/ 实况图（1 张，另存同名 mp4）—— 浏览器兜底改为**先读主文档**再等详情接口
  - 快手视频（720p 档位）与图集（9 张）
  - 公众号文章（图文 + 文章内视频，正文 150~1208 字）、服务号贴图（11 张）、视频号预览
  - X 图片 / 图集 / 图文混排（FxTwitter 多媒体列表）
  - 微博实况图（1 张 / 9 张，产物 9 个 .gif + 9 个同名 .mp4）
  - 图集下载：目录 = 作者名，文件 = 标题_序号，实况图视频与图片同名
- P1 的两条边界：
  - **小红书需要登录态**：Windows 上用「登录小红书.bat」在专用浏览器里登录一次；
    未登录时如实提示，不会伪成功。端到端未验证（本机浏览器 profile 未登录小红书）。
  - 微博正文页必须走 `m.weibo.cn`：桌面版 weibo.com 页面里没有 `$render_data`（实测）。

- **P2 已完成（2026-10-29）**，单元测试 `windows/tests/test_p2_tiers.py`（10 个用例），
  全套 55 个用例全绿。实测通过：
  - 抖音档位 8 个：最高档 1280p H.265 下载出 17.8 MB，切到默认档 1024p 出 29.1 MB，
    重名自动另存 `(1)`
  - B站档位 4 个（1080p/720p/480p/360p）；用 selector 下 480p → ffprobe 确认 `852x480`
  - 下载条速度来自近 4 秒滑动窗口（实测采样 9.1 MB/s 一档，卡顿时会降）
  - 结果卡片出现档位下拉与「MD5（平台自报）」一行，切换档位时 MD5 跟着换
  - **修掉一个 P0 引入的卡死**：yt-dlp 解析超时后，node 子进程仍握着管道，
    `communicate()` 永远等不到 EOF → 界面停在「正在调用解析引擎…」。
    现在超时用 `taskkill /T /F /PID` 杀整棵进程树，实测 YouTube 30 秒准时返回超时提示。
  - 新增配置 `engine.direct_threads`（默认 6）与 `engine.socket_timeout`（默认 20 秒）

- **P3 已完成（2026-10-29）**，单元测试 `windows/tests/test_p3_md5.py`（8 个用例），
  全套 63 个用例全绿。实测通过：
  - 新增「MD5」页签：入口（虚线框 + 选择视频/图片/其他文件 + 已下载文件分区）→
    详情（预览 + 时长/大小/分辨率/MD5 + 修改）→ 结果（新旧大小与 MD5 同行划掉旧值 + 重选/保存）
  - 视频改写：ffmpeg `-c copy` 无损重封装。实测下载时自动改写把
    平台自报 `cb341e94…` 变成 `aa441eb2…`，成品 ffmpeg 解码退出码 0
  - 下载收尾写库：`source_md5` 保留平台值，`file_md5` 记成品实际值（两者不同，符合预期）
  - 图片改写：PNG 插 tEXt（IHDR 之后）、JPEG 插 COM（SOI/APP0 之后），
    单元测试逐字节校验块结构，且 ffmpeg 解码通过；原件 MD5 全程不变
  - MD5 页改写走「另存副本」：`md5_edit` 把成品写到应用数据目录的缓存，
    `/file` 预览返回 206，原件未被改动
  - 设置页新增「下载时自动改 MD5」开关与 ffmpeg 状态（启动即探测：
    `bin/ffmpeg` → `engine.ffmpeg_dir` → PATH）
- P3 的边界：文件选择框与保存对话框是系统原生窗口，无头环境无法自动化点击，
  只验证了它们调用的后端接口（`media_info` / `md5_edit` / 缓存与预览链路）。

- **P4 已完成（2026-10-29）**，全套 63 个用例全绿（P4 是纯界面改造，未新增后端用例）。
  实测通过：
  - 外观三档（浅色 / 深色 / 跟随系统）：选「深色」后 `html[data-theme=dark]`、胶囊高亮；
    选「跟随系统」按系统偏好解析为浅色，模式仍是 system；选择存 localStorage，刷新保留
  - 设置页重构：Cookie 显示「已导入 / 未导入」并可一键清除（实测 set→present=true，
    clear→present=false）；新增关于页（支持范围、编码/体积科普、MD5 原理）
  - 诊断信息卡：版本指纹 + 构建时间 + 系统 UA + 保存路径 + ffmpeg 路径 + 自动改 MD5 状态，
    支持一键复制；改保存目录后诊断文本同步更新
  - 意见反馈改为**模板化邮件**：mailto 带主题「[反馈] 视频解析下载（Windows）」与
    预填正文（问题描述 + 诊断信息），实测解码后内容完整
  - 按已确认决策：不做实况页、不做授权体系、不做自动更新（仅展示构建指纹）





---

## 1. 两端现状对比

### 1.1 页面结构

| 能力 | 安卓端 | Windows 网页端 | 差距 |
|---|---|---|---|
| 底部/侧边导航 | 解析 / 历史 / 实况 / MD5 / 设置（`MainScreen.kt:66-86`） | 解析 / 解析历史 / 设置（`index.html:12-19`） | 缺「实况」「MD5」两个页签 |
| 常驻下载进度条 | 有，压在底部导航上方（`MainScreen.kt:207-318`） | 有（`index.html:93-101`、`app.js:603-704`） | 字段与状态机落后（见 1.7） |
| 意见反馈 | 独立表单页，提交进企业微信（`FeedbackScreen.kt`、`FeedbackSender.kt:42`） | 只有一个 `mailto:` 链接（`index.html:84-86`） | 缺表单、缺图片、缺设备信息 |
| 授权 | 密钥 + 试用次数（`LicenseApi.kt`、`LicenseStore.kt`） | 无 | Windows 端本身无此体系 |
| 版本更新 | 检查更新 + 更新说明（`UpdateChecker.kt`、`SettingsScreen.kt:265-337`） | 只有界面指纹自愈（`app.js:189-213`） | 概念不同，需单独决定 |

### 1.2 解析页

| 能力 | 安卓端 | Windows 网页端 |
|---|---|---|
| 输入框 + 一键粘贴 | `ParseScreen.kt:174-234` | `index.html:24-28`、`app.js:473-488` |
| 回车即解析 | 有（`ImeAction.Go`，`ParseScreen.kt:219-221`） | 无 |
| 链接格式校验/提取整段分享文案 | 有 | 有（`app.js:95-101`） |
| 解析中状态 | `ParseUi.Loading` + **分平台文案** + VPN 提示（`ParseScreen.kt:265-287`） | 只有按钮变成「解析中…」（`app.js:498-500`），**无阶段提示、无超时提示、无取消** |
| 失败展示 | 独立失败卡片，保留换行（`ParseScreen.kt:289-305`） | 内联错误（`app.js:510-515`） |
| 清空结果 | 有（`ParseScreen.kt:243-249`） | 无 |
| 结果卡片：平台徽标/备用链路标记 | 有（`ParseScreen.kt:404-419`） | 只有一行文字 |
| 结果卡片：标题/作者/账号ID/时长/画质/大小 | 有（`ParseScreen.kt:423-448`） | 有（`app.js:442-467`），但无「备用链路」标记 |
| 正文（description）折叠/展开/复制 | 有（`ParseScreen.kt:451-454`、`BodyBlock`） | **无** |
| 封面 + 内联播放 + 卡死兜底 + 自动重解析 | 有（`app.js:282-374` 已有兜底，安卓有 `VideoPlayer.kt`） | 有（同左），但**不能播本地文件** |
| 图集/图文/实况图浏览 | `GalleryBox` 逐张翻页（`ParseScreen.kt:458-460`） | **无** |
| 档位选择器（画质/编码/体积） | 有，默认最高档（`ParseScreen.kt:544-551`、`TierSelector`） | **无** |
| MD5 行（下载前就能看平台自报值） | 有（`ParseScreen.kt:477-483`、`md5Line`） | **无** |
| 「下载时自动改 MD5」开关 | 有（`ParseScreen.kt:485-507`） | **无** |
| 下载 / 下载全部 / 下载第 N 张 | 有（`ParseScreen.kt:526-541`） | 只有「下载」 |
| 复制链接 / 打开原视频 | 各自整行（`ParseScreen.kt:557-573`） | 只有复制链接 |
| 存储权限引导 | 有（`ParseScreen.kt:146-168`） | 不适用 |

### 1.3 历史页

| 能力 | 安卓端 | Windows 网页端 |
|---|---|---|
| 「全部」+ 平台标签 | 有，「其他」常驻垫底（`HistoryScreen.kt:111-149`） | 只有平台标签，**无「全部」**，无记录的平台不显示、其他不常驻（`app.js:235-276`） |
| 排序 | 按条数降序，同数按名字（`HistoryScreen.kt:119-121`） | 固定平台顺序（`app.js:22`、`235-240`） |
| 折叠态 | 图标 + 标题 + 时间 + 已下载标记（`HistoryScreen.kt:419-470`） | 图标 + 标题 + 副标题 + 5 个操作图标（`app.js:377-420`） |
| 展开态字段 | 作者 / 账号 ID / 时长 / 原始链接（可折）/ 本地文件（`HistoryScreen.kt:325-333`） | 只有播放区 + 原始链接 + 3 个按钮 |
| 本地文件失联处理 | `Engine.history()` 在 IO 线程 stat 并清掉失效路径（`Engine.kt:179-191`） | **无**（库里根本没有 local_path 列） |
| 已下载后播放本地文件 | 有（`Video.playSource` 本地优先，`Engine.kt:386-397`） | **无** |
| 图集逐张浏览/单张下载 | 有（`HistoryScreen.kt:335-389`） | **无** |
| 清空 | 按当前筛选清空 + 二次确认（`HistoryScreen.kt:97-107`、`207-229`） | 按平台清空 / 全部清空（`app.js:724-743`） |
| 删除单条 | 有 | 有 |

### 1.4 设置页

| 能力 | 安卓端 | Windows 网页端 |
|---|---|---|
| 保存位置 | 有，且会显示「落到私有目录」的原因（`SettingsScreen.kt:124-144`） | 有（`index.html:55-63`） |
| Cookie | 有导入/重新导入/清除 + 各平台策略说明（`SettingsScreen.kt:146-182`） | 只有选择文件，无清除、无「是否已存在」状态（`index.html:64-80`） |
| 外观（浅色/深色/跟随系统） | 有（`SettingsScreen.kt:101-116`） | 只有 `prefers-color-scheme` 自动（`style.css:23`） |
| 反馈入口 | 有独立页 | 只有邮件 |
| 抖音兜底工具出口 | 有（`SettingsScreen.kt:208-226`） | 无 |
| 关于（支持范围/编码科普/MD5 科普） | 有（`SettingsScreen.kt:339-392`） | 无 |
| 版本更新 | 有 | 无 |

### 1.5 MD5 / 实况 / 反馈

| 能力 | 安卓端 | Windows 网页端 |
|---|---|---|
| MD5 入口页 / 详情页 / 结果页 | 完整三段式（`Md5Screen.kt:174-355`） | **完全没有** |
| 媒体选择 | 系统相簿 PickVisualMedia；视频/图片各一个 launcher | 不适用（Windows 用系统文件对话框或 HTML `<input type=file>` 替身） |
| 新旧 MD5 / 大小对比、删除线 | 有（`Md5Screen.kt:373-498`） | 无 |
| 另存副本语义 | 有（原件不动） | 无 |
| 实况图合成 | 有（`LiveScreen.kt`、`LivePhotoSynth.kt`） | 无；**且 Windows 无「相簿实况图」概念**，需单独定产品形态 |

### 1.6 平台与内容形态

安卓端识别规则 `ytdlp_engine.py:141-174`：YouTube / B站 / 抖音 / TikTok / X / Instagram / 小红书 / **快手** / **微信视频号** / **微信公众号** / 微博。

Windows 端识别规则 `engine.py:41-61`：YouTube / B站 / 抖音 / TikTok / X / Instagram / 小红书 / 微博。
**缺快手、视频号、公众号。**

内容形态（README「实测平台支持」表）：

| 平台 | 安卓端能力 | Windows 现状 | Windows 能否补 |
|---|---|---|---|
| 抖音 | 视频 + 图文/图集/实况图 + 档位 + 平台自报 MD5 | 仅视频单档（`douyin_fallback.py:159-174` 只取 `_pick_tiers` 的 play/download 两条） | ✅ 纯 HTTP 可补，接口返回里本来就有 `images` |
| 快手 | 视频 + 图集（`_parse_kuaishou`） | 无 | ✅ 纯 HTTP 可补 |
| 小红书 | 视频 / 图文 / 图集 / 实况图（WebView 登录态） | yt-dlp + cookies.txt（不稳） | ⚠️ 需用真实浏览器替代 WebView |
| 微博 | 视频 / 图文 / 图集 / 实况图 / 动图 / 音频 / 头条文章（WebView） | yt-dlp 仅视频 | ⚠️ 需真实浏览器取数 |
| 公众号 | 文章内视频 / 图文 / 贴图（`_parse_wechat`） | 无 | ✅ 纯 HTTP 可补 |
| 微信视频号 | 仅预览（`_parse_wechat_channels`） | 无 | ✅ 纯 HTTP 可补（但不可下载） |
| X | 单图 / 图集 / 一推多视频 / 图文混排 | 只有第一个视频（`x_fallback.py:100-126`） | ✅ fxtwitter 返回里本来就有完整 media |

### 1.7 引擎与数据层

| 能力 | 安卓端 | Windows 端 |
|---|---|---|
| 历史表列 | id/platform/title/uploader/uploader_id/duration/thumbnail/source_url/resolved_url/download_url/resolved_at/play_kind/**local_path/content_type/media_json/source_md5/file_md5/description**（`ytdlp_engine.py:1065-1115`） | 只有前 12 列（`engine.py:283-313`），**`local_path` 等 6 列全缺** |
| 下载状态字段 | `type/active/percent/speed/eta/downloaded/total/url/seq/md5/md5_after/message/path`（`Engine.kt:508-567`） | `type/active/value(字符串"pct|speed|eta")/path/message/seq`（`engine.py:389-401`） |
| 状态阶段 | idle/starting/progress/**processing**/done/error | idle/starting/progress/done/error，**无 processing** |
| 下载产物入库 | `_finish_download()`：记 local_path → 算 MD5 → 可选改写 → 报 done（`ytdlp_engine.py:3686-3729`） | **无**，下完只是 `done + path`，库里没记 |
| 档位 spec | `start_download(url, index, spec)` | `start_download(url)`，**无档位参数** |
| 直链并行下载 | Range 分块 8 线程（`ytdlp_engine.py:2975-3130`） | 只靠 yt-dlp `-N`（`engine.py:809-819`） |
| 封面抽取 / MD5 改写 | `extract_cover`、`rewrite_md5`、`edit_md5_to`（`ytdlp_engine.py:3493-3686`） | 无 |
| 输出目录可写性校验 | `_writable()` + fallback 目录 | 无 |
| 解析超时 | 外层 30s + 各平台自己的判据 | 统一 30s（`engine.py:29`），抖音/小红书无专用判据 |

> ⚠️ 顺带发现一个现存 bug：`engine.py:687-707` 的 `_with_local_path()` 会读 `old["local_path"]`，
> 但 `Store` 建表时没有这一列 —— 所以永远读不到，逻辑等于没写。

---

## 2. 差异分类：能 1:1 同步 / 需换实现 / 不适用

### A. 能直接 1:1 同步（纯逻辑，Windows 完全做得到）

1. 历史库 schema 与 upsert 规则
2. 下载状态结构化字段 + `processing` + `url` 关联 + `seq`
3. 下载收尾 `_finish_download`（local_path / file_md5 / 可选改写）
4. 档位模型（Tier）与 `spec` 传参
5. 直链 Range 分块并行下载
6. 图集/图文/实况图的 `media_json` 与逐张下载、按作者建目录
7. 解析页全量卡片、历史页全量字段、全部/其他标签规则
8. 快手、公众号、视频号解析（纯 HTTP）
9. 抖音图文/图集/实况图 + 平台自报 MD5（纯 HTTP 接口里已有）
10. X 图文/图集/多视频（fxtwitter 返回里已有）
11. 浅色/深色/跟随系统主题切换
12. 关于页文案、诊断信息

### B. 需要换实现（安卓靠系统能力，Windows 没有对应物）

| 安卓做法 | Windows 替代方案 |
|---|---|
| 应用内 WebView 取数（小红书 / 微博 / 抖音兜底） | 真实浏览器。`douyin_fallback.py` 已在用 DrissionPage + Edge/Chrome，可直接复用同一套 profile 与监听思路，但**对象从 WebView 换成浏览器标签页** |
| PickVisualMedia（相簿选择器） | 系统文件对话框（后端 `tkinter.filedialog`，已有先例 `pick_cookie_file`）；或网页 `<input type=file multiple>` |
| `Environment.isExternalStorageManager()` 权限门 | 不需要。Windows 只要判断目录可写，不可写就给明确报错 |
| 写入系统「下载」目录 + 媒体扫描 | 直接写用户选的目录；不涉及媒体库 |
| 实况图写 `live_photo` 字段 / 单文件 JPEG+MP4 | Windows 没有相簿实况概念。**建议只提供「合成」产物（jpg+mp4 或单文件），不做「相簿可动」承诺** |
| 授权密钥 / 试用次数 | 不适用（Windows 端无此体系，除非另行设计） |
| APK 检查更新 | 可改为检查 GitHub Releases / 版本文件；需产品决策 |

### C. 不适用 / 不建议照搬

- 存储权限引导、相簿扫描、分享 Intent
- 授权绑定设备、VPN 工具下载入口（安卓激活需要 VPN，Windows 不需要）
- `parseUrl 不可中断` 的妥协：Windows 是子进程，**可以真正 kill**，比安卓更强

---

## 3. Windows 端设计与实现方案

### 3.1 数据层（`windows/engine.py` · `Store`）

1. 建表补列 + `_migrate()`，与 `ytdlp_engine.py:1065-1115` 完全对齐：
   `local_path` / `content_type` / `media_json` / `source_md5` / `file_md5` / `description`。
2. `upsert()` 改成与安卓同构（`ytdlp_engine.py:1117-1165`）：
   - 统一标题归一（`_norm_title`，短标题 + 没标题用正文顶上）
   - `description` 用 `CASE WHEN excluded.description = '' THEN history.description`，避免备用链路把正文冲空
   - **`file_md5` 不进 upsert**（只在下载收尾回填）
3. 新增 `set_local_path(source_url, path)`、`set_media_json(id, json)`、`set_video_url(id, url)`、`set_file_md5(path, md5)`。
4. 修掉 `_with_local_path()` 的坏读（有了 `local_path` 列即自动恢复）。

### 3.2 解析链路（`engine.py`）

统一按平台分流，顺序与安卓 `parse_url` 保持一致：

```
detect_platform(url)
  ├─ 抖音      → _parse_douyin()：公开接口 → 浏览器兜底
  ├─ 快手      → _parse_kuaishou()
  ├─ 小红书    → 浏览器取数（DrissionPage）
  ├─ 微博      → 浏览器取数（DrissionPage）
  ├─ 公众号    → _parse_wechat()
  ├─ 视频号    → _parse_wechat_channels()（仅预览）
  ├─ X         → yt-dlp → x_fallback（扩展为完整 media 列表）
  └─ 其他      → yt-dlp
```

可移植的纯逻辑（直接照安卓实现翻成 Windows 风格）：

| 来源 | 行号 | 作用 |
|---|---|---|
| `_parse_kuaishou` 及其 `_ks_*` | `ytdlp_engine.py:536-673`、`2337-2464` | 快手 SSR JSON 解析 |
| `_parse_wechat` / `_parse_wechat_channels` 及其 `_wx_*` | `ytdlp_engine.py:1330-1647`、`2622-2734` | 公众号 / 视频号 |
| `_parse_douyin_via_api` | `ytdlp_engine.py:2140-2336` | 抖音档位 + 图文/图集/实况图 + `source_md5` |
| `douyin_pick_tiers` / `douyin_collect_tiers` | `ytdlp_engine.py:409-535` | **档位规则只保留一份** |
| `ytdlp_collect_tiers` | `ytdlp_engine.py:693-865` | yt-dlp 档位（B站/YouTube 等） |
| `_xhs_pick_video` | `ytdlp_engine.py:1701-1736` | 小红书流挑选 |

浏览器取数（小红书 / 微博）的设计：

- 复用 `douyin_fallback.py` 已有的「找浏览器 → 起 profile → 监听页面 → 取数据」基础设施；
- 小红书：打开笔记页，取 `window.__INITIAL_STATE__`（含 `noteDetailMap`），无登录态时明确提示「请在弹出的浏览器里登录一次」；
- 微博：打开 `m.weibo.cn/status/<id>`，取服务端渲染的 `$render_data`；
- 所有浏览器链路都必须遵守已有纪律：**按页面地址锁定目标作品 id**，绝不退回推荐流第一条。

### 3.3 下载链路

1. `start_download(url, index=0, spec="")`，`spec` 为 JSON（`{"url": ...}` 或 `{"selector": ...}`）。
2. `_download_target()` 的「用库里的直链」判断改成「凡是备用链路入库的平台」：抖音 / 快手 / 小红书 / 微博 / 公众号 / X。
3. 直链域名表 `DIRECT_URL_HOSTS` 与安卓同步（补 `dypns.com` 等快手域名、公众号 `mpvideo.qpic.cn`）。
4. 直链走自研 Range 分块并行下载（移植 `ytdlp_engine.py:2913-3130`），非直链保留 yt-dlp `-N`。
5. 下载状态：`DownloadState.update()` 改为结构化字段，并补 `processing`（合并音视频阶段）、`url`、`md5`、`md5_after`；前端不再解析 `"pct|speed|eta"` 字符串。
6. 速度用近 4 秒滑动窗口瞬时速率（与安卓一致）。
7. `_finish_download()`：
   - 按 `source_url` 记 `local_path`
   - 算 `file_md5`
   - 若开启「下载时自动改 MD5」→ 无损重封装改 MD5 → 回填新的 `file_md5`
   - 最后才报 `done`（顺序不能换，否则历史页播的是旧文件）
8. 取消：Windows 有子进程，可直接 `proc.kill()`，做真正的取消（安卓只能「界面不等」）。

### 3.4 播放与本地文件

- 现有 `/media?t=&u=&ref=` 代理远程直链（`main.py:143-181`）保留。
- 新增 `/file?t=&p=`：只允许输出目录内的路径，支持 `Range`，用于播本地产物。
- 前端 `resolvePlaySource(item)`：`local_path` 存在且文件还在 → `/file`；否则 `resolved_url` → `/media`。
  （对应安卓 `Video.playSource`，`Engine.kt:386-397`）
- 历史接口在返回前 stat 一次，把失效 `local_path` 清成空串（对应 `Engine.kt:179-191`）。

### 3.5 前端（`windows/web/`）

建议按「视图 + 组件」重排，而不是继续往 `app.js` 里堆：

```
web/
  index.html        导航 4 项：解析 / 历史 / MD5 / 设置（实况另议）
  app.js            引导 + 视图切换 + API 封装
  views/parse.js    解析页状态机（Idle/Loading/Failed/Done）
  views/history.js  历史页（全部/平台标签、展开详情、图集翻页）
  views/md5.js      MD5 三段式
  views/settings.js 设置页
  components/player.js  PlayerBox（封面→播放器、卡死兜底、自动重解析、/file 支持）
  components/gallery.js GalleryBox（逐张翻页 + 下载第 N 张）
  components/tier.js    TierSelector（默认最高档、宽度封顶、单行省略）
```

解析页状态机（对齐 `ParseUi`）：

| 状态 | 界面 |
|---|---|
| Idle | 只有输入区 |
| Loading | 转圈 + 分平台文案（「正在直接读取抖音作品数据…」等）+ 计时；超过 10s 提示 VPN/网络 |
| Failed | 失败卡片，保留换行 |
| Done | 结果卡片 |

> 现行 `POST /api/call` 是同步阻塞（`main.py:247-252`），最长 30 秒。
> 为了「实时跟进」，建议把解析改成**任务式**：`parse_url` 立即返回 `taskId`，
> 前端轮询 `get_parse_state` 拿阶段文案与最终结果。这与下载进度用的是同一套轮询机制，
> 也顺带解决 `AbortController` 取消后后端还在跑的问题（后端可直接 kill 子进程）。

### 3.6 MD5 页（Windows 版）

流程与安卓一致，替换掉「相簿」这一环：

```
入口页（虚线框 + 选择视频 / 选择图片）
  → 系统文件对话框（后端 tkinter / 或网页 <input type=file>）
  → 详情页（预览 + 时长/大小/分辨率/MD5 + 「重选」+「修改 MD5」）
  → 结果页（预览 + 改写前后大小/ MD5 + 「重选」+「保存文件」）
```

- 复制进缓存目录处理（对应安卓「先整份复制」的纪律），原件不动。
- 保存 = 另存副本到输出目录。
- 改写实现与安卓共享同一套规则：mp4 走 ffmpeg `-c copy` + comment 元数据；
  png 插 `tEXt`（IHDR 之后）、jpg 插 `COM`（SOI/APP0 之后）、其余格式尾部追加。
- **ffmpeg 依赖**：现在 `config/defaults.json` 里写的是 `D:\Dev-env\FFmpeg\bin`，
  用户机器上不一定存在。需要：
  1. 启动时探测（`bin/ffmpeg` → 配置 → PATH）；
  2. 探测不到时 MD5 页与「下载时自动改 MD5」开关明确置灰并说明原因。

### 3.7 实况图（Windows 版，需产品决策）

Windows 没有「相簿实况图」这种消费场景，硬照搬安卓的 `live_photo` 字段没有意义。三个选项：

- **A（推荐）**：不做。实况图相关的价值在安卓端，Windows 端解析时把实况图当作「一张图 + 一段同名视频」正常下载即可（也就是只补 P1 的解析能力）。
- **B**：做「合成工具」，产物为 `xxx.jpg` + `xxx.mp4` 同名对，或单文件动态照片；不做相簿承诺。
- **C**：做完整工具 + 尝试写 Windows 侧元数据（无标准，收益低）。

### 3.8 反馈 / 更新 / 授权

- 反馈：Windows 保持 `mailto:`（当前实现），但把「遇到什么、链接、版本、构建指纹」做成模板化正文，提高可复现性。
  若要 1:1 对齐安卓的图片 + 企业微信提交，需要把 `FeedbackSender` 的 webhook key 搬到本地服务里 —— 等于把凭据写进分发包，需单独评估。
- 更新：建议只做「检查新版本 → 打开发布页」，不做自动替换。
- 授权：不适用。

---

## 4. 分阶段实施计划

> 每阶段结束都必须能独立验收；按顺序做，不跳步。

### P0 · 同一份数据 + 基础体验（最优先）

1. 历史库补 6 列 + upsert/setter 对齐安卓
2. 下载状态结构化为 `percent/speed/eta/downloaded/total/url/seq/md5/md5_after` + `processing`
3. `_finish_download`：local_path + file_md5（自动改写 MD5 顺延到 P3，与 MD5 页共用一套实现）
4. 新增 `/file` 本地播放入口，前端 `playSource` 本地优先
5. 历史接口 stat 校验失效路径
6. 解析结果卡片补齐（平台徽标 / 备用链路 / 正文折叠 / 打开原视频 / 清空结果）
7. 历史页补齐（「全部」tab、「其他」常驻、折叠两行、展开五字段、已下载标记）
8. 解析过程加阶段提示 + 超时提示 + 取消

**验收**
- 下完一条抖音 → 历史页显示「已下载」，展开能直接播本地文件
- 关掉软件重开 → local_path 仍在，仍能播
- 文件被手动删掉 → 历史页自动不显示「已下载」，不报错
- 下载条显示的百分比/速度/剩余与 yt-dlp 实际一致，合并阶段显示「正在合并音视频…」

### P1 · 平台与内容形态

1. 平台识别规则补 快手 / 公众号 / 视频号（含域名分隔符校验）
2. 抖音：图文 / 图集 / 实况图 + `source_md5` + 档位全集
3. 快手：纯 HTTP 解析
4. 公众号：纯 HTTP 解析（图文 / 贴图 / 文章内视频）
5. 视频号：仅预览
6. X：图文 / 图集 / 一推多视频
7. 小红书：浏览器取数（登录态一次）
8. 微博：浏览器取数（图文 / 图集 / 实况 / 动图 / 音频 / 头条）
9. 前端 `GalleryBox` + `media_json` 渲染

**验收**
- README 的支持表逐行在 Windows 端实测通过（每条链接留档到 `docs/`）
- 图集能翻页、能「下载全部」、能「下载第 N 张」，产物按作者目录保存
- 抖音解析结果里能看到平台自报 MD5

### P2 · 档位 + 下载性能

1. Tier 模型（`url` / `selector` / `label` / `height` / `vcodec` / `filesize` / `hash`）
2. 前端档位选择器（默认最高档、切换作品重置、宽度封顶）
3. `start_download(url, index, spec)`
4. 直链 Range 分块并行下载（并发数走配置 `engine.direct_threads`）
5. 滑动窗口瞬时速度

**验收**
- 抖音同一作品切档下载，产物分辨率/编码与所选档一致
- 直链下载速度接近单连接 4 倍以上（本地实测对比）
- 档位文案过长时「复制链接」不换行

### P3 · MD5

1. ffmpeg 探测 + 状态展示
2. MD5 入口/详情/结果三段式
3. `edit_md5_to`（mp4 / png / jpg）
4. 「下载时自动改 MD5」开关
5. 已下载列表作次要分区

**验收**
- 改完 MD5 前后逐字比对（`certutil -hashfile` 或 Python），原件一字不变
- ffmpeg 解码产物零告警（`ffmpeg -v error -i out.mp4 -f null -`）
- 图片（png/jpg）走各自插入路径，产物仍是规范文件

### P4 · 周边

1. 主题切换（浅色 / 深色 / 跟随系统）
2. 设置页重构（Cookie 状态/清除、关于、诊断信息）
3. 反馈模板化正文
4. 版本检查（仅提示 + 打开发布页）
5. 实况图（按 3.7 决策执行或不做）

---

## 5. 风险与边界

1. **两端代码重复**：档位规则、平台规则、错误文案在 Python 里已经各有一份。
   P1/P2 移植时必须继续遵守「只保留一份实现」的纪律：Windows `engine.py` 为主，安卓 `ytdlp_engine.py` 同步修改，反之亦然。
2. **浏览器链路稳定性**：DrissionPage + 本机 Edge/Chrome 依赖用户装浏览器与驱动；失败要给出可执行的原因，不能只报「解析失败」。
3. **ffmpeg 缺失**：MD5 / 合并音视频都依赖它。必须显式探测 + 明确报错，不能静默降级。
4. **安全边界**：`/file` 必须限制在输出目录内（防任意文件读取）；`/media` 已有 SSRF 内网拦截，保持不变。
5. **Windows 端无授权体系**：不引入安卓的密钥/试用逻辑，避免把两套产品模型混在一起。
6. **实况图**：Windows 无相簿概念，先不做「相簿可动」承诺。

---

## 6. 需要你拍板的问题

1. **「实况」页签**：Windows 端要不要做？做的话是 A（不做，解析时照常下一图一视频）/ B（只做合成工具）/ C（完整对齐）？
2. **反馈**：保持 `mailto:`（推荐），还是要 1:1 对齐安卓的图片 + 企业微信提交（需要把 webhook 放进本地服务，要单独评估凭据风险）？
3. **授权 / 更新**：Windows 端是否也需要？还是明确「Windows 端不做」？
4. **范围顺序**：是否按 P0 → P1 → P2 → P3 → P4 逐步来，每阶段做完给你验收后再进下一阶段？
