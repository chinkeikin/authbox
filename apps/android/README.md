# AuthBox Android

Kotlin、Compose；最低 Android 8 / API 26，编译与目标 API 35。AGP 8.7.3、Gradle 8.9、Kotlin 2.0.20；构建需要 JDK 17 或 21、Android SDK 35。Gradle Wrapper 固定官方分发包 SHA-256。

设置本机 `JAVA_HOME`、`ANDROID_HOME`，或在不入库的 `local.properties` 中设置 `sdk.dir`。执行：

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
adb -s <PHB110序列号> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <PHB110序列号> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <PHB110序列号> shell am instrument -w -e class com.authbox.android.KeystoreTest -e phase write com.authbox.android.test/androidx.test.runner.AndroidJUnitRunner
adb -s <PHB110序列号> shell am force-stop com.authbox.android
adb -s <PHB110序列号> shell am instrument -w -e class com.authbox.android.KeystoreTest -e phase read com.authbox.android.test/androidx.test.runner.AndroidJUnitRunner
```

`KeystoreBox` 创建不可导出的 P-256 设备签名密钥，以及 AES-256-GCM 本地存储密钥。文件置于应用私有 `noBackupFilesDir`，使用 AtomicFile 替换；AAD 绑定包名、命名空间与文件名。密钥失效或密文损坏时抛出异常，不能自动清空数据冒充首次启动。系统备份及设备转移排除本地文件；Activity 设置 FLAG_SECURE。

保险库 VMK 解锁后仅驻留内存，暂停 Activity 即隐藏验证码并锁定，回到前台需重新解锁。此步骤的存储密钥不要求生物识别，保护的是应用私有文件；系统凭据/生物识别快捷解锁、Room 业务索引、WorkManager 后台同步、闲置计时与完整恢复流程在后续步骤实现。不能据此宣称已完成整个 Android 阶段。

## 当前界面与账号流程

- 服务地址填写 HTTPS origin，例如 `https://authbox.example.com`，不带路径或尾部 `/`。未内置生产服务或 Cloudflare 凭证。
- 新建平台账号需要部署管理员生成的邀请码。先设置平台账号及保险库密码，生成并离线保存恢复密钥和 8 个账号恢复码，确认保存后再输入登录密码和邀请码注册；两种密码独立。
- 新设备普通登录只能得到 reader。已授权的本机设备勾选“使用本机已有设备身份重新登录”，通过 Keystore 签名恢复现有角色。设备批准界面仍待实现，不能通过自行声明角色成为 manager。
- 登录状态、完整密文缓存与待上传操作共用一个 Keystore 加密 AtomicFile。重启恢复登录状态时仍锁定保险库，密码只用于当次操作，不保存。默认命名空间绑定服务 origin 与账号。
- manager 可以手动添加/编辑账号、设置密钥与算法参数、调整组内顺序，创建/重命名/排序/删除分组，创建/重命名/删除标签、多选移动/设置标签/收藏/移入回收站及恢复。删除分组将账号移入未分组，删除标签只移除关联。密钥或参数更换需要额外确认，已经同步的历史版本仍保留在原服务端快照中。
- 支持按平台、自定义名称、账号、备注、分组与标签搜索，并按分组、标签、收藏或回收站筛选。点击验证码可以复制，剪贴板标记为敏感数据；当前没有自定义剪贴板清理定时器。
- 编辑确认后先保存可继续修改的加密草稿，不需要联网；点击“同步修改”才固定 operationId 与 baseRevision 发起请求。固定请求未确认或出现 409 后，保留本地内容并阻止覆盖。当前没有冲突合并界面。
- 会话刷新串行执行，先保存“结果未确认”状态，再替换完整令牌对。刷新结果不确定时要求重新登录，避免自动重放旧刷新令牌导致会话族撤销；保险库缓存和待上传内容继续保留。

## 可重复验证

从仓库根目录运行 `npm run verify:android:app:fixtures` 生成公开合成协议数据，再构建 APK。设置 `AUTHBOX_ANDROID_SERIAL` 与 `ANDROID_HOME` 后运行 `npm run verify:android:app:protocol`，Node 会验证 Android 输出并删除手机上的测试输出文件。

独立 instrumentation 类：`KeystoreTest`、`VaultTest`、`AccountStateTest`、`ScreenTest`。界面测试使用合成缓存，核对离线解锁、分组显示、搜索、Home 退后台、恢复及 Activity 重建。不会写入真实 OTP，也不会修改设备的安全策略。

## 扫码与迁移

- 相机扫描标准 `otpauth://totp` 或 Google `otpauth-migration://offline`；也可一次选择多张本地图片、粘贴二维码内容。相机授权按系统提示处理，不申请全盘相册访问。第一次授权或系统图片选择可能触发锁定，解锁后再继续。
- Google 多张迁移二维码可乱序扫码，重复扫描同一张不会增加条目；不同批次、同索引不同内容、缺张和未知版本不能直接确认。HOTP、未知算法/位数等显示为不支持，需明确选择跳过后才可保存可支持条目。
- 确认前展示列表与重复项；默认跳过密钥、算法、位数和周期相同的条目，也可保留两个独立 UUID，禁止自动覆盖。回收站内容也参与查重。预览只在内存，锁定即取消。新的一次图片选择会替换当前预览，批次图片宜一次多选。
- 图片与二维码仅本机解码，不访问二维码中的链接，不上传图片/URI，不写日志。单张图片最大 8 MiB，解码尺寸最多 2048×2048；会话最多 100 张二维码、1000 条，在线快照仍受原 1 MiB 限制。

从根目录运行 `node tools/android/import-fixtures.mjs` 生成公开 URI、迁移 protobuf 与独立 Node PNG 测试图片。新增 instrumentation 类为 `ManagementTest`、`ManagementScreenTest`、`ImportTest`、`QrImageTest`、`ImportScreenTest`；`CameraFlowTest` 需要用户允许相机并将设备对准公开测试图。测试 provider 只在测试 APK，debug queries 不进入 release。

管理和导入的真实验证范围、Google 格式的合成测试边界见 [管理与扫码记录](../../docs/android-management-results.md)。正式 Google 应用导出、真实网站绑定与完整迁移验收仍需单独演练。

`npm run verify:android:app:cloud` 需要本机忽略的 `.env.cloudflare`、`JAVA_HOME`、`ANDROID_HOME`、`AUTHBOX_ANDROID_SERIAL`、已授权测试区 `AUTHBOX_CF_TEST_ZONE` 和空闲子域 `AUTHBOX_CF_TEST_HOST`。它确认免费套餐，创建临时 SQLite DO 和 Worker，生成仅测试 APK 可读的配置，三轮联调后清理资源。`--cleanup --android` 可按同一报告重试云端清理。测试 APK 不应作为日用安装；联调完成后可卸载 `com.authbox.android.test`。

本步骤固定已验证的工具链与依赖；Lint 关于较新目标 SDK 和依赖版本的提醒保留，发布前另行升级与兼容性验证。实际结果见 [真机记录](../../docs/android-results.md)。

依赖许可见 APK assets/THIRD_PARTY_NOTICES.txt；不需要 Google Play 服务或付费 SDK。当前仅开发签名，不包含发布签名与生产服务地址。
