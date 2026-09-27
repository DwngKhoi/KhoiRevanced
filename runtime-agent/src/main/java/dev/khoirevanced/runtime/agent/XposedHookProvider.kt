package dev.khoirevanced.runtime.agent

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import dev.khoirevanced.runtime.api.HookHandle
import dev.khoirevanced.runtime.api.HookRuntime
import dev.khoirevanced.runtime.api.HookParam
import dev.khoirevanced.runtime.api.MethodHookCallback
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Routes NexAlloy's legacy Xposed Java API through the standalone LSPlant
 * backend. The bundled Pine JARs are API classes only; their Pine provider is
 * never selected and libpine.so is never loaded.
 *
 * ## One interception per member, many callbacks
 *
 * `XposedBridge.hookMethod` may be called any number of times for the same
 * member, each with its own `XC_MethodHook`, and Xposed runs all of them. LSPlant
 * installs exactly one ART interception per member, so this provider keeps a
 * single handle per member and an ordered callback list behind it.
 *
 * This matters for the real patch set: `initializeButtonsFingerprint` is hooked
 * three times (NavigationBarHookPatch), `experimentalBooleanFeatureFlagFingerprint`
 * twice (EnableDebuggingPatch and FeatureOverride), and
 * `pivotBarButtonsViewSetSelectedFingerprint` twice. Registering the handle with
 * `computeIfAbsent` silently discarded every registration after the first,
 * dropping those callbacks entirely.
 *
 * Ordering follows Xposed: `before` callbacks run in registration order and stop
 * at the first `returnEarly`; `after` callbacks run over exactly the callbacks
 * that ran in `before`, in reverse.
 */
internal object XposedHookProvider : XposedBridge.HookProvider {

    private class MemberHooks(
        @Suppress("unused") val handle: HookHandle,
    ) {
        /**
         * Ordered, flattened view of every registered callback set.
         *
         * Each registration contributes its own priority-sorted snapshot, so
         * within a set Xposed's priority order is preserved and across sets
         * registration order wins. Rebuilt on registration rather than per
         * invocation to keep hooked methods allocation-free.
         */
        @Volatile
        var callbacks: List<XC_MethodHook> = emptyList()
    }

    private val hooks = ConcurrentHashMap<Member, MemberHooks>()

    private class Pending(
        val param: XC_MethodHook.MethodHookParam,
        val callbacks: List<XC_MethodHook>,
        val completed: Int,
    )

    private val pending = ThreadLocal<Pending?>()

    private val beforeCallback = XC_MethodHook::class.java.getDeclaredMethod(
        "beforeHookedMethod", XC_MethodHook.MethodHookParam::class.java,
    ).apply { isAccessible = true }
    private val afterCallback = XC_MethodHook::class.java.getDeclaredMethod(
        "afterHookedMethod", XC_MethodHook.MethodHookParam::class.java,
    ).apply { isAccessible = true }
    private val returnEarly = XC_MethodHook.MethodHookParam::class.java.getDeclaredField(
        "returnEarly",
    ).apply { isAccessible = true }

    fun install() {
        XposedBridge.setHookProvider(this)
    }

    override fun hook(
        member: Member,
        callbackSet: XposedBridge.CopyOnWriteSortedSet<XC_MethodHook>,
    ) {
        val snapshot = callbackSet.snapshotCallbacks()
        val result = hooks.compute(member) { _, existing ->
            if (existing != null) {
                existing.callbacks = existing.callbacks + snapshot
                existing
            } else {
                val handle = HookRuntime.backend.hook(member, dispatcherFor(member))
                MemberHooks(handle).apply { callbacks = snapshot }
            }
        }
        RuntimeLog.info(
            "xposed-hook",
            "${member.declaringClass?.name}.${member.name} " +
                "callbacks=${result?.callbacks?.size} total=${result != null}",
        )
    }

    override fun invokeOriginal(member: Member, receiver: Any?, args: Array<Any?>): Any? =
        HookRuntime.backend.invokeOriginal(member, receiver, args)

