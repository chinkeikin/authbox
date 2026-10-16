# Google 身份令牌可行性验证结果

2026年10月16日，使用 jose 6.2.12 和内存生成的合成 RSA 密钥，在 Node WebCrypto 及本地 workerd 各执行 22 类接受或拒绝检查，全部符合预期。

检查覆盖签名、两种允许的 issuer、显式 audience、过期与未来签发、登录令牌时效、nonce、subject、必需时间字段、多个 audience 的 azp、可信签名密钥变化、未知 kid、伪造签名、篡改 payload、无签名和对称算法混淆。令牌自带的密钥 URL 不参与公钥选择。

身份返回值仅包含规范化 issuer 和 subject。同一邮箱且不同 subject 的两份合成令牌返回不同身份，不以邮箱自动合并账号。探针未实现平台账号绑定或会话签发。

默认远程公钥端点固定为 Google 的 HTTPS JWKS，测试注入本地可信公钥集以验证运行兼容性。测试的密钥变化仅验证两个已知公钥，不代表实际远程 JWKS 下载、缓存、轮换刷新或网络失败处理已经通过。

本次没有使用真实 Google 用户令牌，也没有完成浏览器授权回调。真实 OAuth 客户端、回调地址、一次性 nonce 消费、账号绑定与解绑属于后续集成条件。当前不能把该项描述为“Google 快捷登录已可用”。

复现命令为 `npm run verify:google`；报告位于 `.cache/feasibility/google/report.json`，不保存测试私钥或令牌。依据：[Google 令牌验证](https://developers.google.com/identity/gsi/web/guides/verify-google-id-token)、[jose 验证接口](https://github.com/panva/jose/blob/main/docs/jwt/verify/functions/jwtVerify.md)。
