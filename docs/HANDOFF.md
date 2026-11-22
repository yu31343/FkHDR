# 会话交接文档（HANDOFF）

> ⚠️ 历史记录（截至 v1.6.1）：本文的字段名、版本号、日志样例与实现结论只描述当时的样本；当前 FkHDR v1.6.2 的行为以 [AOSP 对齐记录](aosp-alignment.md) 与 [hdr-module/README.md](../hdr-module/README.md) 为准。

> 最后更新：v1.9.4 / 2026-10-25（HDR 已拆分为独立模块 FkHDR）
> 目的：下次会话 5 分钟内进入状态，不必重新逆向。

---

## 0. 一句话现状

FkOScreen HDR 修复模块在 **ColorOS 16 上图片与视频 HDR 链路全部实测通过**（v1.9.1）。
类原生（LineageOS 23，同硬件）反馈"无效"，已用其 LSPosed 日志闭环定位：
**v1.9.1 的 AOSP 通路有两处静默失效**（注入点永不触发 + 闸门指向 OPPO 私有符号），
v1.9.2 修好注入链（**实测面板背光 21→2047→21，物理提亮已生效**），但比值仍 NaN
（闸门 hook 被 ART 内联）。v1.9.3 改为「自算自调 `handleHdrSdrNitsChanged`」并补
`DisplayInfo` 伪装 + 退出迟滞。全过程见 `docs/lineage-aosp-no-effect.md`。

**v1.9.4（2026-10-25）**：用户确认类原生生效后，HDR 已**拆分为独立 LSPosed 模块
`FkHDR`**（`hdr-module/`，包名 `io.github.benbaobaoshigemi.fkhdr`，APK 703 KB，无 androidx 依赖）。
原模块检测到 FkHDR 已安装时自动跳过内建 HDR hook，避免双重点火。
见 `docs/fkhdr-standalone.md`。

**v1.0.1（2026-10-25）**：解码了设备上的「改配置 + 反编译 jar」类模块
（`hdr_sdr_ratio_fix`），确认其只做了两件事：配置里 `sdrHdrRatioMap` 的 `hdrRatio`
1.0→5.0、jar 里删 `LocalDisplayAdapter.mSupportUltraHDR` 门禁。
FkHDR 已补齐对应通路（新增 `HighBrightnessModeController` hook），
覆盖度分析见 `docs/hbm-module-coverage.md`。

---

## 1. 环境与设备

| 项 | 值 |
|---|---|
| 设备 | OnePlus Pad Pro / OPD2404 / adb serial `<serial>` |
| 面板 | **CSOT-NT36532，LCD 单背光**（`ro.oplus.display.lcd_module=1`） |
| 当前系统 | ColorOS 16 / Android 16 / SDK 36 / `V16.0.0` |
| 用户反馈的类原生系统 | Android 16 / SDK 36 / **`BP4A.251205.006`**（纯 AOSP，同硬件） |
| Root | KernelSU 3.3.1 + LSPosed 2.2.1 (7912) |
| 工作目录 | `<repo>` |
| 模块包名 | `io.github.benbaobaoshigemi.fkoscreen` |

### 关键 adb 命令

```bash
# 模块日志（最重要）
adb -s <serial> shell "su -c 'grep -h HdrFix /data/adb/lspd/log/modules_*.log | tail -20'"

# 显示状态
adb -s <serial> shell dumpsys display | grep -aoE 'hdrSdrRatio [0-9.]+|mScreenBrightness=[0-9.]+|mSdrScreenBrightness=[0-9.]+'

# HDR 内容证据（SF 图层元数据）
adb -s <serial> shell dumpsys SurfaceFlinger | grep -oE 'hdr metadata types=[0-9]+' | sort | uniq -c

# 面板配置探测
adb -s <serial> shell dumpsys display | grep -aE 'mScreenBrightnessRange|mScreenBrightnessNormalMaximum'

# 功耗（判断是否进 HBM）
adb -s <serial> shell "su -c 'cat /sys/class/power_supply/battery/current_now'"

# 唤醒屏幕（探测前必做，否则读数全 0）
adb -s <serial> shell input keyevent KEYCODE_WAKEUP
adb -s <serial> shell wm dismiss-keyguard
```

### 构建

```bash
cd <repo>/module
./gradlew :app:assembleDebug   --offline   # 日常
./gradlew :app:assembleRelease --offline   # 发布
# 产物：module/app/build/outputs/apk/release/app-release.apk
# 发布：cp 到 <release-dir>/FkOScreen_HDRFix_vX.Y.Z_release.apk
```

