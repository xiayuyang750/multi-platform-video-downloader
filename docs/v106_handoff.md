# v1.0.6 开发交接（给新对话用；本文件自洽，不需要回头看旧对话）

## 一、上一步（v1.0.5）的完工状态
- 仓库：`d:\Project\yt-dIp`；远端 `https://github.com/xiayuyang750/multi-platform-video-downloader`，分支 `main`
- 本地 HEAD = **`ed39446`**，已推送（远端 main 同步）
- 发布页：release id **`413319580`**，tag `v1.0.5`；正文 **1694 字**（范本见 `v105_body.md`），附件 `shuiyin-gongfang-v1.0.5.apk`（**41085126 字节**，已替换为含「关于科普 + 备用 app 入口 + MD5 加粗」的新包）
- 仓库内副本 `releases/水印工坊-1.0.5.apk` 已同步为同一个新包
- 版本号仍是 `versionCode = 5 / versionName = "1.0.5"`（`android/app/build.gradle.kts`）—— **开 1.0.6 时改这里**
- **遗留未做**：真机上核对设置页那两段科普与 MD5 页加粗（当时 `adb devices` 无设备）。文案已确认进了 APK（dex 字符串核对），但**视觉没在设备上看过**

## 二、1.0.6 的范围（三块，用户已确认，缺一不可）
1. **新增平台解析**：微博 / 小红书 / 快手 / 微信视频号 —— 视频优先、安卓先行
   - 已知实测结论：**微博**能走现有 yt-dlp 通用链路开箱解析（无需登录态）；**小红书**需要登录态 / 新鲜 Cookie；**快手**没有 yt-dlp 提取器，要自建链路；**微信视频号**优先级最低
   - 规矩：新增平台的功能与细节要对齐现有平台的实现标准（图标、错误提示、档位、历史标签等）
2. **「其他平台 × 各种内容形式」逐条测通并实现**：B站 / YouTube / TikTok / Instagram 的**视频 / 图文 / 图集 / 多图 / 图文混排**，逐条实测，能做出来的都做
3. **安全演练 —— 性质是「安全研究与文档」，不是给本应用加功能**：研究**同类下载器产品**如何滥用用户已授权的相册 / 文件权限，把数据静默外发到开发者服务器、用户为什么察觉不到。（此处上一版写反了 —— 写成「原理由用户给出」，方向应是研究同类产品）
   - 原理：读相册只要**一次长期授权**（之后每次读都不再询问）；外发只要 `INTERNET`（普通权限、安装即授予、不弹窗）—— 而下载器本来就要联网，所以它的权限清单看上去完全合理。
   - 为什么看不见：读相册**没有系统指示器**（指示器只覆盖摄像头 / 麦克风 / 位置）；隐私仪表盘只给「哪个权限被谁用过」，没有逐次文件日志、也不记网络目的地；后台排程用 `WorkManager` / `JobScheduler` 就够，**不需要前台服务**（不出现常驻通知）。
   - 交付形态：`docs/` 报告 +「关于」页一句可操作提示；**不要实现任何外发功能**（不写可运行的窃取代码）。
   - 用户侧防御：看**按应用的上行流量**（恶意外发的特征是持续上行、且发生在你没打开它的时候）、看隐私仪表盘的时间点、授权前问一句「这个功能真需要读全库吗」（下载器只在保存到公共目录时需要「所有文件访问」，**不需要读全库相册**）。
   - 开发者侧防御：权限最小化、只在用户动作时读、网络出口**白名单化**、依赖固定版本与 hash（上游被投毒会让你「被动」变成外发客户端）。
   - 来源已核：AOSP 隐私指示器的覆盖范围、Android 14 部分照片访问、Play 对「所有文件访问」的高风险政策（侧载绕开）。
- **建议顺序**：先加平台 → 再补内容形态 → 最后安全演练 + 测试验收
- **用户会提供**小红书 / 快手 / 微信视频号的真实分享链接；没有真实链接就做不了实测（尤其小红书那条需要登录态）

## 三、技术约束（都是踩过的坑，别重走）
- **git 必须带代理**：`$env:HTTPS_PROXY="http://127.0.0.1:7892"`；必要时 `dangerouslyDisableSandbox: true`（git 不读 WinINET）
- **给 GitHub API 发 JSON 不能用 `ConvertTo-Json`**（会 400）。可行姿势：
  `Add-Type -AssemblyName System.Web` → `'{"name":"' + [System.Web.HttpUtility]::JavaScriptStringEncode($x) + '","body":"' + … + '"}'` → `[System.Text.Encoding]::UTF8.GetBytes($json)` → `ContentType 'application/json; charset=utf-8'`
