# FkHDR — 独立 HDR 提亮修复模块

> 当前版本：v1.8.0 / versionCode 34（libxposed API 102）。实现细节与 AOSP 基线见 [docs/aosp-alignment.md](../docs/aosp-alignment.md)。
> 状态：Redmi 22041216C / Android 15 / HyperOS 在 v1.7.6 验收通过（专项链系统自算）；一加平板 Pro OPD2404 / Android 16 / ColorOS 在 v1.7.6 验收通过（原生驱动 + GENERIC 兜底，关掉 HDR 后比值 2 秒内回落），v1.8.0 在同一台平板上新增「视频 HDR 时全局压暗 SDR」。静态作用域写在 APK 里，装好启用即可，无需手动勾选作用域。

| 项 | 值 |
|---|---|
| 包名 | `io.github.benbaobaoshigemi.fkhdr` |
| 入口 | `io.github.fkhdr.FkModule`（注册在 `META-INF/xposed/java_init.list`） |
| 作用域 | `META-INF/xposed/scope.list`：`system`（system_server 的伪包名，不能写 `android`）+ `com.android.providers.settings`（免 root 写开关/上限），`staticScope=true` |
| 依赖 | `io.github.libxposed:api:102.0.0`（compileOnly，运行时由框架提供）；不用 Compose、不用 androidx |

---

## 为什么独立

原 FkOScreen 模块把 HDR 提亮和多个 ColorOS 专属功能放在一起。FkHDR 只负责 HDR 链，并在 AOSP 分支始终保留 GENERIC 兜底，避免只依赖某一种 ROM 的私有字段。

---

## 支持与兜底

| 平台 | 主链路 | 兜底 |
|---|---|---|
| ColorOS / 一加 / OPPO / realme | OEM 原生提亮驱动、后处理与 feature 钩子 | OPPO 通路全部失配时启用 AOSP 兜底 |
| 类原生 / AOSP 显示栈 | 运行时验证成功后使用专项链（spline + 比值接管） | **始终安装** request/state 捕获、亮度注入、比值发布、媒体/WMS 检测与 watcher |
| 其他 | 通用 DPC / WMS / 媒体会话 / HDR 检测 | 找不到私有字段时不写系统状态，保持原路径 |

专项链只是运行时优化开关：`splineInstalled`、`ratioBypassInstalled`、`ratioBypassVerified` 全部成立才抑制 GENERIC 补偿；任一环节失败、曲线被系统换回、或 2 秒内仍没有有限有效比值，watcher 退回 GENERIC。

AOSP 分支不再强制 `isSupportSdrToHdrRatio()`，也不再写 `mSupportUltraHDR` / `mForceSurfaceControl`。比值不可用时系统内部保持 `Float.NaN`，但重建 `DisplayDeviceInfo` 之前会把 NaN 归一成 `1.0`：AOSP 的 `Display.isHdrSdrRatioAvailable()` 就是 `!Float.isNaN(hdrSdrRatio)`，不归一的话应用侧永远看到「不支持」、永远不注册监听器，专项链写进去的真实比值也没人消费（v1.7.0 就是这样把 v1.6.1 的 ✔ 弄丢的）。ColorOS 分支的 OPPO Ext feature 钩子与 HBM 控制器字段是另一套设计，不能和 AOSP 比率闸门混为一谈。

---

## v1.7.1 / v1.7.2 修了什么

v1.7.0 换模块框架后出现三个真实问题，v1.7.1 与 v1.7.2 分别修掉：

