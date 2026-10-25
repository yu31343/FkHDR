# AOSP 对齐记录（HDR / hdrSdrRatio）

> **结论**：本机 HDR 比值恒为 1 的唯一原因，是厂商在
> `LocalDisplayDevice$1.handleHdrSdrNitsChanged` 里多加了一道与条件。
> 只要把 `mForceSurfaceControl` / `mSupportUltraHDR` 置位，
> 设备行为就与 AOSP android15 **完全一致**，比值与提亮全部由系统自己算。

## 1. AOSP android15 的原始实现

`services/core/java/com/android/server/display/LocalDisplayAdapter.java`

```java
// :921 setDisplayBrightness —— 只有一道门
private void setDisplayBrightness(float brightnessState, float sdrBrightnessState) {
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

提亮侧由 `HighBrightnessModeController` 完全事件驱动：

```java
// HdrListener.onHdrInfoChanged(...)  —— SurfaceFlinger 回调
mIsHdrLayerPresent = numberOfHdrLayers > 0
        && (maxW * maxH) >= (mWidth * mHeight * mHbmData.minimumHdrPercentOfScreen);
mMaxDesiredHdrSdrRatio = mIsHdrLayerPresent ? maxDesiredHdrSdrRatio : 1.0f;
onBrightnessChanged(...);                       // 触发重算

// calculateHighBrightnessMode()
if (mIsHdrLayerPresent) return HIGH_BRIGHTNESS_MODE_HDR;

// getHdrBrightnessValue()
mHdrBrightnessCfg.getHdrBrightnessFromSdr(mBrightness, mMaxDesiredHdrSdrRatio);

// DisplayDeviceConfig.getHdrBrightnessFromSdr
float ratio = Math.min(mSdrToHdrRatioSpline.interpolate(nits), maxDesiredHdrSdrRatio);
return backlightToBrightness(nitsToBacklight(nits * ratio));
```

## 2. 本机的差异

反编译 `/system/framework/services.jar` 确认，
`LocalDisplayAdapter$LocalDisplayDevice$1.handleHdrSdrNitsChanged` 被改成了：

```java
if (displayNits != -1 && sdrNits != -1
        && (mBacklightAdapter.mUseSurfaceControlBrightness
            || mBacklightAdapter.mForceSurfaceControl)   // ← 厂商加
        && LocalDisplayAdapter.this.mSupportUltraHDR) {  // ← 厂商加
    ratio = max(1, nits/sdrNits);
} else {
    ratio = NaN;
}
```

两个标志的来源：

| 标志 | 赋值处 | 本机值 |
|---|---|---|
| `mUseSurfaceControlBrightness` | `BacklightAdapter` 构造：`SurfaceControlProxy.getDisplayBrightnessSupport(token)` | **false** |
| `mForceSurfaceControl` | `LocalDisplayDevice.loadDisplayDeviceConfig()`：`config.hasQuirk("canSetBrightnessViaHwc")` | **false** |
| `mSupportUltraHDR` | `LocalDisplayAdapter` 内部（小米 HDR 开关） | false |

⇒ 比值恒 NaN ⇒ `Display.isHdrSdrRatioAvailable()`（= `!Float.isNaN(hdrSdrRatio)`）恒 false
⇒ 验收 App 不注册监听 ⇒ 界面永远停在 1.0。

## 3. 旧模块做了什么（已用 smali / XML 逐字节核实）

1. 替换 `product/etc/displayconfig/display_id_*.xml`：**只改了一个数**
   `sdrHdrRatioMap` 第一个点 `hdrRatio 1.0 → 5.0`（其余 1949 字节完全相同）
2. 替换 `services.jar`：`handleHdrSdrNitsChanged` 里**三道门禁整块删除**

```diff
- if-eqz v1, :cond_35                    # displayNits != -1
+ if-eqz v1, :cond_13
- if-eqz v0, :cond_35                    # sdrNits != -1
- ... -$$Nest$fgetmUseSurfaceControlBrightness
- ... -$$Nest$fgetmForceSurfaceControl
- ... -$$Nest$fgetmSupportUltraHDR
+ if-eqz v0, :cond_13
```

改完后方法体与 AOSP 逐字节等价。整个 `classes2.dex` 的 `com/android/server/display/`
包下只有这一个文件存在实质差异。

## 4. 本模块的对齐方案（v1.5.5 起）

**启动时一次性判定，判定成功就不安装任何补偿钩子。**

```
specificChainPossible():
    LocalDisplayAdapter.mSupportUltraHDR         字段存在？
    BacklightAdapter.setForceSurfaceControl(Z)   方法存在？
    DisplayDeviceConfig.mSdrToHdrRatioSpline     字段存在？
```

三者齐全 → `chain = SPECIFIC-ONLY`，只装 5 个钩子：

| 钩子 | 作用 | 关键坑 |
|---|---|---|
| `LocalDisplayAdapter` 构造 → `mSupportUltraHDR=true` | 开门禁 | — |
| **`BacklightAdapter` 构造 → `mForceSurfaceControl=true`** | 开门禁 | **必须挂构造函数**：`setForceSurfaceControl` 只在 `loadDisplayDeviceConfig()` 里调用，而适配器构造会把字段初始化成 false；只要适配器被重建一次，补写就丢了 |
| `DisplayDeviceConfig.mSdrToHdrRatioSpline` 替换 | 等效旧模块改 XML | `android.util.Spline` 是**抽象类**，不能用 Proxy；必须用 `Spline.createMonotoneCubicSpline(x,y)` |
| `updateDeviceInfoLocked` NaN→1.0 | 让 App 订阅 | — |
| `getDisplayDeviceInfoLocked` NaN→1.0 | 真正的拷贝点 | — |

另外挂一个**只读**诊断钩子 `handleHdrSdrNitsChanged`，打印入参与门禁真实值，
不改行为。

## 5. 实测结果（Redmi xaga / HyperOS 3 / Android 15）

| 状态 | hdrSdrRatio | mScreenBrightness | hbmMode |
|---|---|---|---|
| 播放 HDR10 | **4.9895067** | 0.018172512（18 nits） | **hdr** |
| 停止后 | **1.0** | 0.0034099757（3.4 nits） | **off** |

验收 App「HDR SDR调光支持」：**✔️**

诊断日志样例：

```
PROBE handleHdrSdrNitsChanged(nits=11.12 sdrNits=3.7) -> current=3.01 [use=false force=true ultra=true]
```

## 6. 日志对照表

| 日志 | 含义 |
|---|---|
| `specific-chain probe -> true [...]` | 三个锚点齐全 |
| `specific chain ① sdrHdrRatioSpline replaced (Spline x=[2.0,650.0] y=[5.0,1.0])` | 曲线已替换 |
| `AOSP-align ①' BacklightAdapter ctor -> mForceSurfaceControl=true` | 门禁①已开 |
| `specific chain ② mSupportUltraHDR forced true` | 门禁②已开 |
| `chain = SPECIFIC-ONLY(系统自算, 无补偿钩子)` | 走 AOSP 原生链路 |
| `PROBE ... [use= force= ultra=]` | 门禁运行时真实值（诊断用） |
