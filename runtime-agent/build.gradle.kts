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
    // payload, plus Pine's own API, which is the hook engine this runtime uses.
    compileOnly(files("../third_party/pine-libs/pine-core.jar"))
    compileOnly(files("../third_party/pine-libs/pine-xposed.jar"))
    // StandaloneXposedInterface implements the modern libxposed service
    // interface, and NexAlloyCompatibilityModule attaches it to the payload's
    // XposedModule. The classes reach the agent's classes.dex through
    // tools/package-runtime.ps1, which dexes this AAR's classes.jar, so the
    // module needs it on the compile classpath to implement against it.
    compileOnly(libs.libxposed.api)
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
