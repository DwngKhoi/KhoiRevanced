package dev.khoirevanced.runtime.agent

import java.io.File

data class RuntimeConfig(
    val packageName: String,
    val profile: String,
    val cacheDir: String,
    val agentPath: String,
    val modulePath: String?,
    val pinePath: String?,
    val applicationTimeoutMs: Long,
    /**
     * Whether Pine narrates every hook callback to logcat.
     *
     * Off by default, and it has to stay off for anything but a debugging
     * session. Pine logs one `handleBridge` and one `handleCall` line per
     * invocation of every hooked method, which on a warm start of YouTube is
     * thousands of lines within a second. Android then enforces the per-process
     * log quota and drops the surplus:
     *
     *     W LOG_FLOWCTRL: ==LOGS OVER PROC QUOTA(300), rows(622) DROPPED==
     *
     * The lines it drops are not chosen by relevance. They are exactly the ones
     * the runtime needs -- a patch that logs a failure through `android.util.Log`
     * is indistinguishable from the hook chatter that pushed it out. That is
     * what made the settings-screen failure impossible to diagnose: the one
     * message explaining it was evicted by the engine's own tracing.
     */
    val pineDebug: Boolean = false,
) {
    companion object {
        fun parse(file: File): RuntimeConfig {
            val values = file.readLines()
                .asSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith('#') }
                .map { line ->
                    val separator = line.indexOf('=')
                    require(separator > 0) { "Invalid config line: $line" }
                    line.substring(0, separator).trim() to line.substring(separator + 1).trim()
                }
                .toMap()

            return RuntimeConfig(
                packageName = values.getValue("package"),
                profile = values["profile"] ?: "default",
                cacheDir = values["cache_dir"] ?: "/data/local/tmp/khoirevanced/cache",
                agentPath = values.getValue("agent"),
                modulePath = values["module"]?.takeIf { it.isNotBlank() },
                pinePath = values["pine"]?.takeIf { it.isNotBlank() },
                applicationTimeoutMs = values["application_timeout_ms"]?.toLong() ?: 15_000L,
                pineDebug = values["pine_debug"]?.trim()?.equals("1", ignoreCase = true) ?: false,
            )
        }
    }
}
