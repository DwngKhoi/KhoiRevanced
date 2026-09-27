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
        // The patch set is executed by NexAlloyCompatibilityModule before this
        // point, so reaching here means the payload was loaded and its executor
        // reported completion. There is no "probe" mode: the agent is either
        // running the real patch set or it failed during bootstrap.
        Log.i("KhoiRevanced", "Patch profile ${config.profile} is ready")
    }
}
