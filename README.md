# AuthBox

AuthBox 是支持加密同步的多设备 2FA 验证器。Android 手机负责账号管理，Chrome 扩展只负责查看、搜索和复制验证码，NAS 主动拉取加密备份。

免费 Workers + SQLite Durable Objects 的密码认证可行性已通过，当前开始实现跨端协议与密码学基础，尚未实现正式客户端或后端。

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

`packages/protocol/` 已建立，其他业务模块仍为规划目录；可行性探针独立于正式业务工程。

## 开发方式

每个步骤形成可评审的独立改动，完成对应检查后提交并及时推送。提交说明记录改动、验证和限制。真实验证密钥、备份、令牌及部署秘密不进入 Git。

Cloudflare 免费档的密码哈希开销、Android 与扩展跨端解密、Google 登录和 NAS 网络可达性需要先做可行性验证。

## 许可证

本项目采用标准 GNU Affero General Public License version 3，SPDX 标识为 `AGPL-3.0-only`。完整条款见 [LICENSE](LICENSE)，不附加自定义许可限制。详见[许可证说明](docs/licensing.md)。
