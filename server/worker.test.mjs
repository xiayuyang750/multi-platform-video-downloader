import { test } from 'node:test';
import assert from 'node:assert/strict';
import worker from './worker.js';

/** 内存版 KV，接口与 Cloudflare KV 保持一致（只实现用到的几个方法）。 */
function makeEnv() {
  const store = new Map();
  return {
    LICENSES: {
      async get(k) { return store.has(k) ? store.get(k) : null; },
      async put(k, v) { store.set(k, v); },
      async delete(k) { store.delete(k); },
      async list({ prefix = '', limit, cursor } = {}) {
        const all = [...store.keys()].filter((k) => k.startsWith(prefix)).sort();
        const start = cursor ? Number(cursor) : 0;
        const end = start + (limit || all.length);
        return {
          keys: all.slice(start, end).map((name) => ({ name })),
          list_complete: end >= all.length,
          cursor: String(end),
        };
      },
    },
    LICENSE_SECRET: 'test-secret',
    ADMIN_TOKEN: 'test-admin',
    __store: store,
  };
}

const BASE = 'https://license.test';

function call(env, path, { method = 'GET', body, headers = {} } = {}) {
  return worker.fetch(
    new Request(BASE + path, {
      method,
      headers: { 'content-type': 'application/json', ...headers },
      body: body === undefined ? undefined : JSON.stringify(body),
    }),
    env,
  );
}

async function json(res) {
  return { status: res.status, body: await res.json() };
}

async function newKey(env, count = 1) {
  const r = await call(env, '/admin/api/keys', {
    method: 'POST',
    headers: { authorization: 'Bearer test-admin' },
    body: { count },
  });
  const j = await r.json();
  return j.keys;
}

test('管理接口没有 token 时返回 401', async () => {
  const env = makeEnv();
  const { status } = await json(await call(env, '/admin/api/keys', { method: 'POST', body: {} }));
  assert.equal(status, 401);
});

test('生成密钥的格式是 WM-XXXX-XXXX-XXXX', async () => {
  const env = makeEnv();
  const keys = await newKey(env, 3);
  assert.equal(keys.length, 3);
  for (const k of keys) {
    assert.match(k, /^WM(-[0-9A-HJKMNP-TV-Z]{4}){3}$/);
  }
  assert.equal(new Set(keys).size, 3, '生成的密钥不能重复');
});

test('不存在的密钥激活失败', async () => {
  const env = makeEnv();
  const { status, body } = await json(
    await call(env, '/api/activate', { method: 'POST', body: { key: 'WM-0000-0000-0000', device: 'dev-a' } }),
  );
  assert.equal(status, 404);
  assert.equal(body.ok, false);
  assert.equal(body.code, 'invalid_key');
});

test('首次激活成功并返回 token', async () => {
  const env = makeEnv();
  const [key] = await newKey(env);
  const { status, body } = await json(
    await call(env, '/api/activate', { method: 'POST', body: { key, device: 'dev-a' } }),
  );
  assert.equal(status, 200);
  assert.equal(body.ok, true);
  assert.ok(body.token && body.token.includes('.'), 'token 应该是 payload.sig 形式');
  assert.ok(body.exp > Date.now() / 1000, 'exp 必须是未来时间');
});

test('同一台设备重复激活仍然成功', async () => {
  const env = makeEnv();
  const [key] = await newKey(env);
  await call(env, '/api/activate', { method: 'POST', body: { key, device: 'dev-a' } });
  const { body } = await json(
    await call(env, '/api/activate', { method: 'POST', body: { key, device: 'dev-a' } }),
  );
  assert.equal(body.ok, true);
});

test('换一台设备激活被拒绝', async () => {
  const env = makeEnv();
  const [key] = await newKey(env);
  await call(env, '/api/activate', { method: 'POST', body: { key, device: 'dev-a' } });
  const { status, body } = await json(
    await call(env, '/api/activate', { method: 'POST', body: { key, device: 'dev-b' } }),
  );
  assert.equal(status, 409);
  assert.equal(body.code, 'bound_elsewhere');
  assert.equal(body.ok, false);
});

test('密钥大小写与多余空格会被归一化', async () => {
  const env = makeEnv();
  const [key] = await newKey(env);
  const messy = '  ' + key.toLowerCase().replace(/-/g, ' ') + '  ';
  const { body } = await json(
    await call(env, '/api/activate', { method: 'POST', body: { key: messy, device: 'dev-a' } }),
  );
  assert.equal(body.ok, true, '小写、空格、缺少横线都应能识别');
});