改模块后**必须重启**（system_server 才重新加载）。

---

## 2. 验证播放器（关键工具，勿删）

`module/player` —— 独立极简播放器，包名 `io.github.fkoscreen.player`。

- `MediaExtractor → MediaCodec → SurfaceView`，**故意不调 `setColorMode`**，模拟普通第三方播放器
- 屏上显示解码 format（tag `HdrProbe`），可证明"HDR 解码是真的"
- 按钮：播 SDR / 播 HDR10 / 停止 / 半屏模式

```bash
adb -s <serial> shell am start -n io.github.fkoscreen.player/.MainActivity
```

按钮坐标（3000x2120 屏）：播 SDR `(367,2836)`、播 HDR10 `(1059,2836)`、停止 `(1752,2836)`、半屏 `(1060,2943)`

测试片源（已在设备）：
- `/sdcard/Movies/hdr10_test.mp4` —— HEVC Main10 + PQ/BT2020 + HDR10 静态元数据
- `/sdcard/Movies/videotest.mp4` —— H.264 + AAC，纯 SDR

**为什么必须有它**：系统播放器（`com.heytap.yoli`）在自动化下经常停在"已加载未播放"，
永远不产生 `hdr metadata types=3`，无法复现 HDR 内容路径。这个播放器是唯一稳定入口。

---

## 3. 架构：平台分级（v1.9.0）

`HdrBoostHook.init()` 先判平台，**只挂对应平台的 hook**，避免跨平台 ClassNotFound 刷日志。

| 平台 | 判定 | 启用 |
|---|---|---|
| `coloros` | 有 `OplusFeatureConfigManagerService` | feature 注入 / 原生驱动 / 后处理兜底 / 白名单解除 / DisplayInfo 伪装 |
| `aosp` | 有 `LocalDisplayAdapter` 但无 OPPO 类 | Ext 闸门 + 亮度峰值注入 |
| `unknown` | 都没有 | 仅通用链路 |

通用链路（三类都挂，全是 AOSP 基础类）：`DisplayPowerController`、`WindowManagerService`、
`MediaSessionStack`、HDR 内容检测、状态机 watcher。

### 为什么 AOSP 路径在 ColorOS 上也能挂上（重要认知）

**ColorOS 就是 AOSP + ExtImpl 插件替换**，AOSP 的类原封不动保留：

```
com.android.server.display.LocalDisplayAdapter$LocalDisplayDevice      ← 存在（AOSP 基类）
com.android.server.display.LocalDisplayAdapter$LocalDisplayDevice$1    ← 存在（匿名 Runnable）
字段 mLocalDisplayDeviceExt                                            ← 存在
    ├─ AOSP   → 运行时类 LocalDisplayDeviceExt（桩，isSupportSdrToHdrRatio 默认 false）
    └─ ColorOS→ 运行时类 LocalDisplayDeviceExtImpl（OPPO 实现）
```

所以 AOSP 路径的 hook **天然可移植**。代码里 `hookExt()` 特意用
`ext.javaClass`（运行时类）而不是写死类名，正是为了通吃两边。

**但这不等于应该让它在 ColorOS 上跑**：ColorOS 的原生驱动质量更好（自带动画、HBM 处理、
rateType），AOSP 注入是"生硬改值"，两者同时跑会互相覆盖。故用平台分级隔离，
仅在 `platform=aosp` 或显式 `persist.sys.fkoscreen.hdr_aosp_force=1` 时启用。

---

## 4. 已确认的根因（不要重复逆向）

### 4.1 ColorOS

| # | 根因 | 解法 |
|---|---|---|
| 1 | 产品配置缺 3 个 OPPO feature | feature 查询层注入 true |
| 2 | 面板无 HDR colorMode + HWC 无 HDR 通路 → SF 不进 HDR 合成 → `checkIsPlayingHDR()` 恒 0 | 自造信号（窗口 colorMode / 媒体会话 + SF 图层元数据） |
| 3 | `Builder.setNit()` 在 `mHasEdrProcessed==false` 时同步抬高 sdrNit → 比率恒 1 | 先 `setHasEdrProcessed()` 保住 SDR 白点 |
| 4 | **C17**：feature 查询改走本地缓存 `OplusFeatureConfigManager#hasFeature → mCache.query()`，不再经过 Service | hook `OplusFeatureConfigManager`/`OplusFeatureCache` + 构造前强制 `mEnhanceHDRBrightnessSupport=true` |

