# ColorOS 16 (Android 16) HDR/SDR 比率与 HDR 亮度链路逆向报告

> 目标设备：OnePlus Pad Pro (OPD2404)，ColorOS 16 / Android 16，序列号 <serial>
> 样本：`analysis/jars/framework.jar`、`services.jar`、`oplus-framework.jar`、`oplus-services.jar`（后两者从设备 `/system/framework/` 拉取）
> 反编译：jadx 1.5.6 → `analysis/framework/out/{framework,services,oplus-framework,oplus-services}/sources`；OPLUS 侧关键类用 baksmali 补读 → `/tmp/oplus-smali`
> 设备侧证据：`analysis/framework/device/ddc_4630946450791512195.xml`、`dumpsys display` 全文

---

## 0. 结论摘要（先看这段）

1. **`hdrSdrRatio` 不是从 SurfaceFlinger 直接来的**，它在 system_server 里由
   `LocalDisplayAdapter.LocalDisplayDevice.handleHdrSdrNitsChanged()` 用
   `nits / sdrNits` 现算，经 `DisplayDeviceInfo` → `LogicalDisplay` → `DisplayInfo` 一路复制到客户端。
2. **本机 dumpsys 里 `hdrSdrRatio = NaN` 的唯一原因**：
   `handleHdrSdrNitsChanged()` 的调用被 `LocalDisplayDeviceExtImpl.isSupportSdrToHdrRatio()` 挡住，
   而它返回的是 ColorOS 特性开关 `oplus.software.display.enhance_brightness_with_uidimming`（本机未配置 → false）。
   该函数从未被调用 ⇒ 字段停留在构造函数里的 `Float.NaN`。
3. `Display.getHdrSdrRatio()` 在 NaN 时**硬编码返回 1.0f**，`Display.isHdrSdrRatioAvailable()` 返回 false，
   `registerHdrSdrRatioChangedListener()` 直接抛 `IllegalStateException("HDR/SDR ratio changed not available")`。
   → 这就是验收 App `isHdrSdrRatioAvailable()=false` 的原因。
4. **HDR「提亮」在 Android 16 走的是 nits 亮度模型里的 `DisplayBrightnessState.hdrBrightness`**，
   由 `HdrBrightnessModifier.apply()`（新路径）或 `HighBrightnessModeController.getHdrBrightnessValue()`（旧 HBM 路径）计算，
   **两条路径都必须要有 DisplayDeviceConfig 里的 `<hdrBrightnessData>` / `<highBrightnessMode>`**。
5. **本机 `display 0` 的 DisplayDeviceConfig 退化成 `<config.xml>` 默认值**：
   `mHbmData=null`、`mHdrBrightnessData=null`、`mNitsToBacklightSpline=null`、亮度样条是恒等映射。
   ⇒ `HdrBrightnessModifier.apply()` 第一行就 return，`HighBrightnessModeController` 的 HDR boost 也返回 -1。
   **HDR 提亮逻辑在本机等于被彻底禁用。**
6. ColorOS 自己另有一套 HDR 提亮实现 `OplusFeatureHDREnhanceBrightness`（feature
   `oplus.software.display.hdr_enhance_brightness_support`），本机同样未配置 → `mEnhanceHDRBrightnessSupport=false`。
7. 因此现状 = 「只有压暗、没有提亮」：
   - 面板亮度 `mScreenBrightness` 与 SDR 亮度 `mSdrScreenBrightness` **恒等**（实测都是 2.0），比率恒为 1；
   - 任何"压暗"效果都来自上层（SF 的 `edrSdrRatio` / 应用自绘 dimming / FkOScreen 之类 hook 伪造比率），
     而**面板侧的提亮在任何一层都没有发生**。

> 一句话根因：**HDR 提亮所需的 display device config（HBM/HDR brightness 数据）在这台设备的内屏上不存在，
> 而 ColorOS 私有的替代实现又被特性开关关掉，于是整条提亮链路是死的；`hdrSdrRatio` 恒 NaN→1.0 只是这条链路已死的表征。**

---

## 1. 完整数据链路（文字版调用图）

```
【面板亮度写入端】
DisplayPowerController.updatePowerState()
  └─ mDisplayBrightnessController.updateBrightness(...)        → DisplayBrightnessState(brightness)
  └─ mBrightnessClamperController.clamp(state, request, ...)   → 叠加 HdrBrightnessModifier / HdrClamper 等 modifier
        └─ HdrBrightnessModifier.apply(request, stateBuilder)
              └─ mDisplayDeviceConfig.getHdrBrightnessFromSdr(brightness, mMaxDesiredHdrRatio, sdrToHdrRatioSpline)
                    └─ ratio = min(sdrToHdrSpline.interpolate(nits), maxDesiredHdrSdrRatio)
                       hdrNits = nits * ratio
                       hdrBacklight = clamp(backlightFromNits(hdrNits))
              └─ stateBuilder.setHdrBrightness(min(hdrBrightness, maxBrightness))
  └─ (内屏) mDpcExt.handleDisplayChanged(...)  → OplusDisplayPowerControllerExtImpl
        └─ OplusBrightnessPostProcessingManager.postProcessing(builder)
              └─ OplusDisplayFeatureManager.postProcessing(builder)   ← 特性链，含 OplusFeatureHDREnhanceBrightness
              └─ clampNit → convertNitToBrightness(builder)
                    brightness    = brightnessFromNits(builder.getNit())
                    sdrBrightness = brightnessFromNits(builder.getSdrNit())
        └─ IOplusDisplayPowerControllerWrapper.animateScreenBrightness(targetBrightness, targetSdrBrightness, rate)
  └─ DisplayPowerController.animateScreenBrightness(target, sdrTarget, rate)
        └─ mScreenBrightnessRampAnimator = RampAnimator.DualRampAnimator(
               DisplayPowerState, SCREEN_BRIGHTNESS_FLOAT, SCREEN_SDR_BRIGHTNESS_FLOAT)
  └─ DisplayPowerState.setScreenBrightness(v) / setSdrScreenBrightness(v)
        └─ DisplayPowerState.Listener → LocalDisplayAdapter.LocalDisplayDevice.setDisplayBrightness(brightness, sdrBrightness)

【hdrSdrRatio 产生端】（同一个 setDisplayBrightness 里）
LocalDisplayAdapter.LocalDisplayDevice.setDisplayBrightness(brightnessState, sdrBrightnessState)   L831
  ├─ backlight = brightnessToBacklight(brightnessState);  nits = backlightToNits(backlight)
  ├─ sdrBacklight = brightnessToBacklight(sdrBrightnessState); sdrNits = backlightToNits(sdrBacklight)
  ├─ if (isInternal) { 由 OPLUS ext 改写这 4 个值 }                          L844-849
  ├─ if (mLocalDisplayDeviceExt.isSupportSdrToHdrRatio() && nits!=0 && sdrNits!=0)
  │       handleHdrSdrNitsChanged(nits, sdrNits)                              L855-857
  └─ else → mBacklightAdapter.setBacklight(...)

LocalDisplayDevice.handleHdrSdrNitsChanged(displayNits, sdrNits)              L874-892
  ├─ if (!mUseSurfaceControlBrightness && !mForceSurfaceControl) → NaN
  ├─ if (nits == sdrNits) → 1.0f  else → max(1.0f, nits/sdrNits)
  └─ 变化时：mLocalDisplayDeviceExt.notifyBacklightAnimFinished(ratio)
              mCurrentHdrSdrRatio = ratio
              updateDeviceInfoLocked()

【hdrSdrRatio 传播端】
LocalDisplayDevice.getDisplayDeviceInfoLocked()  L714:  mInfo.hdrSdrRatio = mCurrentHdrSdrRatio
  └─ DisplayDeviceInfo.diff()  L121:  hdrSdrRatio 变化 → diff |= 16
  └─ LogicalDisplayMapper.updateLogicalDisplaysLocked(diff)  L672/675: diff==16 → logicalDisplayEventMask |= 64
  └─ DisplayManagerService.LogicalDisplayListener.onLogicalDisplayEventLocked(display, 64)   L3588
  └─ DisplayManagerService.handleLogicalDisplayHdrSdrRatioChangedLocked(display)            L2136
        └─ sendDisplayEventIfEnabledLocked(display, 5)      // EVENT_DISPLAY_HDR_SDR_RATIO_CHANGED = 5
  └─ DisplayManagerGlobal.handleDisplayEvent case 5: mListener.onDisplayChanged(displayId)  L1076-1078
  └─ Display$HdrSdrRatioListenerWrapper.onDisplayChanged(displayId)   Display.java L1424-1432
        └─ newRatio = Display.this.getHdrSdrRatio(); 变化则 listener.accept(display)

【DisplayInfo 侧】
LogicalDisplay.copyDisplayDeviceInfoLocked(deviceInfo)  L337:  mBaseDisplayInfo.hdrSdrRatio = deviceInfo.hdrSdrRatio
  └─ DisplayInfo.copyFrom(DisplayInfo o)  L218:  this.hdrSdrRatio = other.hdrSdrRatio
  └─ DisplayInfo.readFromParcel(Parcel) L302 / writeToParcel L381
```