test('verify 校验 token 与设备绑定', async () => {
  const env = makeEnv();
  const [key] = await newKey(env);
  const act = await (await call(env, '/api/activate', { method: 'POST', body: { key, device: 'dev-a' } })).json();

  const good = await json(
    await call(env, '/api/verify', { method: 'POST', body: { key, device: 'dev-a', token: act.token } }),
  );
  assert.equal(good.body.ok, true);

  const otherDevice = await json(
    await call(env, '/api/verify', { method: 'POST', body: { key, device: 'dev-b', token: act.token } }),
  );
  assert.equal(otherDevice.body.ok, false);
  assert.equal(otherDevice.body.code, 'bound_elsewhere');
});

test('被篡改的 token 校验失败', async () => {
  const env = makeEnv();
  const [key] = await newKey(env);
  const act = await (await call(env, '/api/activate', { method: 'POST', body: { key, device: 'dev-a' } })).json();
  const tampered = act.token.slice(0, -2) + (act.token.endsWith('aa') ? 'bb' : 'aa');
  const { body } = await json(
    await call(env, '/api/verify', { method: 'POST', body: { key, device: 'dev-a', token: tampered } }),
  );
  assert.equal(body.ok, false);
  assert.equal(body.code, 'bad_token');
});

test('过期 token 校验失败', async () => {
  const env = makeEnv();
  const [key] = await newKey(env);
  await call(env, '/api/activate', { method: 'POST', body: { key, device: 'dev-a' } });

  // 用与服务端相同的算法伪造一个已过期的 token，验证过期判定确实生效
  const payload = b64url(JSON.stringify({ k: key, d: 'dev-a', e: Math.floor(Date.now() / 1000) - 10 }));
  const sig = await hmac(payload, env.LICENSE_SECRET);
  const { body } = await json(
    await call(env, '/api/verify', { method: 'POST', body: { key, device: 'dev-a', token: `${payload}.${sig}` } }),
  );
  assert.equal(body.ok, false);
  assert.equal(body.code, 'token_expired');
});

test('封禁后激活与校验都失败', async () => {
  const env = makeEnv();
  const [key] = await newKey(env);
  const act = await (await call(env, '/api/activate', { method: 'POST', body: { key, device: 'dev-a' } })).json();

  await call(env, '/admin/api/revoke', {
    method: 'POST',
    headers: { authorization: 'Bearer test-admin' },
    body: { key, banned: true },
  });

  const again = await json(await call(env, '/api/activate', { method: 'POST', body: { key, device: 'dev-a' } }));
  assert.equal(again.body.ok, false);
  assert.equal(again.body.code, 'revoked');

  const verify = await json(
    await call(env, '/api/verify', { method: 'POST', body: { key, device: 'dev-a', token: act.token } }),
  );
  assert.equal(verify.body.ok, false);
  assert.equal(verify.body.code, 'revoked');
});

test('缺少参数返回 400', async () => {
  const env = makeEnv();
  const { status, body } = await json(await call(env, '/api/activate', { method: 'POST', body: { key: '' } }));
  assert.equal(status, 400);
  assert.equal(body.code, 'bad_request');
});

test('管理列表能看到密钥的绑定设备', async () => {
  const env = makeEnv();
  const [key] = await newKey(env);
  await call(env, '/api/activate', { method: 'POST', body: { key, device: 'dev-a' } });
  const { body } = await json(
    await call(env, '/admin/api/keys', { headers: { authorization: 'Bearer test-admin' } }),
  );
  assert.equal(body.ok, true);
  const item = body.items.find((it) => it.key === key);
  assert.ok(item, '列表里必须有刚生成的密钥');
  assert.equal(item.device, 'dev-a');
  assert.equal(item.banned, false);
});

test('列表接口按 limit / cursor 分页', async () => {
  const env = makeEnv();
  await newKey(env, 3);
  const headers = { authorization: 'Bearer test-admin' };

  const first = await json(await call(env, '/admin/api/keys?limit=2', { headers }));
  assert.equal(first.body.ok, true);
  assert.equal(first.body.items.length, 2);
  assert.equal(first.body.complete, false);
  assert.ok(first.body.cursor, '未取完时必须给出游标');

  const second = await json(
    await call(env, `/admin/api/keys?limit=2&cursor=${encodeURIComponent(first.body.cursor)}`, { headers }),
  );
  assert.equal(second.body.items.length, 1);
  assert.equal(second.body.complete, true);
  assert.equal(second.body.cursor, '');
});

test('单次生成数量最多 20 个', async () => {
  const env = makeEnv();
  const keys = await newKey(env, 999);
  assert.equal(keys.length, 20);
});

test('管理接口不返回 CORS 头，公开接口保留', async () => {
  const env = makeEnv();
  const admin = await call(env, '/admin/api/keys', { headers: { authorization: 'Bearer test-admin' } });
  assert.equal(admin.headers.get('access-control-allow-origin'), null);
  const health = await call(env, '/api/health');
  assert.equal(health.headers.get('access-control-allow-origin'), '*');
});

