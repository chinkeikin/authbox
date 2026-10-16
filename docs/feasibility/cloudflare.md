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

## 下一步

先取得能正常服务最小脚本的正式入口，再测真实 CPU、内存和并发。后续可评估静态导入预编译 WASM 或其他认证运行环境，但必须实际验证；不替换全局 WebAssembly、不为运行额度降低密码哈希强度，也不开通未经用户决定的付费方案。

当前官方文档仍将 `node:crypto` 的 argon2 和 argon2Sync 列为不支持，不能以 Node.js 本机支持推断 Workers 可用。

依据：[Workers 错误与异常](https://developers.cloudflare.com/workers/observability/errors/)、[GraphQL Workers 指标](https://developers.cloudflare.com/analytics/graphql-api/tutorials/querying-workers-metrics/)、[Node crypto 兼容范围](https://developers.cloudflare.com/workers/runtime-apis/nodejs/crypto/)、[静态 WASM 导入](https://developers.cloudflare.com/workers/runtime-apis/webassembly/javascript/)。
