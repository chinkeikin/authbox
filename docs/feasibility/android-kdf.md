# Android Argon2id 真机验证结果

2026年10月16日在 PHB110、Android API 35 上运行独立 dex 探针，使用 Bouncy Castle 低层 API。没有安装 APK，测试后删除设备临时文件并核对不存在。

| 检查 | 结果 |
| --- | --- |
| 固定依赖 | `org.bouncycastle:bcprov-jdk15to18:1.86`，下载后核对固定 SHA256 |
| RFC 9106 第 5.3 节 | 含 secret 和 additional data 的标准向量通过 |
| 保险库候选参数 | Argon2id v1.3、64 MiB、3 次迭代、并行度 1、32 字节输出 |
| 与浏览器实现互通 | 三次结果与 Chrome 所用的 hash-wasm 相同公开向量一致 |
| 派生耗时 | 同一进程预热一次后，三次约 241.52、224.70、226.65 ms |
| 中文及 emoji 密码 | 按明确 UTF8 字节输入，与 hash-wasm 输出相同 |
| 盐改变 | 结果改变，且两端输出仍一致 |
| 清理 | 设备上的探针文件已删除 |

复现命令为 `npm run verify:android-kdf`，环境同 [Android AES GCM 探针](android.md)。生成物、下载依赖和报告位于被 Git 忽略的 `.cache/feasibility/android-kdf/`，依赖不是正式应用选型。

这证明候选参数在该手机的 Android 运行时可执行，并与浏览器实现互通。耗时仅代表这一台手机的 app_process 测试，不代表低内存设备、正式应用线程调度、后台恢复、低电量或系统内存压力下的表现。Keystore、应用锁定及生命周期仍待 Android 工程验证。

依赖使用官方建议用于非多版本 Java 8 工具链的 jdk15to18 构件，许可证按其 JAR 中 `org.bouncycastle.LICENSE` 保留于 [许可文件](../../tools/feasibility/licenses/bouncycastle.txt)。依据：[Bouncy Castle 下载说明](https://www.bouncycastle.org/download/bouncy-castle-java/)、[许可说明](https://www.bouncycastle.org/licence.html)、[RFC 9106](https://www.rfc-editor.org/rfc/rfc9106.html#section-5.3)。
