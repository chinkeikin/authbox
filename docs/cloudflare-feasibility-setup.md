# Cloudflare 可行性验证配置

本阶段只准备独立的 `authbox-feasibility` 测试 Worker，不部署业务服务或连接真实保险库。测试探针使用随机访问凭据及固定合成数据。

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

## 结果边界

Miniflare 本地成功不代表免费档 CPU 和内存限制通过。只有实际请求执行与 Cloudflare 指标或限制错误，才能决定云端资格。缺少 Token 时不把这一项标记完成，也不默认开通付费套餐。

依据：[Workers 权限](https://developers.cloudflare.com/workers/authorization/workers/)、[Token 权限](https://developers.cloudflare.com/fundamentals/api/reference/permissions/)、[Workers CPU 分析](https://developers.cloudflare.com/workers/observability/dev-tools/cpu-usage/)。
