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

// 管理口令失败限流。存在进程内存里，冷启动会重置 —— 目的是拦住明显的暴力尝试，
// 不是精确的风控；用 KV 计数反而会让攻击者用一次失败换一次写入，打爆免费额度。
const ADMIN_MAX_FAILS = 8;
const ADMIN_FAIL_WINDOW_MS = 5 * 60 * 1000;
const adminFailures = new Map();

// 审计日志单独占一个键，只保留最近 LOG_MAX 条
const LOG_KEY = 'log:recent';
const LOG_MAX = 200;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (request.method === 'OPTIONS') {
      // 管理接口只在同源页面里调用，不需要 CORS；给它们开放跨域等于凭空多一条攻击面
      const admin = url.pathname.startsWith('/admin');
      return new Response(null, { status: 204, headers: admin ? {} : corsHeaders() });
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
        const res = await handleAdmin(request, env, url);
        const headers = new Headers(res.headers);
        headers.delete('access-control-allow-origin');
        headers.delete('access-control-allow-methods');
        headers.delete('access-control-allow-headers');
        return new Response(res.body, { status: res.status, headers });
      }
      if (url.pathname === '/admin' || url.pathname === '/admin/') {
        return new Response(ADMIN_PAGE, {
          headers: {
            'content-type': 'text/html; charset=utf-8',
            'cache-control': 'no-store',
            'content-security-policy':
              "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; img-src data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
            'x-content-type-options': 'nosniff',
            'referrer-policy': 'no-referrer',
          },
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
  const info = cleanInfo(body.info);
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

  // info 只用于后台展示，缺失或格式不对都不影响绑定与签发
  if (!rec.device) {
    rec.device = device;
    rec.at = Date.now();
    if (info) rec.info = info;
    await putRecord(env, key, rec);
  } else if (info && !sameInfo(info, rec.info)) {
    rec.info = info;
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
  const info = cleanInfo(body.info);
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

  // 老用户升到新版 App 后，靠这次校验把设备信息补上；只在内容变化时写一次
  if (info && !sameInfo(info, rec.info)) {
    rec.info = info;
    await putRecord(env, key, rec);
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

  const ip = clientIp(request);
  if (isRateLimited(ip)) {
    return json({ ok: false, code: 'too_many_attempts', message: '尝试次数过多，请 5 分钟后再试' }, 429);
  }

  const auth = request.headers.get('authorization') || '';
  if (!timingSafeEqual(auth, `Bearer ${env.ADMIN_TOKEN}`)) {
    noteAuthFailure(ip);
    return json({ ok: false, code: 'unauthorized', message: '管理口令不正确' }, 401);
  }
  adminFailures.delete(ip);

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
    await appendLog(env, { at: Date.now(), action: 'create', count, note, ip });
    return json({ ok: true, keys });
  }

  if (url.pathname === '/admin/api/keys' && request.method === 'GET') {
    // 分页返回：密钥多了以后一次性把全部记录 get 出来会撞上 Workers 的子请求上限
    // （免费版每次调用 50 个子请求）。管理页按 cursor 逐页取完，再在本地筛选。
    const limit = clampLimit(url.searchParams.get('limit'));
    const cursor = url.searchParams.get('cursor') || undefined;
    const page = await env.LICENSES.list({ prefix: 'key:', limit, cursor });
    const items = [];
    for (const k of page.keys) {
      const raw = await env.LICENSES.get(k.name);
      if (raw) items.push(JSON.parse(raw));
    }
    return json({
      ok: true,
      items,
      cursor: page.list_complete ? '' : page.cursor || '',
      complete: !!page.list_complete,
    });
  }

  if (url.pathname === '/admin/api/revoke' && request.method === 'POST') {
    const body = await readJson(request);
    const key = normalizeKey(body.key);
    if (!key) return json({ ok: false, code: 'bad_request', message: '密钥格式不对' }, 400);
    const rec = await getRecord(env, key);
    if (!rec) return json({ ok: false, code: 'invalid_key', message: '没有这个密钥' }, 404);
    rec.banned = body.banned !== false;
    await putRecord(env, key, rec);
    await appendLog(env, { at: Date.now(), action: rec.banned ? 'revoke' : 'unban', key, ip });
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
    await appendLog(env, { at: Date.now(), action: 'unbind', key, ip });
    return json({ ok: true, key });
  }

  if (url.pathname === '/admin/api/note' && request.method === 'POST') {
    const body = await readJson(request);
    const key = normalizeKey(body.key);
    if (!key) return json({ ok: false, code: 'bad_request', message: '密钥格式不对' }, 400);
    const rec = await getRecord(env, key);
    if (!rec) return json({ ok: false, code: 'invalid_key', message: '没有这个密钥' }, 404);
    rec.note = typeof body.note === 'string' ? body.note.trim().slice(0, 60) : '';
    await putRecord(env, key, rec);
    await appendLog(env, { at: Date.now(), action: 'note', key, ip });
    return json({ ok: true, key, note: rec.note });
  }

  if (url.pathname === '/admin/api/logs' && request.method === 'GET') {
    const raw = await env.LICENSES.get(LOG_KEY);
    return json({ ok: true, items: raw ? JSON.parse(raw) : [] });
  }

  return json({ ok: false, code: 'not_found', message: '没有这个管理接口' }, 404);
}

// ---------------- 管理面防护 / 审计 ----------------

function clientIp(request) {
  return request.headers.get('cf-connecting-ip') || 'unknown';
}

function isRateLimited(ip) {
  const rec = adminFailures.get(ip);
  if (!rec) return false;
  if (Date.now() > rec.resetAt) {
    adminFailures.delete(ip);
    return false;
  }
  return rec.count >= ADMIN_MAX_FAILS;
}

function noteAuthFailure(ip) {
  const now = Date.now();
  const rec = adminFailures.get(ip);
  if (!rec || now > rec.resetAt) {
    // 简单的容量保护：异常来源太多时直接清空，避免 Map 无限增长
    if (adminFailures.size > 5000) adminFailures.clear();
    adminFailures.set(ip, { count: 1, resetAt: now + ADMIN_FAIL_WINDOW_MS });
    return;
  }
  rec.count++;
}

async function appendLog(env, entry) {
  // 审计写入失败不能影响主操作本身
  try {
    const raw = await env.LICENSES.get(LOG_KEY);
    const list = raw ? JSON.parse(raw) : [];
    list.unshift(entry);
    await env.LICENSES.put(LOG_KEY, JSON.stringify(list.slice(0, LOG_MAX)));
  } catch {
    // 忽略
  }
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

const INFO_FIELDS = ['manufacturer', 'brand', 'model', 'device', 'android', 'sdk', 'app'];

/**
 * 设备信息只做展示用，按白名单挑字段、限长；任何不合规的输入都直接丢掉，
 * 不能让它影响绑定与签发。
 */
function cleanInfo(raw) {
  if (!raw || typeof raw !== 'object') return null;
  const out = {};
  let any = false;
  for (const field of INFO_FIELDS) {
    const v = raw[field];
    if (typeof v !== 'string' && typeof v !== 'number') continue;
    const s = String(v).replace(/[\u0000-\u001f]/g, '').trim().slice(0, 40);
    if (!s) continue;
    out[field] = s;
    any = true;
  }
  return any ? out : null;
}

function sameInfo(a, b) {
  return JSON.stringify(a || null) === JSON.stringify(b || null);
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

/** 单次生成上限 20：每把密钥要 1 次查重 + 1 次写入，20 把正好在免费版 50 子请求以内。 */
function clampCount(n) {
  const v = Number.isFinite(n) ? Math.floor(n) : 1;
  return Math.min(20, Math.max(1, v));
}

/** 单页上限 50：一次调用只发 50 个 get，不会超子请求上限。 */
function clampLimit(raw) {
  const v = Number(raw);
  if (!Number.isFinite(v)) return 50;
  return Math.min(50, Math.max(1, Math.floor(v)));
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
  :root{
    --bg:#f4f6f9; --panel:#ffffff; --border:#e5e8ee; --border-strong:#d6dae2;
    --text:#111827; --muted:#6b7280; --faint:#9aa1ad;
    --accent:#2563eb; --accent-hover:#1d4ed8; --accent-soft:#eef4ff;
    --green:#0f9f6e; --green-soft:#e8f8f1;
    --red:#dc2626; --red-soft:#fdeeee;
    --soft:#f1f3f6;
    --radius:14px;
    --shadow:0 1px 2px rgba(16,24,40,.04),0 10px 28px rgba(16,24,40,.06);
  }
  *,*::before,*::after{box-sizing:border-box}
  [hidden]{display:none !important}
  html,body{height:100%}
  body{
    margin:0;background:var(--bg);color:var(--text);
    font:14px/1.55 -apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Hiragino Sans GB","Microsoft YaHei",sans-serif;
    -webkit-font-smoothing:antialiased;
  }
  button{font:inherit}
  code{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}

  /* 顶栏 */
  .topbar{position:sticky;top:0;z-index:20;background:rgba(255,255,255,.86);
          -webkit-backdrop-filter:blur(12px);backdrop-filter:blur(12px);border-bottom:1px solid var(--border)}
  .topbar-inner{max-width:1280px;margin:0 auto;padding:14px 20px;display:flex;align-items:center;
                justify-content:space-between;gap:16px}
  .brand{display:flex;align-items:center;gap:12px;min-width:0}
  .logo{width:38px;height:38px;border-radius:11px;flex:0 0 auto;display:flex;align-items:center;
        justify-content:center;color:#fff;background:linear-gradient(135deg,#3b82f6,#2563eb);
        box-shadow:0 6px 16px rgba(37,99,235,.28)}
  .brand-title{font-size:15px;font-weight:600;letter-spacing:.01em;white-space:nowrap}
  .brand-sub{font-size:12px;color:var(--muted);margin-top:1px;white-space:nowrap}
  .topbar-right{display:flex;align-items:center;gap:10px}
  .health{display:inline-flex;align-items:center;gap:6px;font-size:12px;color:var(--muted);white-space:nowrap}
  .dot{width:7px;height:7px;border-radius:50%;background:#cbd5e1}
  .dot.ok{background:#10b981;box-shadow:0 0 0 3px rgba(16,185,129,.15)}
  .dot.bad{background:#ef4444;box-shadow:0 0 0 3px rgba(239,68,68,.15)}

  /* 布局 */
  .page{max-width:1280px;margin:0 auto;padding:26px 20px 72px;display:flex;flex-direction:column;gap:18px}
  .card{background:var(--panel);border:1px solid var(--border);border-radius:var(--radius);
        box-shadow:var(--shadow);padding:20px}
  .card-head{display:flex;align-items:flex-start;justify-content:space-between;gap:16px;margin-bottom:14px}
  h2{font-size:15px;font-weight:600;margin:0;letter-spacing:.01em}
  .card-desc{margin:3px 0 0;font-size:12.5px;color:var(--muted)}

  /* 概览数字 */
  .stats{display:grid;grid-template-columns:repeat(4,1fr);gap:14px}
  .stat{background:var(--panel);border:1px solid var(--border);border-radius:var(--radius);
        padding:16px 18px;box-shadow:var(--shadow)}
  .stat-label{font-size:12px;color:var(--muted)}
  .stat-value{font-size:26px;font-weight:600;letter-spacing:-.02em;margin-top:5px;
              font-variant-numeric:tabular-nums;line-height:1.15}
  .stat-value.blue{color:var(--accent)}
  .stat-value.green{color:var(--green)}
  .stat-value.red{color:var(--red)}

  /* 按钮 */
  .btn{display:inline-flex;align-items:center;justify-content:center;gap:6px;height:36px;padding:0 14px;
       border-radius:9px;border:1px solid var(--border-strong);background:#fff;color:var(--text);
       font-size:13px;font-weight:500;cursor:pointer;white-space:nowrap;transition:background .15s,border-color .15s}
  .btn:hover{background:#f8fafc;border-color:#c3c9d4}
  .btn:disabled{opacity:.55;cursor:not-allowed}
  .btn-primary{background:var(--accent);border-color:var(--accent);color:#fff;
               box-shadow:0 6px 14px rgba(37,99,235,.22)}
  .btn-primary:hover{background:var(--accent-hover);border-color:var(--accent-hover)}
  .btn-sm{height:32px;padding:0 11px;font-size:12.5px;border-radius:8px}
  .btn-ghost{border-color:transparent;background:transparent;color:var(--muted)}
  .btn-ghost:hover{background:#eef1f5;color:var(--text)}
  .btn-block{width:100%;height:40px}
  .btn-danger{color:var(--red);border-color:#f3d3d3;background:#fff}
  .btn-danger:hover{background:var(--red-soft);border-color:#eab8b8}
  .btn-row{display:flex;gap:8px;flex-wrap:wrap}

  /* 表单 */
  .field-label{display:block;font-size:12px;color:var(--muted);margin-bottom:6px}
  .input{width:100%;height:38px;padding:0 12px;border-radius:9px;border:1px solid var(--border-strong);
         background:#fff;color:var(--text);font-size:13.5px;outline:none;transition:border-color .15s,box-shadow .15s}
  .input::placeholder{color:var(--faint)}
  .input:focus{border-color:#93b4ff;box-shadow:0 0 0 3px rgba(37,99,235,.12)}
  .input-wrap{position:relative}
  .input-wrap .input{padding-right:40px}
  .input-wrap .icon-btn{position:absolute;right:5px;top:50%;transform:translateY(-50%)}
  .icon-btn{display:inline-flex;align-items:center;justify-content:center;width:28px;height:28px;
            border-radius:7px;border:0;background:transparent;color:var(--faint);cursor:pointer;flex:0 0 auto}
  .icon-btn:hover{background:#eef1f5;color:var(--text)}
  .error-text{margin-top:10px;font-size:12.5px;color:var(--red);background:var(--red-soft);
               border:1px solid #f5d7d7;border-radius:8px;padding:8px 10px}
  .hint{font-size:11.5px;color:var(--faint);margin:12px 0 0;text-align:center}

  /* 登录 */
  .login-wrap{min-height:calc(100vh - 68px);display:flex;align-items:center;justify-content:center;padding:32px 20px}
  .login-card{width:100%;max-width:400px;padding:28px}
  .login-icon{width:44px;height:44px;border-radius:12px;background:var(--accent-soft);color:var(--accent);
              display:flex;align-items:center;justify-content:center;margin-bottom:16px}
  .login-card h1{font-size:18px;margin:0 0 6px;font-weight:600}
  .login-card .muted{font-size:12.5px;margin:0 0 20px;color:var(--muted)}
  .login-card .btn-block{margin-top:16px}

  /* 生成密钥 */
  .gen-row{display:grid;grid-template-columns:96px 1fr auto;gap:12px;align-items:end}
  .gen-count input{text-align:center}
  .gen-result{margin-top:16px;border-top:1px solid var(--border);padding-top:14px}
  .gen-result-head{display:flex;align-items:center;justify-content:space-between;gap:12px;
                   margin-bottom:10px;font-size:13px;color:var(--muted);flex-wrap:wrap}
  .key-chips{display:flex;flex-wrap:wrap;gap:8px}
  .chip{display:inline-flex;align-items:center;gap:7px;padding:6px 10px;border:1px solid var(--border);
        background:#fafbfc;border-radius:8px;font-size:12.5px;color:#1f2937;cursor:pointer;
        font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}
  .chip:hover{border-color:#bcd0ff;background:var(--accent-soft);color:#1d4ed8}

  /* 表格工具栏 */
  .table-head{align-items:center}
  .toolbar{display:flex;align-items:center;gap:8px;flex-wrap:wrap;justify-content:flex-end}
  .search{position:relative;width:220px}
  .search svg{position:absolute;left:10px;top:50%;transform:translateY(-50%);color:var(--faint);pointer-events:none}
  .search .input{height:34px;padding-left:32px;font-size:12.5px}
  .segmented{display:inline-flex;padding:3px;background:var(--soft);border-radius:9px;gap:2px}
  .segmented button{border:0;background:transparent;height:28px;padding:0 10px;border-radius:7px;
                    font-size:12.5px;color:var(--muted);cursor:pointer;white-space:nowrap}
  .segmented button.active{background:#fff;color:var(--text);font-weight:500;
                           box-shadow:0 1px 2px rgba(16,24,40,.08)}

  /* 表格 */
  .table-wrap{overflow-x:auto;margin:0 -20px;padding:0 20px}
  table{width:100%;border-collapse:separate;border-spacing:0;font-size:13px;min-width:1180px}
  th{text-align:left;font-size:11.5px;font-weight:500;color:var(--muted);padding:10px 12px;
     border-bottom:1px solid var(--border);white-space:nowrap}
  td{padding:12px;border-bottom:1px solid #f1f3f6;vertical-align:middle}
  tbody tr:hover{background:#fafbfd}
  tbody tr:last-child td{border-bottom:0}
  .ta-right{text-align:right}
  .key-cell{display:flex;align-items:center;gap:4px;white-space:nowrap}
  .key-cell code{font-size:12.5px;letter-spacing:.02em;color:#1f2937}
  .dev-cell{display:flex;align-items:flex-start;gap:2px;max-width:230px}
  .dev-code{font-size:10.5px;line-height:1.6;color:#4b5563;word-break:break-all}
  .nowrap{white-space:nowrap}
  .dev-time{font-size:11px;color:var(--faint);margin-top:2px}
  .info-brand{font-size:12.5px;font-weight:600;color:#1f2937;max-width:200px;
              overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
  .info-meta{font-size:11.5px;color:var(--muted);margin-top:2px}
  .old-tag{display:inline-block;margin-top:3px;font-size:10.5px;color:#b45309;
           background:#fff7ed;border-radius:6px;padding:1px 6px}
  .note-cell{display:flex;align-items:center;gap:2px;max-width:190px}
  .note-text{display:inline-block;max-width:150px;overflow:hidden;text-overflow:ellipsis;
             white-space:nowrap;vertical-align:bottom}
  .note-edit-btn{opacity:0;transition:opacity .15s}
  tr:hover .note-edit-btn,.note-edit-btn:focus{opacity:1}
  .note-add{border:0;background:transparent;color:var(--faint);font-size:12px;cursor:pointer;
            padding:2px 6px;border-radius:6px}
  .note-add:hover{background:var(--accent-soft);color:var(--accent)}
  .note-edit{display:flex;align-items:center;gap:3px}
  .note-input{width:150px;height:30px;padding:0 8px;border-radius:7px;border:1px solid #93b4ff;
              background:#fff;color:var(--text);font:inherit;font-size:12.5px;outline:none;
              box-shadow:0 0 0 3px rgba(37,99,235,.12)}
  .icon-btn.ok{color:var(--green)}
  .icon-btn.ok:hover{background:var(--green-soft)}
  .muted{color:var(--muted)}
  .trunc{display:inline-block;max-width:170px;overflow:hidden;text-overflow:ellipsis;
         white-space:nowrap;vertical-align:bottom}
  .pill{display:inline-flex;align-items:center;gap:5px;height:22px;padding:0 9px;border-radius:99px;
        font-size:11.5px;font-weight:500;white-space:nowrap}
  .pill::before{content:'';width:5px;height:5px;border-radius:50%}
  .pill.free{background:var(--soft);color:#4b5563}
  .pill.free::before{background:#9ca3af}
  .pill.bound{background:var(--accent-soft);color:#1d4ed8}
  .pill.bound::before{background:#2563eb}
  .pill.banned{background:var(--red-soft);color:#b91c1c}
  .pill.banned::before{background:#dc2626}
  .row-actions{display:flex;gap:6px;justify-content:flex-end}
  .empty{padding:48px 20px;text-align:center;color:var(--faint);font-size:13px}
  .empty svg{display:block;margin:0 auto 10px;color:#cbd5e1}
  .pager{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-top:14px;
         font-size:12.5px;color:var(--muted)}
  .log-list{display:flex;flex-direction:column}
  .log-item{display:flex;align-items:center;gap:10px;padding:9px 2px;border-bottom:1px solid #f1f3f6;
            font-size:12.5px;color:var(--muted)}
  .log-item:last-child{border-bottom:0}
  .log-time{color:var(--faint);flex:0 0 auto;font-variant-numeric:tabular-nums}
  .log-action{color:var(--text);font-weight:500}
  .log-ip{margin-left:auto;color:var(--faint);font-size:11.5px;flex:0 0 auto}

  /* 提示 */
  .toast{position:fixed;left:50%;bottom:28px;transform:translateX(-50%) translateY(8px);
         background:#111827;color:#fff;font-size:13px;padding:10px 16px;border-radius:10px;
         box-shadow:0 12px 30px rgba(16,24,40,.24);opacity:0;pointer-events:none;
         transition:opacity .2s,transform .2s;z-index:60;max-width:80vw}
  .toast.show{opacity:1;transform:translateX(-50%) translateY(0)}
  .toast.error{background:#b91c1c}

  @media (max-width:860px){
    .stats{grid-template-columns:repeat(2,1fr)}
    .card-head{flex-direction:column;align-items:stretch}
    .toolbar{justify-content:flex-start}
    .search{width:100%}
    .gen-row{grid-template-columns:1fr 1fr}
    .gen-row .btn{grid-column:1/-1}
  }
  @media (max-width:520px){
    .page{padding:18px 14px 60px}
    .card{padding:16px}
    .table-wrap{margin:0 -16px;padding:0 16px}
    .topbar-inner{padding:12px 14px}
    .brand-sub{display:none}
  }
  @media (hover:none){
    /* 触屏没有 hover，备注编辑按钮要常驻，否则点不到 */
    .note-edit-btn{opacity:1}
  }
</style>
</head>
<body>

<header class="topbar">
  <div class="topbar-inner">
    <div class="brand">
      <div class="logo">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="7.5" cy="15.5" r="4.5"/><path d="M10.6 12.4 20 3m-2.5 0H20v2.5"/></svg>
      </div>
      <div>
        <div class="brand-title">水印工坊 · 授权管理</div>
        <div class="brand-sub">密钥只在这里生成</div>
      </div>
    </div>
    <div class="topbar-right" id="topActions" hidden>
      <span class="health"><i class="dot" id="healthDot"></i><span id="healthText">检查中</span></span>
      <button class="btn btn-ghost" id="logout">退出</button>
    </div>
  </div>
</header>

<main id="loginView" class="login-wrap">
  <section class="card login-card">
    <div class="login-icon">
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="4" y="10" width="16" height="10" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3"/></svg>
    </div>
    <h1>进入管理后台</h1>
    <p class="muted">粘贴你在 Cloudflare 配置的 ADMIN_TOKEN。口令只保存在本机浏览器。</p>
    <label class="field-label" for="token">管理口令</label>
    <div class="input-wrap">
      <input class="input" id="token" type="password" placeholder="ADMIN_TOKEN" autocomplete="current-password">
      <button class="icon-btn" id="toggleToken" title="显示口令" aria-label="显示口令">
        <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6S2 12 2 12z"/><circle cx="12" cy="12" r="2.5"/></svg>
      </button>
    </div>
    <div class="error-text" id="loginError" hidden></div>
    <button class="btn btn-primary btn-block" id="loginBtn">进入</button>
    <p class="hint">口令不会写进代码，也不会发给用户。</p>
  </section>
</main>

<main id="appView" class="page" hidden>
  <section class="stats">
    <div class="stat"><div class="stat-label">密钥总数</div><div class="stat-value" id="statTotal">0</div></div>
    <div class="stat"><div class="stat-label">已绑定</div><div class="stat-value blue" id="statBound">0</div></div>
    <div class="stat"><div class="stat-label">未使用</div><div class="stat-value green" id="statFree">0</div></div>
    <div class="stat"><div class="stat-label">已停用</div><div class="stat-value red" id="statBanned">0</div></div>
  </section>

  <section class="card">
    <div class="card-head">
      <div>
        <h2>生成新密钥</h2>
        <p class="card-desc">超过 20 个会自动分批生成；生成后请立即复制或导出，密钥不会再次完整展示。</p>
      </div>
    </div>
    <div class="gen-row">
      <div class="gen-count">
        <label class="field-label" for="count">数量</label>
        <input class="input" id="count" type="number" value="5" min="1" max="200">
      </div>
      <div>
        <label class="field-label" for="note">备注（可选）</label>
        <input class="input" id="note" type="text" placeholder="例如：抖音群第一批" maxlength="60">
      </div>
      <button class="btn btn-primary" id="gen">生成密钥</button>
    </div>
    <div class="gen-result" id="genResult" hidden>
      <div class="gen-result-head">
        <span id="genSummary"></span>
        <div class="btn-row">
          <button class="btn btn-sm" id="copyAll">复制全部</button>
          <button class="btn btn-sm" id="downloadGen">下载 CSV</button>
        </div>
      </div>
      <div class="key-chips" id="keyChips"></div>
    </div>
  </section>

  <section class="card">
    <div class="card-head table-head">
      <div>
        <h2>密钥列表</h2>
        <p class="card-desc" id="listDesc">—</p>
      </div>
      <div class="toolbar">
        <div class="search">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="11" cy="11" r="6"/><path d="M20 20l-3.5-3.5"/></svg>
          <input class="input" id="search" type="text" placeholder="搜索密钥 / 备注 / 设备">
        </div>
        <div class="segmented" id="filters">
          <button data-f="all" class="active">全部</button>
          <button data-f="free">未使用</button>
          <button data-f="bound">已绑定</button>
          <button data-f="banned">已停用</button>
        </div>
        <button class="btn btn-sm" id="refresh">刷新</button>
        <button class="btn btn-sm" id="exportAll">导出 CSV</button>
      </div>
    </div>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>密钥</th><th>状态</th><th>备注</th><th>设备信息</th><th>绑定设备</th><th>创建时间</th><th class="ta-right">操作</th>
          </tr>
        </thead>
        <tbody id="list"></tbody>
      </table>
      <div class="empty" id="empty" hidden>
        <svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M4 13h4l1.5 2.5h5L16 13h4"/><path d="M5.5 5h13l2.5 8v6h-18v-6z"/></svg>
        <div id="emptyText">还没有密钥</div>
      </div>
    </div>
    <div class="pager" id="pager" hidden>
      <span id="pagerInfo"></span>
      <div class="btn-row">
        <button class="btn btn-sm" id="prevPage">上一页</button>
        <button class="btn btn-sm" id="nextPage">下一页</button>
      </div>
    </div>
  </section>

  <section class="card">
    <div class="card-head table-head">
      <div>
        <h2>最近操作</h2>
        <p class="card-desc">生成、停用、解绑的记录，保留最近 200 条</p>
      </div>
      <button class="btn btn-sm" id="reloadLogs">刷新</button>
    </div>
    <div class="log-list" id="logList"><div class="muted">加载中…</div></div>
  </section>
</main>

<div class="toast" id="toast"></div>

<script>
var $ = function (id) { return document.getElementById(id); };
var NL = String.fromCharCode(10);
var state = { token: '', items: [], loaded: 0, filter: 'all', q: '', page: 1, pageSize: 20, generated: [], genNote: '' };
var editingNoteKey = null;
var toastTimer = null;

function esc(v) {
  return String(v == null ? '' : v).replace(/[&<>"']/g, function (c) {
    if (c === '&') return '&amp;';
    if (c === '<') return '&lt;';
    if (c === '>') return '&gt;';
    if (c === '"') return '&quot;';
    return '&#39;';
  });
}

function pad(n) { return (n < 10 ? '0' : '') + n; }

function fmt(ms) {
  if (!ms) return '—';
  var d = new Date(ms);
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
    + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
}

function statusText(it) {
  if (it.banned) return '已停用';
  if (it.device) return '已绑定';
  return '未使用';
}

function statusClass(it) {
  if (it.banned) return 'banned';
  if (it.device) return 'bound';
  return 'free';
}

/** 后台展示的设备名称：品牌 + 型号，缺哪个用哪个。 */
function infoName(it) {
  var info = it.info || {};
  var parts = [info.brand || info.manufacturer || '', info.model || ''];
  return parts.filter(function (s) { return !!s; }).join(' ');
}

/** 系统与 App 版本的副标题。 */
function infoMeta(it) {
  var info = it.info || {};
  var parts = [];
  if (info.android) parts.push('Android ' + info.android);
  if (info.app) parts.push('App ' + info.app);
  return parts.join(' · ');
}

function deviceInfoText(it) {
  var parts = [infoName(it), infoMeta(it)];
  return parts.filter(function (s) { return !!s; }).join(' ');
}

/** 备注单元格：平时只显示文字，点一下原地变成输入框，不弹对话框。 */
function noteCell(it, key) {
  if (editingNoteKey === it.key) {
    return '<div class="note-edit">'
      + '<input class="note-input" id="noteInput" maxlength="60" placeholder="输入备注" value="' + esc(it.note || '') + '">'
      + '<button class="icon-btn ok" data-note-save="' + key + '" title="保存（Enter）" aria-label="保存备注">'
      + '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 12.5l5 5L20 6.5"/></svg>'
      + '</button>'
      + '<button class="icon-btn" data-note-cancel="1" title="取消（Esc）" aria-label="取消编辑">'
      + '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M6 6l12 12M18 6L6 18"/></svg>'
      + '</button>'
      + '</div>';
  }
  if (!it.note) {
    return '<button class="note-add" data-note-edit="' + key + '">＋ 备注</button>';
  }
  return '<div class="note-cell"><span class="note-text" title="' + esc(it.note) + '">' + esc(it.note) + '</span>'
    + '<button class="icon-btn note-edit-btn" data-note-edit="' + key + '" title="编辑备注" aria-label="编辑备注">'
    + '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 20h4L19 9l-4-4L4 16z"/></svg>'
    + '</button></div>';
}

function toast(text, isError) {
  var el = $('toast');
  el.textContent = text;
  el.className = 'toast show' + (isError ? ' error' : '');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(function () { el.className = 'toast' + (isError ? ' error' : ''); }, 2400);
}

function api(path, options) {
  options = options || {};
  return fetch(path, {
    method: options.method || 'GET',
    headers: {
      'content-type': 'application/json',
      'authorization': 'Bearer ' + state.token
    },
    body: options.body ? JSON.stringify(options.body) : undefined
  }).then(function (res) {
    return res.json().catch(function () {
      return { ok: false, message: '返回内容不是 JSON（HTTP ' + res.status + '）' };
    }).then(function (data) {
      if (!res.ok || !data.ok) throw new Error(data.message || ('请求失败 HTTP ' + res.status));
      return data;
    });
  });
}

function copyText(text) {
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(text).then(function () {
      toast('已复制');
    }, function () {
      fallbackCopy(text);
    });
    return;
  }
  fallbackCopy(text);
}

function fallbackCopy(text) {
  var ta = document.createElement('textarea');
  ta.value = text;
  ta.style.position = 'fixed';
  ta.style.opacity = '0';
  document.body.appendChild(ta);
  ta.select();
  var ok = false;
  try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
  document.body.removeChild(ta);
  toast(ok ? '已复制' : '复制失败，请手动选择');
}

// ---- 登录 ----

function enter(token) {
  state.token = String(token || '').trim();
  $('loginError').hidden = true;
  $('loginBtn').disabled = true;
  loadAll().then(function () {
    localStorage.setItem('wm_admin', state.token);
    $('loginView').hidden = true;
    $('appView').hidden = false;
    $('topActions').hidden = false;
    checkHealth();
    loadLogs();
  }).catch(function (e) {
    $('loginError').textContent = e.message;
    $('loginError').hidden = false;
  }).then(function () {
    $('loginBtn').disabled = false;
  });
}

function checkHealth() {
  fetch('/api/health').then(function (r) { return r.json(); }).then(function (data) {
    $('healthDot').className = 'dot ' + (data.ok ? 'ok' : 'bad');
    $('healthText').textContent = data.ok ? '服务正常' : '服务异常';
  }).catch(function () {
    $('healthDot').className = 'dot bad';
    $('healthText').textContent = '连接失败';
  });
}

// ---- 数据 ----

function loadAll() {
  state.items = [];
  state.loaded = 0;
  editingNoteKey = null;
  var cursor = '';
  var pages = 0;

  function step() {
    var url = '/admin/api/keys?limit=50' + (cursor ? '&cursor=' + encodeURIComponent(cursor) : '');
    return api(url).then(function (data) {
      state.items = state.items.concat(data.items || []);
      state.loaded = state.items.length;
      $('listDesc').textContent = '正在加载… 已读取 ' + state.loaded + ' 把';
      pages++;
      if (!data.complete && data.cursor && pages < 100) {
        cursor = data.cursor;
        return step();
      }
      if (!data.complete) toast('密钥数量较多，仅加载了前 ' + state.loaded + ' 把', true);
    });
  }

  return step().then(function () {
    state.items.sort(function (a, b) { return (b.createdAt || 0) - (a.createdAt || 0); });
    state.page = 1;
    render();
  });
}

function filtered() {
  var q = state.q.toLowerCase();
  return state.items.filter(function (it) {
    if (state.filter === 'free' && (it.banned || it.device)) return false;
    if (state.filter === 'bound' && (it.banned || !it.device)) return false;
    if (state.filter === 'banned' && !it.banned) return false;
    if (!q) return true;
    return String(it.key || '').toLowerCase().indexOf(q) >= 0
      || String(it.note || '').toLowerCase().indexOf(q) >= 0
      || String(it.device || '').toLowerCase().indexOf(q) >= 0
      || deviceInfoText(it).toLowerCase().indexOf(q) >= 0;
  });
}

function render() {
  var total = state.items.length;
  var bound = 0, free = 0, banned = 0;
  state.items.forEach(function (it) {
    if (it.banned) banned++;
    else if (it.device) bound++;
    else free++;
  });
  $('statTotal').textContent = total;
  $('statBound').textContent = bound;
  $('statFree').textContent = free;
  $('statBanned').textContent = banned;
  $('listDesc').textContent = '共 ' + total + ' 把 · 已绑定 ' + bound + ' · 未使用 ' + free + ' · 已停用 ' + banned;

  var rows = filtered();
  var pages = Math.max(1, Math.ceil(rows.length / state.pageSize));
  if (state.page > pages) state.page = pages;
  var start = (state.page - 1) * state.pageSize;
  var slice = rows.slice(start, start + state.pageSize);

  $('list').innerHTML = slice.map(function (it) {
    var key = esc(it.key);
    var note = noteCell(it, key);
    var name = infoName(it);
    var meta = infoMeta(it);
    var infoCell = name
      ? '<div class="info-brand" title="' + esc(name) + '">' + esc(name) + '</div>'
        + (meta ? '<div class="info-meta">' + esc(meta) + '</div>' : '')
      : (it.device
          ? '<span class="muted">—</span><div><span class="old-tag">旧版本 App，未上报</span></div>'
          : '<span class="muted">—</span>');
    var device = it.device
      ? '<div class="dev-cell"><code class="dev-code">' + esc(it.device) + '</code>'
        + '<button class="icon-btn" data-copy="' + esc(it.device) + '" title="复制设备码" aria-label="复制设备码">'
        + '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15V5a2 2 0 0 1 2-2h8"/></svg>'
        + '</button></div>'
        + '<div class="dev-time">绑定于 ' + fmt(it.at) + '</div>'
      : '<span class="muted">—</span>';
    var actions = it.banned
      ? '<button class="btn btn-sm" data-act="unban" data-key="' + key + '">恢复</button>'
      : '<button class="btn btn-sm btn-danger" data-act="revoke" data-key="' + key + '">停用</button>'
        + (it.device ? '<button class="btn btn-sm" data-act="unbind" data-key="' + key + '">解绑</button>' : '');
    return '<tr>'
      + '<td><div class="key-cell"><code>' + key + '</code>'
      + '<button class="icon-btn" data-copy="' + key + '" title="复制密钥" aria-label="复制密钥">'
      + '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15V5a2 2 0 0 1 2-2h8"/></svg>'
      + '</button></div></td>'
      + '<td><span class="pill ' + statusClass(it) + '">' + statusText(it) + '</span></td>'
      + '<td>' + note + '</td>'
      + '<td>' + infoCell + '</td>'
      + '<td>' + device + '</td>'
      + '<td class="muted nowrap">' + fmt(it.createdAt) + '</td>'
      + '<td class="ta-right"><div class="row-actions">' + actions + '</div></td>'
      + '</tr>';
  }).join('');

  var isEmpty = rows.length === 0;
  $('empty').hidden = !isEmpty;
  $('emptyText').textContent = state.items.length === 0 ? '还没有密钥，先在上方生成一批' : '没有符合当前筛选条件的密钥';

  var showPager = rows.length > 0;
  $('pager').hidden = !showPager;
  if (showPager) {
    $('pagerInfo').textContent = '第 ' + state.page + ' / ' + pages + ' 页 · 共 ' + rows.length + ' 条'
      + (rows.length !== total ? '（已筛选，全部 ' + total + ' 条）' : '');
    $('prevPage').disabled = state.page <= 1;
    $('nextPage').disabled = state.page >= pages;
  }

  // 进入编辑态后把光标放进输入框，省去用户再点一次
  if (editingNoteKey) {
    var input = $('noteInput');
    if (input) { input.focus(); input.select(); }
  }
}

// ---- CSV ----

function exportRows(rows, filename) {
  var head = ['密钥', '状态', '备注', '设备信息', '绑定设备', '创建时间'];
  var lines = [head].concat(rows.map(function (it) {
    return [it.key, statusText(it), it.note || '', deviceInfoText(it), it.device || '', fmt(it.createdAt)];
  }));
  var csv = lines.map(function (cols) {
    return cols.map(function (c) {
      return '"' + String(c == null ? '' : c).replace(/"/g, '""') + '"';
    }).join(',');
  }).join(NL);

  var blob = new Blob([String.fromCharCode(0xFEFF) + csv], { type: 'text/csv;charset=utf-8' });
  var url = URL.createObjectURL(blob);
  var a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
}

function dateStamp() {
  var d = new Date();
  return d.getFullYear() + pad(d.getMonth() + 1) + pad(d.getDate());
}

// ---- 生成 ----

function renderGenerated() {
  $('genResult').hidden = false;
  $('keyChips').innerHTML = state.generated.map(function (k) {
    return '<button class="chip" data-copy="' + esc(k) + '">' + esc(k)
      + '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15V5a2 2 0 0 1 2-2h8"/></svg>'
      + '</button>';
  }).join('');
}

$('gen').onclick = function () {
  var total = Math.min(200, Math.max(1, Math.floor(Number($('count').value) || 1)));
  var note = $('note').value.trim();
  var created = [];
  var failure = null;

  $('gen').disabled = true;
  state.generated = [];
  state.genNote = note;
  $('genResult').hidden = false;
  $('keyChips').innerHTML = '';
  $('genSummary').textContent = '正在生成… 0 / ' + total;

  // 服务端单次最多 20 个（一次调用最多 50 个子请求），这里自动分批，
  // 每批成功后立刻把密钥放进结果区，避免中途失败时用户不知道已经生成了多少。
  function batch() {
    var remain = total - created.length;
    if (remain <= 0) return Promise.resolve();
    return api('/admin/api/keys', {
      method: 'POST',
      body: { count: Math.min(20, remain), note: note }
    }).then(function (data) {
      var got = data.keys || [];
      // 服务端说成功却没给密钥时必须停下，否则这里会无限递归
      if (!got.length) throw new Error('服务端没有返回密钥');
      created = created.concat(got);
      state.generated = created.slice();
      renderGenerated();
      $('genSummary').textContent = '正在生成… ' + created.length + ' / ' + total;
      return batch();
    });
  }

  batch().catch(function (e) {
    failure = e;
  }).then(function () {
    state.generated = created;
    renderGenerated();
    $('gen').disabled = false;

    if (failure) {
      // 已生成的部分是真实存在的，必须如实告知，否则用户会重复生成
      $('genSummary').textContent = '已生成 ' + created.length + ' / ' + total
        + ' 个，中途失败：' + failure.message;
      toast('已生成 ' + created.length + ' 个，剩余未生成', true);
    } else {
      $('genSummary').textContent = '本次生成 ' + created.length + ' 个密钥'
        + (note ? ' · 备注「' + note + '」' : '');
      toast('已生成 ' + created.length + ' 个密钥');
    }

    return loadAll().then(loadLogs).catch(function () {});
  });
};

$('copyAll').onclick = function () {
  if (!state.generated.length) return;
  copyText(state.generated.join(NL));
};

$('downloadGen').onclick = function () {
  if (!state.generated.length) return;
  exportRows(state.generated.map(function (k) {
    return { key: k, note: state.genNote, device: '', createdAt: Date.now() };
  }), 'keys-new-' + dateStamp() + '.csv');
};

// ---- 审计日志 ----

function actionLabel(e) {
  if (e.action === 'create') return '生成 ' + (e.count || 1) + ' 个密钥' + (e.note ? '（' + e.note + '）' : '');
  if (e.action === 'revoke') return '停用 ' + e.key;
  if (e.action === 'unban') return '恢复 ' + e.key;
  if (e.action === 'unbind') return '解绑 ' + e.key;
  if (e.action === 'note') return '修改备注 ' + e.key;
  return e.action;
}

function loadLogs() {
  return api('/admin/api/logs').then(function (data) {
    var items = data.items || [];
    $('logList').innerHTML = items.length
      ? items.map(function (e) {
          return '<div class="log-item"><span class="log-time">' + fmt(e.at) + '</span>'
            + '<span class="log-action">' + esc(actionLabel(e)) + '</span>'
            + '<span class="log-ip">' + esc(e.ip || '—') + '</span></div>';
        }).join('')
      : '<div class="muted">还没有操作记录</div>';
  }).catch(function (e) {
    $('logList').innerHTML = '<div class="muted">' + esc(e.message) + '</div>';
  });
}

$('reloadLogs').onclick = loadLogs;

// ---- 备注编辑 ----

function saveNote(key) {
  var input = $('noteInput');
  if (!input) return;
  var value = input.value.trim().slice(0, 60);
  input.disabled = true;
  api('/admin/api/note', { method: 'POST', body: { key: key, note: value } }).then(function (data) {
    var it = state.items.filter(function (x) { return x.key === key; })[0];
    if (it) it.note = data.note || '';
    editingNoteKey = null;
    render();
    toast(value ? '备注已保存' : '备注已清空');
    loadLogs();
  }).catch(function (e) {
    input.disabled = false;
    toast(e.message, true);
  });
}

// ---- 列表交互 ----

$('list').onclick = function (e) {
  var editBtn = e.target.closest('[data-note-edit]');
  if (editBtn) {
    editingNoteKey = editBtn.getAttribute('data-note-edit');
    render();
    return;
  }
  var saveBtn = e.target.closest('[data-note-save]');
  if (saveBtn) {
    saveNote(saveBtn.getAttribute('data-note-save'));
    return;
  }
  if (e.target.closest('[data-note-cancel]')) {
    editingNoteKey = null;
    render();
    return;
  }

  var btn = e.target.closest('button[data-act]');
  if (!btn) return;
  var key = btn.getAttribute('data-key');
  var act = btn.getAttribute('data-act');

  if (act === 'unbind' && !confirm('解绑后这台设备会掉线，确定要解绑吗？')) return;

  var path = act === 'unbind' ? '/admin/api/unbind' : '/admin/api/revoke';
  var body = act === 'unbind' ? { key: key } : { key: key, banned: act === 'revoke' };
  api(path, { method: 'POST', body: body }).then(function () {
    toast(act === 'unbind' ? '已解绑' : (act === 'revoke' ? '已停用' : '已恢复'));
    return loadAll().then(loadLogs);
  }).catch(function (err) {
    toast(err.message, true);
  });
};

document.addEventListener('click', function (e) {
  var el = e.target.closest('[data-copy]');
  if (el) copyText(el.getAttribute('data-copy'));
});

$('list').onkeydown = function (e) {
  if (!editingNoteKey) return;
  if (e.key === 'Enter') {
    e.preventDefault();
    saveNote(editingNoteKey);
  } else if (e.key === 'Escape') {
    editingNoteKey = null;
    render();
  }
};

$('filters').onclick = function (e) {
  var btn = e.target.closest('button[data-f]');
  if (!btn) return;
  var buttons = $('filters').querySelectorAll('button');
  for (var i = 0; i < buttons.length; i++) buttons[i].className = '';
  btn.className = 'active';
  state.filter = btn.getAttribute('data-f');
  state.page = 1;
  render();
};

var searchTimer = null;
$('search').oninput = function () {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(function () {
    state.q = $('search').value.trim();
    state.page = 1;
    render();
  }, 160);
};

$('prevPage').onclick = function () {
  if (state.page > 1) { state.page--; render(); }
};
$('nextPage').onclick = function () {
  var pages = Math.max(1, Math.ceil(filtered().length / state.pageSize));
  if (state.page < pages) { state.page++; render(); }
};

$('refresh').onclick = function () {
  $('refresh').disabled = true;
  loadAll().then(loadLogs).then(function () {
    toast('已刷新');
  }).catch(function (e) {
    toast(e.message, true);
  }).then(function () {
    $('refresh').disabled = false;
  });
};

$('exportAll').onclick = function () {
  var rows = filtered();
  if (!rows.length) { toast('当前没有可导出的密钥', true); return; }
  exportRows(rows, 'keys-' + dateStamp() + '.csv');
  toast('已导出 ' + rows.length + ' 条');
};

$('logout').onclick = function () {
  localStorage.removeItem('wm_admin');
  state.token = '';
  location.reload();
};

// ---- 启动 ----

$('toggleToken').onclick = function () {
  var input = $('token');
  var show = input.type === 'password';
  input.type = show ? 'text' : 'password';
  $('toggleToken').title = show ? '隐藏口令' : '显示口令';
};

$('token').onkeydown = function (e) {
  if (e.key === 'Enter') enter($('token').value);
};

$('loginBtn').onclick = function () {
  enter($('token').value);
};

var saved = localStorage.getItem('wm_admin') || '';
$('token').value = saved;
if (saved) enter(saved); else $('token').focus();
</script>
</body>
</html>`;