- **替换发布附件**：先 `DELETE /repos/{o}/{r}/releases/assets/{asset_id}`，再 `POST https://uploads.github.com/repos/{o}/{r}/releases/{release_id}/assets?name=…`
- **构建**：`gradle -p android assembleRelease --offline`（gradle 在 `D:\Dev-env\Gradle97\Program\bin\gradle.bat`）
- **产物路径**：`android/app/build/outputs/apk/release/水印工坊.apk`
- **安装**：`adb install -r <apk>`；装完 `MANAGE_EXTERNAL_STORAGE` 的 appop 会被**重置**，要重新 `adb shell appops set com.ytdlp.android MANAGE_EXTERNAL_STORAGE allow`
- **真机**：vivo。预览里有 ExoPlayer 在渲染时 `uiautomator dump` 会失败 → 用 `screencap`
- **没有 GitHub PAT 就不能调 API**（本轮的临时令牌已按要求删除；下一次收尾发布请你再提供。`git push` 走系统凭据缓存，不受影响）
- **无设备时的兜底取证**：`check_apk_strings.py`（python + zipfile 读 dex 里的 UTF-8 字符串）

## 四、工程规矩（用户明确要求过，务必遵守）
- **不许动用户没点名要改的东西**：曾擅自删掉 MD5 说明被批评；也删过一次入口页说明被要求原样恢复
- **发布说明长度对标** v1.0.4（1644 字）/ v1.0.3（1707 字）；v1.0.5 是 1694 字。**不写实现级细节**（`uifid`、基线定位之类留在代码注释里）
- **不许凭印象写说明，必须看代码 / diff 取证**
- 中文沟通与代码注释
- 顺序先写理解确认再动手（需求澄清门）、超过 3 步先写计划、完工要有新鲜验证证据

## 五、本轮动过的代码（1.0.6 可参照）
- `android/app/src/main/java/com/ytdlp/android/ui/Components.kt`：新增 `Hint(AnnotatedString)` 重载 + `markedBold("**…**")` 富文本助手（文案里用 `**` 圈出加粗片段）
- `SettingsScreen.kt`：关于卡片新增两段科普（清晰度/编码/体积、MD5 原理）；新增「抖音解析不了？」卡片，常量 `DOUYIN_TOOL_URL = https://github.com/xiayuyang750/watermark-tool/releases/tag/v0.4.0`
- `Md5Screen.kt`：入口页说明关键处加粗
- `README.md`：`### v1.0.5` 段已同步（含「正在开发中」两处条目 —— 就是 1.0.6 要做的事）

## 六、相关知识位置（省得到新对话里重找）
- 平台规则表：`android/app/src/main/python/ytdlp_engine.py` 第 52 行起 `PLATFORM_RULES`（决定 `detect_platform` 与历史标签）；目前只认 YouTube / B站 / 抖音 / TikTok / X / Instagram，**其余一律落「其他」**
- 档位：抖音 `douyin_collect_tiers` / `douyin_pick_tiers`（只对视频）；yt-dlp 平台 `ytdlp_collect_tiers`（selector 型）
- 抖音兜底：`DouyinActivity`，超时 15 秒；视频类作品在无头 WebView 里拿不到数据（恒「200 + 空响应体」），不要再去试 JS 钩子
- 历史排序：「其他」常驻并垫底，按记录条数排
- MD5 改写的两条路：视频 ffmpeg `-c copy` + comment；图片 png `tEXt` / jpg `COM`

## 七、本轮进度：小红书链路验证（2026-10-17，安卓先行）

**结论：走现成 yt-dlp 链路可通，但必须登录态；只对「视频笔记」有效。**

