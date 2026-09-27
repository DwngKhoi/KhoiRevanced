package dev.khoirevanced.runtime.agent

import dev.khoirevanced.runtime.api.HookBackend
import dev.khoirevanced.runtime.api.HookCapability
import dev.khoirevanced.runtime.api.HookHandle
import dev.khoirevanced.runtime.api.MethodHookCallback
import java.lang.reflect.Member

/** JNI facade for the LSPlant ART backend packaged with the standalone runtime. */
object NativeHookBackend : HookBackend {
    override val capabilities: Set<HookCapability>
        get() = nativeCapabilities().mapTo(linkedSetOf()) { ordinal ->
            HookCapability.entries.getOrElse(ordinal) {
                error("Native hook backend returned invalid capability ordinal: $ordinal")
            }
        }

    fun initialize(config: RuntimeConfig) {
        check(nativeInitialize(config.cacheDir)) { "LSPlant ART backend initialization failed" }
        check(HookCapability.METHOD in capabilities && HookCapability.INVOKE_ORIGINAL in capabilities) {
            "LSPlant ART backend started without required capabilities: $capabilities"
        }
    }

    override fun hook(member: Member, callback: MethodHookCallback): HookHandle {
        val token = nativeHook(member, LsplantHookDispatcher(member, callback))
        check(token != 0L) { "LSPlant could not hook $member" }
        return HookHandle { nativeUnhook(token) }
    }

    override fun invokeOriginal(member: Member, receiver: Any?, args: Array<Any?>): Any? =
        nativeInvokeOriginal(member, receiver, args)

    override fun deoptimize(member: Member): Boolean = nativeDeoptimize(member)

    private external fun nativeInitialize(cacheDir: String): Boolean
    private external fun nativeCapabilities(): IntArray
    private external fun nativeHook(member: Member, dispatcher: Any): Long
    private external fun nativeUnhook(token: Long)
    private external fun nativeInvokeOriginal(
        member: Member,
        receiver: Any?,
        args: Array<Any?>,
    ): Any?
    private external fun nativeDeoptimize(member: Member): Boolean
}