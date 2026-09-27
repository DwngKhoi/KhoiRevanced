import org.gradle.api.tasks.Copy
import java.util.Properties

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

    // The manager is the only installable product, so its release build has to
    // be signable. Reuse the same optional signing.properties contract as the
    // payload module. When the file is absent the release variant is simply
    // left unsigned (AGP emits *-unsigned.apk) rather than being signed with the
    // debug key, so a debug-signed build can never be mistaken for a release.
    val keystoreProperties = rootProject.file("signing.properties")
    val hasReleaseKeystore = keystoreProperties.exists()
    if (hasReleaseKeystore) {
        signingConfigs {
            create("release") {
                val properties = Properties().apply {
                    keystoreProperties.inputStream().use { load(it) }
                }
                storePassword = properties["KEYSTORE_PASSWORD"] as String
                keyAlias = properties["KEYSTORE_ALIAS"] as String
                keyPassword = properties["KEYSTORE_ALIAS_PASSWORD"] as String
                storeFile = file(properties["KEYSTORE_FILE"] as String)
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    sourceSets.getByName("main").assets.srcDir(
        layout.buildDirectory.dir("generated/khoirevancedRuntimeAssets").get().asFile
    )
}

val runtimeBundle = rootProject.layout.projectDirectory.file("dist/KhoiRevanced.sh")
val generatedRuntimeAssets = layout.buildDirectory.dir("generated/khoirevancedRuntimeAssets")

/**
 * Fails loudly when the self-extracting runtime has not been generated.
 *
 * `Copy.from(<missing file>)` is a silent no-op, which previously produced a
 * manager APK whose only job - extracting and running the payload - could never
 * work. The manager has no value without this asset, so treat it as a hard
 * prerequisite and point at the script that creates it.
 */
val verifyRuntimeBundle by tasks.registering {
    group = "khoirevanced"
    description = "Fails when dist/KhoiRevanced.sh has not been generated yet."
    val bundle = runtimeBundle.asFile
    val generator = rootProject.layout.projectDirectory.file("tools/package-runtime.ps1").asFile
    doLast {
        if (!bundle.isFile) {
            throw GradleException(
                "Missing runtime bundle: ${bundle.absolutePath}\n" +
                    "Generate it first: pwsh -File tools\\package-runtime.ps1 -Configuration Release\n" +
                    "or build the manager end to end: pwsh -File tools\\build-manager.ps1 -Configuration Release"
            )
        }
        if (bundle.length() < 1024) {
            throw GradleException(
                "Runtime bundle ${bundle.absolutePath} is only ${bundle.length()} bytes; " +
                    "packaging most likely failed. Re-run ${generator.name}."
            )
        }
    }
}

val syncRuntimeAsset by tasks.registering(Copy::class) {
    group = "khoirevanced"
    description = "Embeds the self-extracting runtime inside the manager APK."
    dependsOn(verifyRuntimeBundle)
    from(runtimeBundle)
    into(generatedRuntimeAssets)
}

tasks.named("preBuild").configure { dependsOn(syncRuntimeAsset) }
