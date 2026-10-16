# Android AES GCM 真机验证结果

2026年10月16日在用户选择的 PHB110 手机上执行，系统 API level 35。探针使用独立 dex 和 app_process 的 JCA，不安装 APK，不访问已有应用数据。

| 检查 | 结果 |
| --- | --- |
| Node WebCrypto 加密后 Android 解密 | 通过，UTF8 中文内容一致 |
| Android 加密后 Node WebCrypto 解密 | 通过 |
| 固定公开 key nonce AAD 对应的密文及 128 位标签 | 两端完全相同 |
| 改动密文、标签、nonce、AAD、key | 五类均以认证失败拒绝，返回明确失败状态 |
| 临时文件清理 | finally 删除设备上的探针 dex，并确认文件已不存在 |

复现使用 `npm run verify:android`，环境变量和工具要求见 [探针说明](../../tools/feasibility/README.md)。测试使用的固定 key 和 nonce 仅用于公开向量互通，不可用于真实加密；生产加密必须使用安全随机密钥及不重复 nonce。

这一结果证明 Android JCA 与 WebCrypto 的 AES GCM 字节格式可以互通，尚不证明 Android Keystore、应用锁定、Room 存储、手机 Argon2id 或正式备份格式已完成。后续在应用环境中分别验证。
