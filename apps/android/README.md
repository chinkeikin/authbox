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
- 新建平台账号需要部署管理员生成的邀请码。先选择恢复材料包文件的保存位置，再设置平台账号及保险库密码；生成后写入并读回核对，确认已在手机以外保存后再输入登录密码和邀请码注册，两种密码独立。
- 新设备普通登录只能得到 reader。在“账号与设备”查看完整设备 ID，与原管理手机核对；原手机刷新设备列表并批准后，新手机勾选“使用本机已有设备身份重新登录”，通过 Keystore 签名取得授权角色。没有公钥的设备不能批准为 manager，不能通过自行声明角色提权。
- 登录状态、完整密文缓存与待上传操作共用一个 Keystore 加密 AtomicFile。重启恢复登录状态时仍锁定保险库，密码只用于当次操作，不保存。默认命名空间绑定服务 origin 与账号。
- manager 可以手动添加/编辑账号、设置密钥与算法参数、调整组内顺序，创建/重命名/排序/删除分组，创建/重命名/删除标签、多选移动/设置标签/收藏/移入回收站及恢复。删除分组将账号移入未分组，删除标签只移除关联。密钥或参数更换需要额外确认，已经同步的历史版本仍保留在原服务端快照中。
- 支持按平台、自定义名称、账号、备注、分组与标签搜索，并按分组、标签、收藏或回收站筛选。点击验证码可以复制，剪贴板标记为敏感数据；当前没有自定义剪贴板清理定时器。
- 编辑确认后先保存可继续修改的加密草稿，不需要联网；点击“同步修改”才固定 operationId 与 baseRevision 发起请求。固定请求未确认或出现版本冲突后保留原请求，可进入“冲突与历史”比较并明确选择整份内容。
- 会话刷新串行执行，先保存“结果未确认”状态，再替换完整令牌对。刷新结果不确定时要求重新登录，避免自动重放旧刷新令牌导致会话族撤销；保险库缓存和待上传内容继续保留。

## 账号与设备

“账号与设备”入口提供设备列表、管理授权及撤销、平台登录密码修改和账号恢复码轮换。授权/撤销需要核对完整 ID 并二次确认；撤销当前设备会退出本机会话，但不删除缓存。改平台密码需要当前密码和两次一致的新密码，通过服务端 prepare/confirm 与本机签名完成，其他会话会被注销，保险库密码不变。

轮换时先在本机生成并离线保存 8 个新账号恢复码，勾选保存后再确认作废旧码；后台锁定会清除尚在显示的新码。未登录或锁定界面可使用“用账号恢复码恢复”，填写一个 ABAC1 码和新平台密码，明确确认撤销全部旧设备；成功后仍需要保险库密码才能下载解密。ABAC1 账号恢复码不等于 ABR1 保险库恢复密钥。

密码修改或账号恢复结果不确定时，按提示保留相应新旧材料并重新登录，不自动重放不可逆请求。恢复的新设备身份先单独持久保存，明确失败保留旧身份；不确定结果可用新密码和“本机已有设备身份”确认。该路径不自动合并旧待上传修改。

`SecurityScreenTest` 使用合成账号验证确认条件和后台清理；`CloudSecurityTest` 在临时免费服务验证真实 Keystore 授权、密码与恢复流程，测试期间正常打开应用并仅给测试窗口加保持亮屏标志，结束后关闭。结果和未验收边界见[账号安全记录](../../docs/android-security-results.md)。

## 可重复验证

从仓库根目录运行 `npm run verify:android:app:fixtures` 生成公开合成协议数据，再构建 APK。设置 `AUTHBOX_ANDROID_SERIAL` 与 `ANDROID_HOME` 后运行 `npm run verify:android:app:protocol`，Node 会验证 Android 输出并删除手机上的测试输出文件。

独立 instrumentation 类：`KeystoreTest`、`VaultTest`、`AccountStateTest`、`ScreenTest`。界面测试使用合成缓存，核对离线解锁、分组显示、搜索、Home 退后台、恢复及 Activity 重建。不会写入真实 OTP，也不会修改设备的安全策略。

## 扫码与迁移

