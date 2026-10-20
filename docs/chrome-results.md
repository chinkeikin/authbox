# Chrome 扩展验证

当前独立账号的 Chrome 任务已完成；下文前三步保留阶段验证边界，最终结果以第四步为准。Google 快捷登录按用户安排在 Google 绑定阶段实现。

## 第一步：只读核心及 MV3 工程，2026年10月20日

复用跨端备份、Argon2id、AES-GCM 和 TOTP；为已解锁内存密钥增加共享的完整备份认证入口。扩展使用只读登录且 publicKey 为 null，网络路径固定为登录、刷新、注销、head 和 snapshot。管理写入不进入客户端路由。

已验证密文与待验证候选分开保存；完整解密和业务校验通过后才接受缓存。持久层不保存密码、VMK、业务名称或验证密钥明文。临时 VMK 只进入 TRUSTED_CONTEXTS 的 session 存储，锁定期限、后台期限和时钟回退在取码前检查。重建 Worker 可在期限内恢复，浏览器重启/扩展重载依靠 Chrome session 生命周期清除解锁状态。

本地核心 10 项测试和共享协议 47 项通过，覆盖只读身份、加密缓存、筛选、取码、Worker 重建、离线解锁、换代、坏密文、同步不延长锁定、撤销、刷新结果不明及并发锁定。Google Chrome 155.0.8059.84 在隔离配置下通过浏览器级 CDP 实际安装 MV3 包并执行后台消息。该安装方法不等于已验证公司受管浏览器允许手动安装。

此步骤界面仍为骨架，完整取码界面、真实生命周期和免费云端联调继续开发，不能将本段计为 Chrome 全部功能验收完成。原始报告在忽略的 `.cache/chrome/smoke-report.json`。

## 第二步：完整只读界面及本机 SQLite DO 联调

已实现登录、密码解锁、收藏与全部切换、分组/标签筛选、平台/账号/备注/标签搜索、排序、验证码倒计时、复制、刷新与前台定期拉取、手动锁定、完整窗口、本机外观/锁定设置、注销及确认清除本机缓存。回收站条目不显示；无新增、编辑、删除、导入、导出、收藏修改或管理授权入口。默认闲置 5 分钟、离开全部窗口立即锁定，可改为 30 秒后台宽限或沿用闲置期限。

Chrome 155 实际安装扩展，通过仅回环可达的 HTTPS 代理连接真实本地 workerd / SQLite DO，9 组场景通过：真实登录与密码错误拒绝、完整 AEAD 认证、只读筛选及 XSS 文本渲染、真实剪贴板写入、持久存储无解密材料/业务明文、实际只读令牌对 8 个管理接口均为 403、手机协议的管理端更新可拉取且不延长解锁、实际停止 Worker 后恢复、浏览器重启清除解锁及断服务离线取码、本机清除确认。剪贴板读取只在隔离测试配置中授予以断言结果，扩展仅申请 clipboardWrite。

HTTPS 代理使用新生成的合成自签证书，测试浏览器忽略该证书错误；正式构建不配置或忽略证书验证。所有账号、验证码和数据均为合成，原始报告和截图在忽略的 `.cache/chrome/browser-report.json` 及其 directory。真实免费云端、实际弹窗、重载和实际时间边界继续验证，不把本机模拟视为生产验收。

## 第三步：实际生命周期、存储隔离和安装包

最终本机 Chrome 界面检查为 10 组：在第二步基础上加入仅测试包声明的内容脚本，实际证明其访问 local/session 存储均被拒绝，向后台发送取码消息也返回 untrusted_sender。正常安装包没有内容脚本。测试代码不把内容脚本可用作为产品能力。

实际生命周期 6 组通过：原生 action 弹窗、离开全部窗口立即锁定、真实 60 秒闲置锁定、真实 30 秒后台宽限、runtime.reload 后解锁状态清除、实际 HTTPS 晚响应不能解除手动锁定、普通网页不能调用扩展接口。部分相关断言合并在同一组；没有用缩短生产计时或模拟时钟替代两项真实时间检查。测试环境为 Chrome 155.0.8059.84，原始报告为 `.cache/chrome/lifecycle-report.json`。

