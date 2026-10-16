# 免费 SQLite Durable Objects 认证验证

验证日期：2026年10月16日。用户要求保持免费部署、自定义用户名密码、不降低密码哈希强度；优先验证本方案，失败后才考虑 Firebase，暂不考虑 Supabase。

## 验证范围

普通 Worker 只进行临时探针鉴权和转发，SQLite Durable Object 执行 Argon2id。保持服务端候选参数 32 MiB、3 次迭代、并行度 1、32 字节输出，复用固定版本 hash-wasm 的静态 WASM 加载方式，不修改密码学实现。

使用客户端提交的公开合成用户名与密码，创建 3 个测试账号，包含中文、Emoji 和组合字符。每个账号生成独立随机 16 字节盐，SQLite 只保存 Argon2id PHC 记录及参数。探针的访问 Token 仅隔离临时测试，不能误认为给最终用户生成的登录口令。

本阶段只验证密码存储、密码校验及运行成本，不签发正式登录会话，也未实现产品的注册邀请、限速、恢复码、设备授权或 Chrome 只读接口。检查数据的接口会返回合成密码的哈希，只允许临时测试使用，不能作为正式账号接口发布。

## 本地结果

`npm run verify:worker:durable` 在真实本地 workerd、SQLite Durable Object 中通过 26 次请求检查：

- 拒绝未授权、错误方法、未知路由、坏 JSON 和超限输入。
- RFC 9106 第 5.3 节向量由 noble 验证通过；候选账号 PHC 记录由 Node 原生 argon2Sync 独立复算一致。
- 自定义用户名密码、Unicode 密码、相同密码的不同随机盐均通过；重复账号拒绝。
- 正确密码通过，错误密码和不存在账号均拒绝；不存在账号也执行同强度 KDF。
- 同时提交 4 个混合正确和错误密码请求，结果正确。对象内有界队列将 KDF 串行执行，最大同时运行哈希数为 1，避免并发放大内存。
- 使用对象状态的 abort 主动丢弃运行时；重新进入的实例标识改变，SQLite 账号记录逐字节一致，正确密码仍能通过。
- 测试账号清空后查询为零，另行检查探针过期时拒绝入口及认证请求。

末轮本地候选密码校验请求墙钟约 61–70 ms；这是本地请求耗时，不能作为 Cloudflare CPU 资格。完整报告在 `.cache/feasibility/static-wasm/durable-local-report.json`。

## 云端结果

`npm run verify:cloudflare:durable` 已在用户的真实免费账号执行，末轮命令退出码为 0。[公开结果摘要](durable-object-results.json) 只保留参数、检查结果、HTTP 状态和 CPU 样本，不包含实际域名、账号标识、命名空间标识或凭据。

| 检查 | 实际结果 |
| --- | --- |
| 免费账号 | 测试前后 Workers 付费订阅数均为 0，没有修改账单 |
| SQLite 后端 | 控制面命名空间 `use_sqlite=true`，实际执行 SQL 建表、写入和读取 |
| 同一检查集 | 26 次请求均符合预期，含 3 个自定义合成账号、Unicode、拒绝错误和 4 个并发请求 |
| 密码哈希强度 | Argon2id，32 MiB、3 次迭代、并行度 1、32 字节；独立随机盐及 Node 原生复算一致 |
| 重启持久化 | 主动 abort 后实例标识改变，SQLite 记录一致，密码仍能校验 |
| 候选 CPU 样本 | 7 条，165、167、171、172、182、194、280 ms，均为 `outcome=ok` |
| CPU 覆盖 | 本轮 14 次候选哈希取得 7 条可独立关联记录，其余标签明确保留为缺失 |
| 本轮资源清理 | 测试账号清零，域名绑定删除，删除类迁移移除命名空间，再删除 Worker 并延迟复核 |
| 跨轮复核 | 额外只读检查本次 4 轮尝试的 Worker 和 SQLite 命名空间均不存在，测试域名无绑定，付费订阅仍为 0 |

日志只输出测试标签、操作及开始执行哈希的标记，通过标记与 Cloudflare 调用记录关联 CPU，不保存请求正文、密码或 Authorization。完整本机报告为 `.cache/feasibility/cloudflare/durable-object-report.json`。

最初的直接连接超时由访问路径造成：同一健康入口经本机现有代理返回 200，Node 直接连接超时。验证命令改用 Node 官方 `--use-env-proxy`，不修改系统网络；域名就绪增加 120 秒总截止时间，并验证按归属日志重试清理。两次功能通过的尝试曾因要求实时日志 14/14 完整而退出非零；对应原报告仍在本机保留。最后按下面的可行性边界验收，未把缺失 CPU 记录伪称已测。

## 验收边界

本项免费认证运行可行性通过：要求功能检查全部通过、真实免费账号与 SQLite 后端确认、至少 3 条可关联候选 CPU 且包含成功密码校验样本、已测调用均未超 DO 默认 CPU 限额，以及临时对象和 Worker 清理完成。CPU 的覆盖数、缺失标签独立记录。

本轮缺失的记录包括 4 个并发调用的 CPU，因此并发功能通过不等于完整并发 CPU 指标已采齐。实时流缺记录的原因尚未定位，不强行按回调顺序分配 CPU。这里证明个人自用认证计算可继续采用免费方案，不替代生产容量、完整内存指标及长期负载验收。

后续按免费 Workers 加 SQLite DO 推进，自定义密码和哈希强度不变；本轮不接入 Firebase 或 Supabase。正式账号、会话、恢复及权限接口保持未实现状态，阶段 1 的其他集成边界继续按进度表记录。

托管证书的生命周期独立于域名绑定；证书标识只保留本机报告，本项没有验证证书已删除。

依据：[DO 免费额度](https://developers.cloudflare.com/durable-objects/platform/pricing/)、[DO 执行限制](https://developers.cloudflare.com/durable-objects/platform/limits/)、[对象状态与重启](https://developers.cloudflare.com/durable-objects/api/state/)、[类迁移](https://developers.cloudflare.com/durable-objects/reference/durable-objects-migrations/)。
