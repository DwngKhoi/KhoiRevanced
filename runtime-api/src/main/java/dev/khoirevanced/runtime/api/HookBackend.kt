package dev.khoirevanced.runtime.api

import java.lang.reflect.Member
import java.util.concurrent.atomic.AtomicReference

/**
 * Stable boundary between NexAlloy-derived patches and the runtime hook engine.
 * Patch code must depend on this contract instead of importing Xposed classes.
 */
interface HookBackend {
    val capabilities: Set<HookCapability>

    fun hook(member: Member, callback: MethodHookCallback): HookHandle

    fun invokeOriginal(member: Member, receiver: Any?, args: Array<Any?>): Any?

    fun deoptimize(member: Member): Boolean = false
}

enum class HookCapability {
    METHOD,
    CONSTRUCTOR,
    INVOKE_ORIGINAL,
    DEOPTIMIZE,
    ADDITIONAL_FIELDS,
}

fun interface HookHandle {
    fun unhook()
}

abstract class MethodHookCallback {
    open fun before(param: HookParam) = Unit
    open fun after(param: HookParam) = Unit
}

class HookParam internal constructor(
    val member: Member,
    var receiver: Any?,
    val args: Array<Any?>,
) {
    var result: Any? = null
    var throwable: Throwable? = null
    var returnEarly: Boolean = false

    fun returnResult(value: Any?) {
        result = value
        throwable = null
        returnEarly = true
    }

    fun throwResult(error: Throwable) {
        throwable = error
        returnEarly = true
    }
}

/** Installed once by the process bootstrap before patches are evaluated. */
object HookRuntime {
    private val backendRef = AtomicReference<HookBackend?>()

    val backend: HookBackend
        get() = backendRef.get() ?: error("KhoiRevanced hook backend is not installed")

    fun install(backend: HookBackend) {
        check(backendRef.compareAndSet(null, backend)) { "Hook backend already installed" }
    }

    fun resetForTests() {
        backendRef.set(null)
    }
}
