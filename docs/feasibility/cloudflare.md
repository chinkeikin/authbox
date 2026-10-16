# Cloudflare 云端验证结果

验证日期：2026年10月16日。专用 Token 已配置并能读写测试 Worker，未开通或修改付费订阅。阶段 1 尚未完成。

## 正式入口

| 检查 | 实际结果 |
| --- | --- |
| API 权限 | Workers 设置、脚本上传、部署、子域启用、实时日志会话及 Analytics 查询可用 |
| 账号设置 | 默认 usage model 为 standard；订阅查询未发现 Workers 订阅，不能将 standard 字段当作已付费 |
| 上传与部署 | 模块能下载核对，fetch 处理器已登记，版本为 100% 流量部署 |
| 最小 Worker | 仅返回公开固定文本，也在正式 workers.dev 入口返回 HTTP 500 / Cloudflare 1101 |
| 对照 | 自写上传与官方 Wrangler、原测试名与全新测试名、2026-10-16 与 2026-07-01 兼容日期均复现 |
| 访问来源 | 本机、NAS 和用户浏览器均复现 1101；NAS 初次 403 在后续结构化请求中没有稳定复现 |
| 指标与日志 | 控制台调用数为 0，查询未取得正式执行 CPU 数据，实时日志未提供可定位的异常记录 |
| 相同最小脚本的远程预览 | HTTP 200，固定文本和响应标识正确 |

当前根因尚未定位。没有证据认定账号未开通、受限，或正式入口的 1101 由密码哈希开销导致。最小公开文本请求本身不执行密码哈希，不能把此处 1101 当作 KDF 的 CPU 超限结果。

### 自有域名对照

用户授权自有测试子域名后，使用同一最小探针验证：电脑和真实 NAS 均取得 HTTPS 200、预期响应标识及相同公开文本，实时日志也取得 `outcome=ok`。自有域名已提供可工作的正式入口；workers.dev 内部异常根因仍未知，本轮对照还曾返回 404 / 1042，不能将各种入口错误混作 KDF 超限。

现有令牌可以读取 Zone 和操作 Worker 自定义域名，直接 DNS 记录读取返回 403。程序改用 Cloudflare 的变更预检查；只接受新增指定域名、无冲突、无更新及删除的计划，全部覆盖选项为 false。8 个本地用例验证已有 DNS、其他 Worker 绑定、夹带域名、删除计划及缺少冲突字段均被拒绝。

测试结束移除自定义域名绑定及 Worker，按独立标签确认归属并延迟核对。Cloudflare 的域名删除可能返回空成功响应，程序已兼容；最初中断的清理已补做并复核。托管证书不视为随绑定自动删除，证书标识保留在本机报告供后续核对。

复现命令为 `npm run verify:cloudflare:domain-route`，在本机设置 `AUTHBOX_CF_TEST_ZONE` 和 `AUTHBOX_CF_TEST_HOST`；正式 KDF 命令为 `npm run verify:cloudflare:domain`。实际域名、Zone 标识及证书标识不写入公开仓库。

### 自有域名正式 KDF

同一候选参数在可用的自有域名正式入口执行，鉴权、方法、未知路由及 RFC 向量先通过，再发出 3 次顺序请求和 2 个并发请求：

| 检查 | 实际结果 |
| --- | --- |
| 5 次候选请求 | 2 次 200 且哈希一致，3 次 HTTP 503 / 1102 |
| 2 次完成请求的实时 CPU | 2000 ms、604 ms |
| 3 次失败请求的实时结果 | 均为 `outcome=exceededCpu`，异常为 `Worker exceeded CPU time limit.` |
| 失败时记录的 CPU | 63 ms、10 ms、10 ms |
| hash-wasm 动态加载 | 422，仍禁止 WebAssembly.compile |
| workers.dev 同 Worker 对照 | 启用状态已确认，访问返回 500 / 1101 |
| 资源清理 | 本次域名绑定和 Worker 均删除并延迟复核 |

此处可以确认 CPU 超限，不再只凭 1102 推测 CPU 或内存。免费档标称每请求 10 ms CPU，偶尔完成长请求不代表有可依赖的额度。本次纯 JavaScript 候选不满足免费档采用条件；参数保持 32 MiB、3 次迭代、并行度 1。

CPU 数值来自 Cloudflare 实时执行记录，不是外部请求墙钟。Analytics 查询窗口已扩展到覆盖整秒，本轮 GraphQL 仍未返回聚合行，不以空数据推断没有执行。完整本机记录为 `.cache/feasibility/cloudflare/custom-domain-report.json`。

`npm run verify:cloudflare:route` 使用全新临时名称复现最小路由检查。本地产物先在 workerd 验证到期、HTTP 方法和路由；云端没有就绪时以非零状态退出，报告记录失败。测试结束按每次独立标签确认归属，再删除 Worker，延迟多次复核控制面状态。

## 远程预览 KDF

使用官方 Wrangler 的远程开发预览运行实际云端探针，回环监听仅绑定 `127.0.0.1`。远程预览与正式入口的执行路径不同，本节不能替代正式 CPU、容量及免费档验收。

