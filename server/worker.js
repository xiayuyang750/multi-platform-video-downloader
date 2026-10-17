/**
 * 水印工坊 · 授权服务（Cloudflare Worker）
 *
 * 职责只有一件事：记录「哪个密钥绑在哪台设备上」。
 * App 侧只负责上报设备码、保存服务端签发的 token，不参与判定。
 *
 * 依赖的环境变量（在 Cloudflare 里配置，不要写进代码）：
 *   LICENSE_SECRET  HMAC 签名密钥，随便一串足够长的随机字符
 *   ADMIN_TOKEN     管理接口口令，只有你自己知道
 * 依赖的 KV 绑定：
 *   LICENSES        存放密钥记录
 */

const KEY_ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'; // Crockford Base32：去掉 I L O U
const KEY_BODY_LEN = 12;
const TOKEN_TTL_SECONDS = 30 * 24 * 60 * 60; // 30 天

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers: corsHeaders() });
    }

    try {
      if (url.pathname === '/api/health') {
        return json({ ok: true, service: 'watermark-license', time: Date.now() });
      }
      if (url.pathname === '/api/activate' && request.method === 'POST') {
        return await handleActivate(request, env);
      }
      if (url.pathname === '/api/verify' && request.method === 'POST') {
        return await handleVerify(request, env);
      }
      if (url.pathname.startsWith('/admin/api/')) {
        return await handleAdmin(request, env, url);
      }
      if (url.pathname === '/admin' || url.pathname === '/admin/') {
        return new Response(ADMIN_PAGE, {
          headers: { 'content-type': 'text/html; charset=utf-8' },
        });
      }
      return json({ ok: false, code: 'not_found', message: '没有这个接口' }, 404);
    } catch (e) {
      return json(
        { ok: false, code: 'server_error', message: '服务器出错：' + (e && e.message ? e.message : e) },
        500,
      );
    }
  },
};

// ---------------- 激活 / 校验 ----------------

async function handleActivate(request, env) {
  const body = await readJson(request);
  const key = normalizeKey(body.key);
  const device = cleanDevice(body.device);
  if (!key || !device) {
    return json({ ok: false, code: 'bad_request', message: '密钥或设备码格式不对' }, 400);
  }

  const rec = await getRecord(env, key);
  if (!rec) {
    return json({ ok: false, code: 'invalid_key', message: '这个密钥不存在，请检查是否输错' }, 404);
  }
  if (rec.banned) {
    return json({ ok: false, code: 'revoked', message: '这个密钥已被停用' }, 403);
  }
  if (rec.device && rec.device !== device) {
    return json(
      {
        ok: false,
        code: 'bound_elsewhere',
        message: '这个密钥已经绑定过另一台设备。换机请联系开发者解绑。',
      },
      409,
    );
  }

  if (!rec.device) {
    rec.device = device;
    rec.at = Date.now();
    await putRecord(env, key, rec);
  }

  const exp = Math.floor(Date.now() / 1000) + TOKEN_TTL_SECONDS;
  const token = await signToken({ k: key, d: device, e: exp }, env.LICENSE_SECRET);
  return json({ ok: true, key, exp, token });
}

async function handleVerify(request, env) {
  const body = await readJson(request);
  const key = normalizeKey(body.key);
  const device = cleanDevice(body.device);
  const token = typeof body.token === 'string' ? body.token : '';
  if (!key || !device || !token) {
    return json({ ok: false, code: 'bad_request', message: '缺少参数' }, 400);
  }

  const rec = await getRecord(env, key);
  if (!rec) {
    return json({ ok: false, code: 'invalid_key', message: '这个密钥不存在' }, 404);
  }
  if (rec.banned) {
    return json({ ok: false, code: 'revoked', message: '这个密钥已被停用' }, 403);
  }
  if (rec.device && rec.device !== device) {
    return json({ ok: false, code: 'bound_elsewhere', message: '这台设备不是该密钥的绑定设备' }, 409);
  }

  const checked = await verifyToken(token, key, device, env.LICENSE_SECRET);
  if (!checked.ok) {
    return json({ ok: false, code: checked.code, message: checked.message }, 401);
  }

  // 剩余有效期不足一半时顺手续期，避免用户每次启动都卡在「即将过期」
  const now = Math.floor(Date.now() / 1000);
  let exp = checked.exp;
  let fresh = null;
  if (checked.exp - now < TOKEN_TTL_SECONDS / 2) {
    exp = now + TOKEN_TTL_SECONDS;
    fresh = await signToken({ k: key, d: device, e: exp }, env.LICENSE_SECRET);
  }
  return json({ ok: true, exp, token: fresh });
}

