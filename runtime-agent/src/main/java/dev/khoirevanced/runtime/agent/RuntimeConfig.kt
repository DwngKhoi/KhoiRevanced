package dev.khoirevanced.runtime.agent

import java.io.File

data class RuntimeConfig(
    val packageName: String,
    val profile: String,
    val cacheDir: String,
    val agentPath: String,
    val modulePath: String?,
    val applicationTimeoutMs: Long,
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
                applicationTimeoutMs = values["application_timeout_ms"]?.toLong() ?: 15_000L,
            )
        }
    }
}