1. **应用侧「HDR SDR调光支持」变 ❌**：v1.6.2 审查时删掉了「比值未知就归一成 1.0」的钩子，系统回报 `hdrSdrRatio = NaN`，`isHdrSdrRatioAvailable()` 为 false，验收 App 显示 ❌ + 比率 1.00000。现在由 `hookRatioAvailability()` 在 `LocalDisplayDevice.updateDeviceInfoLocked()` 之前把 NaN 归一成 1.0（AOSP 自己的下界，表示 HDR 与 SDR 同亮度）；HDR 播放时仍写真实比值。
2. **专项链永不启用、只剩 GENERIC 兜底**：`xp/Xp.kt` 的 hook id 只由「类名 + 方法名 + 参数表」拼成，而 `hookAospRatioBypass` 与 `hookRatioProbeOnly` 挂的是同一个 `handleHdrSdrNitsChanged(float,float)`；libxposed 对同 id 是原子替换，后装的只读 probe 把 bypass 顶掉，`ratioBypassVerified` 永远 false → `updateSpecificMode()` 永远 false → `chain` 停在 GENERIC。现在 id 末尾追加 hook 站点类名（匿名类每处唯一），不同钩子共存、同一处重装仍幂等。
3. **第二台设备上模块完全不加载（静态作用域写错包名）**：`scope.list` 原来写 `android`。新版 LSPosed 会把 `android` 归一到 system_server 的伪包名 `system`，但旧版守护进程（一加平板 Pro 上的 2.2.1/7912）原样落库成 `android`，注入点因此落在一个并不存在的进程上，system_server 里一行模块日志都没有。改成 `system` 后静态作用域直接生效，装好启用即可，不必手动勾作用域。

另有一处运维坑：`adb install -r` 后 LSPosed 记录的 `apkPath` 可能失效，模块会被静默跳过，需要 disable/enable 让 LSPosed 重写路径（见「安装」）。

## v1.7.5：提亮倍数和压暗倍数对齐

平板上出现过「提亮正常，但 SDR 部分没有压暗、整体偏亮」。根因是两个倍数不一致：

- 厂商原生驱动把 nit 直接顶到面板上限：`99.39909 -> 846.09`，也就是 8.51 倍；
- SurfaceFlinger 只按自己的 hdr/sdr ratio 压暗 SDR 层，那台机器上是 5.0 倍。

提亮 8.51 倍、压暗 5.0 倍，SDR 就按 8.51/5.0 ≈ 1.7 倍偏亮。现在 `hookPostProcessBoost()` 在 `convertNitToBrightness()` 之前把 nit 收口到 `sdrNit × 上限`（上限就是 App 里的「比值封顶上限」），实测：

```
hdr nit clamped 846.09 -> 497.0 (ratio 8.51 -> cap 5.0)
mgr pp ran nit=496.99548 sdrNit=99.39909 brightness=1660.9534
```

亮度从面板最大 2047 落到 1660.95，`dumpsys display` 的 `hdrSdrRatio` 同步变成 5.000007 —— 提亮倍数、SF 压暗倍数、界面显示的比值三者一致。上限还是实时可改：滑到 3.0，下一次后处理就把 nit 收到 `99.4 × 3.0`。

## v1.7.6：HDR 关掉之后比值要马上回落

ColorOS 平板上出现过「HDR 已经关了，比率还挂在 5.0，要等自动亮度动一下才降」。两条原因叠在一起：

1. **窗口的 `colorMode` 是残留信号**。HDR 关掉后窗口仍带 HDR colorMode，watcher 每 5 秒左右在「有 HDR」和「没有 HDR」之间翻一次，每翻回去就重新提亮一次。
2. **厂商驱动不看 SF 有没有 HDR 图层**，照样把 nit 顶到面板上限（`846.09`）。模块只在 HDR 开启时管亮度，退出后既没把 nit 按回 SDR，也没主动重写比值；驱动自己的 `handleHdrSdrNitsChanged()` 只在亮度变化时重算，于是比值一直挂着。

改法：

- HDR 判定优先采信 SF 的图层证据（`dataspace=0x188a`、`current hdr/sdr ratio`）：`sfMeta == true` 就是 HDR，`sfMeta == false` 且不是 AOSP 栈就不是 HDR，两者都拿不到才回落到窗口 colorMode、媒体会话与 `hdrWindows`。
- 新增 `suppressStaleBoost()`：HDR 已退出但驱动仍在抬 nit 时，把 nit 直接限回 `sdrNit`，日志 `hdr off but vendor boosting X -> sdr Y (suppressed)`。
- HDR 退出时所有平台都主动把比值写回 1.0（原来只有 AOSP 栈做）；每 500ms 查一次，`|ratio - 1| > 0.05` 就写，日志 `ratio restored 1.0 on HDR exit (was X)`。

平板实测（一加平板 Pro / Android 16 / ColorOS），开启 6 秒再关闭：

```text
on+6s  hdrSdrRatio 5.000007  brightness 1660
t+1    4.6617727             1540
t+2    1.0                   718
```

