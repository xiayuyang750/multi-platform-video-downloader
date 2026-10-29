/* 视频解析下载 — 前端交互逻辑
 *
 * 界面状态全部在前端维护；需要调用引擎或落盘的动作，通过 window.pywebview.api
 * 交给 Python 侧执行。这样界面代码可以原样复用到安卓 WebView。
 */

/* ==================== 平台信息 ==================== */
const PLATFORM_META = {
  'YouTube':   { cls: 'pf-youtube',   letter: 'Y' },
  'B站':       { cls: 'pf-bilibili',  letter: 'B' },
  '抖音':      { cls: 'pf-douyin',    letter: 'D' },
  'TikTok':    { cls: 'pf-tiktok',    letter: 'T' },
  'X':         { cls: 'pf-x',         letter: 'X' },
  'Instagram': { cls: 'pf-instagram', letter: 'I' },
  '小红书':     { cls: 'pf-xiaohongshu', letter: '红' },
  '微博':       { cls: 'pf-weibo',     letter: '微' },
  '快手':       { cls: 'pf-kuaishou',  letter: '快' },
  '微信公众号':  { cls: 'pf-wechat',    letter: '公' },
  '微信视频号':  { cls: 'pf-channels',  letter: '视' },
  // 未识别平台的聚合入口。库里存的值是「其他」，只在界面上显示为「其他平台」，
  // 这样已有的历史记录不用做数据迁移。
  '其他':      { cls: 'pf-other',     letter: '·', label: '其他平台' },
};
// 「其他」是兜底聚合入口，名字必须与 engine.detect_platform 的返回值一致
// （排序规则见 platformsInUse）。

function platformMeta(name) {
  return PLATFORM_META[name] || { cls: 'pf-other', letter: '·' };
}

/* 平台图标：优先用内联的品牌 SVG（见 brand-icons.js），没有就退回字母。
 * 用 currentColor 填充，颜色由 .pf-* 的背景色衬托，深浅主题都适用。 */
function brandIcon(name) {
  const d = (typeof BRAND_ICONS !== 'undefined' && BRAND_ICONS[name]) || '';
  if (!d) return esc(platformMeta(name).letter);
  return `<svg viewBox="0 0 24 24" width="13" height="13" fill="currentColor" aria-hidden="true"><path d="${d}"/></svg>`;
}

/* ==================== 图标 ====================
 * 用内联 SVG 而不是特殊 Unicode 字符（⧉ ⟳ ▶ ↓ ✕）：
 * 那些符号在部分 Windows 字体里显示不出来，会变成空白按钮。
 */
const ICONS = {
  copy:
    '<svg viewBox="0 0 16 16" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.4">' +
    '<rect x="5.6" y="5.6" width="7.9" height="7.9" rx="1.6"/>' +
    '<path d="M10.4 5.6V3.9c0-.8-.6-1.4-1.4-1.4H3.9c-.8 0-1.4.6-1.4 1.4v5.1c0 .8.6 1.4 1.4 1.4h1.7"/></svg>',
  reanalyze:
    '<svg viewBox="0 0 16 16" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round">' +
    '<path d="M13.1 8a5.1 5.1 0 1 1-1.5-3.6"/>' +
    '<path d="M13.3 2.7v3.1h-3.1"/></svg>',
  play:
    '<svg viewBox="0 0 16 16" width="15" height="15" fill="currentColor">' +
    '<path d="M5.6 3.7v8.6l6.9-4.3z"/></svg>',
  download:
    '<svg viewBox="0 0 16 16" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round">' +
    '<path d="M8 2.6v6.8"/><path d="M5.1 6.7 8 9.6l2.9-2.9"/><path d="M3.2 12.6h9.6"/></svg>',
  del:
    '<svg viewBox="0 0 16 16" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round">' +
    '<path d="M4.2 4.2l7.6 7.6M11.8 4.2l-7.6 7.6"/></svg>',
  playBig:
    '<svg viewBox="0 0 24 24" width="28" height="28" fill="currentColor">' +
    '<path d="M8 5.4v13.2L19 12z"/></svg>',
};

/* ==================== 工具函数 ==================== */
function $(sel) { return document.querySelector(sel); }

