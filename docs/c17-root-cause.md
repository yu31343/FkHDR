# ColorOS 17（Android 17 / SDK 37）HDR 提亮失效根因

> ⚠️ 历史记录（截至 v1.6.1）：本文的字段名、版本号、日志样例与实现结论只描述当时的样本；当前 FkHDR v1.6.2 的行为以 [AOSP 对齐记录](aosp-alignment.md) 与 [hdr-module/README.md](../hdr-module/README.md) 为准。

设备：OPD2404，`ro.build.version.oplusrom=V17.0.0`，`sdk=37`
对照：ColorOS 16（`V16.0.0`，`sdk=36`）

## 一、现象（用户日志实据）

模块 v1.1.3 在 C17 上：

```
FkOScreen:HdrFix: hooked feature service
FkOScreen:HdrFix: native driver hooked (OplusFeatureHDREnhanceBrightness)
FkOScreen:HdrFix: hooked WindowManagerService
FkOScreen:HdrFix: init done, nativeDriver=true
FkOScreen:HdrFix: HDR -> true          ← 状态识别正常
FkOScreen:HdrFix: HDR -> false
```

hook 全"成功"、HDR 状态也识别到了，但 **亮度一次都没重算**：
`full.log` 里 `DpcExtImpl: Animating brightness` 在 HDR 窗口内 0 条；
`OplusFeatureHDREnhanceBrightness: checkIsPlayingHDR` 一行都没有
（C16 正常机每次刷新率模式切换都会打这一行）。

## 二、根因（C17 二进制 + 运行时 dumpsys 双向确认）

### 1. C17 的 feature 查询换成了「本地缓存」

```java
// com.oplus.content.OplusFeatureConfigManager   (oplus-framework.jar, C17)
public boolean hasFeature(String name) {
    return this.mCache.query(name);      // 纯本地缓存，根本不经过 ConfigManagerService
}
```

C16 版本是走 Service 的，模块 hook 的 `OplusFeatureConfigManagerService.hasFeature` 在 C16 有效。
**C17 的这次改动让"补 feature"这一招彻底失效。**

### 2. 于是驱动类压根没被 new 出来

```java
// com.android.server.display.feature.postprocess.OplusDisplayFeatureManager  (C17:485-511)
private void constructOptionalEnhanceFeatures(Looper workLooper) {
    if (this.mEnhanceHDRBrightnessSupport) {
        this.mHDREnhanceBrightness = new OplusFeatureHDREnhanceBrightness(...);   // :490
    }
    ...
}
```

```java
// 同文件 :128
this.mEnhanceHDRBrightnessSupport =
    OplusFeatureConfigManager.getInstacne().hasFeature("oplus.software.display.hdr_enhance_brightness_support");
```

设备自己的 dumpsys 直接给出答案：

```
mEnhanceHDRBrightnessSupport = false     ← 驱动从未构造
mSupportEdrEnhance = true                ← EdrEnhanceBrightnessManager 在链上
mSupportEdrWithUiDimming = true
mSupportPwEdrEnhance = false
mSupportVideoAssist = false
```

所以模块日志里 `driver ready` 一次都没出现，watcher 里 `hdrEnhance?.let{...}` 恒为空，**整条原生提亮链静默消失**。
日志表现（hook 都成功、HDR 也识别）与"什么都没做"完全一致 —— 这就是最难查的地方。

### 3. C17 还做了这些改动（已核对）

| 项 | C16 | C17 |
|---|---|---|
| `OplusFeatureEdrEnhanceBrightness` | 存在（EDR 主提亮路径） | **已删除** |
| 替代者 | — | `...postprocess.edr.EdrEnhanceBrightnessManager` |
| 新增 | — | `OplusFeatureVideoAssistHDRBrightness`（视频助手/Cinema HDR 白名单） |
| `OplusDisplayBrightnessState$Builder` | — | **零变化**（`setNit` 仍会顺手改 `sdrNit`） |
| `convertNitToBrightness` | private | 仍在、仍 private |
| `OplusBrightnessPostProcessingManager` 构造器 | 4 参（含 Handler） | 改为 `(displayId, featureManager, model, dpcWrapper)` |
| `OplusFeatureHDREnhanceBrightness` 字段 | 7 个 | 6 个（`mLastEnhancedStatus` 删除，其余全在） |
| `applyHdrEnhance` | 内联在 `postProcessing` | 抽成私有方法，仍**不调** `setHasEdrProcessed` |
| `mEnhanceFactor` | 构造器 `=1.0f` | HDREnh 仍 0.0f；VideoAssist **默认 0.0f**（坑） |
| 亮度动画日志 tag | `DpcExtImpl` | `AnimationProvider[display=0][Primary][AnimationScreenBrightness]` |

