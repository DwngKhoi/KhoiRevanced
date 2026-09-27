import com.google.protobuf.gradle.proto
import groovy.xml.MarkupBuilder
import groovy.xml.XmlSlurper
import groovy.xml.slurpersupport.NodeChild
import org.gradle.kotlin.dsl.support.uppercaseFirstChar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.protobuf)
}

val gitCommitHashProvider = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
    workingDir = rootProject.rootDir
}.standardOutput.asText!!

val gitCommitDateProvider = providers.exec {
    commandLine("git log -1 --format=%ct".split(" "))
    workingDir = rootProject.rootDir
}.standardOutput.asText!!

android {
    namespace = "io.github.nexalloy"
    // Pin the NDK to the version the injector, the LSPlant/Dobby agent and
    // tools/package-runtime.ps1 are all built against. Without this, AGP falls
    // back to its own default NDK and warns that it disagrees with ndk.dir.
    ndkVersion = "25.2.9519653"

    defaultConfig {
        applicationId = "io.github.chsbuffer.revancedxposed"
        versionCode = 107
        versionName = "2.0.$versionCode"
        val patchVersion = Properties().apply {
            rootProject.file("morphe-patches/gradle.properties").inputStream().use { load(it) }
        }["version"]
        buildConfigField("String", "PATCH_VERSION", "\"$patchVersion\"")
        buildConfigField("String", "COMMIT_HASH", "\"${gitCommitHashProvider.get().trim()}\"")
        buildConfigField("long", "COMMIT_DATE", "${gitCommitDateProvider.get().trim()}L")
    }
    androidResources {
        additionalParameters += arrayOf("--allow-reserved-package-id", "--package-id", "0x4b")
    }
    packaging.resources {
        excludes.addAll(
            arrayOf(
                "META-INF/**", "**.bin"
            )
        )
    }
    val ksFile = rootProject.file("signing.properties")
    signingConfigs {
        if (ksFile.exists()) {
            create("release") {
                val properties = Properties().apply {
                    ksFile.inputStream().use { load(it) }
                }

                storePassword = properties["KEYSTORE_PASSWORD"] as String
                keyAlias = properties["KEYSTORE_ALIAS"] as String
                keyPassword = properties["KEYSTORE_ALIAS_PASSWORD"] as String
                storeFile = file(properties["KEYSTORE_FILE"] as String)
            }
        }
    }
    buildFeatures.buildConfig = true
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
            if (ksFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
//            excludes += "**"
        }
    }

    lint {
        checkReleaseBuilds = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets {
        named("main") {
            val srcDirs = arrayOf(
                "../morphe-patches/extensions/shared/library/src/main/java",
                "../morphe-patches/extensions/shared-youtube/library/src/main/java",
                "../morphe-patches/extensions/youtube/src/main/java",
                "../morphe-patches/extensions/music/src/main/java",
                "../morphe-patches/extensions/reddit/src/main/java",
                "../morphe-patches-library/extension-library/src/main/java"
            )
            java.directories += srcDirs
            kotlin.directories += srcDirs

            proto {
                srcDirs(
                    "../morphe-patches/extensions/youtube/src/main/proto",
                    "../morphe-patches/extensions/shared-youtube/library/src/main/proto",
                )
            }
        }
    }
}

// Exclude Morphe-specific files that depend on protobuf/innertube/javascriptengine
// which are not available in the Xposed module build context.
tasks.withType<JavaCompile>().configureEach {
    exclude(
        "**/patches/HideRelatedVideosPatch.java",
        "**/patches/playback/quality/PrioritizeVideoQualityPatch.java",
        "**/OAuth2Preference.java",
        "**/SpoofVideoStreamsSignInPreference.java",
        "**/SpoofSignaturePatch.java",
    )
}
kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xno-param-assertions",
            "-Xno-receiver-assertions",
            "-Xno-call-assertions",
            "-Xcontext-parameters"
        )
        jvmTarget = JvmTarget.JVM_17
    }
}
tasks.withType<Test> {
    useJUnitPlatform()
}

