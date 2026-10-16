# AuthBox 可行性验证进度

验证日期：2026年10月16日。已完成项目的结果来自实际执行；待验证项目保持未完成，不能据此开始依赖其结论的正式实现。

| 项目 | 状态 | 证据与下一步 |
| --- | --- | --- |
| README 精简 | 已推送 | 删除作者、版权及上游详细段落，保留标准 LICENSE |
| 本地 workerd Argon2id | 已验证兼容性 | [运行时结果](worker.md)，另见 [真实云端对照](cloudflare.md) |
| Android AES GCM | 真机通过 | [PHB110 结果](android.md)，Keystore 留待应用工程验证 |
| Android Argon2id | 真机通过 | [64 MiB KDF 结果](android-kdf.md)，与浏览器结果一致；应用内存压力待验证 |
| 浏览器加密和 KDF | Chrome 页面环境通过 | [浏览器结果](browser.md)，MV3 存储及生命周期留待扩展工程验证 |
| Google 身份登录 | 令牌校验探针通过 | [Google 结果](google.md)，真实授权及远程公钥刷新待验证 |
| NAS 环境 | 只读检查通过 | Linux x86_64、Python 3.12.3、OpenSSL 3.0.13、Docker 可用 |
| NAS 主动 HTTPS 拉取 | 真实 NAS 样本拉取通过 | [NAS 结果](nas.md)，Cloudflare 鉴权备份及加密恢复待验证 |
| Cloudflare 正式入口 | 自有域名最小探针通过 | [电脑和 NAS HTTPS 对照](cloudflare.md)，workers.dev 内部异常仍未定位 |
| Cloudflare 正式 KDF | 当前不按免费档采用 | [纯 JS 明确超限，静态 WASM 输出正确但观察到高 CPU](cloudflare.md)，部署预算待决定 |
| 静态 WASM 加载 | 本地及自有域名通过兼容与输出校验 | 5 类本地交叉校验、正式两轮各 5 次正确输出；CPU 逐请求标识关联未通过 |
| 免费 SQLite Durable Objects | 本地通过，云端待验证 | [自定义密码、26 次请求、SQLite 重启持久化](durable-object.md)；保持现有 Argon2id 参数 |
| Cloudflare 远程预览 KDF | 兼容性已执行，可靠性未通过 | [5 次请求有 2 次 1102](cloudflare.md)，不能据此认定免费档可用 |

复现命令与环境变量见 [探针说明](../../tools/feasibility/README.md)。本地生成物和详细报告由 `.gitignore` 排除；公开记录不含设备序列号、内网地址或凭据。
