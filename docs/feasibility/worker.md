# Workers Argon2id 本地可行性结果

## 结果

2026年10月16日在 Windows、Node.js 24.13.0 和真实 workerd 本地运行时执行，兼容日期为 2026-10-16。使用 `npm run verify:worker`，完整结果生成于 `.cache/feasibility/worker/report.json`。

| 检查 | 结果 |
| --- | --- |
| RFC 9106 第 5.3 节 Argon2id 向量 | Node 与 workerd 均通过 |
| 候选服务端参数 | 32 MiB、3 次迭代、并行度 1、32 字节输出 |
| 跨实现结果 | Node hash-wasm、Node noble、workerd noble 完全相同 |
| noble 本地请求墙钟时间 | 三次分别 273.49、278.33、272.86 ms |
| Node hash-wasm 墙钟时间 | 本次单样本 58.33 ms，不作稳定性能结论 |
| workerd hash-wasm | 失败，WebAssembly.compile 的代码生成被运行时禁止 |
| 探针访问检查 | 未授权 401、方法不符 405、未知路由 404 |
| 依赖安全检查 | 固定版本后 npm audit 当次报告 0 项已知漏洞 |

## 技术决策

`hash-wasm` 当前导入方式不能直接作为 Workers 服务端实现。浏览器端可独立评估该库；如果服务端选择 WASM，需要另行验证预编译模块适配，不能绕过运行时限制或替换全局 WebAssembly 接口。

`@noble/hashes` 2.4.0 的纯 JavaScript Argon2id 是可执行的服务端候选，当前不作为最终生产选型。真实云端的 CPU、内存、冷启动和并发尚未验证，也未据此决定免费或付费方案。正式密码存储参数不得为免费额度降低。

本地 Miniflare 不执行云端免费档全部限制，上表墙钟时间不是 Cloudflare 计费 CPU 时间。标准小向量使用 RFC 指定的 32 KiB，候选认证参数另以 32 MiB 验证，两者用途不同。

依据：[RFC 9106](https://www.rfc-editor.org/rfc/rfc9106.html#section-5.3)、[Workers 本地开发](https://developers.cloudflare.com/workers/local-development/)、[Workers CPU 分析](https://developers.cloudflare.com/workers/observability/dev-tools/cpu-usage/)。