### 4. 比率链路本体没坏，但客户端通知机制变了（重要）

| 项 | C16 | C17 |
|---|---|---|
| `handleHdrSdrNitsChanged` | 唯一计算点 | **仍在、逐行等价、仍是唯一计算点** |
| 公式 | `max(1.0, nits/sdrNits)` | **完全未变**，无新增上限 |
| `isSupportSdrToHdrRatio()` | 只看 `enhance_brightness_with_uidimming` | 改成 `mSupportEdrWithUiDimming \|\| mSupportEdrNoUiDimming`（多一条 dolby OR） |
| `sdrBrightness` | 直接用 | 先 `min(sdrBrightnessState, brightnessState)` 钳一次 |
| `DisplayManagerService#handleLogicalDisplayHdrSdrRatioChangedLocked` | 存在 | **已删除**，并入 `getSendDisplayEventMaskLocked` |
| display 事件常量 | 序号制，HDR=5，内部位=16 | **位掩码制**，HDR=**64**，内部位=**128**（全量重编号） |
| 客户端收事件 | `registerDisplayListener` 默认能收 | **必须额外注册内部位 128**，否则收不到 |

也就是说：**C17 上系统侧的比率是对的，但普通 `registerDisplayListener` 收不到 "比率变化" 事件了**。
第三方检测 App（如 HDR能力检测 V0.7）若只注册默认过滤器、且不在 `onDisplayChanged` 里重读，
就会一直显示旧值 `1.00000` —— 看起来像"没修好"，其实系统里已经变了。
正确读法：`Display#registerHdrSdrRatioChangedListener`（内部以 0x88=136=128|8 注册），
或在 **HDR 开着的时候** 直接看 `dumpsys display` 里的 `hdrSdrRatio`。

## 三、修复（v1.3.0）

1. **补 feature 的两条路都兜住**
   `com.oplus.content.OplusFeatureConfigManager#hasFeature` 与
   `com.oplus.content.OplusFeatureCache#query` → 对 3 个缺失 feature 返回 true。
2. **双保险**：hook `constructOptionalEnhanceFeatures`（C17）/ `addPostprocessFeatures`（C16），
   在读取 `mEnhanceHDRBrightnessSupport` 之前把它掰成 true，
   OPPO 以后再换 feature 实现也不会再失效。
3. **通用兜底**（不依赖任何 OPPO 类）：在 `convertNitToBrightness` 之前按面板上限抬 nit，
   并 `setHasEdrProcessed()` 保住 SDR 白点；HDR 状态变化时主动 `updatePowerState()` 点火。
4. **每次后处理重新压制** `mEnterWhiteApp=true` / `mEnhanceFactor=factor()` /
   `mNeedToEnhanceBrightness=true`，抵消 C17 新增的"按包名取倍率"白名单逻辑。

## 四、C17 实测（v1.2.0，兜底层）

```
init done [features=2 ratioFlag=1 native=true boost=2 dpc=true wms=1] factor=100.0
HDR -> true(focus=true) [driver=null]        ← 印证驱动为空
boost nit 53.39 -> 846.09 (max=846.09 ratio=15.85)
mgr pp ran nit=846.09485 sdrNit=53.389984 brightness=2047.0
```

`dumpsys display` 侧 `DisplayDeviceRepository` 已出现 `hdrSdrRatio 1.34…` 的退出动画序列，
证明比率链路在 C17 上仍然工作、只是被亮度曲线与 clamp 压缩。
兜底层在 C17 上已经能把面板顶到 2047（= 846.09 nit 硬件上限）并保住 SDR 白点。
