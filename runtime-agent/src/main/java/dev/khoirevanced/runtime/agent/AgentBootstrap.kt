package dev.khoirevanced.runtime.agent

import android.app.Application
import android.util.Log
import dev.khoirevanced.runtime.api.HookRuntime
import java.io.File

/** Entry called by libkhoirevanced_agent after it attaches to the host VM. */
object AgentBootstrap {
    private const val TAG = "KhoiRevanced"

    @JvmStatic
    fun start(configPath: String) {
        var config: RuntimeConfig? = null
        runCatching {
            val parsed = RuntimeConfig.parse(File(configPath))
            config = parsed
            RuntimeLog.open(parsed.cacheDir)
            RuntimeLog.stage("bootstrap", "pid=${android.os.Process.myPid()} package=${parsed.packageName} " +
                "profile=${parsed.profile} agent=${parsed.agentPath} module=${parsed.modulePath}")
            RuntimeDiagnostics.stage(parsed, "config-loaded")
            // The library was originally loaded by the ptrace injector. Load it
            // through this DexClassLoader too, so ART associates JNI methods
            // with the payload's class loader.
            System.load(parsed.agentPath)
            // The native half of invokeSpecial lives in this library, so the
            // super-call path is only usable once it is loaded.
            ArtInvoke.markLoaded()
            RuntimeLog.stage("agent-library-loaded")
            RuntimeDiagnostics.stage(parsed, "agent-library-loaded")
            // PineRuntime starts the engine and installs the backend; this
            // records the capabilities that were actually installed.
            PineRuntime.initialize(parsed)
            RuntimeLog.stage("hook-backend-ready", HookRuntime.backend.capabilities.joinToString())
            RuntimeDiagnostics.stage(
                parsed,
                "hook-backend-ready",
                HookRuntime.backend.capabilities.joinToString(),
            )
            XposedHookProvider.install()
            val app = waitForApplication(parsed.applicationTimeoutMs)
            RuntimeLog.stage("application-ready", app.packageName)
            RuntimeDiagnostics.stage(parsed, "application-ready", app.packageName)
            // NexAlloy's entry class extends XposedModule, whose every delegated
            // call throws "Framework not attached" until a framework object is
            // attached. Supply our own in-process implementation before any
            // module code runs.
            StandaloneXposedInterface.context = app
            StandaloneXposedInterface.hostApplicationInfo = app.applicationInfo
            StandaloneXposedInterface.payloadDirectory = File(parsed.agentPath).parentFile
            RuntimeLog.stage("xposed-service-bound", "framework=${StandaloneXposedInterface.getFrameworkName()}")
            RuntimeDiagnostics.stage(parsed, "nexalloy-loading", parsed.modulePath ?: "none")
            NexAlloyCompatibilityModule.load(app, parsed)
            RuntimeDiagnostics.stage(parsed, "nexalloy-load-finished", NexAlloyCompatibilityModule.status)
            check(NexAlloyCompatibilityModule.status == "nexalloy-loaded") {
                "NexAlloy compatibility module did not load: ${NexAlloyCompatibilityModule.status}"
            }
            PatchEntry.start(app, parsed)
            RuntimeDiagnostics.stage(parsed, "patch-entry-finished")
            RuntimeDiagnostics.record(
                parsed,
                state = "ready",
                detail = "engine=pine; module=${NexAlloyCompatibilityModule.status}; " +
                    "capabilities=${HookRuntime.backend.capabilities.joinToString()}; " +
                    "failedPatches=${NexAlloyCompatibilityModule.failedPatches
                        .ifEmpty { listOf("none") }.joinToString(" ; ")}",
            )
            Log.i(TAG, "Runtime attached to ${app.packageName}; profile=${parsed.profile}")
        }.onFailure { error ->
            config?.let {
                RuntimeDiagnostics.stage(it, "bootstrap-failed",
                    "${error.javaClass.name}:${error.message ?: "no-message"}")
                RuntimeDiagnostics.record(it, "failed", error.stackTraceToString())
            }
            Log.e(TAG, "Agent bootstrap failed", error)
        }
    }

    private fun waitForApplication(timeoutMs: Long): Application {
        val activityThread = Class.forName("android.app.ActivityThread")
        val currentApplication = activityThread.getDeclaredMethod("currentApplication")
        val deadline = System.currentTimeMillis() + timeoutMs
        do {
            (currentApplication.invoke(null) as? Application)?.let { return it }
            Thread.sleep(25)
        } while (System.currentTimeMillis() < deadline)
        error("Application was not created within ${timeoutMs}ms")
    }
}
