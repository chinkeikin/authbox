# 业务数据、同步与权限协议 v1

2026年10月16日。本文固定第一版账号及同步服务的边界，服务代码位于 `services/api/`，实际验证状态另见开发路线。已有加密容器、AAD 和备份编码继续使用 [密码学协议](protocol-v1.md)，不改变原有密钥派生参数。

## 数据与存储边界

客户端在解密、业务校验全部成功后，才在本地事务中写入完整候选状态。业务实体的 payload 固定 `schemaVersion: 1`，拒绝未知字段、未知版本及悬空引用。

| 类型 | 明文字段，均放入客户端加密的 payload |
| --- | --- |
| account | schemaVersion、issuer、name、account、secret、algorithm、digits、period、groupId、tagIds、favorite、order、notes、deletedAt |
| group | schemaVersion、name、order |
| tag | schemaVersion、name |
| settings | schemaVersion、defaultView，取 all 或 favorites；一个保险库最多一个设置实体 |

名称等短文本上限 256 个 UTF8 字节，备注 4096 字节，禁止控制字符。账号至少有 issuer/name/account 中一个非空；TOTP Secret 必须是规范大写无填充 Base32。order 为非负安全整数。groupId 为 UUID 或 null（未分组），tagIds 最多 64 个不同 UUID，必须引用当前分组/标签。删除分组或标签的提交同时更新所有关联账号。account.deletedAt 为 null 或 UTC 毫秒 ISO 时间，软删除仍保留完整加密内容；默认回收站 30 天后才由手机发起移除。服务端看不到 deletedAt，不能自行判断明文回收站到期。

个人自用部署以**单个安装级 SQLite Durable Object**作为账号、用户名唯一性、邀请消费、设备、会话、恢复码、密文快照、幂等结果和 nonce 登记的唯一数据源。对象通过固定名称定位，不使用请求提供的 user_id 决定身份，不引入 D1/R2。所有影响一致性的改动在同一个 `transactionSync` 中提交；内存队列仅限制并发及 KDF 内存占用，不代替数据库条件检查和事务。该方案面向个人规模，不宣称可承载公开大规模注册服务。

