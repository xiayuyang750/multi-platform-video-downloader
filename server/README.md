# 授权服务（Cloudflare Worker）

一机一码的后端，只做一件事：记录「哪个密钥绑在哪台设备上」。
代码是单文件 [`worker.js`](./worker.js)，不需要数据库服务器 —— 数据放在 Cloudflare KV 里。

---

## 一、它是怎么工作的

```
用户输入 WM-XXXX-XXXX-XXXX
        │
        ▼
App 算出本机设备码（ANDROID_ID + 厂商/型号 的 SHA-256 前 32 位）
        │
        ▼
POST /api/activate { key, device }
        │
        ├── 密钥不存在        → 提示「密钥不存在」
        ├── 已被停用          → 提示「已被停用」
        ├── 已绑在别的设备    → 提示「已绑定另一台设备」
        └── 没绑过 / 就是本机 → 绑定并签发 30 天有效的 token
        │
        ▼
App 保存 token；之后每次启动静默调 /api/verify 续期
```

- **换手机**：设备码不同 → 服务端拒绝，必须由你在管理页解绑。
- **离线**：token 还在有效期内就照常使用，联网后自动续期。
- **清应用数据**：本地 token 没了，但设备码没变，重新输入同一个密钥即可恢复。

---

## 二、部署到 Cloudflare（网页操作，不用装命令行）

### 1. 注册

打开 <https://dash.cloudflare.com/sign-up> 注册一个免费账号，登录后进入控制台。

### 2. 创建 Worker

左侧 **Workers & Pages** → **Create** → **Worker** → 名字填 `watermark-license` → **Deploy**。

部署后会进入在线编辑器：把仓库里 [`worker.js`](./worker.js) 的**全部内容**粘贴进去，覆盖默认代码，点右上角 **Deploy**。

### 3. 创建 KV 命名空间

左侧 **Storage & Databases** → **KV** → **Create namespace** → 名字填 `LICENSES`。

### 4. 绑定 KV

回到 Worker → **Settings** → **Bindings** → **Add** → **KV namespace**：

- Variable name：`LICENSES`（必须一字不差）
- KV namespace：选刚才创建的 `LICENSES`

### 5. 配置两个密文变量

同一个 **Settings** 页 → **Variables and Secrets** → **Add**：

| 名称 | 类型 | 值 |
|---|---|---|
| `LICENSE_SECRET` | Secret | 一串足够长的随机字符，例如 40 位以上乱码 |
| `ADMIN_TOKEN` | Secret | 你自己定的管理口令，登录管理页要用 |

`LICENSE_SECRET` 一旦换掉，所有已签发的 token 会立即失效（用户可以重新激活，不影响绑定关系）。

### 6. 记下地址

Worker 的地址形如：

```
https://watermark-license.你的子域.workers.dev
```

打开 `https://.../admin`，输入 `ADMIN_TOKEN`，就能生成密钥、看绑定情况、停用、解绑。

### 7. 让 App 指到这个地址

打包时加参数：

```powershell
& 'D:\Dev-env\Gradle97\Program\bin\gradle.bat' -p android assembleRelease --offline `
  "-PlicenseBaseUrl=https://watermark-license.你的子域.workers.dev"
```

不加这个参数时，App 里会明确提示「授权服务器还没有配置」。

---

## 三、重要：国内访问问题

`*.workers.dev` 在国内**经常连不上**，这不是代码问题，是域名本身被干扰。

正式对外发布前，建议：

1. 买一个自己的域名（几十元/年），托管到 Cloudflare；
2. 在 Worker → **Settings** → **Domains & Routes** → **Add custom domain**，绑定 `license.你的域名`；
3. 打包时把 `licenseBaseUrl` 换成这个自定义域名。

在没绑定自定义域名之前，这套只适合自己测试。

---

## 四、本地联调（不部署也能真机测）

仓库里带了一个 Node 版服务器，逻辑与 Worker 完全相同，数据存在 `server/.dev-data.json`：

```powershell
node server/dev-server.mjs
```

启动后会打印电脑的局域网地址，例如：

```
App 填：http://192.168.0.103:8787
管理口令：dev-admin
```

然后打包时指定这个地址：

```powershell
gradle ... "-PlicenseBaseUrl=http://192.168.0.103:8787"
```

手机与电脑连同一个 WiFi 即可。管理页：`http://192.168.0.103:8787/admin`。

---

## 五、接口一览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/activate` | `{ key, device }` → 绑定并返回 token |
| POST | `/api/verify` | `{ key, device, token }` → 校验，必要时续期 |
| GET | `/api/health` | 服务是否活着 |
| POST | `/admin/api/keys` | 生成密钥，`{ count, note }` |
| GET | `/admin/api/keys` | 列出全部密钥与绑定状态 |
| POST | `/admin/api/revoke` | `{ key, banned }` 停用 / 恢复 |
| POST | `/admin/api/unbind` | `{ key }` 解绑设备 |
| GET | `/admin` | 管理页面 |

管理接口统一用 `Authorization: Bearer <ADMIN_TOKEN>`。

---

## 六、已知限制

- **KV 不是强一致**：两台设备同时激活同一个密钥，理论上可能都成功（毫秒级窗口）。个人规模可以忽略；要严格可以改用 Durable Object。
- **免费额度**：KV 每天 10 万次读、1000 次写；激活/校验都是读，够用很久。
- **防不住改包**：有人反编译把校验删掉，任何纯客户端授权都拦不住。这里的目标是防「一个密钥到处传」，不是防专业破解。
- **试用次数记在本地**：清应用数据会重置 3 次试用。早期可以接受，量大了再改成服务端记账。

---

## 七、测试

```powershell
node --test server/worker.test.mjs
```

覆盖：密钥格式、首次激活、同机重激活、换机拒绝、token 篡改、token 过期、停用、解绑、管理接口鉴权、列表查询。