---

## 2. Q1 — `android.view.DisplayInfo.hdrSdrRatio` 从哪来？谁写？

### 2.1 字段定义
```java
// framework.jar → android/view/DisplayInfo.java:60
public float hdrSdrRatio;
```
三个构造函数都初始化成 NaN：
```java
// DisplayInfo.java:105 / 119 / 134
this.hdrSdrRatio = Float.NaN;
```

### 2.2 `copyFrom` 与调用者
```java
// DisplayInfo.java:218
public void copyFrom(DisplayInfo other) {
    ...
    this.hdrSdrRatio = other.hdrSdrRatio;   // L218
    ...
}
```
`copyFrom` 的调用者（system_server 侧）：
- `LogicalDisplayMapper.updateLogicalDisplaysLocked()` L632：
  `this.mTempDisplayInfo.copyFrom(display.getDisplayInfoLocked())`（仅用于 diff 比较）
- `DisplayManagerService` 在把 `DisplayInfo` 交给客户端前的快照/缓存复制路径。

### 2.3 system_server 里真正的写入者（唯一）
```java
// services.jar → com/android/server/display/LogicalDisplay.java:337
this.mBaseDisplayInfo.hdrSdrRatio = deviceInfo.hdrSdrRatio;   // deviceInfo: com.android.server.display.DisplayDeviceInfo
```
即 `LogicalDisplay.copyDisplayDeviceInfoLocked(DisplayDeviceInfo deviceInfo)`。
它的上游是 `DisplayDevice.getDisplayDeviceInfoLocked()`，内屏实现为
`LocalDisplayAdapter.LocalDisplayDevice.getDisplayDeviceInfoLocked()`：
```java
// services.jar → com/android/server/display/LocalDisplayAdapter.java:714
this.mInfo.hdrSdrRatio = this.mCurrentHdrSdrRatio;
```
所以 **DisplayInfo.hdrSdrRatio 的唯一来源是 `LocalDisplayDevice.mCurrentHdrSdrRatio`**。

---

## 3. Q2 — `DisplayDeviceInfo.hdrSdrRatio` 怎么算？为什么 dumpsys 是 NaN？

### 3.1 字段与初值
```java
// services.jar → com/android/server/display/DisplayDeviceInfo.java:93
public float hdrSdrRatio = Float.NaN;
// L186 copyFrom: this.hdrSdrRatio = other.hdrSdrRatio;
// L239 toString: ", hdrSdrRatio " + this.hdrSdrRatio
```
注意：`DisplayDeviceInfo.toString()` 不做 NaN 特殊化，所以 dumpsys 直接打印 **`hdrSdrRatio NaN`**；
而客户端 `DisplayInfo.toString()` 会打印 `not_available`：
```java
// framework.jar → android/view/DisplayInfo.java:643-648
sb.append(", hdrSdrRatio ");
if (Float.isNaN(this.hdrSdrRatio)) sb.append("not_available"); else sb.append(this.hdrSdrRatio);
```

### 3.2 唯一计算点：`handleHdrSdrNitsChanged`
```java
// LocalDisplayAdapter.java:874-892
void handleHdrSdrNitsChanged(float displayNits, float sdrNits) {
    float newHdrSdrRatio;
    if (displayNits != -1.0f && sdrNits != -1.0f
        && (mBacklightAdapter.mUseSurfaceControlBrightness || mBacklightAdapter.mForceSurfaceControl)) {
        if (BrightnessSynchronizer.floatEquals(displayNits, sdrNits)) {
            newHdrSdrRatio = 1.0f;                       // ← SDR/HDR 亮度相同 ⇒ 比率 1.0
        } else {
            newHdrSdrRatio = Math.max(1.0f, displayNits / sdrNits);   // ← 唯一的比率计算式
        }
    } else {
        newHdrSdrRatio = Float.NaN;                      // ← 不满足条件 ⇒ NaN
    }
    if (!BrightnessSynchronizer.floatEquals(mCurrentHdrSdrRatio, newHdrSdrRatio)) {
        mLocalDisplayDeviceExt.notifyBacklightAnimFinished(newHdrSdrRatio);
        synchronized (getSyncRoot()) {
            mCurrentHdrSdrRatio = newHdrSdrRatio;
            updateDeviceInfoLocked();
        }
    }
}
```
唯一调用点（门禁在这里）：
```java
// LocalDisplayAdapter.java:855-857
if (mLocalDisplayDeviceExt.isSupportSdrToHdrRatio() && nits != 0.0f && sdrNits != 0.0f) {
    handleHdrSdrNitsChanged(nits, sdrNits);
}
```
字段初值：
```java
// LocalDisplayAdapter.java:205  private float mCurrentHdrSdrRatio;
// LocalDisplayAdapter.java:244  this.mCurrentHdrSdrRatio = Float.NaN;
```

### 3.3 为什么本机是 NaN —— 逐条排除

设备实测（`dumpsys display`）：
```
mBacklightAdapter=BacklightAdapter [useSurfaceControl=true (force_anyway? true), ...]   ← 这一条是满足的
DisplayDeviceConfig{mLoadedFrom=<config.xml>
  ... mNitsToBacklightSpline=null, mBacklightMinimum=0.016, mBacklightMaximum=1.0 ...
  mLuxThrottlingData={}, mHbmData=null, ...
  mHdrBrightnessData= null
DisplayDeviceInfo{... hdrSdrRatio NaN ...}
DisplayInfo{... hdrSdrRatio not_available ...}
mScreenBrightness=2.0
mSdrScreenBrightness=2.0
```

- `mUseSurfaceControlBrightness = true`、`mForceSurfaceControl = true` ⇒ **不是** BacklightAdapter 那条分支导致的 NaN。
- 因此只剩 **`isSupportSdrToHdrRatio() == false`**（或 nits/sdrNits 为 0）这一种可能。
  `handleHdrSdrNitsChanged` 从未执行 ⇒ `mCurrentHdrSdrRatio` 保持构造时的 NaN。

OPLUS 侧实现（baksmali）：
```smali
# oplus-services.jar → com/android/server/display/LocalDisplayDeviceExtImpl.smali
.method public isSupportSdrToHdrRatio()Z          # L1025
    iget-boolean v0, p0, Lcom/android/server/display/LocalDisplayDeviceExtImpl;->mSupportEdrWithUiDimming:Z
    return v0
.end method

# 该字段在构造器里由特性开关决定：
#   sget-object v0, Lcom/android/server/content/IOplusFeatureConfigManagerInternal;->DEFAULT
#   const-string/jumbo v1, "oplus.software.display.enhance_brightness_with_uidimming"
#   invoke-interface {v0, v1}, ...->hasFeature(Ljava/lang/String;)Z
#   iput-boolean v0, p0, ...->mSupportEdrWithUiDimming:Z
```
设备上 `grep -rl "enhance_brightness_with_uidimming" /my_product/etc /system_ext/etc /system/etc /odm/etc /vendor/etc`
**无任何命中**（该串只出现在若干 APK 里）⇒ `hasFeature(...) == false` ⇒ `isSupportSdrToHdrRatio() == false` ⇒ **比率永远 NaN**。

