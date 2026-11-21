# AOSP 对齐记录（HDR / hdrSdrRatio）

> 当前实现：FkHDR v1.8.0（versionCode 34，libxposed API 102）。审查对象是 `hdr-module/`；`module/`（legacy API 82）只作历史参考。
> 设计原则：GENERIC 兜底和 watcher 始终安装；专项链路只是运行时健康开关；不把启动期静态探测当作永久事实。
> 验证状态：静态审查、Kotlin 编译与 clean release 构建通过；v1.7.6 在两台设备验收通过 —— Redmi 22041216C / Android 15（专项链系统自算）与一加平板 Pro OPD2404 / Android 16 / ColorOS（原生驱动 + GENERIC 兜底，关掉 HDR 后比值 2 秒内回落）；v1.8.0 在平板上新增视频 HDR 的全局 SDR 压暗并实测生效（§4.10），跨 ROM 缺口见 §8。

## 1. AOSP Android 15 契约

权威源码（android15-release）：

- [LocalDisplayAdapter.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/services/core/java/com/android/server/display/LocalDisplayAdapter.java)：`:961` 以 `hasSdrToHdrRatioSpline()` 为比值入口；`:982-994` 在有效 `displayNits/sdrNits` 时计算 `Math.max(1.f, displayNits / sdrNits)`，否则写 `Float.NaN`；变化时才在 `synchronized (getSyncRoot())` 里写 `mCurrentHdrSdrRatio` 并 `updateDeviceInfoLocked()`。
- [DisplayDeviceConfig.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/services/core/java/com/android/server/display/DisplayDeviceConfig.java)：`:752` 声明 `private Spline mSdrToHdrRatioSpline;`；`:1157` 是 `hasSdrToHdrRatioSpline()`；`:1167-1186` 的 `getHdrBrightnessFromSdr()` 用 `Math.min(mSdrToHdrRatioSpline.interpolate(nits), maxDesiredHdrSdrRatio)`，缺曲线或缺 nits-to-backlight 映射时返回 `BRIGHTNESS_INVALID`。
- [HighBrightnessModeController.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/services/core/java/com/android/server/display/HighBrightnessModeController.java)：`:187-232` 的 `getHdrBrightnessValue()` 消费 `mMaxDesiredHdrSdrRatio`；`:605-634` 的 `HdrListener.onHdrInfoChanged()` 根据 HDR 图层面积更新该值并触发亮度重算。
- [Display.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/core/java/android/view/Display.java)：`:1409-1412` 的 `isHdrSdrRatioAvailable()` 返回 `!Float.isNaN(mDisplayInfo.hdrSdrRatio)`。
- [BrightnessSynchronizer.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/core/java/com/android/internal/display/BrightnessSynchronizer.java)：`floatEquals()` 的容差是 `0.0001f`，且 `floatEquals(NaN, NaN)` 为真。

两条链是独立的：

1. **比值上报** `setDisplayBrightness → handleHdrSdrNitsChanged → mInfo.hdrSdrRatio`。
2. **HBM 提亮** `HdrListener → mMaxDesiredHdrSdrRatio → getHdrBrightnessFromSdr → brightness`。

`Float.NaN` 表示「比值不可用」。把它改成 `1.0` 会让不支持的设备误报可用；这不是安全网，而是 AOSP 语义偏移。

## 2. 厂商样本与旧模块

本机厂商反编译件不是纯净 AOSP 形状：

- `DisplayDeviceConfig` 直接字段里没有 `mSdrToHdrRatioSpline`；曲线挂在 `mHbmData.sdrToHdrRatioSpline` 或 `mHdrBrightnessData.sdrToHdrRatioSpline`。
- `setDisplayBrightness` 还检查 OEM Ext 的 `isSupportSdrToHdrRatio()`；`handleHdrSdrNitsChanged` 还检查 `mUseSurfaceControlBrightness || mForceSurfaceControl` 等条件。
- 旧模块改 `display_id_*.xml` 的 `sdrHdrRatioMap`，并删掉 `services.jar` 里的门禁代码；它不写 `mForceSurfaceControl`。

历史记录里 v1.6.1 的「固定 `[2,650]`」「`mSdrToHdrRatioSpline` 只有一种形状」「`SPECIFIC-ONLY` 永久关闭补偿钩子」都只适用于当时的单个样本，不能推广到所有设备。

## 3. v1.6.1 审查发现的问题

7 个切片 reviewer 与 AOSP 基线复核均返回 ISSUES。收敛后的根因：

- `specificOnlyChain` 是启动期静态探针；为 true 时跳过 GENERIC 钩子与 watcher。bypass 返回 0、spline 安装失败、运行时字段失配后没有兜底，也不能自愈。
- spline 首次安装失败后，唯一重试路径被 `cfg != null && splineInstalled` 挡住，永久停在 deferred。
- 比值接管把无效输入写成 `1.0`、用 `ratioCap()` 截断原始比值、用 `0.01f` 代替 `floatEquals`，偏离 AOSP。
- 硬编码 `$1` / `this$1`、拿不到 SyncRoot 仍无锁写、写失败仍设 `param.result = null`，会让反射失败的 ROM 既不算也不让系统算。
- GENERIC 与 bypass 同时安装时存在 `apply.invoke(...)` 与 `forceHdrSdrRatio(...)` 双写；`pendingRatio` 读写有竞态。
- 门禁守护仍可写 `mSupportUltraHDR=true` 和 `setForceSurfaceControl(true)`，与黑屏教训冲突。
- `enabled()` 开机前 5 秒硬编码为 true；部分 hook、watcher 循环和延迟 Runnable 没有复查开关。
- `SettingsProviderHook` 的白名单缺 `hdr_ratio_max`，`enforceRestricted...` 不核调用包；UI 丢弃写入结果，SeekBar 每 tick 同步 IO。
- docs/README 把旧 GENERIC 方案、旧日志、无来源实测数值和绝对化「不写任何系统标志位」当成当前事实。

