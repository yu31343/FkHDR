# FkHDR — ColorOS / HyperOS 的 HDR 提亮修复

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Framework](https://img.shields.io/badge/LSPosed-libxposed%20API%20102-blue.svg)](https://github.com/LSPosed/LSPosed)

ColorOS / HyperOS 的显示栈在播放 HDR 内容时不一定提亮面板，也不一定把真实的 HDR/SDR 比值发布给应用。表现就是「HDR 视频看着没比 SDR 亮」「验收工具显示 ❌ 或比率恒为 1.0」。FkHDR 是一个 LSPosed 模块，把断掉的环节接回去：能走厂商原生提亮链就走原生链，走不通时用 AOSP 的比率链路兜底。

> 当前版本 **v1.8.1 / versionCode 35**，模块框架 **libxposed API 102**（LSPosed 2.x）。
> 实现细节：[hdr-module/README.md](hdr-module/README.md)　AOSP 逐条对照：[docs/aosp-alignment.md](docs/aosp-alignment.md)

## 功能

- **面板提亮**：HDR 内容出现时把面板 nit 顶到上限，退出 HDR 时按系统自己的 DPC 动画回落。
- **比值发布**：把真实 `hdrSdrRatio` 发布给应用侧（`Display.getHdrSdrRatio()`、`Display.HdrSdrRatioListener`），退出后回落到 `1.0`。
- **SDR 压暗**：按同一比值压暗 SDR 图层，让 HDR 部分和 SDR 部分亮度一致（过渡走 220ms 斜坡，不是硬切）。
- **平台无关兜底**：ColorOS 的 feature 开关、应用白名单、私有字段全部失配时自动退到 AOSP 兜底链路。
- **不碰危险状态位**：不写 `mSupportUltraHDR` / `mForceSurfaceControl`，也不改面板背光通路 —— 这两条都会让屏幕点不亮（见 [docs/aosp-alignment.md](docs/aosp-alignment.md)）。

## 目录

| 路径 | 内容 |
|---|---|
| [`hdr-module/`](hdr-module) | **当前主线**：FkHDR 模块本体，包名 `io.github.benbaobaoshigemi.fkhdr`，libxposed API 102 |
| [`docs/`](docs) | 逆向与对齐记录：AOSP 基线对照、ColorOS 16/17 根因、历史会话 |
| [`module/`](module) | 历史 legacy 模块（Xposed API 82，FkOScreen 派生）与探针播放器 `module/player/`，只作参考 |
| [`alternatives/`](alternatives) | 未采用的备选方案（KernelSU 文件替换） |
| [`contrib/`](contrib) | 可以直接提给上游 FkOScreen 的补丁与诊断脚本 |

## 安装

1. 装 LSPosed 2.x（libxposed API 102）。
2. `adb install -r fkhdr-v1.8.1.apk`，在 LSPosed 里启用 FkHDR，重启 `system_server` 或整机。
3. 作用域已经静态写在 APK 里（`system` + `com.android.providers.settings`），不用手动勾选。
4. 验证：播放 HDR 视频/图片时 `adb shell dumpsys display | grep -i hdrSdrRatio` 应该是真实比值（如 `5.000007`），停止播放后 2 秒内回到 `1.0`。

> **安装坑**：`adb install -r` 之后 LSPosed 记录的 `apkPath` 可能失效，模块会被静默跳过，日志里是 `Failed to parse package archive .../base.apk`。此时在 LSPosed 里 disable/enable 一次，让它重写路径。

## 构建

需要 JDK 21、Android SDK 37、Gradle 9.8.0、AGP 9.4.1、Kotlin 2.4.20。

```bash
cd hdr-module
echo "sdk.dir=$ANDROID_HOME" > local.properties   # 或用你自己的 SDK 路径
./gradlew :app:clean :app:assembleRelease --console=plain
# 产物：hdr-module/app/build/outputs/apk/release/app-release.apk
```

`hdr-module/keystore/fkoscreen-debug.jks` 是随仓库公开的调试签名（alias `fkoscreen`，口令 `android`），只为让 release APK 能直接覆盖安装升级，不要拿去发布。

## 验收状态

最近一次完整实机验收是 **v1.7.6**，v1.8.0 在同一台平板上复验 SDR 压暗：

| 设备 | 系统 | 结果 |
|---|---|---|
| Redmi 22041216C | Android 15 / HyperOS | 专项链（spline + 比值接管）自算，验收 App 比率 4.37、面板提亮 ✔ |
| 一加平板 Pro OPD2404 | Android 16 / ColorOS | 原生驱动提亮 + GENERIC 兜底，比率 8.51210、逻辑峰值 1728 cd/m²；关掉 HDR 后 2 秒回落 `1.0`；SDR 压暗系数 0.2 覆盖 17 个窗口并复位 |

## 已知边界

- **只对 GPU 合成（CLIENT）的图层压暗**。SurfaceFlinger 的 `colorTransform` 只在 CLIENT 合成时由 GPU 执行，DEVICE 合成的不透明应用窗口、输入法、系统 UI 不会被压暗。让面板级压暗走 HWC 的 `DisplayBrightnessCommand` 在本机不可行（`mForceSurfaceControl` 为 false、dump 里 `displayBrightness=nullopt`），而强制全局 GPU 合成违背「不引入较高性能开销」的目标，所以不做。详见 [docs/aosp-alignment.md §4.11](docs/aosp-alignment.md)。
- 压暗只覆盖登记在 `mWindowMap` 的窗口；'SurfaceView 子图层按设计不压（视频本身要亮），全屏游戏 Surface 的观感未单独验证。
- Android 16 上 `sdrToHdrRatioSpline` 缺失，专项链在 Android 16 未验证，只能走 GENERIC 兜底。
- 面板本身没有 HDR color mode 的机型，天生是「tone-map 到 SDR 容器 + 面板提亮」，不是真正的 HDR 输出链路 —— 这是硬件限制，不是模块能补的。
- 其它 ROM、多显示器实例隔离、仪器化测试都还没做。

## 文档

| 文档 | 内容 |
|---|---|
| [`hdr-module/README.md`](hdr-module/README.md) | 模块当前行为、安装与开关、日志判读、构建与验收记录（**权威**） |
| [`docs/aosp-alignment.md`](docs/aosp-alignment.md) | 每个钩子对应的 AOSP 代码位置与取舍、三个会黑屏的坑、面板级压暗的硬件边界 |
| [`docs/root-cause-framework.md`](docs/root-cause-framework.md) | ColorOS 16（Android 16）HDR/SDR 比率与亮度链路的完整逆向 |
| [`docs/root-cause-oplus.md`](docs/root-cause-oplus.md) | 同一条链路在 OPPO/一加（OPD2404）上的差异 |
| [`docs/c17-root-cause.md`](docs/c17-root-cause.md) | ColorOS 17（Android 17 / SDK 37）提亮失效的根因 |
| [`docs/lineage-aosp-no-effect.md`](docs/lineage-aosp-no-effect.md) | 为什么类原生（LineageOS）上这套链路本来就没被调用 |
| [`docs/hbm-module-coverage.md`](docs/hbm-module-coverage.md) | 设备上现成「改配置 + 反编译 jar」方案的解码与覆盖度对比 |
| [`docs/fkhdr-standalone.md`](docs/fkhdr-standalone.md) | 为什么把 HDR 链从 FkOScreen 里拆成独立模块 |
| [`docs/FEATURES.md`](docs/FEATURES.md) | 功能总览（历史版本口径） |
| [`docs/HANDOFF.md`](docs/HANDOFF.md) | 会话交接记录（历史版本口径） |
| [`docs/history/`](docs/history) | 早期 legacy 模块（`module/`）的验收记录 |

除 `aosp-alignment.md` 与 `hdr-module/README.md` 外，其余 `docs/` 正文都带「历史记录」横幅，只描述当时的样本，不要拿里面的旧数值当当前版本的验收证据。

## 许可

[Apache License 2.0](LICENSE)，Copyright 2026 benbaobaoshigemi。

`module/` 目录衍生自 [FkOScreen](https://github.com/benbaobaoshigemi/FkOScreen)（同样 Apache-2.0）。仓库内不含任何厂商闭源二进制，反编译产物与抓包数据均未入库。
