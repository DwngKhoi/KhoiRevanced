package dev.khoirevanced.runtime.agent

import dev.khoirevanced.runtime.api.HookRuntime
import dev.khoirevanced.runtime.api.MethodHookCallback
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Mirrors the patch set's own log into [RuntimeLog].
 *
 * ## Why this is needed
 *
 * The runtime trace records the bootstrap, the hook registrations and the patch
 * results, and hook-time exceptions. None of that is where a patch reports a
 * problem with itself. NexAlloy funnels every message through one class,
 * `app.morphe.extension.shared.Logger`, whose `logInternal` is the single point
 * all of `printDebug`, `printInfo` and `printException` pass through. A patch
 * that swallows an exception and reports it there -- `BaseActivityHook` doing so
 * around the whole settings-screen construction, for instance -- produces no
 * crash, no logcat line that survives the quota, and no entry anywhere else. The
 * failure is then visible only as a toast, truncated to what fits on screen.
 *
 * ## Why hooking it rather than reading logcat
 *
 * Reading logcat was the previous approach and it does not work. Two reasons,
 * both observed on the device:
 *
 * 1. With the hook engine tracing enabled, the process blows through Android's
 *    per-process log quota and the surplus is dropped
 *    (`LOGS OVER PROC QUOTA(300) ... DROPPED`). The dropped lines are not chosen
 *    by relevance, so the explanatory message is as likely to be evicted as the
 *    noise that evicted it.
 * 2. An injected process does not necessarily log where the reader is looking.
 *    A run can produce a full logcat buffer with nothing from the process in it.
 *
 * Intercepting `logInternal` puts the text in a file this runtime owns, on every
 * process, with no quota involved.
 *
 * ## What is captured
 *
 * The severity, the rendered message and -- when there is one -- the exception
 * with its full stack trace. The stack trace is the part that was missing
 * entirely before: `Logger` appends it only when its own `DEBUG_STACKTRACE`
 * setting is on, and it reaches the toast truncated regardless.
 *
 * `logInternal` is private, so this depends on an internal method of a
 * third-party class. That is a deliberate, narrow coupling: if the signature
 * changes the bridge reports it and the run continues, because a missing log
 * mirror must never be the reason a process fails to patch.
 */
internal object NexAlloyLogBridge {

    private const val STAGE = "nexalloy-log"
    private const val LOGGER_CLASS = "app.morphe.extension.shared.Logger"
    private const val LOG_INTERNAL = "logInternal"
    private const val BUILD_MESSAGE = "buildMessageString"
    private const val ANDROID_LOG_LIMIT = 3

    /**
     * Guards against a message whose own rendering logs. Without it, a
     * `LogMessage` that calls `Logger` while building its text would recurse into
     * this hook indefinitely, and it would do so from inside whichever patched
     * method happened to be logging.
     */
    private val rendering = ThreadLocal.withInitial { false }

    /**
     * Install the mirror. Called once the payload's class loader exists but
     * before any payload code runs, so that the earliest messages -- including
     * the ones from `initZygote` -- are captured.
     *
     * Returns the number of messages mirrored, for the stage detail.
     */
    fun install(payloadLoader: ClassLoader): Int {
        val logger = runCatching { payloadLoader.loadClass(LOGGER_CLASS) }.getOrElse { error ->
            RuntimeLog.warn(
                STAGE,
                "Logger class is absent (${error.javaClass.simpleName}: " +
                    "${error.message}); the patch log will not be mirrored",
            )
            return 0
        }
        val target = runCatching {
            logger.declaredMethods.first { method ->
                method.name == LOG_INTERNAL &&
                    Modifier.isStatic(method.modifiers) &&
                    method.parameterCount == ANDROID_LOG_LIMIT + 2
            }
        }.getOrElse { error ->
            RuntimeLog.warn(
                STAGE,
                "Logger.$LOG_INTERNAL is not the expected shape " +
                    "(${error.javaClass.simpleName}); the patch log will not be mirrored",
            )
            return 0
        }
        target.isAccessible = true

        val messageOf = buildMessageAccessor(target.parameterTypes[1])
        val count = java.util.concurrent.atomic.AtomicInteger()
        runCatching {
            HookRuntime.backend.hook(target, object : MethodHookCallback() {
                override fun before(param: dev.khoirevanced.runtime.api.HookParam) {
                    if (rendering.get()) return
                    rendering.set(true)
                    try {
                        val level = param.args.getOrNull(0)?.toString() ?: "?"
                        val text = messageOf(param.args.getOrNull(1))
                        val throwable = param.args.getOrNull(2) as? Throwable
                        val line = "$level $text"
                        when (level) {
                            "ERROR" -> RuntimeLog.error(STAGE, line, throwable)
                            "WARN" -> RuntimeLog.warn(STAGE, line)
                            else -> RuntimeLog.info(STAGE, line)
                        }
                        count.incrementAndGet()
                    } finally {
                        rendering.set(false)
                    }
                }
            })
        }.onSuccess {
            RuntimeLog.stage("$STAGE-installed", "target=${logger.name}.${target.name}")
        }.onFailure { error ->
            RuntimeLog.warn(
                STAGE,
                "Could not hook Logger.$LOG_INTERNAL: " +
                    "${error.javaClass.simpleName}: ${error.message}",
            )
        }
        return count.get()
    }

    /**
     * Render a `LogMessage`, whose `buildMessageString` is declared on an
     * interface the runtime does not compile against.
     *
     * Rendered once here, in addition to the render `Logger` performs itself, so
     * the text is the same one the user would have read in a toast. A failure to
     * render falls back to the object's own `toString`, which is still better
     * than dropping the line.
     */
    private fun buildMessageAccessor(parameterType: Class<*>): (Any?) -> String {
        val method: Method? = runCatching {
            parameterType.getMethod(BUILD_MESSAGE)
        }.getOrNull()
        return { message ->
            if (message == null) {
                "<no message>"
            } else if (method == null) {
                message.toString()
            } else {
                runCatching { method.invoke(message) as? String }
                    .getOrNull()
                    ?: message.toString()
            }
        }
    }
}
