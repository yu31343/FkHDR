# ColorOS 16 (OPD2404 / OnePlus Pad Pro) HDR 亮度链路逆向报告

> ⚠️ 历史记录（截至 v1.6.1）：本文的字段名、版本号、日志样例与实现结论只描述当时的样本；当前 FkHDR v1.6.2 的行为以 [AOSP 对齐记录](aosp-alignment.md) 与 [hdr-module/README.md](../hdr-module/README.md) 为准。

> 分析对象: `oplus-framework.jar` (10.6 MB) / `oplus-services.jar` (24.7 MB, 从设备 `/system/framework/` 拉取)
> 对照: `services.jar` (AOSP 16) / `framework.jar`
> 设备: OnePlus Pad Pro OPD2404, ColorOS 16.0.1.301(CN01), Android 16
> 反编译: jadx 1.5.6, 输出在 `analysis/oplus/jadx/`
> **结论一句话: ColorOS 把 HDR 提亮做成了 4 条互斥的 feature 分支，OPD2404 的 feature 配置 XML 里 4 条全部没有打开；AOSP 兜底路径又被 `mHbmData == null` 掐死。结果只剩"压暗"没有任何"提亮"。**

---

## 0. 根因结论 (TL;DR)

ColorOS 的 HDR 提亮只有 4 个可能入口，**在这台设备上全部处于关闭状态**：

| # | 提亮路径 | 门控 feature flag | OPD2404 是否存在 | 结果 |
|---|---------|------------------|-----------------|------|
| 1 | AOSP `HighBrightnessModeController` HDR boost | `DisplayDeviceConfig.mHbmData != null` | **否**（dumpsys: `mHbmData=null`） | `getHdrBrightnessFromSdr()` 直接 `return -1.0f` |
| 2 | Oplus `OplusFeatureEdrEnhanceBrightness`（EDR/ProXDR 提亮，主路径） | `oplus.software.display.enhance_brightness_with_uidimming` **或** `oplus.software.display.dolby_enhance_v1_support` | **否**（只有 `dolby_enhance_v1_rm_support`） | `mSupportEdrEnhance=false` → 不进 postprocess 链 |
| 3 | Oplus `OplusFeatureHDREnhanceBrightness`（HDR 白名单 App 提亮） | `oplus.software.display.hdr_enhance_brightness_support` | **否** | 对象都不构造 → 不进 postprocess 链 |
| 4 | Oplus `OplusFeaturePwEdrEnhanceBrightness`（vendor HAL EDR） | `oplus.software.display.game.hdr2.0_enable` (IRIS_EDR) | **否**（设备上是 `hdr3.0_enable` = COLORX_EDR） | `mSupportPwEdrEnhance=false` |

同时 **HDR/SDR ratio 恒为 NaN→1.0** 也是同一个 flag 造成的（见第 4 节），两者互为因果、互相锁死。

而"压暗"是 SurfaceFlinger / HWC 原生侧无条件做的（HDR layer 存在 → SDR 层 dimming），framework 层没有对应的"提亮"补偿，于是用户看到的就是纯压暗。

---

## 1. ColorOS 显示栈结构（文字版）

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  AOSP 骨架 (services.jar)                                                    │
│                                                                              │
│  DisplayManagerService                                                       │
│    └─ DisplayManagerInternalLocal                                            │
│         ├─ getHighestHdrSdrRatio(int displayId)   ◄── OPLUS 覆写点 (Q4)      │
│         │      └─ mDmsExt.getHighestHdrSdrRatio()                            │
│         └─ getDisplayDeviceInfo()                                            │
│                                                                              │
│  LogicalDisplay ──► LocalDisplayAdapter.LocalDisplayDevice                   │
│         ├─ updateDeviceInfoLocked()                                          │
│         │    ├─ mInfo.brightnessMinimum = ddc.getBrightnessMinimum()  //0.016│
│         │    ├─ mInfo.brightnessMaximum = ddc.getBrightnessMaximum()  //1.0  │
│         │    ├─ mInfo.brightnessDefault = oplusModel.getDefaultBrightness()  │
│         │    │                            // 873.10876  ← 单位被 OPLUS 换掉了│
│         │    └─ mInfo.hdrSdrRatio       = mCurrentHdrSdrRatio  // NaN        │
│         ├─ requestDisplayStateLocked() ─► BacklightAdapter.setBrightness()   │
│         │    └─ if (mLocalDisplayDeviceExt.isSupportSdrToHdrRatio())         │
│         │           handleHdrSdrNitsChanged(nits, sdrNits)   ◄── OPLUS 门控  │
│         └─ mLocalDisplayDeviceExt (OPLUS)  = LocalDisplayDeviceExtImpl       │
│                                                                              │
│  DisplayPowerController                                                      │
│    ├─ mBrightnessRangeController (BrightnessRangeController, A16 新增)       │
│    │    ├─ mHbmController : HighBrightnessModeController                     │
│    │    │    ├─ mMaxDesiredHdrSdrRatio  ← onHdrInfoChanged(SF 回调)          │
│    │    │    └─ getHdrBrightnessValue() → ddc.getHdrBrightnessFromSdr(...)   │
│    │    └─ mHdrClamper : HdrClamper                                          │
│    ├─ mDisplayBrightnessController                                           │
│    └─ mDpcExt (OPLUS) = OplusDisplayPowerControllerExtImpl                   │
│         ├─ setupPostProcessLocked()                                          │
│         │    └─ OplusBrightnessPostProcessingManager                         │
│         │         └─ OplusDisplayFeatureManager.postProcessing(builder)      │
│         │              ├─ mDimPolicy                    (只压暗)             │
│         │              ├─ mLowPowerModeBrightness       (只压暗)             │
│         │              ├─ mHDREnhanceBrightness  ◄── flag#3 OFF              │
│         │              ├─ mReduceBrightness             (只压暗)             │
│         │              ├─ mGameBrightness               (只压暗)             │
│         │              ├─ mCameraBrightness                                  │
│         │              ├─ mPwEdrEnhanceBrightness ◄── flag#4 OFF             │
│         │              ├─ mTemperatureLimitBrightness   (只压暗)             │
│         │              ├─ mEdrEnhanceBrightness  ◄── flag#2 OFF              │
│         │              ├─ mGlobalLimitBrightness        (只压暗)             │
│         │              ├─ mLongTakeBrightness / mSinglePulseDimming / ...    │
│         │              └─ mIncreaseBrightnessRange ◄── flag OFF              │
│         ├─ getNitByBrightness() / getBrightnessByNit()  (nit ⇄ brightness)   │
│         └─ setScreenBrightnessRangeMin/Max/NormalMax/Default                 │
└──────────────────────────────────────────────────────────────────────────────┘

