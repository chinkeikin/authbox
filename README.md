# AuthBox

AuthBox 是支持加密同步的多设备 2FA 验证器。Android 手机负责账号管理，Chrome 扩展只负责查看、搜索和复制验证码，NAS 主动拉取加密备份。

跨端协议及账号、设备、会话和密文同步服务核心已实现。Android 已支持账号与分组标签管理、二维码及迁移导入、加密草稿、设备授权撤销、账号恢复、保险库封装更换、独立离线文件导入导出、离线副本复制注册新账号、完整恢复材料包、冲突取舍及历史恢复。Keystore、登录和加密同步通过 PHB110 与 Cloudflare 免费环境联调。系统解锁及 Chrome 扩展等功能仍在后续阶段，真实迁移、故障恢复和发布验收统一放到最后，当前未部署生产服务。

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
- [第一版剩余工作](docs/remaining-work.md)
- [可行性验证进度与结果](docs/feasibility/README.md)
- [跨端密码学与备份协议](docs/protocol-v1.md)
- [业务数据、同步与权限协议](docs/sync-api-v1.md)
- [协议与真机互通结果](docs/protocol-results.md)
- [账号与同步服务](services/api/README.md)
- [服务验证记录](docs/api-results.md)
- [Android 工程与构建](apps/android/README.md)
- [Android 真机及云端联调记录](docs/android-results.md)
- [Android 管理与扫码导入记录](docs/android-management-results.md)
- [Android 设备与账号安全记录](docs/android-security-results.md)
- [Android 保险库备份与离线恢复记录](docs/android-backup-results.md)
- [Android 离线副本接入新账号记录](docs/android-registration-results.md)
- [Android 冲突处理与历史恢复记录](docs/android-sync-review-results.md)
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

`packages/protocol/`、`services/api/`、`apps/android/` 已建立，Chrome 和 NAS 业务模块仍为规划目录；可行性探针独立于正式业务工程。

## 开发方式

每个步骤形成可评审的独立改动，完成对应检查后提交并及时推送。提交说明记录改动、验证和限制。真实验证密钥、备份、令牌及部署秘密不进入 Git。

执行 `npm test` 验证协议、HTTP 边界、SQLite API 和正式打包入口，执行 `npm run build:api` 生成本地部署包。Android 构建、合成测试材料及免费云端联调命令见客户端说明；Google 真实授权、完整客户端功能和 NAS 恢复仍按后续阶段验证。

## 许可证

本项目采用标准 GNU Affero General Public License version 3，SPDX 标识为 `AGPL-3.0-only`。完整条款见 [LICENSE](LICENSE)，不附加自定义许可限制。详见[许可证说明](docs/licensing.md)。