### 4.2 类原生（AOSP）— v1.9.2 重写

> **旧结论已作废**：`mLocalDisplayDeviceExt` / `ILocalDisplayDeviceExt#isSupportSdrToHdrRatio()`
> 是 **OPPO 私有**（oplus-services + ExtLoader）。纯 AOSP 16 **根本没有这两个符号**，
> 所以 v1.9.1 的类原生通路是「hook 一个不存在的字段 → 静默空转」。
> 已用 android16-release 源码逐行核实（LocalDisplayAdapter.java blob 2cad7ed2e9e9）。

| # | 根因（实测确认） | 解法（v1.9.2） |
|---|---|---|
| 1 | AOSP 闸门是 `DisplayDeviceConfig#hasSdrToHdrRatioSpline()`；本机无 spline → 返回 false → `handleHdrSdrNitsChanged` 永不调用 → 比值恒为初始 NaN | hook `DisplayDeviceConfig#hasSdrToHdrRatioSpline` → true（HDR 关时 brightness==sdr，比值自然回 1.0） |
| 2 | **被动注入永远等不到点火**：`requestDisplayStateLocked` 只在「状态或亮度真变化」时才被调用（`DisplayPowerState.setScreenBrightness` 用 `!=` 挡住无变化），而 HDR 起播不产生亮度变化 | HDR 翻转时主动反射调一次 `requestDisplayStateLocked(state, peak, sdr, null)` 并执行返回的 Runnable，造出「变化」 |
| 3 | brightness == sdrBrightness → 比值 1 | 改写 `args[1] = 1.0`（面板物理最大 = HBM 区），保留 `args[2] = 用户亮度` |
| 4 | 旧 `hookExt` 失败时 catch 后静默 return，日志上看不出任何异常 | 找不到字段/方法都明确打日志；新增 `ratio probe` 证明 `handleHdrSdrNitsChanged` 是否真的在跑 |

**AOSP 侧的完整链路（v1.9.2 打通）**：

```
HDR 内容出现
  └─ watcher 判定 hdrOn=true
      ├─ pushAospBrightness(true)
      │    └─ 反射 requestDisplayStateLocked(state=ON, 1.0, 用户亮度, null)
      │         ├─ brightnessChanged = (mBrightnessState != 1.0) = true → 返回 Runnable
      │         └─ Runnable.run() → setDisplayBrightness(1.0, 用户亮度)
      │              ├─ backlight → 面板最大（物理变亮）
      │              ├─ nits / sdrNits 分开上报给 SurfaceFlinger（HDR 有真实 headroom）
      │              └─ hasSdrToHdrRatioSpline()（已 hook 成 true）→ handleHdrSdrNitsChanged
      │                   └─ ratio = max(1, nits/sdrNits) → mInfo.hdrSdrRatio → App 可读
      └─ HDR 结束 → pushAospBrightness(false) → setDisplayBrightness(用户亮度, 用户亮度) → 比值回 1.0
```

**注意 `mUseSurfaceControlBrightness`**：`handleHdrSdrNitsChanged` 内部还要求
`mBacklightAdapter.mUseSurfaceControlBrightness || mForceSurfaceControl` 为 true，否则 ratio 直接置 NaN
（只剩物理提亮）。该值来自 `SurfaceControl.getDisplayBrightnessSupport(token)`。
v1.9.2 在 `aosp funnel alive` 日志里把它打出来，一眼可判。

### 4.3 AOSP 原生 HDR 提亮 API（已查清，尚未接通）

`com.android.server.display.HighBrightnessModeController`：

```java
private static final float DEFAULT_MAX_DESIRED_HDR_SDR_RATIO = 1.0f;  // ← 默认 1.0，等于不提升
private float mMaxDesiredHdrSdrRatio;   // 构造时 = 1.0f
private boolean mHdrBoostDisabled;
boolean mIsHdrLayerPresent;             // 由 SurfaceControlHdrLayerInfoListener 设置

float getHdrBrightnessValue() {
    if (mHdrBrightnessCfg != null) {
        float hdrBrightness = mHdrBrightnessCfg.getHdrBrightnessFromSdr(mBrightness, mMaxDesiredHdrSdrRatio);
        if (hdrBrightness != -1.0f) return hdrBrightness;
    }
    return MathUtils.map(...);          // 回退 = 与 SDR 相同（不提亮）
}
```

