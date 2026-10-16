# 技术可行性验证

这些程序使用公开的合成测试数据，不接收真实密码或验证密钥，不是生产登录或同步服务。

## 环境

使用 Node.js 24 或更新版本，先执行 `npm ci`。依赖版本由根目录锁文件固定。

## Workers Argon2id

运行 `npm run verify:worker`，在本机 Miniflare 的 workerd 中验证：

- RFC 9106 第 5.3 节标准向量。
- 32 MiB、3 次迭代、并行度 1 的候选服务端参数。
- JavaScript 与 WebAssembly 实现的派生结果及运行时兼容性。
- 探针访问凭据、HTTP 方法和固定路由限制。

输出写入被 Git 忽略的 `.cache/feasibility/worker/report.json`。探针只允许内置固定参数，凭据每次随机生成，不支持真实账号数据。

记录的耗时由外部 Node 进程测量，属于本地请求墙钟时间。它不是 Cloudflare 的计费 CPU 时间，不能用来认定免费档可用。云端资格需要真实部署后的 CPU 指标、并发和资源限制验证。

## 依赖许可

本阶段候选库 `@noble/hashes`、`hash-wasm`、`jose`、`esbuild`、`miniflare` 使用 MIT，`playwright-core` 使用 Apache-2.0，`wrangler` 使用 MIT 或 Apache-2.0。引入运行客户端或发布打包依赖时，另行携带对应许可声明。

云端实时日志传输固定使用 MIT 许可的 `ws` 8.21.0，初始化帧与已安装 Wrangler 的 trace-v1 协议一致。报告只提取错误和资源字段，不保存原始请求头或日志帧。

Android KDF 探针使用 Bouncy Castle Licence，按官方说明与 MIT 同样理解。探针保留 JAR 内的版权和许可于 [许可文件](licenses/bouncycastle.txt)，不修改项目标准 AGPL 许可。

Miniflare 固定为与 Wrangler 4.151.0 自身依赖相同的 5.20261012.0-beta，使用其导出的官方 V4 配置转换接口。该版本属于本地测试工具，不代表云端服务已验证。

## Cloudflare 云端与对照

按 [专用 Token 配置](../../docs/cloudflare-feasibility-setup.md) 准备被忽略的 `.env.cloudflare` 后运行：

- `npm run verify:cloudflare`：受随机密钥保护的固定 KDF 探针，正式入口就绪后才执行哈希和 Analytics 查询。
- `npm run verify:cloudflare:route`：全新临时名称，仅返回公开常量，用于排除加密库影响。
- `npm run verify:cloudflare:preview`：通过官方 Wrangler 远程开发预览运行相同 KDF，停止后核对回环监听已关闭。
- `npm run verify:cloudflare:domain-route` 和 `npm run verify:cloudflare:domain`：按本机环境变量中的授权 Zone 和测试子域名创建自定义入口，分别验证最小响应和固定 KDF。
- `npm run verify:cloudflare:domain-safety`：本地检查 8 类域名变更计划，只允许指定新增域名，不访问云端。
- `npm run verify:worker:static-wasm`：固定上游版本和源码摘要，在本地 workerd 校验静态 WASM；5 类输入与 Node 原生、原库和 noble 交叉比对，并检查不同盐的并发请求。
- `npm run verify:worker:durable`：本地 SQLite Durable Object，检查自定义合成密码、同强度 Argon2id、不同盐、错误密码、并发及运行时重启持久化。
- `npm run verify:cloudflare:durable`：仅在免费账号创建独立 SQLite DO 及临时 Worker，运行同一账号检查集，采集带测试标签的对象执行 CPU，再删除本次类命名空间与 Worker。
- `npm run verify:cloudflare:domain-static-wasm`：同样的授权域名流程，上传两个预编译 WASM 模块，测量 3 次顺序和 2 个并发候选请求。实时日志尝试按随机路径标识关联，只保存标签、已知路由、结果与 CPU 字段；当前正式环境尚未关联成功，报告同时保留未关联事件及关联数量，不按回调顺序强行匹配。

部署程序先在本地 workerd 验证同一打包产物，等待云端控制面传播，结束后核对独立归属标签并删除，只操作本次创建的临时资源。报告位于 `.cache/feasibility/cloudflare/`。临时探针 30 分钟失效；路由对照的公开常量不执行密码哈希。

每轮正式探针采用全新名称；自有入口必须连续 6 次响应符合预期。入口传播错误与候选资源失败分别记录。CPU 是否可用和候选 CPU 是否逐请求关联是不同字段，不能用入口的 0 ms 记录冒充 KDF 开销。

没有取得正式 CPU 数据或就绪失败时，报告明确保留未完成状态。已知负面结果见 [云端记录](../../docs/feasibility/cloudflare.md)，不能把探针程序执行结束或预览 HTTP 200 当作免费档验收通过。

