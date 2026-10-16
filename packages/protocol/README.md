# AuthBox 跨端协议

本目录存放跨端格式和可复用的纯 ESM 模块，可由后续 TypeScript 工程打包使用。尚未完成账号服务、Android 应用或 Chrome 扩展。

## TOTP

`src/totp.mjs` 封装固定版本的 OTPAuth，不自行实现 HMAC 或截断算法。仅支持 SHA1、SHA256、SHA512，6/8 位，1–86400 秒周期，Unix 时间起点为 0。时间参数为整数毫秒，返回验证码、剩余毫秒及到期时间；验证码生成不访问网络。

`parseTotpUri` 仅接受 `otpauth://totp/`，默认 SHA1、6 位、30 秒。拒绝 HOTP、未知算法、重复或未知参数、issuer 冲突、空账号、控制字符和非法 Base32。标签只解码一次，展示端仍须按纯文本渲染。Base32 接受大小写及规范的可选填充，返回无填充大写；密钥范围为 10–128 字节，不自动修正含空格、缺位或多余尾比特的输入。手动录入的格式提示由客户端提供。

执行 `npm run test:protocol`，检查 RFC 6238 附录 B 的全部 18 个公开向量、6 位结果、非默认周期、2038 年之后和超过 32 位的计数器、时间边界及坏输入。Google 迁移二维码是另一种协议，尚未实现，不能当作普通 otpauth URI 导入。

依赖许可见根目录 `THIRD-PARTY-NOTICES.txt`；项目本身保持标准 AGPL-3.0-only。

## 加密与备份

`src/vault.mjs` 提供 AES-256-GCM 实体加密、64 MiB Argon2id 密码封装、独立 HKDF 恢复封装及恢复密钥校验编码。`src/backup.mjs` 提供自包含备份、摘要检查和完整认证后的离线恢复。完整规则、限制与后续服务端事务约束见 [协议 v1](../../docs/protocol-v1.md)。

`test/fixtures/v1.json` 是公开固定向量，含合成密码和测试密钥，不得用于真实加密。执行 `npm test` 运行协议测试；`npm run verify:protocol:browser` 和 `npm run verify:protocol:android` 验证真实运行环境。安装工程、Keystore、MV3 生命周期及账号恢复服务仍未完成。

## 业务、同步和权限

`business.mjs` 校验版本化业务字段和组/标签引用，`sync.mjs` 校验完整快照的版本变化和显式历史恢复，`permissions.mjs` 固定角色矩阵及 P-256 挑战签名字节，`account-recovery.mjs` 生成独立随机账号恢复码及其哈希。完整字段和 API/事务约束见 [同步权限协议](../../docs/sync-api-v1.md)。服务端必须自己重新校验并在 SQLite 事务中执行，不信任客户端已调用这些函数。

依据：[RFC 6238](https://www.rfc-editor.org/rfc/rfc6238.html)、[RFC 4648](https://www.rfc-editor.org/rfc/rfc4648.html)、[Google Key URI](https://github.com/google/google-authenticator/wiki/Key-Uri-Format)、[OTPAuth](https://github.com/hectorm/otpauth)。
