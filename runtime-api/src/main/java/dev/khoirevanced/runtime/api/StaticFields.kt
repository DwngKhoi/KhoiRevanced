package dev.khoirevanced.runtime.api

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Field access that the standalone runtime owns, including `static final`.
 *
 * ## Why this exists
 *
 * Upstream NexAlloy compiles against `de.robv.android.xposed:api:82`, but that
 * artifact is a **stub**: every method body is `throw new RuntimeException("Stub!")`.
 * The real implementation, and in particular the Android 14+/17 handling of
 * writes to `static final` fields, is injected by LSPosed at runtime. Upstream
 * therefore gets that behaviour for free, and commit `97e14d5` ("Remove legacy
 * Xposed compatibility shims") is explicitly about switching to the official API
 * to pick up the "Android 17 static final unmodifiable field workaround".
 *
 * KhoiRevanced has no LSPosed, so it must own that workaround. The Xposed API
 * classes it does ship come from Pine, and Pine's `XposedHelpers` predates the
 * change: it has no `sun.misc.Unsafe` reference and no final-modifier handling,
 * so `setStaticObjectField` on a `static final` field degrades to plain
 * reflection, which ART rejects on newer releases.
 *
 * The three fallbacks below are ordered cheapest-first, and each is skipped
 * silently if unavailable, so behaviour degrades rather than crashing:
 *
 *  1. direct reflective write — always fine for non-final fields;
 *  2. clear `Field.modifiers`' FINAL bit and retry — blocked on Android 14+;
 *  3. `sun.misc.Unsafe` write at the field's memory offset — always available
 *     to app processes, and the only option that survives Android 17.
 */
object StaticFields {

    private val unsafe: Any? by lazy {
        runCatching {
            Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").run {
                isAccessible = true
                get(null)
            }
        }.getOrNull()
    }

    private val objectFieldOffset: Method? by lazy { unsafeMethod("objectFieldOffset", Field::class.java) }
    private val staticFieldBase: Method? by lazy { unsafeMethod("staticFieldBase", Field::class.java) }
    private val putObject: Method? by lazy {
        unsafeMethod("putObject", Any::class.java, java.lang.Long.TYPE, Any::class.java)
    }

    private fun unsafeMethod(name: String, vararg parameters: Class<*>): Method? = runCatching {
        unsafe?.javaClass?.getMethod(name, *parameters)?.apply { isAccessible = true }
    }.getOrNull()

    // region instance fields
    //
    // Parameters are deliberately nullable-tolerant so this is a drop-in
    // replacement for the Java `XposedHelpers` methods it stands in for: the
    // payload's `<T> T.setObjectField(field: String?, ...)` helpers are called
    // with unbounded generics and nullable names, which only compile against
    // platform types. Nullability is rejected at runtime with a clear message.

    fun setObjectField(target: Any?, fieldName: String?, value: Any?) =
        write(requireField(target, fieldName), target, value, value)

    fun setBooleanField(target: Any?, fieldName: String?, value: Boolean) =
        write(requireField(target, fieldName), target, value, java.lang.Boolean.valueOf(value))

    fun setIntField(target: Any?, fieldName: String?, value: Int) =
        write(requireField(target, fieldName), target, value, java.lang.Integer.valueOf(value))

    fun setLongField(target: Any?, fieldName: String?, value: Long) =
        write(requireField(target, fieldName), target, value, java.lang.Long.valueOf(value))

    fun getObjectField(target: Any?, fieldName: String?): Any? =
        requireField(target, fieldName).accessible().get(target)

    // endregion

    // region static fields

    fun setStaticObjectField(target: Class<*>?, fieldName: String?, value: Any?) =
        write(requireField(target, fieldName), null, value, value)

    fun setStaticBooleanField(target: Class<*>?, fieldName: String?, value: Boolean) =
        write(requireField(target, fieldName), null, value, java.lang.Boolean.valueOf(value))

    fun setStaticIntField(target: Class<*>?, fieldName: String?, value: Int) =
        write(requireField(target, fieldName), null, value, java.lang.Integer.valueOf(value))

    fun setStaticLongField(target: Class<*>?, fieldName: String?, value: Long) =
        write(requireField(target, fieldName), null, value, java.lang.Long.valueOf(value))

    fun getStaticObjectField(target: Class<*>?, fieldName: String?): Any? =
        requireField(target, fieldName).accessible().get(null)

    // endregion

    /**
     * Resolve [fieldName] on [owner] or any superclass, honouring shadowing.
     *
     * `Class.getDeclaredField` does not walk the hierarchy, and these helpers
     * are called with obfuscated host names that routinely live on a base class.
     */
    private fun requireField(owner: Any?, fieldName: String?): Field {
        val name = fieldName
            ?: throw NoSuchFieldException("field name must not be null")
        val type: Class<*> = when (owner) {
            null -> throw IllegalArgumentException("owner must not be null")
            is Class<*> -> owner
            else -> owner.javaClass
        }
        var current: Class<*>? = type
        while (current != null) {
            val found = runCatching {
                current.declaredFields.firstOrNull { it.name == name }
            }.getOrNull()
            if (found != null) return found
            current = current.superclass
        }
        throw NoSuchFieldException("${type.name}.$name")
    }

    private fun Field.accessible(): Field = apply { isAccessible = true }

    private fun write(field: Field, target: Any?, value: Any?, boxed: Any?) {
        field.isAccessible = true
        val isStatic = Modifier.isStatic(field.modifiers)
        if (!isStatic && target == null) {
            throw IllegalArgumentException("${field.name} is not static")
        }
        if (isStatic && target != null) {
            throw IllegalArgumentException("${field.name} is static")
        }

        if (!Modifier.isFinal(field.modifiers)) {
            field.set(target, value)
            return
        }

        // `static final`, and ART refuses a plain reflective write on Android 14+.
        if (clearFinalAndRetry(field, target, value)) return
        if (unsafeWrite(field, target, boxed, isStatic)) return
        throw IllegalAccessException(
            "Cannot write final field ${field.declaringClass.name}.${field.name}; " +
                "no usable fallback for this Android release."
        )
    }

    /** Fallback 2: drop the FINAL bit so the reflective write is permitted. */
    private fun clearFinalAndRetry(field: Field, target: Any?, value: Any?): Boolean = runCatching {
        val modifiers = Field::class.java.getDeclaredField("modifiers")
        modifiers.isAccessible = true
        modifiers.setInt(field, modifiers.getInt(field) and Modifier.FINAL.inv())
        field.set(target, value)
        true
    }.getOrDefault(false)

    /** Fallback 3: write the field's memory directly, bypassing the final check. */
    private fun unsafeWrite(field: Field, target: Any?, boxed: Any?, isStatic: Boolean): Boolean {
        val handle = unsafe ?: return false
        val offsetOf = objectFieldOffset ?: return false
        val put = putObject ?: return false
        return runCatching {
            val offset = offsetOf.invoke(handle, field) as Long
            val base = if (isStatic) staticFieldBase?.invoke(handle, field) else target
            put.invoke(handle, base, offset, boxed)
            true
        }.recoverCatching {
            // Some releases fold the static base into the offset itself.
            put.invoke(handle, null, offsetOf.invoke(handle, field) as Long, boxed)
            true
        }.getOrDefault(false)
    }
}