- **短链域名是 `xhslink.cn`**（实测 6 条真实分享链接全部是 `.cn`；`.com` 也存在、同样跳向 xiaohongshu.com）。两条都已加进两端 `PLATFORM_RULES` 与 `defaults.json`（`use_cookies: true`）。
- **没导入 Cookie**：分享短链被 302 到 `www.xiaohongshu.com/login?redirectPath=…`，yt-dlp 报 **`Unsupported URL`**（不是 No video formats found —— 这一条此前文档里没写过，报错提示要按它来匹配）。
- **带已登录 Cookie**：视频类笔记 **3/3 解析成功**，拿到 720x1280 / 1080x1920 两档，`vcodec` 字段是小红书自己的档位名 **`EF4`**（`_ytdlp_vcodec_name` 认不出 → 档位标签只显示分辨率，符合「认不出就不硬塞编码名」的既有规则）。
- **失败的那 3 条是「笔记本身没有视频」**：直连页面看数据结构，`type=normal`（图文 / 图集 / 实况图合集）、`video` 段为空 —— 与登录态无关。**图文类要支持得另做链路**（yt-dlp 的 XiaoHongShu 提取器只取视频格式）。
- 验证手法（可复用）：把 `test-cookie.md` 的 Netscape 片段落到仓库外的临时文件，再用
  `android/app/src/main/python/ytdlp_engine.py` 的 `Engine` **在 PC 上原样跑**（PC 与安卓只差「怎么调 yt-dlp」，两边都是进程内 CPython，所以结论可直接采信）。
- 离线断言：`android/.build-tmp/test_xhs_platform.py`（两端识别 / 两种真实报错 / Cookie 规则 / 图标一致性，共 54 项）。
- **已构建 1.0.6 包**（`versionCode 6`，41,087,534 字节）并核对包内落地内容。
- **未做**：真机实测（需设备）；微博 / 快手 / 微信视频号；图文类内容形态；安全演练。
- 发布说明按用户要求**边做边写**：`docs/水印工坊-v1.0.6-发布说明草稿.md`，每完成一项往里加、口径变了就回去改。

### 七·补 小红书图文/图集链路调研（同日，只取证未实现）

- 结论写在 `docs/小红书-图文图集链路调研.md`：**能做**（带登录态 HTTP 直连笔记页取 `imageList`），**但现成 yt-dlp 链路做不了**（提取器只取视频格式）。
- 关键技术事实：① 图片直链 `http://sns-webpic-qc.xhscdn.com/…/notes_pre_post/…`，**取流不需要 Cookie**、模板名不含 `water`；② 分享短链 302 到 `/login?redirectPath=<完整笔记地址>`，**未登录也能从 redirectPath 解析出真实地址**；③ **Netscape 文件里 `#HttpOnly_` 行不能当注释跳过** —— 跳过的后果是「yt-dlp 能过、自己拼的请求头过不去」（`web_session` 就在那几行里）。
- **踩到的坑：小红书有频控/风控。** 短时间内十几次请求后，全部请求被送到 `/website-login/captcha`、随后到 `/login`（39,560 字节空壳），**连 yt-dlp 也一起被拒**；Cookie 没过期（`web_session` 还剩 8,759 小时）。实现时必须识别风控页 + 限频，**触发阈值未证实**。
- 未取到（不猜结论）：图集 8 图 / 实况图 13 图的完整结构、实况图动效视频挂在哪个字段、图片下载后是否真无水印（要目视）。
- **真机实测（1.0.6 包已装到 vivo）**：Cookie 已导入（`/data/user/0/com.ytdlp.android/files/cookies.txt`），但视频短链在手机上**也被送到登录页**（`Unsupported URL: …/login?redirectPath=…`）→ **被拦与 IP 无关，是这份会话被平台标记**（PC/手机一致）。结论与后续实验协议见 `docs/小红书-图文图集链路调研.md` 第八节。
- 已就此修掉一个文案 bug：小红书「Unsupported URL」提示原文写死「不带 Cookie 时…」，用户已导入 Cookie 时会被误导（现改为「没导入 / 登录态被要求重新验证 / 平台限流」三种成因并列）。
- **真机第二轮（换全新 Cookie 后）**：应用发出的**第一条**自动化请求就被送进验证码页（`website-login/captcha`）→ **换新会话也救不了，被拦的是请求本身**。根因分析与「另谋出路」见 `docs/小红书-图文图集链路调研.md` 第九、十节。
- **实现坑（记下来别重踩）**：给应用准备 `cookies.txt` 时**绝不能带 UTF-8 BOM** —— PowerShell 的 `Set-Content -Encoding utf8` 会写 BOM，yt-dlp 直接报 `does not look like a Netscape format cookies file`（这句报错看着像 Cookie 内容坏了，其实是文件头）。要用 `[System.IO.File]::WriteAllText($p, $text, (New-Object System.Text.UTF8Encoding($false)))`。
- 提示文案同步修两处：①「Unsupported URL」把「登录页 / 验证码页」两种形态都写明，并加了「先别连着试」；② `_cookie_hint` 检测到 `website-login/captcha` 或 `/login?redirectPath` 时**不再叠加**「Cookie 可能已过期」（实测刚导入新 Cookie 却被这句误导）。离线断言 **60 项全通过**。
- 顺手装好了用户自己的逆向 SOP skill：`C:\Users\XCM\.trae-cn\skills\crawler-reverse-pentest-sop`（Trae CN 全局；10 个文件、哈希清单零差异）；并按用户要求镜像到 `C:\Users\XCM\.codex\skills\crawler-reverse-pentest-sop`。

