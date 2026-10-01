<div align="center">

# 多平台视频下载器

**一个真正开箱即用的多平台视频解析下载工具 —— Android 原生客户端 + Windows 本地 Web 端**

内置 Python 运行时、ffmpeg 与 JS 引擎，装完就能用，不需要装任何环境

[![Platform](https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-3DDC84?logo=android&logoColor=white)](#)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2-7F52FF?logo=kotlin&logoColor=white)](#)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)](#)
[![yt-dlp](https://img.shields.io/badge/yt--dlp-powered-red)](#)
[![License](https://img.shields.io/badge/License-MIT-blue)](#)

</div>

---

## 它解决什么问题

市面上的同类工具，要么是**套壳网页**（体验割裂、广告多），要么**装完还要自己配环境**（Python、ffmpeg、Node 一个个装）。这个项目的做法不同：

> **把一个完整的解析引擎塞进 APK 里。**

Python 3.12 运行时、ffmpeg、QuickJS 全部随包分发，其中 ffmpeg 和 QuickJS 都是**本项目用 NDK 自己交叉编译**的（不是从第三方下载的预编译二进制）。用户装完打开即用。

---

## 实测平台支持

以下结果全部在真机（vivo V2425A / Android 16 / arm64-v8a）上逐条验证：

| 平台 | 解析 | 下载 | 在线播放 | 是否需额外配置 |
|---|:---:|:---:|:---:|---|
| **B站** | ✅ 1080p | ✅ | — （分轨流） | 无 |
| **YouTube** | ✅ 1080p | ✅ | — （分轨流） | 需 VPN |
| **X（推特）** | ✅ 1280p | ✅ | ✅ | 无（走备用链路） |
| **抖音** | ✅ 原始画质 | ✅ 无水印 | ✅ | 无（首次可能需登录一次） |
| TikTok | ⚠️ | — | — | 需地区节点或 Cookie |
| Instagram | ⚠️ | — | — | 需 Cookie 登录 |

> 失败时应用会给出**中文可读的原因**和该往哪个方向排查，而不是把 yt-dlp 的英文报错原样丢给你。

---

## 核心亮点

### 1. 下载后直接在应用内播放 —— 解决一个行业难题

实测发现：**YouTube 的 53 个可用格式里，0 个是音视频合一的**；B站 也全是 `audio only` + `video only`。主流平台已全面 DASH 化，"拿直链直接播"这条路对它们根本走不通。

所以本项目的做法是：**下载时用 ffmpeg 把双轨合流成单文件，并把产物路径记进历史库**，之后点开历史记录就能直接看已下载的文件。

这是网页端做不到的 —— 网页只能播在线直链，而这两家已经不存在可播直链了。

### 2. 系统分享直达

在 B站 / 抖音 / YouTube 客户端里点「分享 → 视频解析下载」，链接自动填入并开始解析。省掉"复制 → 切应用 → 粘贴"三步。

（分享出来的文本常带前后缀，如 `【标题】 https://…`，所以做了 URL 提取并剥掉尾部中英文标点。）

### 3. 抖音无水印 —— 内置浏览器模式

抖音的接口要求 `a_bogus` 签名和登录态，纯 HTTP 客户端拿不到，必然 403。本项目让 **WebView 去当那个浏览器**：它自己算好签名、带上登录态，我们只在它请求详情接口时拦下响应。

> 踩坑记录：这里必须用**桌面版 UA**。移动版 UA 会让链接跳到 `iesdouyin.com` 分享页，数据是服务端直出在 HTML 里的，全程不触发接口 —— 拦截器永远等不到东西。

### 4. 两端共用同一套设计系统

配色、圆角、间距、字号全部逐条抽自同一份设计令牌，Windows 网页端与 Android 客户端看起来是**同一个产品**：安卓端按平台习惯改成底部导航（而非网页端的侧边栏），视觉语言不变。

### 5. 应用内反馈，且不把凭据塞进 APK

「设置 → 意见反馈」可以直接填问题、传截图、留联系方式，一键送到开发者手上。走的是企业微信的群机器人接口。

这里有个刻意的选择值得说明：**最常见做法是让 App 直接用 SMTP 发信，但那样邮箱授权码就必须硬编码进客户端**，而本仓库是公开的 —— 授权码等于挂在网上，谁拿到都能冒充你发信、还能读你的全部邮件。群机器人的 webhook 性质完全不同：它泄露了最多是别人往群里发消息，把机器人移出群就当场作废，不存在可被冒用的身份。

顺带解决了可达性：接口是腾讯自己的域名，国内不挂代理也能连通 —— 而本项目的用户里有大量抖音/B站用户，他们恰恰是不开代理的，反馈通道不能用境外服务。

---

## 架构

```
┌─────────────────────────── Android 客户端 ───────────────────────────┐
│                                                                      │
│  Compose UI  ──  解析页 / 历史页 / 设置页  +  底部下载进度条           │
│      │                                                               │
│      ├── Media3 / ExoPlayer      播放（本地文件优先于在线直链）        │
│      ├── Coil                    封面图                              │
│      └── WebView                 抖音浏览器模式解析                   │
│      │                                                               │
│  ────┼──────────────────────  ← Kotlin / Python 边界 ────────────────│
│      │                                                               │
│  Chaquopy 内嵌 CPython 3.12                                          │
│      │                                                               │
│      ├── yt-dlp                  解析 / 下载（进程内 Python API 调用） │
│      ├── ffmpeg（自编译）         音视频合流                           │
│      ├── QuickJS-NG（自编译）     YouTube n-sig 挑战求解               │
│      └── SQLite                  解析历史                            │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
```

### 为什么是"进程内调用"而不是子进程

Chaquopy 里的 CPython **不是可执行文件**，没有 `python` 可 fork；而且实测 Android 上 fork 出的子进程偶发 `SIGSEGV`（`_posixsubprocess` 层面的已知竞态）。所以 yt-dlp 的调用方式从"拼命令行 + subprocess"改成了**直接调 Python API**。

这个改动带来一个意外好处：下载完成后不需要像命令行版那样靠 `--print after_move` 回传文件路径，直接读 `info` 对象即可。

### 为什么原生二进制要自己编

ffmpeg 和 QuickJS 都是**本项目用 NDK r30 交叉编译**的（脚本见 `android/tools/`）：

- **ffmpeg 9.0.2**，LGPL 2.1，静态链接。只出 `ffmpeg` 一个程序，不带 ffprobe / ffplay —— 静态链接下每多一个程序就多一整份 libav* 代码。
- **QuickJS-NG 0.17.0**，剥掉调试符号后从 6.64 MB 降到 1.21 MB。

不用第三方预编译二进制的理由很直接：**那是一个会带着完整文件读写和网络能力、装进用户手机的可执行文件，来源不可追溯就不该进最终产物。**

两个 `.so` 的 LOAD 段都按 `0x4000` 对齐，4 KB 页与 16 KB 页设备通用。

---

## 踩过的坑（部分）

这些是真实调试出来的结论，写在这里省得后来者重走：

| 问题 | 原因与解法 |
|---|---|
| Gradle 报 `InternalProblems` 不存在 | Gradle 9.6+ 移除了 AGP 8.x 依赖的内部 API，本项目必须用 AGP 9.x |
| AGP 9 下 `org.jetbrains.kotlin.android` 报错 | AGP 9.0 起内置 Kotlin 支持，不能再显式声明该插件 |
| 开了 Compose 却报缺编译器 | AGP 9 内置 Kotlin，但**不含** Compose 编译器，仍需声明 `org.jetbrains.kotlin.plugin.compose` |
| `Module was compiled with an incompatible version of Kotlin` | Coil3 是 Kotlin Multiplatform 库，会带进 `kotlin-stdlib 2.4.x` 与 JetBrains 版 Compose，与 androidx 体系冲突。改用 Coil2 |
| 封面图不显示 | B站封面是 `http://`，Android 9+ 默认禁止明文流量，需开 `usesCleartextTraffic` |
| 下载成功但拿不到文件路径 | 顶层 `info` 上**没有** `filepath` 键，路径挂在 `requested_downloads` 上 |
| 中文标题下载失败 | `trim_file_name` 按字符算，而 Android 文件系统限制是 **255 字节**（一个汉字 3 字节）。改为按字节截断 |
| `adb input text` 输入变成了中文乱码 | 设备上的中文输入法会把 ASCII 当拼音转换。改用 `am start` 发分享意图喂链接 |
| Gradle 依赖解析总是超时 | JVM 不读系统代理，而本机是 TUN 模式代理，需在 `gradle.properties` 显式配 `systemProp.https.proxyHost` |
| 反馈里的图片一张都发不出去 | `inJustDecodeBounds = true` 时 `BitmapFactory.decodeStream` **按设计返回 null**（它只负责往 `Options` 填宽高）。原代码写成 `openInputStream(uri)?.use { decodeStream(...) } ?: return null`，于是每次都当成"读取失败"，压缩函数无条件返回 null |
| 播放器的进度条点不到 | Media3 的控制器贴在 PlayerView 底部，而竖屏视频按真实比例算出的高度很长，播放器下边缘会落到应用底部导航栏后面，进度条正好被盖住。给播放区加了「不超过屏高 40%」的上限 |
| 历史列表里解析时间从来不显示 | 算过宽度：行内可用约 1085px，平台图标 + 三个操作图标 + 间隙就吃掉约 630px，再塞一个 `2026-10-01 10:06`（约 310px），标题只剩 5 个字。改成时间单独占一行 |

---

## 快速开始

### 环境要求

- JDK 17+
- Android SDK（platform 37、build-tools 36.0.0）
- NDK r30（仅重建原生二进制时需要）
- Python 3.12（构建期供 Chaquopy 打包用）

### 构建

```bash
# 1. 编译原生二进制（首次或需要更新时执行，产物在 jniLibs/）
"$GIT_BASH" android/tools/build-ffmpeg.sh
"$GIT_BASH" android/tools/build-quickjs.sh

# 2. 构建 APK
gradle -p android assembleDebug

# 3. 安装到设备
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

> 两个原生库体积较大，未纳入版本控制，由脚本生成。

---

## 项目结构

```
yt-dIp/
├── android/                     Android 原生客户端
│   ├── app/src/main/
│   │   ├── java/com/ytdlp/android/
│   │   │   ├── MainActivity.kt        入口 + 接收系统分享
│   │   │   ├── DouyinActivity.kt      抖音浏览器模式解析页
│   │   │   ├── FeedbackActivity.kt    意见反馈页宿主
│   │   │   ├── ProbeActivity.kt       引擎自检页（诊断用）
│   │   │   ├── engine/                Kotlin ↔ Python 桥接与数据模型
│   │   │   │   └── FeedbackSender.kt  反馈发送（企业微信群机器人）
│   │   │   └── ui/                    Compose 界面（设计令牌 / 三页 / 反馈页 / 组件）
│   │   │       └── BrandIcons.kt      平台品牌矢量，与网页端 brand-icons.js 同源
│   │   ├── python/                    引擎层（yt-dlp 调用、历史库、报错翻译）
│   │   └── jniLibs/arm64-v8a/         自编译的原生二进制
│   └── tools/                         交叉编译脚本（ffmpeg / QuickJS）
│
└── windows/                     Windows 本地 Web 端
    ├── main.py                        本地 HTTP 服务（仅监听 127.0.0.1）
    ├── engine.py                      解析 / 下载 / 历史
    └── web/                           纯网页界面 + 设计令牌源
```

---

## 设计取舍记录

**保存位置为什么不能随便选**
Android 的 SAF 给的是 `content://` URI，而 yt-dlp 走普通文件 API 读不了。所以只能"系统下载目录 / 应用私有目录"二选一，优先前者，无权限时自动回退并在界面上如实说明。

**为什么不自动播放**
自动起播会立刻拉一个几百 MB 的连接，而用户可能只想下载。

**解析为什么不能取消**
进程内调用没有子进程可 kill，界面的"取消"只能做到"我不等了"，Python 侧会自己跑完。所以界面自己收 75 秒超时（与 Windows 端一致）。

---

## 更新日志

### v1.0.1

**新增**
- 应用内「意见反馈」：填问题、传截图（最多 3 张，自动压缩后发送）、留联系方式，一键直达开发者
- 失败原因**按平台细化**：同一个 HTTP 403，抖音是「境外 IP 被拦」、X 是「没带 Cookie」、TikTok 是「地区限制」，分别给出对应说明
- 播放器补齐**可拖拽进度条 + 当前/总时长**
- 详情页补上**原始解析链接**与平台账号 ID

**改进**
- 平台图标换成 simple-icons 的**官方品牌矢量**（此前只是「色块 + 首字」），与 Windows 网页端同源
- 历史列表改为紧凑两行：第一行「平台图标 + 标题 + 操作」，第二行「解析时间」—— 一屏能多看近一倍条目，且不再丢时间
- 点开历史条目时自动滚到列表顶部，展开内容一屏装得下
- 竖屏视频按自身比例铺满播放区，不再被塞进 16:9 横条压扁

**修复**
- 反馈里的图片必然发送失败（`inJustDecodeBounds` 与 `decodeStream` 返回值混用，压缩函数无条件返回 null）
- 点播放后的等待期会露出「需下载后才能播放」的错误提示（内部把"不能播"和"正在播"混成了一个状态）
- 「连不上网络」这类最常见的失败此前会落到笼统的兜底文案

### v1.0.0

第一个正式版本：Android 原生客户端 + Windows 本地 Web 端，内置完整解析引擎。

---

## 免责声明

本项目仅供**个人学习与技术研究**使用。请遵守目标平台的用户协议与著作权法律，不要用于商业用途或大规模抓取。下载的内容版权归原作者所有。

---

## License

MIT