## 4. 当前实现（HDR 语义自 v1.6.2 起，v1.7.0 只换模块框架）

### 4.1 永远保留兜底

`init()` 在 `aospMode` 下无条件安装 `hookAospRatioBypass`、`hookAospLocalDisplay`、`hookHighBrightnessModeController`、`hookRatioProbeOnly`；通用段无条件安装 `hookDisplayInfoForgery`、`hookDisplayPowerController`、`hookMediaSessions`、`hookWindowManager`、`startWatcher()`。

`specOn()` 只由运行时状态决定：`aospMode && specificMode && !specFallback`。`specificMode` 需要 `splineInstalled`、`ratioBypassInstalled`、`ratioBypassVerified` 三者同时成立。watcher 每 3 秒自检；曲线被换回时重装，专项链 2 秒仍无有效比值时退回 GENERIC，HDR 退出时只清理残留的有限旧比值。

### 4.2 AOSP 比值语义

- 有效输入：`max(1f, nits / sdrNits)`，不额外 `ratioCap()` clamp；`ratioCap()` 只用于亮度目标与曲线端点。
- 亮度兜底：`aospBoostTarget(sdr)` 让被动改写与主动推送共用同一曲线目标；`Math.min(t, peakBrightness())` 后仍要求 `capped > sdr`，曲线不可用或量纲不一致时保持 `sdr`。不再有 `t > 1.0f -> 1.0f`，避免把 ColorOS 0..2047 的绝对量纲压到接近全黑。
- 无效输入：内部继续保持 `Float.NaN`（AOSP 用 NaN 表示「比值不可用」），但 `hookRatioAvailability()` 在 `LocalDisplayDevice.updateDeviceInfoLocked()` 之前把 NaN 归一成 `1.0`。理由：`LogicalDisplay.getDisplayInfoLocked()` 把 device 的 `hdrSdrRatio` 拷进 `mBaseDisplayInfo`，应用侧 `Display.isHdrSdrRatioAvailable()` 就是 `!Float.isNaN(...)`，`registerHdrSdrRatioChangedListener()` 在 NaN 时直接 return；不归一，应用永远显示「不支持」，专项链写进去的真实比值没人消费（v1.7.0 的回归）。1.0 是 AOSP 自己的下界（`Math.max(1.f, displayNits/sdrNits)`），不是随手伪造。
- 比较用 `ratioEquals()`：容差 `0.0001f`，`NaN` 与 `NaN` 相等，对齐 `BrightnessSynchronizer.floatEquals()`。
- 只在 `findFieldUp(dev.javaClass, "mCurrentHdrSdrRatio")` 存在且 `syncRootOf(dev)` 拿到 adapter 的 `getSyncRoot()` / `mSyncRoot` 时，在 `synchronized(root)` 内写字段并 `updateDeviceInfoLocked()`。
- 拿不到 SyncRoot：bypass 放行原方法，GENERIC 直接写入拒绝执行；绝不无锁 `updateDeviceInfoLocked()`。
- 写失败：不设 `param.result`，让原方法继续；`ratioBypassVerified` 只在有限比值确实写成功后置位。
- `pushRatioDirect` 与 bypass 互斥：专项链健康时抑制 GENERIC 补偿；不再 `apply.invoke(inner, nits, sdrNits)` 与 `forceHdrSdrRatio(...)` 双写。
- `pendingRatio` 改为 `AtomicReference<PendingRatio?>`；`getAndSet(null)` 原子取走，trailRunnable 复查 `enabled()` 和 HDR/gate 窗口。

### 4.3 动态反射与两种 spline 形状

- `ratioHandlerClasses()` / `brightnessCandidates()` 扫描 `declaredClasses`、`$1..$16` 与外层 `LocalDisplayDevice`，不再硬编码 `$1`。
- `outerOf()` / `localDeviceOf()` / `adapterOf()` 按类型找合成外部引用，不依赖 `this$1` 名字。
- nits 方法按精确 `(float)->float` 签名解析，兼容 `brightnessToBacklight/backlightToNits` 与 `getBacklightFromBrightness/getNitsFromBacklight` 两组命名。
- `resolveSplineTarget()` 支持 AOSP15 的 `DisplayDeviceConfig.mSdrToHdrRatioSpline`，也支持 OEM 的 `mHbmData.sdrToHdrRatioSpline` 与 `mHdrBrightnessData.sdrToHdrRatioSpline`。
- x 轴来自运行时 `panelMinNits/panelMaxNits`；`buildSpline()` 先试 `createMonotoneCubicSpline`，再回退 `createLinearSpline`。字段或曲线不可用就保持 deferred，GENERIC 继续工作。

### 4.4 不再碰的危险通路

