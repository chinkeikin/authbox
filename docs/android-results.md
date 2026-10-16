# Android 验证记录

## 2026-10-16：工程与 Keystore 基线

- 设备：PHB110，ADB `dc1f38cd`，Android API 35；没有操作另一台 KW1401。
- 新建 Kotlin / Compose 工程，包名 `com.authbox.android`，最低 API 26，目标 API 35；开发 APK 与独立 instrumentation APK 构建、安装成功。
- `KeystoreTest` 在应用 UID 下运行两次，每次 2 项通过。第二轮前执行 `am force-stop com.authbox.android`，重新启动进程后恢复同一密文和公钥。
- 验证 AES-256-GCM 随机 nonce、正确解密、篡改拒绝、文件 AAD 隔离；P-256 私钥 encoded 为空，签名转换为协议要求的 64 字节 P1363 后可验证。
- 不宣称 StrongBox；没有生物识别/系统凭据快捷解锁测试。本步骤只验证 Keystore 应用密钥与进程重建，不能代表整机重启、系统升级或设备丢失恢复演练。
- 调试数据均为合成字符串，未导入真实 OTP；应用安装需要手机系统确认，已由用户完成。
- 工程基线提交时 Lint 首次依赖下载仍在进行，登录、保险库密码学和同步联调继续下一步骤；没有部署生产服务或改变 Workers 套餐。

## Android / Node 密码学互通

- PHB110 的 `VaultTest` 3 项 instrumentation 测试通过，耗时 5.914 秒。实际使用 Bouncy Castle 1.86 Argon2id，保险库参数固定 64 MiB / t=3 / p=1；服务端登录仍为 32 MiB / t=3 / p=1。
- Node 生成当前业务 schema 的账号、分组、标签、设置快照，Android 验证完整清单、解密全部记录，用密码与恢复密钥分别恢复同一个 VMK；RFC TOTP 59 秒向量得到 `94287082`。
- Android 修改分组生成 revision 2；Node 独立恢复并验证业务引用，未修改账号的密文与 revision 保持原样。Android 自行生成的新保险库也由 Node 用恢复密钥成功解密。
- 缺少实体但重算外层摘要、AAD 改动、错误密码、低强度 KDF、未知版本、缺失分组引用、重复 JSON 字段、错误 Unicode 均被拒绝。JSON 编码额外核对中文、emoji、组合字符、U+2028/U+2029 与控制字符的跨端一致性。
- 验证命令：`node tools/android/prepare-fixtures.mjs`，构建并安装测试 APK 后设置 `AUTHBOX_ANDROID_SERIAL` 和 `ANDROID_HOME`，运行 `node tools/android/check-protocol.mjs`。合成明文与结果保存在忽略的 build/.cache 路径，测试输出文件核对后从手机删除。

## Workers Free + SQLite Durable Objects 联调

- PHB110 实际 APK 通过 HTTPS 连接临时自定义域名服务。`CloudTest` 分注册同步、进程重建、冲突三轮运行，每轮 2 项测试均通过，另有 1 项合成数据与 Keystore 别名清理测试通过。
- Android 自行创建保险库、恢复密钥和 8 个账号恢复码；未确认保存恢复材料时不能提交注册。邀请码注册得到首台 manager，设备签名由真实 AndroidKeyStore 私钥完成，服务器成功验证协议 P1363 签名。
- 手机上传 4 类实体的 revision 2；普通登录仍为 reader，客户端与服务端分别拒绝写入。刷新会话轮换令牌并加密保存。错误密码被拒绝，已授权设备用原私钥和正确密码重新登录恢复 manager。
- 本地收藏修改先加密持久化为待上传操作。强制停止后，新进程默认锁定，解锁并提交原操作到 revision 3；Node 独立账号登录下载同一密文，并用 Android 生成的恢复密钥验证全部业务数据。
- Node 生成一个并发 revision 4，由 Android 签名提交；原本基于 revision 3 的修改得到 409，仍保留待上传内容和本地分组编辑，下载不能覆盖它。此处验证过期版本冲突；冲突合并界面未实现。
- 返回的签名挑战若替换 origin、挑战 ID、账号、设备、会话、操作、请求摘要或有效期，客户端拒绝签名。
- 开始与结束均确认 Workers 付费订阅数为 0；SQLite namespace、临时 Worker 和域名绑定删除已核对。托管证书属于独立生命周期，没有宣称证书资源已删除。没有使用 D1、R2、付费 SDK 或降低 KDF 参数。
- 第一轮 Windows 命令封装错误发生在测试安装前，临时云资源已清理；修复封装并独立验证后，重新创建环境完成上述测试。清理异常现在不会覆盖原始测试失败原因。
- 运行：设置 `JAVA_HOME`、`ANDROID_HOME`、`AUTHBOX_ANDROID_SERIAL`、`AUTHBOX_CF_TEST_ZONE`、`AUTHBOX_CF_TEST_HOST`，执行 `node --use-env-proxy --env-file=.env.cloudflare tools/api/run-cloud.mjs --android`。测试 gate 与配置只在独立测试 APK 中，生产 API bundle 不含测试接口。原始报告在忽略的 `.cache/api/cloud/android-service-report.json`。
- Android Lint 通过；已有回归的 46 项协议、2 项 HTTP、157 项本地接口与 9 项正式入口检查全部通过。
