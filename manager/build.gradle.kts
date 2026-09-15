plugins { id("com.android.application") }

android {
    namespace = "com.dwngkhoi.khoirevanced"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dwngkhoi.khoirevanced"
        minSdk = 27
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}