- AOSP 分支删除 `hookExt()` 对 `isSupportSdrToHdrRatio()` 的强制置 true，删除 `hookAospRatioGate()`。
- 删除门禁守护、`reassertVendorGates()`、`KEY_GATE_WATCHDOG` 与 UI 开关；绝不写 `mSupportUltraHDR` / `mForceSurfaceControl`。
- ColorOS 分支的 `hookRatioSupport()` 是 OPPO Ext 私有钩子，不是 AOSP 比率闸门；`applyHbm()` 仍按原设计操作 HBM 控制器字段。文档不再笼统声称「不写任何系统标志位」。

### 4.5 开关与设置写入

- `enabled()` 初次读取不再硬编码前 5 秒为 true；所有会写设备状态、触发提亮或发布比值的回调、watcher 循环、延迟 Runnable 和 `requestPowerUpdate()` 都复查开关。纯观测/实例捕获回调只维护缓存；watcher 在关闭期间清空 `hdrWindows` / `playingPkgs`，避免重新开启后沿用过期的 HDR 判定。
- `SettingsProviderHook` 用 `AtomicBoolean` 保证安装幂等，只放行本模块包名与 `hdr_ratio_fix` / `hdr_ratio_max` 两个白名单键；`mutateSystemSetting` 的 before/after 用 `MethodHookParam.setObjectExtra/getObjectExtra` 配对，`checkAndNoteWriteSettingsOperation` 再核 `callingPackage`，`enforceRestricted...` 同时要求模块写标记与白名单键。
- ColorOS 原生驱动在开关关闭时只记录实例、不写 `mEnhanceHDRBrightnessSupport` / `init()`；开关打开后由 `hardenDriverIfPending()` 在驱动回调中补跑。
- UI 删除门禁守护开关，SeekBar 停止拖动后才持久化，所有写入在后台线程执行并回读验证，失败提示检查作用域或 root。

### 4.6 模块框架：libxposed API 102

v1.7.0 把入口从 legacy 的 `de.robv.android.xposed:api:82` 换成 `io.github.libxposed:api:102.0.0`。targetApiVersion=102 的模块不允许再引用任何 `de.robv.*` 类，否则框架直接拒绝加载。

- 入口：`io.github.fkhdr.FkModule extends XposedModule`，注册在 `META-INF/xposed/java_init.list`；元数据 `META-INF/xposed/module.prop`（`minApiVersion=102`、`targetApiVersion=102`、`staticScope=true`、`exceptionMode=protective`）；作用域 `META-INF/xposed/scope.list`。旧的 `assets/xposed_init`、`xposedmodule/xposedminversion/xposedscope` 元数据与 `HookInit.kt` 已删除。
- 静态作用域：`scope.list` 第一行必须是 `system`（system_server 的伪包名）。写 `android` 时新版 LSPosed 会自动归一，旧版（一加平板 Pro 的 2.2.1/7912）原样落库成 `scope(module, android, 0)`，模块因此不注入 system_server，且没有任何报错，见 §7 坑 6。静态作用域生效后无需手动勾选作用域。
- 钩子语义：`xp/XpHook.kt` 把 `XC_MethodHook` 的 before/after 适配为拦截器链——before 抛异常按 legacy 语义吞掉并继续 `chain.proceed(args)`；before 里给 `result` 赋值即短路原方法；after 在 `proceed` 之后执行并可改返回值；构造器 hook 在 `proceed` 之后重新读 `getThisObject()`。
- 稳定性：hook id 为 `"fkhdr:" + 类名 + "#" + 方法名 + 参数表 + "@" + hook 站点类名`，同时满足两条要求——同一源码点重装是原子替换（watcher 重试不叠钩子），不同源码点挂同一个 `Executable` 必须共存。少了末尾站点类名时，`hookAospRatioBypass` 会被同方法的只读 `hookRatioProbeOnly` 原子替换：`ratioBypassVerified` 恒 false、`updateSpecificMode()` 恒 false，`chain` 永远停在 GENERIC（v1.7.0 真机日志只有 `PROBE handleHdrSdrNitsChanged(...)` 与 GENERIC 的 `ratio direct/ratio forced`）。
- 挂载时机：`SettingsProvider` 只在 `onPackageLoaded` 里挂。Android 13+ 的 SettingsProvider 跑在 system_server 内，但类在它自己的 APK classloader 里，`onSystemServerStarting` 的 system_server loader 必然找不到（v1.7.0 首次真机验证就是这个报错），失败后再用后台重试也只是浪费 20 秒。
- 观察到的真机事实（Redmi 22041216C / Android 15 / HyperOS / LSPosed 2.2.1）：`lspctl module show` 报 `minApiVersion=102 targetApiVersion=102 legacy=false staticScope=true`，`declaredScope=["android","com.android.providers.settings"]`；system_server 日志出现 `FkHDR:FkModule: loaded api=102 framework=LSPosed/2.2.1`；HDR 播放时 `ratio direct nits=187.02 sdrNits=39.2 -> ratio=4.77`、`ratio forced 4.77`、`aosp push applied brightness=0.19 sdr=0.04`、`hbm hdrLayerPresent=true -> mode=2`。

### 4.7 比值上限可以在 App 里实时改

上限存在 `Settings.System hdr_ratio_max`（默认 5.0，范围 1.0–20.0），界面滑块写入，system_server 侧不需要重启：

- `ratioCap()` 的缓存 TTL 是 1 秒，值一变就打印 `read app ratio cap X -> Y`；
- watcher 每次唤醒调 `syncRatioCap()`，变化时打印 `app ratio cap X -> Y, applying live` 并按新端点重装 spline（`y=[cap, 1.0]`），所以系统自算的比值立刻跟着变；
- 模块代发的比值都过 `cappedRatio()`：上限调低后当前 HDR 会话会被直接收口，不必等下一次内容变化。

