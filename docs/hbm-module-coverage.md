# 设备上「改配置 + 反编译 jar」HDR 模块的完整解码与 FkHDR 覆盖度

> 样本：Redmi xaga（HyperOS 3 / Android 15）上的 `hdr_sdr_ratio_fix` 模块 v1（作者：囫碾）
> 方法：从模块目录取文件 → 与 stock 分区逐字节对比（stock 通过 `mount -t erofs -o ro /dev/block/dm-{2,3}` 取得）

---

## 1. 这个模块到底改了什么

### 1.1 配置文件（1 个文件，1 个数字）

`/product/etc/displayconfig/display_id_4627039422300187648.xml`

```diff
         <sdrHdrRatioMap>
             <point>
                 <sdrNits>2.0</sdrNits>
-                <hdrRatio>1.0</hdrRatio>
+                <hdrRatio>5.0</hdrRatio>
             </point>
             <point>
                 <sdrNits>650.0</sdrNits>
                 <hdrRatio>1.0</hdrRatio>
```

**只改了这一个数字。** 其余（`highBrightnessMode enabled=true`、`transitionPoint`、
`minimumLux=6001`、`thermalStatusLimit=critical`、`allowInLowPowerMode=true`、
`minimumHdrPercentOfScreen=0`、`screenBrightnessMap` 2→650 nits）**全是 ROM 原厂就有**。

作用：`HighBrightnessModeController.getHdrBrightnessValue()`
→ `DisplayDeviceConfig.getHdrBrightnessFromSdr(brightness, maxRatio)`
→ 查这条 spline。原厂两端都是 1.0 ⇒ HDR 亮度 == SDR 亮度 ⇒ 无论 HBM 进不进都不提亮。
改成低 SDR 亮度处 5.0 ⇒ 低亮度时 HDR 目标亮度是 SDR 的 5 倍。

### 1.2 services.jar（重打包，只有 1 个类被语义修改）

- classes.dex / classes3.dex 完全一致；只有 **classes2.dex** 不同（baksmali 全量 diff）
- 12 个类文件有差异，其中 **11 个只是 2 行行号噪声**（重编译产物）
- 真正的语义改动只有 **1 个**：

`com/android/server/display/LocalDisplayAdapter$LocalDisplayDevice$1.smali`
（即 `handleHdrSdrNitsChanged`）

```diff
 if (displayNits != -1 && sdrNits != -1
-    && (mBacklightAdapter.mUseSurfaceControlBrightness || mBacklightAdapter.mForceSurfaceControl)
-    && LocalDisplayAdapter.this.mSupportUltraHDR) {      ← 被删掉的就是这一行
+    && (mBacklightAdapter.mUseSurfaceControlBrightness || mBacklightAdapter.mForceSurfaceControl)) {
     newHdrSdrRatio = Math.max(1f, displayNits / sdrNits);
 } else {
     newHdrSdrRatio = Float.NaN;
 }
```

被删的闸门是 **`LocalDisplayAdapter.mSupportUltraHDR`**（小米私有字段）。
它不置位时，比值被强制写成 `NaN` → `DisplayInfo.hdrSdrRatio` 恒 NaN → 应用看不到 HDR。

---

## 2. 完整提亮链路（这台设备）

```
应用播放 HDR
  └─ SurfaceFlinger 合成出 HDR 图层
       └─ SurfaceControlHdrLayerInfoListener 回调
            mIsHdrLayerPresent = (HDR图层数 > 0)
                                 && (HDR面积 >= 屏幕面积 × minimumHdrPercentOfScreen=0)   ← 本机为 0，任意面积都算
                 └─ HighBrightnessModeController.calculateHighBrightnessMode()
                      if (mIsHdrLayerPresent) return 2;        // ★ HBM_ON_HDR
                           └─ mHbmChangeCallback → DPC 重算电源状态 → 面板进 HBM
                 └─ getHdrBrightnessValue()
                      = getHdrBrightnessFromSdr(mBrightness, mMaxDesiredHdrSdrRatio=10)
                           └─ 查 sdrHdrRatioMap  ← ★ 配置改的就是这里
                 └─ handleHdrSdrNitsChanged(nits, sdrNits)      ← ★ jar 删门禁的就是这里
                      = max(1, nits/sdrNits) → mCurrentHdrSdrRatio → DisplayInfo.hdrSdrRatio
```

所以这个模块做的是**两件独立的事**：

| | 改动 | 解决 | 表现 |
|---|---|---|---|
| **A** | 配置 `hdrRatio` 1.0→5.0 | HDR 目标亮度没有 headroom | 画面**更亮** |
| **B** | jar 删 `mSupportUltraHDR` | 比值被强制 NaN | 应用**看得到** HDR 比值 |

---

## 3. FkHDR 覆盖度

| 设备模块的做法 | FkHDR 的等效实现 | 覆盖 |
|---|---|---|
| **B** 删 `mSupportUltraHDR` 门禁 | 反射直调 `handleHdrSdrNitsChanged(nits, sdrNits)`，**绕过整个 if 判断**（不只看那一个字段） | ✅ 等效且更彻底 |
| **A** 配置 spline 1.0→5.0 | **v1.0.1 新增**：HDR 期间置 `mIsHdrLayerPresent=true` + `mMaxDesiredHdrSdrRatio=factor` + 调 `updateHbmMode()` | ✅ 等效（运行时，不改文件不重打包） |
| `minimumHdrPercentOfScreen=0` | 自己的内容检测（窗口 colorMode / 媒体会话 / SF 图层元数据），不依赖该阈值 | ✅ |
| `screenBrightnessMap`（brightness→nits） | 本机原厂就有，`backlightToNits` 直接可用 | ✅ 无需处理 |
| 面板物理提亮 | 改写 `requestDisplayStateLocked` 的 `brightnessState` 到 1.0 | ✅ |

