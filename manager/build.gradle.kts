plugins { id("com.android.application") }

android {
    namespace = "com.dwngkhoi.khoirevanced"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dwngkhoi.khoirevanced"
        minSdk = 27
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
    }

    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    externalNativeBuild { cmake { path = file("../agent/src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
}