| 检查 | 实际结果 |
| --- | --- |
| 未授权、HTTP 方法、未知路由 | 分别 401、405、404 |
| RFC 9106 第 5.3 节 | 200，输出符合标准向量 |
| 候选参数 | Argon2id、32 MiB、3 次迭代、并行度 1、32 字节输出 |
| 3 次顺序请求 | 2 次 200 且输出一致，1 次 HTTP 503 / 1102 |
| 2 个并发请求 | 1 次 200 且输出一致，1 次 HTTP 503 / 1102 |
| hash-wasm 4.12.0 | 422，动态 WebAssembly.compile 被运行时禁止 |
| 资源清理 | 停止预览并实际核对回环端口不再监听 |

5 次候选 KDF 请求中，3 次完成、2 次触发资源限制。它证明纯 JavaScript 实现可在真实预览运行时执行，当前候选的可靠性尚不满足采用条件。请求墙钟时间包含代理、网络及运行时间，不能当作计费 CPU；没有记录到准确 CPU 数值，也不将 1102 进一步猜测为确定的 CPU 或内存归因。

复现使用 `npm run verify:cloudflare:preview`。报告位于 `.cache/feasibility/cloudflare/preview/report.json`；正式探针为 `npm run verify:cloudflare`，报告位于 `.cache/feasibility/cloudflare/report.json`。配置、日志和生成物均被 Git 忽略。

## 静态 WASM

固定 hash-wasm 4.12.0 与发布源码 SHA256，从包内提取原始 Argon2 和 BLAKE2b WASM，不修改二进制或算法。构建时只替换模块缓存与动态编译回退，改为两个静态导入；未登记模块直接拒绝，不替换全局 WebAssembly。版本、源码或预期加载片段变化时停止构建。打包产物保留 MIT 许可及相关 C 实现声明。

`npm run verify:worker:static-wasm` 在本地 workerd 通过 5 类交叉校验：32 MiB 服务端参数、Unicode 密码、变化盐、64 MiB 客户端参数及含 secret 的双通道输入。输出同时符合 Node 原生 argon2Sync、未适配的 hash-wasm 和 noble；不同盐的并发请求也一致。32 MiB 基础输入本地请求墙钟约 63 ms，不能作为正式 CPU 资格。完整 RFC 9106 第 5.3 节仍通过 noble 验证，hash-wasm 的公开 API 不提供 associated data，未冒充完整 RFC 用例。

### 自有域名正式执行

上传同一 JavaScript 产物和两个 `application/wasm` 模块，候选参数保持 32 MiB、3 次迭代、并行度 1。首次 3 次顺序和 2 个并发请求全部返回 200 且输出一致，但日志未按请求标识关联，Analytics 未返回聚合行，不能将这一轮当作免费档验收通过。

复测曾在方法检查返回 1104 / Script not found，该轮尚未执行候选 KDF，已单独保留失败记录并清理。随后采用全新 Worker 名称，并要求自有入口连续 6 次符合预期才开始测量，避免以单次就绪响应忽略传播状态。

新名称复测的鉴权、方法、未知路由及 RFC 检查均通过，5 次候选请求再次全部 200、输出一致。该独立探针的实时日志记录到 5 条高开销执行：345、330、305、208、275 ms，均为 `outcome=ok`；另有一条 23 ms 记录及入口探测的小开销记录。请求标识未成功关联，不能把这 5 个 CPU 数字逐一分配给顺序或并发标签；高开销记录数量与本次 5 次候选调用一致。完整证据留在 `.cache/feasibility/cloudflare/custom-domain-static-wasm-report.json`，`correlatedCandidateCpuCount` 明确为 0，不伪称逐请求测量已通过。

静态加载已证明兼容和输出正确，本轮执行也已观察到远超 10 ms 的 CPU 开销，不能依据 5 次成功承诺免费档可靠性。Cloudflare 官方说明 isolate 对偶尔超过限额有一定弹性，持续超限仍会被终止。当前不按免费档采用；付费档的真实并发、CPU 限制及完整认证接口还未验证。

所有本轮临时 Worker 及域名绑定已删除并延迟复核。托管证书保持独立生命周期记录；没有开通付费订阅，也没有修改已有 DNS 记录。

## 当前部署路线

用户已明确保持免费部署和自定义账号密码，选择优先验证 Workers 加 SQLite Durable Objects，不降低 Argon2id 强度。普通 Worker 内执行 KDF 的负面结果不能用于否定 DO：后者有独立的执行限制和免费 SQLite 后端。具体进度及证据见 [DO 认证验证](durable-object.md)。只有该路线验证不通过后才评估 Firebase，暂不考虑 Supabase；本轮不启用付费服务或把认证服务迁到 NAS。

当前官方文档仍将 `node:crypto` 的 argon2 和 argon2Sync 列为不支持，不能以 Node.js 本机支持推断 Workers 可用。

依据：[Workers 错误与异常](https://developers.cloudflare.com/workers/observability/errors/)、[Workers 定价和 CPU 限额](https://developers.cloudflare.com/workers/platform/pricing/)、[GraphQL Workers 指标](https://developers.cloudflare.com/analytics/graphql-api/tutorials/querying-workers-metrics/)、[Node crypto 兼容范围](https://developers.cloudflare.com/workers/runtime-apis/nodejs/crypto/)、[静态 WASM 导入](https://developers.cloudflare.com/workers/runtime-apis/webassembly/javascript/)。
