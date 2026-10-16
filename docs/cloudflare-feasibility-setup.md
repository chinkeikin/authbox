# Cloudflare 可行性验证配置

本阶段只准备独立的 `authbox-feasibility` 测试 Worker，不部署业务服务或连接真实保险库。测试探针使用随机访问凭据及固定合成数据。

最小路由对照使用 `authbox-feasibility-route-` 前缀的临时名称，仅返回公开常量。真实结果见 [云端记录](feasibility/cloudflare.md)。

## 本机凭据

复制根目录 `.env.cloudflare.example` 为 `.env.cloudflare`，在本机填写 `CLOUDFLARE_ACCOUNT_ID` 和专用 `CLOUDFLARE_API_TOKEN`。实际配置文件已被 `.gitignore` 忽略，禁止将其加入 Git、聊天、截图或日志。

使用 API Token，不使用 Global API Key。资源范围限定为本次测试账号。按 Cloudflare 当前控制台的权限名称配置：

| 权限 | 用途 |
| --- | --- |
| Workers Scripts Edit 或对应 Workers Editor | 创建更新测试 Worker 及测试密钥绑定 |
| Account Settings Read | 读取 Worker 账号配置 |
| Account Analytics Read | 读取真实 CPU 及请求指标 |
| Workers Tail Read | 验证运行结果与资源限制错误 |

此探针不需要 DNS、域名路由、KV、D1、R2 或账单编辑权限。若控制台采用新版按 Worker 分配的角色，按现有测试 Worker 限定权限；创建新 Worker 所需范围以实际 API 校验为准，不自动扩大 Token 权限。

## 可选自有域名对照

仅在域名所有者已授权后，在本机设置 `AUTHBOX_CF_TEST_ZONE=example.com` 和 `AUTHBOX_CF_TEST_HOST=authbox-test.example.com`。执行 `npm run verify:cloudflare:domain-route` 检查最小响应；`npm run verify:cloudflare:domain` 检查固定 KDF。

`npm run verify:worker:static-wasm` 先在本地检查静态导入与跨实现输出；`npm run verify:cloudflare:domain-static-wasm` 使用同一临时域名流程检查预编译 WASM 的正式执行。每轮 Worker 使用全新名称，入口连续就绪后才执行候选计算。

`npm run verify:cloudflare:durable` 验证免费 SQLite Durable Objects 密码存储及校验，要求账号没有 Workers 付费订阅。沿用现有令牌即可创建 SQLite 类迁移、查询命名空间及删除本轮测试类，不增加账单权限。程序结束时复核测试命名空间和 Worker 已移除；失败遗留时，在同一配置下运行 `npm run verify:cloudflare:durable -- --cleanup`，按本地报告的独立标签核对归属再重试清理。

该命令要求 Node 24.13.0 或以上，启用 Node 官方 `--use-env-proxy` 来使用本机已有代理环境变量，不修改系统代理。域名就绪检查有 120 秒总截止时间；直接连接超时不等同于密码哈希失败。

程序使用 Workers 自定义域名接口自动配置目标记录与证书。要求令牌能读取对应 Zone，绑定权限由 Workers Scripts Write 提供；直接 DNS 读取权限不作为前提。先取得服务端变更计划，无冲突、无其他更新删除且只新增指定子域名时才执行；覆盖已有 DNS、已有源站及绑定范围的选项全部关闭。

结束后只删除本次归属的域名绑定和 Worker，并保存复核结果。Cloudflare 不保证随绑定删除托管证书，证书标识仅保存本机，不能把 Worker 已删除等同于证书已删除。依据：[自定义域名](https://developers.cloudflare.com/workers/configuration/routing/custom-domains/)。

## 结果边界

Miniflare 本地成功不代表免费档 CPU 和内存限制通过。只有实际请求执行与 Cloudflare 指标或限制错误，才能决定云端资格。缺少 Token 时不把这一项标记完成，也不默认开通付费套餐。

执行 `npm run verify:cloudflare` 自动上传受随机密钥保护的 KDF 探针，30 分钟失效，并在结束后按独立归属标签删除 Worker 和日志会话；若同名 Worker 已存在则拒绝覆盖。`npm run verify:cloudflare:route` 使用全新名称，仅检验无密码学依赖的最小响应；它不接受或返回真实账号数据。

`npm run verify:cloudflare:preview` 通过 Wrangler 的远程开发预览验证运行时，结束后停止本机监听。该路径不代表正式入口，也不能用其请求墙钟时间证明免费档资格。

依据：[Workers 权限](https://developers.cloudflare.com/workers/authorization/workers/)、[Token 权限](https://developers.cloudflare.com/fundamentals/api/reference/permissions/)、[Workers CPU 分析](https://developers.cloudflare.com/workers/observability/dev-tools/cpu-usage/)。