单位域：
  Settings.System.screen_brightness (0..8191/0..255)  ──►  Oplus 曲线  ──►  nit (2.0 .. 2047)
  nit  ──►  framework "brightness" 值（ColorOS 把 framework brightness 直接定义成 nit！）
  nit  ──►  backlight 0..1  ──►  硬件 backlight 0..8191 (min 222)
```

### 与 AOSP 的关系（Q1 答案）

**不是继承，是 "ExtImpl 插件" 模式**（OPPO 的 `IOplusXxxExt` 注入框架，配合 `OplusFeatureCache` 服务定位）：

| Oplus 类 | 与 AOSP 的关系 |
|---|---|
| `OplusDisplayPowerControllerExtImpl` | 实现 `IOplusDisplayPowerControllerExt`，被 AOSP `DisplayPowerController` 以 `mDpcExt` 字段持有并回调（hook 点：`setupPostProcessLocked` / `updateBrightness` 前后）。**不是** `DisplayPowerController` 的子类。 |
| `OplusDisplayFeatureManager` | ColorOS 自己的"后处理责任链"，23 个 `OplusDisplayFeature` 实现按顺序改 `OplusDisplayBrightnessState.Builder` 的 nit。AOSP 没有对应物。 |
| `DisplayManagerServiceExtImpl` | 实现 `IOplusDisplayManagerServiceExt`，被 `DisplayManagerService` 持有（`mDmsExt`）。**`getHighestHdrSdrRatio()` 就是它覆写的。** |
| `LocalDisplayDeviceExtImpl` | 实现 `ILocalDisplayDeviceExt`，被 `LocalDisplayAdapter.LocalDisplayDevice` 持有。**`isSupportSdrToHdrRatio()` 在这里。** |
| `OplusAutomaticBrightnessController` | ColorOS 自研自动亮度（ambience 模块），**替换**了 AOSP `AutomaticBrightnessController` 的角色。 |
| `IDisplayBrightnessModelExt`（`OplusDisplayDeviceConfig` 内） | ColorOS 自研亮度模型：nit ⇄ backlight ⇄ color 三条曲线，挂在 `DisplayDeviceConfig.getExtImpl().getBrightnessModel()`。 |
| `OplusBrightnessAmbientAdapter` | ambience（环境光/自适应）适配层。 |
| `OplusFeatureDCBacklight` | 通过 SurfaceFlinger `transact()` 直接和 SF 通信（CODE_GET_HDR_STATE=21012），绕过 AOSP。 |

**不存在** `OplusDisplayPowerController` / `OplusBrightnessService` / `OplusHwDisplay`（后者只用于 RUS/ADFR 面板特性 XML 解析：`feature/panel/hwdpfeatures/OplusHwDisplay*`，与亮度无关）。

---

## 2. HDR / 亮度 / HBM 关键代码（Q2）

### 2.1 HDR/SDR ratio 写入点 —— 决定 NaN 的地方

**`oplus-services.jar` → `com.android.server.display.LocalDisplayDeviceExtImpl`**

```
// LocalDisplayDeviceExtImpl.java:40, :84
private static final String UIDIMMING_FEATURE = "oplus.software.display.enhance_brightness_with_uidimming";
this.mSupportEdrWithUiDimming = OplusFeatureCache.getOrCreate(IOplusFeatureConfigManagerInternal.DEFAULT)
                                     .hasFeature(UIDIMMING_FEATURE);        // ← 设备上 = false

// LocalDisplayDeviceExtImpl.java:102-104
public boolean isSupportSdrToHdrRatio() {
    return this.mSupportEdrWithUiDimming;      // ← 恒 false
}
```

调用方在 AOSP `LocalDisplayAdapter.java:855-892`：

```
// LocalDisplayAdapter.java:855
if (LocalDisplayDevice.this.mLocalDisplayDeviceExt.isSupportSdrToHdrRatio()
        && nits != 0.0f && sdrNits != 0.0f) {
    handleHdrSdrNitsChanged(nits, sdrNits);     // ← 永远不执行
}

// LocalDisplayAdapter.java:874-892
void handleHdrSdrNitsChanged(float displayNits, float sdrNits) {
    float newHdrSdrRatio;
    if (displayNits != -1.0f && sdrNits != -1.0f && (...mUseSurfaceControlBrightness || ...mForceSurfaceControl)) {
        if (BrightnessSynchronizer.floatEquals(displayNits, sdrNits)) {
            newHdrSdrRatio = 1.0f;
        } else {
            newHdrSdrRatio = Math.max(1.0f, displayNits / sdrNits);   // ← 真正算比率的地方
        }
    } else {
        newHdrSdrRatio = Float.NaN;                                   // ← 走这里
    }
    ...
    LocalDisplayDevice.this.mCurrentHdrSdrRatio = newHdrSdrRatio;
    LocalDisplayDevice.this.updateDeviceInfoLocked();
}
```

`mCurrentHdrSdrRatio` 初值就是 `Float.NaN`（`LocalDisplayAdapter.java:244`），所以：

```
// LocalDisplayAdapter.java:714
this.mInfo.hdrSdrRatio = this.mCurrentHdrSdrRatio;   // → NaN
```

→ `dumpsys display` 里 `hdrSdrRatio NaN` / `hdrSdrRatio not_available`，`Display.getHdrSdrRatio()` 回落到 1.0。

### 2.2 系统侧 HDR/SDR ratio 出口 —— 恒 1.0 的那行（Q4 主答案）

**`DisplayManagerServiceExtImpl.java:753-758`**

```
private static final float HIGHEST_HDR_SDR_RATIO = 8.0f;                       // :83

public float getHighestHdrSdrRatio() {
    if (this.mSupportEdrWithUiDimming) {
        return HIGHEST_HDR_SDR_RATIO;      // 8.0f  —— 永远不会走到
    }
    return 1.0f;                           // ★ ColorOS 把 HDR/SDR 比率恒置为 1
}
```

被 AOSP 调用的位置 `DisplayManagerService.java:4708-4713`：

```
public float getHighestHdrSdrRatio(int displayId) {
    if (DisplayManagerService.this.mDmsExt != null) {
        return DisplayManagerService.this.mDmsExt.getHighestHdrSdrRatio();
    }
    return 1.0f;
}
```

### 2.3 AOSP 侧 HDR 提亮公式（被 `mHbmData=null` 掐死）

`DisplayDeviceConfig.java:366-391`：

```
public float getHdrBrightnessFromSdr(float brightness, float maxDesiredHdrSdrRatio) {
    Spline sdrToHdrSpline = this.mHbmData != null ? this.mHbmData.sdrToHdrRatioSpline : null;
    return getHdrBrightnessFromSdr(brightness, maxDesiredHdrSdrRatio, sdrToHdrSpline);
}

