# 类原生（LineageOS）无效 — 日志闭环分析（2026-10-23）

> 结论：**类原生上 v1.9.1 从来没有真正提亮过一次**。两条通路各断一次，
> 且两次失败都是**静默**的 —— 日志里除了 `HDR -> true` 之外什么都没留下。
> v1.9.2 已修，并补了能让下一次反馈「一眼定位」的诊断日志。

---

## 1. 日志来源

| 项 | 值 |
|---|---|
| 文件 | `LSPosed_20261023_214917.zip`（LSPosed 自带日志导出） |
| 设备 | OnePlus Pad Pro `OPD2404` / OP5D77L1 / SM8650（与 ColorOS 那台**同一块硬件**） |
| 系统 | `BP4A.251205.006` / Android 16 / SDK 36 —— **LineageOS 23**（日志里出现 `org.lineageos.lineageparts`） |
| LSPosed | v2.2.1 (7925) |
| 模块 | `io.github.benbaobaoshigemi.fkoscreen` v1.9.1（versionCode 17） |
| 作用域 | `system` / `com.android.systemui` / `com.android.settings` ✅ 正常 |

---

## 2. 日志证据（逐条）

### 2.1 模块确实加载了，且被判定为 AOSP 平台

```
FkOScreen:HookInit: Hooking system_server...
FkOScreen:HdrFix: init done [platform=aosp aosp[dev=1 bright=1] dpc=true media=3 wms=1] factor=100.0
FkOScreen:HdrFix: panel config ready [sdrToHdrSpline=false]
```

- `platform=aosp` —— 判定正确（无 OPPO 类，有 `LocalDisplayAdapter`）
- `dev=1 bright=1` —— 两个 hook 都**挂上了**
- `sdrToHdrSpline=false` —— **面板没有 sdr→hdr 比值曲线**，这就是后面闸门关死的原因

### 2.2 HDR 检测是好的

```
21:43:43.396  FkOScreen:HdrFix: HDR -> true(focus=true) [driver=null]
21:43:47.011  FkOScreen:HdrFix: HDR -> false(focus=false) [driver=null]
```

`focus=true` = 焦点窗口 `colorMode == COLOR_MODE_HDR`，说明**内容识别没问题**。
`[driver=null]` 正常（类原生没有 OPPO 原生驱动）。

### 2.3 但是：提亮链一行日志都没有

在整个日志（含 `log.old` 里 21:43–21:44 三个滚动文件）中搜索：

| 期望出现的日志 | 实际 |
|---|---|
| `aosp boost brightness ...`（注入点触发） | **0 次** |
| `aosp ratio gate opened ...`（闸门挂上） | **0 次** |
| `aosp funnel alive ...`（v1.9.2 新增） | 当时还没有 |

**即：HDR 状态机翻转了，但没有任何一环真正作用到亮度上。**

---

## 3. 根因一：注入点选错了 —— 被动 hook 永远等不到点火

AOSP 16 的真实调用链（已对 `android16-release` 源码逐行核实）：

```
DisplayPowerController.updatePowerState()
  └─ RampAnimator（动画帧，值不变就不写）
      └─ DisplayPowerState.setScreenBrightness(v)
           if (mScreenBrightness != v) {          ← 值没变就 return
               scheduleScreenUpdate()
                   └─ PhotonicModulator.setState(state, b, sdr)
                        if (stateChanged || backlightChanged) { ... }   ← 又是变化才走
                            └─ LocalDisplayDevice.requestDisplayStateLocked(state, b, sdr, session)
                                 if (stateChanged || brightnessChanged) {
                                     return new Runnable() { ... }   ← 只有变化才创建
                                 }
                                 return null;                      ← 否则 null
```

v1.9.1 把注入点放在最末端 `LocalDisplayAdapter$LocalDisplayDevice$1#setDisplayBrightness`。
**这个方法是上面那条链的叶子，只有「亮度真的变了」才会被调用。**
而 HDR 起播本身不改变亮度 → 整条链根本不会跑 → hook 一辈子不触发。

