/**
 * 本地联调服务器：把 Cloudflare Worker 跑在 Node 里，数据落到本地 JSON 文件。
 *
 * 用途：真机连同一个 WiFi，App 里把授权地址指到 http://<电脑局域网IP>:8787
 * 就能完整跑通「激活 / 校验 / 换机拒绝」流程，不必等 Cloudflare 部署。
 *
 * 启动：
 *   node server/dev-server.mjs
 * 自定义口令：
 *   LICENSE_SECRET=xxx ADMIN_TOKEN=yyy node server/dev-server.mjs
 */

import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import worker from './worker.js';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const DATA_FILE = path.join(HERE, '.dev-data.json');
const PORT = Number(process.env.PORT || 8787);

const store = new Map(
  fs.existsSync(DATA_FILE) ? Object.entries(JSON.parse(fs.readFileSync(DATA_FILE, 'utf8'))) : [],
);

function persist() {
  fs.writeFileSync(DATA_FILE, JSON.stringify(Object.fromEntries(store), null, 2));
}

const env = {
  LICENSES: {
    async get(k) {
      return store.has(k) ? store.get(k) : null;
    },
    async put(k, v) {
      store.set(k, v);
      persist();
    },
    async delete(k) {
      store.delete(k);
      persist();
    },
    async list({ prefix = '' } = {}) {
      return {
        keys: [...store.keys()].filter((k) => k.startsWith(prefix)).map((name) => ({ name })),
        list_complete: true,
      };
    },
  },
  LICENSE_SECRET: process.env.LICENSE_SECRET || 'dev-secret-change-me',
  ADMIN_TOKEN: process.env.ADMIN_TOKEN || 'dev-admin',
};

const server = http.createServer(async (req, res) => {
  const chunks = [];
  for await (const c of req) chunks.push(c);
  const body = chunks.length ? Buffer.concat(chunks) : undefined;

  const request = new Request(`http://${req.headers.host}${req.url}`, {
    method: req.method,
    headers: req.headers,
    body: req.method === 'GET' || req.method === 'HEAD' ? undefined : body,
  });

  try {
    const response = await worker.fetch(request, env);
    res.writeHead(response.status, Object.fromEntries(response.headers));
    res.end(Buffer.from(await response.arrayBuffer()));
  } catch (e) {
    res.writeHead(500, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ ok: false, message: String(e) }));
  }
});

server.listen(PORT, '0.0.0.0', () => {
  console.log('授权联调服务器已启动');
  console.log(`  本机：      http://127.0.0.1:${PORT}/admin`);
  for (const list of Object.values(os.networkInterfaces())) {
    for (const net of list || []) {
      if (net.family === 'IPv4' && !net.internal) {
        console.log(`  局域网：    http://${net.address}:${PORT}/admin`);
        console.log(`  App 填：    http://${net.address}:${PORT}`);
      }
    }
  }
  console.log(`  管理口令：  ${env.ADMIN_TOKEN}`);
  console.log(`  数据文件：  ${DATA_FILE}`);
});
