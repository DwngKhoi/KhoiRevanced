package dev.khoirevanced.runtime.agent

import java.lang.reflect.Method

/**
 * A real `invokespecial`, delegated to `JNIEnv`'s `CallNonvirtual` family.
 *
 * ## What this is for
 *
 * `SettingsPatch` replaces `LicenseActivity.onCreate` and then calls the
 * superclass's `onCreate` on the same instance. That is `invokespecial`, and
 * neither reflection nor a privileged `MethodHandles.Lookup` is available here:
 * the hidden-API policy is enforced in this process, and Pine cannot lift it on
 * this device because it resolves its six ART hook targets by name and none of
 * them is in the build's `libart.so` `.dynsym`. So the super call degraded into
 * a guarded virtual call, which dispatches back into the replaced method and
 * crashes:
 *
 *     java.lang.NullPointerException: Attempt to read from field
 *       'java.lang.String zzr.a' on a null object reference in method
 *       'void com.google.android.libraries.social.licenses.LicenseActivity.onCreate'
 *
 * The native half ([nativeInvokeSpecial]) is built on the one JNI entry point
 * that means "invoke without dispatch", so it needs neither a privileged lookup
 * nor any knowledge of ART's internal layouts.
 *
 * ## What it costs
 *
 * A JNI call has no local frame of its own beyond the C one, and the callee is
 * reached through a `jmethodID` rather than an `ArtMethod`, so the resolved
 * method is not separately cached. This runs once per settings-screen creation,
 * so that is irrelevant here; it is stated because the alternative reading of
 * "native" is that it is cheap, and for a hot path it is not.
 */
internal object ArtInvoke {

    /**
     * Whether the native half is present.
     *
     * The library is not loaded through `System.loadLibrary`: it lives in the
     * app's `code_cache`, not in the APK's native library directory, and
     * [AgentBootstrap] loads it by absolute path because the injector placed it
     * there. This flag is set by that step rather than guessed at, since a
     * missing native method has to be a degraded super call and not an
     * `UnsatisfiedLinkError` from inside an Activity's `onCreate`.
     */
    @Volatile
    var available: Boolean = false
        private set

    /** Called by [AgentBootstrap] immediately after the agent library is loaded. */
    fun markLoaded() {
        available = true
        RuntimeLog.stage("art-invoke", "CallNonvirtual path active")
    }

    /**
     * Invoke [target]'s implementation directly on [receiver].
     *
     * [target] must be declared by a superclass of [receiver]'s class, which is
     * what makes this a super call. Arguments are boxed by the caller exactly as
     * they would be for [Method.invoke], including `null`s, and an exception
     * thrown by the callee is rethrown here rather than swallowed.
     */
    fun invokeSpecial(receiver: Any, target: Method, args: Array<out Any?>): Any? {
        val declaring = target.declaringClass
            ?: error("invokeSpecial: ${target.name} has no declaring class")
        if (!declaring.isInstance(receiver)) {
            throw IllegalArgumentException(
                "invokeSpecial: ${declaring.name} does not declare ${target.name} for " +
                    "${receiver.javaClass.name}, so there is no super call to make",
            )
        }
        if (!available) {
            throw UnsupportedOperationException(
                "invokeSpecial: the agent's native half is not loaded",
            )
        }
        // A virtual call is what this method exists to avoid, so the check is
        // explicit rather than left to the VM's own error.
        require(receiver.javaClass != declaring) {
            "invokeSpecial: ${declaring.name} is the receiver's own class, which is a " +
                "virtual call rather than a super call"
        }
        return nativeInvokeSpecial(
            receiver,
            declaring,
            target.name,
            target.parameterTypes,
            args,
            target.returnType,
        )
    }

    @Suppress("unused") // Bound from native by class and method name.
    private external fun nativeInvokeSpecial(
        receiver: Any,
        declaringClass: Class<*>,
        name: String,
        parameterTypes: Array<Class<*>>,
        arguments: Array<out Any?>,
        returnType: Class<*>,
    ): Any?
}