### 七·补二 第三方解析接口实测（2026-10-17，全部未用用户 Cookie）

按用户定的顺序「先第三方 → 再 TLS 指纹 → 最后 WebView」做第一轮：

- **付费托管服务都有对应能力，但都要 key**：EasyDown（`POST https://api.easydown.org/api/v1/platforms/xiaohongshu/parse`）实测返回 `HTTP 401 {"invalid_token"}`；TikHub 需 `Authorization: Bearer <key>`（`/api/v1/xiaohongshu/app_v2/get_image_note_detail` 与 `get_video_note_detail` 两条接口，图文/视频分开）；Apify 类 actor 从 **$3.50/1k 条** 起。
- **免费聚合接口本轮一个都没测通**：`api.xingzhige.com/API/xhs/` 返回自家 404 页（路径已失效）；`api.pearktrue.cn` / `api.vvhan.com` / `api.zhaotianyun.cn` 在 TLS 层就断（`UNEXPECTED_EOF`）；`api.oioweb.cn` 自签证书；`tenapi.cn` 502；`cn.apihz.cn/api/other/xhs.php` 404。
  - 已排除「是我的网络问题」：同一环境访问 `www.bilibili.com`、`cn.apihz.cn` 都是 HTTP 200。
- **结论（待用户定）**：第三方这条路「能用，但要花钱 + 要 key + 用户的链接会经过第三方服务器」。要么用户自己注册买 key（应用里填），要么开发者内置 key（按量付费 + key 能被从 APK 里抠出来）。

### 七·补三 小红书改走「应用内 WebView」并修掉一批缺陷（2026-10-17 真机实测）

**方案定型**：小红书不再用「导出 Cookie + 裸 HTTP」（会被风控、还会把用户账号顶登出），改成**应用内 WebView 取数**：用户在这个窗口里登录一次，之后长期有效。新增 `XhsActivity`（骨架复用 `DouyinActivity`）+ `XhsParser` + 引擎 `save_xhs` 入口；`Engine.ParseResult.Err` 加了 `webviewKind`（`douyin`/`xhs`）让界面拉起对应解析页。

**真机实测踩到的坑（都已修）**

| 现象 | 根因（实测） | 修法 |
|---|---|---|
| 解析时**闪出几帧浏览器界面** | WebView 挂进视图树后立刻渲染 | 挂载但 `alpha=0f`（不闪、JS 仍执行） |
| 改成「不挂载」后**取不到数据**（20 秒超时） | **未 attach 的 WebView 里 `evaluateJavascript` 回调不执行** —— 抖音不挂载能用是因为它靠拦请求，不靠 JS。A/B 实测确认 | 回到「挂载 + alpha=0」 |
| 登录页**顶部被状态栏盖住** | 透明主题会画到状态栏下方，我们自己的提示条没留内边距 | 取 `status_bar_height` 做 padding，并把提示条缩小、加取消按钮 |
| 视频**下载报「不支持这种链接」** | ① `_download_target` 里白名单只有 `("抖音","X")` → 小红书拿到的是**原始短链**去喂 yt-dlp；② `DIRECT_URL_HOSTS` 也没收小红书域名 | 两处都把小红书加进去（两端同步） |
| 视频**没有声音** | 挑流规则按「高度最高」→ 挑中 `format:"fmp4"`、`audioChannels:0` 的**纯画面**条。ffprobe 实证：只有一条 hevc 视频流 | 改成**优先挑带音轨的**（`audioChannels>0`），再比高度；一条都没有时才退回纯画面并如实标注 |
| 图集**预览白屏**（下载后相册能看到） | `GalleryBox` 给图片**写死了抖音的 Referer**，小红书 CDN 拒掉（下载那条路不带 Referer 所以正常） | Referer 按平台给：小红书不带 |
| **实况图只拿到静态图** | `imageList[i].stream` 是**对象**（`{"EF4":[…],"EF5":[]…}`）而代码按**数组**解析 → 一直取空 | 按对象解析，并用同一套「优先带音轨」规则挑 |
| 登录后停在首页/其它页、等不到结果 | 登录完成后没有把用户带回笔记页 | 取数重试耗尽后，最多重载 3 次分享链接 |