dependencies {
//    implementation(libs.dexkit)

    // DexKit fork with instruction operand introspection
    // https://github.com/NexAlloy/DexKit/commit/046c0484b37e6a2100dd7bcc16748132c45dd2d9
    implementation(":dexkit-android@aar")
    implementation("com.google.flatbuffers:flatbuffers-java:23.5.26") // dexkit dependency
    implementation(libs.annotation)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.fuel)
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.jadx.core)
    testImplementation(libs.slf4j.simple)
    debugImplementation(kotlin("reflect"))
    // The standalone runtime ships Pine's Xposed-compat API. Compile the
    // payload against the exact same API jar instead of resolving
    // de.robv.android.xposed:api from the retired remote repository.
    //
    // Note that the official artifact is a stub whose every method body is
    // `throw new RuntimeException("Stub!")`; LSPosed injects the real
    // implementation at runtime. That is precisely why the payload must not
    // depend on it for behaviour the runtime has to own itself.
    compileOnly(files("../third_party/pine-libs/pine-xposed.jar"))
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
//    implementation(project(":extensions"))
    compileOnly(project(":stub"))
    // KhoiRevanced's own runtime contract. compileOnly because these classes
    // are dexed into the agent's classes.dex, which is the parent class loader
    // the payload's DexClassLoader resolves them against; shipping them inside
    // the dexpack as well would duplicate the types.
    compileOnly(project(":runtime-api"))
    implementation(libs.androidx.javascriptengine)
    implementation(libs.protobuf.javalite)
    implementation(libs.collections4)
    implementation(libs.lang3)
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}

abstract class GenerateStringsTask @Inject constructor(
) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    private fun writeNode(builder: MarkupBuilder, node: Any?) {
        if (node !is NodeChild) return
        val attributes = node.attributes()
        builder.withGroovyBuilder {
            if (node.children().any()) {
                node.name()(attributes) {
                    node.children().forEach {
                        writeNode(builder, it)
                    }
                }
            } else {
                node.name()(attributes, node.text())
            }
        }
    }

    /**
     * Morphe addresources structure:
     *   values/youtube/strings.xml, values/shared/strings.xml, etc.
     * Each XML is flat: <resources> <string name="...">...</string> ... </resources>
     *
     * Merge all subdirectory XMLs into a single output file per variant.
     */
    private fun mergeResources(inputFiles: List<File>, output: File) {
        output.parentFile.mkdirs()
        output.writer().use { writer ->
            val builder = MarkupBuilder(writer)
            builder.doubleQuotes = true
            builder.withGroovyBuilder {
                val keys = mutableSetOf<String>()
                "resources" {
                    for (inputFile in inputFiles) {
                        if (!inputFile.exists()) continue
                        // Preserve the upstream Morphe section and its
                        // copyright notices. Only the entry-point title is
                        // branded for KhoiRevanced.
                        val brandedXml = inputFile.readText()
                            .replace(
                                Regex("(<string[^>]*name=\\\"morphe_settings_title\\\"[^>]*>).*?(</string>)"),
                                "$1KhoiRevanced$2"
                            )
                        val inputXml = XmlSlurper().parseText(brandedXml)
                        // Flat structure: direct children of <resources>
                        inputXml.children().forEach {
                            if (it !is NodeChild) return@forEach
                            val key = it.attributes()["name"] as? String ?: return@forEach
                            if (keys.contains(key)) return@forEach
                            writeNode(builder, it)
                            keys.add(key)
                        }
                    }
                }
            }
        }
    }

    // Subdirectories within each variant that contain resource files.
    private val subDirs = listOf("shared", "shared-youtube", "youtube", "music", "reddit", "sponsorblock")

    @TaskAction
    fun action() {
        val inputDir = inputDirectory.get().asFile
        val outputDir = outputDirectory.get().asFile

        runCatching {
            // Process each variant directory (values, values-xx-rYY, ...)
            inputDir.listFiles()?.filter { it.isDirectory }?.forEach { variant ->
                val genResDir = File(outputDir, variant.name).apply { mkdirs() }

                // Merge strings.xml from all subdirectories
                val stringFiles = subDirs.map { File(variant, "$it/strings.xml") }
                mergeResources(stringFiles, File(genResDir, "strings.xml"))

                // Merge arrays.xml from all subdirectories
                val arrayFiles = subDirs.map { File(variant, "$it/arrays.xml") }
                if (arrayFiles.any { it.exists() }) {
                    mergeResources(arrayFiles, File(genResDir, "arrays.xml"))
                }
            }
        }.onFailure {
            System.err.println(it)
            throw it
        }
    }
}

