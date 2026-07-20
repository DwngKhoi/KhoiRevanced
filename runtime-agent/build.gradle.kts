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
    testImplementation("junit:junit:4.13.2")
}
