# feat(hdr): 按硬件上限恢复 SDR/HDR 比值

> 替换写死 1.5625 的伪造方案，让 HDR 真正提亮、关闭后正常回落。

## 问题

ColorOS 上 HDR 内容只被压暗、面板不提亮：`Display.getHdrSdrRatio()` 恒返回 `1.0`，
`isHdrSdrRatioAvailable()` 为 `false`，验收 App「HDR SDR调光支持」显示 ❌。

## 根因

1. 产品配置缺 3 个 OPPO feature（`enhance_brightness_with_uidimming` /
   `hdr_enhance_brightness_support` / `dolby_enhance_v1_support`）
   → `LocalDisplayDeviceExtImpl.isSupportSdrToHdrRatio()` 恒 false
   → `LocalDisplayAdapter` 的 `handleHdrSdrNitsChanged()` 从不执行
   → `DisplayInfo.hdrSdrRatio` 停在 `NaN`，`OplusFeatureHDREnhanceBrightness` 也不进亮度链。
2. `OplusDisplayBrightnessState.Builder.setNit()` 会顺手覆盖 `sdrNit`，面板与 SDR 白点恒等
   → 即使面板亮了，比值仍恒 1。
3. 原 `HdrRatioHook` 只 hook `DisplayInfo.getHdrSdrRatio` 伪造 1.5625：不进亮度管线、只骗读者，
   所以"能压暗、不能提亮"。

## 改动

**新增 `HdrBoostHook`**

- 补 3 个 feature，恢复系统自身的比率计算与 HDR 提亮 feature
- **不使用白名单**：直接接管 `mEnterWhiteApp`，任何前台应用均可触发
- 提亮交给 ColorOS 自己的 `enterEnhanceBrightness()/exitEnhanceBrightness()`
  （倍数、clamp 到 `getMaxPanelNit()`、亮度动画全部是系统代码，本模块不实现亮度算法）
- 提亮前 `setHasEdrProcessed()` 保住 SDR 白点，使 `hdrSdrRatio` 反映真实 nits 比值
- HDR 开关信号三路取或：焦点窗口 `LayoutParams.colorMode` / relayout 事件 / SF transact 21012
- 找不到 `OplusFeatureHDREnhanceBrightness` 时降级到通用 nit 后处理链

**其它**

- 删除 `HdrRatioHook`（写死 1.5625，已无调用方）
- `ConfigManager` 新增 `isHdrFixEnabled()`（复用 `hdr_ratio_fix` 键），旧方法标 `@Deprecated`
- 模块界面：「修复 HDR 亮度比例 / 锁定 1.56」→「HDR 提亮修复 / 按面板硬件上限恢复比值」

## 实测（OPD2404 / 一加平板 Pro / ColorOS 16）

| 状态 | mScreenBrightness | mSdrScreenBrightness | hdrSdrRatio |
|---|---|---|---|
| HDR 关 | 800 | 800 | **1.0** |
| HDR 开 | 2047 | 800 | **7.19** |
| 再关 | 800 | 800 | **1.0** |

比值 = `面板 nit(getMaxPanelNit()) / SDR 白点 nit`，不设软件封顶：
低亮度余量大→比值大，亮度拉满→回落到 1.0，HDR 关闭→动画回落 1.0。

## 兼容性

- 所有 hook 均包在 `try/catch`，类不存在则静默跳过
- 非 ColorOS 或无对应私有类的机型：自动走降级分支或空操作，不会崩
- 关闭方式：`setprop persist.sys.fkoscreen.hdr_fix 0`（重启生效）

## Checklist

- [x] 本地编译通过（AGP 8.3.2 / Gradle 8.6 源码兼容，未改动构建配置）
- [x] 真机验证：进入 / 退出两态，含按 Home 离开 App 后的回落
- [ ] 其它 ColorOS 机型验证（请维护者协助，用 `dumpsys display | grep hdrSdrRatio` 复核）
