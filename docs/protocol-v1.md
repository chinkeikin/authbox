# 跨端密码学与备份格式 v1

2026年10月16日。端到端加密格式和完整备份已通过 Node、Chrome 页面及 Android app_process 互通；业务 schema、同步和权限见 [业务协议](sync-api-v1.md)，账号/同步核心已通过 [服务验证](api-results.md)。本页不代表正式客户端或生产部署已完成。

## 密码学参数

| 用途 | v1 参数 |
| --- | --- |
| 平台登录密码 | 服务端 Argon2id，32 MiB、3 轮、并行度 1、32 字节输出；本步没有更改 DO 探针 |
| 保险库密码 | 客户端 Argon2id v19，64 MiB、3 轮、并行度 1、32 字节输出，16 字节随机盐 |
| VMK | 系统随机数生成的 32 字节独立主密钥 |
| 实体、清单及 VMK 封装 | AES-256-GCM，随机 12 字节 nonce，16 字节认证标签 |
| 保险库恢复密钥 | 独立随机 32 字节，经 HKDF-SHA256 派生 32 字节封装密钥 |
| HKDF | 32 字节随机盐，UTF8 info 固定为 `AuthBox/v1/vmk-recovery` |
| 文件摘要 | SHA256，检查传输和落盘完整性，不作为来源认证 |

哈希由固定版本 hash-wasm 执行，AES、HKDF 和随机数使用 WebCrypto。Android 互通探针使用 JCA 和 Bouncy Castle，不自行实现密码学算法。平台登录密码和保险库密码用途独立，账号服务使用相同的 `LOGIN_KDF` 约定。

保险库密码采用原始 UTF8，不做 trim、大小写转换或 Unicode 规范化；v1 接受 8–1024 字节，拒绝未配对 UTF16 代理项。密码策略的交互提示留给客户端。v1 只接受上表完整 KDF 配置，先检查格式和参数再执行计算，未知或过大的参数拒绝，不降级。单个 JS 模块实例串行派生，最多接纳 4 个任务（含正在运行的 1 个），多标签页和多 Worker 的资源协调留待客户端工程。

## 字节与 JSON

- 二进制统一使用无填充 base64url，拒绝非规范尾比特和 `=`；GCM 的 `ciphertext` 为密文后接认证标签。
- UUID 为小写规范 UUID v4。版本、实体修订号及密钥代次为大于 0 的安全整数；v1 创建时密钥代次为 1。
- JSON 使用 UTF8，无 BOM、空格或换行。对象键按 UTF16 码元排序，数组保留顺序，字符串按 `JSON.stringify` 规则转义。数字仅允许 JavaScript 安全整数，拒绝浮点数、非有限数及未配对代理项；负零编码为 `0`。不是通用 JCS 实现。
- 解密后的明文也必须满足同样编码。嵌套深度最多 32；不允许稀疏数组、非 JSON 类型、重复键或未知外层字段。完整备份要求重新编码后的字节与原文件一致，因此重复键和不同序列化形式均被拒绝。

`encoding.mjs` 是客户端编码实现。跨端应以公开向量的 AAD 字节和密文结果为准，不能直接依赖各平台 JSON 库的默认键顺序或转义行为。

## 实体密文

外层字段固定为 `version`、`vaultId`、`id`、`type`、`revision`、`keyGeneration`、`nonce`、`ciphertext`。`type` 支持 `account`、`group`、`tag`、`settings`，以及仅供备份认证使用的 `manifest`。单条 JSON 明文上限为 65,536 字节。

AAD 为以下数组的规范 JSON UTF8：

```text
["AuthBox",1,"entity",vaultId,id,type,revision,keyGeneration]
```

每次加密由库内部生成新 nonce，调用方不能指定固定 nonce。完整备份还检查同一 VMK 下各实体及清单的 nonce 不重复；不同快照中原样保留同一条密文不属于再次加密。跨设备随机 nonce 的碰撞概率不是数学上的零，长期写入计数及换代策略须在同步和密钥轮换实现中落实。

账号名称、密钥、分组、标签、收藏、排序和备注只能进入加密 payload；外层不建立明文搜索索引。业务字段、分组引用和删除语义的 schema 见 [业务协议](sync-api-v1.md)，客户端写入数据库前必须完成业务校验。

## VMK 封装与恢复密钥

封装字段固定为 `version`、`vaultId`、`keyGeneration`、`purpose`、`kdf`、`nonce`、`ciphertext`。`purpose` 为 `password` 或 `recovery`，解密后的 VMK 必须恰为 32 字节。

密码封装的 `kdf` 为：

```json
{"name":"argon2id","version":19,"memoryKiB":65536,"iterations":3,"parallelism":1,"hashBytes":32,"salt":"<16-byte-base64url>"}
```

恢复封装的 `kdf` 为：

```json
{"name":"HKDF-SHA256","salt":"<32-byte-base64url>","info":"AuthBox/v1/vmk-recovery"}
```

封装 AAD 为：