> 这正是「日志里只有 HDR -> true，没有任何 boost」的原因。
> 顺带说明：当时即使触发了，`run()` 随后会用**未修改的**参数写回
> `mBrightnessState = brightnessState`，导致退出 HDR 时无法回落 —— 也是同一个设计缺陷。

### 修复（v1.9.2）

1. **改到漏斗口**：hook `LocalDisplayDevice#requestDisplayStateLocked` 的 **before**，
   把 `args[1]`（brightnessState）改成 `1.0`，`args[2]`（sdrBrightnessState）保留用户值。
   方法内部据此算出 `brightnessChanged=true` → 正常创建 Runnable → 正常写回 `mBrightnessState`。
2. **主动点火**：HDR 翻转时反射调一次
   `requestDisplayStateLocked(mState, peak|user, user, null)` 并 `run()` 返回的 Runnable。
   这样不依赖 DPC 自己发起更新，HDR 一出现立刻生效；HDR 结束再推一次用户亮度，自然回落。

---

## 4. 根因二：AOSP 的比值闸门被当成 OPPO 的 Ext 了

v1.9.1 的类原生通路假设：

```java
// 以为 AOSP 也有（其实只有 ColorOS 有）
if (mLocalDisplayDeviceExt.isSupportSdrToHdrRatio() && nits != 0 && sdrNits != 0)
    handleHdrSdrNitsChanged(nits, sdrNits);
```

**核实结果（android16-release，LocalDisplayAdapter.java）**：

- AOSP `LocalDisplayDevice` **没有** `mLocalDisplayDeviceExt` 字段
- AOSP **没有** `isSupportSdrToHdrRatio()` 方法
- `ILocalDisplayDeviceExt` / `LocalDisplayDeviceExtImpl` / `ExtLoader` 全部是 **oplus-services** 的东西
- AOSP 真正的闸门是：

```java
if (getDisplayDeviceConfig().hasSdrToHdrRatioSpline()) {
    handleHdrSdrNitsChanged(nits, sdrNits);
}
```

而本机 `hasSdrToHdrRatioSpline() = false`（`sdrToHdrSpline=false`，日志已证）→
`handleHdrSdrNitsChanged` 永不调用 → `mCurrentHdrSdrRatio` 一直是初始值 `NaN`。

旧 `hookExt()` 里 `getObjectField(dev, "mLocalDisplayDeviceExt")` 抛 `NoSuchFieldException`，
被 `catch (_: Throwable) {}` 吞掉 → **完全静默**。

### 修复（v1.9.2）

- 新增 `hookAospRatioGate()`：hook `DisplayDeviceConfig#hasSdrToHdrRatioSpline()` → 返回 true。
  放行后 `handleHdrSdrNitsChanged` 自己用 `backlightToNits` 算 `nits/sdrNits`；
  HDR 关时 `brightness == sdr` → 比值自然回到 1.0，不需要额外收拾。
- `hookExt()` 保留（ColorOS 强制走 AOSP 兜底时仍需要），但**失败必须打日志**，
  并改成「按字段名 + 按类型名」两种策略查找。

---

## 5. 还有一个隐藏限制：`mUseSurfaceControlBrightness`

`handleHdrSdrNitsChanged` 内部还有一层条件：

```java
if (displayNits != INVALID_NITS && sdrNits != INVALID_NITS
    && (mBacklightAdapter.mUseSurfaceControlBrightness || mBacklightAdapter.mForceSurfaceControl)) {
    newHdrSdrRatio = Math.max(1f, displayNits / sdrNits);
} else {
    newHdrSdrRatio = Float.NaN;      // ← 比值作废
}
```

`mUseSurfaceControlBrightness` 来自 `SurfaceControl.getDisplayBrightnessSupport(token)`。
若该机型走的是 HWC 私有背光（false），则**即使闸门开了也只有物理提亮、没有比值**。

v1.9.2 在 `aosp funnel alive` 日志里直接打出这个值，下一份日志即可判定。

---

## 6. 顺带修掉的一个真实缺陷：system_server 读不到设置

日志里有一条 SELinux 拒绝：

```
avc: denied { open } for comm="PhotonicModulat"
  path="/data/data/io.github.benbaobaoshigemi.fkoscreen/shared_prefs/fkoscreen_prefs.xml"
  scontext=u:r:system_server:s0 tclass=file permissive=0
```