public float getHdrBrightnessFromSdr(float brightness, float maxDesiredHdrSdrRatio, Spline sdrToHdrSpline) {
    if (sdrToHdrSpline == null) {
        return -1.0f;                       // ★ 本机走这里，HDR 提亮直接不可用
    }
    float backlight = getBacklightFromBrightness(brightness);
    float nits      = getNitsFromBacklight(backlight);
    float ratio     = Math.min(sdrToHdrSpline.interpolate(nits), maxDesiredHdrSdrRatio);  // ← 双重上限
    float hdrNits   = nits * ratio;
    float hdrBacklight = Math.max(mBacklightMinimum, Math.min(mBacklightMaximum, getBacklightFromNits(hdrNits)));
    return getBrightnessFromBacklight(hdrBacklight);
}
```

`HighBrightnessModeController.java`：
- `:80`  `mMaxDesiredHdrSdrRatio = 1.0f`
- `:134-136`  `getHdrBrightnessValue()` → `mHdrBrightnessCfg.getHdrBrightnessFromSdr(mBrightness, mMaxDesiredHdrSdrRatio)`
- `:469-497`  `onHdrInfoChanged(displayToken, numberOfHdrLayers, maxW, maxH, flags, maxDesiredHdrSdrRatio)`：

```
  if (mIsHdrLayerPresent && mHdrBrightnessCfg != null) candidateDesiredHdrSdrRatio = maxDesiredHdrSdrRatio;
  else                                                  candidateDesiredHdrSdrRatio = 1.0f;
  if (candidateDesiredHdrSdrRatio < 1.0f) candidateDesiredHdrSdrRatio = 1.0f;
  mMaxDesiredHdrSdrRatio = candidateDesiredHdrSdrRatio;
```

**双保险：ratio 被 ColorOS 压成 1.0，spline 又是 null。即便修好 ratio，`getHdrBrightnessFromSdr` 仍然返回 -1.0f。**

### 2.4 ColorOS EDR 提亮主路径（被 feature flag 关掉）

**`OplusFeatureEdrEnhanceBrightness`**（1070 行，`com.android.server.display.feature.postprocess`）

特征串：

```
:54   DOLBY_ENHANCE_FEATURE   = "oplus.software.display.dolby_enhance_v1_support"
:68   UIDIMMING_FEATURE       = "oplus.software.display.enhance_brightness_with_uidimming"
:155  ctor:
        mSupportEdrWithUiDimming = hasFeature(UIDIMMING_FEATURE);       // false
        mSupportEdrNoUiDimming   = hasFeature(DOLBY_ENHANCE_FEATURE);   // false
        mSupportEdrEnhance       = mSupportEdrWithUiDimming || mSupportEdrNoUiDimming;  // false
```

核心提亮代码：

```
// :279-308  postProcessing
public void postProcessing(OplusDisplayBrightnessState.Builder builder) {
    ...
    float res = enhanceBrightness(this.mDisplayId, this.mNit, builder);
    if (Float.compare(res, this.mNit) > 0) {
        AmbienceLog.d(mTag, "ProXdrEnhance nit:" + mNit + " --> " + res);
        builder.setHasEdrProcessed().setNit(res).setEdrType(mBacklightType).setReason(mTag);
    }
    builder.setEdrSence(isEdrSence());
    setEdrRateWithType(builder);
}

// :327-351  enhanceBrightness  —— 唯一的"乘系数提亮"公式
public float enhanceBrightness(int displayId, float nit, OplusDisplayBrightnessState.Builder builder) {
    DisplayManagerInternal.DisplayPowerRequest request = builder.getPowerReq();
    if (request == null) return nit;
    if (request.policy != 3 && request.policy != 1) return nit;   // 只在 ON(1)/VR(3) 下生效
    float adjustNit = nit;
    if (this.mNeedToEnhanceBrightness) {
        if (this.mIsPlayingEdr == 1) {
            adjustNit = clampEnhanceNit(
                getRealEnhanceFactor(displayId, this.mEnhanceFactor, adjustNit, this.mBacklightType) * adjustNit);
        }
        this.mIsEnhancedStatus = true;
    } else {
        this.mIsEnhancedStatus = false;
    }
    ...
}

// :498-539  EdrListener.onEdrInfoChanged —— 系数来自 SurfaceFlinger
public void onEdrInfoChanged(IBinder displayToken, ArrayList<EdrLayerInfo> infos) {
    ...
    for (EdrLayerInfo info : infos) {
        tempToggle        |= info.getToggle();          // 1 = 有 EDR/HDR 层
        tempBacklightType |= info.getBacklightType();   // 2=Dolby 8=Aurora 32=Game 16=HdrVision 128=Preview
        maxScale           = max(maxScale, info.getScale());
    }
    mEnhanceFactor = maxScale;                          // ★ 提亮系数 = SF 给的 EDR scale
    mIsPlayingEdr  = tempToggle;
    mBacklightType = tempBacklightType;
    sendEdrBrightnessEnhancingMessage();                // → enterEnhanceBrightness()
}

// :403-406  clampEnhanceNit —— 上限是 getMaxPanelNit()
private float clampEnhanceNit(float nit) {
    return Math.min(getMaxNitForPreview(setSpecialSceneNit(nit, mBacklightType), mBacklightType),
                    getBrightnessModel().getMaxPanelNit());
}
```

**关键洞察**：`mEnhanceFactor` 完全来自 SurfaceFlinger 的 `EdrLayerInfo.getScale()`。而 SF 计算 scale 的依据就是显示器的 HDR/SDR ratio —— 也就是 framework 报上去的那个 NaN/1.0。**所以 ratio 恒 1 不仅让 AOSP 不提亮，也让 ColorOS 自己的 EDR 提亮系数恒为 1。**

### 2.5 ColorOS HDR 白名单提亮（被 flag 关掉）

**`OplusFeatureHDREnhanceBrightness`**（428 行）

```
:40   CODE_GET_HDR_STATE = 21012;                       // 直接问 SurfaceFlinger
:114  mEnhanceHDRBrightnessSupport = OplusFeatureConfigManager.getInstacne()
                                        .hasFeature("oplus.software.display.hdr_enhance_brightness_support");  // false
:115  if (mEnhanceHDRBrightnessSupport) { init(); }     // ★ 连 init 都不跑

// :126-152  postProcessing
if (this.mEnhanceHDRBrightnessSupport && needHandleDisplayId(builder.getDisplayId())) {
    ...
    if (this.mNeedToEnhanceBrightness && this.mEnterWhiteApp
            && this.mBatteryLevel > 20 && !this.mPowerSavingMode) {     // 4 个前置条件
        this.mNit = this.mEnterNit * this.mEnhanceFactor;                // ★ 提亮 = nit × factor
        this.mRate = getBrightnessModel().getBrightnessRampRateSlow();
        if (this.mNit > this.mMaxNit && this.mMaxNit > 0.0f) this.mNit = this.mMaxNit;   // mMaxNit = getMaxPanelNit()
        builder.setHdrHbmBaseNit(this.mEnterNit).setNit(this.mNit).setReason(TAG);
        this.mIsEnhancedStatus = true;
        return;
    }
}

