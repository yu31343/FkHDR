# ColorOS HDR 修复（OPD2404 / 一加平板 Pro / ColorOS 16）

修复 ColorOS 上「HDR 内容只压暗、不提亮，SDR/HDR 调光比率恒为 1」的问题。
验收标准：`com.lns103.hdrCapability`（HDR能力检测 V0.7）开关两态均正常。

## 现象

| | 修复前 | 修复后 |
|---|---|---|
| App「HDR SDR调光支持」 | ❌ | ✔️ |
| App「HDR SDR调光比率」 | 1.00000（恒为一） | HDR 关 1.00000 / HDR 开 7.19112 |
| 面板背光 vs SDR 白点 | 恒等 | HDR 开 2047 / 800 |
| HDR 内容观感 | 只压暗不提亮 | 面板真实提亮，退出后动画回落 |

## 根因（逆向 + 实测）

1. **产品配置缺 3 个 OPPO feature**：`enhance_brightness_with_uidimming` /
   `hdr_enhance_brightness_support` / `dolby_enhance_v1_support`
   → `LocalDisplayDeviceExtImpl.isSupportSdrToHdrRatio()` 恒 false
   → `LocalDisplayAdapter:855 handleHdrSdrNitsChanged()` 从不执行
   → `DisplayInfo.hdrSdrRatio` 停在 `NaN` → `Display.getHdrSdrRatio()` 兜底 `1.0f`
   → ColorOS 的 HDR 提亮 feature 根本不进亮度后处理链。
2. **`<hdr_enhance_method>` 白名单表整张缺失**（`display_brightness_app_list.xml`）
   → `getAppPkgs()` 恒空 → `mEnterWhiteApp` 永远 false → 所有应用都拿不到提亮。
3. **面板没有 HDR color mode**（`supportedColorModes=[0,7,9]`）
   → 应用 `setColorMode(COLOR_MODE_HDR)` 时 SurfaceFlinger 不进入 HDR 合成
   → ColorOS 的 `checkIsPlayingHDR()`（SF transact 21012）恒 0，HDR 开关信号缺失。
4. **`Builder.setNit()` 会顺手覆盖 sdrNit** → 面板亮了但 SDR 白点跟着变，比率恒 1。

详细逆向见 [docs/root-cause-framework.md](docs/root-cause-framework.md)、[docs/root-cause-oplus.md](docs/root-cause-oplus.md)。

## 方案（模块只补配置 / 补信号，不实现亮度算法）

`module/app/src/main/java/io/github/fkoscreen/hooks/HdrBoostHook.kt`

| 层 | 做法 |
|---|---|
| 配置 | hook `OplusFeatureConfigManagerService.hasFeature/hasFeatureMap`，补 3 个 feature |
| 白名单 | **不使用白名单**：直接接管 `mEnterWhiteApp`，任何前台应用都可触发 |
| 提亮 | 驱动 ColorOS 自己的 `enterEnhanceBrightness()/exitEnhanceBrightness()`（倍数、clamp 到 `getMaxPanelNit()`、亮度动画全部是它的代码） |
| 比率 | 不写死任何比值：提亮前 `setHasEdrProcessed()` 保住 SDR 白点，系统按**面板硬件上限**推导 `ratio = getMaxPanelNit() / SDR 白点 nit` |
| 信号 | 焦点窗口 `LayoutParams.colorMode` / relayout 事件 / SF transact 21012 三路取或 |
| 降级 | 找不到 `OplusFeatureHDREnhanceBrightness` 时回退到 ColorOS 通用 nit 后处理链 |

所有 hook 都有 try/catch，类不存在则静默跳过，不影响其它 ColorOS 版本/机型。

## 构建与安装

```bash
cd module
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew assembleDebug --offline
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb reboot          # system_server 需重启才能加载 hook
```

LSPosed 作用域：**系统框架（system）** 必选；其余（SystemUI / 设置 / eyeprotect）按需。

详细构建说明见 [module/BUILD.md](module/BUILD.md)。

## 验证

```bash
adb shell dumpsys display | grep -E '^  mScreenBrightness=|^  mSdrScreenBrightness='
adb shell dumpsys display | grep -oE 'hdrSdrRatio [0-9.]+'
```

| 状态 | mScreenBrightness | mSdrScreenBrightness | hdrSdrRatio |
|---|---|---|---|
| HDR 关 | 800 | 800 | 1.0 |
| HDR 开 | 2047 | 800 | 7.19 |
| 再关 | 800 | 800 | 1.0 |

## 控制与回滚

```bash
adb shell su -c 'setprop persist.sys.fkoscreen.hdr_fix 0'      # 关闭（重启生效）
adb shell su -c 'setprop persist.sys.fkoscreen.hdr_factor 100' # 提亮倍数，默认 100（会被 clamp 到硬件上限）
```

## 已知边界

- 面板没有 HDR color mode，本质是「tone-map 到 SDR 容器 + 面板提亮」，不是真正的 HDR 输出链路（硬件限制）。
- `DisplayDeviceConfig.mHbmData=null`（内屏缺 `display_id_*.xml`）、`lhdr_only_dimming_support`
  属 vendor/native 层，Java 侧无法修改。
- 需要 LSPosed（作用域：系统框架）。

## 许可

`module/` 基于 [FkOScreen](https://github.com/benbaobaoshigemi/FkOScreen)（Apache-2.0）二次开发。
