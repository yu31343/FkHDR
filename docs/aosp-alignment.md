# AOSP 对齐记录（HDR / hdrSdrRatio）

> **最终方案（v1.6.1）**：不写任何系统标志位，只做两件事 ——
> ① 替换 `mSdrToHdrRatioSpline`（等效旧模块改 XML）
> ② 接管 `handleHdrSdrNitsChanged`，用 AOSP 原版逻辑（等效旧模块删门禁代码）
> 提亮与比值全部由系统自己完成。

## 1. AOSP android15 原版

`services/core/java/com/android/server/display/LocalDisplayAdapter.java`

```java
// :921
private void setDisplayBrightness(float brightnessState, float sdrBrightnessState) {
    final float nits    = backlightToNits(brightnessToBacklight(brightnessState));
    final float sdrNits = backlightToNits(brightnessToBacklight(sdrBrightnessState));
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
```

提亮由 `HighBrightnessModeController.HdrListener` 完全事件驱动（SF 回调 →
`mIsHdrLayerPresent` / `mMaxDesiredHdrSdrRatio` → `onBrightnessChanged` →
`getHdrBrightnessValue()` → `getHdrBrightnessFromSdr()` 用 spline 算目标亮度）。

## 2. 本机差异

`handleHdrSdrNitsChanged` 被厂商加了两道条件：

```java
if (displayNits != -1 && sdrNits != -1
        && (mBacklightAdapter.mUseSurfaceControlBrightness
            || mBacklightAdapter.mForceSurfaceControl)   // 厂商加
        && LocalDisplayAdapter.this.mSupportUltraHDR) {  // 厂商加
    ratio = max(1, nits/sdrNits);
} else {
    ratio = NaN;
}
```

本机两个标志都是 false ⇒ 比值恒 NaN ⇒ `Display.isHdrSdrRatioAvailable()`
（= `!Float.isNaN(hdrSdrRatio)`）恒 false ⇒ 验收 App 不订阅 ⇒ 永远显示 1.0。

## 3. 旧模块做了什么（smali / XML 逐字节核实）

1. `product/etc/displayconfig/display_id_*.xml`：**只改一个数**
   `sdrHdrRatioMap` 第一点 `hdrRatio 1.0 → 5.0`（其余 1949 字节相同）
2. `services.jar`：`handleHdrSdrNitsChanged` 里**三道门禁整块删除**，方法体与 AOSP 等价

## 4. 本模块的实现（v1.6.1）

| # | 钩子 | 作用 |
|---|---|---|
| ① | 替换 `DisplayDeviceConfig.mSdrToHdrRatioSpline` | 等效改 XML。`Spline` 是**抽象类**，必须用 `Spline.createMonotoneCubicSpline(x=[2,650], y=[cap,1])`，不能用 Proxy |
| ② | **接管 `handleHdrSdrNitsChanged`** | 等效删门禁：自己按 AOSP 算 `max(1, nits/sdrNits)`、只在变化时写字段 + `updateDeviceInfoLocked()`，然后 `param.result = null` 跳过原方法 |
| ③ | `updateDeviceInfoLocked` 前把 NaN 归一成 1.0 | 安全网 |
| ④ | 只读诊断 `PROBE` | 打印入参 / 当前比值 / 门禁真实值 |

**绝不写 `mSupportUltraHDR` / `mForceSurfaceControl`。**

## 5. 三个致命坑（都已踩过并记录）

### 坑 1：写 `mForceSurfaceControl=true` → 屏幕点不亮

该标志会把亮度输出从 **sysfs 背光通路**切到 **SurfaceControl 通路**。
本机 `getDisplayBrightnessSupport()` 返回 false，说明它走 sysfs；
强切过去后真实背光收不到指令 → 黑屏、点不亮（已实测）。
**旧模块从不碰这个标志，它删的是判断代码。**

### 坑 2：`getSyncRoot()` 在 `DisplayAdapter` 上，不在 device 上

`XposedHelpers.callMethod(dev, "getSyncRoot")` 永远失败 → 无锁写
`mCurrentHdrSdrRatio` → 与显示线程竞态 → `system_server` 崩溃：

```
ArrayIndexOutOfBoundsException: length=1; index=1
  at LocalDisplayAdapter$LocalDisplayDevice.getDisplayDeviceInfoLocked(LocalDisplayAdapter.java:725)
  at DisplayDeviceRepository.handleDisplayDeviceChanged
```

正确取法：`dev.this$0`（adapter）→ `getSyncRoot()` 或 `mSyncRoot`。
同时删掉了 `getDisplayDeviceInfoLocked` 的钩子（它在崩溃栈里，且已无必要）。

### 坑 3：LSPosed 缓存的 APK 路径会失效

每次 `adb install -r` 后 `/data/app/~~xxx==` 目录名会变，
LSPosed 数据库里仍是旧路径 → 启动时报
`Failed to parse package archive .../base.apk` → **整个模块被静默跳过**。

**每次安装后必须核对：**

```bash
pm path io.github.benbaobaoshigemi.fkhdr
lspctl --json module show io.github.benbaobaoshigemi.fkhdr   # 看 apkPath
```

不一致就再 `install -r` 一次（LSPosed 会更新记录），然后重启。

## 6. 实测结果（Redmi xaga / HyperOS 3 / Android 15）

| 状态 | hdrSdrRatio | mScreenBrightness | hbmMode |
|---|---|---|---|
| 播放 HDR10 | **3.5368757** | 0.013215097（≈13 nits） | **hdr** |
| 停止后 | **1.0** | 0.0034099757（≈3.4 nits） | **off** |

- 熄屏/亮屏正常，屏幕可正常点亮
- 无 `system_server` 崩溃、无 app 崩溃
- 验收 App「HDR SDR调光支持」= ✔️

## 7. 日志对照

| 日志 | 含义 |
|---|---|
| `specific-chain probe -> true [mSdrToHdrRatioSpline=true handleHdrSdrNitsChanged=true]` | 两个锚点齐全 |
| `specific chain ① sdrHdrRatioSpline replaced (Spline x=[2.0,650.0] y=[5.0,1.0])` | 曲线已替换 |
| `chain = SPECIFIC-ONLY(系统自算, 无补偿钩子)` | 走 AOSP 原生链路 |
| `ratio bypass nits=11.11 sdrNits=3.7 -> 3.0` | 比值由我们按 AOSP 公式算出并写入 |
| `PROBE ... [use= force= ultra=]` | 门禁运行时真实值（只读诊断） |
