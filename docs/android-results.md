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
