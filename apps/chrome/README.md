# AuthBox Chrome 扩展

独立平台账号的 MV3 只读客户端，Google 快捷登录按用户安排留到 Google 绑定阶段。采用现有 ESM、esbuild 和跨端密码学模块，不增加运行时依赖；密码哈希参数不变。

在仓库根目录执行 `npm run build:chrome -- --api-origin=https://你的服务域名`，将 `dist/chrome` 作为解压后的扩展加载。未传入时使用不可用的 `https://api.example.invalid` 占位地址。安装包仅申请所配置 API 的主机访问权限，不包含内容脚本、自动填码或所有网站访问权限。服务端 `CLIENT_ORIGINS` 需加入构建输出的 `chrome-extension://扩展ID`。

manifest 的 key 是公开的开发扩展标识，不是签名私钥，也不提供身份认证。不要把真实凭据放入源码、构建参数或配置。Chrome 商店发布后的扩展 ID 和服务 origin 需在发布阶段核对。

当前开发结果见[验证记录](../../docs/chrome-results.md)。本机测试使用隔离 Chrome 资料目录，不改动日常浏览器资料；公司受管策略及商店发布另行确认。