**实测确认的小红书数据结构（以后不用再猜）**

```
笔记: __INITIAL_STATE__.note.noteDetailMap[<noteId>].note
  type        ← "video"（视频笔记）/ "normal"（图文）
  imageList[] ← 每张含 urlDefault/urlPre/infoList[{imageScene:WB_DFT|WB_PRV,url}]
  imageList[i].stream ← **实况图的动效就在这里**（对象，键是 EF4/EF5…）；
                        普通图文这里是 {}，所以天然可区分
  video.media.stream  ← 视频笔记的候选流，同样是对象
候选条目字段: masterUrl / backupUrls / width / height / size / format /
             videoCodec / audioCodec / audioChannels / audioBitrate / qualityType / streamDesc
  合流条: format="mp4",   audioCodec="aac", audioChannels=2, audioBitrate=64010
  纯画面: format="fmp4",  audioChannels=0,  audioBitrate=0
键名 EF4/EF5/EF6/EF7 是**平台自己的编码编号**，不是 h264/h265 —— 按编码名挑没意义
```

**验证证据（本轮实跑）**
- 视频：下载成功 → ffprobe = `h264 720x1280` + **`aac 44100Hz 2ch`**（修前只有一条 hevc 视频流、无音轨）
- 实况图：13 组 `NN.jpg` + `NN.mp4` **同名配对**（修前只有 jpg，其中一个还是 0 字节），界面显示「1/13 · 实况」
- 图集预览：截图确认**照片正常渲染**（修前白屏）
- 离线断言 `android/.build-tmp/test_xhs_platform.py`：**76 项全通过**

**⚠️ 必须让用户知情的副作用（实测反馈）**：在应用 WebView 里登录小红书后，**PC 网页端立刻提示「电脑设备登录超限，请重新登录」** —— 小红书的设备数有限，应用内登录把 PC 的会话挤掉了。这不是 bug，是平台策略，但用户要为此付出代价（详情见 `小红书-图文图集链路调研.md`）。

**已删除的临时物**：为定位「实况图字段 / 是否含音轨」临时加的 `xhsdiag` 诊断日志（JS `streamSummary` + `Log.i`）已全部移除。

**用户拍板的三条（2026-10-17，已实现）**

1. **接受 PC 被顶下线** → 小红书保留完整功能；并在**登录页当场写明**这句话（不能只在发布说明里说）。
2. **合集类优先给图集** → `save_xhs` 里判：`content_type == "video"` 但 `media` 有 **≥2** 张图时改判为图集（`live` / `images` 按有没有动效）。判据来自实测：**真正的视频笔记 imageList 恰好只有 1 张（封面）**。Kotlin 侧对这类笔记不再传 `video_raw`（那个对象里的 `mediaV2` 是一大段 JSON，没必要跨语言搬）。真机验证：`94pGuG2YUfw` 现在显示 **「1/49 · 实况」**（之前给的是那条平台合成的 98 秒视频）。
3. **小红书直接走内置浏览器** → `parse_url` 里小红书**短路**成 `need_webview(kind="xhs")`，不再先跑 yt-dlp。理由：① 继续用导出 Cookie 打小红书会让**用户账号**被风控；② 浏览器链路已全覆盖，白等几秒没必要。连带改动：两端 `defaults.json` 的小红书 `use_cookies` 改 **false**，设置页与网页端的 Cookie 说明同步去掉小红书（改成「走内置浏览器，首次登录一次」）。
   - `PLATFORM_ERROR_HINTS["小红书"]` 那几条**暂时不再被走到**（小红书不再跑 yt-dlp），保留是为了排查时参考，注释里已写明。

**离线断言**：`android/.build-tmp/test_xhs_platform.py` **83 项全通过** —— 新增覆盖：小红书只回浏览器模式、合集类改判图集、纯视频笔记不被误判、两端 `use_cookies` 必须为 false。

### 七·补四 界面文案与布局返工（2026-10-17，用户逐条点名）

