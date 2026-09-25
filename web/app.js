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
  // 未识别平台的聚合入口。库里存的值是「其他」，只在界面上显示为「其他平台」，
  // 这样已有的历史记录不用做数据迁移。
  '其他':      { cls: 'pf-other',     letter: '·', label: '其他平台' },
};
// 顺序即切换栏的显示顺序；「其他」放最后，作为兜底聚合入口
const PLATFORM_ORDER = ['YouTube', 'B站', '抖音', 'TikTok', 'X', 'Instagram', '其他'];

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

function syncBuild() {
  return callApi('get_build').then(b => {
    if (!b || !b.build) return;
    if (buildAtLoad && b.build !== buildAtLoad) {
      console.warn(`[界面版本] 服务端已更新为 ${b.build}，本页仍是 ${buildAtLoad}，自动刷新`);
      location.reload();
      return;
    }
    buildAtLoad = b.build;
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
};

/* ==================== 视图切换 ==================== */
function switchView(name) {
  document.querySelectorAll('.nav-item').forEach(b => {
    b.classList.toggle('is-active', b.dataset.view === name);
  });
  document.querySelectorAll('.view').forEach(v => {
    v.classList.toggle('is-active', v.dataset.view === name);
  });
}

/* ==================== 平台切换栏 ==================== */
function platformsInUse() {
  const set = new Set(state.history.map(h => h.platform));
  const known = PLATFORM_ORDER.filter(p => set.has(p));
  const rest = [...set].filter(p => !PLATFORM_ORDER.includes(p)).sort();
  return [...known, ...rest];
}

function renderTabs() {
  const box = $('#platform-tabs');
  const list = platformsInUse();

  if (!list.length) {
    box.innerHTML = '';
    box.classList.add('is-hidden');
    state.platform = null;
    return;
  }
  box.classList.remove('is-hidden');

  if (!list.includes(state.platform)) state.platform = list[0];

  box.innerHTML = list.map(p => {
    const count = state.history.filter(h => h.platform === p).length;
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
  const next = tab.dataset.platform;
  if (next === state.platform) return;
  state.platform = next;
  state.expandedId = null;
  renderTabs();
  renderHistory();
}

/* ==================== 播放区 ====================
 * 未点之前只显示「封面 + 播放按钮」，点了才把封面换成播放器。
 * 这样不会一进来就黑屏转圈，也符合「封面在那儿等你看」的直觉。
 */
function renderPlayerBlock(item) {
  if (!item.resolved_url) {
    const msg = item.play_kind === 'none'
      ? '该平台是「视频 + 音频」分离流，没有可直接播放的链接 —— 请点「下载到本地」，下载完就能播。'
      : '直链已失效，请点「重新解析」获取新直链。';
    return `<div class="player-frame player-empty">${msg}</div>`;
  }

  const src = proxied(item.resolved_url, item.source_url);
  const poster = item.thumbnail ? proxied(item.thumbnail, item.source_url) : '';
  const posterAttr = poster ? ` poster="${esc(poster)}"` : '';

  return `
    <div class="player-frame player-box" data-source="${esc(item.source_url || '')}">
      ${poster ? `<img class="player-poster" src="${esc(poster)}" alt="封面">` : ''}
      <button class="player-play" data-act="play-inline" type="button" title="播放">${ICONS.playBig}</button>
      <video class="player-video is-hidden" controls preload="none" src="${esc(src)}"${posterAttr}></video>
      <div class="player-error is-hidden">播放失败。</div>
    </div>`;
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
function renderItem(item) {
  const meta = platformMeta(item.platform);
  const open = state.expandedId === item.id;

  const sub = [item.uploader, item.uploader_id, formatDuration(item.duration), formatTime(item.resolved_at)]
    .filter(Boolean).map(esc).join(' · ');

  // 折叠态就给出操作小图标，不必展开即可操作
  const ops = `
    <button class="icon-btn" data-act="copy"      data-id="${esc(item.id)}" title="复制链接" type="button">${ICONS.copy}</button>
    <button class="icon-btn" data-act="reanalyze" data-id="${esc(item.id)}" title="重新解析" type="button">${ICONS.reanalyze}</button>
    <button class="icon-btn" data-act="play"      data-id="${esc(item.id)}" title="播放" type="button">${ICONS.play}</button>
    <button class="icon-btn" data-act="download"  data-id="${esc(item.id)}" title="下载" type="button">${ICONS.download}</button>
    <button class="icon-btn is-danger" data-act="delete" data-id="${esc(item.id)}" title="删除" type="button">${ICONS.del}</button>`;

  // 展开态：向下扩出播放预览区
  const player = renderPlayerBlock(item);

  // 播放按钮始终显示：能不能播由点击后的提示说明，避免「按钮时有时无」造成困惑
  const detailActions = `
    <button class="btn btn-primary" data-act="play" data-id="${esc(item.id)}" type="button">播放</button>
    <button class="btn btn-ghost" data-act="download" data-id="${esc(item.id)}" type="button">下载到本地</button>
    <button class="btn btn-ghost" data-act="reanalyze" data-id="${esc(item.id)}" type="button">重新解析</button>`;

  const detail = open ? `
    <div class="history-detail">
      ${player}
      <div class="history-detail-actions">${detailActions}</div>
      <p class="detail-note">原始链接：${esc(item.source_url)}</p>
    </div>` : '';

  return `
  <div class="history-item" data-id="${esc(item.id)}">
    <div class="history-row" data-act="toggle" data-id="${esc(item.id)}">
      <span class="pf-icon ${meta.cls}">${brandIcon(item.platform)}</span>
      <div class="history-main">
        <p class="history-title">${esc(item.title || '未命名')}</p>
        <p class="history-sub">${sub}</p>
      </div>
      <div class="history-ops">${ops}</div>
    </div>
    ${detail}
  </div>`;
}

function renderHistory() {
  const box = $('#history-list');
  if (!state.platform) {
    box.innerHTML = `<div class="history-empty">还没有解析记录，去「解析」页粘贴一个链接试试</div>`;
    return;
  }
  const items = state.history.filter(h => h.platform === state.platform);
  if (!items.length) {
    box.innerHTML = `<div class="history-empty">该平台还没有记录</div>`;
    return;
  }
  box.innerHTML = items.map(renderItem).join('');
}

function renderHistoryAll() {
  renderTabs();
  renderHistory();
}

/* ==================== 解析结果卡片 ==================== */
function renderResult(info) {
  const box = $('#parse-result');
  const bits = [
    info.platform,
    formatDuration(info.duration),
    info.quality,
    info.filesize,
    info.uploader,
    info.uploader_id,   // 平台唯一标识（推特号 / B站UID 等），没有则自动省略
  ].filter(Boolean).map(esc).join(' · ');

  const title = info.source_url
    ? `<a href="${esc(info.source_url)}" target="_blank" rel="noreferrer">${esc(info.title || '未命名')}</a>`
    : esc(info.title || '未命名');

  // 竖向布局：信息在上，播放框在下面占满整行
  box.innerHTML = `
    <div class="result-head">
      <h3 class="result-title">${title}</h3>
      <p class="result-meta">${bits}</p>
    </div>
    <div class="result-media">${renderPlayerBlock(info)}</div>
    <div class="result-actions">
      <button class="btn btn-primary" data-act="download-selected" type="button">下 载</button>
      <button class="btn btn-ghost"   data-act="copy-selected" type="button">复制链接</button>
    </div>`;
  box.classList.remove('is-hidden');
  box.dataset.sourceUrl = info.source_url || '';
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

async function onParse() {
  if (state.busy) return;

  const raw = $('#url-input').value.trim();
  const url = extractUrl(raw);
  if (!url) { alert('没有识别到有效链接，请检查输入内容。'); return; }

  const btn = $('#parse-btn');
  state.busy = true;
  btn.disabled = true;
  btn.textContent = '解析中…';

  try {
    const info = await callApi('parse_url', url);
    if (info && info.ok) {
      renderResult(info);
      await refreshHistory();
    } else {
      const msg = (info && info.error) || '无法解析，请稍后重试。';
      // 报错里可能带换行（说明 + 原始报错），用 pre-line 保留
      $('#parse-result').innerHTML = `
        <div class="result-head">
          <h3 class="result-title">解析失败</h3>
          <p class="result-error">${esc(msg)}</p>
        </div>`;
      $('#parse-result').classList.remove('is-hidden');
    }
  } finally {
    state.busy = false;
    btn.disabled = false;
    btn.textContent = '解 析';
  }
}

/* ==================== 交互：历史项 ==================== */
async function onHistoryClick(e) {
  const hit = e.target.closest('[data-act]');
  if (!hit) return;

  const act = hit.dataset.act;

  // 封面上的播放按钮只需操作 DOM，不依赖记录数据
  if (act === 'play-inline') { handlePlayInline(hit); return; }

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
      // 直链带时效；部分平台（B站/YouTube 高清）只有分离流，本就没有可播链接
      if (!item.resolved_url) {
        alert(item.play_kind === 'none'
          ? '该平台是「视频 + 音频」分离流，没有可直接播放的链接，请先下载到本地。'
          : '直链已失效，请先点「重新解析」。');
      }
      break;

    case 'reanalyze':
      await reanalyze(item, hit);
      break;

    case 'download':
      startDownload(item.source_url, item.title || '正在准备下载…');
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
let dlSawActive = false;   // 是否已经看到「正在下载」状态

function startDownload(url, label) {
  if (!url) { alert('没有可下载的链接。'); return; }

  const bar = $('#dl-bar');
  dlPath = '';
  bar.classList.remove('is-error', 'is-done');
  $('#dl-label').textContent = label ? `正在准备下载：${label}` : '正在准备下载…';
  $('#dl-stat').textContent = '准备中';
  // 还没拿到百分比之前先走「不确定态」，避免空条看起来像卡死
  const fill = $('#dl-fill');
  fill.style.width = '';
  fill.classList.add('is-indeterminate');
  $('#dl-open').classList.add('is-hidden');
  $('#dl-close').classList.add('is-hidden');
  bar.classList.remove('is-hidden');

  // 必须先发起下载、再开始轮询。
  // 反过来的话，第一次轮询会读到「上一次任务」的结束状态：
  // 界面会显示上一条的完成信息，并且立刻停止轮询 —— 新任务就永远不显示了。
  callApi('start_download', url).then(res => {
    if (res && res.ok === false) {
      bar.classList.add('is-error');
      $('#dl-label').textContent = res.error || '无法启动下载';
      $('#dl-close').classList.remove('is-hidden');
      return;
    }
    stopPolling();          // 清掉可能残留的旧循环，避免两个循环抢着渲染
    dlSawActive = false;
    dlLastSeq = -1;
    pollDownload();
  });
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
    // 只有「见过 active=true」之后才能判定任务结束，
    // 否则会把上一次的结束状态误当成本次的结束。
    if (st && st.active === false && dlSawActive) {
      stopPolling();
      return;
    }
    dlTimer = setTimeout(pollDownload, 500);
  });
}

function renderDownloadState(st) {
  const bar = $('#dl-bar');
  bar.classList.remove('is-hidden');

  if (st.type === 'progress') {
    const [pct = '', speed = '', eta = ''] = String(st.value || '').split('|');
    const p = parseFloat(pct) || 0;
    if (p > 0) $('#dl-fill').classList.remove('is-indeterminate');
    $('#dl-fill').style.width = `${Math.max(0, Math.min(100, p))}%`;
    $('#dl-stat').textContent = [
      pct.trim(),
      speed.trim(),
      eta.trim() ? `剩余 ${eta.trim()}` : '',
    ].filter(Boolean).join('   ');
    $('#dl-label').textContent = '正在下载…';
    $('#dl-close').classList.remove('is-hidden');

  } else if (st.type === 'done') {
    dlPath = st.path || '';
    bar.classList.add('is-done');
    $('#dl-fill').classList.remove('is-indeterminate');
    $('#dl-fill').style.width = '100%';
    $('#dl-stat').textContent = '';
    $('#dl-label').textContent = `下载完成：${dlPath.split(/[\\/]/).pop() || ''}`;
    $('#dl-open').classList.remove('is-hidden');
    $('#dl-close').classList.remove('is-hidden');
    refreshHistory();

  } else if (st.type === 'error') {
    bar.classList.add('is-error');
    $('#dl-fill').classList.remove('is-indeterminate');
    $('#dl-label').textContent = String(st.message || '下载出错').slice(0, 240);
    $('#dl-stat').textContent = '';
    $('#dl-close').classList.remove('is-hidden');
  }
}

/* 解析结果卡片内的按钮 */
async function onResultClick(e) {
  const hit = e.target.closest('[data-act]');
  if (!hit) return;
  if (hit.dataset.act === 'play-inline') { handlePlayInline(hit); return; }

  const url = $('#parse-result').dataset.sourceUrl || '';

  if (hit.dataset.act === 'download-selected') {
    startDownload(url);   // 结果卡片上方已经显示标题，这里不用重复
  } else if (hit.dataset.act === 'copy-selected') {
    try {
      await navigator.clipboard.writeText(url);
      flash(hit, '✓ 已复制');
    } catch { alert('复制失败，请手动复制。'); }
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

  const mail = await callApi('get_feedback_mail');
  if (typeof mail === 'string' && mail) {
    const mailEl = $('#feedback-mail');
    if (mailEl) {
      mailEl.href = `mailto:${mail}`;
      mailEl.textContent = `发送邮件（${mail}）`;
    }
  }
}

async function onPickCookie() {
  const picked = await callApi('pick_cookie_file');
  if (typeof picked === 'string' && picked) {
    const el = $('#cookies-file');
    if (el) el.value = picked;
  } else {
    alert('没能选择到文件。如果没弹出选择框，请检查它是否被浏览器窗口挡住了。');
  }
}

async function onPickDir() {
  const picked = await callApi('pick_folder');
  if (typeof picked === 'string' && picked) {
    const el = $('#output-dir');
    if (el) el.value = picked;
  } else {
    alert('没能选择到文件夹。如果没弹出选择框，请检查它是否被浏览器窗口挡住了。');
  }
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
  document.querySelectorAll('.nav-item').forEach(btn => {
    btn.addEventListener('click', () => switchView(btn.dataset.view));
  });

  on('paste-btn', 'click', onPaste);
  on('parse-btn', 'click', onParse);
  on('history-list', 'click', onHistoryClick);
  on('platform-tabs', 'click', onPlatformTabClick);
  on('clear-platform-btn', 'click', onClearPlatform);
  on('clear-all-btn', 'click', onClearAll);
  on('pick-dir-btn', 'click', onPickDir);
  on('pick-cookie-btn', 'click', onPickCookie);
  on('parse-result', 'click', onResultClick);
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