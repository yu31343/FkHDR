# 备选方案（未采用）

## ksu-config-module —— KernelSU 文件替换方案

思路：用 KernelSU 模块覆盖三份配置，完全不依赖 LSPosed：

1. `/my_product/etc/permissions/oplus.product.display_features.xml` —— 补 3 个 OPPO feature
2. `/my_product/vendor/etc/display_brightness_app_list.xml` —— 补 `<hdr_enhance_method>` 白名单表
3. `/vendor/etc/displayconfig/display_id_<physical-display-id>.xml` —— 内屏缺失的亮度/比率配置

未采用的原因：

- 本机 `/my_product` 是独立 erofs 分区，metamodule 为 hybrid_mount，需要在 `config.toml` 里
  额外配置挂载规则，通用性差。
- 更关键：本机面板没有 HDR color mode，应用 `setColorMode(COLOR_MODE_HDR)` 不会让
  SurfaceFlinger 进入 HDR 合成，ColorOS 的 `checkIsPlayingHDR()` 恒 0 —— 这个「HDR 开关信号」
  任何文件都补不出来，只能由模块桥接。

保留此目录仅作参考记录。