### 3.4 结论（Q2）
> `DisplayDeviceInfo.hdrSdrRatio` = `nits/sdrNits`，只有当 ColorOS 特性
> `oplus.software.display.enhance_brightness_with_uidimming` 打开时才会被计算；
> 本机该特性未配置，函数根本没被调用，字段停留在 `Float.NaN`。
> 换句话说：**ColorOS 把「SDR/HDR 比率」这件事做成了自家的可选特性，而不是 AOSP 的默认行为。**

---

## 4. Q3 — `Display` 的三个 API 实现与数据来源

### 4.1 `isHdrSdrRatioAvailable()`
```java
// framework.jar → android/view/Display.java:580-587
public boolean isHdrSdrRatioAvailable() {
    synchronized (this.mLock) {
        updateDisplayInfoLocked();
        return !Float.isNaN(this.mDisplayInfo.hdrSdrRatio);   // L584
    }
}
```
NaN ⇒ false。验收 App 显示"不可用"就来自这里。

### 4.2 `getHdrSdrRatio()` —— **NaN 被强制成 1.0f**
```java
// Display.java:589-596
public float getHdrSdrRatio() {
    synchronized (this.mLock) {
        updateDisplayInfoLocked();
        return Float.isNaN(this.mDisplayInfo.hdrSdrRatio) ? 1.0f : this.mDisplayInfo.hdrSdrRatio;  // L593
    }
}
```
**这就是"恒返回 1.0"的硬编码点。**

### 4.3 `registerHdrSdrRatioChangedListener` / `unregister`
```java
// Display.java:622-683（jadx 未能反编译出结构化代码，以下为字节码还原）
public void registerHdrSdrRatioChangedListener(Executor executor, Consumer<Display> listener) {
    if (!isHdrSdrRatioAvailable()) {                                    // 0x0004
        throw new IllegalStateException("HDR/SDR ratio changed not available");   // 0x0040
    }
    HdrSdrRatioListenerWrapper wrapper = null;
    synchronized (mLock) {
        if (findHdrSdrRatioListenerLocked(listener) == -1) {            // 0x000c
            wrapper = new HdrSdrRatioListenerWrapper(listener);         // 0x0014
            mHdrSdrRatioListeners.add(wrapper);                         // 0x0018
        }
    }
    if (wrapper != null) {
        mGlobal.registerDisplayListener(wrapper, executor, 18,         // 18 = 2|16
                ActivityThread.currentPackageName(), true);             // 0x0023
    }
}
```
事件掩码 `18 = EVENT_DISPLAY_BASIC_CHANGED(2) | EVENT_DISPLAY_HDR_SDR_RATIO_CHANGED(16)`。

```java
// Display.java:1406-1433
private class HdrSdrRatioListenerWrapper implements DisplayManager.DisplayListener {
    float mLastReportedRatio;
    Consumer<Display> mListener;
    private HdrSdrRatioListenerWrapper(Consumer<Display> l) { mLastReportedRatio = 1.0f; mListener = l; }
    public void onDisplayChanged(int displayId) {
        if (displayId == Display.this.getDisplayId()) {
            float newRatio = Display.this.getHdrSdrRatio();
            if (newRatio != mLastReportedRatio) { mLastReportedRatio = newRatio; mListener.accept(Display.this); }
        }
    }
}
```
注意 `mLastReportedRatio` 初值 **1.0f**：即使比率变成 1.0 也不会触发回调。

### 4.4 另一个 API：`getHighestHdrSdrRatio()`
```java
// Display.java:698-700
public float getHighestHdrSdrRatio() { return this.mGlobal.getHighestHdrSdrRatio(this.mDisplayId); }

// android/hardware/display/DisplayManagerGlobal.java:881-884
public float getHighestHdrSdrRatio(int displayId) {
    try { return this.mDm.getHighestHdrSdrRatio(displayId); } ...
}
// IDisplayManager.aidl:  TRANSACTION_getHighestHdrSdrRatio = 60
```
服务端（**默认恒 1.0f**）：
```java
// DisplayManagerService.java:4708-4713
public float getHighestHdrSdrRatio(int displayId) {
    if (DisplayManagerService.this.mDmsExt != null) {
        return DisplayManagerService.this.mDmsExt.getHighestHdrSdrRatio();
    }
    return 1.0f;
}
// IDisplayManagerServiceExt.java:116-118
default float getHighestHdrSdrRatio() { return 1.0f; }
```

---

## 5. Q4（核心）— `DisplayPowerController` 的 nits 亮度模型与 HDR 提亮路径

Android 16 的 DPC 亮度管线（services.jar）：

```
DisplayPowerController
 ├─ mDisplayBrightnessController : DisplayBrightnessController   → DisplayBrightnessState（含 brightness）
 ├─ mBrightnessClamperController : BrightnessClamperController   → clamp() 叠加各 Modifier
 │    ├─ HdrBrightnessModifier（新路径，Android 15+ 的 HDR 提亮）
 │    ├─ HdrClamper（旧/兼容路径）
 │    └─ 其他（低电量、热、夜览…）
 ├─ mBrightnessRangeController   : BrightnessRangeController     → 亮度上下限 / HBM / HDR boost 上限
 │    └─ mHbmController : HighBrightnessModeController          → 旧 HBM 路径的 HDR 提亮
 └─ mScreenBrightnessRampAnimator : RampAnimator.DualRampAnimator
      (SCREEN_BRIGHTNESS_FLOAT = 面板亮度, SCREEN_SDR_BRIGHTNESS_FLOAT = SDR 亮度)
```

### 5.1 两个亮度通道：`mScreenBrightness` 与 `mSdrBrightness`
```java
// DisplayPowerController.java:764
mScreenBrightnessRampAnimator = mInjector.getDualRampAnimator(
        mPowerState, DisplayPowerState.SCREEN_BRIGHTNESS_FLOAT, DisplayPowerState.SCREEN_SDR_BRIGHTNESS_FLOAT);
```
```java
// DisplayPowerState.java:46  private float mSdrScreenBrightness;
// DisplayPowerState.java:75  SCREEN_SDR_BRIGHTNESS_FLOAT → setSdrScreenBrightness / getSdrScreenBrightness
// DisplayPowerState.java:157 构造时 mSdrScreenBrightness = mScreenBrightness;   ← 默认相等
```
实测 `dumpsys display`：`mScreenBrightness=2.0` / `mSdrScreenBrightness=2.0` ⇒ **两路完全相同 ⇒ 比率必然 1.0**。

### 5.2 应用亮度的地方（关键分叉）
```java
// DisplayPowerController.java:1325-1331
if (((i5 != 2 && mSkipRampState == 0 && mReportedScreenStateToPolicy != 0)
        || (i5 == 3 && !mBrightnessBucketsInDozeConfig)) && !z15) {
    if (this.mDpcExt == null || !this.mIsDisplayInternal) {
        animateScreenBrightness(brightness, brightness, SCREEN_ANIMATION_RATE_MINIMUM);   // L1327 两参相同！
    } else {
        this.mDpcExt.handleDisplayChanged(zIsSlowChange, ..., brightness, i5, mDisplayId, mBrightnessReasonTemp);  // L1329
    }
}
```
- 对**外接/虚拟屏**：`animateScreenBrightness(brightness, brightness, ...)` —— 直接把 SDR 亮度设成面板亮度（比率恒 1）。
- 对**内屏**：整个亮度应用被交给 **ColorOS 的 `OplusDisplayPowerControllerExtImpl.handleDisplayChanged()`**。