// :235-268  checkIsPlayingHDR —— 触发条件
Parcel data = Parcel.obtain(); ... mFlinger.transact(CODE_GET_HDR_STATE, data, reply, 0);
this.mIsPlayingHDR = reply.readInt();
if (mIsPlayingHDR == 1 && mEnterWhiteApp && mBatteryLevel > 20 && !mPowerSavingMode) enterEnhanceBrightness();
else                                                                                 exitEnhanceBrightness();
```

**触发条件汇总**（若 flag 打开）：SF 报 HDR=1 + 当前前台 App 在 `EnhanceHDRBrightnessConfig` 白名单 + 电量>20% + 非省电模式 + display 0 + policy=ON。系数 `mEnhanceFactor` 来自全局配置 XML 每个 App 的 `mFactor`。

### 2.6 vendor HAL EDR 提亮（被 flag 关掉）

**`OplusFeaturePwEdrEnhanceBrightness`**（456 行），门控 `oplus.software.display.game.hdr2.0_enable`（`OplusDisplayFeatureManager:34` 的 `PW_EDR_FEATURE`）。回调 `onEdrBrightnessChange(boolean edrOn, boolean hasAnimation, int backlightScale)`（`:345`），`mBackLightScale` 只取 20/25/30 三档（`:220-228`），`builder.setEdrType(4).setNit(res)`（`:133`）。

### 2.7 HBM 相关（本机走 ColorOS 自己的一套）

`OplusDisplayPowerControllerExtImpl.java:147`：

```
private boolean mGlobalHbmSupported =
    OplusFeatureConfigManager.getInstacne().hasFeature("oplus.software.display.screen_gloablehbm_support");  // true
```

`OplusDisplayPowerControllerExtImpl.java:1322-1325`（把 AOSP 的亮度区间改写成 nit 区间）：

```
mDpc.getWrapper().setScreenBrightnessRangeMinimum(mBrightnessModel.getMinBrightness());       // 2.0
mDpc.getWrapper().setScreenBrightnessRangeMaximum(mBrightnessModel.getTotalBrightness());     // 2047.0
mDpc.getWrapper().setScreenBrightnessNormalMaximum(mBrightnessModel.getMaxBrightness());      // 1798.0
mDpc.getWrapper().setScreenBrightnessDefault(mBrightnessModel.getDefaultBrightness());        // 873.10876
```

**ColorOS 完全不用 AOSP 的 HBM 状态机**（`mHbmData=null`），而是把 `RangeMaximum` 抬到 `getTotalBrightness()`（2047）来实现"全局 HBM"。`HighBrightnessModeController` 因此永远进不了 `HBM_MODE_HDR`（mode 2）。

---

## 3. ColorOS 亮度模型：Settings → 面板背光（Q3）

### 3.1 完整链路

```
Settings.System.screen_brightness  (int, 0..8191 / 0..255)
        │
        ▼
OplusAutomaticBrightnessController / BrightnessMappingStrategy
        │  (ColorOS ambience 曲线：lux → brightness_level → nit)
        ▼
DisplayPowerController 计算 "brightness"  float
        │   ★ ColorOS 把 AOSP 的 brightness 语义改成了 "nit"
        ▼
OplusDisplayPowerControllerExtImpl.updateBrightness()   (:889-893)
        │   float dpcNit = getNitByBrightness(brightness);       // curve "backlights" : brightness→nit
        │   builder.setNit(dpcNit).setDpcNit(dpcNit)...
        ▼
OplusBrightnessPostProcessingManager.postProcessing(builder, rate)   (:31-52)
        │   mDisplayFeatureManager.postProcessing(builder);      // ← 23 个 feature 改 nit
        │   builder.setNit(mBrightnessModel.clampNit(builder.getNit()));
        │   convertNitToBrightness(builder);                      // curve "nits" : nit→brightness
        ▼
OplusDisplayFeatureManager.postProcessing(builder)   (责任链，见第 1 节图)
        ▼
dpc.getWrapper().animateScreenBrightness(brightness, sdrBrightness, rate)   (:903)
        ▼
LocalDisplayDevice.requestDisplayStateLocked(brightnessState, sdrBrightnessState)
        │
        ├─ LocalDisplayDeviceExtImpl.setDisplayBrightness()   (:111-140)
        │     nits      = brightnessToNits(brightnessState)      // model.getNitFromBrightness
        │     backlight = getBacklightFromBrightness(brightnessState)
        │     scale     = getEnhanceDolbyScale(nits, sdrNits)    // ★ Dolby 提亮系数 = nits/sdrNits
        │     mBacklightAdapter.setBacklight(sdrBacklight, sdrNits, backlight, nits,
        │                                     color, scale, sdrNits, mCurrentBacklightType, false)
        ▼
SurfaceFlinger / HWC  →  面板背光 (raw 0..8191, min 222)
```

### 3.2 亮度映射表（真机文件）

`/system_ext/etc/display_brightness_config_default.xml`：

```
<brightness_table max="8191" min="222">
    <level>0   ,0   ,0      ,1.00000</level>     <!-- index, ?, nit, ? -->
    <level>1   ,1   ,2.00000,1.00000</level>
    <level>2   ,4   ,2.00419,1.00000</level>
    ...
```

第 3 列就是 **nit**（从 2.0 起），第 1/2 列是 raw backlight（0..8191，min 222）。

`/system_ext/etc/display_brightness_config_common.xml`（lux → nit 曲线，自适应亮度用）：

```
<lux_table id="0">  0,4   1,7   2,10  3,13  8,31  16,48  36,82  60,83  100,86
                    260,97  540,120  1000,169  2000,273  3500,362  5900,418  7000,430