| 用户要求 | 处理 |
|---|---|
| 解析时不要闪出浏览器画面 | **已修**：WebView 挂载但 `alpha=0f`（Chromium 认为可见 → JS 正常跑；一个像素都不画到屏幕上）。用户已确认「不会闪了」 |
| 解析首页加小红书、删掉「（底层是 yt-dlp…）」 | **已改**：「支持 YouTube / B站 / 抖音 / TikTok / X / Instagram / 小红书 等平台。境外站点需要开着 VPN。」 |
| 「复制链接」被档位选择器挤成两行 | **已修**：从按钮行里拿出来，改成**整行按钮**，放在「打开原视频」上面（两行都整行）。真机用 `https://v.douyin.com/uOrW98XyAh4/`（1280p 档位）验证：下载 + 档位一行、复制链接一行、打开原视频一行，均单行 |
| 关于页：小红书图文/图集/实况图已支持 | **已改**：「抖音、小红书的图文、图集、实况图都已支持」 |
| 关于页：不能一口咬定「由 yt-dlp 完成」 | **已改**：「**其他平台**的解析与下载由内置的 yt-dlp 完成…」；随后逐条说明：抖音走公开接口、小红书走内置浏览器（不必导 Cookie / 代价是电脑端被挤下线）、X 走备用链路 |
| 登录页适配手机 | **部分改善**：提示条按状态栏留内边距 + 缩小块头 + 可双指缩放。**但没能改成移动版页面** —— 见下 |

**移动版 UA 试过了，行不通（实测证据）**

- 换成移动版 UA 后，页面**内容能正常显示、也确实适配手机宽度**（截图确认）；
- 但取数失败。第一层原因是移动页面会自己尝试跳 `xhsdiscover://…` 拉起 App，WebView 处理不了这个 scheme，**整页被换成 `net::ERR_UNKNOWN_URL_SCHEME`**（截图证据）。加了 `shouldOverrideUrlLoading` 拦掉非 http(s) 后页面正常了；
- 第二层原因是**移动版页面里读不到数据**：`window.__INITIAL_STATE__` 中没有 `note.noteDetailMap`，所以取数仍为空。
- 结论：**取数必须用桌面版页面**，登录页因此只能是桌面布局。已退回桌面 UA 并保留 scheme 拦截（防同类坑）。

**顺带修掉一个文案渲染 bug**：`Hint(String)` 不走富文本，写进去的 `**…**` 会被原样显示成星号（实测在 Cookie 说明里露出来了）。已去掉星号；只有 `Hint(AnnotatedString)` + `markedBold` 才支持加粗。

### 八、微博适配（2026-10-17，已完成并真机验证）

**结论：微博走现成 yt-dlp 链路即可，不需要登录态、不需要自建链路。**

- **取证方式（不等用户提供链接）**：用 yt-dlp 自带的三个官方样例 URL，直接跑**安卓端引擎代码**（`ytdlp_engine.Engine`，PC 上进程内执行）：
  | 形态 | 样例 | 结果 |
  |---|---|---|
  | `weibo.com/tv/show/<id>` | `1034:4797699866951785` | ✅ 76s，档位 1080p/720p/180p |
  | `m.weibo.cn/status/<id>` | `4189191225395228` | ✅ 53s，档位 480p/360p |
  | `weibo.com/<uid>/<mid>` | `7827771738/N4xlMvjhI` | ✅ 918s，档位 1080p/720p/180p |
- **真机验证**：装包后 `ACTION_SEND` 投喂第一条，界面显示平台「微博」、标题、作者 · `3905382233` · `1:16`、档位 `1080p · H.264 · 11.6 MB`。
- 改动（两端同步，与小红书同一套口径）：
  - `PLATFORM_RULES` 加 `weibo.com` / `weibo.cn`（**`t.cn` 短链故意没认** —— 它是新浪通用短链，认了会把非微博内容也判成微博；等真实分享链接再定）
  - `defaults.json`：`weibo.com` / `weibo.cn` 均 `use_cookies: false`（实测三个样例都不带 Cookie 就能解析）
  - 品牌图标（simple-icons 的 `sinaweibo`，两端逐字一致）、配色 `#E6162D`、首字「微」
  - 报错提示只加语义明确的 **404 / 超时**；403 的成因没实测过，**不猜**，让它走通用兜底
  - 解析首页的支持列表加上微博；Windows 端 `PLATFORM_ORDER` / `PLATFORM_META` 同步
- 离线断言：`android/.build-tmp/test_xhs_platform.py` **108 项全通过**（含微博 4 种形态识别、3 条主机名锚定回归、两端 `use_cookies` 必须为 false、两端图标逐字一致）。

**未做 / 待补**：表情包、微博长文、视频+图混排等「内容形态」尚未逐条测；真实分享链接（尤其 `t.cn` 短链）到手后再补一轮。




