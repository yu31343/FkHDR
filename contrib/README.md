# 向上游提交（PR）用的补丁

本目录存放可以直接提交给 [FkOScreen](https://github.com/benbaobaoshigemi/FkOScreen) 上游的补丁。

## 文件

`0001-feat-hdr-SDR-HDR-1.5625.patch` —— 单个提交，基于上游 HEAD `d1a0e65`

```
 app/src/main/java/io/github/fkoscreen/ConfigManager.kt       |  10 +-
 app/src/main/java/io/github/fkoscreen/HookInit.kt            |   5 +-
 app/src/main/java/io/github/fkoscreen/hooks/HdrBoostHook.kt  | 305 ++++++++++++
 app/src/main/java/io/github/fkoscreen/hooks/HdrRatioHook.kt  |  51 ----
 app/src/main/java/io/github/fkoscreen/ui/MainActivity.kt     |   4 +-
 5 files changed, 319 insertions(+), 56 deletions(-)
```

**只包含源码层改动**，不含本地构建环境改动（AGP 9.4.1 / Gradle 9.8 / JDK 21）。
上游仍是 AGP 8.3.2 / Gradle 8.6 / Kotlin 2.3.20，源码本身兼容。
已用 `git apply --check` 验证可干净应用到上游 HEAD。

## 提交方式

```bash
git clone https://github.com/benbaobaoshigemi/FkOScreen.git
cd FkOScreen
git checkout -b feat/hdr-boost
git am /path/to/0001-feat-hdr-SDR-HDR-1.5625.patch
git push origin feat/hdr-boost
# 然后在 GitHub 上开 PR
```

## 上游可能的分歧点

1. **删除 `HdrRatioHook`**：上游 README 把「锁定 hdrSdrRatio 为 1.56」当作卖点。
   若维护者想保留，可改为「保留文件但默认不挂载」，由 `HdrBoostHook` 取代其功能。
2. **依赖 ColorOS 私有类**：`OplusFeatureHDREnhanceBrightness` /
   `OplusBrightnessPostProcessingManager` 都是 OPPO 私有实现，但全部包在 try/catch 中，
   并有降级分支，非 ColorOS 设备不会崩。
3. **设备覆盖面**：目前只在 OPD2404（一加平板 Pro / ColorOS 16）实测，
   其它机型建议维护者用 `dumpsys display` 里的 `hdrSdrRatio` 复核。
