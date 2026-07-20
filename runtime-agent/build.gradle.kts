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
    // Pine provides the in-process ART hook engine and its Xposed-compatible
    // Java API.  It is bundled into the standalone DEX by package-runtime.ps1.
    // Use the Java jars instead of direct AAR dependencies: runtime-agent is
    // itself packaged as an AAR, while the final standalone DEX is assembled
    // explicitly by package-runtime.ps1.
    compileOnly(files("../third_party/pine-libs/pine-core.jar"))
    compileOnly(files("../third_party/pine-libs/pine-xposed.jar"))
    testImplementation("junit:junit:4.13.2")
}