```text
["AuthBox",1,"vmk-wrap",vaultId,keyGeneration,purpose,kdf]
```

恢复密钥文本为 `ABR1-<52位无填充大写Base32>-<8位校验>`。校验取原始 32 字节 SHA256 的无填充 base64url 前 8 个字符，区分大小写；校验仅帮助发现抄写错误，不代替密钥保密或 GCM 认证。恢复密钥原文不写入保险库备份。

修改保险库密码时可以用 `wrapVmk` 对当前 VMK 生成新密码封装，再生成新清单和快照，不需要重新加密全部实体。旧离线备份仍需旧密码或对应的恢复密钥。平台账号恢复码与此密钥无关，其单次消费和账号恢复由独立服务事务处理。

## 自包含备份

备份整体为一个规范 JSON 文件，最多 16 MiB、10,000 个实体，不分页、不压缩、不允许截断成功。两项上限同时适用。字段固定如下：

| 字段 | 要求 |
| --- | --- |
| `magic` / `version` | `AUTHBOX-BACKUP` / `1`，未知版本拒绝 |
| `vaultId` / `revision` / `keyGeneration` | 固定快照身份和版本，原 UUID 保留供离线验证 |
| `createdAt` / `appVersion` | UTC 毫秒 ISO 时间和生成客户端版本，均纳入认证 |
| `wraps` | 同一保险库及密钥代次的 password 和 recovery 两种封装 |
| `entities` | 按 UUID 升序、无重复的完整实体数组，实体 revision 不得大于快照 revision |
| `manifest` | 用 VMK 加密的快照清单；id 为 vaultId，type 为 manifest，revision 为快照版本 |
| `digest` | 除本字段外整个备份的规范 JSON 的 SHA256，无填充 base64url |

清单的明文为 `{"digest": <主体摘要>, "entityCount": <实体数>}`。主体包含上述字段中除 `manifest`、`digest` 之外的全部内容，包括封装和完整实体列表。客户端解密清单后核对主体摘要，再逐条认证所有实体，全部成功才返回恢复结果。删除实体、替换密文、修改封装或快照头，即使重算外层文件摘要，也不能通过该清单认证。

同步服务遵守以下边界：**认证清单由持有 VMK 的手机在提交完整快照时生成，与封装和实体在同一版本原子提交。** 服务端只能保存、返回并计算外层文件摘要，不能自行生成认证清单。手机需根据目标提交版本构建清单，云端以版本条件写入；发生并发冲突则手机重新构建并提交。云端不得在备份时改写已认证的时间、版本或条目。此事务边界已通过服务端故障回滚和并发检查，手机应用仍待实现。

`inspectBackup` 仅做无秘密的格式及文件摘要检查，不能证明来源或密文有效。`restoreBackup` 用密码或恢复密钥解锁，再认证清单和全部实体，不依赖平台登录、Google、Keystore 或网络，也不直接改写本地数据库。调用者只能在全部业务校验成功后原子导入。掌握 VMK 的设备有能力生成新的有效备份；格式不提供防恶意服务器完整回滚的保证。

## 接口、向量与复现

实现位于 `packages/protocol/src/`：

- `totp.mjs`：严格 URI 解析、TOTP 参数校验和本地取码。
- `vault.mjs`：创建 VMK、密码与恢复封装、实体加解密、恢复密钥编码。
- `backup.mjs`：生成完整备份、无秘密检查、离线恢复。

`createVault` 和 `restoreBackup` 返回的 VMK 及明文只供调用者内存使用，不可直接持久化或记录日志。模块尽力清理派生密钥字节，JS 字符串、库内部副本和 GC 不支持硬件级擦除保证。本机加密存储、锁定、受信任扩展上下文及密钥生命周期还需客户端实现。

公开固定向量为 [v1.json](../packages/protocol/test/fixtures/v1.json)，包括合成密码、VMK、恢复密钥、AAD、四类实体和备份，由 Node 原生 Argon2id、HKDF、AES-GCM 独立生成。固定密钥、盐和 nonce **仅用于测试**，不得加密真实数据。`node tools/protocol/generate-vectors.mjs` 可复现，不会创建真实用户数据。

```text
npm test
npm run verify:protocol:browser
npm run verify:protocol:android
```

浏览器命令使用隔离的已安装 Chrome，加载本地包后断网执行。Android 命令复用可行性探针的环境变量和已核验 Bouncy Castle JAR，限定用户选择的 PHB110，输出后清理临时文件。二者详细报告均写入 Git 忽略的 `.cache/protocol/`，公开验证记录见 [协议验证结果](protocol-results.md)。

依据：[RFC 6238](https://www.rfc-editor.org/rfc/rfc6238.html)、[RFC 9106](https://www.rfc-editor.org/rfc/rfc9106.html)、[RFC 5869](https://www.rfc-editor.org/rfc/rfc5869.html)、[WebCrypto AES-GCM](https://developer.mozilla.org/en-US/docs/Web/API/SubtleCrypto/encrypt)。