1 秒内快速开/关：`t+1 1.28 → t+2 1.82 → t+3 2.52 → t+4 起恒 1.0`，不再反复弹回。真 HDR 视频：`play+2 1.68 → play+5 5.000007` 保持，`stop+4 3.59 → stop+5 1.0`。

## v1.8.0：ColorOS 视频 HDR 的全局 SDR 压暗

问题：ColorOS 上视频 HDR 只把面板亮度顶上去（`mgr pp ran nit=496.99 sdrNit=99.4 brightness=1660.95`），合成器不给 SDR 图层做 per-layer dimming，于是 HDR 内容变亮的同时桌面、播放器 UI 这些 SDR 内容也被一起抬亮，观感就是「只提亮、没压暗」。AOSP 在 `OutputLayer.cpp:351-361` 会算 `dimmingRatio = clamp(sdrWhitePointNits / displayBrightnessNits, 0, 1)` 交给 HWC，但这台设备的 HWC 返回 `DimmingStage::NONE`，不执行这一层。

改法（只在 ColorOS 路径 `hdrOn && !aospMode` 时启用）：`SdrDimmer` 在 system_server 里遍历 `WindowManagerService.mWindowMap` 的全部窗口，给每个窗口的 `SurfaceControl` 下发 `SurfaceControl.Transaction#setColorTransform`（RGB 乘 1/ratio，口径与 AOSP 的 dimmingRatio 相同），最后一次 `apply()` 提交。窗口自己 `colorMode=HDR` 时跳过；视频走 SurfaceView 子图层，父窗口的矩阵不下发到子图层，所以 HDR 内容不受影响。退出 HDR 时下发一次 identity 还原。

- 系数：`f = max(0.05, 1/ratio)`；ratio 优先读 `mCurrentHdrSdrRatio`，ColorOS 路径没有 `LocalDisplayDevice` 引用时退回厂商后处理链里观测到的 `nit/sdrNit`（`observedHdrRatio`）。
- 没有 HDR 时绝不下发矩阵：颜色矩阵会改变合成路径，所以只在需要压暗时下发，退出时还原一次。
- 范围是登记在 `mWindowMap` 里的所有窗口（桌面、状态栏、通知、后台应用），不是只压前台一个窗口。

实测（一加平板 Pro / Android 16 / ColorOS，HDR10 测试片，上限 5.0）：

```text
FkHDR:SdrDim: sdr dim factor=0.200 on 17 window(s), skipped 0 hdr window(s), failed 0 of 17
dumpsys SurfaceFlinger: colorTransform=[[0.200,0.000,0.000,0.000][0.000,0.200,0.000,0.000][0.000,0.000,0.200,0.000][0.000,0.000,0.000,1.000]
```

- 播放器 SDR 按钮（截图 (100,2800)-(600,2880) 均值）：播放前 `(95.3, 94.3, 96.2)` → 播放中 `(41.7, 41.7, 42.7)`（线性 0.2）→ 停止后 `(95.3, 94.3, 96.2)`，三通道一致。
- 同一帧里 HDR 视频的红色条仍是 `(255.0, 0.0, 30.2)`，与不压暗时一致 → HDR 内容没有被一起压。
- `dumpsys display` 的 `hdrSdrRatio` 播放中 5.000007、停止后 1.0。

关键坑：`Transaction#setColorTransform` 的 16 个 float 在本机由 native 侧按「列主序 3×3」解释（下标 0,1,2 = 第 0 列 = `m00,m10,m20`；3,4,5 = 第 1 列；6,7,8 = 第 2 列）。按行主序填 `(f,0,0, 0,f,0, 0,0,f, …)` 会把绿通道系数落到 `m21`，绿被压成 0、屏幕整体发紫（实测 `colorTransform=[[0.200,0.000,0.000,0.000][0.000,0.000,0.000,0.000][0.000,0.200,0.000,0.000][0.000,0.000,0.000,1.000]]`）。对角必须写在下标 0/4/8。

GAP：只在 ColorOS 路径启用；AOSP 栈走系统自己的 per-layer dimming，不需要也不该重复压暗。HDR 与 SDR 共用同一块背光，这条压暗的前提是「面板亮度已经按比值抬高」（本机由厂商驱动保证）。

---

## 模块框架：libxposed API 102

模块按新版 libxposed 规范实现，不再使用 legacy 的 `de.robv.android.xposed:api:82`。

