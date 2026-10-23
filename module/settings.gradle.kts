pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = java.net.URI("https://api.xposed.info/")
        }
    }
}

rootProject.name = "FkOScreen"
include(":app")

// 独立的 HDR 验证播放器（不参与模块功能，仅用于实验取证）
include(":player")
