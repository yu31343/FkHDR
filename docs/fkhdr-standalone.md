# HDR 功能拆分：独立模块 FkHDR

> ⚠️ 历史记录（截至 v1.6.1）：本文的字段名、版本号、日志样例与实现结论只描述当时的样本；当前 FkHDR v1.6.2 的行为以 [AOSP 对齐记录](aosp-alignment.md) 与 [hdr-module/README.md](../hdr-module/README.md) 为准。

> 2026-10-25。用户确认 v1.9.3 在类原生（LineageOS 23）上生效后，
> 把 HDR 提亮从 FkOScreen 拆成**独立 LSPosed 模块**，解决"兼容性"与"分发"两个问题。

---

## 1. 为什么拆

| 问题 | 说明 |
|---|---|
| 兼容性噪音 | FkOScreen 含 10+ 个 ColorOS 专属功能（亮度解限/深色三档/色温球/144Hz/护眼）。在 LineageOS/PE/GSI/HyperOS 上加载时，每个 OPPO 私有类都会打一条 `ClassNotFoundException`，日志不可读，也无法判断到底哪个功能在起作用 |
| 分发困难 | 只想修 HDR 的用户被迫装整个 ColorOS 工具箱 |
| 责任边界 | HDR 链与其余功能零耦合，混在一起让每次改动都要回归全部功能 |

---

## 2. 拆分结果

| | FkOScreen（原） | **FkHDR（新）** |
|---|---|---|
| 包名 | `io.github.benbaobaoshigemi.fkoscreen` | `io.github.benbaobaoshigemi.fkhdr` |
| 版本 | 1.9.4 / versionCode 20 | 1.0.0 / versionCode 1 |
| 入口 | `io.github.fkoscreen.HookInit` | `io.github.fkhdr.HookInit` |
| 作用域 | system / systemui / settings / eyeprotect | **只有 system**（+ settings provider 用于写开关） |
| 依赖 | Compose + Miuix + androidx | **无**（纯 SDK，APK 703 KB） |
| 功能 | 亮度解限 / 深色三档 / 色温球 / 144Hz / 护眼 / 全景 AOD / HDR | **只有 HDR** |

FkHDR 的 `HookInit` 只有两个分支：

```kotlin
"android"                       -> HdrBoostHook.init + SettingsProviderHook.init
"com.android.providers.settings" -> SettingsProviderHook.init
```

少挂一个进程，就少一份与其他模块/ROM 冲突的可能。

---

## 3. 两个模块如何共存

**FkOScreen 自动让位**：`HookInit` 在挂 HDR 前先检测 FkHDR 是否已安装
（`ActivityThread#getSystemContext().packageManager.getPackageInfo("io.github.benbaobaoshigemi.fkhdr")`）：

```
FkOScreen:HookInit: standalone FkHDR installed -> skip built-in HDR hook (no double boost)
```

- 检测到 → 跳过内建 HDR hook（避免两套 watcher 各自 `checkIsPlayingHDR` / 推亮度）
- 检测失败 → 按"未安装"处理，**宁可重复也不静默丢掉 HDR**

两者共用 `Settings.System:hdr_ratio_fix` 这一个开关键，所以关掉任意一个界面里的开关，两边都会关。

---

## 4. 拆分时顺手做的 4 处加固

| # | 改动 | 原因 |
|---|---|---|
| 1 | 属性名 `persist.sys.fkhdr.*`，**自动回落** `persist.sys.fkoscreen.hdr_*` | 老用户不用重新 setprop |
| 2 | 平台识别加一级兜底：`OplusFeatureConfigManagerService` → `com.oplus.content.OplusFeatureConfigManager` → `LocalDisplayAdapter` | 个别 ColorOS 版本 Service 类名不同，旧逻辑会误判成 AOSP |
| 3 | 亮度内部类**不再写死 `$1`**：先扫 `$1..$9`，扫不到就等 `requestDisplayStateLocked` 返回 Runnable 时按**运行时类**动态挂 | 「第几个匿名类」取决于 ROM 在该类里插了多少匿名实现，写死必挂 |
| 4 | 开关写入失败**显式提示**：写入后回读 `Settings.System`，对不上就 Toast + `Log.i("FkHDR", ...)` | 否则用户看到开关切了、实际没生效，会以为模块坏了 |

---

## 5. 开关通道的真实约束（实测）

在 HyperOS 测试机上（LSPosed 未激活本模块）实测：

```
E DatabaseUtils: SecurityException: ... was not granted this permission: android.permission.WRITE_SETTINGS
I FkHDR   : writeConfig hdr_ratio_fix=0 written=false err=SecurityException: ...
I FkHDR   : writeConfig su fallback failed: java.io.IOException: Cannot run program "su": error=13, Permission denied
```

结论：**开关要走 Settings.System，必须满足以下之一**

1. LSPosed 作用域勾选 `com.android.providers.settings` → `SettingsProviderHook` 放行写入（推荐，无需 root 授权弹窗）
2. 授予本应用 root → `su -c settings put system ...` 兜底

否则开关只是"看起来切了"。界面已对此做回读校验并 Toast 提示。

---

## 6. 交付物

```
<home>/ftp/FkHDR_v1.0.0_release.apk          # 独立 HDR 模块（703 KB）
<home>/ftp/FkOScreen_HDRFix_v1.9.4_release.apk  # 原模块（HDR 自动让位）
```

安装顺序：先装 FkHDR → LSPosed 启用并勾选「系统框架」+「设置存储」→ 重启。
原 FkOScreen 可留可卸；留着会自动让位。
