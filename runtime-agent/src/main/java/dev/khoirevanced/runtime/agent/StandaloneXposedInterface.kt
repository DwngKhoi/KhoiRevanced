package dev.khoirevanced.runtime.agent

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.ParcelFileDescriptor
import android.util.Log
import dev.khoirevanced.runtime.api.HookParam
import dev.khoirevanced.runtime.api.HookRuntime
import dev.khoirevanced.runtime.api.MethodHookCallback
import io.github.libxposed.api.XposedInterface
import java.io.File
import java.io.FileNotFoundException
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * The libxposed service object that NexAlloy expects its host framework to
 * provide, implemented in-process.
 *
 * ## Why this exists
 *
 * NexAlloy is written against the modern libxposed API, and its entry class
 * extends `XposedModule`, which extends `XposedInterfaceWrapper`. That wrapper
 * starts with no framework attached and every delegated call goes through
 * `ensureAttached()`, which throws
 *
 *     IllegalStateException: Framework not attached
 *
 * until a real Xposed daemon calls `attachFramework`. There is no daemon here,
 * so the service has to be provided. `XposedInterface` is a service interface of
 * twelve methods, not a hook API, and this class is that implementation.
 *
 * Which of them NexAlloy actually needs is narrow. `SettingsPatch` calls
 * `xposed.getInvoker(...)` for `Activity.onCreate` and then
 * `invokeSpecial(activity, args)`, which is a super call: it has to reach the
 * superclass implementation because the subclass's own `onCreate` is being
 * replaced by the very same patch. Without it the settings screen integration
 * fails to apply and nothing else about that patch runs.
 *
 * The rest is here so nothing is a landmine. The `hook` family is routed to the
 * same [HookRuntime] backend the legacy `XposedBridge` path uses, and
 * `getRemotePreferences` returns real preferences rather than a Binder proxy,
 * which is what makes remote preference reads work in-process.
 */
object StandaloneXposedInterface : XposedInterface {

    private const val TAG = "KhoiRevanced"
    private const val FRAMEWORK_NAME = "KhoiRevanced"
    private const val FRAMEWORK_VERSION = "1.0"

    /** Set once the host Application exists; several services need it. */
    @Volatile
    var context: Context? = null
        internal set

    @Volatile
    var hostApplicationInfo: ApplicationInfo? = null
        internal set

    /** Directory the payload was staged in, for [listRemoteFiles] / [openRemoteFile]. */
    @Volatile
    var payloadDirectory: File? = null
        internal set

    private val preferences = mutableMapOf<String, SharedPreferences>()

    override fun getApiVersion(): Int = XposedInterface.API_102

    override fun getFrameworkName(): String = FRAMEWORK_NAME

    override fun getFrameworkVersion(): String = FRAMEWORK_VERSION

    override fun getFrameworkVersionCode(): Long = 1L

    /** Nothing here is served over Binder, so there are no capabilities to advertise. */
    override fun getFrameworkProperties(): Long = 0L

    override fun log(priority: Int, tag: String?, message: String) {
        Log.println(priority, tag ?: FRAMEWORK_NAME, message)
    }

    override fun log(priority: Int, tag: String?, message: String, throwable: Throwable?) {
        Log.println(
            priority,
            tag ?: FRAMEWORK_NAME,
            if (throwable == null) message else "$message\n${Log.getStackTraceString(throwable)}",
        )
    }

    override fun getModuleApplicationInfo(): ApplicationInfo =
        hostApplicationInfo
            ?: context?.applicationInfo
            ?: error("No ApplicationInfo is available yet")

    override fun getRemotePreferences(name: String): SharedPreferences {
        preferences[name]?.let { return it }
        val context = context ?: error("No context for remote preferences: $name")
        return context.getSharedPreferences(name, Context.MODE_PRIVATE)
            .also { preferences[name] = it }
    }

    override fun listRemoteFiles(): Array<String> {
        val directory = payloadDirectory ?: return emptyArray()
        return directory.listFiles()?.map(File::getName)?.toTypedArray() ?: emptyArray()
    }

