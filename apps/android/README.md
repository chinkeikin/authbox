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

此步骤的存储密钥不要求生物识别，保护的是应用私有文件。保险库 VMK 仍应在解锁后仅驻留内存；系统凭据/生物识别快捷解锁、Room 业务索引与 WorkManager 后台同步在后续步骤实现。不能据此宣称已完成整个 Android 阶段。

依赖许可见 APK assets/THIRD_PARTY_NOTICES.txt；不需要 Google Play 服务或付费 SDK。当前仅开发签名，不包含发布签名与生产服务地址。
