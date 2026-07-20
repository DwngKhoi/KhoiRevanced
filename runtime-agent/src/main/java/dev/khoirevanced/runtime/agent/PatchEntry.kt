package dev.khoirevanced.runtime.agent

import android.app.Application
import android.util.Log

/**
 * Deliberately small upstream-port boundary. NexAlloy patches will be moved
 * behind this entry incrementally, without coupling injection code to patches.
 */
object PatchEntry {
    @JvmStatic
    fun start(application: Application, config: RuntimeConfig) {
        require(application.packageName == config.packageName) {
            "Config targets ${config.packageName}, process is ${application.packageName}"
        }
        if (config.modulePath == null) SettingsProbeOverlay.install(application, config)
        Log.i("KhoiRevanced", "Patch profile ${config.profile} is ready")
        // Phase 2: construct RuntimePatchExecutor and register ported patch sets.
    }
}