### 5.3 HDR 提亮路径 A：`HdrBrightnessModifier`（新）
```java
// services.jar → com/android/server/display/brightness/clamper/HdrBrightnessModifier.java:118-129
public void apply(DisplayManagerInternal.DisplayPowerRequest request, DisplayBrightnessState.Builder stateBuilder) {
    if (this.mHdrBrightnessData == null || this.mMode == Mode.NO_HDR) {
        return;                                            // ★★★ 本机就是从这里 return 的
    }
    float hdrBrightness = mDisplayDeviceConfig.getHdrBrightnessFromSdr(
            stateBuilder.getBrightness(), mMaxDesiredHdrRatio, mHdrBrightnessData.sdrToHdrRatioSpline);
    float maxBrightness = getMaxBrightness(mMode, mMaxBrightness, mHdrBrightnessData);
    stateBuilder.setHdrBrightness(Math.min(hdrBrightness, maxBrightness));   // ★ HDR 提亮值
    stateBuilder.setCustomAnimationRate(mTransitionRate);
    stateBuilder.getBrightnessReason().addModifier(4);
}
```
- `mHdrBrightnessData` 来自 `DisplayDeviceConfig.getHdrBrightnessData()`（即 XML 里的 `<hdrBrightnessData>`）：
  ```java
  // HdrBrightnessModifier.java:176-199 (onDisplayChanged)
  HdrBrightnessData data = mDisplayDeviceConfig.getHdrBrightnessData();
  if (data == null) unregisterHdrListener(); else registerHdrListener(displayData.mDisplayToken);
  ...
  this.mMode = recalculateMode(data);       // data==null → Mode.NO_HDR
  ```
- 模式判定（HDR 图层占屏比）：
  ```java
  // HdrBrightnessModifier.java:237-254
  private Mode recalculateMode(HdrBrightnessData data) {
      if (data == null) return Mode.NO_HDR;
      if (mHdrLayerSize == -1.0f) return Mode.NO_HDR;
      if (!data.allowInLowPowerMode && mLowPowerMode) return Mode.NO_HDR;
      if (mHdrLayerSize < mScreenSize * data.minimumHdrPercentOfScreenForNbm) return Mode.NO_HDR;
      if (mHdrLayerSize < mScreenSize * data.minimumHdrPercentOfScreenForHbm) return Mode.NBM_HDR;
      return Mode.HBM_HDR;
  }
  ```
- HDR 图层信息来自 SurfaceFlinger 的 `SurfaceControlHdrLayerInfoListener`：
  ```java
  // HdrBrightnessModifier.java:55-68
  public void onHdrInfoChanged(IBinder displayToken, int numberOfHdrLayers, int maxW, int maxH, int flags, float maxDesiredHdrSdrRatio) {
      boolean hdrLayerPresent = numberOfHdrLayers > 0;
      ... onHdrInfoChanged(hdrLayerPresent ? maxW*maxH : -1.0f,
                           hdrLayerPresent ? Math.max(maxDesiredHdrSdrRatio, 1.0f) : 1.0f);
  }
  ```

### 5.4 HDR 提亮路径 B：`HighBrightnessModeController`（旧 HBM，Android 14 时代）
```java
// HighBrightnessModeController.java:134-142
float getHdrBrightnessValue() {
    if (mHdrBrightnessCfg != null) {
        float hdrBrightness = mHdrBrightnessCfg.getHdrBrightnessFromSdr(mBrightness, mMaxDesiredHdrSdrRatio);
        if (hdrBrightness != -1.0f) return hdrBrightness;          // ★ 有 spline 才生效
    }
    return MathUtils.map(getCurrentBrightnessMin(), getCurrentBrightnessMax(), mBrightnessMin, mBrightnessMax, mBrightness);
}
// HighBrightnessModeController.java:80   mMaxDesiredHdrSdrRatio = 1.0f;
// HighBrightnessModeController.java:479-495 (HdrListener.lambda)
candidateDesiredHdrSdrRatio = (mIsHdrLayerPresent && mHdrBrightnessCfg != null) ? maxDesiredHdrSdrRatio : 1.0f;
if (candidateDesiredHdrSdrRatio < 1.0f) { Slog.w(TAG, "Ignoring invalid desired HDR/SDR Ratio: " + ...); candidateDesiredHdrSdrRatio = 1.0f; }
```
消费侧：
```java
// BrightnessRangeController.java:145-148
void onBrightnessChanged(float brightness, float unthrottledBrightness, DisplayBrightnessState state) {
    mHbmController.onHdrBoostApplied(state.getHdrBrightness() != -1.0f);
    mHbmController.onBrightnessChanged(brightness, unthrottledBrightness, state.getBrightnessMaxReason());
}
// BrightnessRangeController.java:165-168
float getHdrBrightnessValue() {
    float hdrBrightness = mHbmController.getHdrBrightnessValue();
    return mUseHdrClamper ? mHdrClamper.clamp(hdrBrightness) : hdrBrightness;
}
// BrightnessRangeController.java:31  mUseHdrClamper = !flags.useNewHdrBrightnessModifier();
// BrightnessRangeController.java:33-35 if (flags.useNewHdrBrightnessModifier()) mHbmController.disableHdrBoost();
```

### 5.5 提亮的核心数学：`DisplayDeviceConfig.getHdrBrightnessFromSdr`
```java
// services.jar → com/android/server/display/DisplayDeviceConfig.java:366-391
public float getHdrBrightnessFromSdr(float brightness, float maxDesiredHdrSdrRatio) {
    Spline sdrToHdrSpline = (mHbmData != null) ? mHbmData.sdrToHdrRatioSpline : null;
    return getHdrBrightnessFromSdr(brightness, maxDesiredHdrSdrRatio, sdrToHdrSpline);
}

public float getHdrBrightnessFromSdr(float brightness, float maxDesiredHdrSdrRatio, Spline sdrToHdrSpline) {
    if (sdrToHdrSpline == null) return -1.0f;                       // ★ 没有 spline ⇒ 不生效
    float backlight = getBacklightFromBrightness(brightness);
    float nits = getNitsFromBacklight(backlight);
    if (nits == -1.0f) return -1.0f;
    float ratio = Math.min(sdrToHdrSpline.interpolate(nits), maxDesiredHdrSdrRatio);   // ★ 比率取小
    float hdrNits = nits * ratio;                                   // ★ 提亮 = nits × ratio
    if (getNitsToBacklightSpline() == null) return -1.0f;           // ★★ 本机 null ⇒ 直接 -1
    float hdrBacklight = Math.max(mBacklightMinimum, Math.min(mBacklightMaximum, getBacklightFromNits(hdrNits)));
    float hdrBrightness = getBrightnessFromBacklight(hdrBacklight);
    return hdrBrightness;
}
```

### 5.6 ColorOS 私有实现（内屏真正走的路）
```
OplusDisplayPowerControllerExtImpl.handleDisplayChanged(ZZFIIBrightnessReason)   // smali L3530-4207
  ├─ L3791  targetBrightness     = newBrightnessState.getBrightness()
  ├─ L3797  targetSdrBrightness  = newBrightnessState.getSdrBrightness()
  ├─ L3941  mDpc.getWrapper().animateScreenBrightness(targetBrightness, targetSdrBrightness, rate)
  └─ (state 来自 OplusBrightnessPostProcessingManager.postProcessing(builder, rate))

OplusBrightnessPostProcessingManager.postProcessing(builder, initialRate)        // smali L283
  ├─ OplusDisplayFeatureManager.postProcessing(builder)      // 特性链（含 OplusFeatureHDREnhanceBrightness）
  ├─ builder.setNit(mBrightnessModel.clampNit(builder.getNit()))
  ├─ convertNitToBrightness(builder)                          // smali L139
  │     brightness    = brightnessFromNits(builder.getNit())
  │     sdrBrightness = brightnessFromNits(builder.getSdrNit())
  │     hdrHbmBaseBrightness = brightnessFromNits(builder.getHdrHbmBaseNit())  (非 NaN 时)
  └─ build()

OplusFeatureHDREnhanceBrightness（ColorOS 的 HDR 提亮）        // smali 全文 1856 行
  ├─ 开关：OplusFeatureConfigManager.hasFeature("oplus.software.display.hdr_enhance_brightness_support")
  │        → mEnhanceHDRBrightnessSupport
  ├─ 触发：SurfaceFlinger transact CODE_GET_HDR_STATE = 0x5214 → checkIsPlayingHDR()
  ├─ 条件：mNeedToEnhanceBrightness && mEnterWhiteApp && mBatteryLevel > 0x14(20) && !mPowerSavingMode && 非旋转受限…
  ├─ 动作：builder.setHdrHbmBaseNit(mEnterNit); builder.setNit(mNit); builder.setReason("OplusFeatureHDREnhanceBrightness")
  └─ 结果：nit 被抬高、sdrNit 不变 ⇒ brightness↑ / sdrBrightness 不变 ⇒ 比率 > 1（这才是"提亮"）
```

