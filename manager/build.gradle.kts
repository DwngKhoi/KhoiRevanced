plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.khoirevanced.manager"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.khoirevanced.manager"
        minSdk = 27
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