而 `DisplayDeviceConfig.getHdrBrightnessFromSdr(brightness, maxRatio)`：
```java
if (sdrToHdrSpline == null) return -1.0f;              // ← 面板没写曲线就直接返回 -1
float ratio = Math.min(sdrToHdrSpline.interpolate(nits), maxDesiredHdrSdrRatio);
```

**结论**：AOSP 原生提亮要求面板在 displayconfig 里声明 `sdr-to-hdr-ratio-spline`。
本机（LCD）没有 → 返回 -1 → 不提亮。且 `getHdrBrightnessValue()` 在本版本 DPC 中
**没有调用点**，说明该通路未接通。故 AOSP 侧采用直接注入方案，未走此 API。

---

## 5. 关键实测数据（基线，用于回归对比）

### 5.1 ColorOS 16 正常亮度（800）

| 场景 | hdrSdrRatio | brightness / sdr |
|---|---:|---|
| SDR | 1.0 | 800 / 800 |
| 图片 HDR | 5.23–7.19 | 2047 / 800 |
| HDR10 视频（全屏） | **7.191125** | 2047 / 800 |
| HDR10 视频（半屏） | **7.191125** | 2047 / 800 |
| SDR 视频 | 1.0 | 800 / 800 |
| 停止 | 1.0 | 800 / 800 |

### 5.2 比值与亮度关系（物理上限档，无人工 cap）

| SDR 亮度 | ratio | brightness / sdr |
|---:|---:|---|
| 201 | 33.03 | 2047 / 201 |
| 400 | 17.807 | 2047 / 400 |
| 800 | 7.191 | 2047 / 800 |
| 1400 | 2.544 | 2047 / 1400 |
| 2047 | 1.0 | 2047 / 2047 |

### 5.3 HBM 判定（功耗法）

| 状态 | current_now | backlight |
|---|---:|---:|
| SDR 800 | −114 mA | 800 |
| SDR 1798（normal max） | 967 mA | 1798 |
| SDR 2047（HBM 区间） | **1783 mA** | 2047 |
| **HDR ON** | **1817 mA** | **2047** |

→ **HDR 已进 HBM，峰值不是问题**。HBM 是 `brightness > 1798` 的自然结果，无需单独控制。

---

## 6. 踩过的坑（务必避开）

| 坑 | 真相 |
|---|---|
| `/sys/kernel/oplus_display/hbm` 写 1 无效果 | **该节点与 HBM 无关**，是误导项；HBM 靠 brightness 区间触发 |
| 屏幕灭时读 backlight/hbm 全是 0 | 探测前必须 `KEYCODE_WAKEUP` + `dismiss-keyguard` |
| 用音频判定视频 | **已否决**：任何媒体播放都命中，误判面太大 |
| 用 MediaSession 播放态判定视频 | 系统播放器恒 PAUSED，不可靠；改用"拥有会话" |
| 用窗口全屏判定视频 | 会漏掉分屏/小窗；**已取消全屏限制** |
| SF `hdr metadata types` 解析写成 `substring(i+20)` | **off-by-one**，字面量长度是 19；个位数被吃掉 → 恒 false。已改正则 |
| 改 `supportedColorModes` 伪装 HDR | 虚标，HWC 会拒绝/花屏风险；且本机 HDR 窗口被 SF 静默降级为 SDR，改了也没用 |
| 系统播放器做 HDR 实验 | 自动化下经常停播，永不产生 HDR 元数据 → 必须用自建播放器 |
| jadx 对 Android jar | jar 里是 `classes*.dex`，需 `unzip -p <jar> 'classes*.dex' | strings` 定位后 `--single-class` |

---

## 7. 开关

| 属性 | 默认 | 作用 |
|---|---|---|
| `persist.sys.fkoscreen.hdr_fix` | 1 | 总开关 |
| `persist.sys.fkoscreen.hdr_native` | 1 | 是否挂 ColorOS 原生驱动 |
| `persist.sys.fkoscreen.hdr_fallback` | 1 | 是否允许通用兜底（0=只走原生） |
| `persist.sys.fkoscreen.hdr_factor` | 100 | 提亮倍数上限 |
| `persist.sys.fkoscreen.hdr_ratio_max` | 空 | 人工指定比值上限；空=面板自适应 |
| `persist.sys.fkoscreen.hdr_video` | 1 | 视频链路开关 |
| `persist.sys.fkoscreen.hdr_aosp_force` | 0 | 强制启用 AOSP 通路（调试用） |