**结论：Android 16 上 HDR 提亮应发生在 `DisplayPowerController` 的 nits 亮度模型这一层**
（`DisplayBrightnessState.hdrBrightness` → `DualRampAnimator` 的 sdrTarget 与 target 分离）。
ColorOS 则把它换成了自家的 `OplusFeatureHDREnhanceBrightness`。
本机**两条路都没有生效**（AOSP 路缺 `<hdrBrightnessData>`/`<highBrightnessMode>`，ColorOS 路缺特性开关）。

---

## 6. 本机实测证据（`dumpsys display`，display 0）

```
mPhysicalDisplayId=<physical-display-id>
mBrightnessState=2.0
mBacklightAdapter=BacklightAdapter [useSurfaceControl=true (force_anyway? true), ...]
DisplayDeviceConfig{mLoadedFrom=<config.xml>
  mBacklight=null, mNits=null, mRawBacklight=null, mRawNits=null, mInterpolationType=0
  mBrightnessToBacklightSpline=MonotoneCubicSpline{[(0.0,0.0:1.0),(1.0,1.0:1.0)]}     ← 恒等
  mBacklightToBrightnessSpline=MonotoneCubicSpline{[(0.0,0.0:1.0),(1.0,1.0:1.0)]}     ← 恒等
  mNitsToBacklightSpline=null                                                        ← ★ null
  mBacklightMinimum=0.016, mBacklightMaximum=1.0, mBrightnessDefault=0.75
  mLuxThrottlingData={}, mHbmData=null, ...                                          ← ★ mHbmData=null
  mHdrBrightnessData= null }                                                          ← ★ null
mScreenBrightness=2.0
mSdrScreenBrightness=2.0                                                              ← ★ 两路相同
HdrBrightnessModifier:
  mHdrBrightnessData=null        ← apply() 第一行 return
  mMaxDesiredHdrRatio=1.0
  mHdrLayerSize=-1.0             ← 没有 HDR 图层信息
BrightnessRangeController:
  mUseHdrClamper=false
HighBrightnessModeController:
  mHbmMode=off
DisplayDeviceInfo{... hdrSdrRatio NaN ...}
DisplayInfo{... hdrSdrRatio not_available ...}
```

设备上 `/vendor/etc/displayconfig/` 只有：
`display_id_4630946450791512195.xml`、`display_id_4630946614210407555.xml`、`display_id_4630946994637926275.xml`、`display_layout_configuration.xml`
——**没有 `display_id_<physical-display-id>.xml`**（内屏的 physicalDisplayId）。
`/odm/etc/displayconfig/`、`/my_product/etc/displayconfig/` 不存在。
`DisplayDeviceConfig.createWithoutDefaultValues()`（L205-215）依次查 product/vendor 目录，
找不到就退回 `create()` → `initFromGlobalXml()`（L763-780，`mLoadedFrom="<config.xml>"`），
该路径**不加载 `<highBrightnessMode>` / `<hdrBrightnessData>` / nits 映射**。

作为对照，那几个存在的 XML 里确实有完整的 HDR 数据（`analysis/framework/device/ddc_4630946450791512195.xml`）：
```xml
<screenBrightnessMap interpolation="linear"> … value 0→0.2289 nits … value 1.0→782.4283 nits </screenBrightnessMap>
<highBrightnessMode enabled="true">
  <transitionPoint>0.85</transitionPoint>
  <minimumHdrPercentOfScreen>0.1</minimumHdrPercentOfScreen>
  <minimumLux>10000</minimumLux>
  <sdrHdrRatioMap>
    <point><sdrNits>2.000</sdrNits><hdrRatio>8.000</hdrRatio></point>
    <point><sdrNits>500.000</sdrNits><hdrRatio>1.5</hdrRatio></point>
  </sdrHdrRatioMap>
</highBrightnessMode>
```
→ 说明"配置长什么样"是已知的，只是**没有匹配到内屏**。

---

## 7. Q5 — 全量 `hdrSdrRatio` 引用点清单（读 / 写）

### 7.1 framework.jar

| 文件 | 行 | 符号 | 读/写 |
|---|---|---|---|
| `android/view/DisplayInfo.java` | 60 | `public float hdrSdrRatio;` 字段 | 定义 |
| `android/view/DisplayInfo.java` | 105/119/134 | 三个构造器 `= Float.NaN` | **写** |
| `android/view/DisplayInfo.java` | 150 | `equalsWithOnlyBasicChanges()` 中 `BrightnessSynchronizer.floatEquals(hdrSdrRatio, …)` | 读 |
| `android/view/DisplayInfo.java` | 218 | `copyFrom()` `this.hdrSdrRatio = other.hdrSdrRatio` | **写** |
| `android/view/DisplayInfo.java` | 302 | `readFromParcel()` `= source.readFloat()` | **写** |
| `android/view/DisplayInfo.java` | 381 | `writeToParcel()` `dest.writeFloat(...)` | 读 |
| `android/view/DisplayInfo.java` | 643-648 | `toString()` `NaN → "not_available"` | 读 |
| `android/view/Display.java` | 108/143 | `mHdrSdrRatioListeners` 列表 | - |
| `android/view/Display.java` | 584 | `isHdrSdrRatioAvailable()` `!Float.isNaN(...)` | 读 |
| `android/view/Display.java` | 593 | `getHdrSdrRatio()` `NaN ? 1.0f : ...` | 读（**钳制**） |
| `android/view/Display.java` | 607-615 | `findHdrSdrRatioListenerLocked` | - |
| `android/view/Display.java` | 622-683 | `registerHdrSdrRatioChangedListener` | - |
| `android/view/Display.java` | 685-696 | `unregisterHdrSdrRatioChangedListener` | - |
| `android/view/Display.java` | 698-700 | `getHighestHdrSdrRatio()` → `mGlobal` | 读 |
| `android/view/Display.java` | 1406-1433 | `HdrSdrRatioListenerWrapper`（`mLastReportedRatio=1.0f`，onDisplayChanged 比对） | 读 |
| `android/hardware/display/DisplayManagerGlobal.java` | 56 | `EVENT_DISPLAY_HDR_SDR_RATIO_CHANGED = 5` | 定义 |
| `android/hardware/display/DisplayManagerGlobal.java` | 65 | `INTERNAL_EVENT_FLAG_DISPLAY_HDR_SDR_RATIO_CHANGED = 16` | 定义 |
| `android/hardware/display/DisplayManagerGlobal.java` | 1076-1078 | `case 5: mListener.onDisplayChanged(displayId)` | 读 |
| `android/hardware/display/DisplayManagerGlobal.java` | 881-884 | `getHighestHdrSdrRatio(displayId)` → binder | 读 |
| `android/hardware/display/IDisplayManager.java` | 66 / 416 / 469 / 663 / 1065 / 2064 | AIDL `getHighestHdrSdrRatio`，TRANSACTION=60 | 读 |

### 7.2 services.jar

