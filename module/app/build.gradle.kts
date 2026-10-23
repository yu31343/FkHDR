import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.fkoscreen"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.benbaobaoshigemi.fkoscreen"
        minSdk = 26
        targetSdk = 34
        versionCode = 12
        versionName = "1.6.1"
    }

    signingConfigs {
        // 项目内自带 keystore，保证 debug/release 签名一致，重复 install -r 不会因签名变化失败
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

    buildFeatures {
        compose = true
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
    compileOnly("de.robv.android.xposed:api:82")

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Compose (版本与本地 Gradle 缓存对齐，避免依赖联网)
    implementation("androidx.compose.ui:ui:1.12.0")
    implementation("androidx.compose.ui:ui-graphics:1.12.0")
    implementation("androidx.compose.ui:ui-tooling-preview:1.12.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    // Miuix (Compose Multiplatform)
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.4")
}