- 相机扫描标准 `otpauth://totp` 或 Google `otpauth-migration://offline`；也可一次选择多张本地图片、粘贴二维码内容。相机授权按系统提示处理，不申请全盘相册访问。第一次授权或系统图片选择可能触发锁定，解锁后再继续。
- Google 多张迁移二维码可乱序扫码，重复扫描同一张不会增加条目；不同批次、同索引不同内容、缺张和未知版本不能直接确认。HOTP、未知算法/位数等显示为不支持，需明确选择跳过后才可保存可支持条目。
- 确认前展示列表与重复项；默认跳过密钥、算法、位数和周期相同的条目，也可保留两个独立 UUID，禁止自动覆盖。回收站内容也参与查重。预览只在内存，锁定即取消。新的一次图片选择会替换当前预览，批次图片宜一次多选。
- 图片与二维码仅本机解码，不访问二维码中的链接，不上传图片/URI，不写日志。单张图片最大 8 MiB，解码尺寸最多 2048×2048；会话最多 100 张二维码、1000 条，在线快照仍受原 1 MiB 限制。

从根目录运行 `node tools/android/import-fixtures.mjs` 生成公开 URI、迁移 protobuf 与独立 Node PNG 测试图片。新增 instrumentation 类为 `ManagementTest`、`ManagementScreenTest`、`ImportTest`、`QrImageTest`、`ImportScreenTest`；`GalleryFlowTest` 需要将生成的 `qr/camera.png` 传入手机 Pictures 并通过媒体扫描加入相册，再从真实系统选图页只选择该图；`CameraFlowTest` 需要允许相机并将设备对准公开测试图，目前摄像头实扫仍未验收。人工选图/扫码可使用 `instrument(name, 'write', 600000)` 延长测试超时。测试 provider 只在测试 APK，debug queries 不进入 release。`UiCleanupTest` 可清理因测试中断遗留且仍被选中的合成 UI 账号。

管理和导入的真实验证范围、Google 格式的合成测试边界见 [管理与扫码记录](../../docs/android-management-results.md)。正式 Google 应用导出、真实网站绑定与完整迁移验收仍需单独演练。

## 备份与保险库

锁定界面可直接进入“备份与保险库”，已解锁账号通过“账号与设备”进入。同步账号的导出要求 manager 且保险库已解锁，包含最新本机草稿和待确认快照，不代表这些内容已经在云端提交。更换保险库密码仅更新 VMK 密码封装；生成新 ABR1 恢复密钥后须勾选已离线保存，再确认更新，变更通过原加密草稿同步。原来的账号登录密码和设备身份不变。

账号解锁界面勾选“使用保险库恢复密钥 ABR1”，可用恢复密钥离线解锁缓存，或在平台登录后下载并解密。ABR1 不作为账号登录凭据。旧文件仍使用导出时的密码或恢复密钥，不能靠更换封装撤回旧备份。

从系统文件选择器打开 AuthBox 完整加密备份（最多 16 MiB），用保险库密码或 ABR1 解密校验并查看数量，确认后保存到独立离线副本。它不要求平台登录或旧手机 Keystore，也不覆盖同步账号。当前本机保留一份离线副本，已有副本时必须明确确认替换，并应先导出保存旧副本。

离线副本支持本地取码、账号和分组标签管理及重新导出，修改只写入本机，页面不提供同步写入按钮。通过“接入新账号同步”可在新平台账号下创建独立复制的保险库，不覆盖原账号或原离线副本；详细边界见下节。选文件和保存文件会按原规则触发后台锁定。导出前缓存的是密文，写入关闭后重新读取核对；取消、写入失败或进程重建丢失缓冲时不报告导出成功。

`verify:android:app:fixtures` 同时生成测试备份文件。`npm run verify:android:app:backup` 运行 `BackupStoreTest` 并用 Node 独立验证封装与密文。`BackupFilesTest` 验证真实 ContentResolver，`BackupScreenTest` 用合成选择器结果验证界面，`CloudBackupTest` 验证封装更新后的同步与恢复密钥下载。测试 provider 只在测试 APK，不能作为日常文件接口；界面测试保留已有真实离线副本，有副本时跳过会替换它的测试。结果与边界见[备份记录](../../docs/android-backup-results.md)。

测试安装前比对本机构建与手机 APK 的 SHA256，相同文件跳过重复安装；需要更新时仍走手机正常系统确认。摄像头实扫、真实迁移、故障恢复演练和发布验收按最新安排放在功能开发完成后。

## 离线副本接入新账号与恢复材料包

先导入并确认保存离线副本，再选择“接入新账号同步”。先选择恢复材料包保存位置，之后填写新的 HTTPS 服务地址和用户名，重新输入源副本密码或 ABR1，设置两次一致的新同步保险库密码，并明确确认创建独立副本。