    override fun openRemoteFile(name: String): ParcelFileDescriptor {
        val directory = payloadDirectory
            ?: throw FileNotFoundException("No payload directory for $name")
        val file = File(directory, name)
        if (!file.isFile) throw FileNotFoundException("No such payload file: $name")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /**
     * Pine has no deoptimisation entry point, so this reports failure rather than
     * pretending. Callers treat a false result as "not available"; the one NexAlloy
     * patch that asks, `CheckRecycleBitmapMediaSession`, then leaves the method
     * compiled instead of forcing a re-resolution.
     */
    override fun deoptimize(executable: Executable): Boolean = false

    override fun hook(executable: Executable): XposedInterface.HookBuilder =
        StandaloneHookBuilder(executable)

    /**
     * Pine hooks methods and constructors, not class initialisers. There is no
     * Member to hand it, so the builder is returned with a target that cannot be
     * intercepted and the failure is raised, and logged, only if a caller
     * actually tries.
     */
    override fun hookClassInitializer(clazz: Class<*>): XposedInterface.HookBuilder =
        StandaloneHookBuilder(null, clazz)

    override fun getInvoker(method: Method): XposedInterface.Invoker<*, Method> =
        MethodInvoker(method)

    override fun <T : Any> getInvoker(constructor: Constructor<T>): XposedInterface.CtorInvoker<T> =
        ConstructorInvoker(constructor)

    /**
     * A method invoker whose `invokeSpecial` is a super call.
     *
     * `invoke` is an ordinary virtual call. `invokeSpecial` has to reach the
     * superclass implementation of [method] on the receiver, which is what
     * `invokespecial` does in bytecode: skip the receiver's own class, find the
     * nearest superclass declaring the same name and parameters, and invoke that.
     * Reflection can do this directly, with no ART support needed.
     */
    private class MethodInvoker(
        private val method: Method,
    ) : XposedInterface.Invoker<MethodInvoker, Method> {

        override fun setType(type: XposedInterface.Invoker.Type): MethodInvoker = this

        override fun invoke(receiver: Any, vararg args: Any?): Any? {
            method.isAccessible = true
            return method.invoke(receiver, *args)
        }

        /**
         * A genuine `invokespecial` on the superclass implementation.
         *
         * Reflection alone cannot do this. `Method.invoke` performs virtual
         * dispatch, so invoking the superclass's `onCreate` on a LicenseActivity
         * calls `LicenseActivity.onCreate` -- the very method this patch replaced
         * -- which re-enters the hook and recurses until the stack overflows.
         * That is exactly what happened before this was fixed.
         *
         * `unreflectSpecial` is the supported way to bypass that dispatch, but
         * obtaining it requires a `Lookup` with privileges over the declaring
         * class, and `privateLookupIn` is refused for framework classes on
         * Android. `MethodHandles.Lookup.IMPL_LOOKUP` has those privileges, so
         * it is read out through `sun.misc.Unsafe` the same way StaticFields
         * already does for static final writes.
         */
        override fun invokeSpecial(receiver: Any, vararg args: Any?): Any? {
            val target = resolveSuperMethod(method, receiver) ?: method
            val special = specialInvoker(target, receiver)
            if (special != null) {
                target.isAccessible = true
                return if (args.isEmpty()) special.invokeWithArguments()
                else special.invokeWithArguments(*args)
            }
            // No privileged Lookup. Fall back to a single-level call guarded
            // against re-entry, which degrades the patch instead of looping.
            if (!Reentrancy.enter(target)) {
                throw IllegalStateException(
                    "Re-entered super call for ${target.declaringClass?.name}.${target.name} " +
                        "and no privileged Lookup is available to bypass dispatch",
                )
            }
            try {
                target.isAccessible = true
                return target.invoke(receiver, *args)
            } finally {
                Reentrancy.exit(target)
            }
        }
    }

    /**
     * Per-thread guard against a super call re-entering itself. A stack overflow
     * inside an Activity's onCreate is not a recoverable failure, so the fallback
     * path refuses to recurse rather than trying again.
     */
    private object Reentrancy {
        private val active = ThreadLocal.withInitial { mutableSetOf<Method>() }

        fun enter(method: Method): Boolean = active.get()!!.add(method)
        fun exit(method: Method) { active.get()!!.remove(method) }
    }

    /**
     * Build an invokespecial bound handle for [target] on [receiver], or null
     * when the privileged `IMPL_LOOKUP` cannot be obtained on this runtime.
     */
    private fun specialInvoker(target: Method, receiver: Any): MethodHandle? {
        val implLookup = privilegedLookup ?: return null
        return runCatching {
            target.isAccessible = true
            // specialCaller must be the subclass whose super call this is, which
            // is the receiver's own class.
            implLookup.unreflectSpecial(target, receiver.javaClass).bindTo(receiver)
        }.getOrElse { error ->
            RuntimeLog.warn("invoker", "unreflectSpecial failed: ${error.javaClass.simpleName}: ${error.message}")
            null
        }
    }

    /**
     * `MethodHandles.Lookup.IMPL_LOOKUP`, read through `sun.misc.Unsafe`.
     * Null on a runtime that refuses, in which case the caller degrades.
     */
    private val privilegedLookup: MethodHandles.Lookup? by lazy {
        runCatching {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafe = unsafeClass.getDeclaredField("theUnsafe").run {
                isAccessible = true
                get(null)
            }
            val lookupClass = Class.forName("java.lang.invoke.MethodHandles\$Lookup")
            val implField = lookupClass.getDeclaredField("IMPL_LOOKUP").apply { isAccessible = true }
            val base = unsafeClass.getMethod("staticFieldBase", Field::class.java)
                .invoke(unsafe, implField) as java.lang.reflect.Field
            val offset = unsafeClass.getMethod("staticFieldOffset", Field::class.java)
                .invoke(unsafe, implField) as Long
            val getObject = unsafeClass.getMethod(
                "getObject", java.lang.Object::class.java, java.lang.Long.TYPE,
            )
            getObject.invoke(unsafe, base, offset) as MethodHandles.Lookup
        }.getOrElse { error ->
            RuntimeLog.warn("invoker", "IMPL_LOOKUP unavailable: ${error.javaClass.simpleName}: ${error.message}")
            null
        }
    }

    /**
     * A constructor invoker. `newInstance` is plain construction;
     * `newInstanceSpecial(clazz, args)` is the super-constructor call, so it
     * looks the signature up on [clazz] rather than on the declaring class.
     */
    private class ConstructorInvoker<T : Any>(
        private val constructor: Constructor<T>,
    ) : XposedInterface.CtorInvoker<T> {

        override fun setType(type: XposedInterface.Invoker.Type): XposedInterface.CtorInvoker<T> = this

        override fun newInstance(vararg args: Any?): T {
            constructor.isAccessible = true
            return constructor.newInstance(*args)
        }

        override fun <U : Any> newInstanceSpecial(clazz: Class<U>, vararg args: Any?): U {
            val parameters = constructor.parameterTypes
            val found = clazz.declaredConstructors.firstOrNull { candidate ->
                candidate.parameterTypes.contentEquals(parameters)
            } ?: throw NoSuchMethodException("No matching constructor on ${clazz.name}")
            found.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            return found.newInstance(*args) as U
        }

        // Present because Invoker declares it; a constructor has no receiver to
        // dispatch on, so construction is the only meaningful operation.
        override fun invoke(receiver: Any, vararg args: Any?): Any? = newInstance(*args)

        override fun invokeSpecial(receiver: Any, vararg args: Any?): Any? =
            error("A constructor invoker has no receiver to invoke it on")
    }

    /**
     * Locate the implementation [method] resolves to once virtual dispatch is
     * skipped, searching upwards from the receiver's class.
     *
     * When [method] is already declared by a superclass there is nothing to find
     * and it is returned unchanged. Returns null only when no superclass declares
     * the signature and the receiver's own class does not either.
     */
    private fun resolveSuperMethod(method: Method, receiver: Any): Method? {
        val receiverClass = receiver.javaClass
        if (method.declaringClass != receiverClass &&
            method.declaringClass.isAssignableFrom(receiverClass)
        ) {
            return method
        }
        val parameters = method.parameterTypes
        var type: Class<*>? = receiverClass.superclass
        while (type != null && type != Any::class.java) {
            val candidate = type.declaredMethods.firstOrNull { declared ->
                declared.name == method.name &&
                    declared.parameterTypes.contentEquals(parameters) &&
                    !Modifier.isPrivate(declared.modifiers)
            }
            if (candidate != null) return candidate
            type = type.superclass
        }
        return null
    }

    /**
     * The modern hook entry point, routed to the same backend the legacy
     * `XposedBridge` path uses so both APIs share one interception per member.
     */
    private class StandaloneHookBuilder(
        private val executable: Executable?,
        private val clazz: Class<*>? = null,
    ) : XposedInterface.HookBuilder {

        private var id: String? = null

        override fun setPriority(priority: Int): XposedInterface.HookBuilder = this

        override fun setExceptionMode(mode: XposedInterface.ExceptionMode): XposedInterface.HookBuilder = this

        override fun setId(id: String?): XposedInterface.HookBuilder = this.also { this.id = id }

        override fun intercept(hooker: XposedInterface.Hooker): XposedInterface.HookHandle {
            val target = executable
                ?: error("Pine cannot hook the initialiser of ${clazz?.name}: it hooks members only")
            val member = target as Member
            val handle = HookRuntime.backend.hook(member, object : MethodHookCallback() {
                override fun before(param: HookParam) {
                    // The original runs first so the hooker observes a resolved
                    // result, and the same resolved value is what `after` sees.
                    val resolved = HookRuntime.backend.invokeOriginal(member, param.receiver, param.args)
                    param.result = runHook(hooker, member, param, resolved)
                    param.returnEarly = true
                }

                override fun after(param: HookParam) {
                    param.result = runHook(hooker, member, param, param.result)
                }
            })
            RuntimeLog.info("hook", "modern ${member.declaringClass?.name}.${member.name} id=$id")
            return StandaloneHookHandle(target, handle, hooker)
        }
    }

    private fun runHook(
        hooker: XposedInterface.Hooker,
        member: Member,
        param: HookParam,
        resolved: Any?,
    ): Any? = try {
        hooker.intercept(DirectChain(member, param.receiver, param.args, resolved))
    } catch (error: Throwable) {
        RuntimeLog.error("hook", "${member.name} interceptor threw", error)
        throw error
    }

    /** Minimal [XposedInterface.Chain] over an already-resolved result. */
    private class DirectChain(
        private val executable: Member,
        private val receiver: Any?,
        private val args: Array<Any?>,
        private val result: Any?,
    ) : XposedInterface.Chain {
        override fun getExecutable(): Executable = executable as Executable
        override fun getThisObject(): Any? = receiver
        override fun getArgs(): List<Any?> = args.toList()

        override fun getArg(index: Int): Any? {
            if (index !in args.indices) throw IndexOutOfBoundsException("arg $index of ${args.size}")
            return args[index]
        }

        // The original has already run by the time a hooker is entered, so every
        // proceed variant returns the same resolved value. Overriding the receiver
        // or arguments here would have no effect: the call is over.
        override fun proceed(): Any? = result
        override fun proceed(newArgs: Array<out Any?>): Any? = result
        override fun proceedWith(thisObject: Any): Any? = result
        override fun proceedWith(thisObject: Any, newArgs: Array<out Any?>): Any? = result
    }

    private class StandaloneHookHandle(
        private val executable: Executable,
        private val handle: dev.khoirevanced.runtime.api.HookHandle,
        private val hooker: XposedInterface.Hooker,
    ) : XposedInterface.HookHandle {
        override fun getExecutable(): Executable = executable
        override fun unhook() = handle.unhook()
        override fun getId(): String = executable.name

        override fun replaceHook(hooker: XposedInterface.Hooker): XposedInterface.HookHandle {
            handle.unhook()
            return StandaloneHookBuilder(executable).intercept(hooker)
        }
    }
}