---

## 8. 文件地图

```
module/app/src/main/java/io/github/fkoscreen/
├── HookInit.kt                    # 入口，按包名分发（android / systemui / settings / eyeprotect）
├── ConfigManager.kt               # 模块 UI 配置
├── hooks/
│   ├── HdrBoostHook.kt            # ★ 核心（~900 行）：平台分级 + ColorOS/AOSP 双通路 + 视频检测
│   ├── BrightnessLimitHook.kt     # 亮度上限解除（与 HDR 无关但常一起用）
│   ├── FossBypassHook.kt          # 降亮度白名单绕过
│   └── ...
module/player/                     # ★ 验证播放器（勿删）
hdr-module/                        # ★ 独立 HDR 模块（FkHDR，无 androidx，703 KB）
docs/
├── HANDOFF.md                     # ★ 本文档
├── fkhdr-standalone.md            # ★ HDR 拆分说明 + 开关通道实测约束
├── lineage-aosp-no-effect.md      # ★ 类原生"无效"日志闭环 + v1.9.2 修复 + 判读表
├── FEATURES.md                    # 功能总览 + 实测矩阵
├── c17-root-cause.md              # C17 根因
├── root-cause-framework.md        # AOSP 框架层逆向
└── root-cause-oplus.md            # OPPO 层逆向（最详，含 hook 清单）
analysis/
├── jars/                          # C16 的 4 个 jar（services/oplus-services/framework/oplus-framework）
├── lead/                          # 关键类反编译源码
├── C17_postprocess_report.md      # C17 提亮链逆向
└── c17_hdr_ratio_verification.md  # C17 比率链逆向
```

---

## 9. 待办 / 下一步

| 优先级 | 事项 | 说明 |
|---|---|---|
| **高** | 类原生 v1.9.2 回验 | 让类原生用户装 v1.9.2，按 `docs/lineage-aosp-no-effect.md` §7 的判读表对照日志（看 `aosp push applied` / `ratio probe`） |
| **高** | C17 实机复验 | 需确认日志出现 `forced mEnhanceHDRBrightnessSupport=true` 与 `driver ready` |
| 中 | AOSP 原生 API 接通 | 若目标机型有 `sdr-to-hdr-ratio-spline`，可改走 `HighBrightnessModeController` 正规通路 |
| 中 | 上游 PR | `contrib/` 里的补丁是旧版，需按当前代码重新生成 |
| 低 | 视频链路误判收敛 | 当前"全屏/有会话 + SF 元数据"已较严格，但仍需观察音乐 App 场景 |

### 类原生验证步骤（下次直接照做）

1. 类原生设备接 adb，确认 `adb devices` 可见
2. 装 `FkOScreen_HDRFix_v1.9.0_release.apk`，LSPosed 作用域勾 **系统框架**
3. 重启
4. 看日志：`grep HdrFix` → 应出现 `platform=aosp`、`aosp[dev=.. bright=..]`、`gate=1 probe=1`、
   `aosp ratio gate opened on DisplayDeviceConfig.hasSdrToHdrRatioSpline`、`aosp funnel alive (... useSurfaceControlBrightness=..)`
5. 推测试片源 + 装 `module/player`，播 HDR10，看 `hdrSdrRatio` 是否 > 1、亮度是否上升
6. 播 SDR，确认不动
7. 若失败：抓 `dumpsys display`（看 `supportedColorModes`、`hdrSdrRatio`）+ 模块日志 + `dumpsys SurfaceFlinger | grep 'hdr metadata'`

---

## 10. 硬件事实（决定能力上限，别再纠结）

- 本机是 **LCD 单背光**，物理上无法做到"高光很亮 + 暗部很黑"同时呈现
- HWC **无 HDR 通路**（SF `supportedHdrTypes=INVALID`、`supportedColorModes` 无 BT2100_PQ/HLG）
- 因此模块能对齐的只有**亮度维度**（峰值到面板上限、SDR 白点保留、比率真实上报）
- **动态范围与色域仍是 SDR**，软件无法伪造
- 厂商宣传的"支持 HDR"= 能解码 HDR 内容（tone-map 成 SDR 输出），不是端到端 HDR 输出
