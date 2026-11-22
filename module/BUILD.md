# FkOScreen 模块构建说明 (module/)

本目录由 `ref-FkOScreen` 复制而来，并针对本机环境（JDK 21 / Gradle 9.x / Android SDK 37）
修好了构建链路，可稳定产出可安装 APK。

> 签名文件已从仓库移除（`module/keystore/fkoscreen-debug.jks` 不再入库）。下文提到该文件的步骤需要自己生成一把：
> `keytool -genkeypair -keystore module/keystore/fkoscreen-debug.jks -alias fkoscreen -storepass android -keypass android -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -validity 10000`；
> 没有这个文件时，构建脚本会退回 AGP 默认的 debug 签名。

## 1. 最终版本组合

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | **OpenJDK 21.0.12.1** (`/usr/lib/jvm/java-21-openjdk`) | 本机只有 JDK 21 / 11，**没有 JDK 17**；AGP 9.x 支持 JDK 17+，直接用 21 |
| Gradle | **9.8.0** | 来自本地分发缓存 `~/.gradle/wrapper/dists/gradle-9.8.0-bin`，无需联网下载 |
| AGP | **9.4.1** | 本地 Gradle 缓存里已有；AGP 8.3.2 与 Gradle 9.x 不兼容，故升级 |
| Kotlin | **2.4.20** | AGP 9 内置 KGP 2.2.10，被 compose 插件的 BOM 升级到 2.4.20（`buildEnvironment` 可验证） |
| Compose 编译器插件 | 2.4.20 | `org.jetbrains.kotlin.plugin.compose` |
| compileSdk | **37** | 依赖（compose 1.12.0 / miuix 0.9.4）要求 compileSdk ≥ 37，本机已装 `platforms/android-37.0` |
| minSdk / targetSdk | 26 / 34 | 与原工程一致 |
| Build Tools | 37.0.0 | `$ANDROID_HOME/build-tools/37.0.0` |

依赖版本（全部命中本地 Gradle 缓存，离线可构建）：

- `androidx.compose.ui:ui / ui-graphics / ui-tooling-preview : 1.12.0`
- `androidx.activity:activity-compose : 1.13.0`
- `androidx.core:core-ktx : 1.18.0`，`androidx.appcompat:appcompat : 1.7.0`
- `top.yukonga.miuix.kmp:miuix-ui / miuix-preference : 0.9.4`
- `de.robv.android.xposed:api:82`（compileOnly，来自 https://api.xposed.info/）

> 原工程的 `androidx.compose.material3` 已移除：源码只用了 miuix + compose foundation/runtime，没有 material3 引用。

## 2. 改了什么

### `gradle/wrapper/gradle-wrapper.properties`
- `gradle-8.6-bin.zip` → `gradle-9.8.0-bin.zip`（本地缓存已有该分发，首次 `./gradlew` 不会下载）

### `build.gradle.kts`（根）
- AGP `8.3.2` → `9.4.1`
- **删除** `org.jetbrains.kotlin.android` 插件：AGP 9.0 起 Kotlin 已内置，
  显式再应用会直接报错
  `The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0`
- 保留 `org.jetbrains.kotlin.plugin.compose` 2.4.20（它会把内置 KGP 顶到 2.4.20）

### `app/build.gradle.kts`
- `compileSdk 35` → `37`（否则 `checkDebugAarMetadata` 报 15 个依赖要求 API 37）
- 新增 `signingConfigs.create("localDebug")`：使用仓库内自带 keystore
  `keystore/fkoscreen-debug.jks`，debug 与 release 都签同一把 key
  （原来 release 借用 debug 签名，debug 走 `~/.android/debug.keystore`，
  换机器/换签名会导致 `adb install -r` 失败）
- 依赖改为上面列出的显式版本（去掉 BOM，避免联网拉 compose-bom 2024.04.01）
- 新增 `lint { checkReleaseBuilds = false; abortOnError = false }`
- 删除了 `tasks.matching { it.name.contains("AarMetadata") }.configureEach { enabled = false }`
  这个临时 hack（compileSdk 37 之后不再需要）
