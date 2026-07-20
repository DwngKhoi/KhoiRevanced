package dev.khoirevanced.runtime.agent

import dev.khoirevanced.runtime.api.HookBackend
import dev.khoirevanced.runtime.api.HookCapability
import dev.khoirevanced.runtime.api.HookHandle
import dev.khoirevanced.runtime.api.MethodHookCallback
import java.lang.reflect.Member

/** JNI facade. ART-version-specific implementations live exclusively in native/. */
object NativeHookBackend : HookBackend {
    override val capabilities: Set<HookCapability>
        get() = nativeCapabilities().mapTo(linkedSetOf()) { HookCapability.entries[it] }

    fun initialize(config: RuntimeConfig) {
        check(nativeInitialize(config.cacheDir)) { "Native ART backend initialization failed" }
    }

    override fun hook(member: Member, callback: MethodHookCallback): HookHandle {
        val token = nativeHook(member, callback)
        check(token != 0L) { "Could not hook $member" }
        return HookHandle { nativeUnhook(token) }
    }

    override fun invokeOriginal(member: Member, receiver: Any?, args: Array<Any?>): Any? =
        nativeInvokeOriginal(member, receiver, args)

    override fun deoptimize(member: Member): Boolean = nativeDeoptimize(member)

    private external fun nativeInitialize(cacheDir: String): Boolean
    private external fun nativeCapabilities(): IntArray
    private external fun nativeHook(member: Member, callback: MethodHookCallback): Long
    private external fun nativeUnhook(token: Long)
    private external fun nativeInvokeOriginal(
        member: Member,
        receiver: Any?,
        args: Array<Any?>,
    ): Any?
    private external fun nativeDeoptimize(member: Member): Boolean
}
