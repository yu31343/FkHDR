plugins {
    id("com.android.application") version "9.4.1" apply false
    // 显式 Kotlin 2.4.20：与 module/ 一致，本地 Gradle 缓存已有，可离线构建
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
}
