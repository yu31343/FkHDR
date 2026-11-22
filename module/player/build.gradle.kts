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

    // 仓库不带签名文件；本地放了 keystore/fkoscreen-debug.jks 就用它，否则退回 AGP 默认 debug 签名。
    val localKeystore = rootProject.file("keystore/fkoscreen-debug.jks")

    signingConfigs {
        if (localKeystore.exists()) {
            create("localDebug") {
                storeFile = localKeystore
                storePassword = "android"
                keyAlias = "fkoscreen"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        val signer =
            if (localKeystore.exists()) signingConfigs.getByName("localDebug")
            else signingConfigs.getByName("debug")
        debug {
            isMinifyEnabled = false
            signingConfig = signer
        }
        release {
            isMinifyEnabled = false
            signingConfig = signer
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
