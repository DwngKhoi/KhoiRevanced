package dev.khoirevanced.runtime.agent

import android.util.Log
import dev.khoirevanced.runtime.api.HookRuntime
import top.canyie.pine.Pine
import top.canyie.pine.PineConfig
import java.io.File

/**
 * Starts Pine and installs [PineHookBackend] as the process hook engine.
 *
 * ## Loading the engine
 *
 * Pine's own default loader is `System.loadLibrary("pine")`, which searches the
 * host application's native library directory and finds nothing: the engine is
 * not part of the target APK. [PineConfig.libLoader] is replaced so Pine loads
 * the exact file the injector staged, which is also why the JNI methods resolve:
 * `libpine.so`'s `JNI_OnLoad` calls `FindClass("top/canyie/pine/Pine")` and
 * returns `JNI_ERR` unless the Java classes are reachable from the loading
 * classloader. Loading through the agent's own classloader satisfies that.
 */
object PineRuntime {

    private const val TAG = "KhoiRevanced"

    fun initialize(config: RuntimeConfig) {
        val engine = config.pinePath
            ?: error("pine= is missing from the runtime config")
        check(File(engine).canRead()) { "Pine engine is not readable: $engine" }

        PineConfig.libLoader = Pine.LibLoader { System.load(engine) }
        PineConfig.debug = config.pineDebug
        // PineConfig.debuggable is left at its default. Deciding it would mean
        // reading the host ApplicationInfo, and this runs before the Application
        // exists, on a thread started from an ELF constructor. The only target is
        // a normal release app, where false is the correct answer.

        Pine.ensureInitialized()
        check(Pine.isInitialized()) { "Pine reported an initialised state of false" }
        Log.i(TAG, "Pine initialised; arch64=${Pine.is64Bit()} hookMode=${Pine.getHookMode()}")
        // Stated explicitly because it changes what logcat is worth: with debug
        // on, the quota drops the host's own diagnostics and logcat cannot be
        // used to investigate anything at all.
        RuntimeLog.stage("pine-trace", "debug=${config.pineDebug}")

        HookRuntime.install(PineHookBackend)
        RuntimeDiagnostics.stage(config, "pine-ready", "mode=${Pine.getHookMode()}")
    }
}
