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
        runCatching {
            val config = RuntimeConfig.parse(File(configPath))
            val app = waitForApplication(config.applicationTimeoutMs)
            NativeHookBackend.initialize(config)
            HookRuntime.install(NativeHookBackend)
            PatchEntry.start(app, config)
            Log.i(TAG, "Runtime attached to ${app.packageName}; profile=${config.profile}")
        }.onFailure { error ->
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
