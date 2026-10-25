# AOSP 对齐记录（HDR / hdrSdrRatio）

> 结论先行：本机 HDR 比值恒为 NaN，**唯一原因**是厂商在
> `LocalDisplayDevice$1.handleHdrSdrNitsChanged` 里多加了一道与条件。
> 把两个标志置位后，设备行为与 AOSP android15 **完全一致**，模块不再需要任何干预。

## 1. AOSP android15 的原始实现

`services/core/java/com/android/server/display/LocalDisplayAdapter.java`

```java
// :921 setDisplayBrightness —— 只有一道门
private void setDisplayBrightness(float brightnessState, float sdrBrightnessState) {
    ...
    final float backlight    = brightnessToBacklight(brightnessState);
    final float sdrBacklight = brightnessToBacklight(sdrBrightnessState);
    final float nits         = backlightToNits(backlight);
    final float sdrNits      = backlightToNits(sdrBacklight);

    mBacklightAdapter.setBacklight(sdrBacklight, sdrNits, backlight, nits);

    if (getDisplayDeviceConfig().hasSdrToHdrRatioSpline()) {   // ← 唯一的门
        handleHdrSdrNitsChanged(nits, sdrNits);
    }
}

// :982
void handleHdrSdrNitsChanged(float displayNits, float sdrNits) {
    final float newHdrSdrRatio;
    if (displayNits != INVALID_NITS && sdrNits != INVALID_NITS) {
        newHdrSdrRatio = Math.max(1.f, displayNits / sdrNits);
    } else {
        newHdrSdrRatio = Float.NaN;
    }
    if (!BrightnessSynchronizer.floatEquals(mCurrentHdrSdrRatio, newHdrSdrRatio)) {
        synchronized (getSyncRoot()) {
            mCurrentHdrSdrRatio = newHdrSdrRatio;
            updateDeviceInfoLocked();
        }
    }
}

// :778 DisplayInfo 的唯一拷贝点
mInfo.hdrSdrRatio = mCurrentHdrSdrRatio;
```

HDR 提亮由 `HighBrightnessModeController` 完全事件驱动：

```java
// HdrListener.onHdrInfoChanged(...)  —— 由 SurfaceFlinger 回调
mIsHdrLayerPresent = numberOfHdrLayers > 0
        && (maxW * maxH) >= (mWidth * mHeight * mHbmData.minimumHdrPercentOfScreen);
mMaxDesiredHdrSdrRatio = mIsHdrLayerPresent ? maxDesiredHdrSdrRatio   // ← 来自 SF/HWC
                                          : DEFAULT_MAX_DESIRED_HDR_SDR_RATIO;
onBrightnessChanged(...);            // 触发重算

// calculateHighBrightnessMode()
if (mIsHdrLayerPresent) return HIGH_BRIGHTNESS_MODE_HDR;

// getHdrBrightnessValue()
mHdrBrightnessCfg.getHdrBrightnessFromSdr(mBrightness, mMaxDesiredHdrSdrRatio);

// DisplayDeviceConfig
float ratio = Math.min(mSdrToHdrRatioSpline.interpolate(nits), maxDesiredHdrSdrRatio);
return backlightToBrightness(nitsToBacklight(nits * ratio));
```

注意 android15 里 `DEFAULT_MAX_DESIRED_HDR_SDR_RATIO = 1.0f`，
所以**没有 HDR 图层时系统不会提亮**，这是 AOSP 的既定行为。

## 2. 本机（HyperOS 3 / Android 15 / MTK）的差异

反编译 `/system/framework/services.jar`（stock，32,491,458 B）确认，
`LocalDisplayAdapter$LocalDisplayDevice$1.handleHdrSdrNitsChanged` 被改成了：

```smali
cmpl-float v1, p1, v0          # displayNits != -1
if-eqz v1, :cond_35
cmpl-float v0, p2, v0          # sdrNits != -1
if-eqz v0, :cond_35
... -$$Nest$fgetmUseSurfaceControlBrightness
if-nez v0, :cond_22
... -$$Nest$fgetmForceSurfaceControl          # ← 厂商加的
if-eqz v0, :cond_35
:cond_22
... -$$Nest$fgetmSupportUltraHDR              # ← 厂商加的
if-eqz v0, :cond_35
const/high16 v0, 0x3f800000   # max(1.0f, nits/sdrNits)
:cond_35
const/high16 v0, 0x7fc00000   # Float.NaN
```