`PhotonicModulat` 是 `DisplayPowerController$PhotonicModulator` 线程的 comm（截断到 15 字符），
即**我们的 hook 在亮度线程上读模块 prefs 被 SELinux 拦了**。

根因不是 prefs 本身，而是 `ConfigManager.getSettingInt()` 在 system_server 里
`ActivityThread.currentApplication()` 返回 null → 拿不到 ContentResolver → 每次都回退到
`XSharedPreferences` → 每 5 秒一次 avc denied。

**修复**：`appContext()` 增加 `ActivityThread#getSystemContext()` 兜底；
被 hook 的进程里若 Settings.System 没有该键，直接返回默认值，不再去碰 prefs 文件。
（模块界面每次改开关都会写 Settings.System，所以开关依然有效。）

---

## 7. v1.9.2 之后，日志应该长什么样

正常工作时，一次「播放 HDR → 停止」应当依次出现：

```
FkOScreen:HdrFix: init done [platform=aosp aosp[dev=1 bright=1] gate=1 probe=1 dpc=true media=3 wms=1]
FkOScreen:HdrFix: aosp ratio gate opened on DisplayDeviceConfig.hasSdrToHdrRatioSpline (1 method)
FkOScreen:HdrFix: aosp funnel alive (state=2 brightness=0.39 sdr=0.39 useSurfaceControlBrightness=true)
FkOScreen:HdrFix: HDR -> true(focus=true) [driver=null]
FkOScreen:HdrFix: aosp push applied brightness=1.0 sdr=0.39 state=2
FkOScreen:HdrFix: ratio probe nits=2047.0 sdrNits=800.0 -> ratio=2.56
FkOScreen:HdrFix: HDR -> false(focus=false) [driver=null]
FkOScreen:HdrFix: aosp push applied brightness=0.39 sdr=0.39 state=2
```

判读表：

| 现象 | 含义 |
|---|---|
| 没有 `gate=1` | `hasSdrToHdrRatioSpline` 没挂上（ROM 改类名） |
| 有 `aosp funnel alive` 但没有 `HDR -> true` | 内容识别失败，与提亮无关 |
| 有 `HDR -> true` 但没有 `aosp push applied` | 主动推送失败（看 `aosp push skipped/failed` 那行） |
| 有 push 但屏幕不变亮 | 面板亮度模型不接受 1.0（需看 `DisplayDeviceConfig` 的 brightness map） |
| `useSurfaceControlBrightness=false` | 只有物理提亮，App 侧读不到 hdrSdrRatio |
| 有 push 但没有 `ratio probe` | 闸门没生效（回到第 1 行） |

---

## 8. 结论

| 版本 | 类原生表现 |
|---|---|
| v1.9.1 及以前 | **完全无效**：注入点永不触发 + 闸门指向 OPPO 私有符号（不存在），两条都静默失败 |
| v1.9.2 | 注入点改到漏斗口 + 主动点火 + AOSP 正确闸门 + 全链路诊断日志 |

---

# 第二轮：v1.9.2 回验日志（`LSPosed_20261024_222107.zip`，2026-10-24 22:20）

## 9. 这一轮证实了什么

**注入链已经打通了。** 面板驱动自己的背光日志是决定性证据：

```
<CSOT-NT36532> dsi_display_set_backlight ... 14:20:40.300:2047, 14:20:41.898:21 ...
```

背光 **21 → 2047 → 21**，即「HDR 一出现面板真的被推到物理最大」。模块侧对应：

```
22:20:40.286 HDR -> true(focus=true) [driver=null]
22:20:40.286 aosp push brightness 0.01 -> 1.0 (sdr kept 0.01)
22:20:40.287 aosp boost brightness 0.01 -> 1.0 (sdr kept 0.01)
22:20:40.291 aosp push applied brightness=1.0 sdr=0.01 state=2
22:20:41.890 HDR -> false(focus=false) [driver=null]
22:20:41.893 aosp push applied brightness=0.01 sdr=0.01 state=2
```

同时 `init done [... gate=1 probe=1 ...]`、`aosp funnel alive (... useSurfaceControlBrightness=true)` 都出现了。