实测（Android 15 手机，HDR 播放中改上限）：`dumpsys display` 的 `hdrSdrRatio` 在 cap 5.0 时是 4.965725，改成 2.0 后是 2.0，改回 5.0 后是 4.2025065；三次都是 1 秒内完成，没有重启 system_server，也没有重启被测应用。

Android 16 / ColorOS 平板没有 spline 字段，比值由厂商驱动发布，模块只对自家亮度目标与曲线负责 —— 上限在那台机器上不会截断厂商上报的比值（GAP，见 §8）。

### 4.8 提亮倍数与压暗倍数必须一致（v1.7.5）

HDR 的「压暗」是 SurfaceFlinger 按 `hdrSdrRatio` 压 SDR 层，模块的「提亮」是抬 nit/亮度，两者是独立的数。Android 16 / ColorOS 平板（无 `mSdrToHdrRatioSpline`）走厂商原生驱动，原生驱动把 nit 直接顶到面板上限：

- 原生：`99.39909 -> 846.09485`，即 8.51 倍（`maxPanelNit / sdrNit`，等于「顶满」）；
- SurfaceFlinger：那台机器 dumpsys 里是 `current hdr/sdr ratio=5.000000`，只压 5 倍。

8.51 倍提亮 + 5.0 倍压暗，SDR 部分就按 1.7 倍偏亮，观感是「提亮有了、压暗没生效」。v1.7.5 在 `convertNitToBrightness()` 之前把 nit 收口到 `sdrNit × ratioCap()`：

```
hdr nit clamped 846.09 -> 497.0 (ratio 8.51 -> cap 5.0)
mgr pp ran nit=496.99548 sdrNit=99.39909 brightness=1660.9534
```

收口后亮度从 2047（面板最大）降到 1660.95，`dumpsys display` 的 `hdrSdrRatio` 也变成 5.000007：模块提亮倍数 == SF 压暗倍数 == 界面读到的比值。上限仍由 §4.7 的实时机制控制，滑到 3.0 下一次后处理就按 3 倍收口。

### 4.9 HDR 退出后比值必须主动回落（v1.7.6）

ColorOS 设备上出现过「HDR 关了，`hdrSdrRatio` 仍停在 5.0，必须等自动亮度改一次亮度才降」。这不是 watcher 的迟滞太长，而是两个独立问题：

1. **窗口 `colorMode` 是残留信号**：HDR 关掉后窗口属性仍带 HDR colorMode，watcher 每 5 秒左右在 `HDR -> true(focus=true)` 与 `HDR -> false(focus-only expired, no HDR layer)` 之间翻，每翻回 true 就重新提亮一次。
2. **厂商驱动不看 SF 图层**：ColorOS 的 `OplusFeatureHDREnhanceBrightness` 后处理只要被叫醒就把 nit 顶到面板上限，与 SF 里是否真有 HDR 图层无关（`hdr nit clamped 846.09 -> 497.0`）。模块原本只在 `hdrOn` 时管亮度，退出后既不按回 SDR，也不主动写比值；而厂商 `handleHdrSdrNitsChanged(float,float)` 只在亮度变化时重算，于是比值一直挂着。

v1.7.6 的三个改动：

- HDR 判定改为「SF 图层证据优先」：`sfLayerHasHdrMetadata()` 的 `sfMeta` 为 `true` 就是 HDR；为 `false` 且非 AOSP 栈就不是 HDR；只有 `null`（读不到 SF 状态）才回落到窗口 colorMode、媒体会话与 `hdrWindows`。SF 证据来自 `dataspace=0x188a`（BT2020/PQ）与 `current hdr/sdr ratio > 1.5`，都是 SF dump 里的真实字段，不受窗口属性残留影响。
- 新增 `suppressStaleBoost()`：`hdrMetaCache == false`（SF 明确没有 HDR 图层）且驱动把 `getNit()/getSdrNit()` 抬到 1.02 以上时，`setNit(getSdrNit())` 把亮度按回 SDR，日志 `hdr off but vendor boosting X -> sdr Y (suppressed)`。它挂在 `convertNitToBrightness()` before 钩子里 `!hdrOn` 的分支，AOSP 栈不走厂商驱动时自然不触发。
- 退出回落改成所有平台都做：`!hdrOn && gateUntil != 0L` 时，过了 `HDR_EXIT_HOLD_MS`（1200ms）就清 gate，否则每 `FORCE_MIN_INTERVAL_MS`（500ms）读一次 `currentHdrSdrRatio()`，`|ratio - 1| > 0.05` 就 `forceHdrSdrRatio(aospDev, 1.0f)`，日志 `ratio restored 1.0 on HDR exit (was X)`。原来这段只在 `aospMode` 下生效，ColorOS 栈因此永远不主动写回。

平板实测（一加平板 Pro / Android 16 / ColorOS，验收 App 开 HDR 6 秒后关闭）：

```text
on+6s  hdrSdrRatio 5.000007  brightness 1660
t+1    4.6617727             1540
t+2    1.0                   718
```

