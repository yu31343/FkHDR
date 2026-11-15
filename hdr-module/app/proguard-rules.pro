# FkHDR 不使用混淆（isMinifyEnabled = false），此文件仅为 AGP 配置占位。
-keep class io.github.fkhdr.** { *; }
# libxposed API 102：入口类由框架按 META-INF/xposed/java_init.list 反射实例化，
# 一旦开启混淆必须保证类名与无参构造器不被改名。
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule { public <init>(); }
