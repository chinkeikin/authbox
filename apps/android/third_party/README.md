# Google Authenticator 迁移结构参考

`google_auth.proto` 取自 Aegis 的 `app/src/main/proto/google_auth.proto`（2026-10-19 获取）：
https://github.com/beemdevelopment/Aegis/blob/master/app/src/main/proto/google_auth.proto

该文件保留原包名和内容，仅作字段与枚举的兼容性参考，不参与生成或编译。源项目由 Aegis contributors 提供，使用 GPL-3.0；许可完整副本保存在 APK assets/licenses/aegis-gpl-3.0.txt。本项目保持标准 AGPL-3.0-only，GPLv3 与 AGPLv3 的组合按各自第 13 条处理。

Android 使用 protobuf 的 CodedInputStream 解析限长消息，不复制 Aegis Java 导入实现，不接受未知版本。真实 Google 导出兼容性与本机合成格式测试分别记录，不把标准 TOTP 二维码测试当作迁移验证。
