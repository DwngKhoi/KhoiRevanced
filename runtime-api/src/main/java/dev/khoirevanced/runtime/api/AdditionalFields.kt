package dev.khoirevanced.runtime.api

import java.util.Collections
import java.util.WeakHashMap

/** Xposed additional-instance-field equivalent without retaining host objects. */
object AdditionalFields {
    private val values = Collections.synchronizedMap(
        WeakHashMap<Any, MutableMap<String, Any?>>()
    )

    operator fun get(instance: Any, key: String): Any? = synchronized(values) {
        values[instance]?.get(key)
    }

    fun put(instance: Any, key: String, value: Any?): Any? = synchronized(values) {
        values.getOrPut(instance) { HashMap() }.put(key, value)
    }

    fun remove(instance: Any, key: String): Any? = synchronized(values) {
        values[instance]?.remove(key)
    }
}
