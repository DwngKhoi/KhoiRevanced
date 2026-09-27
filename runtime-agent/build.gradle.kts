plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.khoirevanced.runtime.agent"
    ndkVersion = "25.2.9519653"

    defaultConfig {
        minSdk = 27
        externalNativeBuild.cmake {
            arguments += "-DANDROID_STL=c++_static"
            cppFlags += listOf("-std=c++20", "-Wall", "-Wextra", "-Werror=return-type")
        }
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    externalNativeBuild.cmake {
        path = file("src/main/cpp/CMakeLists.txt")
        version = "3.22.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":runtime-api"))
    // These JARs provide the legacy Xposed *Java API* required by the NexAlloy
    // payload. They are not an ART hook engine: libpine.so is not packaged or
    // loaded, and XposedBridge is configured to use LSPlant at bootstrap.
    compileOnly(files("../third_party/pine-libs/pine-core.jar"))
    compileOnly(files("../third_party/pine-libs/pine-xposed.jar"))
    testImplementation("junit:junit:4.13.2")
}

// NOTE on `io.github.libxposed:api`
//
// `io.github.nexalloy.MainHook` extends `io.github.libxposed.api.XposedModule`.
// The API is only `compileOnly` for :nexalloy-payload, so it is absent from the
// dexpack, and the standalone runtime has no LSPosed framework to supply it.
// `tools/package-runtime.ps1` therefore resolves the AAR from the Gradle module
// cache and dexes its classes.jar into the agent's classes.dex, which is the
// parent class loader the payload resolves MainHook against.
//
// The API is classes-only at runtime on the standalone path: MainHook never
// calls into the framework (it reuses the already-created Application instead
// of onPackageReady's LSPosed branch), and both XposedModule and
// XposedInterfaceWrapper expose public no-argument constructors.
