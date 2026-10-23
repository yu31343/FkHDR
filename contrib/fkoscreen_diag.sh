#!/system/bin/sh
# FkOScreen HDR 修复 —— 一键诊断信息收集
# 用法（Termux 里执行 tsu 后）：sh /sdcard/fkoscreen_diag.sh
# 产物：/sdcard/fkoscreen_diag.zip

OUT=/sdcard/fkoscreen_diag.zip
WORK=/data/local/tmp/fkodiag
PKG=io.github.benbaobaoshigemi.fkoscreen

rm -rf "$WORK"; mkdir -p "$WORK"; cd "$WORK" || exit 1

{
  echo "===== 系统版本 ====="
  getprop ro.build.version.release
  getprop ro.build.version.sdk
  getprop ro.build.version.oplusrom
  getprop ro.build.display.id
  getprop ro.product.model
  getprop ro.product.device
  getprop ro.boot.prjname
  echo "===== 模块属性 ====="
  getprop persist.sys.fkoscreen.hdr_fix
  getprop persist.sys.fkoscreen.hdr_ratio
  getprop persist.sys.fkoscreen.hdr_factor
  echo "===== 模块版本 ====="
  dumpsys package $PKG | grep -E "versionName|versionCode|lastUpdateTime"
  echo "===== OPPO feature 状态 ====="
  dumpsys display | grep -iE "mSupportEdr|EnhanceHDR|mEnhanceHDR"
  echo "===== 亮度/比率 ====="
  dumpsys display | grep -E "^  mScreenBrightness=|^  mSdrScreenBrightness="
  dumpsys display | grep -oE "hdrSdrRatio [a-zA-Z0-9_.]+" | sort | uniq -c
  echo "===== 背光 ====="
  cat /sys/class/backlight/panel0-backlight/brightness 2>/dev/null
  cat /sys/class/backlight/panel0-backlight/max_brightness 2>/dev/null
  echo "===== 色彩模式 / HDR 能力 ====="
  dumpsys SurfaceFlinger | grep -E "Current color mode|colorMode=" | head -3
  dumpsys display | grep -oE "supportedColorModes \[[^]]*\]" | head -1
  dumpsys display | grep -oE "hdrCapabilities[^,]*" | head -1
  echo "===== 亮度上限 ====="
  dumpsys display | grep -E "mScreenBrightnessRange|mScreenBrightnessNormalMaximum|mBrightnessNitsForDefaultDisplay" | head -5
} > sysinfo.txt 2>&1

ls -t /data/adb/lspd/log/modules_*.log 2>/dev/null | head -1 | while read f; do
  grep -i "FkOScreen" "$f" > fkoscreen-log.txt
done

logcat -d 2>/dev/null | grep -iE "FkOScreen|HdrBoost|HdrFix" | tail -200 > logcat-fk.txt
logcat -d 2>/dev/null | grep -iE "FATAL EXCEPTION" | tail -20 > logcat-crash.txt

cat /data/user_de/0/$PKG/shared_prefs/*.xml > prefs.xml 2>/dev/null
cat /data/data/$PKG/shared_prefs/*.xml >> prefs.xml 2>/dev/null
cp /data/adb/lspd/config/modules_config.db . 2>/dev/null

ls -la /vendor/etc/displayconfig/ > displayconfig.txt 2>&1
echo "hdr_enhance_method count: $(grep -c hdr_enhance_method /my_product/vendor/etc/display_brightness_app_list.xml 2>/dev/null)" >> displayconfig.txt

# 打包：优先 tar（ColorOS 自带），失败则用 zip
rm -f "$OUT"
cd /data/local/tmp
if tar -cf /data/local/tmp/fkodiag.tar fkodiag 2>/dev/null; then
  cp /data/local/tmp/fkodiag.tar "$OUT"
  rm -f /data/local/tmp/fkodiag.tar
else
  zip -qr "$OUT" fkodiag || cp -r fkodiag "$OUT"
fi
rm -rf "$WORK"

if [ -d "$OUT" ]; then
  echo "完成（目录）：$OUT"
elif [ -f "$OUT" ]; then
  echo "完成：$OUT"
  ls -la "$OUT"
else
  echo "失败，请手动发送 /data/local/tmp/fkodiag 目录内容"
fi