<hbm_lux_table id="0">  10101,505   20101,550   30101,600   40101,600
```

### 3.3 "HDR 模式下自动拉高亮度"的逻辑在哪？触发条件是什么？

**AOSP 路径**：`HighBrightnessModeController.getHdrBrightnessValue()` → `DisplayDeviceConfig.getHdrBrightnessFromSdr(sdrBrightness, mMaxDesiredHdrSdrRatio)`
- 触发：`HbmStateReceiver.onHdrInfoChanged(... maxDesiredHdrSdrRatio)`（SF 回调）→ `mIsHdrLayerPresent && mHdrBrightnessCfg != null` → 算 `ratio = min(sdrToHdrSpline.interpolate(nits), maxDesiredHdrSdrRatio)` → `hdrNits = nits * ratio`
- **本机被挡**：`mHbmData == null` → `sdrToHdrRatioSpline == null` → `return -1.0f`

**ColorOS 路径**：`OplusFeatureEdrEnhanceBrightness.enhanceBrightness()`（`:327`）
- 触发：`SurfaceControlEdrLayerInfoListener.onEdrInfoChanged()` → `mIsPlayingEdr==1 && mNeedToEnhanceBrightness` → `adjustNit = factor * nit`
- **本机被挡**：`mSupportEdrEnhance == false` → 对象不进 `mPostProcessFeatures`（`OplusDisplayFeatureManager:330-332`）

**ColorOS 白名单路径**：`OplusFeatureHDREnhanceBrightness.postProcessing()`（`:135`）
- **本机被挡**：`mEnhanceHDRBrightnessSupport == false` → 不进链（`:315-317`）

**结论：HDR 提亮在 ColorOS 里应该在 `OplusDisplayFeatureManager` 的 postprocess 责任链中被触发（`OplusFeatureEdrEnhanceBrightness` 为首选），而它因为 feature flag 没进链 —— 这就是"为什么没触发"。**

---

## 4. `hdrSdrRatio` 恒置 1 的具体代码位置（Q4 汇总）

三处，同一根因（`oplus.software.display.enhance_brightness_with_uidimming` 缺失）：

| # | 位置 | 行为 |
|---|------|------|
| ① | `LocalDisplayDeviceExtImpl.isSupportSdrToHdrRatio()` `(LocalDisplayDeviceExtImpl.java:102-104)` | 返回 false → `handleHdrSdrNitsChanged()` 永不调用 → `mInfo.hdrSdrRatio` 永远 `NaN` |
| ② | `DisplayManagerServiceExtImpl.getHighestHdrSdrRatio()` `(DisplayManagerServiceExtImpl.java:753-758)` | `return 1.0f`（本应 `8.0f`） |
| ③ | `LocalDisplayDeviceExtImpl.mSupportEdrWithUiDimming` 同时被 `DisplayManagerServiceExtImpl:175` 和 `LocalDisplayDeviceExtImpl:84` 读取 | 一处 flag 决定全部三处行为 |

`hdrSdrRatio = NaN` 在 framework 里被 `DisplayInfo.getHdrSdrRatio()` 回落成 1.0f（`Float.isNaN` 判断），所以 App 侧 `display.getHdrSdrRatio()` 恒 1.0 —— 与用户观察完全一致。

**FkOScreen 现在 hook `DisplayInfo.getHdrSdrRatio()` 返回 1.5625 只能压暗、不能提亮的原因**：这个值只影响 **App 侧 / SF 侧对 SDR 内容做除法压暗**（`sdrBrightness = brightness / ratio`），它**不进入** `DisplayManagerServiceExtImpl.getHighestHdrSdrRatio()`（那是 `mDmsExt` 直接返回的常量），也**不进入** `DisplayDeviceConfig.getHdrBrightnessFromSdr()`（那条路径被 `mHbmData=null` 堵死）。所以只拿到"压暗"一半。

---

## 5. 亮度单位之谜（Q5）

### 5.1 单位定义

**ColorOS 把 framework 的 `brightness` 语义改成了"名义 nit"**：

| 量 | 值 | 单位 | 来源 |
|---|---|---|---|
| `mScreenBrightnessRangeMinimum` | 2.0 | nit | `model.getMinBrightness()` |
| `mScreenBrightnessNormalMaximum` | 1798.0 | nit | `model.getMaxBrightness()`（SDR 正常上限） |
| `mScreenBrightnessRangeMaximum` | 2047.0 | nit | `model.getTotalBrightness()`（含全局 HBM） |
| `model.getDefaultBrightness()` | 873.10876 | nit | 曲线默认值 |
| `DisplayDeviceConfig.mBacklightMinimum/Maximum` | 0.016 / 1.0 | 归一化背光 | AOSP 原生（硬件 backlight 0..1） |
| 硬件 raw backlight | 222 .. 8191 | raw 13-bit | `display_brightness_config_default.xml` |
| `HdrCapabilities.mMaxLuminance` | 540.0 | 实测 nit | 面板 HDR 峰值（EDR 参考） |

### 5.2 `dumpsys display` 里那三个"自相矛盾"的数

```
brightnessMinimum 0.016, brightnessMaximum 1.0, brightnessDefault 873.10876
```

来自 `LocalDisplayAdapter.updateDeviceInfoLocked()`（`LocalDisplayAdapter.java:693-714`）：

```
this.mInfo.brightnessMinimum = getDisplayDeviceConfig().getBrightnessMinimum();   // 0.016  ← 背光归一化域
this.mInfo.brightnessMaximum = getDisplayDeviceConfig().getBrightnessMaximum();   // 1.0    ← 背光归一化域
if (mStaticDisplayInfo.isInternal) {
    IDisplayBrightnessModelExt model = ...getBrightnessModel();
    if (model != null) this.mInfo.brightnessDefault = model.getDefaultBrightness(); // 873.10876 ← nit 域  ★
} else {
    this.mInfo.brightnessDefault = getDisplayDeviceConfig().getBrightnessDefault(); // 0.75（AOSP 原值）
}
```

**不是矛盾，是 ColorOS 补丁引入的单位混用**：
- `brightnessMinimum/Maximum` 仍走 AOSP 的 `DisplayDeviceConfig.getBrightnessMinimum/Maximum()` = `getBrightnessFromBacklight(0.016/1.0)` → **归一化背光 [0,1]**；
- `brightnessDefault` 被 OPPO 换成了 `IDisplayBrightnessModelExt.getDefaultBrightness()` → **nit（873.10876）**。
- 三者在 `DisplayDeviceInfo` 里被并排打印，看起来"最小 0.016、最大 1.0、默认 873"，实际是两个单位域拼在一起。

### 5.3 `Display Brightness=2.0` 是什么

`dumpsys display` 同时给出：

```
mPendingRequestLocked=policy=DIM
Display Brightness=2.0          ← mPowerState.getScreenBrightness()，即当前实际下发值
mCachedBrightnessInfo.brightness=889.0          ← 用户/曲线目标（nit）
mCachedBrightnessInfo.adjustedBrightness=2.0    ← 经 dim 后的实际值
mScreenBrightnessRangeMinimum=2.0
```

抓取时屏幕处于 **DIM（policy=DIM）**，`adjustedBrightness` 被压到下限 2.0。**这是 dim 状态，不是 HDR 现象**，与 HDR 无关。

### 5.4 那个 `1.0` 上限是不是把 HDR 提亮卡死了？

**不是。** `brightnessMaximum = 1.0` 是**归一化背光上限**，它对应的 nit 是 2047（`mScreenBrightnessRangeMaximum`）。真正卡死 HDR 提亮的是另外三件事：

1. `DisplayDeviceConfig.mHbmData == null` → `sdrToHdrRatioSpline == null` → `getHdrBrightnessFromSdr()` 返回 `-1.0f`（AOSP 路径彻底不可用）；
2. `getHighestHdrSdrRatio()` 恒 1.0（ColorOS 覆写）；
3. 4 条 ColorOS 提亮 feature 全部未启用。

**但** 有一个真实的"上限"需要注意：即使打开提亮，`OplusFeatureEdrEnhanceBrightness.clampEnhanceNit()` 会 `Math.min(..., getBrightnessModel().getMaxPanelNit())`，而 `OplusFeatureHDREnhanceBrightness` 也用 `mMaxNit = getBrightnessModel().getMaxPanelNit()` 封顶。**`getMaxPanelNit()` 才是真正的提亮天花板**，需要确认它等于 `getTotalBrightness()`(2047) / `getMaxBrightness()`(1798) / 还是更低（540 HDR 峰值）。

---

## 6. ColorOS 专有 HDR / 画质 / 护眼 会干预亮度的开关（Q6）

### 6.1 Oplus feature flags（`/my_product/etc/permissions/*.xml`）

**已开启（本机存在）**：

| Feature | 作用 |
|---|---|
| `oplus.software.display.screen_gloablehbm_support` | 全局 HBM（把 RangeMaximum 抬到 2047） |
| `oplus.software.display.local_hbm_support` | 局部 HBM |
| `oplus.software.display.lhdr_only_dimming_support` | **"LHDR only support dimming, brightening by animation mode"** ← 明确写着只支持压暗 |
| `oplus.software.display.dolby_enhance_v1_rm_support` | Dolby 增强（`_rm` 变体，**代码查的是无 `_rm` 的 `dolby_enhance_v1_support`，不匹配！**） |
| `oplus.software.display.game.hdr3.0_enable` | ColorX EDR（`FEATURE_COLORX_EDR_SUPPORT`）。**代码查的是 `hdr2.0_enable`（IRIS_EDR），不匹配！** |
| `oplus.software.display.multibits_dimming_support` | 多位调光 |
| `oplus.software.display.autobrightctl_animation_support` | 自动亮度动画 |
| `oplus.software.display.brightness_mode_automatic` | 自动亮度模式 |
| `oplus.software.display.peak_brightness_test_support` | 峰值亮度测试 |
| `oplus.software.display.screen_calibrate_100apl` | 100APL 校准 |

**缺失（= 提亮路径全灭）**：

| Feature | 代码位置 |
|---|---|
| `oplus.software.display.hdr_enhance_brightness_support` | `OplusDisplayFeatureManager:104`, `OplusFeatureHDREnhanceBrightness:114` |
| `oplus.software.display.enhance_brightness_with_uidimming` | `DisplayManagerServiceExtImpl:88/175`, `LocalDisplayDeviceExtImpl:40/84`, `OplusDisplayFeatureManager:101`, `OplusFeatureEdrEnhanceBrightness:68` |
| `oplus.software.display.dolby_enhance_v1_support`（无 `_rm`） | `OplusDisplayFeatureManager:102`, `OplusFeatureEdrEnhanceBrightness:54` |
| `oplus.software.display.game.hdr2.0_enable` | `OplusDisplayFeatureManager:34`（PW_EDR_FEATURE） |
| `oplus.software.display.pwm_switch.support` | `OplusDisplayFeatureManager:33` |
| `oplus.software.display.sec_max_brightness_rm` | `OplusDisplayFeatureManager:105`（`mIncreaseBrightnessRange`） |

### 6.2 Settings keys（会改亮度/EDR 行为）

| Key | 域 | 作用 |
|---|---|---|
| `screen_brightness` | System | 主亮度 |
| `screen_brightness_mode` | System | 0=手动 1=自动 |
| `low_power` | Global | 省电模式（**HDR 提亮的硬性否决条件**，`OplusFeatureHDREnhanceBrightness:135`） |
| `dolby_hdrvision_enter_rate` | Secure | HdrVision 进入 ramp rate |
| `dolby_dynamic_enter_rate` / `dolby_dynamic_exit_rate` | Secure | Dolby 动态进出 rate |
| `edr_dynamic_enter_duration` / `edr_dynamic_exit_duration` | Secure | EDR 进出时长 |
| `max_nit_for_preview` | Secure | Preview 场景 nit 上限（默认 800） |
| `oplus_customize_fingerprint_unlock_switch` | Secure | 指纹解锁时限制 EDR nit |
| `oplus_customize_eye_protect_enable` | Secure | 明眸护眼总开关 |

### 6.3 System properties

| Prop | 本机值 | 作用 |
|---|---|---|
| `ro.display.brightness.dobly.enter.rate` | 未设(-1) | Dolby 进入 rate 覆盖 |
| `ro.display.brightness.dobly.exit.rate` | 未设(-1) | Dolby 退出 rate 覆盖 |
| `ro.display.brightness.edr.enter.duration` / `.exit.duration` | 未设(-1) | EDR 进出时长覆盖 |
| `ro.oplus.display.brightness.min_settings.rm` | 未设 | 非空则启用 `OplusFeatureMinAutoBrightness` |
| `ro.oplus.display.aod.brightness` | 1.0 | AOD 亮度 |
| `ro.oplus.dc.brightness.threshold` | 0 | DC 调光阈值 |
| `ro.surface_flinger.has_HDR_display` | true | SF 有 HDR 显示 |
| `ro.vendor.oplus.hdr.uniform` | 1 | Oplus HDR 统一处理 |
| `ro.vendor.oplus.dolby_vision_dpu` | 1 | Dolby Vision DPU |
| `persist.sys.oplus.display.brightness.mode` | 0 | 亮度模式 |
| `ro.oplus.display.lcd_module` | 1 | LCD 模组（非 OLED）→ 无真正像素级 EDR，只能靠背光 |

### 6.4 明眸护眼 / 画质增强（`com.android.server.display.color` 包）

`ColorDisplayServiceExtImpl`, `OplusEyeProtectManager`, `OplusReadModeManager`, `OplusPaperTextureManager`, `AiEyeProtectManagerController`, `OplusVisionCorrectionManager`, `OplusFeatureColorMode`, `OplusFeatureGameColorEnhance`, `OplusFeatureWhitePoint`。
这些通过 `DisplayTransformManager` 改色彩矩阵，**不直接改 nit**，但 `OplusReduceSaturationUtil` / `PaperTextureRender` 在 HDR 场景下会叠加 SDR 压制。

---

## 7. 可 hook 点清单（类 + 方法签名 + 期望效果）

> 全部基于 `oplus-services.jar` / `oplus-framework.jar`，LSPosed 作用域需包含 `android`（system_server）。

### P0 —— 打开提亮主路径（推荐）

| # | 类 | 方法签名 | 期望效果 |
|---|---|---|---|
| H1 | `com.android.server.display.DisplayManagerServiceExtImpl` | `public float getHighestHdrSdrRatio()` | 返回 `8.0f`（或 `3.0f`）而非 1.0。让 AOSP `HdrClamper`/`HighBrightnessModeController` 拿到非 1 比率 |
| H2 | `com.android.server.display.LocalDisplayDeviceExtImpl` | `public boolean isSupportSdrToHdrRatio()` | 强制 `return true`。让 `handleHdrSdrNitsChanged()` 执行，`mInfo.hdrSdrRatio` 从 NaN 变成真实值（`nits/sdrNits`） |
| H3 | `com.android.server.display.feature.postprocess.OplusDisplayFeatureManager` | 字段 `mSupportEdrEnhance` / `mSupportEdrWithUiDimming` / `mEnhanceHDRBrightnessSupport` | 反射置 `true`，让 `mEdrEnhanceBrightness` / `mHDREnhanceBrightness` 进入 `mPostProcessFeatures`（需在 `addPostprocessFeatures()` 之前，即构造函数早期） |
| H4 | `com.android.server.display.feature.postprocess.OplusFeatureEdrEnhanceBrightness` | `public float enhanceBrightness(int displayId, float nit, OplusDisplayBrightnessState.Builder builder)` | 直接返回 `nit * factor`（factor 取 1.3~2.0），绕过所有 feature flag 判断。**最小改动、最可控** |
| H5 | `com.android.server.display.feature.postprocess.OplusFeatureEdrEnhanceBrightness` | `public void postProcessing(OplusDisplayBrightnessState.Builder builder)` | 在链尾强制 `builder.setNit(builder.getNit() * f)`，当检测到 HDR（`isEdrSence()` 或 SF HDR 状态）时生效 |

### P1 —— 补齐 EDR 系数来源

| # | 类 | 方法签名 | 期望效果 |
|---|---|---|---|
| H6 | `...postprocess.OplusFeatureEdrEnhanceBrightness$EdrListener` | `public void onEdrInfoChanged(IBinder displayToken, ArrayList<EdrLayerInfo> infos)` | hook 后强制 `mIsPlayingEdr = 1`、`mEnhanceFactor = 期望系数`、`mNeedToEnhanceBrightness = true` |
| H7 | `...postprocess.OplusFeatureHDREnhanceBrightness` | `private void checkIsPlayingHDR()` | 跳过 `mFlinger.transact(21012)` 结果，直接 `enterEnhanceBrightness()` |
| H8 | `android.hardware.display.DisplayDeviceConfig`（AOSP） | `public float getHdrBrightnessFromSdr(float brightness, float maxDesiredHdrSdrRatio)` | 返回 `brightness * ratio`，绕过 `mHbmData==null` 的 `-1.0f` |

### P2 —— App 侧（只能压暗，不能提亮，仅供参考）

| # | 类 | 方法签名 | 效果 |
|---|---|---|---|
| H9 | `android.view.DisplayInfo` | `public float getHdrSdrRatio()` | FkOScreen 现有做法（1.5625），仅影响 App/SF 侧 SDR 压暗比例 |
| H10 | `android.view.Display` | `public float getHdrSdrRatio()` | 同上 |

### 关键注意

- **H1/H2 单独做不会提亮**：因为 `DisplayDeviceConfig.getHdrBrightnessFromSdr()` 在本机 `mHbmData==null` 时直接 `return -1.0f`。**必须配 H8 或走 H4/H5（ColorOS 自己的链）。**
- **H3 需要反射改 `mPostProcessFeatures` 列表**：因为 `addPostprocessFeatures()` 在 `OplusDisplayFeatureManager` 构造时已执行完；更稳的做法是 hook `OplusBrightnessPostProcessingManager.postProcessing()` 之后手动调用一次目标 feature 的 `postProcessing()`。
- **LCD 面板**：`ro.oplus.display.lcd_module=1`，无像素级 EDR，提亮只能靠背光，物理上限受 `getMaxPanelNit()` 约束。

---

## 8. 为什么"只压暗不提亮"（完整因果链）

```
[配置层] /my_product/etc/permissions/oplus.product.display_features.xml
   ✗ 没有 oplus.software.display.enhance_brightness_with_uidimming
   ✗ 没有 oplus.software.display.dolby_enhance_v1_support
   ✗ 没有 oplus.software.display.hdr_enhance_brightness_support
   ✓ 有   oplus.software.display.lhdr_only_dimming_support   ← "只压暗" literally
              │
              ▼
[framework] DisplayManagerServiceExtImpl.mSupportEdrWithUiDimming = false
              │
    ┌─────────┴──────────────────────────┐
    ▼                                    ▼
getHighestHdrSdrRatio() = 1.0f     LocalDisplayDeviceExtImpl.isSupportSdrToHdrRatio() = false
    │                                    │
    │                                    ▼
    │                          handleHdrSdrNitsChanged() 不调用
    │                                    │
    │                                    ▼
    │                          mInfo.hdrSdrRatio = NaN  → Display.getHdrSdrRatio() = 1.0
    ▼
HighBrightnessModeController.mMaxDesiredHdrSdrRatio = 1.0
    │
    ▼
DisplayDeviceConfig.getHdrBrightnessFromSdr(sdr, 1.0)
    ├─ sdrToHdrSpline == null (mHbmData == null)  →  return -1.0f   ★ AOSP 路径死
    └─ 即便有 spline，ratio = min(spline, 1.0) = 1 → hdrNits = nits  ★ 也不提亮

[ColorOS postprocess 链] OplusDisplayFeatureManager.mPostProcessFeatures
   ├─ OplusFeatureEdrEnhanceBrightness   ✗ 未加入 (mSupportEdrEnhance=false)
   ├─ OplusFeatureHDREnhanceBrightness   ✗ 未加入 (mEnhanceHDRBrightnessSupport=false)
   ├─ OplusFeaturePwEdrEnhanceBrightness ✗ 未加入 (hdr2.0_enable 缺失)
   └─ 剩下全是只会压低的: DimPolicy / ReduceBrightness / GlobalLimitBrightness /
      TemperatureLimitBrightness / LowPowerModeBrightness / DarkEnvReduceBrightness ...
                                    │
                                    ▼
                          nit 只降不升 → 只压暗

[SurfaceFlinger / HWC 原生侧]
   HDR layer 存在 → SDR 内容 dimming (无条件)  → 用户看到"压暗"
   本应配套的 "背光抬升" 在 framework 层被上面 4 道门全关掉 → 用户看不到"提亮"
```

**最终结论**：

HDR 提亮应该在 **`com.android.server.display.feature.postprocess.OplusDisplayFeatureManager` 的 postprocess 责任链**里被 `OplusFeatureEdrEnhanceBrightness`（首选）或 `OplusFeatureHDREnhanceBrightness`（白名单）触发。

**没触发的原因**是 `oplus.software.display.enhance_brightness_with_uidimming` / `oplus.software.display.dolby_enhance_v1_support` / `oplus.software.display.hdr_enhance_brightness_support` 这三个 feature flag 在 OPD2404 的 `oplus.product.display_features.xml` 里根本不存在（可能是 OPPO 为 LCD 平板刻意裁剪，也可能是配置遗漏），加上 AOSP 兜底路径被 `mHbmData=null` 掐死。

---

## 9. 证据附录

### 9.1 设备 feature 配置（实测）

```
$ adb shell su -c 'grep -o "oplus.software.display[^\"]*" /my_product/etc/permissions/*.xml | sort -u'
oplus.software.display.screen_gloablehbm_support
oplus.software.display.local_hbm_support
oplus.software.display.lhdr_only_dimming_support        ← 关键
oplus.software.display.dolby_enhance_v1_rm_support      ← 注意 _rm
oplus.software.display.game.hdr3.0_enable               ← 注意 3.0
oplus.software.display.multibits_dimming_support
oplus.software.display.autobrightctl_animation_support
oplus.software.display.brightness_mode_automatic
oplus.software.display.peak_brightness_test_support
oplus.software.display.screen_calibrate_100apl
...
（无 enhance_brightness_with_uidimming / dolby_enhance_v1_support / hdr_enhance_brightness_support）
```

### 9.2 dumpsys display 关键片段（实测）

```
DisplayDeviceInfo{"内置屏幕": ... hdrCapabilities HdrCapabilities{mSupportedHdrTypes=[1, 2, 3, 4],
    mMaxLuminance=540.0, mMaxAverageLuminance=270.10236, mMinLuminance=0.2047},
    state ON, brightnessMinimum 0.016, brightnessMaximum 1.0, brightnessDefault 873.10876,
    brightnessDim 0.05, hdrSdrRatio NaN, ...}

mBacklightToBrightnessSpline=MonotoneCubicSpline{[(0.0, 0.0: 1.0), (1.0, 1.0: 1.0)]}
mNitsToBacklightSpline=null                       ← ★ 无 nit→backlight 曲线
mBacklightMinimum=0.016, mBacklightMaximum=1.0, mBrightnessDefault=0.75, mBrightnessDim=0.05
mLuxThrottlingData={}, mHbmData=null              ← ★★★ AOSP HBM/HDR 路径死在这
mHdrBrightnessData= null

mScreenBrightnessRangeMinimum=2.0
mScreenBrightnessRangeMaximum=2047.0
mScreenBrightnessNormalMaximum=1798.0
mCachedBrightnessInfo.brightness=889.0
mCachedBrightnessInfo.adjustedBrightness=2.0      ← policy=DIM，非 HDR 现象
mPendingRequestLocked=policy=DIM
Display Brightness=2.0
Display SdrBrightness=2.0

mMinimumBrightnessCurve=[(0.0, 0.0), (2000.0, 50.0), (4000.0, 90.0)]
mHdrConversionMode=HdrConversionMode{ConversionMode=HDR_CONVERSION_SYSTEM, PreferredHdrOutputType=HDR_TYPE_INVALID}
```

### 9.3 dumpsys SurfaceFlinger 关键片段（实测）

```
Layer [6682] com.lns103.hdrCapability/...MainActivity#6682
    isVisible=true ... dataspace=V0_SRGB (142671872) hdr metadata types=0
    edrSdrRatio=1.000000  dimming enabled=true
（所有 display mode: supportedHdrTypes=INVALID —— SF 侧未上报 HDR 类型）
```

### 9.4 反编译产物位置

| 路径 | 内容 |
|---|---|
| `analysis/oplus/jadx/oplus-framework/sources/` | oplus-framework.jar，3002 个 .java |
| `analysis/oplus/jadx/oplus-services/sources/` | oplus-services.jar，3260 个 .java |
| `analysis/oplus/jadx/aosp-single/` | AOSP `BrightnessRangeController` / `HighBrightnessModeController` / `DisplayDeviceConfig` |
| `analysis/oplus/logs/` | jadx 日志 |
| `/tmp/hdrflags.txt` | 所有 HDR feature flag 的引用点 |

### 9.5 待确认项（下一步）

1. ~~`IDisplayBrightnessModelExt.getMaxPanelNit()` 的实际返回值~~ **已确认实现**（见 9.6）。
2. `OplusDisplayGlobalConfig` 的 `EnhanceHDRBrightnessConfig`（App 白名单 + `mFactor`）实际配置内容 —— 若 flag 打开，哪些 App 会被提亮。
3. `oplus.software.display.dolby_enhance_v1_rm_support` 的 `_rm` 后缀语义（是否被 `OplusFeatureConfigManager` 做前缀匹配），以及 `hdr3.0_enable` 与 `hdr2.0_enable` 是否互斥。
4. SurfaceFlinger `EdrLayerInfo.getScale()` 的原生计算逻辑（在 `libsurfaceflinger.so`，不在 jar 内）。
### 9.6 提亮天花板的真正实现（补充确认）

`com.android.server.display.model.OplusDisplayBrightnessModel`（亮度模型唯一实现）：

```java
// :474-479  getMaxPanelNit —— 取面板 nit 曲线的最后一个点
public float getMaxPanelNit() {
    if (this.mPanelNits != null && this.mPanelNits.length > 0) {
        return this.mPanelNits[this.mPanelNits.length - 1];
    }
    return 1.0f;
}

// :496-498  clampNit —— 所有 postprocess 改完的 nit 都会被这一步夹住
public float clampNit(float nit) {
    return Math.min(getMaxPanelNit(), Math.max(nit, getMinPanelNit()));
}

// :492-494
public float clampBrightness(float brightnessState) {
    return Math.min(this.mTotalBrightness, Math.max(brightnessState, getMinBrightness()));
}

// :500-502  brightness ⇄ nit 曲线
public final float getNitFromBrightness(float brightness) {
    return this.mSplineManager.getCurve("backlights").interpolate(brightness);
}
```

调用点在 `OplusBrightnessPostProcessingManager.postProcessing()` :34：

```java
this.mDisplayFeatureManager.postProcessing(builder);          // 所有 feature 改 nit
builder.setNit(this.mBrightnessModel.clampNit(builder.getNit()));  // ★ 统一夹到 [minPanelNit, maxPanelNit]
convertNitToBrightness(builder);
```

**含义**：
- HDR 提亮的**物理上限 = `getMaxPanelNit()` = 面板 nit 曲线末点**（dumpsys 里 `mTotalBrightness=2047`、`mMaxBrightness=1798` 是另一组字段）。
- 任何 hook 想提高 nit，**最终都会被 `clampNit()` 夹一次**；hook 必须放在 `clampNit()` 之前（即 hook 某个 feature 的 `postProcessing`），或在 `clampNit` 之后修改（需再 hook `OplusBrightnessPostProcessingManager`）。
- `OplusFeatureEdrEnhanceBrightness.clampEnhanceNit()` 和 `OplusFeatureHDREnhanceBrightness.mMaxNit` 用的都是这个同一个上限，所以三条提亮路径的上限一致。
