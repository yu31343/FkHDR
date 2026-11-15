# FkOScreen HDR 修复模块 — 功能总览（v1.7.1）

> ⚠️ 历史记录（截至 v1.6.1）：本文的字段名、版本号、日志样例与实现结论只描述当时的样本；当前 FkHDR v1.6.2 的行为以 [AOSP 对齐记录](aosp-alignment.md) 与 [hdr-module/README.md](../hdr-module/README.md) 为准。

> 目标：让 ColorOS 设备在 **面板/HWC 不支持 HDR 合成** 的前提下，仍然把
> 「HDR 峰值亮度 + SDR/HDR 调光比率」做到与正常 HDR 设备一致的**亮度维度**表现。

## 一、解决的三个根因

| # | 根因 | 现象 | 解法 |
|---|---|---|---|
| 1 | 产品配置缺 3 个 OPPO feature | `isSupportSdrToHdrRatio()` 恒 false，HDR 提亮不进后处理链 | 在 feature 查询层注入 true |
| 2 | 面板无 HDR colorMode + HWC 无 HDR 通路 | SF 不进 HDR 合成，`checkIsPlayingHDR()` 恒 0，HDR 信号缺失 | 自己造信号（窗口 colorMode / 媒体会话 + SF 图层元数据） |
| 3 | `Builder.setNit()` 在 `mHasEdrProcessed==false` 时同步抬高 sdrNit | 面板亮了但 `hdrSdrRatio` 恒 1 | `setHasEdrProcessed()` 先置位，保住 SDR 白点 |

## 一之二、平台分级（v1.9.0，严格互斥）

启动时先判定平台，**只挂对应平台的 hook**，避免在其它系统上反复触发
`ClassNotFoundException` 产生无意义错误日志。

| 平台 | 判定依据 | 启用的 hook |
|---|---|---|
| `coloros` | 存在 `OplusFeatureConfigManagerService` | feature 注入、原生驱动、后处理兜底、白名单解除、DisplayInfo 伪装 |
| `aosp` | 存在 `LocalDisplayAdapter` 但无 OPPO 类 | `ILocalDisplayDeviceExt#isSupportSdrToHdrRatio` 闸门 + `setDisplayBrightness` 峰值注入 |
| `unknown` | 两者皆无 | 仅通用链路（DPC / WMS / 媒体会话 / HDR 内容检测） |

通用链路（三类平台都挂，均为 AOSP 基础类）：
`DisplayPowerController`、`WindowManagerService`、`MediaSessionStack`、HDR 内容检测、状态机。

## 二、实现分层

### A. feature 注入（两代 API 都覆盖）
- C16：`OplusFeatureConfigManagerService.hasFeature/hasFeatureMap`
- **C17：查询改走本地缓存** → 改 hook `com.oplus.content.OplusFeatureConfigManager#hasFeature`
  与 `OplusFeatureCache#query`（这是 C17 上唯一有效的注入点）
- 双保险：在 `constructOptionalEnhanceFeatures`(C17) / `addPostprocessFeatures`(C16)
  之前强制 `mEnhanceHDRBrightnessSupport = true`

### B. 原生驱动（首选，画质最好）
驱动 ColorOS 自己的 `OplusFeatureHDREnhanceBrightness`：
接管 `mEnterWhiteApp` / `mEnhanceFactor` / `mNeedToEnhanceBrightness`，
走它自带的 `applyHdrEnhance`（含动画与 `mMaxNit` 截断）。

### C. 通用兜底（不依赖任何 OPPO 类名）
`OplusBrightnessPostProcessingManager#convertNitToBrightness` 之前按面板上限抬 nit：
全部 feature 之后（不会被覆盖）、亮度换算之前（立即生效）。
并主动 `DisplayPowerController.updatePowerState()` 点火（兜底路径本身没有触发器）。