// ---------------- 管理接口 ----------------

async function handleAdmin(request, env, url) {
  if (!env.ADMIN_TOKEN) {
    return json({ ok: false, code: 'server_error', message: '服务器未配置 ADMIN_TOKEN' }, 500);
  }
  const auth = request.headers.get('authorization') || '';
  if (auth !== `Bearer ${env.ADMIN_TOKEN}`) {
    return json({ ok: false, code: 'unauthorized', message: '管理口令不正确' }, 401);
  }

  if (url.pathname === '/admin/api/keys' && request.method === 'POST') {
    const body = await readJson(request);
    const count = clampCount(body.count);
    const note = typeof body.note === 'string' ? body.note.slice(0, 60) : '';
    const keys = [];
    for (let i = 0; i < count; i++) {
      let key;
      do {
        key = makeKey();
      } while (await getRecord(env, key));
      await putRecord(env, key, { key, device: '', at: 0, banned: false, note, createdAt: Date.now() });
      keys.push(key);
    }
    return json({ ok: true, keys });
  }

  if (url.pathname === '/admin/api/keys' && request.method === 'GET') {
    const items = [];
    let cursor;
    do {
      const page = await env.LICENSES.list({ prefix: 'key:', cursor });
      for (const k of page.keys) {
        const raw = await env.LICENSES.get(k.name);
        if (raw) items.push(JSON.parse(raw));
      }
      cursor = page.list_complete ? undefined : page.cursor;
    } while (cursor);
    items.sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));
    return json({ ok: true, items });
  }

  if (url.pathname === '/admin/api/revoke' && request.method === 'POST') {
    const body = await readJson(request);
    const key = normalizeKey(body.key);
    if (!key) return json({ ok: false, code: 'bad_request', message: '密钥格式不对' }, 400);
    const rec = await getRecord(env, key);
    if (!rec) return json({ ok: false, code: 'invalid_key', message: '没有这个密钥' }, 404);
    rec.banned = body.banned !== false;
    await putRecord(env, key, rec);
    return json({ ok: true, key, banned: rec.banned });
  }

  if (url.pathname === '/admin/api/unbind' && request.method === 'POST') {
    const body = await readJson(request);
    const key = normalizeKey(body.key);
    if (!key) return json({ ok: false, code: 'bad_request', message: '密钥格式不对' }, 400);
    const rec = await getRecord(env, key);
    if (!rec) return json({ ok: false, code: 'invalid_key', message: '没有这个密钥' }, 404);
    rec.device = '';
    rec.at = 0;
    await putRecord(env, key, rec);
    return json({ ok: true, key });
  }

  return json({ ok: false, code: 'not_found', message: '没有这个管理接口' }, 404);
}

// ---------------- 密钥与 token ----------------

function makeKey() {
  const bytes = new Uint8Array(KEY_BODY_LEN);
  crypto.getRandomValues(bytes);
  let body = '';
  for (const b of bytes) body += KEY_ALPHABET[b % KEY_ALPHABET.length];
  return formatKey(body);
}

function formatKey(body) {
  return 'WM-' + body.slice(0, 4) + '-' + body.slice(4, 8) + '-' + body.slice(8, 12);
}

/** 把用户输入的各种形态（小写、空格、缺横线、O/0 混淆）统一成标准写法。 */
function normalizeKey(raw) {
  if (typeof raw !== 'string') return '';
  let s = raw.toUpperCase().replace(/[^0-9A-Z]/g, '');
  s = s.replace(/O/g, '0').replace(/[IL]/g, '1');
  if (s.startsWith('WM')) s = s.slice(2);
  if (s.length !== KEY_BODY_LEN) return '';
  if (!/^[0-9A-HJKMNP-TV-Z]{12}$/.test(s)) return '';
  return formatKey(s);
}

function cleanDevice(raw) {
  if (typeof raw !== 'string') return '';
  const s = raw.trim().toLowerCase();
  return /^[a-z0-9_-]{4,64}$/.test(s) ? s : '';
}

async function signToken(payload, secret) {
  requireSecret(secret);
  const p = base64urlEncode(JSON.stringify(payload));
  const sig = await hmac(p, secret);
  return p + '.' + sig;
}