完整离线备份仍上限 16 MiB；当前在线快照额外限制为 **1 MiB**，使完整备份能作为一行保存在 SQLite 的 2 MB 行上限以内。超过限制明确拒绝，不能截断或降低 KDF 参数。扩大在线容量需要后续分块协议，不悄悄开通付费存储。[Cloudflare SQLite 限制](https://developers.cloudflare.com/durable-objects/platform/limits/)

## 同步事务

首版采用完整密文快照提交和拉取，不实现实体分页或服务端合并。提交 payload 为：

```text
{operationId: UUID, baseRevision: integer, restoredIds: UUID[], backup: canonicalBackupString}
```

空服务端版本为 0，初次快照 revision/keyGeneration 均为 1。只有 baseRevision 等于当前版本，且新备份 revision 为 baseRevision + 1，才接受提交。已有保险库不得改变 vaultId；keyGeneration 只能维持或加 1，新代次必须让完整实体及封装都使用该代次。实体未改动则保留原密文及 revision，新增或改变的实体必须使用目标快照 revision，类型不得改变。

服务端记录移除过的稳定实体 ID。重新加入这些 ID 必须在 restoredIds 中显式列出，类型保持一致；否则拒绝，避免重新拉取后的旧离线内容静默复活。恢复历史快照时也必须作为当前版本上的新提交，不能回退同步序号。客户端需要保留本地待上传候选，发生冲突后先完整拉取再展示合并/恢复选择，不能只把 baseRevision 改成最新后自动覆盖。

同一用户、设备及 operationId 对应唯一请求摘要和提交结果。相同请求重试返回原结果；同 ID 不同请求返回 409。即使重试已成功请求，也必须先验证会话、设备当前状态和当前权限。请求摘要不含 proof；同一次操作可重新获取挑战和签名。写入条件检查、快照、ID 移除/恢复登记、nonce 登记、版本递增、挑战消费及幂等结果在同一事务中完成，失败全部回滚。

同一 VMK 代次下，实体及认证清单的 nonce 必须唯一；完全相同密文在多个快照中保留允许，重复 nonce 对应不同密文或身份则拒绝。服务端无需 VMK 即可登记 `SHA256(canonical(record))`。手机生成认证清单，云端只保存并返回完整备份，不能重新生成或改写已认证的头和封装。服务端只能验证格式及摘要，客户端仍必须 AEAD 认证和业务校验后再使用。

历史快照保留至少 30 天和当前版本，超容量时拒绝新写入，不能为了接收新数据而静默清除未过期历史。初期每用户快照总量上限 32 MiB、安装总量 128 MiB；实体 ID 登记最多 10,000 个、nonce 登记最多 100,000 个/用户。幂等记录保留 30 天、每用户最多 10,000 个；过期原始请求的旧 baseRevision 仍会冲突，客户端不得复用 operationId。读取旧版本只限当前用户，游标早于历史窗口返回 410/full_sync_required，未来游标返回 400。

## 身份、设备与会话

用户名固定为 `[a-z][a-z0-9_]{2,31}`，平台密码为原始 UTF8 的 8–256 字节，不 trim 或规范化。密码 PHC 使用 Argon2id v19，32 MiB、3 轮、并行度 1、32 字节输出、16 字节随机盐。不存在的用户也执行同参数 dummy KDF，错误信息统一。保险库派生保持独立的 64 MiB 参数。

注册必须消费部署者配置的高熵一次性邀请，服务端仅保存其 SHA256。注册事务同时建立账号、首个管理设备、会话、账号恢复码哈希和初始加密快照；客户端须先保存恢复材料。只要任一步失败，不能消耗邀请或留下半初始化账号。登录密码不进入日志、请求摘要、备份或快速哈希记录。

普通用户名密码登录**总是创建新的只读设备和会话**，请求不得提供 role、client_type 或已有设备 ID 来取得管理权限。新手机可以提交 P-256 公钥作为待审批设备，已有管理设备明确批准后，撤销该设备已有只读会话；目标设备需用平台密码及其私钥完成 device-login 才能取得管理会话。无公钥的 Chrome 设备不能被批准为管理设备。已有管理手机重新登录同样需要密码和登记私钥证明。

访问/刷新令牌是独立的 256 位随机 bearer secret，不包含可由客户端修改的角色。访问令牌有效 10 分钟；刷新会话闲置 30 天、绝对 90 天；服务端仅保存令牌的域内 SHA256。每次访问重新检查会话和设备状态，角色来自数据库；撤销无需等访问令牌过期。刷新轮换访问及刷新令牌，旧刷新令牌重用撤销整个会话族，新旧令牌均失效。注销允许只读设备执行，不需要管理签名。客户端持久保存令牌的安全封装留待对应客户端工程。

账号恢复码独立于保险库恢复密钥，每组由客户端生成 8 个 128 位随机码，文本为 `ABAC1-<16字节无填充base64url>`，哈希为 `SHA256(UTF8("AuthBox/account-recovery/v1/" + code))`。注册/轮换只上传哈希；恢复时通过 TLS 提交一个原码和新平台密码，并证明新管理设备私钥。单次消费、密码重设、撤销全部旧会话/设备及新管理授权必须原子完成。恢复不会改变或解密保险库，新设备仍需保险库密码或 ABR1 恢复密钥。

## 私钥挑战与权限

设备公钥仅接受 `{kty:"EC",crv:"P-256",x:<32字节>,y:<32字节>}`，拒绝私钥字段。签名为 WebCrypto ECDSA/SHA256 的 64 字节 P1363 格式，无填充 base64url；Android Keystore 的 DER 签名需在客户端明确转换，不能把 DER 原样当成 P1363。

挑战有效期 90 秒，签名字节为以下规范 JSON 的 UTF8：

```text
["AuthBox",1,"device-proof",apiOrigin,challengeId,subject,deviceId,sessionId,operation,requestHash,expiresAt,nonce]
```

subject 为用户 UUID；注册、device-login、恢复等未登录流程使用用户名，sessionId 为空。requestHash 是操作 payload 的 SHA256；注册与恢复排除密码、邀请原码及恢复原码，只绑定非秘密上下文，禁止将可猜测密码写成快速请求哈希。nonce 为 32 字节系统随机值。服务端校验 API origin、当前主体/设备/会话、操作、摘要、到期、未使用状态和签名，不能只校验签名。成功写入事务中单次消费；同签名不得换主体、操作、请求、环境或会话重放。

更改平台密码单独采用 prepare/confirm：prepare 在管理会话中验证旧密码并生成新密码的同强度 PHC，短期存于挑战，签名绑定该随机盐慢哈希的摘要；confirm 验证设备签名后原子消费挑战、替换 PHC、撤销旧会话并返回新会话。普通 challenge 接口不能创建 password.change，以免绕过旧密码重认证或保存密码快速哈希。

| 主体 | 允许 |
| --- | --- |
| reader | 读当前密文/版本/自己的历史，刷新及注销自己的会话 |
| manager | reader 能力；查看本账号设备；带私钥证明提交快照、审批/撤销设备、改平台密码、轮换账号恢复码 |
| backup_reader | 仅备份接口读取清单及完整密文快照，不能用作用户会话；专用凭据 API 留到 NAS 步骤实现 |

管理员身份只限所属账号，没有全站管理或跨用户读写能力。读权限不能生成管理挑战；修改前后均检查当前授权。Google 的后续绑定仅替代密码输入，不跳过设备证明或改变角色。

## HTTP 边界

API 固定 HTTPS origin。所有业务响应 `Cache-Control: no-store`，不使用 Cookie 会话，不把秘密放在 URL。CORS 仅允许显式配置的客户端 origin，不开放通配；没有 Origin 的原生客户端仍须按同一认证规则访问。Worker 只做轻量路由及转发，大体积 JSON、KDF 和数据库计算在 DO 中进行。

| 路由 | 协议 |
| --- | --- |
| POST /v1/auth/challenge | 未登录注册/device-login/恢复挑战，按来源和主体限速 |
| POST /v1/auth/register | 邀请、密码、设备证明、恢复码哈希、初始备份，同事务注册 |
| POST /v1/auth/login | 密码登录，默认新 reader，会话与设备由服务端生成 |
| POST /v1/auth/device-login | 密码、已有设备 ID 及私钥挑战，恢复登记设备当前角色 |
| POST /v1/auth/recover | 一次性账号恢复码、新密码和新设备私钥证明 |
| POST /v1/auth/refresh /logout | 轮换或注销当前会话，不能提权 |
| POST /v1/challenges | 已认证 manager 生成指定管理操作挑战 |
| GET /v1/devices | 仅 manager 查看本账号设备 |
| POST /v1/devices/approve /revoke | manager 会话和设备证明，目标必须属于同账号 |
| POST /v1/account/password/prepare /confirm | 旧密码重认证与设备签名确认 |
| POST /v1/account/recovery/rotate | manager 会话和设备证明，替换账号恢复码哈希 |
| GET /v1/vault/head /snapshot /changes | 当前主体隔离读取，历史和游标规则见上文 |
| POST /v1/vault/commit | manager，payload 与 proof 分开，原子版本提交 |

挑战、认证和刷新使用 SQLite 持久限速；内存队列最多一个执行、八个等待，重启不清除限速或授权记录。配置错误、超过免费平台额度或应用容量限制时明确失败，不自动升级套餐，不回退密码强度。公开临时验证入口必须另有随机探针凭据和到期限制，正式服务不包含读取密码哈希或任意清库的接口。

实现基线：`business.mjs`、`sync.mjs`、`permissions.mjs`、`account-recovery.mjs` 和 `api.mjs`；单元测试覆盖版本、引用、权限、签名绑定及认证摘要排除秘密。实际请求体、配置、持久记录容量限制见 [服务说明](../services/api/README.md)。服务端的并发、事务故障回滚、单次消费、重启持久化和免费环境运行结果见 [服务验证记录](api-results.md)，不能用纯函数测试代替。