### D. 视频链路（内容驱动，非看窗口尺寸）
三层信号，任何一层成立即提亮，且 **必须拿到 HDR 内容证据**：
1. 焦点窗口 `mAttrs.colorMode == COLOR_MODE_HDR`
2. 前台应用拥有 MediaSession（覆盖“播放器不举手”的情况）
3. **`dumpsys SurfaceFlinger` 图层 `hdr metadata types != 0`** ← HDR 内容的硬证据
   （实证：HDR10 → `dataspace=BT2020_ITU_PQ` + `types=3`；SDR → `V0_SRGB` + `types=0`）
- 事件驱动：`MediaSessionStack.addSession` / `WMS.relayoutWindow` 触发即时重算
- **不要求全屏**：半屏/分屏同样触发

### E. 类原生（AOSP）通路（v1.9.0）

AOSP 的比率链与 ColorOS 同构（ColorOS 就是在 AOSP 上做 ExtImpl）：

```
LocalDisplayAdapter$LocalDisplayDevice$1#setDisplayBrightness(brightness, sdrBrightness)
  ├─ nits    = backlightToNits(brightnessToBacklight(brightness))
  ├─ sdrNits = backlightToNits(brightnessToBacklight(sdrBrightness))
  └─ if (mLocalDisplayDeviceExt.isSupportSdrToHdrRatio() && nits!=0 && sdrNits!=0)
         handleHdrSdrNitsChanged(nits, sdrNits)      ← 唯一比率计算点
```

类原生失效点：`ILocalDisplayDeviceExt#isSupportSdrToHdrRatio()` 默认 false → 比率永不计算。
修复两步：
1. **闸门**：捕获 `LocalDisplayDevice` 实例 → 取 `mLocalDisplayDeviceExt` 的**运行时类** →
   hook `isSupportSdrToHdrRatio` 返回 true。不写死类名，通吃 AOSP 桩类 / OEM Impl。
2. **造差值**：hook `setDisplayBrightness(float,float)`，HDR 内容时把 `args[0]` 抬到面板最大、
   `args[1]` 保留用户亮度。**量纲自适应**：`args[0] > 1.5` 视为绝对量纲（ColorOS 0..2047），
   否则视为 AOSP 归一化（0..1）。

实测（C16 强制 AOSP 模式验证 hook 挂载）：
```
aosp[dev=1 bright=1]
aosp ratio gate opened on com.android.server.display.LocalDisplayDeviceExtImpl (1 method)
```

### F. 白名单彻底解除（C17 新增了按包名取倍率）
| 层 | 处理 |
|---|---|
| `getEnhanceFactorForPackage(pkg)` | hook 为对所有包返回我们的倍率 |
| `onAppExit()` | HDR 期间短路，防止切应用把提亮掐掉 |
| `mEnterWhiteApp/mEnhanceFactor/mNeedToEnhanceBrightness` | 每次 `postProcessing` 前重新压 |
| `existInHdrAppList()` | C16 老白名单绕过 |

## 三、开关

| 属性 | 默认 | 作用 |
|---|---|---|
| `persist.sys.fkoscreen.hdr_fix` | 1 | 总开关（回落到模块界面开关） |
| `persist.sys.fkoscreen.hdr_native` | 1 | 是否挂原生驱动（0=只用兜底） |
| `persist.sys.fkoscreen.hdr_fallback` | 1 | 是否允许兜底（0=只走原生，用于验证） |
| `persist.sys.fkoscreen.hdr_factor` | 100 | 提亮倍数上限，实际由面板 nit 截断 |
| `persist.sys.fkoscreen.hdr_ratio_max` | 空 | 人工指定比值上限；空=按面板自适应（见第七章） |

## 四、实测矩阵（C16 / OPD2404 / Android 16）

| 场景 | 模块判定 | hdrSdrRatio | mScreenBrightness / mSdr | 备注 |
|---|---|---:|---|---|
| SDR 基线 | — | 1.0 | 800 / 800 | |
| 图片 HDR 测试 | `HDR -> true(focus=true)` | 5.23–7.19 | 2047 / 800 | |
| **HDR10 视频 全屏** | `HDR -> true(video playing)` | **7.191125** | **2047 / 800** | SF 有 2×`types=3` |
| **HDR10 视频 半屏** | `HDR -> true(video playing)` | **7.191125** | **2047 / 800** | 同上 |
| **SDR 视频** | `SDR safe, hdrLayer=false` | 1.0 | 800 / 800 | **不触发** |
| 停止播放 | `HDR -> false` | 1.0 | 800 / 800 | 正常回退 |
| 纯原生（`hdr_fallback=0`） | `fallback disabled` | 7.19 | 2047 / 800 | 原生链路可独立工作 |
| 纯兜底（`hdr_native=0`） | `boost nit ...` | 7.19 | 2047 / 800 | 兜底链路可独立工作 |

