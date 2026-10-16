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

本地候选密码校验请求墙钟约 55–63 ms；这是本地请求耗时，不能作为 Cloudflare CPU 资格。完整报告在 `.cache/feasibility/static-wasm/durable-local-report.json`。

## 云端验证计划

运行 `npm run verify:cloudflare:durable`，使用已有忽略配置和授权测试子域名：

1. 确认账号没有 Workers 付费订阅，不改动账单；创建全新名称、独立归属标签的 Worker 和 SQLite 类迁移。
2. 按已有域名冲突保护流程绑定临时入口，连续就绪后运行同一检查集。
3. 日志仅输出测试标签、操作及是否执行哈希，通过日志标记关联 DO 调用的 CPU；不记录请求正文、密码或 Authorization。
4. 结束后删除域名绑定，通过删除类迁移移除本轮 DO 命名空间及其测试存储，再删除 Worker 并延迟复核。托管证书另记生命周期。

云端真实执行、CPU、免费资格及资源清理尚待运行，不据此标记阶段 1 完成。

依据：[DO 免费额度](https://developers.cloudflare.com/durable-objects/platform/pricing/)、[DO 执行限制](https://developers.cloudflare.com/durable-objects/platform/limits/)、[对象状态与重启](https://developers.cloudflare.com/durable-objects/api/state/)、[类迁移](https://developers.cloudflare.com/durable-objects/reference/durable-objects-migrations/)。
