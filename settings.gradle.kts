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
        // Only :nexalloy-payload uses the legacy compile-time Xposed API while
        // converting upstream patches into the embedded dexpack. The product
        // APK/runtime never resolves or installs this API.
        maven(url = "https://api.xposed.info")
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        flatDir {
            dirs("libs")
        }
        google()
        mavenCentral()
    }
}

plugins {
    id("com.android.settings") version ("9.2.1")
}

android {
    compileSdk = 37
    minSdk = 27
}

rootProject.name = "KhoiRevanced"
// The NexAlloy-derived code is a build-time payload producer only. It is not
// the product APK and is never installed as an LSPosed module.
include(":nexalloy-payload")
project(":nexalloy-payload").projectDir = file("app")
include(":stub")
include(":runtime-api")
include(":runtime-agent")
include(":manager")
