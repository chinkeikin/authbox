# AuthBox Chrome 扩展

独立平台账号的 MV3 只读客户端，Google 快捷登录按用户安排留到 Google 绑定阶段。采用现有 ESM、esbuild 和跨端密码学模块，不增加运行时依赖；密码哈希参数不变。

在仓库根目录执行 `npm run build:chrome -- --api-origin=https://你的服务域名`，将 `dist/chrome` 作为解压后的扩展加载。未传入时使用不可用的 `https://api.example.invalid` 占位地址。安装包仅申请所配置 API 的主机访问权限，不包含内容脚本、自动填码或所有网站访问权限。服务端 `CLIENT_ORIGINS` 需加入构建输出的 `chrome-extension://扩展ID`。

manifest 的 key 是公开的开发扩展标识，不是签名私钥，也不提供身份认证。不要把真实凭据放入源码、构建参数或配置。Chrome 商店发布后的扩展 ID 和服务 origin 需在发布阶段核对。

当前开发结果见[验证记录](../../docs/chrome-results.md)。用户已确认本机就是工作电脑；实际 Chrome 155 在专用资料目录中启用开发者模式、加载和重载通过，不改动日常浏览器资料或企业策略。该结果不覆盖其他电脑或资料目录；商店发布另行安排。

## 使用

在 Chrome 的扩展管理页打开开发者模式，选择“加载已解压的扩展程序”并选中 `dist/chrome`。固定到工具栏后可打开弹窗，也可从“完整窗口”进入标签页。用 Android 已注册的平台账号登录，再用保险库密码解锁；登录不会取得管理权限。账号、分组、标签、收藏和回收站由手机管理。

默认展示保险库配置的收藏或全部视图，可按分组、标签和本地文本搜索，点击验证码复制，倒计时与复制不发起云端请求。打开窗口会检查版本，使用期间按 60 秒间隔检查，网络失败指数退避；关闭后不持续轮询。已验证加密缓存可在断网、手机离线及重新启动浏览器后用密码解锁；重新启动后仍须重新解锁。

默认闲置 5 分钟、离开所有窗口立即锁定。可在本机设置改为 1/5/15 分钟闲置，后台立即/30 秒/沿用闲置期限；保存设置会锁定。切走窗口立即隐藏验证码，返回前检查期限。只读登录及必要令牌保留在本机受信任存储中，明文密钥只使用会话存储和内存，重载/更新/重启后清除；不使用 Chrome 同步存储。浏览器环境不提供硬件级内存擦除保证。

云端换代会保留原缓存并要求新密码，不能把新密码失败自动当作旧版本解锁成功；可明确选择已验证的离线缓存。设备撤销、会话过期或刷新结果不明会要求重新登录，离线缓存不会被删除。退出登录保留密文；清除本机数据须另行确认。扩展不能导出或修改云端保险库，不能远程擦除已持有的验证密钥。

## 验证

`npm test` 包含共享协议、Chrome 核心和服务端回归。`npm run verify:chrome:production` 检查安装包权限、远程代码和测试凭据隔离；`npm run verify:chrome:browser` 使用本机 HTTPS 与真实 SQLite DO 验证完整界面；`npm run verify:chrome:lifecycle` 实测原生弹窗、真实闲置与后台期限、重载及晚响应。后两项需要本机 Google Chrome 和 OpenSSL，可用 `AUTHBOX_OPENSSL` 指定程序路径。浏览器测试请串行运行，窗口焦点属于共享环境。

`npm run verify:chrome:cloud` 需要忽略的 `.env.cloudflare`、已授权的 `AUTHBOX_CF_TEST_ZONE` 和空闲的 `AUTHBOX_CF_TEST_HOST`。探针先确认无 Workers Paid 订阅，再创建临时受随机凭据保护的 Worker/SQLite DO/域名绑定，验证后删除。测试专用 header 只注入 `.cache` 下的临时扩展，正常构建不含它；失败可用同一环境执行 `node --use-env-proxy --env-file=.env.cloudflare tools/api/run-cloud.mjs --chrome --cleanup` 重试云端清理。正式安装包不会忽略 HTTPS 证书错误。

Google 快捷登录、Chrome 商店发布、正式服务域名及生产 origin 配置按后续阶段安排。开发包默认使用占位 API 地址，配置实际服务后才能日常登录使用。若其他浏览器配置受策略限制，需按其管理要求安装。
