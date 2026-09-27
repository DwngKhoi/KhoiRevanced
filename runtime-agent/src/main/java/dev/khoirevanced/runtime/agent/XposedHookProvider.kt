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
        hooks.compute(member) { _, existing ->
            if (existing != null) {
                existing.callbacks = existing.callbacks + snapshot
                existing
            } else {
                val handle = HookRuntime.backend.hook(member, dispatcherFor(member))
                MemberHooks(handle).apply { callbacks = snapshot }
            }
        }
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
                try {
                    beforeCallback.invokeCallback(active[index], xposedParam)
                } catch (error: Throwable) {
                    // Xposed isolates callback failures: log and continue with the
                    // state the previous callback left behind.
                    XposedBridge.log(error)
                    xposedParam.restore(previousResult, previousThrowable)
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
                    try {
                        afterCallback.invokeCallback(state.callbacks[index], state.param)
                    } catch (error: Throwable) {
                        XposedBridge.log(error)
                        state.param.restore(previousResult, previousThrowable)
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

    private fun XC_MethodHook.MethodHookParam.restore(result: Any?, throwable: Throwable?) {
        if (throwable == null) setResult(result) else setThrowable(throwable)
    }

    private fun XC_MethodHook.MethodHookParam.isReturnEarly(): Boolean =
        returnEarly.getBoolean(this)
}