即：

```java
if (displayNits != -1 && sdrNits != -1
        && (mBacklightAdapter.mUseSurfaceControlBrightness
            || mBacklightAdapter.mForceSurfaceControl)
        && LocalDisplayAdapter.this.mSupportUltraHDR) {
    ratio = max(1, nits/sdrNits);
} else {
    ratio = NaN;
}
```

两个标志的来源：

| 标志 | 赋值处 | 本机值 |
|---|---|---|
| `mUseSurfaceControlBrightness` | `BacklightAdapter` 构造：`SurfaceControlProxy.getDisplayBrightnessSupport(token)` | **false** |
| `mForceSurfaceControl` | `LocalDisplayDevice` 构造：`mDisplayDeviceConfig.hasQuirk("canSetBrightnessViaHwc")` | **false** |
| `mSupportUltraHDR` | `LocalDisplayAdapter` 内部（小米 HDR 开关） | false |

⇒ `(use || force)` 恒 false ⇒ 比值恒 NaN ⇒
`Display.isHdrSdrRatioAvailable()`（= `!Float.isNaN(hdrSdrRatio)`）恒 false ⇒
验收 App 不注册 `registerHdrSdrRatioChangedListener` ⇒ 界面永远停在 1.0。

### 2.1 对旧模块的重新评估

设备上原来的 `hdr_sdr_ratio_fix` 模块做了两件事：

1. 改 `display_id_*.xml` 的 `sdrHdrRatioMap`：`2 nits → 5.0`，`650 nits → 1.0`
2. 反编译 services.jar 删掉 `&& LocalDisplayAdapter.mSupportUltraHDR`

**第 2 条在本机是无用补丁** —— 因为 `(use || force)` 仍然是 false，
删掉 `mSupportUltraHDR` 之后条件依旧不成立，比值仍然 NaN。
旧模块真正生效的只有第 1 条（提亮），比值从未被修好。

## 3. 对齐方案（v1.4.0）

不改文件、不重打包，运行时把厂商多出来的两个标志置位，让设备回到 AOSP 语义：

| 步骤 | 实现 |
|---|---|
| ① `mSupportUltraHDR = true` | hook `LocalDisplayAdapter` 构造函数（after），写字段 |
| ①' `mForceSurfaceControl = true` | hook `LocalDisplayDevice` 构造函数（after），调 `mBacklightAdapter.setForceSurfaceControl(true)` |
| ② 面板比值曲线 | 替换 `DisplayDeviceConfig.mSdrToHdrRatioSpline`（等效旧模块改 XML，cap 由 App 配，默认 5.0） |
| ③ NaN 安全网 | `updateDeviceInfoLocked` / `getDisplayDeviceInfoLocked` 里把 NaN 归一成 1.0 |

三者都成功 ⇒ `chain = SPECIFIC(系统自算)`，模块**不再**：推亮度、强制 HBM、
写 `mCurrentHdrSdrRatio`、主动点火 DPC。全部由 AOSP 原生链路完成。

自检：HDR 出现 2 秒后若系统仍只报 1.0（说明 SF 没把 `maxDesiredHdrSdrRatio` 送下来），
本会话自动退回 GENERIC 通用兜底，并打印
`specific chain ineffective after 2s -> fallback to GENERIC for this session`。

## 4. 关键日志对照

| 日志 | 含义 |
|---|---|
| `AOSP-align ①' mForceSurfaceControl = true` | 门禁打开 |
| `specific chain ① sdrHdrRatioSpline replaced (cap=5.0)` | 曲线替换成功 |
| `specific chain ② mSupportUltraHDR forced true` | 门禁打开 |
| `chain = SPECIFIC(系统自算)` | 三个条件全满足，走 AOSP 原生链路 |
| `chain = GENERIC(模块代劳)` | 任一条不满足，模块兜底 |
| `specific chain ineffective after 2s ...` | 专项链路自检失败，本会话回退 |
| `specific gate [b= sdr= use= force=]` | 只读观测：门禁字段实际值 |