async function verifyToken(token, key, device, secret) {
  requireSecret(secret);
  const parts = token.split('.');
  if (parts.length !== 2) return { ok: false, code: 'bad_token', message: '凭证格式不对' };
  const [p, sig] = parts;
  const expect = await hmac(p, secret);
  if (!timingSafeEqual(sig, expect)) {
    return { ok: false, code: 'bad_token', message: '凭证校验失败' };
  }
  let payload;
  try {
    payload = JSON.parse(base64urlDecode(p));
  } catch {
    return { ok: false, code: 'bad_token', message: '凭证内容损坏' };
  }
  if (payload.k !== key || payload.d !== device) {
    return { ok: false, code: 'bad_token', message: '凭证与这台设备不匹配' };
  }
  if (typeof payload.e !== 'number' || payload.e < Math.floor(Date.now() / 1000)) {
    return { ok: false, code: 'token_expired', message: '凭证已过期，请联网重新激活' };
  }
  return { ok: true, exp: payload.e };
}

function requireSecret(secret) {
  if (!secret) throw new Error('服务器未配置 LICENSE_SECRET');
}

async function hmac(data, secret) {
  const enc = new TextEncoder();
  const key = await crypto.subtle.importKey(
    'raw',
    enc.encode(secret),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign'],
  );
  const sig = await crypto.subtle.sign('HMAC', key, enc.encode(data));
  return base64urlFromBytes(new Uint8Array(sig));
}

function base64urlEncode(text) {
  return base64urlFromBytes(new TextEncoder().encode(text));
}

function base64urlFromBytes(bytes) {
  let bin = '';
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function base64urlDecode(text) {
  const b64 = text.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((text.length + 3) % 4);
  const bin = atob(b64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return new TextDecoder().decode(bytes);
}

/** 定长时间比较，避免用字符串提前返回泄露签名前缀。 */
function timingSafeEqual(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

// ---------------- KV / 工具 ----------------

async function getRecord(env, key) {
  const raw = await env.LICENSES.get('key:' + key);
  return raw ? JSON.parse(raw) : null;
}

async function putRecord(env, key, rec) {
  await env.LICENSES.put('key:' + key, JSON.stringify(rec));
}

async function readJson(request) {
  try {
    return await request.json();
  } catch {
    return {};
  }
}

function clampCount(n) {
  const v = Number.isFinite(n) ? Math.floor(n) : 1;
  return Math.min(200, Math.max(1, v));
}

function corsHeaders() {
  return {
    'access-control-allow-origin': '*',
    'access-control-allow-methods': 'GET,POST,OPTIONS',
    'access-control-allow-headers': 'content-type,authorization',
  };
}

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8', ...corsHeaders() },
  });
}

// ---------------- 管理页 ----------------

