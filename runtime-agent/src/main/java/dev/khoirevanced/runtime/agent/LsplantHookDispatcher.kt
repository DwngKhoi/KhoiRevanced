package dev.khoirevanced.runtime.agent

import dev.khoirevanced.runtime.api.HookParam
import dev.khoirevanced.runtime.api.MethodHookCallback
import java.lang.reflect.Member
import java.lang.reflect.Modifier

/**
 * Callback object passed to LSPlant for one hooked member.
 *
 * LSPlant requires a public `Object callback(Object[] args)` method. Keeping the
 * callback policy in Kotlin makes the native layer responsible only for ART
 * interception and backup-method lifetime management.
 */
internal class LsplantHookDispatcher(
    private val member: Member,
    private val callback: MethodHookCallback,
) {
    @Suppress("unused")
    fun callback(arguments: Array<Any?>): Any? {
        val isStatic = Modifier.isStatic(member.modifiers)
        val receiver = if (isStatic) null else arguments.firstOrNull()
        val methodArgs = if (isStatic) arguments else arguments.copyOfRange(1, arguments.size)
        val param = HookParam(member, receiver, methodArgs)

        callback.before(param)
        if (!param.returnEarly) {
            try {
                param.result = NativeHookBackend.invokeOriginal(member, param.receiver, param.args)
            } catch (error: Throwable) {
                param.throwable = error
            }
        }
        callback.after(param)

        param.throwable?.let { throw it }
        return param.result
    }
}