test('管理页带安全响应头', async () => {
  const env = makeEnv();
  const res = await call(env, '/admin');
  const csp = res.headers.get('content-security-policy') || '';
  assert.match(csp, /default-src 'none'/);
  assert.equal(res.headers.get('x-content-type-options'), 'nosniff');
  assert.equal(res.headers.get('cache-control'), 'no-store');
});

test('管理操作会写入审计日志', async () => {
  const env = makeEnv();
  const headers = { authorization: 'Bearer test-admin' };
  const [key] = await newKey(env, 2);
  await call(env, '/admin/api/revoke', { method: 'POST', headers, body: { key, banned: true } });

  const { body } = await json(await call(env, '/admin/api/logs', { headers }));
  assert.equal(body.ok, true);
  assert.equal(body.items[0].action, 'revoke');
  assert.equal(body.items[0].key, key);
  assert.equal(body.items[1].action, 'create');
  assert.equal(body.items[1].count, 2);
});

test('管理口令连续失败会被限流', async () => {
  const env = makeEnv();
  const ip = '203.0.113.77';
  const bad = { authorization: 'Bearer wrong', 'cf-connecting-ip': ip };
  let last;
  for (let i = 0; i < 8; i++) {
    last = await json(await call(env, '/admin/api/keys', { headers: bad }));
    assert.equal(last.status, 401);
  }
  const blocked = await json(await call(env, '/admin/api/keys', { headers: bad }));
  assert.equal(blocked.status, 429);
  assert.equal(blocked.body.code, 'too_many_attempts');
});

test('激活时上报的设备信息会按白名单落库', async () => {
  const env = makeEnv();
  const [key] = await newKey(env);
  await call(env, '/api/activate', {
    method: 'POST',
    body: {
      key,
      device: 'dev-a',
      info: {
        manufacturer: 'Xiaomi',
        brand: 'Redmi',
        model: '23127PN0CC',
        android: '15',
        sdk: 35,
        app: '1.0.7',
        evil: 'x',
      },
    },
  });

  const { body } = await json(
    await call(env, '/admin/api/keys', { headers: { authorization: 'Bearer test-admin' } }),
  );
  const item = body.items.find((it) => it.key === key);
  assert.equal(item.info.brand, 'Redmi');
  assert.equal(item.info.model, '23127PN0CC');
  assert.equal(item.info.sdk, '35');
  assert.equal(item.info.evil, undefined, '白名单之外的字段不能落库');
});

test('旧客户端先绑定，新版校验可补全设备信息', async () => {
  const env = makeEnv();
  const [key] = await newKey(env);
  const act = await (
    await call(env, '/api/activate', { method: 'POST', body: { key, device: 'dev-a' } })
  ).json();
  assert.equal(act.ok, true);

  await call(env, '/api/verify', {
    method: 'POST',
    body: { key, device: 'dev-a', token: act.token, info: { brand: 'Redmi', model: '23127PN0CC' } },
  });

  const { body } = await json(
    await call(env, '/admin/api/keys', { headers: { authorization: 'Bearer test-admin' } }),
  );
  assert.equal(body.items.find((it) => it.key === key).info.model, '23127PN0CC');
});

test('设备信息格式不对不影响激活', async () => {
  const env = makeEnv();
  const [key] = await newKey(env);
  const { body } = await json(
    await call(env, '/api/activate', {
      method: 'POST',
      body: { key, device: 'dev-a', info: { model: { nested: true }, brand: 123 } },
    }),
  );
  assert.equal(body.ok, true);
  assert.equal(body.code, undefined);
});

test('备注可以修改并写入审计日志', async () => {
  const env = makeEnv();
  const headers = { authorization: 'Bearer test-admin' };
  const [key] = await newKey(env);

  const { body } = await json(
    await call(env, '/admin/api/note', { method: 'POST', headers, body: { key, note: '  售后跟进  ' } }),
  );
  assert.equal(body.ok, true);
  assert.equal(body.note, '售后跟进');

  const list = await json(await call(env, '/admin/api/keys', { headers }));
  assert.equal(list.body.items.find((it) => it.key === key).note, '售后跟进');

  const logs = await json(await call(env, '/admin/api/logs', { headers }));
  assert.equal(logs.body.items[0].action, 'note');
  assert.equal(logs.body.items[0].key, key);
});

test('未知路径返回 404', async () => {
  const env = makeEnv();
  const { status } = await json(await call(env, '/nope'));
  assert.equal(status, 404);
});

// ---- 测试辅助：与服务端一致的 base64url / HMAC ----

function b64url(s) {
  return Buffer.from(s, 'utf8').toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

async function hmac(payload, secret) {
  const { createHmac } = await import('node:crypto');
  return createHmac('sha256', secret).update(payload).digest('base64')
    .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