**结论：v1.0.1 起，FkHDR 覆盖了该模块的完整修复路径，且不依赖任何文件替换。**

### 相对优势

1. **不需要改 /product**：改分区文件在 OTA 后要重刷，且 erofs 只读需要 overlay
2. **不需要重打包 services.jar**：每次系统更新 jar 都变，重打包既危险又要重做
3. **门禁判断比删一个字段更彻底**：我们直接跳过整个条件表达式
4. **可开关、可回退**：`persist.sys.fkhdr.hbm 0` 即可关闭 HBM 强制

### 需要诚实说明的差异

- 配置法是**全局**生效（所有走 AOSP HBM 路径的地方）；FkHDR 是 **hook 生效**（只影响 system_server 里这条链）
- 配置法让 `DisplayDeviceConfig` 本身带上曲线，其他读该曲线的组件（如某些相机/相册 HDR 增强）也会受益；
  FkHDR 只覆盖显示亮度这条链
- HBM 有散热代价。FkHDR 只在 HDR 内容存在时强制 HBM，退出即恢复；`persist.sys.fkhdr.hbm 0` 可关

---

## 4. v1.0.1 新增的日志

```
FkHDR:HdrFix: init done [platform=aosp aosp[dev=1 bright=1] gate=1 hbm=1 ...]
FkHDR:HdrFix: HBM controller captured
FkHDR:HdrFix: HDR -> true(focus=true) [driver=null]
FkHDR:HdrFix: hbm hdrLayerPresent=true -> mode=2 (2=HBM_ON_HDR)
FkHDR:HdrFix: ratio direct nits=.. sdrNits=.. -> ratio=.. current=..
FkHDR:HdrFix: HDR -> false(focus=false)
FkHDR:HdrFix: hbm hdrLayerPresent=false -> mode=0
```

判读：`hbm ... mode=2` 表示面板已进 HDR-HBM；`mode=0` 表示已退出。

---

## 5. 本机替换记录（Redmi xaga / HyperOS 3 / Android 15）

**覆盖度结论：显示亮度这条链已完全覆盖**（推导，逐条对应见 §3），
其中「配置 spline」一项由 v1.0.1 的 `HighBrightnessModeController` 通路等效实现。

已执行（2026-10-25 18:13）：

```bash
# 1) 装 FkHDR v1.0.1
adb install -r FkHDR_v1.0.1_release.apk

# 2) 在 LSPosed 中启用 + 设置作用域（用 lspctl，不需要点 UI）
su -c '/data/adb/modules/zygisk_lsposed/lspctl module enable --user=0 io.github.benbaobaoshigemi.fkhdr'
su -c '/data/adb/modules/zygisk_lsposed/lspctl scope set --user=0 io.github.benbaobaoshigemi.fkhdr android'

# 3) 禁用旧的「改配置 + 反编译 jar」模块
su -c 'touch /data/adb/modules/hdr_sdr_ratio_fix/disable'
```

**为什么作用域只勾 `android` 就够**：本机 `SettingsProvider` 的进程就是
`package=com.android.providers.settings process=system`（跑在 system_server 里），
`lspctl scope add com.android.providers.settings` 会被 LSPosed 过滤掉（DB 里不落盘），
但 `android` 作用域已经覆盖它 —— `HookInit` 在 `android` 分支里就调用了 `SettingsProviderHook.init()`。

当前状态：

| 项 | 值 |
|---|---|
| FkHDR | v1.0.1 / versionCode 2，**enabled**，scope=`android` |
| hdr_sdr_ratio_fix | `disable` 文件已建，**下次启动生效** |
| 重启 | **未执行** —— 旧配置此刻仍挂载着（`/product/.../display_id_*.xml` 里仍是 `hdrRatio 5.0`） |

### 下次启动后应该看到

```
FkHDR:HookInit: hooking system_server
FkHDR:HdrFix: init done [platform=aosp aosp[dev=1 bright=1] gate=1 hbm=1 ...]
FkHDR:HdrFix: HBM controller captured
FkHDR:HdrFix: brightness hook installed on ...LocalDisplayAdapter$LocalDisplayDevice$1 (1 method)
```

播放 HDR 时：

```
FkHDR:HdrFix: HDR -> true(...)
FkHDR:HdrFix: aosp push applied brightness=1.0 sdr=<用户亮度> state=2
FkHDR:HdrFix: hbm hdrLayerPresent=true -> mode=2 (2=HBM_ON_HDR)
FkHDR:HdrFix: ratio direct nits=<面板nits> sdrNits=<SDR nits> -> ratio=<自动算出>
```

### 回滚

```bash
su -c 'rm /data/adb/modules/hdr_sdr_ratio_fix/disable'      # 恢复旧模块
su -c '/data/adb/modules/zygisk_lsposed/lspctl module disable --user=0 io.github.benbaobaoshigemi.fkhdr'
# 然后重启
```