## 四之二、比值上限：面板自适应分级推导（v1.8.0）

不写死任何数字，三级降级：

| 档 | 来源 | 适用 |
|---|---|---|
| ① | `persist.sys.fkoscreen.hdr_ratio_max` | 人工覆盖 |
| ② | `DisplayDeviceConfig.hasSdrToHdrRatioSpline()` + `getHdrBrightnessFromSdr()` | 厂商为该屏写了 nits→ratio 曲线时自动生效 |
| ③ | `getMaxPanelNit() / sdrNit` | 面板未声明曲线时，交给物理峰值 |

②的换算（与 `hdrSdrRatio` 同量纲）：

```
sdrBrightness = curve("backlights", sdrNit)
hdrBrightness = config.getHdrBrightnessFromSdr(sdrBrightness, MAX)
hdrNit        = curve("nits", hdrBrightness)
cap           = hdrNit / sdrNit
```

**本机结论**：`sdrToHdrSpline=false`（OPPO 未给这块屏写比值曲线）→ 落第③档，
比值由 `面板顶满 nit / SDR 白点 nit` 决定，实测：

| SDR 亮度 | ratio | brightness / sdr |
|---:|---:|---|
| 400 | 17.807 | 2047 / 400 |
| 800 | 7.191 | 2047 / 800 |
| 1400 | 2.544 | 2047 / 1400 |
| 2047 | 1.0 | 2047 / 2047 |

## 四之三、HBM 与峰值亮度的实测结论

用**功耗法**判定（本机 sysfs 的 `hbm` 节点与 HBM 无关，是误导项）：

| 状态 | `current_now` | 背光 `bl` | 结论 |
|---|---:|---:|---|
| SDR 800 | −114 mA（净充电） | 800 | 常规 |
| SDR 1798（normal max） | 967 mA | 1798 | 常规上限 |
| SDR 2047（**HBM 区间**） | **1783 mA** | 2047 | **+816 mA，HBM 生效** |
| **HDR ON** | **1817 mA** | **2047** | **与手动拉满同级 → HDR 已进 HBM** |

**结论**：HBM 不需要单独控制——它是 `brightness > 1798` 的自然结果；
模块把 brightness 顶到 `RangeMaximum=2047`，HBM 就自动激发。
`/sys/kernel/oplus_display/hbm` 写 1 无效且不改背光，属无关节点。

## 五、关键 bug 复盘

**off-by-one**：`subscript("`hdr metadata types=`")` 长度 19，解析写成 `substring(i+20)`，
把个位数掩码吃掉 → `hdrLayer` 恒 false → HDR 视频永远不触发。
已改为 `Regex("hdr metadata types=([0-9]+)")`。

**为什么隐蔽**：系统播放器在本机把 HDR tone-map 成 SDR，永不产生 `types!=0`，
该分支从未被执行；只有自建播放器（`module/player`，直接 `MediaCodec→Surface` 喂 PQ 内容）
才能进入该路径并暴露问题。

## 六、已知边界（须诚实告知）

- 本机是 **LCD 单背光 + HWC 无 HDR 通路**（`ro.oplus.display.lcd_module=1`、`CSOT-NT36532`、
  SF `supportedHdrTypes=INVALID`、`supportedColorModes` 无 BT2100_PQ/HLG）。
- 因此本模块能对齐的只有 **亮度维度**：峰值到面板上限、SDR 白点保留、比率真实上报。
- **动态范围与色域仍是 SDR**，无法通过软件伪造（改 `supportedColorModes` 属虚标，会引发 HWC 拒绝/花屏风险）。