1 秒内快速开/关：`t+1 1.28 → t+2 1.82 → t+3 2.52 → t+4 起恒 1.0`。真 HDR 视频（探针播放器）：`play+2 1.68 → play+5 5.000007`，`stop+4 3.59 → stop+5 1.0`。第二台设备（手机 / AOSP 栈）行为不变：专项链照常，退出仍按原路径回落。

### 4.10 全局 SDR 压暗（v1.8.0，仅 ColorOS 路径）

问题：ColorOS 平板上视频 HDR 把面板亮度按比值抬到上限（`mgr pp ran nit=496.99 sdrNit=99.4 brightness=1660.95`，比值 5.0），但合成器没有执行 AOSP 的 per-layer dimming，SDR 内容跟着一起被抬亮 —— 观感只有提亮、没有压暗。

AOSP 契约在 `frameworks/native` android15-release：`services/surfaceflinger/CompositionEngine/src/OutputLayer.cpp:351-361` 对非 HDR 图层算 `dimmingRatio = clamp(sdrWhitePointNits / displayBrightnessNits, 0.f, 1.f)`，HDR 图层固定 1.0（源码注释写明 HDR 内容既不提亮也不压暗）；`:547-557` 通过 `hwcLayer->setBrightness(dimmingRatio)` 交给 HWC；`services/surfaceflinger/CompositionEngine/src/Display.cpp:358` 的 `clientTargetDimmingStage` 由 HWC 回填，AOSP 默认 `DimmingStage::NONE`。本机 HWC 返回 NONE 且 `supportedHdrTypes=INVALID`，所以这一层请求被丢掉。

实现（`SdrDimmer.kt`）：在 `hdrOn && !aospMode` 时，遍历 `WindowManagerService.mWindowMap` 的全部窗口，对每个窗口的 `SurfaceControl` 下发 `SurfaceControl.Transaction#setColorTransform`，一次 `apply()` 提交；窗口 `colorMode=HDR` 时跳过；退出 HDR 时下发 identity 还原。系数 `f = max(0.05, 1/ratio)`，与 AOSP 的 dimmingRatio 同口径（线性亮度比）。ratio 优先取 `mCurrentHdrSdrRatio`，摸不到 `LocalDisplayDevice` 时退回厂商后处理链里观测到的 `nit/sdrNit`。

选 `setColorTransform` 而不是 `setDimmingEnabled` / `setExtendedRangeBrightness` 的原因：`setDimmingEnabled` 只能让图层退出 dimming，不能施加压暗；`setExtendedRangeBrightness` 的语义是 EDR 比值、不是亮度缩放。

实测（一加平板 Pro，HDR10 测试片，上限 5.0）：模块日志 `sdr dim factor=0.200 on 17 window(s), skipped 0 hdr window(s), failed 0 of 17`；`dumpsys SurfaceFlinger` 里 `colorTransform=[[0.200,0.000,0.000,0.000][0.000,0.200,0.000,0.000][0.000,0.000,0.200,0.000][0.000,0.000,0.000,1.000]`；播放器 SDR 按钮区均值 `(95.3, 94.3, 96.2)` → `(41.7, 41.7, 42.7)` → 停止后复原；同一帧 HDR 视频红条 `(255.0, 0.0, 30.2)` 不变；`hdrSdrRatio` 播放中 5.000007、停止后 1.0。

GAP：只在 ColorOS 路径启用；AOSP 栈由系统自己做 per-layer dimming，不能重复压暗。压暗的前提是面板亮度已按比值抬高（本机由厂商驱动保证）。只覆盖登记在 `mWindowMap` 的窗口，SurfaceView 子图层（视频本体）按设计不压。

---
## 5. 跨设备行为

| 设备形状 | 当前行为 |
|---|---|
| 纯净 AOSP15，`DisplayDeviceConfig.mSdrToHdrRatioSpline` | 运行时验证通过后启用专项链；GENERIC 钩子与 watcher 仍在，失配即降级。 |
| Android 16 / ColorOS 平板（一加平板 Pro，无 `mSdrToHdrRatioSpline`） | `sdrToHdrSpline=false`，专项链不装；走 OEM 原生驱动 + GENERIC 比值发布，实测 `ratio=8.51`、`brightness=2047`。 |
| OEM，`mHbmData/mHdrBrightnessData.sdrToHdrRatioSpline` | 按 holder 字段安装曲线；其余同上。 |
| 没有 spline 字段或面板 nits 方法 | 曲线保持 deferred；GENERIC 亮度与比值兜底继续安装。 |
| 匿名类序号、合成字段名、方法位置不同 | 动态扫描 + 按类型解析；找不到就退回 GENERIC，不吞掉原方法。 |
| 拿不到 `DisplayAdapter.getSyncRoot()` / `mSyncRoot` | bypass 放行原方法，直接写入拒绝；不无锁发布 display-changed。 |
| 厂商定制 `SettingsProvider` 或多显示器 | 白名单写失败会回读提示；多屏实例尚未完整建模，见 §8 GAP。 |
| HWC 不执行 per-layer dimming（本机 `supportedHdrTypes=INVALID`、`DimmingStage::NONE`） | ColorOS 路径自己给所有 `mWindowMap` 窗口下发颜色矩阵压暗（§4.10）；AOSP 栈交给系统自己做。 |

## 6. 当前日志对照

