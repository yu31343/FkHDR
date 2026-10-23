plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.fkoscreen.player"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.fkoscreen.player"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        // 复用主工程 keystore，保证 adb install -r 不会因签名变化失败
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