abstract class CopyResourcesTask @Inject constructor() : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun action() {
        val baseDir = inputDirectory.get().asFile
        val outputDir = outputDirectory.get().asFile
        outputDir.deleteRecursively()

        val resourcePaths = mapOf(
            "qualitybutton/drawable" to null,
            "settings/drawable" to null,
            "settings/menu" to null,
            "settings/layout" to listOf("morphe_settings_with_toolbar.xml"),
            "sponsorblock/drawable" to null,
            "sponsorblock/layout" to listOf("morphe_sb_skip_sponsor_button.xml"),
            "swipecontrols/drawable" to null,
            "copyvideolinkbutton/drawable" to null,
            "downloads/drawable" to null,
            "speedbutton/drawable" to null,
            "navigationbuttons/drawable" to null,
            "speed/drawable" to null,
        )

        for ((resourcePath, excludes) in resourcePaths) {
            val dir = resourcePath.substringAfter('/')
            val sourceDir = File(baseDir, resourcePath)
            val targetDir = File(outputDir, dir)
            sourceDir.listFiles()?.forEach { file ->
                if (excludes == null || !excludes.contains(file.name)) {
                    file.copyTo(File(targetDir, file.name), overwrite = true)
                }
            }
        }
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.packaging.resources.excludes.add("kotlin/**")
    }

    onVariants { variant ->
        val variantName = variant.name.uppercaseFirstChar()
        val strTask = project.tasks.register<GenerateStringsTask>("generateStrings$variantName") {
            inputDirectory.set(project.file("../morphe-patches/patches/src/main/resources/addresources"))
        }
        variant.sources.res?.addGeneratedSourceDirectory(
            strTask, GenerateStringsTask::outputDirectory
        )

        val resTask = project.tasks.register<CopyResourcesTask>("copyResources$variantName") {
            inputDirectory.set(project.file("../morphe-patches/patches/src/main/resources"))
        }
        variant.sources.res?.addGeneratedSourceDirectory(
            resTask, CopyResourcesTask::outputDirectory
        )
    }
}

/**
 * The fingerprint unit tests fingerprint real host apps, so they need actual
 * YouTube / YouTube Music / Reddit APKs in `app/binaries/`. Those are
 * proprietary and gitignored, so a fresh clone has none.
 *
 * `FilePathArgumentsProvider` throws when the folder is missing and
 * `@ParameterizedClass` then fails with a `TemplateInvocationValidationException`
 * before any condition can disable it, which made `./gradlew build` fail for
 * every contributor without the fixtures. Skip the test tasks instead, so the
 * build is green by default and the tests still run as soon as APKs are present.
 */
val apkFixtureDir = layout.projectDirectory.dir("binaries")

tasks.withType<Test>().configureEach {
    val fixturesDir = apkFixtureDir
    val taskPath = path
    onlyIf {
        val fixtures = fixturesDir.asFile
        // `app/binaries/` ships a .gitignore, so require real APK files rather
        // than just a non-empty directory.
        val present = fixtures.listFiles()?.any { it.isFile && it.extension == "apk" } == true
        if (!present) {
            logger.lifecycle(
                "Skipping $taskPath: no APK fixtures in ${fixtures.absolutePath}. " +
                    "See DEVELOPMENT.md for the expected file names."
            )
        }
        present
    }
}
