# AuthBox 账号与同步服务

运行于 Workers Free + SQLite Durable Objects，安装级 `InstallationStore` 是唯一数据源。提供自定义用户名密码、设备授权、可撤销会话、账号恢复和完整密文快照同步。不依赖 D1/R2、Google、短信或 NAS 在线。当前为服务基础，Android/Chrome 界面、Google 绑定和 NAS 专用备份凭据尚未实现。

## 构建与验证

在仓库根目录运行：

```text
npm ci
npm test
npm run build:api
```

`npm test` 依次运行协议单元测试、HTTP 边界测试、本地 workerd/SQLite 完整接口检查及正式打包入口检查。真实云端验证使用 `npm run verify:api:cloud`，需先配置根目录被忽略的 `.env.cloudflare`，并在本机设置已获域名所有者授权的 `AUTHBOX_CF_TEST_ZONE` 与 `AUTHBOX_CF_TEST_HOST`。该程序先拒绝已订阅 Workers Paid 的账户，只创建临时、受随机凭据保护且 30 分钟失效的测试服务，结束时核对删除 Worker、SQLite 命名空间和域名绑定。控制面临时 GET 失败有限重试；中断后用同一配置运行 `npm run verify:api:cloud -- --cleanup`。托管证书生命周期独立，不能以绑定删除代替证书清理证明。

正式入口 `src/worker.mjs` 和测试入口 `test/fixture-worker.mjs` 分离。正式包检查排除测试控制、改时钟、注入故障、读出密码哈希等代码。`test/suite.mjs` 只使用合成账号与保险库，并通过 Node 原生 Argon2id 独立验证 SQLite 保存的 PHC。本地临时数据库、部署生成物、原始日志和报告不进入 Git。

`npm run build:api` 输出 `dist/api/worker.mjs`、两个静态 WASM 模块和第三方许可。共享构建器固定 hash-wasm 4.12.0 及源码摘要，仅适配静态加载，不修改算法。可以用以下命令只检查本地部署包：

```text
node node_modules/wrangler/bin/wrangler.js deploy --dry-run --config services/api/wrangler.json --outdir .cache/api/dry-run
```

仓库配置使用占位 API origin，禁止 workers.dev/预览 URL，注册邀请默认空；没有配置正式域名或自动部署。正式部署还需要确定域名、客户端 origin 和初始化材料，不与构建或 Git push 混为一项操作。

## 配置

| 配置 | 要求 |
| --- | --- |
| INSTALLATION | SQLite DO 绑定，类为 InstallationStore；迁移必须使用 new_sqlite_classes |
| API_ORIGIN | 唯一 HTTPS origin，无路径、尾斜杠或凭据，绑定域名必须匹配 |
| CLIENT_ORIGINS | JSON 字符串数组，明确允许的 HTTPS/Chrome 扩展 origin；默认空，原生客户端可不发送 Origin |
| REGISTRATION_INVITE_HASHES | 最多 16 个不同的 32 字节 SHA256，无填充 base64url；对应邀请原码必须是独立随机 32 字节的 base64url，只通过安全渠道交给首次注册端 |

不要将 Cloudflare API Token 放入应用配置，也不要把注册邀请、平台密码、保险库密码或恢复材料写进仓库。应用不自动更改 Cloudflare 套餐。部署者需保持 Workers Free；同一账户若为其他项目升级套餐，计费规则需重新检查。

## 请求与身份

API 请求体要求 UTF8 规范 JSON（使用协议库 `canonical`），媒体类型为 application/json；拒绝重复键、非规范编码、压缩体和过大体。单次读取最多 10 秒，提前拒绝时只进行有界清理。访问令牌用 `Authorization: Bearer <accessToken>` 传递，刷新令牌只在刷新请求体内传递。不使用 Cookie 或 URL 令牌，业务响应禁止共享缓存。

HTTP 请求体上限为 2 MiB + 16 KiB，在线 backup 字符串的 UTF8 上限为 1 MiB，离线文件格式仍为 16 MiB。客户端收到容量失败必须保留本地数据，不得截断上传或降低密码强度。

挑战 `requestHash` 使用 `operationDigest(payload)`。签名采用 `signChallenge(privateKey, challenge.payload)`，传回 `proof = {id: challenge.id, signature}`。WebCrypto 的签名为 64 字节 P1363；Android Keystore 接入时需明确处理 DER 格式差异。服务端实际检查签名、操作、主体、设备、会话、请求摘要、90 秒到期和单次消费。

`signChallenge` 是底层签名函数，客户端签名前必须自行核对返回挑战的 origin、主体、设备、会话、操作和请求摘要与预期一致，再交由设备私钥签名，不能把它作为任意数据签名接口。

## 请求体速查