| 项 | 值 |
|---|---|
| 入口类 | `io.github.fkhdr.FkModule`（继承 `io.github.libxposed.api.XposedModule`） |
| 注册 | `META-INF/xposed/java_init.list`（一行一个全限定类名） |
| 元数据 | `META-INF/xposed/module.prop`：`minApiVersion=102`、`targetApiVersion=102`、`staticScope=true`、`exceptionMode=protective` |
| 作用域 | `META-INF/xposed/scope.list` |
| 依赖 | `io.github.libxposed:api:102.0.0`（compileOnly） |

- 生命周期：`onModuleLoaded` 记日志 → `onSystemServerStarting` 挂 HDR 主链 → `onPackageLoaded` 在 `com.android.providers.settings` 出现时挂设置白名单。
- API 102 不允许再引用 `de.robv.android.xposed` 的任何类，所以原来的 `XC_MethodHook` before/after 钩子由 [xp/XpHook.kt](app/src/main/java/io/github/fkhdr/xp/XpHook.kt) 适配到拦截器链（`chain.proceed()` 前后执行）；[xp/Xp.kt](app/src/main/java/io/github/fkhdr/xp/Xp.kt) 提供 `hookAllMethods`/`hookAllConstructors`/`log`，[xp/XpHelpers.kt](app/src/main/java/io/github/fkhdr/xp/XpHelpers.kt) 替代 `XposedHelpers`。
- 钩子 id 是 `"fkhdr:" + 类名 + "#" + 方法名 + 参数表 + "@" + hook 站点类名`：同一处重装是原子替换（`init` 重跑不叠层），不同源码点挂在同一个 `Executable` 上也能共存。少了末尾的站点类名，后装的钩子会把先装的顶掉（v1.7.0 的 bypass 就是这样被只读 probe 替换掉的）。
- `SettingsProvider` 不在 `onSystemServerStarting` 里挂：Android 13+ 它跑在 system_server 内，但类在它自己的 APK classloader 里，必须等 `onPackageLoaded` 拿到 `defaultClassLoader`。
- 热重载未启用（`autoHotReload` 未声明）；模块更新后仍需重启设备或重启 system_server。

---

## 安装

1. 安装 APK。需要支持 libxposed API 102 的框架（LSPosed 2.x，`lspctl status` 的 `apiVersion` 应为 102）。
2. 在 LSPosed 启用模块；作用域来自 APK 内的 `scope.list`（`staticScope=true`），正常无需手动勾选。system_server 那条必须写 `system`，写 `android` 只在部分 LSPosed 版本上被自动归一（见上文第 3 条）。
3. 重启设备或重启 system_server。
4. 每次 `adb install -r` 后核对 LSPosed 记录的 APK 路径与 API 版本：

```bash
pm path io.github.benbaobaoshigemi.fkhdr
lspctl --json module show io.github.benbaobaoshigemi.fkhdr
```

若 `apkPath` 与 `pm path` 不一致，模块会被静默跳过：system_server 里没有任何日志，LSPosed 侧只有 `Failure to parse package archive /data/app/~~.../base.apk`。让 LSPosed 重写路径：

```bash
lspctl --json module disable io.github.benbaobaoshigemi.fkhdr --user 0
lspctl --json module enable  io.github.benbaobaoshigemi.fkhdr --user 0
su -c 'killall system_server'
```

只 `killall system_server` 不够，必须 disable/enable 一次。

---

## 开关

优先级：系统属性 > 模块界面开关。界面写入 `hdr_ratio_fix` 与 `hdr_ratio_max`；两者都在 [SettingsProviderHook.kt](app/src/main/java/io/github/fkhdr/hooks/SettingsProviderHook.kt) 的模块白名单内。白名单写调用用 `MethodHookParam` extra 配对 before/after，权限检查只认调用栈标记与 `args[2] = callingPackage`，限制变更仍按 setting key 复核。

