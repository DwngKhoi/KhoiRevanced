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
            // The library was originally loaded by the ptrace injector. Load it
            // through this DexClassLoader too, so ART associates JNI methods
            // with the payload's class loader.
            System.load(parsed.agentPath)
            val app = waitForApplication(parsed.applicationTimeoutMs)
            PineHookRuntime.initialize(parsed)
            NativeHookBackend.initialize(parsed)
            HookRuntime.install(NativeHookBackend)
            NexAlloyCompatibilityModule.load(app, parsed)
            check(NexAlloyCompatibilityModule.status == "nexalloy-loaded") {
                "NexAlloy compatibility module did not load: ${NexAlloyCompatibilityModule.status}"
            }
            PatchEntry.start(app, parsed)
            RuntimeDiagnostics.record(
                parsed,
                state = "ready",
                detail = "engine=${PineHookRuntime.status}; module=${NexAlloyCompatibilityModule.status}; capabilities=${NativeHookBackend.capabilities.joinToString()}"
            )
            Log.i(TAG, "Runtime attached to ${app.packageName}; profile=${parsed.profile}")
        }.onFailure { error ->
            config?.let { RuntimeDiagnostics.record(it, "failed", error.stackTraceToString()) }
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
