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
            RuntimeDiagnostics.stage(parsed, "config-loaded")
            Log.i(TAG, "bootstrap pid=${android.os.Process.myPid()} package=${parsed.packageName} " +
                "profile=${parsed.profile} agent=${parsed.agentPath} module=${parsed.modulePath}")
            // The library was originally loaded by the ptrace injector. Load it
            // through this DexClassLoader too, so ART associates JNI methods
            // with the payload's class loader.
            System.load(parsed.agentPath)
            RuntimeDiagnostics.stage(parsed, "agent-library-loaded")
            val app = waitForApplication(parsed.applicationTimeoutMs)
            RuntimeDiagnostics.stage(parsed, "application-ready", app.packageName)
            PineHookRuntime.initialize(parsed)
            RuntimeDiagnostics.stage(parsed, "pine-ready", PineHookRuntime.status)
            check(PineHookRuntime.status == "pine-xposed-ready") {
                "Pine ART backend is unavailable: ${PineHookRuntime.status}"
            }
            NativeHookBackend.initialize(parsed)
            RuntimeDiagnostics.stage(parsed, "native-backend-ready")
            HookRuntime.install(NativeHookBackend)
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
                detail = "engine=${PineHookRuntime.status}; module=${NexAlloyCompatibilityModule.status}; capabilities=${NativeHookBackend.capabilities.joinToString()}"
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
