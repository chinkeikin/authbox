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

Android KDF 探针使用 Bouncy Castle Licence，按官方说明与 MIT 同样理解。探针保留 JAR 内的版权和许可于 [许可文件](licenses/bouncycastle.txt)，不修改项目标准 AGPL 许可。

Miniflare 固定为与 Wrangler 4.151.0 自身依赖相同的 5.20261012.0-beta，使用其导出的官方 V4 配置转换接口。该版本属于本地测试工具，不代表云端服务已验证。

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
