package dev.khoirevanced.runtime.agent

import android.app.Application
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import top.canyie.pine.xposed.ModuleClassLoader
import java.io.File

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
            // Do not use PineXposed.loadModule here: its generic module
            // validator intentionally rejects a multidex application package
            // containing compile-time Xposed references. We construct its
            // documented ModuleClassLoader and dispatch the original entry
            // interfaces directly, retaining all NexAlloy patch code.
            val loader = ModuleClassLoader(module.absolutePath, nativeDir, javaClass.classLoader)
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
            status = "nexalloy-failed:${error.javaClass.simpleName}"
            Log.e(TAG, "NexAlloy compatibility patch set failed", error)
        }
    }
}
