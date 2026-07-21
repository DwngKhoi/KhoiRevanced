package dev.khoirevanced.runtime.agent

import android.util.Log
import android.os.Process
import java.io.File
import java.time.Instant

/** File-based diagnostic channel for a root shell controller. */
object RuntimeDiagnostics {
    private const val TAG = "KhoiRevanced"

    fun record(config: RuntimeConfig, state: String, detail: String = "") {
        val output = File(config.cacheDir, "agent-status.txt")
        runCatching {
            output.parentFile?.mkdirs()
            output.writeText(
                "timestamp=${Instant.now()}\n" +
                    "pid=${Process.myPid()}\n" +
                    "state=$state\n" +
                    "package=${config.packageName}\n" +
                    "profile=${config.profile}\n" +
                    "detail=$detail\n"
            )
        }.onFailure { Log.e(TAG, "Could not write runtime diagnostics", it) }
    }
}