```text
FkHDR:HdrFix: init done [platform=... bypass=... aosp[dev=... bright=...] hbm=... probe=... forge=... dpc=... media=... wms=...] factor=...
FkHDR:HdrFix: ratio bypass installed on N method(s)
FkHDR:HdrFix: ratio bypass target missing; GENERIC fallback stays active
FkHDR:HdrFix: specific chain sdrHdrRatioSpline replaced (shape ... x=[...] y=[...])
FkHDR:HdrFix: chain -> SPECIFIC(系统自算) / GENERIC(模块代劳) [spline=... bypass=... verified=... fallback=...]
FkHDR:HdrFix: ratio direct nits=... sdrNits=... -> ratio=... current=...
FkHDR:HdrFix: sync root unavailable, refusing unlocked ratio write
FkHDR:HdrFix: specific chain ineffective after 2s -> fallback to GENERIC for this session
FkHDR:HdrFix: HDR -> true/false
```

`PROBE ... [use= force= ultra=]` 是只读诊断；它挂在 bypass 之后，读到的是当前值，不是独立的「真实入参与门禁」证据。旧日志 `specific-chain probe -> true`、`SPECIFIC-ONLY(...)`、`Spline x=[2.0,650.0]` 已删除。

## 7. 历史坑与历史实测

### 坑 1：写 `mForceSurfaceControl=true` 会黑屏

该标志把亮度输出从 sysfs 背光通路切到 SurfaceControl 通路。本机 `getDisplayBrightnessSupport()` 返回 false；强切后真实背光收不到指令。v1.6.2 删除所有写入路径，只在诊断里读取。

### 坑 2：`getSyncRoot()` 在 `DisplayAdapter` 上

对 `LocalDisplayDevice` 调 `getSyncRoot()` 会失败。旧实现无锁写 `mCurrentHdrSdrRatio` 后与显示线程竞态，历史栈：

```text
ArrayIndexOutOfBoundsException: length=1; index=1
  at LocalDisplayAdapter$LocalDisplayDevice.getDisplayDeviceInfoLocked(LocalDisplayAdapter.java:725)
  at DisplayDeviceRepository.handleDisplayDeviceChanged
```

当前通过 `adapter.getSyncRoot()` / `mSyncRoot` 取锁；取不到就放弃接管或拒绝写入。

### 坑 4：legacy 入口在 API 102 模块里直接不可用

`targetApiVersion=102` 时框架不再提供 `de.robv.android.xposed` 兼容层：`assets/xposed_init` + `xposedminversion=82` 的老入口会被识别成 `legacy=true`（`lspctl module show` 可见），升级到新规范后必须同时改入口类、`META-INF/xposed/*` 与构建依赖，只改其中一处会出现「模块已启用但 system_server 里没有任何日志」。

### 坑 3：LSPosed 缓存的 APK 路径会失效

每次 `adb install -r` 后 `/data/app/~~xxx==` 目录可能变化，LSPosed 数据库里的旧 `apkPath` 会让模块被静默跳过：system_server 里一行模块日志都没有，LSPosed 侧只有

```text
W LSPosedService: Failure to parse package archive /data/app/~~DU2ZdbIOduJF-kD-K3PfMQ==/io.github.benbaobaoshigemi.fkhdr-VRNRu8_EUREGs_6YsGabsg==/base.apk
com.android.internal.pm.parsing.PackageParserException: Failed to parse ...
Caused by: java.io.IOException: Failed to load asset path ...
```

此时 `pm path` 与设备上的 `base.apk` sha256 都已经是新的，只有 LSPosed 的记录还是旧的。安装后核对并刷新：

```bash
pm path io.github.benbaobaoshigemi.fkhdr
lspctl --json module show io.github.benbaobaoshigemi.fkhdr
lspctl --json module disable io.github.benbaobaoshigemi.fkhdr --user 0
lspctl --json module enable  io.github.benbaobaoshigemi.fkhdr --user 0
su -c 'killall system_server'
```

只 `killall system_server` 不会刷新记录，必须 disable/enable 一次让 LSPosed 重写 `apkPath`。

### 坑 5：同 id 钩子互相替换，专项链被只读诊断顶掉

libxposed 对同一 `Executable` 用同一 id 重复装钩子做的是原子替换，不是叠加。v1.7.0 的 id 只含「类名#方法+参数」，而 `hookAospRatioBypass` 与 `hookRatioProbeOnly` 都在 `handleHdrSdrNitsChanged(float,float)` 上——后安装的只读 PROBE 把真正的 bypass 顶掉，于是 `ratioBypassVerified` 永远 false，整机看起来「模块加载成功、也有比值日志」，但走的是 GENERIC 兜底而不是专项链。修法是把 hook 站点类名拼进 id；代价是同源码点重装仍幂等，不同源码点自然共存。

### 坑 6：`scope.list` 里 system_server 要写 `system`，不是 `android`

`android` 是 framework 的包名，system_server 的伪包名是 `system`。新版 LSPosed 会把 `scope.list` 里的 `android` 归一到 `system`，所以手机上一直没暴露；旧版守护进程（一加平板 Pro 的 2.2.1/7912）把 `android` 原样写进数据库，注入点落在一个不存在的进程上 —— 表现是「模块显示已启用、`lspctl status` 说 system server connected，但 system_server 里一行模块日志都没有」。

排查方法：拉 `/data/adb/lspd/config/modules_config.db`，看 `scope` 表里该模块的 system_server 行必须是 `system`。改成 `system` 后静态作用域直接生效，不需要手动勾选；改完要让配置重新落库（重新启用模块 + 重启设备）。

### 坑 7：ColorOS 的窗口 colorMode 是残留信号，不能单独当 HDR 判据

