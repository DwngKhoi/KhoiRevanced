package dev.khoirevanced.runtime.agent

import android.app.Application
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XposedBridge
import io.github.libxposed.api.XposedInterfaceWrapper
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

    /**
     * Names of the patches that were enabled but whose fingerprints did not
     * match this host build.
     *
     * A non-empty list is expected on a host version newer than the one the
     * patch set was written against, and is exactly the situation in which the
     * LSPosed build also shows a warning toast while every other patch keeps
     * working. It is reported through the status file rather than treated as a
     * bootstrap failure, so `inject.sh` still reports ready and the user can
     * see precisely which features are unavailable.
     */
    @Volatile
    var failedPatches: List<String> = emptyList()
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
            // XposedModule extends XposedInterfaceWrapper, and that wrapper throws
            // "Framework not attached" from every delegated call until
            // attachFramework runs. NexAlloy reaches XposedInterface from
            // getInvoker in SettingsPatch, to make a super call.
            (entry as XposedInterfaceWrapper).attachFramework(StandaloneXposedInterface) { }
            RuntimeLog.stage("xposed-service-attached")
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

            // Only a missing state means the callback never reached the patch
            // executor, which is a genuine failure of the runtime.
            //
            // Individual patches that failed to fingerprint are NOT a failure
            // here. `runCatching` inside PatchExecutor has already hooked every
            // patch that did match, and upstream NexAlloy ignores the executor's
            // return value and merely toasts the failed names. Failing the whole
            // bootstrap on a partial result would discard working patches and
            // report "not ready" for a process that is in fact patched. So record
            // the failures for diagnostics and carry on, exactly as the Xposed
            // deployment does.
            val state = System.getProperty("khoirevanced.nexalloy.state")
            check(state == "patches-applied") {
                "NexAlloy callback did not complete its patch executor (state=$state)"
            }
            // Entries are "name: reason" pairs joined by " | ", so the separator is
            // a pipe rather than a comma: patch names and exception messages both
            // contain commas.
            val failed = System.getProperty("khoirevanced.nexalloy.failed").orEmpty()
                .split("|")
                .map(String::trim)
                .filter { it.isNotEmpty() }
            failedPatches = failed
            if (failed.isNotEmpty()) {
                Log.w(TAG, "Patches that did not apply: ${failed.joinToString(", ")}")
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
        val extracted = ZipFile(module).use { zip ->
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

        // ART refuses to load a dex that is still writable, and it tests the
        // containing directory as well as the file:
        //
        //   SecurityException: Writable dex file '<...>/nexalloy-dex/input/classes.dex'
        //   is not allowed.
        //
        // The payload files staged by inject.sh are already made read-only for the
        // same reason, but these are written here at runtime with default
        // permissions, so they have to be sealed after extraction. Clearing only
        // the owner write bit is enough: ART asks whether access(W_OK) would
        // succeed, and this process owns both the files and the directory.
        // Deliberately not sealed read-only as root: the injector chowns the cache
        // tree to the app so DexClassLoader can write its optimized output.
        RuntimeLog.stage("dex-sealed", "count=${extracted.size}")
        extracted.forEach { it.setWritable(false, false) }
        inputDir.setWritable(false, false)
        return extracted
    }
}