新账号使用新的保险库 UUID、VMK、ABR1 和 8 个 ABAC1，从版本 1 开始，全量重新加密，保留业务内容、实体 UUID、排序及回收站状态；原账号历史不复制。新账号需要新的注册邀请码，不能覆盖本机已有账号；源文件过大而无法满足在线 1 MiB 限制时拒绝接入。未确认注册的候选数据保存在本机密文中，不能在结果不确定时重新生成材料，应先使用新密码和本机已有设备身份确认登录。

`.authbox-recovery` 文件为 `AUTHBOX-RECOVERY-KIT` v1 JSON，包含服务地址、用户名、保险库 UUID、日期、备份格式版本、两类恢复秘密及使用说明。它是明文高权限材料，必须另存在手机以外，不进入 NAS 自动备份或云端同步请求；本文件不含密码、VMK 或保险库备份，断云恢复仍需单独保存匹配的 `.authbox-backup`。注册成功前它只对应候选账号，重新生成或轮换后应更新并保留历史备份的对应材料。

生成恢复材料发生在系统选文件返回之后，不跨选择器保留明文；写入后重新读取核对，失败时禁止确认注册，生成后退后台或锁定会清除显示内容。先勾选保存完整材料，再输入两次一致的登录密码和邀请码注册；成功后返回账号，用新的保险库密码下载解锁，再导出新账号的加密备份。

`npm run verify:android:app:registration` 验证存储及 Node 互通，`npm run verify:android:app:registration-ui` 验证真实界面、ContentResolver 与导出结果。选择器测试返回公开测试 URI，不能代替最终真实文件与设备丢失恢复演练。结果见[离线副本接入记录](../../docs/android-registration-results.md)。

## 冲突与历史

解锁管理设备后，从顶栏进入“冲突与历史”。比较时输入最新云端保险库密码或 ABR1，完整校验双方内容；按条目显示非密钥内容及变更字段，不显示验证密钥字段原文。当前按整份快照选择，不做逐字段自动合并。确认后保留本地业务内容会生成新 draft；采用最新云端会确认结束本地候选。确认前重新检查 head，之后的提交仍接受服务端原子 CAS；再次冲突时重新读取比较，不改写原请求。

“读取云端历史”按版本分页显示服务端保留窗口内快照。预览历史时沿用最新云端主密钥认证，旧主密钥的历史需要对应旧密码/ABR1。确认恢复会在最新版本上生成新 draft，明确声明重新加入的 ID，保留当前 VMK、代次和密码封装；不会回退同步序号或重新启用旧密码。点击“提交已确认内容”后才更新云端。

处理前本地、比较时云端和选中历史保存在本机加密保留副本中，按摘要去重，最多 16 份、加密备份 UTF8 内容合计 16 MiB（本机封装另有开销）。空间满则拒绝继续，不自动清理。可导出为标准加密备份并在离线副本中查看，解密仍需对应版本的密码/密钥；删除必须另行明确确认。取消比较也先保留双方，不清除原 pending。

退后台、锁定或重启清除比较明文，保留密文；回到前台不会让旧异步结果重新显示，需重新解锁和比较。保存过的比较未确认或取消前，编辑、封装更换、普通下载及上传均受保护。`verify:android:app:review` 验证存储与 Node 互通，`verify:android:app:review-ui` 验证界面及实际导出；界面测试采用受控请求返回与公开文件 URI，真实云端联调独立记录。见[冲突与历史结果](../../docs/android-sync-review-results.md)。

`npm run verify:android:app:cloud` 需要本机忽略的 `.env.cloudflare`、`JAVA_HOME`、`ANDROID_HOME`、`AUTHBOX_ANDROID_SERIAL`、已授权测试区 `AUTHBOX_CF_TEST_ZONE` 和空闲子域 `AUTHBOX_CF_TEST_HOST`。它确认免费套餐，创建临时 SQLite DO 和 Worker，生成仅测试 APK 可读的配置，执行账号、管理、封装与离线副本接入联调后清理资源。`--cleanup --android` 可按同一报告重试云端清理。测试 APK 不应作为日用安装；联调完成后可卸载 `com.authbox.android.test`。

本步骤固定已验证的工具链与依赖；Lint 关于较新目标 SDK 和依赖版本的提醒保留，发布前另行升级与兼容性验证。实际结果见 [真机记录](../../docs/android-results.md)。

依赖许可见 APK assets/THIRD_PARTY_NOTICES.txt；不需要 Google Play 服务或付费 SDK。当前仅开发签名，不包含发布签名与生产服务地址。
