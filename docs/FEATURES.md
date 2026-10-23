# FkOScreen HDR 修复模块 — 功能总览（v1.7.1）

> 目标：让 ColorOS 设备在 **面板/HWC 不支持 HDR 合成** 的前提下，仍然把
> 「HDR 峰值亮度 + SDR/HDR 调光比率」做到与正常 HDR 设备一致的**亮度维度**表现。

## 一、解决的三个根因

| # | 根因 | 现象 | 解法 |
|---|---|---|---|
| 1 | 产品配置缺 3 个 OPPO feature | `isSupportSdrToHdrRatio()` 恒 false，HDR 提亮不进后处理链 | 在 feature 查询层注入 true |
| 2 | 面板无 HDR colorMode + HWC 无 HDR 通路 | SF 不进 HDR 合成，`checkIsPlayingHDR()` 恒 0，HDR 信号缺失 | 自己造信号（窗口 colorMode / 媒体会话 + SF 图层元数据） |
| 3 | `Builder.setNit()` 在 `mHasEdrProcessed==false` 时同步抬高 sdrNit | 面板亮了但 `hdrSdrRatio` 恒 1 | `setHasEdrProcessed()` 先置位，保住 SDR 白点 |

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

### E. 白名单彻底解除（C17 新增了按包名取倍率）
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