| 路由 | 请求体 |
| --- | --- |
| POST /v1/auth/challenge | operation、username、deviceId、requestHash、invite；operation 为 auth.register/auth.device-login/auth.recover，只有注册提供邀请，其他为 null |
| POST /v1/auth/register | username、password、invite、device、recoveryHashes、backup、proof |
| POST /v1/auth/login | username、password、deviceName、publicKey；publicKey 为 null 或候选 P-256 公钥，始终返回 reader |
| POST /v1/auth/device-login | username、password、deviceId、proof；必须持有已登记设备私钥 |
| POST /v1/auth/recover | username、code、password、device、proof；password 为新的平台密码 |
| POST /v1/auth/refresh | refreshToken |
| POST /v1/auth/logout | 空对象或空体，注销当前访问令牌所属会话 |
| POST /v1/challenges | operation、requestHash，仅 manager；password.change 使用单独 prepare |
| POST /v1/vault/commit | payload = {operationId, baseRevision, restoredIds, backup}、proof |
| POST /v1/devices/approve /revoke | payload = {deviceId}、proof |
| POST /v1/account/recovery/rotate | payload = {hashes}、proof |
| POST /v1/account/password/prepare | currentPassword、newPassword，仅 manager，返回待签名挑战 |
| POST /v1/account/password/confirm | proof，返回新的会话，旧会话全部撤销 |

device 固定为 `{id: UUID, name, publicKey: {kty:"EC", crv:"P-256", x, y}}`，不允许私钥字段或客户端角色。账号恢复码由 `createAccountRecoveryCodes` 生成，注册/轮换只发送 hashes，原码必须离线保存。恢复和密码修改不会解开或改动保险库。

未登录挑战摘要使用 `authenticationContext(operation, requestBody)`，显式排除 password、invite、code 和 proof：注册只绑定 username/device/recoveryHashes/backup，恢复只绑定 username/device，设备登录只绑定 username/deviceId。不得用完整密码请求体生成快速摘要。密码 prepare/confirm 仅绑定新密码的随机盐 Argon2id PHC 摘要，不保存密码快速哈希。

会话响应为 `user: {id, username}`、`device: {id, role}`、`session: {accessToken, refreshToken, accessExpiresAt, refreshExpiresAt, absoluteExpiresAt}`，时间均为 Unix 毫秒。普通密码登录创建新只读设备；管理设备重新登录要走 device-login。新手机经批准后，原只读会话被撤销，必须用该私钥重新登录。Chrome 无公钥的设备不能直接批准为 manager；任意客户端声明 role/client_type 均被拒绝。

读取接口为 GET /v1/vault/head、/snapshot?revision=N、/changes?after=N，以及仅 manager 的 /v1/devices。不允许 userId 查询参数，归属只取认证会话。changes 返回 unchanged 或 full（指向完整快照），过旧游标返回 410/full_sync_required；冲突时保留本地候选，拉取后由客户端明确处理。

## 事务、限额与状态

- 注册、恢复、密码修改和同步等持久状态修改使用同库同步事务。提交全局 revision 的 CAS、不可变快照、ID/nonce 登记、挑战消费和幂等结果一起提交。
- 幂等仅作用于同一用户及设备的 operationId，重试也先检查当前权限；旧访问/刷新令牌、已撤销设备及只读会话不能靠重试取得写权限。
- 访问有效期 10 分钟，刷新闲置 30 天、绝对 90 天。刷新轮换；旧刷新令牌重用立即撤销整个会话族。客户端必须串行刷新，丢失轮换响应后可能需要重新登录。
- 原始账号恢复码只消费一次；轮换时保留旧哈希的作废记录，禁止把旧材料重新激活。账号恢复撤销全部旧设备和会话，保留保险库密文。
- 每安装最多 16 个账号；每用户最多 20 个活跃设备、200 个设备记录、64 个活跃会话、256 个未到绝对期限的会话记录。每会话最多 4096 条已消费刷新令牌、每用户最多 8192 条；超过后需要重新认证或等待旧记录到期。
- 每用户快照 32 MiB、安装快照 128 MiB；保留当前版本和 30 天内历史。超量拒绝新提交，保留旧数据。每用户最多 10,000 个实体 ID、100,000 条 nonce 登记、10,000 条未过期操作记录和 1024 条账号恢复码历史。
- 认证每分钟最多 100 次/安装、40 次/来源、20 次/主体；管理准备/写请求最多 120 次/用户/分钟；挑战最多 1000 个且短期清理。限速状态在 SQLite 中，重启不能重置额度。等待队列最多 8 个，KDF 串行执行，参数保持 32 MiB/3/1。

典型错误：401（需重新登录或凭据错误）、403（无权或设备证明失败）、409（版本/幂等/nonce 冲突或设备不能审批）、410（需完整重拉）、413（HTTP 体过大）、429（限速/忙）、507（应用存储容量）。未知异常只返回 internal_error，不回传 SQL、堆栈或秘密。服务端不能解密业务字段，也不能证明一个有格式和摘要的备份内容有效，客户端必须执行 AEAD 认证和业务 schema 检查。

验收证据见 [服务验证记录](../../docs/api-results.md)。暂未实现 Google 绑定、NAS backup_reader 凭据与其撤销、用户删除、正式客户端安全存储和完整发布运维；这些不标记为已完成。
