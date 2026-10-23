# ColorOS HDR 修复（最终版：单模块，无 KernelSU）

## 验收（实测，重启后稳定复现）

| 状态 | HDR SDR调光支持 | 比率 | 逻辑峰值 | 面板 / SDR 白点 |
|---|---|---|---|---|
| HDR 关 | ✔️ | **1.00000** | 203 cd/m² | 800 / 800 |
| 点一次「HDR图片测试」 | ✔️ | **7.19112** | 1460 cd/m² | **2047 / 800** |
| 再点一次（关） | ✔️ | **1.00000** | 203 cd/m² | **800 / 800** |

日志：`HDR -> true` → `HDR -> false`，亮度/比率由 ColorOS 自己的 DPC 动画回落。

## 被解除的 5 个阻碍

| # | 阻碍 | 解除方式 |
|---|---|---|
| 1 | 产品配置缺 3 个 OPPO feature（`enhance_brightness_with_uidimming` / `hdr_enhance_brightness_support` / `dolby_enhance_v1_support`）→ `isSupportSdrToHdrRatio()` 恒 false、HDR 提亮 feature 不入链 | hook `OplusFeatureConfigManagerService.hasFeature/hasFeatureMap` 返回 true |
| 2 | `display_brightness_app_list.xml` 里整张 `<hdr_enhance_method>` 白名单表缺失 → 所有应用都拿不到提亮 | hook `existInHdrAppList()` 恒 true（全应用适用） |
| 3 | 面板无 HDR color mode → SF 不进 HDR 合成 → `checkIsPlayingHDR()` 恒 0 | 三路信号取或：relayout 事件里的 `colorMode==COLOR_MODE_HDR` / 焦点窗口 `LayoutParams.colorMode` / SF transact 21012 |
| 4 | ColorOS 提亮只改面板 nit，`Builder.setNit()` 顺手把 sdrNit 改成同值 → 面板亮了但比率恒 1 | 提亮前 `setHasEdrProcessed()`，保住 SDR 白点，面板/SDR 分离；比值由系统按**面板硬件上限**推导（不设软件封顶），HDR 关时回落 1.0 |
| 5 | 退出时系统侧无信号 → 亮度不回落 | 信号消失即调 ColorOS 自己的 `exitEnhanceBrightness()`，由 DPC 动画回落 |

## 模块构成（`HdrBoostHook.kt`，约 250 行）

- `hookFeatures()`：补 feature
- `hookHdrEnhance()`：白名单放行 + 保 SDR 白点 + 驱动 `enterEnhanceBrightness()/exitEnhanceBrightness()`
- `hookWindowManager()`：拿 WMS 实例 + relayout 里维护 HDR 窗口集合
- `startWatcher()`：400ms 轮询三路信号，状态翻转时驱动 ColorOS 状态机

**亮度算法一行没写** —— 提亮倍数、clamp 到 `getMaxPanelNit()`、进入/退出动画全部是 ColorOS 自己的代码。

## 已知边界

- 需要 LSPosed（作用域：系统框架）。
- 触发依赖「焦点窗口请求 HDR」或「SF 报告 HDR」；分屏下非焦点窗口的 HDR 可能漏检（relayout 集合可覆盖一部分）。
- 面板本身没有 HDR color mode，所以仍是「tone-map 到 SDR 容器 + 面板提亮」，不是真正 HDR 输出链路（硬件限制）。
- 关闭：`setprop persist.sys.fkoscreen.hdr_fix 0` + 重启。