    private fun dispatcherFor(member: Member) = object : MethodHookCallback() {
        override fun before(param: HookParam) {
            val active = hooks[member]?.callbacks.orEmpty()
            if (active.isEmpty()) return

            val xposedParam = XC_MethodHook.MethodHookParam().apply {
                method = member
                thisObject = param.receiver
                args = param.args
            }
            var completed = -1
            for (index in active.indices) {
                val previousResult = xposedParam.result
                val previousThrowable = xposedParam.throwable
                Recursion.enter(member)
                try {
                    beforeCallback.invokeCallback(active[index], xposedParam)
                } catch (error: Throwable) {
                    // Xposed isolates callback failures: log and continue with the
                    // state the previous callback left behind.
                    XposedBridge.log(error)
                    RuntimeLog.error(
                        "hook-before",
                        "${member.declaringClass?.name}.${member.name} threw",
                        error,
                    )
                    xposedParam.restore(previousResult, previousThrowable)
                } finally {
                    Recursion.exit()
                }
                completed = index
                if (xposedParam.isReturnEarly()) break
            }

            param.receiver = xposedParam.thisObject
            // A callback may swap in a different array, or one of a different
            // length; only copy back the overlap that both sides have.
            xposedParam.args?.let { source ->
                val shared = minOf(source.size, param.args.size)
                for (index in 0 until shared) param.args[index] = source[index]
            }
            if (xposedParam.hasThrowable()) {
                param.throwResult(xposedParam.throwable)
            } else if (xposedParam.isReturnEarly()) {
                param.returnResult(xposedParam.result)
            }
            pending.set(Pending(xposedParam, active, completed))
        }

        override fun after(param: HookParam) {
            val state = pending.get() ?: return
            try {
                if (param.throwable != null) state.param.throwable = param.throwable
                else state.param.result = param.result
                for (index in state.completed downTo 0) {
                    val previousResult = state.param.result
                    val previousThrowable = state.param.throwable
                    Recursion.enter(member)
                    try {
                        afterCallback.invokeCallback(state.callbacks[index], state.param)
                    } catch (error: Throwable) {
                        XposedBridge.log(error)
                        RuntimeLog.error(
                            "hook-after",
                            "${member.declaringClass?.name}.${member.name} threw",
                            error,
                        )
                        state.param.restore(previousResult, previousThrowable)
                    } finally {
                        Recursion.exit()
                    }
                }
                if (state.param.hasThrowable()) param.throwResult(state.param.throwable)
                else param.returnResult(state.param.result)
            } finally {
                pending.remove()
            }
        }
    }

    private fun XposedBridge.CopyOnWriteSortedSet<XC_MethodHook>.snapshotCallbacks(): List<XC_MethodHook> =
        getSnapshot().filterIsInstance<XC_MethodHook>()

    private fun Method.invokeCallback(callback: XC_MethodHook, param: XC_MethodHook.MethodHookParam) {
        try {
            invoke(callback, param)
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    /**
     * Hook callbacks that call back into hooked methods, tracked so the cycle can
     * be named instead of only its consequence.
     *
     * A patch whose callback invokes a method that is itself hooked -- directly or
     * through a call the host makes in between -- will recurse until the thread's
     * stack is gone. Nothing above can report that usefully: ART exhausts the stack
     * inside a native invoke, so the process dies with SIGSEGV and the tombstone
     * is thousands of frames of the same three symbols with no Java frames left to
     * read. That is what was observed here, on a "ComponentLayout" thread, at 2594
     * frames of `handleCall -> Method_invoke -> handleCall`.
     *
     * So the cycle is recorded from the Java side, where the member names still
     * exist, and reported once per threshold crossing. This is a diagnostic only:
     * it neither breaks the cycle nor changes what a callback is allowed to do,
     * because deciding that a patch is recursing and silently truncating it would
     * hide a real defect behind a working-looking feature.
     */
    private object Recursion {

        /** Deep enough that no legitimate patch nest is reported. */
        private const val REPORT_AT = 24

        /** Reported thresholds, so one runaway is described once and not per frame. */
        private const val REPORT_EVERY = 512

        // ThreadLocal.get() is @Nullable on Android whatever the type argument says,
        // so every read here is treated as possibly absent rather than argued about.
        private val stack: ThreadLocal<ArrayList<Member>> =
            ThreadLocal.withInitial<ArrayList<Member>> { ArrayList(REPORT_AT * 2) }

        fun enter(member: Member) {
            val frames = stack.get() ?: return
            frames.add(member)
            val depth = frames.size
            if (depth == REPORT_AT || (depth > REPORT_AT && depth % REPORT_EVERY == 0)) {
                RuntimeLog.warn(
                    "hook-recursion",
                    "depth=$depth chain=" + frames.joinToString(" -> ") {
                        "${it.declaringClass?.simpleName}.${it.name}"
                    },
                )
            }
        }

        fun exit() {
            val frames = stack.get() ?: return
            if (frames.isNotEmpty()) frames.removeAt(frames.size - 1)
            if (frames.isEmpty()) stack.remove()
        }
    }

    private fun XC_MethodHook.MethodHookParam.restore(result: Any?, throwable: Throwable?) {
        if (throwable == null) setResult(result) else setThrowable(throwable)
    }

    private fun XC_MethodHook.MethodHookParam.isReturnEarly(): Boolean =
        returnEarly.getBoolean(this)
}