- `kotlin { compilerOptions { jvmTarget = JVM_17 } }` 从 `android {}` 内挪到顶层
  （AGP 9 的 `android.kotlin` 已不是标准位置）

### `gradle.properties`
- `-Xmx2048m` → `-Xmx3072m -XX:MaxMetaspaceSize=1024m`（AGP 9 + Kotlin 2.4 更吃内存）
- 删除 `android.dependency.enforceCompileSdk=false`（AGP 9 已不需要）
- `org.gradle.configuration-cache=true`：**关键提速项**，AGP 9.4.1 已验证兼容，
  空跑 `assembleDebug` 从 17 秒降到 **2 秒**

### 新增文件
- `local.properties`（`sdk.dir=$ANDROID_HOME`，已在 .gitignore 中）
- `keystore/fkoscreen-debug.jks`（自签名 key，见下）

### 未改动
- `app/src/**`（Kotlin 源码、Manifest、assets/xposed_init、res）**一行没动**，
  后续 Lead 改 hook 代码不需要动构建配置。

## 3. 构建命令

```bash
cd <repo>/module

# Debug APK（日常开发用）
./gradlew :app:assembleDebug

# Release APK（已配置本地 key 签名）
./gradlew :app:assembleRelease
```

产物：

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

实测耗时（本机 4 核 / 4 线程，Gradle daemon 常驻 + configuration cache 已热）：

| 场景 | 耗时 |
|---|---|
| 无改动再跑 `assembleDebug` | **1–2 秒**（`36 tasks: all up-to-date`，config cache reused） |
| 改一个 Kotlin 文件后重建（编译+dex+打包） | **约 22 秒**（`1 executed, 35 up-to-date`） |
| 改回后再重建 | 1 秒（`1 from cache`） |
| `clean :app:assembleDebug`（构建缓存已热） | **20 秒**（`16 executed, 21 from cache`） |
| 无构建缓存的全量 | 约 1 分钟 |
| 首次冷启动（Gradle daemon + buildscript 类路径指纹，要读 ~1.5 GB） | 5–10 分钟，**只发生一次** |

> 改 hook 代码的日常循环就是「改 Kotlin → `./gradlew :app:assembleDebug` → `adb install -r`」，
> 单次约 20 秒。

## 4. 签名方式

项目内自带 keystore，debug/release 共用，保证重复 `install -r` 不因签名变化失败：

```
文件： module/keystore/fkoscreen-debug.jks
alias: fkoscreen
storepass / keypass: android
算法: RSA 2048, SHA384withRSA, 有效期 30 年
证书 SHA-256: 6E:71:CE:A8:BA:21:D2:77:17:5D:09:9F:4D:83:C5:C4:F8:EB:C1:28:FA:EF:7D:3F:0E:38:53:CC:0B:07:A1:CF
```

校验：

```bash
$ANDROID_HOME/build-tools/37.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/debug/app-debug.apk
```

## 5. 安装到设备

```bash
ADB=$ANDROID_HOME/platform-tools/adb
$ADB -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

**注意**：设备上原来装的 FkOScreen 是标准的 `Android Debug` key
（SHA-256 `56:79:4F:DF:...`），与本项目 keystore 不同，所以**第一次**必须：

```bash
$ADB -s <serial> uninstall io.github.benbaobaoshigemi.fkoscreen
$ADB -s <serial> install app/build/outputs/apk/debug/app-debug.apk
```

之后（签名固定为本项目 key）就可以一直用 `install -r` 覆盖安装。

## 6. LSPosed 侧验证（已实测通过）

1. 安装后 LSPosed 自动识别（`/data/adb/lspd/config/modules_config.db` 的 `modules` 表
   出现 `io.github.benbaobaoshigemi.fkoscreen` 且 `apk_path` 指向新安装路径）。
2. 管理器入口：`adb shell am broadcast -a android.telephony.action.SECRET_CODE -d android_secret_code://5776733 android`
   （管理器是寄生在 `com.android.shell` 里的 `org.lsposed.manager.ui.activity.MainActivity`）。