const ADMIN_PAGE = `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>水印工坊 · 授权管理</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; }
  body { margin:0; padding:24px; font:14px/1.6 system-ui,-apple-system,"Segoe UI",sans-serif;
         background:#101216; color:#e8eaed; }
  h1 { font-size:18px; margin:0 0 4px; }
  p.sub { margin:0 0 20px; color:#8b929e; font-size:13px; }
  .box { max-width:880px; margin:0 auto; }
  section { background:#181b21; border:1px solid #262b33; border-radius:12px; padding:16px; margin-bottom:16px; }
  label { display:block; font-size:12px; color:#8b929e; margin-bottom:6px; }
  input, button { font:inherit; }
  input { width:100%; padding:9px 11px; border-radius:8px; border:1px solid #2f3540;
          background:#0e1014; color:#e8eaed; }
  .row { display:flex; gap:8px; margin-top:10px; flex-wrap:wrap; }
  button { padding:9px 14px; border-radius:8px; border:1px solid #2f3540; background:#222731;
           color:#e8eaed; cursor:pointer; }
  button.primary { background:#3b82f6; border-color:#3b82f6; color:#fff; }
  button.danger { color:#f87171; border-color:#4b2a2a; background:#241618; }
  table { width:100%; border-collapse:collapse; margin-top:12px; font-size:13px; }
  th, td { text-align:left; padding:8px 6px; border-bottom:1px solid #242a33; }
  th { color:#8b929e; font-weight:500; font-size:12px; }
  code { font-family:ui-monospace,Consolas,monospace; color:#93c5fd; }
  .muted { color:#8b929e; }
  .tag { display:inline-block; padding:1px 7px; border-radius:99px; font-size:11px; }
  .tag.free { background:#1f2a1f; color:#86efac; }
  .tag.bound { background:#1e2a3a; color:#93c5fd; }
  .tag.banned { background:#33191b; color:#f87171; }
  .keys { margin-top:12px; display:flex; flex-wrap:wrap; gap:8px; }
  .keys code { background:#0e1014; border:1px solid #2f3540; padding:6px 10px; border-radius:8px; }
  .msg { margin-top:10px; font-size:13px; }
  .msg.err { color:#f87171; }
  .msg.ok { color:#86efac; }
</style>
</head>
<body>
<div class="box">
  <h1>水印工坊 · 授权管理</h1>
  <p class="sub">密钥只在这里生成；管理口令不会发给用户。</p>

  <section>
    <label>管理口令（ADMIN_TOKEN）</label>
    <input id="token" type="password" placeholder="粘贴你在 Cloudflare 配置的 ADMIN_TOKEN">
    <div class="row">
      <button class="primary" id="save">保存并加载</button>
      <button id="clear">清除本机保存</button>
    </div>
    <div class="msg" id="msg"></div>
  </section>

  <section>
    <label>生成新密钥</label>
    <div class="row">
      <input id="count" type="number" value="10" min="1" max="200" style="max-width:120px">
      <input id="note" placeholder="备注，例如「抖音群第一批」" style="flex:1;min-width:160px">
      <button class="primary" id="gen">生成</button>
    </div>
    <div class="keys" id="keys"></div>
  </section>

  <section>
    <div class="row" style="margin-top:0;justify-content:space-between;align-items:center">
      <label style="margin:0">全部密钥</label>
      <button id="reload">刷新</button>
    </div>
    <table>
      <thead><tr><th>密钥</th><th>状态</th><th>绑定设备</th><th>创建时间</th><th></th></tr></thead>
      <tbody id="list"></tbody>
    </table>
  </section>
</div>
<script>
const $ = (id) => document.getElementById(id);
let token = localStorage.getItem('wm_admin') || '';
$('token').value = token;

function say(text, ok) {
  const el = $('msg');
  el.textContent = text;
  el.className = 'msg ' + (ok ? 'ok' : 'err');
}

async function api(path, options = {}) {
  const res = await fetch(path, {
    ...options,
    headers: { 'content-type': 'application/json', authorization: 'Bearer ' + token, ...(options.headers || {}) },
  });
  const data = await res.json().catch(() => ({ ok: false, message: '返回内容不是 JSON' }));
  if (!data.ok) throw new Error(data.message || '请求失败');
  return data;
}

$('save').onclick = () => {
  token = $('token').value.trim();
  localStorage.setItem('wm_admin', token);
  say('口令已保存', true);
  load();
};
$('clear').onclick = () => {
  localStorage.removeItem('wm_admin');
  token = '';
  $('token').value = '';
  say('已清除', true);
};
$('gen').onclick = async () => {
  try {
    const count = Number($('count').value) || 1;
    const note = $('note').value.trim();
    const data = await api('/admin/api/keys', { method: 'POST', body: JSON.stringify({ count, note }) });
    $('keys').innerHTML = data.keys.map((k) => '<code>' + k + '</code>').join('');
    say('已生成 ' + data.keys.length + ' 个密钥，记得复制保存', true);
    load();
  } catch (e) { say(e.message, false); }
};
$('reload').onclick = load;

$('list').onclick = async (e) => {
  const btn = e.target.closest('button');
  if (!btn) return;
  const key = btn.dataset.key;
  try {
    if (btn.dataset.act === 'revoke') {
      await api('/admin/api/revoke', { method: 'POST', body: JSON.stringify({ key, banned: true }) });
    } else if (btn.dataset.act === 'unban') {
      await api('/admin/api/revoke', { method: 'POST', body: JSON.stringify({ key, banned: false }) });
    } else if (btn.dataset.act === 'unbind') {
      if (!confirm('解绑后这台设备会掉线，确定？')) return;
      await api('/admin/api/unbind', { method: 'POST', body: JSON.stringify({ key }) });
    }
    load();
  } catch (err) { say(err.message, false); }
};

async function load() {
  if (!token) return;
  try {
    const data = await api('/admin/api/keys');
    $('list').innerHTML = data.items.map((it) => {
      const state = it.banned
        ? '<span class="tag banned">已停用</span>'
        : it.device ? '<span class="tag bound">已绑定</span>' : '<span class="tag free">未使用</span>';
      const actions = it.banned
        ? '<button data-act="unban" data-key="' + it.key + '">恢复</button>'
        : '<button class="danger" data-act="revoke" data-key="' + it.key + '">停用</button>'
          + (it.device ? ' <button data-act="unbind" data-key="' + it.key + '">解绑</button>' : '');
      return '<tr><td><code>' + it.key + '</code></td><td>' + state + '</td><td class="muted">'
        + (it.device ? it.device.slice(0, 12) + '…' : '—') + '</td><td class="muted">'
        + (it.createdAt ? new Date(it.createdAt).toLocaleString() : '—') + '</td><td>' + actions + '</td></tr>';
    }).join('') || '<tr><td colspan="5" class="muted">还没有密钥</td></tr>';
  } catch (e) { say(e.message, false); }
}

load();
</script>
</body>
</html>`;
