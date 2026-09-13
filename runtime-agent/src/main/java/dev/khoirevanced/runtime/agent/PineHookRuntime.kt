package dev.khoirevanced.runtime.agent

import android.os.Build
import android.util.Log
import top.canyie.pine.Pine
import top.canyie.pine.PineConfig
import java.io.File

/**
 * Loads Pine from the root-managed payload and exposes its Xposed-compatible
 * API to the NexAlloy-derived patch layer.  A Pine failure is recorded rather
 * than taking down the host app, which is essential while adapting ART 16.
 */
object PineHookRuntime {
    private const val TAG = "KhoiRevanced"

    @Volatile
    var status: String = "not-initialized"
        private set

    fun initialize(config: RuntimeConfig) {
        if (Pine.isInitialized()) {
            status = "pine-xposed-ready"
            return
        }

        runCatching {
            check(Build.VERSION.SDK_INT < 36) {
                "Bundled Pine revision is not validated for Android API ${Build.VERSION.SDK_INT}; " +
                    "use an Android-16-compatible ART backend before enabling hooks"
            }
            val library = (File(config.agentPath).parentFile ?: error("Invalid agent path"))
                .resolve("libpine.so")
            require(library.isFile) { "Pine native library is missing: $library" }
            PineConfig.debug = true
            PineConfig.libLoader = Pine.LibLoader { System.load(library.absolutePath) }
            Pine.ensureInitialized()
        }.onSuccess {
            status = "pine-xposed-ready"
            Log.i(TAG, "Pine Xposed-compat runtime is ready")
        }.onFailure { error ->
            status = "pine-unavailable:${error.javaClass.simpleName}"
            Log.e(TAG, "Pine initialization failed; preserving non-hook runtime", error)
        }
    }
}