HDR 内容关掉之后，窗口属性里的 colorMode 可能还带着 HDR（实测残留几十秒），按它判定就会反复「重新开启 HDR → 再退出」。这类设备上要靠 SF 的图层证据（`dataspace=0x188a`、`current hdr/sdr ratio`）判 HDR，窗口属性只在读不到 SF 状态时兜底。另一个后果：厂商驱动不看 SF 图层，照样抬 nit，所以退出时既要压回 nit，也要主动把比值写回 1.0（见 §4.9）。

### 坑 8：`SurfaceControl.Transaction#setColorTransform` 的颜色矩阵按列主序 3×3 解释

本机 native 侧只取前 9 个 float，且按列主序排：下标 0,1,2 = 第 0 列（`m00,m10,m20`），3,4,5 = 第 1 列，6,7,8 = 第 2 列，9–15 被忽略。按行主序填 `(f,0,0, 0,f,0, 0,0,f, …)` 时绿系数落到 `m21`，绿通道被压成 0，屏幕整体发紫（实测 `colorTransform=[[0.200,0.000,0.000,0.000][0.000,0.000,0.000,0.000][0.000,0.200,0.000,0.000][0.000,0.000,0.000,1.000]]`，截图按钮从 `(95.3,94.3,96.2)` 变成 `(41.7,0.0,41.7)`）。对角必须写在下标 0/4/8。

### v1.6.1 历史实测（不是 v1.6.2 验收）

仓库曾记录一次 Redmi xaga / HyperOS 3 / Android 15 的 HDR10 读数：播放时 `hdrSdrRatio=3.5368757`、`mScreenBrightness=0.013215097`、`hbmMode=hdr`；停止后 `1.0`、`0.0034099757`、`off`，验收 App 显示 ✔️。该记录没有附带可复查的 logcat / dumpsys 文件，且来自旧实现；不能据此声称 v1.6.2 已实机通过。

## 8. 验证与 GAP

已完成的静态验证：

- 7 个非重叠切片 reviewer + 1 个 AOSP android15 基线复核，原始结论均为 ISSUES，修复逐条对应。
- 延迟送达的 final-review 报 R1–R5（亮度量纲、SettingsProvider 嵌套标记、开关关闭写驱动、诊断 readback、注释矛盾），逐项复核后已修复；verifier 的 6 项检查为 PASS、1 条 nit 与 final-review R2 同源。
- 终审修正后 `:app:compileReleaseKotlin`、`:app:clean :app:assembleRelease --offline`、`git diff --check` 与 `aapt2 dump badging` 全部通过。
- 版本号 `versionCode = 34` / `versionName = "1.8.0"`，`android.suppressUnsupportedCompileSdk=37` 与 `compileSdk = 37` 一致。

已完成的实机验证（Redmi 22041216C / Android 15 / HyperOS / LSPosed 2.2.1，框架 apiVersion=102）：

- `lspctl --json module show io.github.benbaobaoshigemi.fkhdr`：`minApiVersion=102 targetApiVersion=102 legacy=false staticScope=true`，`declaredScope=["com.android.providers.settings","system"]`，user 0 已启用。
- system_server 日志：`FkHDR:FkModule: loaded api=102 framework=LSPosed/2.2.1 systemServer=true`、`init done [platform=aosp bypass=1 aosp[dev=1 bright=1] hbm=true probe=1 ratioAvail=1 forge=1 dpc=true media=3 wms=2]`、`ratio availability candidates=2 withUpdateDeviceInfo=1 hooked=1`、`ratio availability hook installed on 1 method(s)`、`SettingsProviderHook: initialized`。
- 第二台设备（一加平板 Pro / Android 16 / ColorOS，`platform=coloros`）：`init done [platform=coloros features=2 ratioMax=true featCfg=4 fmgr=1 ratioFlag=1 native=true boost=1 forge=1 dpc=true media=3 wms=1]`、`panel config ready [sdrToHdrSpline=false]`、`chain = GENERIC(模块代劳, 兜底已安装)`、`driver ready [support=true maxNit=846.09]`；播放 HDR 时 `HDR -> true(focus=true)`、`pp nit=99.39909/99.39909 cap=none(physical)`、`fallback idle (native engaged, nit=846.09 sdrNit=99.4 ratio=8.51)`、`mgr pp ran nit=846.09485 sdrNit=99.39909 brightness=2047.0`；验收 App 显示 ✔ / 8.51210 / 逻辑峰值 1728 cd/m²。
- v1.7.3 复验（第一台设备）：`specific chain sdrHdrRatioSpline replaced (DisplayDeviceConfig.mSdrToHdrRatioSpline Spline x=[2.0,650.0] y=[5.0,1.0])`、`HDR -> true(focus=true) [driver=null]`、`setDisplayBrightness boost skipped (healthy specific chain)`、`ratio bypass nits=55.6 sdrNits=22.1 -> 2.52`，`dumpsys display` 的 `hdrSdrRatio` 为 1.4128228 —— 专项链在 v1.7.3 仍生效。
- 比值上限实时生效（第一台设备，HDR 播放中改 `Settings.System hdr_ratio_max`）：cap 5.0 → 8.0 → 2.0 → 5.0 时 `hdrSdrRatio` 依次为 4.965725 → 2.0 → 4.2025065，每次 1 秒内完成，未重启 system_server 与被测应用；日志 `read app ratio cap X -> Y` + `app ratio cap X -> Y, applying live` + spline 端点重装。
- 第一台设备专项链运行（v1.7.1 证据）：`ratio bypass nits=16.16 sdrNits=3.7 -> 4.37`、`ratio bypass nits=18.43 sdrNits=3.7 -> 4.98`，同时出现 `setDisplayBrightness boost skipped (healthy specific chain)` → `specOn()` 成立，GENERIC 亮度补偿让位。`chain -> SPECIFIC` 只在状态变化时打印，init 里的 `chain = GENERIC` 是安装时点快照，不能当运行结论。
- 应用侧（v1.7.1 修复目标）：验收 App 从「HDR SDR调光支持 ❌ / 比率 1.00000」变为「✔ / 4.37384」；`dumpsys display` 的 `DisplayDeviceInfo` / `mBaseDisplayInfo` / `mOverrideDisplayInfo` 三处 `hdrSdrRatio` 都是有限值，`not_available` 不再出现。
- watcher 降级仍按设计触发：`specific chain ineffective after 2s (ratio=1.0) -> fallback to GENERIC for this session`（该会话比值恒为 1.0，属预期保护）。
- APK 内不再有 `de/robv` 引用（`grep -ac de/robv classes.dex` = 0），`META-INF/xposed/{module.prop,java_init.list,scope.list}` 均已打包。
- v1.8.0 全局 SDR 压暗（一加平板 Pro / Android 16 / ColorOS，探针播放器播 HDR10）：日志 `sdr dim factor=0.200 on 17 window(s), skipped 0 hdr window(s), failed 0 of 17`；`dumpsys SurfaceFlinger` 的 `colorTransform` 三通道均为 0.200；SDR 按钮区像素 `(95.3, 94.3, 96.2)` → `(41.7, 41.7, 42.7)` → 停止后复原；同一帧 HDR 视频红条 `(255.0, 0.0, 30.2)` 不变；`hdrSdrRatio` 播放中 5.000007、停止后 1.0，SF 里压暗矩阵计数归 0。

