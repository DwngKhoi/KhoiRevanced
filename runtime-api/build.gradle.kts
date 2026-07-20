plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.khoirevanced.runtime.api"

    defaultConfig {
        minSdk = 27
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