自定义域名程序先进行服务端冲突预检查，不覆盖已有记录或绑定；结束后移除本次域名绑定及 Worker。对应托管证书的生命周期另行记录，不能假定它随绑定自动删除。报告采用 `custom-domain-` 前缀，实际测试域名及标识留在被忽略的本机配置和报告中。

静态加载构建器只改 hash-wasm 的模块加载，不改算法或全局对象；未知模块、源码摘要和版本变化均拒绝。WASM、适配源码、模块摘要及第三方许可生成于 `.cache/feasibility/static-wasm/`，完整许可和 C 文件头同时保留在部署脚本的许可块。BLAKE2 参考实现采用其允许的 CC0 选项。该适配仅是可行性探针，尚非正式认证模块。

DO 命令要求 Node 24.13.0 或以上，使用本机已有代理环境变量。密码计算保持 32 MiB、3 轮、并行度 1；只接收有界合成测试输入，读出哈希的检查接口不能用于正式账号。密码检查在对象内串行执行，每个等待请求持有自己的 Promise，队列最多保留 8 个等待者。

DO 可行性验收检查完整请求集、真实免费账号和 SQLite 类型，以及至少 3 条同参数 CPU 样本（包含成功密码校验）。实时日志覆盖数与缺失标签单独报告，不要求实时流完整才承认功能结果，也不将未到达日志填成 0 ms。本轮结果见 [DO 记录](../../docs/feasibility/durable-object.md)，生产容量验收尚未完成。若测试中断，使用同一配置运行 `npm run verify:cloudflare:durable -- --cleanup`，程序只对本地报告中归属匹配的 Worker 执行类删除和清理。

## Android 真机互通

运行 `npm run verify:android` 前配置 `AUTHBOX_ANDROID_SERIAL`、`JAVA_HOME` 和 `ANDROID_HOME` 或 `AUTHBOX_ANDROID_SDK`。默认构建工具为 36.1.0、编译平台为 android-35，可用 `AUTHBOX_ANDROID_BUILD_TOOLS` 和 `AUTHBOX_ANDROID_PLATFORM` 覆盖。

脚本通过 javac 和 D8 编译临时 JCA 探针，只向明确选择的设备推送独立 dex 文件，经 app_process 运行后删除并核对不存在。它验证 AES 256 GCM 的加解密、UTF8、认证标签及篡改拒绝，不验证应用生命周期、Keystore 或手机 Argon2id。生成物和报告保存在 `.cache/feasibility/android/`。

同样环境下执行 `npm run verify:android-kdf`，下载 SHA256 固定的 `org.bouncycastle:bcprov-jdk15to18:1.86`，编译独立探针。在同一设备验证 Argon2id 标准向量、64 MiB 候选参数、UTF8 密码及盐变化；与浏览器所用 hash-wasm 比对，再删除设备临时文件。结果在 `.cache/feasibility/android-kdf/`。不注册系统加密 Provider，也不代表 Keystore 或正式应用内存压力验证。

## 浏览器互通和派生

安装 Google Chrome 后运行 `npm run verify:browser`。脚本使用 Playwright 创建隔离的 headless Chrome，启动仅绑定 127.0.0.1 的临时服务，在限制脚本来源且只允许 WASM 编译的 CSP 下运行，结束后关闭浏览器和服务。

使用与 Android 相同的固定公开向量验证双向 AES GCM 和五类篡改拒绝，并检查 Argon2id 标准向量及 64 MiB、3 次迭代、并行度 1 的浏览器候选参数。它不加载日常 Chrome 资料，也不代表 MV3 安装、权限、存储及锁定行为已通过。结果写入 `.cache/feasibility/browser/report.json`。

## NAS 环境

配置 `AUTHBOX_NAS_SSH_TARGET` 和 `AUTHBOX_NAS_SSH_KEY` 后运行 `npm run inspect:nas`。只读脚本检查操作系统、Python HTTPS 和 Docker，不安装软件。SSH 保留严格主机密钥校验，私钥不进入程序输出。

运行 `npm run verify:nas`，在同一 NAS 环境通过 HTTPS 下载公开固定样本，校验 SHA256，再验证错误摘要不替换旧文件、HTTP 被拒绝和临时文件清理。脚本从标准输入执行，不在 NAS 上安装服务或创建定时任务。

该样本拉取不包含 Cloudflare 备份鉴权和加密恢复；两者另行验证。Cloudflare 令牌配置见 [测试环境说明](../../docs/cloudflare-feasibility-setup.md)。

## Google 身份令牌

执行 `npm run verify:google`，在 Node 和 workerd 检查合成 RS256 JWT 的签名与身份约束。默认公钥来源固定为 Google，测试明确注入本地公钥。脚本不会保存测试私钥或原始令牌，不依赖真实 Google 客户端。

22 类用例通过只证明校验逻辑及运行时兼容，不代表 OAuth 回调、真实 Google JWKS 刷新、一次性 nonce 消费、账号绑定或会话已完成。结果保存在 `.cache/feasibility/google/report.json`。
