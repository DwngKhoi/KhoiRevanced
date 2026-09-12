package io.github.nexalloy

import android.app.AppComponentFactory
import android.app.Application
import android.content.pm.ApplicationInfo
import app.morphe.extension.shared.ResourceType
import app.morphe.extension.shared.ResourceUtils
import app.morphe.extension.shared.Utils
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.IXposedHookZygoteInit.StartupParam
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.nexalloy.common.UpdateChecker
import io.github.nexalloy.morphe.ResourceFinder
import io.github.nexalloy.morphe.resourceMappings

class MainHook : XposedModule(), IXposedHookLoadPackage, IXposedHookZygoteInit {
    lateinit var param: PackageReadyParam
    lateinit var app: Application
    var targetPackageName: String? = null

    fun shouldHook(packageName: String): Boolean {
        if (!patchesByPackage.containsKey(packageName)) return false
        if (targetPackageName == null) targetPackageName = packageName
        return targetPackageName == packageName
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        modulePath = moduleApplicationInfo.sourceDir
    }

    override fun initZygote(startupParam: StartupParam) {
        modulePath = startupParam.modulePath
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (!lpparam.isFirstApplication) return
        if (!shouldHook(lpparam.packageName)) return

        val readyParam = object : PackageReadyParam {
            override fun getPackageName(): String = lpparam.packageName
            override fun getClassLoader(): ClassLoader = lpparam.classLoader
            override fun getDefaultClassLoader(): ClassLoader = lpparam.classLoader
            override fun getApplicationInfo(): ApplicationInfo = lpparam.appInfo
            override fun getAppComponentFactory(): AppComponentFactory = AppComponentFactory()
            override fun isFirstPackage(): Boolean = lpparam.isFirstApplication
        }
        onPackageReady(readyParam)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage) return
        if (!shouldHook(param.packageName)) return
        this.param = param

        inContext(param) { app ->
            this.app = app
            if (isReVancedPatched(param)) {
                Utils.showToastLong("NexAlloy module does not work with patched app")
                return@inContext
            }

            resourceMappings = object : ResourceFinder {
                override operator fun get(type: String, name: String): Int {
                    val id = ResourceUtils.getIdentifier(ResourceType.fromValue(type), name)
                    if (id == 0) throw Exception("Could not find resource type: $type name: $name")
                    return id
                }
            }

            val patches = patchesByPackage[param.packageName] ?: return@inContext
            val patchesApplied = PatchExecutor(app, param, this).applyPatches(patches)
            // Direct-runtime health probe. This is process-local and is read
            // by KhoiRevanced after Pine dispatches the upstream callback.
            System.setProperty(
                "khoirevanced.nexalloy.state",
                if (patchesApplied) "patches-applied" else "patches-failed"
            )
        }
    }

    private fun isReVancedPatched(param: PackageReadyParam): Boolean {
        return runCatching {
            param.classLoader.loadClass("app.morphe.extension.shared.Utils")
        }.isSuccess || runCatching {
            param.classLoader.loadClass("app.morphe.extension.shared.utils.Utils")
        }.isSuccess || runCatching {
            param.classLoader.loadClass("app.revanced.integrations.shared.Utils")
        }.isSuccess || runCatching {
            param.classLoader.loadClass("app.revanced.integrations.shared.utils.Utils")
        }.isSuccess
    }

}

context(xposed: XposedInterface)
fun inContext(lpparam: PackageReadyParam, f: (Application) -> Unit) {
    // KhoiRevanced loads this runtime after the target process has completed
    // Application.onCreate. Reuse the current Application in that case while
    // retaining the normal Xposed callback path for module deployments.
    runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        activityThread.getDeclaredMethod("currentApplication").invoke(null) as? Application
    }.getOrNull()?.let { app ->
        Utils.setContext(app)
        f(app)
        return
    }

    val appClazz = XposedHelpers.findClass(lpparam.applicationInfo.className, lpparam.classLoader)
    appClazz.getMethod("onCreate").hookMethod {
        before {
            val app = it.thisObject as Application
            Utils.setContext(app)
            f(app)
            if (modulePath.startsWith("/data/app/")) {
                val prefs = runCatching { xposed.getRemotePreferences("prefs") }.getOrNull()
                if (prefs?.getBoolean("disable_auto_check_update", false) == false) {
                    UpdateChecker().hookNewActivity()
                }
            }
        }
    }
}
