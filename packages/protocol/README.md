# AuthBox 跨端协议

本目录存放跨端格式和可复用的纯 ESM 模块，可由后续 TypeScript 工程打包使用。尚未完成账号服务、Android 应用或 Chrome 扩展。

## TOTP

`src/totp.mjs` 封装固定版本的 OTPAuth，不自行实现 HMAC 或截断算法。仅支持 SHA1、SHA256、SHA512，6/8 位，1–86400 秒周期，Unix 时间起点为 0。时间参数为整数毫秒，返回验证码、剩余毫秒及到期时间；验证码生成不访问网络。

`parseTotpUri` 仅接受 `otpauth://totp/`，默认 SHA1、6 位、30 秒。拒绝 HOTP、未知算法、重复或未知参数、issuer 冲突、空账号、控制字符和非法 Base32。标签只解码一次，展示端仍须按纯文本渲染。Base32 接受大小写及规范的可选填充，返回无填充大写；密钥范围为 10–128 字节，不自动修正含空格、缺位或多余尾比特的输入。手动录入的格式提示由客户端提供。

执行 `npm run test:protocol`，检查 RFC 6238 附录 B 的全部 18 个公开向量、6 位结果、非默认周期、2038 年之后和超过 32 位的计数器、时间边界及坏输入。Google 迁移二维码是另一种协议，尚未实现，不能当作普通 otpauth URI 导入。

依赖许可见根目录 `THIRD-PARTY-NOTICES.txt`；项目本身保持标准 AGPL-3.0-only。

依据：[RFC 6238](https://www.rfc-editor.org/rfc/rfc6238.html)、[RFC 4648](https://www.rfc-editor.org/rfc/rfc4648.html)、[Google Key URI](https://github.com/google/google-authenticator/wiki/Key-Uri-Format)、[OTPAuth](https://github.com/hectorm/otpauth)。