| 属性 | 默认 | 说明 |
|---|---|---|
| `persist.sys.fkhdr.fix` | 开 | `0` = 关闭整个模块；所有状态写入、提亮请求、watcher 与延迟写入都会复查，关闭期间清空 HDR 判定缓存 |
| `persist.sys.fkhdr.factor` | 100 | 提亮倍数，由面板 nit 上限截断 |
| `persist.sys.fkhdr.ratio_max` | 5.0 | 人工覆盖面板曲线推出的上限（`ratioCapAt` 优先用它）；界面滑块走的是 Settings.System，不是这个属性 |
| `persist.sys.fkhdr.native` | 1 | `0` = 不用 OPPO 原生驱动 |
| `persist.sys.fkhdr.fallback` | 1 | `0` = 关闭 ColorOS 后处理兜底 |

### 比值封顶上限：App 里实时生效

`hdr_ratio_max`（Settings.System，默认 5.0，范围 1.0–20.0）由界面滑块写入，写进去之后：

- `ratioCap()` 的缓存 TTL 是 1 秒，值一变就打印 `read app ratio cap X -> Y`；
- watcher 每次唤醒调 `syncRatioCap()`，打印 `app ratio cap X -> Y, applying live`，并按新端点重装 spline（`y=[cap, 1.0]`）；
- 模块代发的比值都过 `cappedRatio()`，上限调低后当前会话立刻收口。

实测（Android 15 手机，HDR 播放中拖动滑块）：`dumpsys display` 的 `hdrSdrRatio` 在 cap 5.0 时 4.965725，改成 2.0 后 2.0，改回 5.0 后 4.2025065；每次 1 秒内完成，不需要重启 system_server 或应用。Android 16 / ColorOS 平板没有 spline，比值由厂商驱动发布，上限不截断厂商比值。
| `persist.sys.fkhdr.video` | 1 | `0` = 不把视频播放算作 HDR 场景 |
| `persist.sys.fkhdr.hbm` | 1 | `0` = 不主动进 HBM |
| `persist.sys.fkhdr.aosp_force` | 0 | `1` = 在 ColorOS 上也强制走 AOSP 通路（调试用） |

旧属性 `persist.sys.fkoscreen.hdr_*` 会自动回落读取。UI 在后台线程持久化并回读；失败时提示检查两个 LSPosed 作用域或授予 root。

---

## 工作原理

1. **HDR 判定**：焦点窗口、正在播放的媒体会话、SurfaceFlinger 图层 HDR 元数据；退出有 1200ms 迟滞。
2. **GENERIC 兜底**：捕获设备与亮度请求，按精确 `(float)->float` 签名解析 nits 链，必要时在 `DisplayAdapter` 的 SyncRoot 下发布比值。拿不到锁就放弃写入，不无锁刷新 DeviceInfo。
3. **专项链**：兼容 AOSP15 `mSdrToHdrRatioSpline` 与 OEM `mHbmData/mHdrBrightnessData.sdrToHdrRatioSpline` 两种形状；x 轴来自运行时面板 nits，曲线工厂优先 `createMonotoneCubicSpline`，再回退 `createLinearSpline`。
4. **比值语义**：有效输入为 `max(1, nits/sdrNits)` 且不人为封顶；无效输入保留 `Float.NaN`。`Display.isHdrSdrRatioAvailable()` 用 `!Float.isNaN(...)` 判可用，所以 NaN 是合法状态。
5. **自愈**：watcher 每 400ms 运行、每 3 秒自检；开关关闭时清 pendingRatio / HDR 状态，专项链失效时降级，曲线被换回时重装。
6. **量纲安全**：亮度目标由 `aospBoostTarget()` 统一计算；只有曲线有效、clamp 后仍高于 SDR 白点时才提亮。曲线不可用或厂商绝对量纲(0..2047)与 framework 0..1 不一致时保持系统原值，不硬推 `1.0`。

---

## 日志判读

启动：`init done [platform=... bypass=... aosp[dev=... bright=...] hbm=... probe=... forge=... dpc=... media=... wms=...] factor=...`。

```text
FkHDR:HdrFix: ratio bypass installed on N method(s)
FkHDR:HdrFix: ratio bypass target missing; GENERIC fallback stays active
FkHDR:HdrFix: specific chain sdrHdrRatioSpline replaced (shape ... x=[...] y=[...])
FkHDR:HdrFix: chain -> SPECIFIC(系统自算) / GENERIC(模块代劳) [spline=... bypass=... verified=... fallback=...]
FkHDR:HdrFix: ratio direct nits=... sdrNits=... -> ratio=... current=...
FkHDR:HdrFix: sync root unavailable, refusing unlocked ratio write
FkHDR:HdrFix: specific chain ineffective after 2s -> fallback to GENERIC for this session
FkHDR:HdrFix: HDR -> true/false
```