测试通过 Chrome 正常扩展设置在专用资料目录中启用开发者模式，再用浏览器级 CDP 加载。未启用开发者模式时，Chrome 会在 reload 后禁用开发扩展；该条件已纳入安装和测试说明。浏览器测试串行执行，避免实际窗口焦点造成默认锁定。

补齐本机损坏会话期限和锁定配置的安全拒绝。`npm test` 全部通过：协议 48 项、Chrome 核心 13 项、HTTP 2 项、本地 SQLite DO 请求 208 项及服务端生产包隔离 9 项。扩展生产包另外验证 MV3、最小权限、固定 API 主机、无远程脚本、无 unsafe-eval/unsafe-inline、无测试 gate、无 storage.sync/普通网页持久存储和完整许可文件。

会话清理及受信任上下文访问规则参考 [Chrome storage](https://developer.chrome.com/docs/extensions/reference/api/storage)，后台停用边界参考 [Service Worker 生命周期](https://developer.chrome.com/docs/extensions/develop/concepts/service-workers/lifecycle)。实际停止 Worker 后无需重新输入密码的恢复已单独验证；不把调试连接下的强制停止说成自然闲置休眠采样。

## 第四步：免费云端、工作电脑确认与最终复核

Chrome 155.0.8059.84 实际安装扩展，连接临时真实 HTTPS 域名、Workers Free 和 SQLite Durable Objects，6 组检查通过：平台密码登录与 Argon2id 解锁；实际 reader 令牌对 8 个管理接口均为 403，publicKey 为 null 的 Chrome 设备不能升为 manager；真实密文筛选与文本渲染；使用手机相同管理协议执行 VMK 轮换后，旧会话失效、重新登录仍为 reader、旧保险库密码失败而新密码成功；真实撤销后锁定；撤销后仍能断网解锁已经取得的本机密文。没有使用真实 OTP 或用户账号，也未要求手机保持在线。

云端测试浏览器执行正常 HTTPS 证书验证，临时测试 gate 只进入忽略目录下的探针构建。Workers 付费订阅检查前后均为 0，平台哈希仍为 Argon2id 32 MiB / t3 / p1，保险库为 64 MiB / t3 / p1，未引入 D1/R2。临时 Worker、自建域名绑定及 SQLite DO namespace 删除均已核对；托管证书的独立生命周期不计为已验证删除。原始报告为 `.cache/api/cloud/chrome-service-report.json`，runtimeChecksPassed、freeTierRuntimeVerified、cleanupVerified 均为 true。

最终检查发现并修复了用户操作更新时间戳不一致导致提前锁定的问题：延长闲置期限时同时记录同一时刻，避免被会话校验误判。新增毫秒间隔回归检查；最终 Chrome 核心为 13 项。修复后重新运行真实浏览器 10 组、真实生命周期 6 组和整套 `npm test`，均通过。正常构建的 MV3/CSP/最小权限/测试代码隔离和许可检查通过。

用户已确认当前测试机就是上班电脑。在本机安装的 Google Chrome 上，专用资料目录中的开发者模式、解压扩展加载、原生弹窗及重载均通过；未改动日常资料或企业策略。原始探针的 companyPolicyVerified 为 false 表示未做企业策略全面审计，不否认本机实际安装结果，也不代表其他资料目录或电脑均允许安装。

默认开发包 `dist/chrome` 使用 `https://api.example.invalid` 占位地址；按客户端 README 指定实际 HTTPS 服务地址重建，并在服务端配置扩展 origin 后才能日常使用。没有部署生产服务、创建发布标签或提交商店。Google 绑定、NAS 业务备份、此前暂缓的系统认证正向实测及最终真实迁移/故障恢复/发布验收仍待完成。
