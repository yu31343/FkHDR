# FkHDR — 独立 HDR 提亮修复模块

> 从 FkOScreen 拆出来的**单一职责**模块：只做一件事 —— 让不支持的设备在播放 HDR 内容时
> 真正把面板推到物理最大亮度，并让系统上报**真实的 HDR/SDR 比值**。

| 项 | 值 |
|---|---|
| 包名 | `io.github.benbaobaoshigemi.fkhdr` |
| 入口 | `io.github.fkhdr.HookInit` |
| 作用域 | **只挂 system_server**（+ SettingsProvider 用于写开关） |
| 依赖 | 无（不用 Compose、不用 androidx） |

---

## 为什么独立

原 FkOScreen 模块把 HDR 提亮和「亮度上限解除 / 深色模式三档 / 色温球 / 144Hz / 护眼」等
十来个 ColorOS 专属功能塞在一起，带来两个问题：

1. **兼容性差**：非 ColorOS 系统（LineageOS / PE / GSI / HyperOS）加载模块时会为每个
   OPPO 私有类打一堆 `ClassNotFoundException`，噪音大、也不好判断到底哪个功能在起作用。
2. **难以分发**：只想修 HDR 的用户被迫装上整个 ColorOS 工具箱。

FkHDR 只保留 HDR 这一条链，并按平台严格分级，不在别的系统上挂不属于它的 hook。

---

## 支持的系统

| 平台 | 判定依据 | 走的通路 |
|---|---|---|
| **ColorOS 16 / 17**（一加 / OPPO / realme 官方 ROM） | 存在 `OplusFeatureConfigManagerService` 或 `com.oplus.content.OplusFeatureConfigManager` | OPPO 原生提亮驱动 `OplusFeatureHDREnhanceBrightness`（自带动画/硬件 clamp，质量最好）+ 后处理兜底 |
| **类原生**（LineageOS / PixelExperience / GSI / HyperOS 等） | 存在 `com.android.server.display.LocalDisplayAdapter` | AOSP 显示栈主动推送：`requestDisplayStateLocked` 改写 + 自算 `handleHdrSdrNitsChanged` |
| **其他** | 都不存在 | 只挂通用链路（DPC / WMS / 媒体会话 / HDR 检测），不碰任何私有类 |

---

## 安装

1. 装 APK
2. LSPosed 里启用，**作用域只勾「系统框架」**（`android`）
   - 若想让界面里的开关免 root 生效，再勾 `com.android.providers.settings`
3. 重启设备（或重启 system_server）

> 与原 FkOScreen 模块**可以共存**：两边逻辑都是幂等的（都把亮度推到同一个上限、
> 都算同一个比值），只是日志会重复。两者共用 `Settings.System:hdr_ratio_fix` 这个开关键，
> 所以关掉任意一个界面里的开关，两边都会关。

---

## 开关

优先级：系统属性 > 模块界面开关。

| 属性 | 默认 | 说明 |
|---|---|---|
| `persist.sys.fkhdr.fix` | 开 | `0` = 关闭整个模块 |
| `persist.sys.fkhdr.factor` | 100 | 提亮倍数，最终由面板 nit 上限截断 |
| `persist.sys.fkhdr.ratio_max` | 自动 | 人工指定 HDR/SDR 比值上限；不设则按面板物理上限 |
| `persist.sys.fkhdr.native` | 1 | `0` = 不用 OPPO 原生驱动 |
| `persist.sys.fkhdr.fallback` | 1 | `0` = 不用通用兜底 |
| `persist.sys.fkhdr.video` | 1 | `0` = 不把视频播放算作 HDR 场景 |
| `persist.sys.fkhdr.hbm` | 1 | `0` = 不强制进 HBM（只提亮到常规最大亮度） |
| `persist.sys.fkhdr.aosp_force` | 0 | `1` = 在 ColorOS 上也强行走 AOSP 通路（调试用） |

**从旧模块迁移**：上述属性会自动回落读取 `persist.sys.fkoscreen.hdr_*` 旧名字，
不需要重新 setprop。

---

## 工作原理（三句话）

- **ColorOS**：模块缺的 3 个 OPPO feature 由模块注入，让系统自带的
  `OplusFeatureHDREnhanceBrightness` 正常进亮度后处理链；它自己算 nit、自己走动画。
- **类原生**：AOSP 的比值闸门 `hasSdrToHdrRatioSpline()` 在本类面板上是 false，
  且该调用点会被 ART 内联（hook 不生效），所以模块**自己算**：
  `brightnessToBacklight → backlightToNits → handleHdrSdrNitsChanged`。
- **类原生 · HBM**：很多 ROM 的 HDR 提亮最终靠 `HighBrightnessModeController` 进 HBM，
  而它的触发条件是 `mIsHdrLayerPresent`（由 SF 的 HDR 图层面积决定）。
  模块在 HDR 期间直接置位并调 `updateHbmMode()`，等效于「改 displayconfig + 删 jar 门禁」
  那类模块，但不需要动任何系统文件。
- **共同点**：HDR 内容出现时把 `brightnessState` 抬到 `1.0`（面板物理最大），
  `sdrBrightnessState` 保留用户亮度；内容结束立刻回落。无白名单，只看内容不看包名。

---

## 日志判读（LSPosed 管理器 → 日志）

启动时应有：

```
FkHDR:HookInit: hooking system_server
FkHDR:HdrFix: init done [platform=... forge=1 dpc=1 media=3 wms=1] factor=100.0
```

类原生上播放 HDR 时应依次出现：

```
FkHDR:HdrFix: brightness hook installed on ...LocalDisplayDevice$1 (1 method)
FkHDR:HdrFix: aosp funnel alive (state=2 brightness=0.39 sdr=0.39 useSurfaceControlBrightness=true)
FkHDR:HdrFix: HDR -> true(focus=true) [driver=null]
FkHDR:HdrFix: aosp push applied brightness=1.0 sdr=0.39 state=2
FkHDR:HdrFix: ratio direct nits=2047.0 sdrNits=800.0 -> ratio=2.56 current=2.56
FkHDR:HdrFix: HDR -> false(focus=false) [driver=null]
FkHDR:HdrFix: aosp push applied brightness=0.39 sdr=0.39 state=2
```

| 现象 | 含义 |
|---|---|
| 没有 `init done` | 模块没被 LSPosed 加载，检查作用域是否勾了系统框架 |
| `aosp ratio gate SKIPPED: no LocalDisplayDeviceExt field` | **正常**，纯 AOSP 没有 OPPO 的 Ext 字段 |
| 有 `HDR -> true` 没有 `aosp push applied` | 主动推送失败，看同行的 `push skipped/failed` |
| `ratio direct nits=.. current=NaN` | `useSurfaceControlBrightness=false`，只有物理提亮 |
| 物理验证 | `adb shell su -c 'dmesg | grep dsi_display_set_backlight'`，HDR 时背光应跳到面板最大值（本机 2047） |

---

## 构建

```bash
cd hdr-module
./gradlew :app:assembleRelease --offline
# 产物：app/build/outputs/apk/release/app-release.apk
```

要求：JDK 21、Android SDK 37、Gradle 9.8.0、AGP 9.4.1、Kotlin 2.4.20。