| 现象 | 含义 |
|---|---|
| 没有 `init done` | 模块未被 LSPosed 加载；检查作用域和 `apkPath` |
| `ratio bypass target missing` | 专项链比值接管没装上；GENERIC 仍在，属设计内降级 |
| `chain -> GENERIC` | 专项链未通过运行时验证，或已主动降级 |
| `spline install deferred` | 面板配置未就绪或字段形状不支持；watcher 会重试，GENERIC 继续工作 |
| `sync root unavailable` | 拒绝无锁写 / 放行原方法，优先保证不崩溃 |
| `SF layer dump failed` | HDR 证据不可用；清缓存后返回 null，不把旧 true 无限续命 |
| `ratio direct ... current=NaN` | 当前比值不可用，不是成功上报；检查 nits 链与设备日志 |

旧文档中的 `specific-chain probe -> true`、`SPECIFIC-ONLY(系统自算, 无补偿钩子)`、`aosp ratio gate opened` 不再出现；`Spline x=[2.0,650.0]` 在面板曲线可用时仍会打印。

---

## 构建

```bash
cd hdr-module
./gradlew :app:clean :app:assembleRelease --offline --console=plain
# 产物：app/build/outputs/apk/release/app-release.apk
```

要求：JDK 21、Android SDK 37、Gradle 9.8.0、AGP 9.4.1、Kotlin 2.4.20。`android.suppressUnsupportedCompileSdk=37` 与 `compileSdk = 37` 匹配。

---

## 实机验证（v1.7.6 验收 / v1.8.0 复验）

设备：Redmi 22041216C / Android 15 / HyperOS / LSPosed 2.2.1（`lspctl status` 报 `apiVersion=102`）。验收 App：HDR能力检测 V0.7（`com.lns103.hdrCapability`）。

```text
lspctl --json module show io.github.benbaobaoshigemi.fkhdr
  minApiVersion=102 targetApiVersion=102 legacy=false staticScope=true
  declaredScope=["com.android.providers.settings","system"]
FkHDR:FkModule: loaded api=102 framework=LSPosed/2.2.1 systemServer=true process=system
FkHDR:HdrFix: init done [platform=aosp bypass=1 aosp[dev=1 bright=1] hbm=true probe=1 ratioAvail=1 forge=1 dpc=true media=3 wms=2] factor=100.0
FkHDR:HdrFix: ratio availability candidates=2 withUpdateDeviceInfo=1 hooked=1
FkHDR:HdrFix: ratio availability hook installed on 1 method(s)
FkHDR:HdrFix: specific chain sdrHdrRatioSpline replaced (DisplayDeviceConfig.mSdrToHdrRatioSpline Spline x=[2.0,650.0] y=[5.0,1.0])
FkHDR:HdrFix: ratio bypass nits=16.16 sdrNits=3.7 -> 4.37
FkHDR:HdrFix: ratio bypass nits=18.43 sdrNits=3.7 -> 4.98
FkHDR:HdrFix: setDisplayBrightness boost skipped (healthy specific chain)
FkHDR:FkModule: hooking SettingsProvider
FkHDR:SettingsProviderHook: initialized (module-only whitelist writes)
```

- 播放 HDR 时 `dumpsys display` 的 `DisplayDeviceInfo` / `mBaseDisplayInfo` / `mOverrideDisplayInfo` 三处 `hdrSdrRatio` 都是有限值（空闲 1.0，HDR 时随内容变化），不再出现 `not_available`。
- 验收 App 从「HDR SDR调光支持 ❌ / 比率 1.00000」变为「✔ / 4.37384」，与 bypass 日志的 4.37 一致 → 应用已注册监听并实时收到比值。
- `setDisplayBrightness boost skipped (healthy specific chain)` 说明专项链（系统自算比值）在跑，GENERIC 亮度补偿主动让位；HDR 退出后仍按 1200ms 迟滞回落。
- v1.7.3 复验（同一台手机，HDR 播放中点「HDR图片测试」）：`specific chain sdrHdrRatioSpline replaced (... Spline x=[2.0,650.0] y=[5.0,1.0])`、`HDR -> true(focus=true) [driver=null]`、`setDisplayBrightness boost skipped (healthy specific chain)`、`ratio bypass nits=55.6 sdrNits=22.1 -> 2.52`，`dumpsys display` 的 `hdrSdrRatio` 同步变化（1.4128228）—— 专项链在 v1.7.3 上仍然生效；上限滑块实测见上面的「比值封顶上限」小节（4.965725 → 2.0 → 4.2025065）。

