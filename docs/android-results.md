# Android 验证记录

## 2026-10-16：工程与 Keystore 基线

- 设备：PHB110，ADB `dc1f38cd`，Android API 35；没有操作另一台 KW1401。
- 新建 Kotlin / Compose 工程，包名 `com.authbox.android`，最低 API 26，目标 API 35；开发 APK 与独立 instrumentation APK 构建、安装成功。
- `KeystoreTest` 在应用 UID 下运行两次，每次 2 项通过。第二轮前执行 `am force-stop com.authbox.android`，重新启动进程后恢复同一密文和公钥。
- 验证 AES-256-GCM 随机 nonce、正确解密、篡改拒绝、文件 AAD 隔离；P-256 私钥 encoded 为空，签名转换为协议要求的 64 字节 P1363 后可验证。
- 不宣称 StrongBox；没有生物识别/系统凭据快捷解锁测试。本步骤只验证 Keystore 应用密钥与进程重建，不能代表整机重启、系统升级或设备丢失恢复演练。
- 调试数据均为合成字符串，未导入真实 OTP；应用安装需要手机系统确认，已由用户完成。
- 工程基线提交时 Lint 首次依赖下载仍在进行，登录、保险库密码学和同步联调继续下一步骤；没有部署生产服务或改变 Workers 套餐。