仍存在的 GAP：

- v1.7.6 退出回落实测（一加平板 Pro / Android 16 / ColorOS）：验收 App 开 HDR 6 秒后关闭，`hdrSdrRatio` 从 5.000007 降到 4.6617727（t+1）、1.0（t+2），`screen_brightness` 1660 → 1540 → 718；1 秒内快速开/关后 `t+4` 起恒为 1.0；真 HDR 视频停止后 `stop+4 3.59 → stop+5 1.0`。日志侧伴随 `hdr off but vendor boosting ... (suppressed)` 与 `ratio restored 1.0 on HDR exit (was ...)`。
- 已在两台设备验证：Android 15 / HyperOS 手机（AOSP 栈专项链）与 Android 16 / ColorOS 平板（无 spline，走原生驱动 + GENERIC）；其它 ROM、其它 LSPosed 版本与无 root 场景未测。
- Android 16 平板上 `sdrToHdrRatioSpline=false`，专项链在该机型未验证（只有 GENERIC 与原生驱动）。
- 未在多种 ROM 上验证拦截器链与 legacy before/after 的顺序差异、合成字段命名、厂商 `SettingsProvider` 行为和 SF dump 输出。
- 多显示器仍使用部分全局设备快照；`aospDev` 等字段没有按 displayId 完全拆分，需真机多屏回归。
- 仓库没有单元测试或 instrumentation 测试；反射与线程时序只能靠静态审查和构建证明。
- 全局 SDR 压暗只覆盖登记在 `mWindowMap` 的窗口；游戏等全屏 Surface / SurfaceView 子图层的观感、以及多显示器时的逐屏系数未验证。
- 未抓到 `chain -> SPECIFIC` 打印行；专项链生效是从 `ratio bypass ...` 与 `boost skipped (healthy specific chain)` 的副作用日志侧证，不是直接读状态。
- v1.7.0 曾把 `hdrSdrRatio` 保持成 NaN，导致任何依赖 `isHdrSdrRatioAvailable()` 的应用（不只验收 App）都不上报；v1.7.1 的归一是否对所有 ROM 都安全，仍需跨设备确认。

## 9. 相关文件

- [FkModule.kt](../hdr-module/app/src/main/java/io/github/fkhdr/FkModule.kt)
- [xp/XpHook.kt](../hdr-module/app/src/main/java/io/github/fkhdr/xp/XpHook.kt)
- [xp/Xp.kt](../hdr-module/app/src/main/java/io/github/fkhdr/xp/Xp.kt)
- [xp/XpHelpers.kt](../hdr-module/app/src/main/java/io/github/fkhdr/xp/XpHelpers.kt)
- [HdrBoostHook.kt](../hdr-module/app/src/main/java/io/github/fkhdr/hooks/HdrBoostHook.kt)
- [SdrDimmer.kt](../hdr-module/app/src/main/java/io/github/fkhdr/hooks/SdrDimmer.kt)
- [SettingsProviderHook.kt](../hdr-module/app/src/main/java/io/github/fkhdr/hooks/SettingsProviderHook.kt)
- [Config.kt](../hdr-module/app/src/main/java/io/github/fkhdr/Config.kt)
- [MainActivity.kt](../hdr-module/app/src/main/java/io/github/fkhdr/ui/MainActivity.kt)
- [app/build.gradle.kts](../hdr-module/app/build.gradle.kts)
- [hdr-module/README.md](../hdr-module/README.md)