### 第二台设备：一加平板 Pro（OPD2404，Android 16 / ColorOS，LSPosed 2.2.1 API 102）

`platform=coloros`：走 OEM 原生驱动 + GENERIC 兜底。Android 16 上 `sdrToHdrSpline=false`（这一版没有 `DisplayDeviceConfig.mSdrToHdrRatioSpline`），专项链按设计不安装。

```text
FkHDR:FkModule: loaded api=102 framework=LSPosed/2.2.1 systemServer=true process=system
FkHDR:HdrFix: init done [platform=coloros features=2 ratioMax=true featCfg=4 fmgr=1 ratioFlag=1 native=true boost=1 forge=1 dpc=true media=3 wms=1] factor=100.0
FkHDR:HdrFix: panel config ready [sdrToHdrSpline=false]
FkHDR:HdrFix: chain = GENERIC(模块代劳, 兜底已安装)
FkHDR:HdrFix: driver ready [support=true maxNit=846.09 factor=0.0]
FkHDR:FkModule: hooking SettingsProvider
FkHDR:SettingsProviderHook: initialized (module-only whitelist writes)
FkHDR:HdrFix: HDR -> true(focus=true) [need=true enter=true enhanced=false playingHDR=0 battery=57 powerSave=false maxNit=846.09]
FkHDR:HdrFix: pp nit=99.39909/99.39909 cap=none(physical) [...]
FkHDR:HdrFix: fallback idle (native engaged, nit=846.09 sdrNit=99.4 ratio=8.51)
FkHDR:HdrFix: mgr pp ran nit=846.09485 sdrNit=99.39909 brightness=2047.0
```

验收 App：HDR SDR调光支持 ✔、HDR SDR调光比率 8.51210、逻辑峰值亮度 1728 cd/m²、HDR显示状态 ✔。

v1.8.0 在同一台平板上复验（探针播放器播 HDR10 测试片）：

- 模块：`init done [platform=coloros ... native=true boost=1 wms=1]`、`panel config ready [sdrToHdrSpline=false]`、`chain = GENERIC(模块代劳, 兜底已安装)`、`sdr dim factor=0.200 on 17 window(s), skipped 0 hdr window(s), failed 0 of 17`。
- `dumpsys SurfaceFlinger` 出现 `colorTransform=[[0.200,0.000,0.000,0.000][0.000,0.200,0.000,0.000][0.000,0.000,0.200,0.000][0.000,0.000,0.000,1.000]`，停止播放后该行计数归 0。
- 屏幕像素：SDR 按钮区 `(95.3, 94.3, 96.2)` → `(41.7, 41.7, 42.7)` → 停止后 `(95.3, 94.3, 96.2)`；HDR 视频红条 `(255.0, 0.0, 30.2)` 不受影响。
- `hdrSdrRatio` 播放中 5.000007、停止后 1.0。

## 验证与 GAP

- 已完成：7 份切片审查 + AOSP15 基线对照；`git diff --check`、`:app:compileReleaseKotlin`、clean release 构建、`aapt2 dump badging` 通过；两台设备实机验收通过（Android 15 手机专项链 ✔ 4.37 / Android 16 平板原生驱动 + GENERIC ✔ 8.51，截图均已存证）；v1.8.0 的全局 SDR 压暗在 Android 16 平板上实测生效（系数 0.2、17 个窗口、退出还原）。
- 未完成：其它 ROM；Android 16 上 `sdrToHdrRatioSpline` 缺失，专项链在 Android 16 未验证；多显示器实例隔离；仪器化测试；全局 SDR 压暗只覆盖登记在 `mWindowMap` 的窗口，SurfaceView 子图层（视频本身）按设计不压，游戏全屏 Surface 的观感未单独验证。
- 不要把 v1.6.1 的历史实测数值当作本版本验收证据。完整契约见 [docs/aosp-alignment.md](../docs/aosp-alignment.md)。
