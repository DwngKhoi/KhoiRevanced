package dev.khoirevanced.runtime.agent

import android.app.Application
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import dalvik.system.DexClassLoader
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * Runs the upstream NexAlloy patch code in the injected process through
 * Pine's Xposed compatibility layer. The embedded dexpack is never installed
 * as an Android app or module; it is a read-only payload extracted by the
 * root controller next to the native hook libraries.
 */
object NexAlloyCompatibilityModule {
    private const val TAG = "KhoiRevanced"
    private const val LOAD_STATE_PROPERTY = "khoirevanced.nexalloy.load-state"

    @Volatile
    var status: String = "not-requested"
        private set

    fun load(application: Application, config: RuntimeConfig) {
        val modulePath = config.modulePath ?: return
        // System properties and their monitor are shared across class loaders.
        // This prevents a second inject/watch request from installing another
        // complete set of Pine callbacks into the same live app process.
        synchronized(System.getProperties()) {
            when (System.getProperty(LOAD_STATE_PROPERTY)) {
                "loading", "loaded" -> {
                    status = "nexalloy-loaded"
                    return
                }
            }
            System.setProperty(LOAD_STATE_PROPERTY, "loading")
        }
        runCatching {
            val module = File(modulePath)
            require(module.isFile) { "NexAlloy dexpack is missing: $module" }
            val nativeDir = File(config.agentPath).parentFile?.absolutePath
                ?: error("Invalid agent path")
            // Android 16 can expose a root-managed APK to DexClassLoader while
            // still skipping secondary dex entries. Extract every classes*.dex
            // explicitly and pass the raw dex list to the loader. The original
            // APK is retained as modulePath for ResourcesProvider/addModuleAssets.
            val dexRoot = File(config.cacheDir, "nexalloy-dex")
            val inputDir = dexRoot.resolve("input")
            val optimizedDir = dexRoot.resolve("optimized")
            require(inputDir.mkdirs() || inputDir.isDirectory) {
                "Could not create dex input directory: $inputDir"
            }
            require(optimizedDir.mkdirs() || optimizedDir.isDirectory) {
                "Could not create dex optimized directory: $optimizedDir"
            }
            val dexFiles = extractDexFiles(module, inputDir)
            require(dexFiles.isNotEmpty()) {
                "NexAlloy payload has no classes*.dex entries: $module"
            }
            val dexPath = dexFiles.joinToString(File.pathSeparator) { it.absolutePath }
            Log.i(TAG, "Loading NexAlloy payload: dexCount=${dexFiles.size} dexPath=$dexPath")
            val loader = DexClassLoader(
                dexPath,
                optimizedDir.absolutePath,
                nativeDir,
                javaClass.classLoader,
            )
            val entry = loader.loadClass("io.github.nexalloy.MainHook")
                .getDeclaredConstructor().newInstance()
            val zygoteHook = entry as IXposedHookZygoteInit
            zygoteHook.initZygote(IXposedHookZygoteInit.StartupParam().apply {
                this.modulePath = module.absolutePath
                startsSystemServer = false
            })
            val callbackSet = XposedBridge.CopyOnWriteSortedSet<XC_LoadPackage>()
            val loadParam = XC_LoadPackage.LoadPackageParam(callbackSet).apply {
                packageName = application.packageName
                processName = application.packageName
                appInfo = application.applicationInfo
                isFirstApplication = true
                classLoader = application.classLoader
            }
            System.setProperty("khoirevanced.direct-runtime", "true")
            (entry as IXposedHookLoadPackage).handleLoadPackage(loadParam)
            check(System.getProperty("khoirevanced.nexalloy.state") == "patches-applied") {
                "NexAlloy callback did not complete its patch executor"
            }
        }.onSuccess {
            System.setProperty(LOAD_STATE_PROPERTY, "loaded")
            status = "nexalloy-loaded"
            Log.i(TAG, "NexAlloy compatibility patch set loaded")
        }.onFailure { error ->
            System.clearProperty(LOAD_STATE_PROPERTY)
            val causeChain = generateSequence(error) { it.cause }
                .joinToString(" <- ") {
                    "${it.javaClass.simpleName}:${it.message ?: "no-message"}"
                }
            status = "nexalloy-failed:$causeChain"
            Log.e(TAG, "NexAlloy compatibility patch set failed", error)
        }
    }

    private fun extractDexFiles(module: File, inputDir: File): List<File> {
        val dexPattern = Regex("""classes(\d*)\.dex""")
        return ZipFile(module).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && dexPattern.matches(it.name) }
                .sortedWith(compareBy {
                    dexPattern.matchEntire(it.name)?.groupValues?.get(1)
                        ?.takeIf(String::isNotEmpty)?.toInt() ?: 1
                })
                .map { entry ->
                    val destination = inputDir.resolve(entry.name)
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(destination, false).use { output -> input.copyTo(output) }
                    }
                    destination
                }
                .toList()
        }
    }
}