| 文件 | 行 | 符号 | 读/写 |
|---|---|---|---|
| `display/DisplayDeviceInfo.java` | 93 | `public float hdrSdrRatio = Float.NaN` | 定义/**写初值** |
| `display/DisplayDeviceInfo.java` | 121 | `diff()` `hdrSdrRatio` 不等 → `diff |= 16` | 读 |
| `display/DisplayDeviceInfo.java` | 186 | `copyFrom()` | **写** |
| `display/DisplayDeviceInfo.java` | 239 | `toString()` 直接打印（NaN 原样） | 读 |
| `display/LocalDisplayAdapter.java` | 205 | `private float mCurrentHdrSdrRatio` | 定义 |
| `display/LocalDisplayAdapter.java` | 244 | `mCurrentHdrSdrRatio = Float.NaN` | **写初值** |
| `display/LocalDisplayAdapter.java` | 714 | `mInfo.hdrSdrRatio = mCurrentHdrSdrRatio` | **写** |
| `display/LocalDisplayAdapter.java` | 855-857 | 门禁 `isSupportSdrToHdrRatio() && nits!=0 && sdrNits!=0` → `handleHdrSdrNitsChanged` | 读 |
| `display/LocalDisplayAdapter.java` | 874-892 | `handleHdrSdrNitsChanged()` 计算 `nits/sdrNits` | **写** |
| `display/LogicalDisplay.java` | 337 | `mBaseDisplayInfo.hdrSdrRatio = deviceInfo.hdrSdrRatio` | **写** |
| `display/LogicalDisplayMapper.java` | 55 | `LOGICAL_DISPLAY_EVENT_HDR_SDR_RATIO_CHANGED = 64` | 定义 |
| `display/LogicalDisplayMapper.java` | 672/675 | `diff == 16 → mask |= 64` | 读 |
| `display/LogicalDisplayMapper.java` | 744 | `sendUpdatesForDisplaysLocked(64)` | - |
| `display/DisplayManagerService.java` | 2136-2138 | `handleLogicalDisplayHdrSdrRatioChangedLocked()` → `sendDisplayEventIfEnabledLocked(display, 5)` | - |
| `display/DisplayManagerService.java` | 3588 | `case 64: handleLogicalDisplayHdrSdrRatioChangedLocked(display)` | - |
| `display/DisplayManagerService.java` | 4708-4713 | `getHighestHdrSdrRatio()` → `mDmsExt` 或 1.0f | 读 |
| `display/IDisplayManagerServiceExt.java` | 116-118 | `default float getHighestHdrSdrRatio() { return 1.0f; }` | 读（**钳制**） |
| `display/HighBrightnessModeController.java` | 54/80 | `mMaxDesiredHdrSdrRatio = 1.0f` | 定义/**写初值** |
| `display/HighBrightnessModeController.java` | 136 | `getHdrBrightnessFromSdr(mBrightness, mMaxDesiredHdrSdrRatio)` | 读 |
| `display/HighBrightnessModeController.java` | 469-495 | `onHdrInfoChanged(...)` ← SurfaceFlinger；`<1.0f → 1.0f` | 读/**钳制** |
| `display/brightness/clamper/HdrBrightnessModifier.java` | 21 | `DEFAULT_MAX_HDR_SDR_RATIO = 1.0f` | 定义 |
| `display/brightness/clamper/HdrBrightnessModifier.java` | 55-68 | `onHdrInfoChanged`，`Math.max(maxDesiredHdrSdrRatio, 1.0f)` | 读/**钳制** |
| `display/brightness/clamper/HdrBrightnessModifier.java` | 79/119-129/202-208/294-304 | `mMaxDesiredHdrRatio` 使用、`apply()`、`applyStateChange()` | 读/写 |
| `display/brightness/clamper/HdrClamper.java` | 209 | `onHdrInfoChanged(...)` | 读 |
| `display/DisplayDeviceConfig.java` | 362-391 | `hasSdrToHdrRatioSpline()` / `getHdrBrightnessFromSdr()` | 读 |
| `display/config/HdrBrightnessData.java` | 18/26/37/118/126 | `highestHdrSdrRatio` 等 | 读 |
| `display/config/DisplayDeviceConfigUtils.java` | 33 | `getHighestHdrSdrRatio(points, extractor)` | 读 |

### 7.3 oplus-services.jar（ColorOS 私有）

| 文件 | 符号 | 读/写 |
|---|---|---|
| `LocalDisplayDeviceExtImpl` | `isSupportSdrToHdrRatio()` = `mSupportEdrWithUiDimming` = feature `oplus.software.display.enhance_brightness_with_uidimming` | 读（门禁） |
| `LocalDisplayDeviceExtImpl` | `notifyBacklightAnimFinished(float scale)` | 写（通知 GameManager） |
| `LocalDisplayDeviceExtImpl` | `setDisplayBrightness(brightness, sdrBrightness)` → 返回 `Map{brightnessState,sdrBrightnessState,nits,sdrNits}` | **写**（改写 nits/sdrNits） |
| `OplusDisplayPowerControllerExtImpl` | `handleDisplayChanged(ZZFIIBrightnessReason)`、`animateScreenBrightness(DualRampAnimator,FFF,DisplayPowerRequest,DisplayPowerState)`、`mCurrentSdrBrightness` | 读/写 |
| `OplusBrightnessPostProcessingManager` | `convertNitToBrightness(builder)`：nit→brightness、sdrNit→sdrBrightness、hdrHbmBaseNit→hdrHbmBaseBrightness | **写** |
| `OplusFeatureHDREnhanceBrightness` | feature `oplus.software.display.hdr_enhance_brightness_support`；`setNit`/`setHdrHbmBaseNit` | **写** |
| `OplusDisplayBrightnessState` / `$Builder` | `getSdrBrightness`/`getSdrNit`/`getHdrHbmBaseNit`/`isEdrSence`/`getEdrType` | 读 |

---

## 8. Q6 — 有没有地方把它钳制 / 强制成 1.0？

**有，共 6 处（按影响排序）：**

1. **`Display.getHdrSdrRatio()` — 最直接的 1.0**
   ```java
   // Display.java:593
   return Float.isNaN(this.mDisplayInfo.hdrSdrRatio) ? 1.0f : this.mDisplayInfo.hdrSdrRatio;
   ```
   NaN → 1.0f。**这就是"getHdrSdrRatio() 恒返回 1.0"的位置。**

2. **`DisplayManagerService.getHighestHdrSdrRatio()` / `IDisplayManagerServiceExt.getHighestHdrSdrRatio()`**
   ```java
   // DisplayManagerService.java:4710-4713 → 无 ext 时 return 1.0f
   // IDisplayManagerServiceExt.java:116-118 → default return 1.0f
   ```

3. **`HdrBrightnessModifier`**
   ```java
   // L21  static final float DEFAULT_MAX_HDR_SDR_RATIO = 1.0f;
   // L79  mMaxDesiredHdrRatio = 1.0f;
   // L67  hdrLayerPresent ? Math.max(maxDesiredHdrSdrRatio, 1.0f) : 1.0f   ← 下限钳到 1.0
   ```

4. **`HighBrightnessModeController.HdrListener`**
   ```java
   // L483-491
   candidateDesiredHdrSdrRatio = (mIsHdrLayerPresent && mHdrBrightnessCfg != null) ? maxDesiredHdrSdrRatio : 1.0f;
   if (candidateDesiredHdrSdrRatio < 1.0f) { Slog.w(...); candidateDesiredHdrSdrRatio = 1.0f; }
   ```

5. **`LocalDisplayAdapter.handleHdrSdrNitsChanged()` — 下限钳到 1.0（但保留 >1）**
   ```java
   // L880  newHdrSdrRatio = Math.max(1.0f, displayNits / sdrNits);
   ```

6. **`Display$HdrSdrRatioListenerWrapper.mLastReportedRatio = 1.0f`**
   ```java
   // Display.java:1411  初值 1.0，比率若为 1.0 则永不回调
   ```

**没有任何地方把 `hdrSdrRatio` 钳到"上限"**，也没有 `Math.min(..., 1.0f)` 之类的强制。所以"恒为 1"的成因是 **NaN 兜底**，不是上限钳制。

---

## 9. Q7 — binder 实现与触发源

### 9.1 服务端 binder 实现
```java
// DisplayManagerService.DisplayManagerServiceStub（IDisplayManager.Stub 实现类）
// services.jar → DisplayManagerService.java:4708
public float getHighestHdrSdrRatio(int displayId) {
    if (DisplayManagerService.this.mDmsExt != null) return DisplayManagerService.this.mDmsExt.getHighestHdrSdrRatio();
    return 1.0f;
}
```
**注意：AOSP 的 `IDisplayManager` 并没有"注册 HDR/SDR 比率监听"的 binder 方法。**
客户端监听是靠 **显示事件回调**（`IDisplayManagerCallback.onDisplayEvent`）+ 事件码 5 实现的：
```java
// DisplayManagerService.java:2135-2138
public void handleLogicalDisplayHdrSdrRatioChangedLocked(LogicalDisplay display) {
    sendDisplayEventIfEnabledLocked(display, 5);     // 5 = EVENT_DISPLAY_HDR_SDR_RATIO_CHANGED
}
```

### 9.2 触发源（自底向上）
```
SurfaceFlinger / HWC 改变面板/SDR 亮度（不是 HDR 状态！）
  └─ DisplayPowerController → DisplayPowerState.setScreenBrightness / setSdrScreenBrightness
      └─ LocalDisplayAdapter.LocalDisplayDevice.setDisplayBrightness(brightness, sdrBrightness)   L831
          └─ 换算 nits / sdrNits
              └─ [门禁] LocalDisplayDeviceExt.isSupportSdrToHdrRatio()
                  └─ handleHdrSdrNitsChanged(nits, sdrNits)          L874
                      └─ mCurrentHdrSdrRatio = max(1.0, nits/sdrNits)
                          └─ updateDeviceInfoLocked() → DisplayDeviceInfo.hdrSdrRatio
                              └─ DisplayDeviceInfo.diff()  bit16
                                  └─ LogicalDisplayMapper.updateLogicalDisplaysLocked(diff)  event 64
                                      └─ DMS.handleLogicalDisplayHdrSdrRatioChangedLocked → event 5
                                          └─ DisplayManagerGlobal case 5 → DisplayListener.onDisplayChanged
                                              └─ Display$HdrSdrRatioListenerWrapper.onDisplayChanged
                                                  └─ Display.getHdrSdrRatio() 变化 → listener.accept(display)
```
**重要结论：触发源是"亮度变化"，不是"SurfaceFlinger 报 HDR 状态"。**
`DisplayModeDirector` 与 `hdrSdrRatio` 无关（它只管刷新率/模式）。
SurfaceFlinger 的 HDR 状态走的是**另一条路**：`SurfaceControlHdrLayerInfoListener.onHdrInfoChanged(...)`，
直接喂给 `HdrBrightnessModifier` 和 `HighBrightnessModeController`（决定"要不要提亮"），
**不会**写入 `DisplayDeviceInfo.hdrSdrRatio`。这一点很容易混淆，是本项目的关键认知点。

---

## 10. 根因判定：HDR 提亮应该发生在哪一层？为什么现在没发生？

### 10.1 应该发生在哪一层
**`DisplayPowerController` 的 nits 亮度模型层**，具体是 `DisplayBrightnessState` 的
`brightness` 与 `hdrBrightness` 两个字段，最终体现为
`RampAnimator.DualRampAnimator` 的 `target`(面板) 与 `sdrTarget`(SDR) 分离。
- 面板亮度 `target` = `brightnessFromNits(nits × ratio)`
- SDR 亮度 `sdrTarget` = `brightnessFromNits(sdrNits)`（不变）
- 两者之比 = `hdrSdrRatio`

**不在 `Display.getHdrSdrRatio()` 这一层。** 那是**只读**的对外暴露 API，
hack 它只能骗到读这个 API 的应用/系统模块（FkOScreen 现在做的就是这件事），
**改不了 DPC 里 `mScreenBrightness`/`mSdrBrightness` 的实际取值**，所以"能压暗、不能提亮"。

### 10.2 为什么现在没发生（三条独立断点，全断）

| # | 断点 | 证据 | 后果 |
|---|---|---|---|
| A | 内屏 `DisplayDeviceConfig` 退化为 `<config.xml>`：`mHbmData=null`、`mHdrBrightnessData=null`、`mNitsToBacklightSpline=null`、亮度样条恒等 | `dumpsys display` L127-131/171 | `HdrBrightnessModifier.apply()` 首行 return；`getHdrBrightnessFromSdr()` 返回 -1 |
| B | `HdrBrightnessModifier` 的 HDR 图层信息 `mHdrLayerSize=-1.0`、`mMode=NO_HDR` | `dumpsys display` L473-477 | 即使有 config 也不会进 NBM/HBM 模式 |
| C | ColorOS 私有 HDR 提亮 `OplusFeatureHDREnhanceBrightness` 的开关 `oplus.software.display.hdr_enhance_brightness_support` 在本机未配置 | `grep` 全 etc 目录无命中；smali 构造器 | `mEnhanceHDRBrightnessSupport=false` → 不抬 nit |
| D | 比率计算门禁 `isSupportSdrToHdrRatio()` = feature `oplus.software.display.enhance_brightness_with_uidimming` = false | smali `LocalDisplayDeviceExtImpl.isSupportSdrToHdrRatio` | `handleHdrSdrNitsChanged` 从不调用 → NaN |
| E | 内屏亮度应用被 `mDpcExt.handleDisplayChanged()` 接管，SDR 目标值来自 `OplusDisplayBrightnessState.getSdrBrightness()`，而 sdrNit 没有任何地方被降低 | smali `OplusBrightnessPostProcessingManager.convertNitToBrightness` | `mScreenBrightness == mSdrScreenBrightness`（实测 2.0/2.0）→ 比率 1.0 |

### 10.3 为什么用户会看到"压暗"
本机 `Display.getHdrSdrRatio()` 恒 1.0，DPC 两路亮度又相等，
所以**框架层没有产生任何真实的 dimming**。观察到的"压暗"来自框架之外：
- SurfaceFlinger 对 HDR 图层的 tone-mapping / `edrSdrRatio`（SF layer dump 里 `edrSdrRatio=1.000000`）
- 播放器/应用自己做的 HDR→SDR 映射
- 或 FkOScreen 之类 hook 伪造比率后被上层模块读取所产生的效果（"能压暗"）

**提亮需要面板背光真的升高**，这条链路（`hdrBrightness` → `DualRampAnimator.target`）在本机是死的。

---

## 11. 候选 Hook 点清单（LSPosed）

> 优先级：★★★ = 直接见效；★★ = 结构性修复；★ = 辅助/诊断
> 作用域：`system_server`（`android` 进程）为主；部分需要 `com.android.systemui`/目标 App。

### 11.1 ★★★ 直接制造"提亮"：让 DPC 的 SDR 目标亮度低于面板亮度

**H1. `OplusDisplayPowerControllerExtImpl.handleDisplayChanged`**
```
类: com.android.server.display.OplusDisplayPowerControllerExtImpl
方法: public void handleDisplayChanged(boolean isSlowChange, boolean isAutoBrightness,
                                      float brightness, int state, int displayId,
                                      com.android.server.display.brightness.BrightnessReason reason)
      (smali L3530)
Hook: before/after，或在 `IOplusDisplayPowerControllerWrapper.animateScreenBrightness` 之前
预期: 目前 targetBrightness == targetSdrBrightness。要"提亮"必须让 **面板 target 抬高**，
      即修改 `OplusDisplayBrightnessState.getBrightness()`（= brightnessFromNits(nit)）。
```

**H2. `OplusBrightnessPostProcessingManager.convertNitToBrightness`（首选，最干净）**
```
类: com.android.server.display.feature.postprocess.OplusBrightnessPostProcessingManager
方法: private void convertNitToBrightness(OplusDisplayBrightnessState$Builder builder)   (smali L139)
Hook: after；读取 builder.getNit()/getSdrNit()，在 HDR 场景把 nit 乘上目标比率后 setNit(...)
      （等价于把 `OplusFeatureHDREnhanceBrightness` 的行为手动补上）
预期: brightness↑，sdrBrightness 不变 ⇒ `DisplayPowerState.mScreenBrightness > mSdrScreenBrightness`
      ⇒ `handleHdrSdrNitsChanged` 若被放行则比率 > 1，面板真实变亮。
注意: 需要同时让 H4 的开关为 true，否则比率仍 NaN（但不影响提亮本身）。
```

**H3. `OplusFeatureHDREnhanceBrightness` 的开关与状态**
```
类: com.android.server.display.feature.postprocess.OplusFeatureHDREnhanceBrightness
字段: mEnhanceHDRBrightnessSupport:Z  (构造器里由 OplusFeatureConfigManager.hasFeature 赋值)
字段: mNeedToEnhanceBrightness:Z, mEnhanceFactor:F, mIsPlayingHDR:I
Hook: 构造后把 mEnhanceHDRBrightnessSupport 置 true；必要时把 mEnhanceFactor 设为目标值
预期: 走 ColorOS 原生 HDR 提亮（最"原厂"的方式），无需自己算 nits
```

### 11.2 ★★★ 让 `hdrSdrRatio` 真实可见（修 Q1/Q2/Q3）

**H4. `LocalDisplayDeviceExtImpl.isSupportSdrToHdrRatio`**
```
类: com.android.server.display.LocalDisplayDeviceExtImpl   (oplus-services.jar)
方法: public boolean isSupportSdrToHdrRatio()      (smali L1025)
Hook: 直接 return true
预期: `LocalDisplayAdapter.setDisplayBrightness` L855 的门禁放行 →
      `handleHdrSdrNitsChanged` 被调用 → `mCurrentHdrSdrRatio` 从 NaN 变为真实值
前提: nits != sdrNits 才有 >1；否则只会得到 1.0（但至少不再 NaN，`isHdrSdrRatioAvailable()` 变 true）
```

**H5. `LocalDisplayAdapter.LocalDisplayDevice.handleHdrSdrNitsChanged`**
```
类: com.android.server.display.LocalDisplayAdapter$LocalDisplayDevice
方法: void handleHdrSdrNitsChanged(float displayNits, float sdrNits)   (L874)
Hook: before，自行设置 mCurrentHdrSdrRatio 并 updateDeviceInfoLocked()；
      或 after，强制 mCurrentHdrSdrRatio = 期望值（如 1.5625）
预期: dumpsys / DisplayInfo / Display.getHdrSdrRatio() 三处一致，且能触发 event 5 广播
```

**H6. `LocalDisplayAdapter.LocalDisplayDevice.setDisplayBrightness`（nits 侧注入）**
```
类: com.android.server.display.LocalDisplayAdapter$LocalDisplayDevice  (内部类，L831 附近)
方法: setDisplayBrightness(float brightnessState, float sdrBrightnessState)
Hook: 改写入参（如 sdr = brightness / 1.5625），使 nits/sdrNits 天然 > 1
预期: 比率与面板亮度同时自洽（这是"正确"的做法：面板不变，SDR 被压低）
```

### 11.3 ★★ 补上缺失的 display device config（结构性修复）

**H7. `DisplayDeviceConfig.getHdrBrightnessFromSdr`**
```
类: com.android.server.display.DisplayDeviceConfig
方法: public float getHdrBrightnessFromSdr(float brightness, float maxDesiredHdrSdrRatio)
      public float getHdrBrightnessFromSdr(float brightness, float maxDesiredHdrSdrRatio, Spline sdrToHdrSpline)   (L366/L371)
Hook: after；若返回 -1.0f 则自行用设备 XML 里的 sdrHdrRatioMap（2nits→8.0, 500nits→1.5）线性插值计算
      hdrNits = nits * min(spline(nits), maxDesiredHdrSdrRatio) 并返回 brightnessFromNits(hdrNits)
预期: 让 AOSP 原生路径 B（HighBrightnessModeController）能算出提亮值
```

**H8. `DisplayDeviceConfig.getHdrBrightnessData` / `getHighBrightnessModeData`**
```
类: com.android.server.display.DisplayDeviceConfig
方法: public HdrBrightnessData getHdrBrightnessData()          (L589)  → 本机 null
      public HighBrightnessModeData getHighBrightnessModeData() (L504) → 本机 null
Hook: after；返回自行构造的 HdrBrightnessData（含 sdrToHdrRatioSpline、minimumHdrPercentOfScreenForNbm/Hbm、
      hbmTransitionPoint、maxBrightnessLimits…）
预期: `HdrBrightnessModifier.apply()` 不再首行 return，AOSP 原生 HDR 提亮复活
```

### 11.4 ★★ 打开客户端 API（让应用/系统模块认为 HDR 比率可用）

**H9. `Display.isHdrSdrRatioAvailable` / `getHdrSdrRatio`（FkOScreen 现做法，仅"骗"）**
```
类: android.view.Display
方法: public boolean isHdrSdrRatioAvailable()   (L580)
      public float   getHdrSdrRatio()           (L589)
Hook: 返回 true / 目标比率（如 1.5625）
预期: 验收 App 变绿、读该 API 的应用会做相应 SDR 压低；**但不改变面板亮度**
```

**H10. `DisplayInfo.copyFrom` / `DisplayInfo.hdrSdrRatio` 字段**
```
类: android.view.DisplayInfo
方法: public void copyFrom(DisplayInfo other)   (L218)
Hook: after，反射把 hdrSdrRatio 设为目标值（FkOScreen 现做法）
注意: 这只是把客户端副本改掉，system_server 的 DisplayDeviceInfo 仍是 NaN
```

### 11.5 ★ 诊断 / 观测点

| 类.方法 | 用途 |
|---|---|
| `DisplayPowerController.animateScreenBrightness(float target, float sdrTarget, float rate)` (L4447) | 观察 target vs sdrTarget 是否分离 |
| `DisplayPowerState.setSdrScreenBrightness(float)` (L177) | 确认 SDR 通道是否被单独设置 |
| `HdrBrightnessModifier.apply(...)` (L119) | 确认是否首行 return（mHdrBrightnessData==null） |
| `HighBrightnessModeController.getHdrBrightnessValue()` (L134) | 观察是否返回 -1 走 fallback |
| `SurfaceControlHdrLayerInfoListener.onHdrInfoChanged`（抽象类，需 hook 子类） | 观察 SF 是否真的上报 HDR 图层 |
| `DisplayManagerService.handleLogicalDisplayHdrSdrRatioChangedLocked` (L2136) | 确认 event 5 是否发出 |

### 11.6 Hook 实施注意
1. **作用域**：H1–H8、H10 在 `android`（system_server）进程；H9 需同时 hook `system_server` 与目标 App 进程。
2. `HdrBrightnessModifier` / `HighBrightnessModeController` / `BrightnessRangeController` 都是 **package-private 类**，
   LSPosed 需 `XposedHelpers.findClass("com.android.server.display....", cl)` + `setAccessible(true)`。
3. `HdrBrightnessModifier.apply` 的第二参数是 `DisplayBrightnessState.Builder`，
   要写 `hdrBrightness` 需反射调用 `setHdrBrightness(float)`（返回 Builder）。
4. `OplusFeatureHDREnhanceBrightness` 内部有 synchronized 与 Handler，改字段建议在 `init`/构造 after 一次性完成。
5. 修改 `mCurrentHdrSdrRatio` 后必须调用 `updateDeviceInfoLocked()`，否则 dumpsys 不会变、event 64 不会发。

---

## 12. 建议的验证顺序（下一步）

1. **先验证 A**：hook `LocalDisplayDeviceExtImpl.isSupportSdrToHdrRatio()` → true，
   播放 HDR 片源，看 `dumpsys display` 的 `hdrSdrRatio` 是否变成非 NaN。
   - 若变成 `1.0` ⇒ 说明 `mScreenBrightness == mSdrScreenBrightness`（断点 E），提亮确实没人做。
   - 若仍是 `NaN` ⇒ 还有别的门禁（如 nits/sdrNits 为 0），需 hook `setDisplayBrightness` 打日志。
2. **再验证 C**：hook `OplusFeatureHDREnhanceBrightness` 把 `mEnhanceHDRBrightnessSupport` 置 true，
   观察 `mNit`/`mEnterNit` 日志与面板亮度是否上升。
3. **最后做 H2**（`convertNitToBrightness` 里把 nit 乘上目标比率）作为兜底方案——
   这一条不依赖任何 vendor 配置，纯靠算术就能让面板变亮，最稳妥。
4. 若要"原厂体验"，应补齐 `/vendor/etc/displayconfig/display_id_<physical-display-id>.xml`
   （可直接复用 `display_id_4630946450791512195.xml` 的 `<highBrightnessMode><sdrHdrRatioMap>`，
   但 `<screenBrightnessMap>` 的 nits 必须按本面板 540nits 峰值重标定）。

---

## 13. 附：关键文件索引

| 内容 | 路径 |
|---|---|
| framework 反编译 | `analysis/framework/out/framework/sources/` |
| services 反编译 | `analysis/framework/out/services/sources/` |
| oplus-services 反编译 | `analysis/framework/out/oplus-services/sources/` |
| OPLUS 关键类 smali | `/tmp/oplus-smali/com/android/server/display/` |
| 设备 display config XML（非内屏） | `analysis/framework/device/ddc_4630946450791512195.xml` |
| dumpsys display 全文 | `/tmp/dumpsys_display.txt` |
| jadx 日志 | `analysis/framework/logs/` |