function esc(v) {
  return String(v == null ? '' : v)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function formatDuration(sec) {
  const s = Number(sec) || 0;
  if (!s) return '--:--';
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const ss = s % 60;
  const p = n => String(n).padStart(2, '0');
  return h ? `${h}:${p(m)}:${p(ss)}` : `${m}:${p(ss)}`;
}

/* 历史记录用「绝对时间」显示，比“几分钟前”更便于回查 */
function formatTime(ts) {
  const t = Number(ts) || 0;
  if (!t) return '';
  const d = new Date(t * 1000);
  const p = n => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

/* ==================== 链接提取与平台识别 ====================
 * 抖音 / 哔哩哔哩的「复制链接」会附带一大段文案，用户直接整段粘贴即可。
 * 这里负责从任意文本里把真正的链接捞出来。
 */
const URL_RE = /https?:\/\/[^\s"'<>）】》。，、；！？]+/i;

function extractUrl(text) {
  const m = String(text || '').match(URL_RE);
  if (!m) return '';
  return m[0].replace(/[）】》。，、；！？"'<>]+$/, '');
}

function detectPlatform(url) {
  const u = String(url || '');
  if (/(^|\/\/)([a-z0-9-]+\.)*youtu\.?be/i.test(u)) return 'YouTube';
  if (/(^|\/\/)([a-z0-9-]+\.)*(bilibili\.com|b23\.tv)/i.test(u)) return 'B站';
  if (/(^|\/\/)([a-z0-9-]+\.)*(douyin\.com|iesdouyin\.com)/i.test(u)) return '抖音';
  if (/(^|\/\/)([a-z0-9-]+\.)*tiktok\.com/i.test(u)) return 'TikTok';
  if (/(^|\/\/)([a-z0-9-]+\.)*(x\.com|twitter\.com)/i.test(u)) return 'X';
  if (/(^|\/\/)([a-z0-9-]+\.)*instagram\.com/i.test(u)) return 'Instagram';
  return '其他';
}

/* ==================== 后端接口 ====================
 * 后端是同一个 Python 进程起的本地服务：POST /api/call {method, args}
 * token 由启动时打开的地址带上（?t=...），防止浏览器里其它网页调用本地接口。
 */
/* token 获取策略（三层，越靠前越优先）：
 * 1. 启动时网址里的 ?t=...        —— 正常路径
 * 2. 上次存下的（localStorage）   —— 刷新页面、手输地址、从历史记录点开时仍可用
 * 3. 向本地服务 /api/token 现取   —— 服务重启后旧标签页也能自愈
 * 只靠第 1 层的话，一旦地址里没带 token，界面就彻底报废，只能重启软件。 */
let TOKEN = new URLSearchParams(location.search).get('t') || '';
try {
  if (TOKEN) localStorage.setItem('yd_token', TOKEN);
  else TOKEN = localStorage.getItem('yd_token') || '';
} catch { /* 隐私模式下 localStorage 可能不可用，忽略即可 */ }

let tokenReady = null;

function ensureToken() {
  if (TOKEN) return Promise.resolve(TOKEN);
  if (!tokenReady) {
    tokenReady = fetch('/api/token')
      .then(r => (r.ok ? r.json() : {}))
      .then(d => {
        TOKEN = (d && d.token) || '';
        try { if (TOKEN) localStorage.setItem('yd_token', TOKEN); } catch {}
        return TOKEN;
      })
      .catch(() => '');
  }
  return tokenReady;
}

/* 封面和视频直链都要走本地代理：
 * 目标 CDN 会校验 Referer，浏览器直接访问会 403，表现为「一直转圈 + 黑屏」。
 */
function proxied(url, sourceUrl) {
  if (!url) return '';
  return `/media?t=${encodeURIComponent(TOKEN)}`
    + `&u=${encodeURIComponent(url)}`
    + `&ref=${encodeURIComponent(sourceUrl || '')}`;
}

async function callApi(method, ...args) {
  await ensureToken();
  const send = () => fetch(`/api/call?t=${encodeURIComponent(TOKEN)}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ method, args }),
  });
  try {
    let res = await send();
    if (res.status === 403) {
      // token 已失效（服务重启过、或页面是旧标签）：丢掉重取一次再试。
      // 没有这层自愈的话，用户只能重启软件 —— 这正是「偶发解析失败」的来源。
      TOKEN = '';
      tokenReady = null;
      try { localStorage.removeItem('yd_token'); } catch {}
      await ensureToken();
      res = await send();
    }
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      console.error('[api]', method, res.status, data);
      if (res.status === 403) {
        return { error: '与服务连接已失效，请刷新页面（按 Ctrl + F5）' };
      }
      return { error: data.error || `HTTP ${res.status}` };
    }
    return data;
  } catch (e) {
    console.error('[api]', method, e);
    return { error: '无法连接本地服务，请确认启动窗口还开着' };
  }
}

/* 界面指纹 + 自愈。
 * 1) 显示在标签页标题上，不用开 F12 就能看到，用于确认跑的是哪一版代码。
 * 2) 定期比对服务端指纹：不一致说明这个标签页里仍是旧代码，自动刷新一次。
 *    没有这层的话，会出现「页面开着旧代码、却对着新服务」这种最难排查的状态
 *    —— 服务停了网页不会自己关，旧标签页会一直留着。 */
let buildAtLoad = '';
let buildInfo = null;

function syncBuild() {
  return callApi('get_build').then(b => {
    if (!b || !b.build) return;
    if (buildAtLoad && b.build !== buildAtLoad) {
      console.warn(`[界面版本] 服务端已更新为 ${b.build}，本页仍是 ${buildAtLoad}，自动刷新`);
      location.reload();
      return;
    }
    buildAtLoad = b.build;
    buildInfo = b;
    renderDiagnostics();
    if (!/\[[0-9a-f]{8}\]/.test(document.title)) {
      document.title = `${document.title} [${b.build}]`;
    }
    console.log(`[界面版本] ${b.build}  构建时间 ${new Date(b.mtime * 1000).toLocaleString()}`);
  });
}

syncBuild();
setInterval(syncBuild, 15000);

/* ==================== 状态 ==================== */
const state = {
  history: [],
  platform: null,
  expandedId: null,
  defaults: null,
  busy: false,
  // 最近一次解析结果：翻图集时用它，避免只靠 DOM 里的字段拼不回来
  lastResult: null,
  // 当前选中的档位下标（0 = 最高档）；换一条作品时重置
  tierIndex: 0,
  // 结构化设置（含「下载时自动改 MD5」与 ffmpeg 状态）
  settings: null,
  // 反馈邮箱（后端下发）
  feedbackMail: '',
};

/* ==================== 外观 ====================
 * 三档：浅色 / 深色 / 跟随系统。统一由 JS 解析成 data-theme 再套 CSS 变量，
 * 避免「系统媒体查询」与「手动选择」两套规则打架。
 */
const THEME_KEY = 'yd_theme';

function themeMode() {
  try { return localStorage.getItem(THEME_KEY) || 'system'; } catch { return 'system'; }
}

function systemPrefersDark() {
  return !!(window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
}

function applyTheme(mode) {
  const dark = mode === 'dark' || (mode === 'system' && systemPrefersDark());
  document.documentElement.dataset.theme = dark ? 'dark' : 'light';
  document.documentElement.dataset.themeMode = mode;
  document.querySelectorAll('#theme-row .chip').forEach(b => {
    b.classList.toggle('is-active', b.dataset.themeMode === mode);
  });
}

function setThemeMode(mode) {
  try { localStorage.setItem(THEME_KEY, mode); } catch { /* 隐私模式忽略 */ }
  applyTheme(mode);
}

function initTheme() {
  applyTheme(themeMode());
  if (!window.matchMedia) return;
  const mq = window.matchMedia('(prefers-color-scheme: dark)');
  const onChange = () => { if (themeMode() === 'system') applyTheme('system'); };
  if (mq.addEventListener) mq.addEventListener('change', onChange);
  else if (mq.addListener) mq.addListener(onChange);
}

/* ==================== 视图切换 ==================== */
function switchView(name) {
  document.querySelectorAll('.nav-item').forEach(b => {
    b.classList.toggle('is-active', b.dataset.view === name);
  });
  document.querySelectorAll('.view').forEach(v => {
    v.classList.toggle('is-active', v.dataset.view === name);
  });
  // MD5 页是「我已经下过什么」的视图：切进来才刷一次，不必开机白读
  if (name === 'md5') loadMd5Downloads();
}

/* ==================== 平台切换栏 ==================== */
function platformCounts() {
  const counts = {};
  state.history.forEach(h => {
    counts[h.platform] = (counts[h.platform] || 0) + 1;
  });
  return counts;
}

/* 顺序与安卓端一致：记录条数多的排前面，条数相同按平台名；「其他」是兜底桶，
 * 无论有没有记录都固定在最后 —— 它不是一个平台，排前面会让人误以为常用。 */
function platformsInUse() {
  const counts = platformCounts();
  const list = Object.keys(counts)
    .filter(p => p !== '其他')
    .sort((a, b) => (counts[b] - counts[a]) || a.localeCompare(b, 'zh'));
  return list.concat('其他');
}

function renderTabs() {
  const box = $('#platform-tabs');

  if (!state.history.length) {
    box.innerHTML = '';
    box.classList.add('is-hidden');
    state.platform = null;
    return;
  }
  box.classList.remove('is-hidden');

  const list = platformsInUse();
  if (state.platform && !list.includes(state.platform)) state.platform = null;
  const counts = platformCounts();

  const allTab = `<button class="tab${state.platform === null ? ' is-active' : ''}" data-platform="" type="button">
      <span>全部</span>
      <span class="tab-badge">${state.history.length}</span>
    </button>`;

  box.innerHTML = allTab + list.map(p => {
    const count = counts[p] || 0;
    const meta = platformMeta(p);
    return `<button class="tab${p === state.platform ? ' is-active' : ''}" data-platform="${esc(p)}" type="button">
      <span class="pf-icon ${meta.cls}">${brandIcon(p)}</span>
      <span>${esc(meta.label || p)}</span>
      <span class="tab-badge">${count}</span>
    </button>`;
  }).join('');
}

function onPlatformTabClick(e) {
  const tab = e.target.closest('.tab');
  if (!tab) return;
  // 空字符串表示「全部」——与 state.platform = null 对应
  const next = tab.dataset.platform || null;
  if (next === state.platform) return;
  state.platform = next;
  state.expandedId = null;
  renderTabs();
  renderHistory();
}

/* 本地产物走 /file（后端只放行输出目录内、或历史库里记过的路径）。
 * 在线直链仍走 /media 代理：CDN 会校验 Referer，浏览器直连会 403。 */
function localUrl(path) {
  if (!path) return '';
  return `/file?t=${encodeURIComponent(TOKEN)}&p=${encodeURIComponent(path)}`;
}

/** 播放源：本地文件优先于在线直链（直链带时效，本地文件一直在）。
 *  与安卓端 Video.playSource 同一套判定。 */
function playSourceOf(item) {
  if (item.content_type === 'images' || item.content_type === 'live') return '';
  if (item.local_path) return localUrl(item.local_path);
  if (item.resolved_url && item.play_kind === 'progressive') {
    return proxied(item.resolved_url, item.source_url);
  }
  return '';
}

function renderPlayerBlock(item) {
  const src = playSourceOf(item);
  if (!src) {
    const msg = item.play_kind === 'none'
      ? '该平台是「视频 + 音频」分离流，没有可直接播放的链接 —— 请点「下载到本地」，下载完就能播。'
      : '直链已失效，请点「重新解析」获取新直链。';
    return `<div class="player-frame player-empty">${msg}</div>`;
  }

  const isLocal = !!item.local_path;
  const poster = item.thumbnail ? proxied(item.thumbnail, item.source_url) : '';
  const posterAttr = poster ? ` poster="${esc(poster)}"` : '';

  return `
    <div class="player-frame player-box" data-source="${esc(item.source_url || '')}" data-local="${isLocal ? '1' : '0'}">
      ${poster ? `<img class="player-poster" src="${esc(poster)}" alt="封面">` : ''}
      <button class="player-play" data-act="play-inline" type="button" title="播放">${ICONS.playBig}</button>
      <video class="player-video is-hidden" controls preload="none" src="${esc(src)}"${posterAttr}></video>
      <div class="player-error is-hidden">播放失败。</div>
    </div>`;
}

/* ==================== 图文 / 图集 / 实况图 ====================
 * 这类内容是一组媒体地址（media_json），没有单一「直链」，所以不显示播放器，
 * 改成逐张翻页。实况图多出来的「动」那一段在下载时按同名 .mp4 一起落盘。
 */
function isGallery(item) {
  return item.content_type === 'images' || item.content_type === 'live';
}

function mediaListOf(item) {
  if (!item || !item.media_json) return [];
  try {
    const arr = JSON.parse(item.media_json);
    return Array.isArray(arr) ? arr.filter(m => m && m.url) : [];
  } catch {
    return [];
  }
}

function renderGallery(item, page) {
  const media = mediaListOf(item);
  if (!media.length) {
    return `<div class="player-frame player-empty">这条作品的媒体地址为空，请重新解析。</div>`;
  }
  page = Math.max(0, Math.min(page || 0, media.length - 1));
  const m = media[page];
  const isVideo = (m.kind || 'image') === 'video';
  const src = proxied(m.url, item.source_url);
  return `<div class="gallery" data-page="${page}">
    <div class="gallery-stage">
      ${isVideo
        ? `<video class="gallery-video" controls preload="none" src="${esc(src)}"></video>`
        : `<img class="gallery-image" src="${esc(src)}" alt="第 ${page + 1} 张">`}
      <span class="gallery-count">${page + 1} / ${media.length}</span>
      ${m.live ? '<span class="gallery-live">实况</span>' : ''}
    </div>
    <div class="gallery-bar">
      <button class="btn btn-ghost btn-sm" data-act="gallery-prev" type="button">上一张</button>
      <span class="gallery-hint">${item.content_type === 'live'
        ? '实况图：下载时另存同名的 .mp4' : '图集'}</span>
      <button class="btn btn-ghost btn-sm" data-act="gallery-next" type="button">下一张</button>
    </div>
  </div>`;
}

/** 翻页：只替换这一块 DOM，不重绘整张卡片（否则正文展开状态会被重置）。 */
function navigateGallery(hit, dir) {
  const gallery = hit.closest('.gallery');
  if (!gallery) return;
  const host = hit.closest('.result') || hit.closest('.history-item');
  if (!host) return;

  const isParse = host.classList.contains('result');
  const item = isParse ? state.lastResult : state.history.find(h => h.id === host.dataset.id);
  if (!item) return;

  const media = mediaListOf(item);
  if (!media.length) return;
  const page = Number(gallery.dataset.page) || 0;
  const next = (page + dir + media.length) % media.length;
  gallery.outerHTML = renderGallery(item, next);

  // 「下载第 N 张」上的数字跟着翻页走
  const cp = host.querySelector('.cur-page');
  if (cp) cp.textContent = String(next + 1);
}

/** 当前翻到第几张（1 起），用于「下载第 N 张」。 */
function currentGalleryPage(hit) {
  const gallery = hit.closest('.gallery');
  return gallery ? (Number(gallery.dataset.page) || 0) + 1 : 1;
}

/* ==================== 档位 ====================
 * 引擎已经把档位按「好 → 差」排好，第一项就是默认的最高档，界面不再算一遍。
 * 直链型档位带 url、DASH 型档位带 selector，两种都原样交给后端。
 */
function tiersOf(item) {
  return (item && Array.isArray(item.tiers)) ? item.tiers : [];
}

function currentTier() {
  const tiers = tiersOf(state.lastResult);
  if (!tiers.length) return null;
  return tiers[Math.min(Math.max(state.tierIndex, 0), tiers.length - 1)];
}

function tierSpec(tier) {
  if (!tier) return '';
  const o = {};
  if (tier.url) o.url = tier.url;
  if (tier.selector) o.selector = tier.selector;
  return JSON.stringify(o);
}

function md5TextFor(info, tier) {
  const hash = (tier && tier.hash) || (info && info.source_md5) || '';
  return hash ? `MD5（平台自报）：${hash}` : 'MD5：下载完成后计算';
}

function onResultChange(e) {
  if (e.target.id === 'result-rewrite') {
    const on = !!e.target.checked;
    callApi('set_rewrite_md5', on).then(s => {
      if (s && !s.error) state.settings = s;
      const cb = $('#rewrite-md5');
      if (cb) cb.checked = on;
    });
    return;
  }
  const sel = e.target.closest('#tier-select');
  if (!sel) return;
  state.tierIndex = Number(sel.value) || 0;
  const line = $('#result-md5');
  if (line) line.textContent = md5TextFor(state.lastResult, currentTier());
}

function contentTypeHint(item) {
  if (item.content_type === 'preview') {
    return '视频号内容由微信客户端加密分发，本应用无法直接下载' +
      '（微信内一般也没有「保存视频」入口）。';
  }
  if (item.content_type === 'text') {
    return '这篇公众号是纯文字，没有可下载的图片或视频；正文可以直接复制。';
  }
  return '';
}

/* 装一个「卡死」兜底计时器：部分编码（H.265 / AV1）浏览器解不了，
 * 表现为黑屏且不触发 error 事件。8 秒后仍停在 0 秒即判失败。 */
function armStall(box) {
  const video = box.querySelector('.player-video');
  if (!video) return;
  clearTimeout(video._stallTimer);
  video._stallTimer = setTimeout(() => {
    if (video.currentTime === 0 && !video.paused) {
      recoverPlay(box, '播放没有进展，可能是浏览器不支持该视频编码。');
    }
  }, 8000);
}

/* 播放失败后的补救：直链带时效签名，过期后必然被平台拒绝。
 * 与其提示用户手动点「重新解析」，不如自动重解析一次拿新直链再播
 * —— 这就是「主路线失败就自动换一条路线」。只自动重试一次，避免网络
 * 不通时陷入反复请求。 */
async function recoverPlay(box, reason) {
  const video = box.querySelector('.player-video');
  const err = box.querySelector('.player-error');
  if (!video || !err) return;
  clearTimeout(video._stallTimer);

  // 播的是本地产物时不要自动重解析：重解析只会刷新在线直链，救不了本地文件
  if (box.dataset.local === '1') {
    video.classList.add('is-hidden');
    err.textContent = `${reason} 本地文件可能已被移动或删除，请刷新页面后重试。`;
    err.classList.remove('is-hidden');
    return;
  }

  if (box.dataset.retried === '1') {
    video.classList.add('is-hidden');
    err.textContent = `${reason} 已自动重试过一次仍未成功，可点「下载到本地」观看。`;
    err.classList.remove('is-hidden');
    return;
  }
  box.dataset.retried = '1';
  err.textContent = '正在自动重新解析…';
  err.classList.remove('is-hidden');

  const source = box.dataset.source || '';
  if (!source) {
    video.classList.add('is-hidden');
    err.textContent = reason;
    return;
  }

  const info = await callApi('parse_url', source);
  if (info && info.ok && info.resolved_url) {
    video.src = proxied(info.resolved_url, source);
    video.classList.remove('is-hidden');
    err.classList.add('is-hidden');
    video.load();
    video.play().catch(() => {});
    armStall(box);
  } else {
    video.classList.add('is-hidden');
    err.textContent = `${reason} 自动重新解析也失败了。`;
  }
}

/* 点击封面上的播放按钮：把封面换成播放器并开始播（纯 DOM 操作，无需重新请求后端） */
function handlePlayInline(btn) {
  const box = btn.closest('.player-box');
  if (!box) return;

  const video = box.querySelector('.player-video');
  if (!video) return;

  const poster = box.querySelector('.player-poster');
  if (poster) poster.classList.add('is-hidden');
  btn.classList.add('is-hidden');

  video.classList.remove('is-hidden');
  video.play().catch(() => {
    /* 浏览器可能拦截自动播放，此时用户手动点播放器即可 */
  });
  armStall(box);
}

/* ==================== 历史列表 ==================== */
/** 展开态的一行「标签 值」。标签定宽，值可换行 —— 长链接不会把布局撑破。 */
function detailLine(label, value) {
  if (!value) return '';
  return `<div class="detail-line">
    <span class="detail-label">${esc(label)}</span>
    <span class="detail-value">${esc(value)}</span>
  </div>`;
}

function renderItem(item) {
  const meta = platformMeta(item.platform);
  const open = state.expandedId === item.id;
  const hasLocal = !!item.local_path;

  // 折叠态：标题 + 「时间 · 已下载」。作者/账号/时长都收进展开态，
  // 与安卓端一致（安卓是因为屏窄，桌面端则是为了让一屏能多放几条）。
  const subText = [formatTime(item.resolved_at), hasLocal ? '已下载' : '']
    .filter(Boolean).join(' · ');

  // 折叠态就给出操作小图标，不必展开即可操作
  const ops = `
    <button class="icon-btn" data-act="copy"      data-id="${esc(item.id)}" title="复制链接" type="button">${ICONS.copy}</button>
    <button class="icon-btn" data-act="reanalyze" data-id="${esc(item.id)}" title="重新解析" type="button">${ICONS.reanalyze}</button>
    <button class="icon-btn" data-act="play"      data-id="${esc(item.id)}" title="播放" type="button">${ICONS.play}</button>
    <button class="icon-btn" data-act="download"  data-id="${esc(item.id)}" title="下载" type="button">${ICONS.download}</button>
    <button class="icon-btn is-danger" data-act="delete" data-id="${esc(item.id)}" title="删除" type="button">${ICONS.del}</button>`;

  const fields = [
    detailLine('作者', item.uploader),
    detailLine('账号 ID', item.uploader_id),
    detailLine('时长', item.duration ? formatDuration(item.duration) : ''),
    detailLine('原始链接', item.source_url),
    detailLine('本地文件', hasLocal ? '已下载' : ''),
  ].join('');

  const gallery = isGallery(item);
  const hint = contentTypeHint(item);
  const canDownload = !hint;

  const detail = open ? `
    <div class="history-detail">
      ${gallery ? renderGallery(item, 0) : (hint ? '' : renderPlayerBlock(item))}
      <div class="detail-fields">${fields}</div>
      ${hint ? `<p class="detail-note">${esc(hint)}</p>` : ''}
      <div class="history-detail-actions">
        ${canDownload ? `<button class="btn btn-primary" data-act="download" data-id="${esc(item.id)}" type="button">${gallery ? '下载全部' : '下载到本地'}</button>` : ''}
        ${canDownload && gallery ? `<button class="btn btn-ghost" data-act="download-page" data-id="${esc(item.id)}" type="button">下载第 <span class="cur-page">1</span> 张</button>` : ''}
        <button class="btn btn-ghost" data-act="open-source" data-id="${esc(item.id)}" type="button">打开原视频</button>
      </div>
    </div>` : '';

  return `
  <div class="history-item" data-id="${esc(item.id)}">
    <div class="history-row" data-act="toggle" data-id="${esc(item.id)}">
      <span class="pf-icon ${meta.cls}">${brandIcon(item.platform)}</span>
      <div class="history-main">
        <p class="history-title">${esc(item.title || '未命名')}</p>
        <p class="history-sub${hasLocal ? ' is-local' : ''}">${esc(subText)}</p>
      </div>
      <div class="history-ops">${ops}</div>
    </div>
    ${detail}
  </div>`;
}

function renderHistory() {
  const box = $('#history-list');
  if (!state.history.length) {
    box.innerHTML = `<div class="history-empty">还没有解析记录，去「解析」页粘贴一个链接试试</div>`;
    return;
  }
  // state.platform 为 null 表示「全部」
  const items = state.platform
    ? state.history.filter(h => h.platform === state.platform)
    : state.history;
  if (!items.length) {
    box.innerHTML = `<div class="history-empty">该平台还没有记录</div>`;
    return;
  }
  box.innerHTML = items.map(renderItem).join('');
}

function renderHistoryAll() {
  renderTabs();
  renderHistory();

  // 「清空该平台」只在选了某个平台时才有意义；「全部」下只留「全部清空」
  const cb = $('#clear-platform-btn');
  if (cb) {
    cb.classList.toggle('is-hidden', state.platform === null);
    if (state.platform) cb.textContent = `清空「${state.platform}」`;
  }
}

/* ==================== 解析结果卡片 ==================== */
function renderResult(info) {
  const box = $('#parse-result');
  const meta = platformMeta(info.platform);
  const bits = [
    formatDuration(info.duration),
    info.quality,
    info.filesize,
    info.uploader,
    info.uploader_id,   // 平台唯一标识（推特号 / B站UID 等），没有则自动省略
  ].filter(Boolean).map(esc).join(' · ');

  const title = info.source_url
    ? `<a href="${esc(info.source_url)}" target="_blank" rel="noreferrer">${esc(info.title || '未命名')}</a>`
    : esc(info.title || '未命名');

  // 走备用链路时说一句，否则用户不理解为什么慢 / 偶尔失败
  const viaText = info.via === 'browser' ? '浏览器模式'
    : info.via === 'service' ? '第三方服务' : '';
  const via = viaText ? `<span class="result-via">· ${viaText}</span>` : '';

  // 正文（公众号文章 / 微博正文等）：默认折 4 行，长文不把下载按钮顶出屏幕
  const desc = info.description
    ? `<div class="result-desc">
         <p class="desc-text" id="desc-text">${esc(info.description)}</p>
         <div class="desc-actions">
           <button class="link-btn" data-act="toggle-desc" type="button">展开</button>
           <button class="link-btn" data-act="copy-desc" type="button">复制正文</button>
         </div>
       </div>`
    : '';

  // 竖向布局：信息在上，播放/图集在下面占满整行
  const gallery = isGallery(info);
  const hint = contentTypeHint(info);
  const tiers = tiersOf(info);
  // 换一条作品要回到默认的最高档，不能把上一条的选择带过来
  state.tierIndex = 0;

  box.innerHTML = `
    <div class="result-head">
      <div class="result-line">
        <span class="pf-icon ${meta.cls}">${brandIcon(info.platform)}</span>
        <span class="result-platform">${esc(info.platform || '')}</span>
        ${via}
      </div>
      <h3 class="result-title">${title}</h3>
      ${bits ? `<p class="result-meta">${bits}</p>` : ''}
    </div>
    ${desc}
    <div class="result-media">${hint ? '' : (gallery ? renderGallery(info, 0) : renderPlayerBlock(info))}</div>
    ${hint ? `<p class="result-note">${esc(hint)}</p>` : ''}
    ${(!hint && !gallery) ? `<div class="rewrite-row">
      <label class="switch-row">
        <input type="checkbox" id="result-rewrite" ${state.settings && state.settings.rewrite_md5_on_download ? 'checked' : ''}>
        <span>下载时自动改 MD5</span>
      </label>
      <span class="rewrite-hint">开启后下载完用 ffmpeg 无损重封装，换一个 MD5</span>
    </div>` : ''}
    ${(!hint && !gallery) ? `<p class="result-md5" id="result-md5">${esc(md5TextFor(info, tiers[0]))}</p>` : ''}
    <div class="result-actions">
      ${hint ? '' : `<button class="btn btn-primary" data-act="download-selected" type="button">${gallery ? '下载全部' : '下 载'}</button>`}
      ${hint || !gallery ? '' : `<button class="btn btn-ghost" data-act="download-page" type="button">下载第 <span class="cur-page">1</span> 张</button>`}
      ${(!hint && tiers.length > 1) ? `<select id="tier-select" class="tier-select" title="选择下载档位">
        ${tiers.map((t, i) => `<option value="${i}">${esc(t.label)}</option>`).join('')}
      </select>` : ''}
      <button class="btn btn-ghost"   data-act="copy-selected" type="button">复制链接</button>
      <button class="btn btn-ghost"   data-act="open-selected" type="button">打开原视频</button>
    </div>`;
  box.classList.remove('is-hidden');
  box.dataset.sourceUrl = info.source_url || '';
  state.lastResult = info;
  syncClearButton();
}

/* ---- 解析中 / 失败卡片 ---- */
function renderParseLoading(hint, elapsed) {
  const box = $('#parse-result');
  box.innerHTML = `
    <div class="parse-loading">
      <span class="spinner"></span>
      <div class="parse-loading-main">
        <p class="loading-hint">${esc(hint || '正在解析…')}</p>
        <p class="loading-sub">已等待 ${elapsed} 秒${
          hint && (hint.includes('引擎') || hint.includes('第三方'))
            ? ' · 境外站点可能要几十秒，卡住多半是 VPN 没开' : ''
        }</p>
      </div>
      <button class="btn btn-ghost btn-sm" data-act="cancel-parse" type="button">取消</button>
    </div>`;
  box.classList.remove('is-hidden');
  box.dataset.sourceUrl = '';
  syncClearButton();
}

function renderParseError(msg) {
  const box = $('#parse-result');
  box.innerHTML = `
    <div class="result-head">
      <h3 class="result-title">解析失败</h3>
      <p class="result-error">${esc(msg)}</p>
    </div>`;
  box.classList.remove('is-hidden');
  box.dataset.sourceUrl = '';
  syncClearButton();
}

/* ==================== 交互：解析 ==================== */
async function onPaste() {
  let text = '';
  try {
    text = await navigator.clipboard.readText();
  } catch (e) {
    alert('无法读取剪贴板，请手动按 Ctrl+V 粘贴。');
    return;
  }
  if (!text) { alert('剪贴板是空的。'); return; }

  // 用剪贴板最新内容覆盖输入框；能提出链接就只填链接，否则原样填入供用户编辑
  const url = extractUrl(text);
  const input = $('#url-input');
  input.value = url || text.trim();
  input.focus();
}

/* 解析改成任务式：start_parse 立即返回，界面按 phase/hint 显示阶段。
 * 之前是一个阻塞 30 秒的 POST，界面只能显示「解析中…」，既看不到进度，
 * 也没有取消的出口。 */
let parseTimer = null;
let parseStartedAt = 0;

function syncClearButton() {
  const btn = $('#clear-parse-btn');
  if (!btn) return;
  const box = $('#parse-result');
  btn.classList.toggle('is-hidden', box.classList.contains('is-hidden'));
}

function setParseBusy(busy) {
  state.busy = busy;
  const btn = $('#parse-btn');
  btn.disabled = busy;
  btn.textContent = busy ? '解析中…' : '解 析';
  syncClearButton();
}

function stopParsePolling() {
  if (parseTimer) {
    clearTimeout(parseTimer);
    parseTimer = null;
  }
}

function pollParse() {
  callApi('get_parse_state').then(st => {
    if (!st || st.error) {
      stopParsePolling();
      setParseBusy(false);
      renderParseError((st && st.error) || '无法读取解析状态');
      return;
    }
    const elapsed = Math.max(0, Math.round((Date.now() - parseStartedAt) / 1000));

    if (st.state === 'running') {
      renderParseLoading(st.hint, elapsed);
      parseTimer = setTimeout(pollParse, 350);
      return;
    }

    stopParsePolling();
    setParseBusy(false);

    if (st.state === 'done' && st.result && st.result.ok) {
      renderResult(st.result);
      refreshHistory();
    } else if (st.state === 'cancelled') {
      $('#parse-result').classList.add('is-hidden');
      $('#clear-parse-btn').classList.add('is-hidden');
    } else {
      renderParseError((st && st.error) || '无法解析，请稍后重试。');
    }
  });
}

async function onParse() {
  if (state.busy) return;

  const raw = $('#url-input').value.trim();
  const url = extractUrl(raw);
  if (!url) { alert('没有识别到有效链接，请检查输入内容。'); return; }

  setParseBusy(true);
  parseStartedAt = Date.now();
  renderParseLoading('正在识别链接…', 0);

  const res = await callApi('start_parse', url);
  if (!res || res.error || res.ok === false) {
    setParseBusy(false);
    renderParseError((res && res.error) || '无法启动解析');
    return;
  }
  pollParse();
}

async function onCancelParse() {
  stopParsePolling();
  await callApi('cancel_parse');
  setParseBusy(false);
  $('#parse-result').classList.add('is-hidden');
  $('#clear-parse-btn').classList.add('is-hidden');
}

function onClearParse() {
  stopParsePolling();
  setParseBusy(false);
  $('#parse-result').classList.add('is-hidden');
  $('#parse-result').innerHTML = '';
  $('#clear-parse-btn').classList.add('is-hidden');
}

/* ==================== 交互：历史项 ==================== */
async function onHistoryClick(e) {
  const hit = e.target.closest('[data-act]');
  if (!hit) return;

  const act = hit.dataset.act;

  // 封面上的播放按钮只需操作 DOM，不依赖记录数据
  if (act === 'play-inline') { handlePlayInline(hit); return; }
  // 图集翻页同理：按钮在展开区里，用所在卡片反查记录即可
  if (act === 'gallery-prev') { navigateGallery(hit, -1); return; }
  if (act === 'gallery-next') { navigateGallery(hit, 1); return; }

  const id = hit.dataset.id;
  const item = state.history.find(h => h.id === id);
  if (!item && act !== 'toggle') return;

  switch (act) {
    case 'toggle':
      state.expandedId = state.expandedId === id ? null : id;
      renderHistory();
      break;

    case 'copy':
      try {
        await navigator.clipboard.writeText(item.source_url || '');
        flash(hit, '✓');
      } catch { alert('复制失败，请手动复制。'); }
      break;

    case 'play':
      state.expandedId = id;
      renderHistory();
      // 图文/图集没有播放器，「播放」就是展开看图
      if (isGallery(item)) break;
      // 直链带时效；部分平台（B站/YouTube 高清）只有分离流，本就没有可播链接。
      // 本地文件优先（下过就能播，不受直链过期影响）。
      if (!playSourceOf(item)) {
        alert(item.play_kind === 'none'
          ? '该平台是「视频 + 音频」分离流，没有可直接播放的链接，请先下载到本地。'
          : '直链已失效，请先点「重新解析」。');
      }
      break;

    case 'open-source':
      if (item.source_url) window.open(item.source_url, '_blank', 'noreferrer');
      break;

    case 'reanalyze':
      await reanalyze(item, hit);
      break;

    case 'download':
      startDownload(item.source_url, item.title, 0);
      break;

    case 'download-page':
      startDownload(item.source_url, item.title, currentGalleryPage(hit));
      break;

    case 'delete':
      confirmDialog(`确定删除这条记录吗？\n\n${item.title || ''}`, async () => {
        await callApi('delete_history', item.id);
        await refreshHistory();
      });
      break;
  }
}

async function reanalyze(item, hit) {
  const old = hit.textContent;
  hit.textContent = '…';
  hit.disabled = true;
  try {
    const info = await callApi('parse_url', item.source_url);
    if (info && info.ok) {
      await refreshHistory();
      flash(hit, '✓');
    } else {
      alert('重新解析失败：' + ((info && info.error) || '未知原因'));
    }
  } finally {
    hit.textContent = old;
    hit.disabled = false;
  }
}

function flash(el, text) {
  const old = el.textContent;
  el.textContent = text;
  setTimeout(() => { el.textContent = old; }, 900);
}

/* ==================== 下载与进度 ====================
 * 进度由后端持有状态，前端轮询 get_download_state（本地调用，开销可忽略）。
 * 之前用「后端反向推事件」的方式，出问题很难排查，改成轮询后 Network 面板能直接看到。
 */
let dlPath = '';
let dlTimer = null;
let dlLastSeq = -1;
let dlBaseSeq = 0;         // 本次任务开始前的序号，用于判定「新的一次结束」
let dlSawActive = false;   // 是否已经看到「正在下载」状态

async function startDownload(url, label, index = 0, spec = '') {
  if (!url) { alert('没有可下载的链接。'); return; }

  const bar = $('#dl-bar');
  const fill = $('#dl-fill');
  dlPath = '';
  bar.classList.remove('is-error', 'is-done');
  $('#dl-label').textContent = label ? `正在准备下载：${label}` : '正在准备下载…';
  $('#dl-stat').textContent = '准备中';
  // 还没拿到百分比之前先走「不确定态」，避免空条看起来像卡死
  fill.style.width = '';
  fill.classList.add('is-indeterminate');
  $('#dl-open').classList.add('is-hidden');
  $('#dl-close').classList.add('is-hidden');
  bar.classList.remove('is-hidden');

  // 先记下当前序号，再发起下载：这样能分清「本次任务的完成」与「上一次留下的完成」。
  // 只靠「见过 active=true」是不够的 —— 短任务（图集只有一张图）两次轮询之间就跑完了，
  // 界面从头到尾看不到 active，轮询会永远停不下来（安卓端踩过同一个坑）。
  const before = await callApi('get_download_state');
  dlBaseSeq = (before && typeof before.seq === 'number') ? before.seq : 0;

  const res = await callApi('start_download', url, index || 0, spec || '');
  if (res && res.ok === false) {
    bar.classList.add('is-error');
    $('#dl-label').textContent = res.error || '无法启动下载';
    $('#dl-close').classList.remove('is-hidden');
    return;
  }
  stopPolling();          // 清掉可能残留的旧循环，避免两个循环抢着渲染
  dlSawActive = false;
  dlLastSeq = dlBaseSeq;
  pollDownload();
}

function stopPolling() {
  if (dlTimer) {
    clearTimeout(dlTimer);
    dlTimer = null;
  }
}

function pollDownload() {
  callApi('get_download_state').then(st => {
    if (st && typeof st.seq === 'number' && st.seq !== dlLastSeq) {
      dlLastSeq = st.seq;
      if (st.active) dlSawActive = true;
      renderDownloadState(st);
    }
    // 结束判定：见过 active，或出现了比基线更新的序号（短任务也能收住）
    if (st && st.active === false && (dlSawActive || (st.seq || 0) > dlBaseSeq)) {
      stopPolling();
      return;
    }
    dlTimer = setTimeout(pollDownload, 500);
  });
}

/* 速度/剩余时间都用数值字段，不再解析 "12.3%|1.2MiB/s|00:05" 那种字符串 */
function formatSpeed(bytesPerSec) {
  const v = Number(bytesPerSec) || 0;
  if (v <= 0) return '';
  if (v >= 1024 * 1024) return `${(v / 1024 / 1024).toFixed(1)} MB/s`;
  return `${Math.round(v / 1024)} KB/s`;
}

function formatEta(seconds) {
  const s = Number(seconds);
  if (!Number.isFinite(s) || s <= 0) return '';
  const p = n => String(n).padStart(2, '0');
  return s >= 3600
    ? `${Math.floor(s / 3600)}:${p(Math.floor((s % 3600) / 60))}:${p(s % 60)}`
    : `${p(Math.floor(s / 60))}:${p(s % 60)}`;
}

function renderDownloadState(st) {
  const bar = $('#dl-bar');
  bar.classList.remove('is-hidden');

  if (st.type === 'progress') {
    const p = Number(st.percent);
    if (Number.isFinite(p)) {
      $('#dl-fill').classList.remove('is-indeterminate');
      $('#dl-fill').style.width = `${Math.max(0, Math.min(100, p))}%`;
    } else {
      $('#dl-fill').classList.add('is-indeterminate');
      $('#dl-fill').style.width = '';
    }
    $('#dl-stat').textContent = [
      Number.isFinite(p) ? `${p.toFixed(1)}%` : '',
      formatSpeed(st.speed),
      formatEta(st.eta) ? `剩余 ${formatEta(st.eta)}` : '',
    ].filter(Boolean).join('   ');
    $('#dl-label').textContent = '正在下载…';
    $('#dl-close').classList.remove('is-hidden');

  } else if (st.type === 'processing') {
    // 音视频合流阶段没有百分比，显示成不确定态而不是停在 100%
    bar.classList.remove('is-done', 'is-error');
    $('#dl-fill').classList.add('is-indeterminate');
    $('#dl-fill').style.width = '';
    $('#dl-label').textContent = '正在合并音视频…';
    $('#dl-stat').textContent = '';
    $('#dl-close').classList.remove('is-hidden');

  } else if (st.type === 'done') {
    dlPath = st.path || '';
    bar.classList.add('is-done');
    bar.classList.remove('is-error');
    $('#dl-fill').classList.remove('is-indeterminate');
    $('#dl-fill').style.width = '100%';
    $('#dl-stat').textContent = st.md5 ? `MD5：${st.md5}` : '';
    $('#dl-label').textContent = `下载完成：${dlPath.split(/[\\/]/).pop() || ''}`;
    $('#dl-open').classList.remove('is-hidden');
    $('#dl-close').classList.remove('is-hidden');
    refreshHistory();

  } else if (st.type === 'error') {
    bar.classList.add('is-error');
    bar.classList.remove('is-done');
    $('#dl-fill').classList.remove('is-indeterminate');
    $('#dl-label').textContent = String(st.message || '下载出错').slice(0, 240);
    $('#dl-stat').textContent = '';
    $('#dl-close').classList.remove('is-hidden');

  } else if (st.type === 'starting') {
    bar.classList.remove('is-done', 'is-error');
    $('#dl-fill').classList.add('is-indeterminate');
    $('#dl-fill').style.width = '';
    $('#dl-label').textContent = '正在准备下载…';
    $('#dl-stat').textContent = '';
    $('#dl-close').classList.remove('is-hidden');
  }
}

/* 解析结果卡片内的按钮 */
async function onResultClick(e) {
  const hit = e.target.closest('[data-act]');
  if (!hit) return;
  const act = hit.dataset.act;

  if (act === 'play-inline') { handlePlayInline(hit); return; }
  if (act === 'cancel-parse') { onCancelParse(); return; }
  if (act === 'gallery-prev') { navigateGallery(hit, -1); return; }
  if (act === 'gallery-next') { navigateGallery(hit, 1); return; }

  if (act === 'toggle-desc') {
    const p = $('#desc-text');
    if (p) {
      const expanded = p.classList.toggle('is-expanded');
      hit.textContent = expanded ? '收起' : '展开';
    }
    return;
  }

  if (act === 'copy-desc') {
    const p = $('#desc-text');
    try {
      await navigator.clipboard.writeText(p ? p.textContent : '');
      flash(hit, '✓ 已复制');
    } catch { alert('复制失败，请手动复制。'); }
    return;
  }

  const url = $('#parse-result').dataset.sourceUrl || '';

  if (act === 'download-selected') {
    startDownload(url, (state.lastResult && state.lastResult.title) || '', 0,
                  tierSpec(currentTier()));
  } else if (act === 'download-page') {
    startDownload(url, (state.lastResult && state.lastResult.title) || '',
                  currentGalleryPage(hit), tierSpec(currentTier()));
  } else if (act === 'copy-selected') {
    try {
      await navigator.clipboard.writeText(url);
      flash(hit, '✓ 已复制');
    } catch { alert('复制失败，请手动复制。'); }
  } else if (act === 'open-selected') {
    if (url) window.open(url, '_blank', 'noreferrer');
  }
}

/* ==================== 交互：清空 ==================== */
function onClearPlatform() {
  if (!state.platform) return;
  const p = state.platform;
  const n = state.history.filter(h => h.platform === p).length;
  if (!n) { alert(`「${p}」还没有记录。`); return; }
  confirmDialog(`此操作不可恢复，将删除「${p}」的全部 ${n} 条解析历史。\n\n确认继续吗？`, async () => {
    await callApi('clear_history', p);
    await refreshHistory();
  });
}

function onClearAll() {
  const n = state.history.length;
  if (!n) { alert('还没有任何记录。'); return; }
  confirmDialog(`此操作不可恢复，将删除全部 ${n} 条解析历史。\n\n确认继续吗？`, async () => {
    await callApi('clear_all_history');
    await refreshHistory();
  });
}

/* ==================== 确认弹层 ==================== */
let confirmHandler = null;

function confirmDialog(text, onOk) {
  $('#confirm-text').textContent = text;
  $('#confirm').classList.remove('is-hidden');
  confirmHandler = onOk;
}

function closeConfirm() {
  $('#confirm').classList.add('is-hidden');
  confirmHandler = null;
}

async function runConfirm() {
  const fn = confirmHandler;
  closeConfirm();
  if (fn) await fn();
}

/* ==================== 设置 ==================== */
function diagText() {
  const s = state.settings || {};
  const outDir = (state.defaults && state.defaults.download && state.defaults.download.output_dir)
    || s.output_dir || '未设置';
  return [
    `版本指纹：${buildInfo ? buildInfo.build : '未知'}`,
    `构建时间：${buildInfo ? new Date(buildInfo.mtime * 1000).toLocaleString() : '未知'}`,
    `系统：${navigator.userAgent}`,
    `保存路径：${outDir}`,
    `ffmpeg：${s.ffmpeg_available ? s.ffmpeg_path : '未找到'}`,
    `下载时自动改 MD5：${s.rewrite_md5_on_download ? '开' : '关'}`,
  ].join('\n');
}

function renderDiagnostics() {
  const b = $('#diag-build');
  if (b) {
    b.textContent = buildInfo
      ? `版本指纹：${buildInfo.build}（${new Date(buildInfo.mtime * 1000).toLocaleString()}）`
      : '版本指纹：读取中…';
  }
  const sys = $('#diag-system');
  if (sys) sys.textContent = `系统：${navigator.userAgent}`;
  const ff = $('#diag-ffmpeg');
  if (ff) {
    ff.textContent = state.settings
      ? (state.settings.ffmpeg_available
        ? `ffmpeg：${state.settings.ffmpeg_path}`
        : 'ffmpeg：未找到（视频改 MD5 / 合流不可用）')
      : 'ffmpeg：读取中…';
  }
  renderFeedbackMail();
}

function renderFeedbackMail() {
  const el = $('#feedback-mail');
  if (!el || !state.feedbackMail) return;
  const subject = '[反馈] 视频解析下载（Windows）';
  const body = `问题描述：\n\n\n---\n诊断信息：\n${diagText()}`;
  el.href = `mailto:${state.feedbackMail}?subject=${encodeURIComponent(subject)}`
    + `&body=${encodeURIComponent(body)}`;
  el.textContent = '发送反馈邮件';
}

function updateCookieStatus(file, present) {
  const s = $('#cookie-status');
  if (s) s.textContent = present ? `已导入：${file}` : '未导入';
  const cb = $('#clear-cookie-btn');
  if (cb) cb.classList.toggle('is-hidden', !present);
}

function applySettings(st) {
  state.settings = st;
  updateCookieStatus(st.cookies_file || '', !!st.cookies_present);
  const cb = $('#rewrite-md5');
  if (cb) cb.checked = !!st.rewrite_md5_on_download;
  const status = $('#ffmpeg-status');
  if (status) {
    status.textContent = st.ffmpeg_available
      ? 'ffmpeg 已就绪，视频改 MD5 与音视频合流可用。'
      : '没有找到 ffmpeg：视频改 MD5 不可用。请把 ffmpeg 放进 PATH，或配置 config/defaults.json 的 engine.ffmpeg_dir。';
  }
  renderDiagnostics();
}

async function refreshSettings() {
  const st = await callApi('get_settings');
  if (st && !st.error && typeof st === 'object') applySettings(st);
}

async function loadSettings() {
  const cfg = await callApi('get_defaults');

  // callApi 失败时会返回 {error}，绝不能把它当数据用（否则输入框会显示 [object Object]）
  if (!cfg || cfg.error || typeof cfg !== 'object') {
    console.error('[settings] 读取配置失败:', cfg && cfg.error);
    return;
  }
  state.defaults = cfg;

  const outEl = $('#output-dir');
  if (outEl) outEl.value = (cfg.download || {}).output_dir || '';

  const ckEl = $('#cookies-file');
  if (ckEl) {
    const file = (cfg.cookies || {}).file || '';
    ckEl.value = file;
    ckEl.placeholder = file ? '' : '未设置';
  }

  // 结构化设置：Cookie 状态、自动改 MD5、ffmpeg、诊断信息
  await refreshSettings();

  const mail = await callApi('get_feedback_mail');
  if (typeof mail === 'string' && mail) {
    state.feedbackMail = mail;
    renderFeedbackMail();
  }
}

async function onToggleRewrite() {
  const cb = $('#rewrite-md5');
  if (!cb) return;
  const on = !!cb.checked;
  const s = await callApi('set_rewrite_md5', on);
  if (s && !s.error) {
    state.settings = s;
    const rc = $('#result-rewrite');
    if (rc) rc.checked = on;
  }
}

async function onClearCookie() {
  await callApi('set_cookies_file', '');
  const el = $('#cookies-file');
  if (el) { el.value = ''; el.placeholder = '未设置'; }
  updateCookieStatus('', false);
}

async function onCopyDiag() {
  try {
    await navigator.clipboard.writeText(diagText());
    flash($('#copy-diag-btn'), '✓ 已复制');
  } catch {
    alert('复制失败，请手动选中诊断信息复制。');
  }
}

async function onPickCookie() {
  const picked = await callApi('pick_cookie_file');
  if (typeof picked === 'string' && picked) {
    const el = $('#cookies-file');
    if (el) el.value = picked;
    updateCookieStatus(picked, true);
  } else {
    alert('没能选择到文件。如果没弹出选择框，请检查它是否被浏览器窗口挡住了。');
  }
}

async function onPickDir() {
  const picked = await callApi('pick_folder');
  if (typeof picked === 'string' && picked) {
    const el = $('#output-dir');
    if (el) el.value = picked;
    // 诊断信息里的「保存路径」要跟着变，否则反馈里带的是旧目录
    if (!state.defaults) state.defaults = {};
    state.defaults.download = { ...(state.defaults.download || {}), output_dir: picked };
    renderDiagnostics();
  } else {
    alert('没能选择到文件夹。如果没弹出选择框，请检查它是否被浏览器窗口挡住了。');
  }
}

/* ==================== MD5 页 ==================== */
/* 三段式：入口（虚线选择框）→ 详情（预览 + 信息 + 修改）→ 结果（新旧对比 + 保存）。
 * 与安卓端一致的三条规矩：原件不动、成品另存、改完前后 MD5 必须真的不同。 */
const md5State = { stage: 'entry', picked: null, result: null, busy: false };

function md5Bytes(n) {
  const v = Number(n) || 0;
  if (v <= 0) return '—';
  if (v >= 1024 * 1024) return `${(v / 1024 / 1024).toFixed(2)} MB`;
  if (v >= 1024) return `${(v / 1024).toFixed(2)} KB`;
  return `${v} B`;
}

function md5Mmss(sec) {
  const s = Number(sec) || 0;
  if (!s) return '—';
  const p = n => String(n).padStart(2, '0');
  return `${p(Math.floor(s / 60))}:${p(s % 60)}`;
}

function md5Resolution(info) {
  return (info.width > 0 && info.height > 0) ? `${info.width} x ${info.height}` : '—';
}

function md5Preview(info) {
  const src = localUrl(info.path);
  if (info.kind === 'image') {
    return `<img src="${esc(src)}" alt="预览">`;
  }
  return `<video src="${esc(src)}" controls preload="metadata"></video>`;
}

function md5Row(label, value, cls = '') {
  return `<div class="md5-row">
    <span class="md5-label">${esc(label)}</span>
    <span class="md5-value ${cls}">${value}</span>
  </div>`;
}

function setMd5Stage(stage) {
  md5State.stage = stage;
  $('#md5-entry').classList.toggle('is-hidden', stage !== 'entry');
  $('#md5-detail').classList.toggle('is-hidden', stage !== 'detail');
  $('#md5-result').classList.toggle('is-hidden', stage !== 'result');
}

function md5Notice(id, msg) {
  const el = $(id);
  if (!el) return;
  el.textContent = msg || '';
  el.classList.toggle('is-hidden', !msg);
}

function md5RenderDetail(info) {
  $('#md5-preview-1').innerHTML = md5Preview(info);
  const rows = [
    info.kind === 'video' ? md5Row('时长', md5Mmss(info.duration)) : '',
    md5Row('大小', md5Bytes(info.size), 'is-size'),
    md5Row('分辨率', md5Resolution(info)),
    md5Row('MD5', info.md5 || '—'),
  ].join('');
  $('#md5-info-1').innerHTML = rows;
  md5Notice('#md5-detail-notice', '');
}

function md5RenderResult(r) {
  $('#md5-preview-2').innerHTML = md5Preview(r);
  const oldSize = r.before_size > 0 ? md5Bytes(r.before_size) : '';
  const rows = [
    r.kind === 'video' ? md5Row('时长', md5Mmss(r.duration)) : '',
    md5Row('大小',
      (oldSize ? `<span class="struck">${esc(oldSize)}</span>` : '') +
      `<span class="is-size">${esc(md5Bytes(r.size))}</span>`),
    md5Row('分辨率', md5Resolution(r)),
    md5Row('MD5',
      `<span class="struck">${esc(r.before || '')}</span>${esc(r.after || '—')}`),
  ].join('');
  $('#md5-info-2').innerHTML = rows;
  md5Notice('#md5-result-notice', '');
}

async function md5Pick(kind) {
  if (md5State.busy) return;
  md5Notice('#md5-notice', '');
  const info = await callApi('pick_md5_media', kind);
  if (!info || info.ok === false) {
    // 用户点了「取消」不是错误，不弹红字
    const err = (info && info.error) || '';
    if (err && err.indexOf('没有选择文件') < 0) md5Notice('#md5-notice', err);
    return;
  }
  md5State.picked = info;
  md5RenderDetail(info);
  setMd5Stage('detail');
}

async function md5OpenPath(path) {
  if (!path || md5State.busy) return;
  const info = await callApi('media_info', path);
  if (!info || info.ok === false) {
    md5Notice('#md5-notice', (info && info.error) || '读不到这个文件');
    return;
  }
  md5State.picked = info;
  md5RenderDetail(info);
  setMd5Stage('detail');
}

async function md5Run() {
  if (!md5State.picked || md5State.busy) return;
  const btn = $('#md5-run');
  md5State.busy = true;
  btn.disabled = true;
  btn.textContent = '正在改写…';
  md5Notice('#md5-detail-notice', '');
  try {
    const r = await callApi('md5_edit', md5State.picked.path);
    if (!r || r.ok === false) {
      md5Notice('#md5-detail-notice', (r && r.error) || '改写失败');
      return;
    }
    if (!r.changed) {
      md5Notice('#md5-detail-notice',
        '改写跑完了，但 MD5 没有变化 —— 这类文件可能不支持改写，换一个文件试试。');
    }
    md5State.result = r;
    md5RenderResult(r);
    setMd5Stage('result');
  } finally {
    md5State.busy = false;
    btn.disabled = false;
    btn.textContent = '修改 MD5';
  }
}

async function md5Save() {
  if (!md5State.result || md5State.busy) return;
  const btn = $('#md5-save');
  md5State.busy = true;
  btn.disabled = true;
  btn.textContent = '保存中…';
  try {
    const r = await callApi('md5_save_as', md5State.result.path);
    if (!r || r.ok === false) {
      md5Notice('#md5-result-notice', (r && r.error) || '保存失败');
    } else {
      md5Notice('#md5-result-notice', `已保存到：${r.path}`);
    }
  } finally {
    md5State.busy = false;
    btn.disabled = false;
    btn.textContent = '保存文件';
  }
}

async function loadMd5Downloads() {
  const box = $('#md5-downloads');
  if (!box) return;
  const list = await callApi('list_downloads');
  if (!Array.isArray(list) || !list.length) {
    box.innerHTML = '';
    return;
  }
  box.innerHTML = `
    <h3 class="md5-section">已下载的视频</h3>
    <p class="field-hint">点一条也能改它的 MD5。改的是副本，原文件不会被改动。</p>
    ${list.map(item => `
      <div class="md5-dl" data-path="${esc(item.path)}">
        <div class="md5-dl-head">
          <span class="pf-icon ${platformMeta(item.platform).cls}">${brandIcon(item.platform)}</span>
          <span class="md5-dl-title">${esc(item.title || item.filename)}</span>
        </div>
        <p class="md5-dl-sub">${esc(item.filename)} · ${esc(md5Bytes(item.size))}</p>
        ${item.file_md5 ? `<p class="md5-dl-sub">MD5：${esc(item.file_md5)}</p>` : ''}
      </div>`).join('')}`;
}

/* ==================== 媒体加载失败兜底 ====================
 * <video>/<img> 的 error 事件不冒泡，必须在捕获阶段监听。
 * 没有这层兜底，取流失败时播放器会一直转圈、什么都不说。
 */
document.addEventListener('error', e => {
  const el = e.target;
  if (!el || !el.tagName) return;

  if (el.tagName === 'VIDEO') {
    const box = el.closest('.player-box');
    if (!box) return;
    recoverPlay(box, '播放失败：直链可能已过期，或被平台拒绝。');
  } else if (el.tagName === 'IMG') {
    el.classList.add('is-hidden'); // 封面取不到就别显示破图
  }
}, true);

/* ==================== 启动 ==================== */
async function refreshHistory() {
  const list = await callApi('get_history');
  state.history = Array.isArray(list) ? list : [];
  renderHistoryAll();
}

/* 安全绑定：元素不存在时只记一条警告，不让整个 boot 中断。
 * 之前如果浏览器加载的是旧的 index.html，某个新元素缺失就会让整页初始化失败。 */
function on(id, event, handler) {
  const el = document.getElementById(id);
  if (el) el.addEventListener(event, handler);
  else console.warn('[boot] 页面缺少元素 #' + id + '，请强制刷新（Ctrl+F5）');
}

async function boot() {
  initTheme();
  document.querySelectorAll('.nav-item').forEach(btn => {
    btn.addEventListener('click', () => switchView(btn.dataset.view));
  });

  on('theme-row', 'click', e => {
    const chip = e.target.closest('.chip');
    if (chip) setThemeMode(chip.dataset.themeMode);
  });
  on('clear-cookie-btn', 'click', onClearCookie);
  on('copy-diag-btn', 'click', onCopyDiag);

  on('paste-btn', 'click', onPaste);
  on('parse-btn', 'click', onParse);
  on('clear-parse-btn', 'click', onClearParse);
  on('url-input', 'keydown', e => {
    if (e.key === 'Enter') { e.preventDefault(); onParse(); }
  });
  on('history-list', 'click', onHistoryClick);
  on('platform-tabs', 'click', onPlatformTabClick);
  on('clear-platform-btn', 'click', onClearPlatform);
  on('clear-all-btn', 'click', onClearAll);
  on('pick-dir-btn', 'click', onPickDir);
  on('pick-cookie-btn', 'click', onPickCookie);
  on('parse-result', 'click', onResultClick);
  on('parse-result', 'change', onResultChange);
  on('rewrite-md5', 'change', onToggleRewrite);
  // ---- MD5 页 ----
  on('md5-pick-video', 'click', () => md5Pick('video'));
  on('md5-pick-image', 'click', () => md5Pick('image'));
  on('md5-pick-any', 'click', () => md5Pick('all'));
  on('md5-downloads', 'click', e => {
    const card = e.target.closest('.md5-dl');
    if (card) md5OpenPath(card.dataset.path);
  });
  on('md5-back-1', 'click', () => setMd5Stage('entry'));
  on('md5-back-2', 'click', () => setMd5Stage('entry'));
  on('md5-reselect', 'click', () => setMd5Stage('entry'));
  on('md5-run', 'click', md5Run);
  on('md5-save', 'click', md5Save);
  on('confirm-cancel', 'click', closeConfirm);
  on('confirm-ok', 'click', runConfirm);
  on('dl-open', 'click', () => callApi('open_folder', dlPath));
  on('dl-close', 'click', () => $('#dl-bar').classList.add('is-hidden'));

  // 设置失败绝不能连累历史列表 —— 之前就是因为这里抛异常，
  // boot() 整体中断，导致历史看起来「全消失了」（其实数据都在库里）。
  try {
    await loadSettings();
  } catch (e) {
    console.error('[boot] 加载设置失败，但不影响历史:', e);
  }
  await refreshHistory();
}

boot();