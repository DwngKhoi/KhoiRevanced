import org.gradle.api.tasks.Copy

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.dwngkhoi.revanced"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dwngkhoi.revanced"
        minSdk = 27
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    sourceSets.getByName("main").assets.srcDir(
        layout.buildDirectory.dir("generated/khoirevancedRuntimeAssets").get().asFile
    )
}

val runtimeBundle = rootProject.layout.projectDirectory.file("dist/KhoiRevanced.sh")
val generatedRuntimeAssets = layout.buildDirectory.dir("generated/khoirevancedRuntimeAssets")
val syncRuntimeAsset by tasks.registering(Copy::class) {
    group = "khoirevanced"
    description = "Embeds the self-extracting runtime inside the manager APK."
    from(runtimeBundle)
    into(generatedRuntimeAssets)
}

tasks.named("preBuild").configure { dependsOn(syncRuntimeAsset) }
