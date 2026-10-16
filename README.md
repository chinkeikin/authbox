# AuthBox

AuthBox 是支持加密同步的多设备 2FA 验证器。Android 手机负责账号管理，Chrome 扩展只负责查看、搜索和复制验证码，NAS 主动拉取加密备份。

跨端协议及账号、设备、会话和密文同步服务核心已实现，通过本地与 Cloudflare 免费环境验证。Android 和 Chrome 客户端尚未建立，当前未部署生产服务。

## 第一版范围

- Android 完整管理账号、分组、标签、收藏、导入及恢复，iOS 后续增加。
- Chrome 扩展只读查看、筛选和复制验证码，支持离线取码与自动锁定。
- 独立平台账号，可选绑定 Google 快捷登录。
- 客户端加密、Cloudflare 同步、版本检查、冲突和回收站处理。
- 独立账号恢复及保险库解密恢复。
- NAS 主动下载可离线导入的完整加密备份。

## 文档

- [第一版需求与技术方案](docs/requirements-v1.md)
- [开发路线](docs/roadmap.md)
- [可行性验证进度与结果](docs/feasibility/README.md)
- [跨端密码学与备份协议](docs/protocol-v1.md)
- [业务数据、同步与权限协议](docs/sync-api-v1.md)
- [协议与真机互通结果](docs/protocol-results.md)
- [账号与同步服务](services/api/README.md)
- [服务验证记录](docs/api-results.md)
- [许可证说明](docs/licensing.md)

## 仓库组织

采用单仓库，按功能落地时建立对应模块：

| 路径 | 内容 |
| --- | --- |
| `apps/android/` | Kotlin 和 Jetpack Compose 手机客户端 |
| `apps/chrome/` | TypeScript 和 Manifest V3 只读扩展 |
| `services/api/` | Cloudflare Workers 认证与同步服务 |
| `packages/protocol/` | 同步与加密格式、跨端测试向量及 schema |
| `tools/nas-backup/` | NAS 主动备份程序 |
| `tools/feasibility/` | 已建立的运行时、真机和环境验证探针 |
| `docs/` | 需求、方案、开发步骤及运行说明 |

`packages/protocol/`、`services/api/` 已建立，客户端和 NAS 业务模块仍为规划目录；可行性探针独立于正式业务工程。

## 开发方式

每个步骤形成可评审的独立改动，完成对应检查后提交并及时推送。提交说明记录改动、验证和限制。真实验证密钥、备份、令牌及部署秘密不进入 Git。

执行 `npm test` 验证协议、HTTP 边界、SQLite API 和正式打包入口，执行 `npm run build:api` 生成本地部署包。服务核心通过免费云端完整接口验证；Google 真实授权、正式客户端生命周期、客户端联调和完整 NAS 恢复仍按后续阶段验证。

## 许可证

本项目采用标准 GNU Affero General Public License version 3，SPDX 标识为 `AGPL-3.0-only`。完整条款见 [LICENSE](LICENSE)，不附加自定义许可限制。详见[许可证说明](docs/licensing.md)。