## 10. 但还有一环没通：比值仍然是 NaN

整份日志里 **`ratio probe` 一次都没出现**。含义很明确：

- `hasSdrToHdrRatioSpline` 的 hook 装了（`gate=1`），但
- `handleHdrSdrNitsChanged` **从未被调用**（`probe=1` 却零触发）

结论：**调用点 `if (getDisplayDeviceConfig().hasSdrToHdrRatioSpline()) handleHdrSdrNitsChanged(...)`
被 ART 内联成了常量 false**，挂在被调方（`hasSdrToHdrRatioSpline`）上的 hook 不生效。
这类「被调方内联」是 Xposed 的经典坑：hook 一个返回常量的小方法，调用方早已把它 inline 掉。

后果：`mCurrentHdrSdrRatio` 恒为初始值 NaN → `DisplayInfo.hdrSdrRatio` 仍是 NaN →
验收 App（`com.lns103.hdrCapability`）依旧不通过 → 用户看到「问题依旧」。

## 11. v1.9.3 的修法：不靠闸门，自己算自己调

在 `$1.setDisplayBrightness` 的 **after** 钩子里直接完成整条计算（三个方法都在 `$1` 上，反射可调）：

```
backlight    = brightnessToBacklight(brightnessState)
sdrBacklight = brightnessToBacklight(sdrBrightnessState)
nits         = backlightToNits(backlight)
sdrNits      = backlightToNits(sdrBacklight)
handleHdrSdrNitsChanged(nits, sdrNits)          ← 直接调用，绕过内联掉的闸门
```

- HDR 期间：`nits ≠ sdrNits` → 比值 = `nits/sdrNits`（真实物理比值）
- HDR 结束：回落推送 `(sdr, sdr)` → `nits == sdrNits` → 比值自然回 1.0
- 首次解析会打 `ratio direct ready (brightnessToBacklight=.. backlightToNits=.. handleHdrSdrNitsChanged=..)`
- 每次计算打 `ratio direct nits=.. sdrNits=.. -> ratio=.. current=..`，`current` 就是 App 最终读到的值

## 12. 第二个补强：DisplayInfo 伪装改为全平台

v1.9.1/v1.9.2 只对 ColorOS 做 `supportedColorModes` 补 10/11/12，类原生没做。
这就是为什么类原生上应用**无法把窗口 colorMode 设成 HDR**，只能被动等它自己设。
v1.9.3 把 `hookDisplayInfoForgery` 移到通用段：`patchDisplayInfo` 自带守卫
（已含 11/12 就原样返回），真 HDR 机型不会被改动。

## 13. 第三轮日志判读表（v1.9.3）

| 日志现象 | 判定 |
|---|---|
| 有 `ratio direct ready (brightnessToBacklight=false ...)` | 该 ROM 的 `$1` 方法名不同，需按实际类反编译适配 |
| 有 `aosp push applied` 但无 `ratio direct nits=` | 反射调用被拒（`ratio direct failed` 会给出异常） |
| `ratio direct nits=.. sdrNits=.. -> ratio=N current=M` 且 **M > 1** | ✅ 比值已真实上报 |
| `ratio=N` 但 `current=NaN` | `handleHdrSdrNitsChanged` 内部判了 NaN：看 `useSurfaceControlBrightness` 是否为 false |
| 有 `forged DisplayInfo HDR colorModes ...` | 应用现在能看到 HDR 色彩模式，可主动请求 COLOR_MODE_HDR |
| 面板背光日志出现 `:2047` | 物理提亮已生效（`dmesg | grep dsi_display_set_backlight`） |

---

## 14. 三轮结论汇总

| 版本 | 类原生表现 | 断在哪 |
|---|---|---|
| v1.9.1 及以前 | 完全无效 | 注入点永不触发 + 闸门指向 OPPO 私有符号 |
| v1.9.2 | **物理提亮成功**（背光 21→2047），比值仍 NaN | 闸门 hook 被 ART 内联，`handleHdrSdrNitsChanged` 不执行 |
| v1.9.3 | 比值改为自算自调 + 全平台 DisplayInfo 伪装 | 待回验 |

