import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // 只应用 AGP：AGP 9 自带内置 Kotlin 编译（newDsl 默认开启，不能再 apply kotlin-android，
    // 否则报 "not compatible with AGP's 9.0 new DSL"）。KGP 版本由根 build.gradle.kts 的
    // apply false 钉在 2.4.20（离线缓存已有），本模块不引入任何 androidx / Compose 依赖。
    id("com.android.application")
}

android {
    namespace = "io.github.fkhdr"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.benbaobaoshigemi.fkhdr"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "1.0.4"
    }

    signingConfigs {
        // 与 module/ 共用同一 keystore，保证 debug/release 签名一致，install -r 不会因签名变化失败
        create("localDebug") {
            storeFile = rootProject.file("keystore/fkoscreen-debug.jks")
            storePassword = "android"
            keyAlias = "fkoscreen"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("localDebug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("localDebug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 唯一依赖：LSPosed / Xposed API（compileOnly，运行时由框架提供）
    compileOnly("de.robv.android.xposed:api:82")
}