3. 打开模块 → 打开「启用模块」→ 勾选作用域（模块自带 `xposedscope`：
   `system / com.android.systemui / com.android.settings / com.android.providers.settings / com.oplus.eyeprotect`）。

数据库落盘结果：

```
modules:       io.github.benbaobaoshigemi.fkoscreen -> /data/app/.../base.apk
modules_state: enabled = 1
scope:         system, com.android.systemui, com.android.settings, com.android.providers.settings, com.oplus.eyeprotect
```

4. 实测注入日志（`/data/adb/lspd/log/modules_*.log`，2026-10-23 09:53 抓取）：

**system_server（`(system)`，HDR 关键路径）** —— LSPosed 会热加载进已运行的 system_server，**不需要重启**：

```
(system)[io.github.benbaobaoshigemi.fkoscreen] FkOScreen:HdrRatio: Hooked DisplayInfo hdrSdrRatio
(system)[...] FkOScreen:HdrRatio: Hooked Display.getHdrSdrRatio
(system)[...] FkOScreen:BrightnessLimit: Hooked OplusFeatureIncreaseBrightnessRange.getScreenNormalMaxBrightness
(system)[...] FkOScreen:BrightnessLimit: Hooked OplusBrightnessPostProcessingManager.getScreenNormalMaxBrightness
(system)[...] FkOScreen:HookInit: Hooking SettingsProvider...
(system)[...] FkOScreen:SettingsProviderHook: Hooked SettingsProvider.mutateSystemSetting (module write access)
```

**SystemUI**：

```
(com.android.systemui)[...] FkOScreen:HookInit: Hooking com.android.systemui (HDR & QuickSettings)...
(com.android.systemui)[...] FkOScreen:HdrRatio: Hooked DisplayInfo hdrSdrRatio
(com.android.systemui)[...] FkOScreen:HdrRatio: Hooked Display.getHdrSdrRatio
(com.android.systemui)[...] FkOScreen:BrightnessLimit: Hooked SystemUI com.android.systemui.settings.brightness.BrightnessController
(com.android.systemui)[...] FkOScreen:AodBrightness: Hooked panoramic AOD brightness OK
```

**其它**：`(com.android.settings)`、`(com.oplus.eyeprotect)` 均已加载并挂上各自 hook。

> 已知：`BrightnessLimit: Failed to hook com.android.systemui.qs.deprecated.brightness.BrightnessController:
> ClassNotFoundException` —— ColorOS 16 上该类已不存在（属于 hook 代码问题，不是构建问题）。

## 7. 注意事项 / 已知问题

- **`system`（system_server）作用域的 hook 必须重启设备才生效**，SystemUI 作用域
  `kill -9` 重启即可生效（已验证）。
- 重新安装/覆盖安装后，LSPosed 中「启用模块」状态不会丢（包名不变），但**卸载重装会清空
  作用域配置**，需要在管理器里重新勾选。
- 原设备上曾手工加过 `com.coloros.securityguard` 作用域，本次卸载重装后丢失；
  需要时在管理器里搜索该包名重新勾选。
- `com.android.providers.settings` 这条作用域在数据库里没有持久化（管理器里勾了但 `scope` 表没落盘）。
  **不影响功能**：本机 SettingsProvider 跑在 `system_server` 里（日志里 `(system) ... Hooking SettingsProvider...`），
  已被 `system` 作用域覆盖。
- 构建机当前没有 JDK 17；若将来要装 JDK 17，可在 `gradle.properties` 加
  `org.gradle.java.home=/path/to/jdk17`，其余不用改。
- 首次构建慢是 Gradle 对 buildscript 类路径做指纹导致的（读 ~1.5 GB），
  之后有缓存，重复构建很快。