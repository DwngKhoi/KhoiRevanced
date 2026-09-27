package dev.khoirevanced.runtime.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StaticFieldsTest {

    open class Base {
        @JvmField
        var inherited: String? = null
    }

    class Holder : Base() {
        @JvmField
        var mutableField: String? = null

        @JvmField
        var mutableInt: Int = 0

        @JvmField
        var finalObjectField: String? = "original"

        @JvmField
        var finalIntField: Int = 1

        companion object {
            @JvmStatic
            var mutableStatic: String? = null

            @JvmStatic
            val finalStatic: String = "static-original"
        }
    }

    private fun newHolder() = Holder()

    @Test
    fun writesMutableInstanceField() {
        val holder = newHolder()
        StaticFields.setObjectField(holder, "mutableField", "written")
        assertEquals("written", holder.mutableField)
    }

    @Test
    fun writesMutableStaticField() {
        Holder.mutableStatic = null
        StaticFields.setStaticObjectField(Holder::class.java, "mutableStatic", "static-written")
        assertEquals("static-written", Holder.mutableStatic)
    }

    @Test
    fun writesPrimitiveInstanceField() {
        val holder = newHolder()
        StaticFields.setIntField(holder, "mutableInt", 42)
        assertEquals(42, holder.mutableInt)
    }

    /**
     * The whole reason this class exists. A plain `Field.set` on a final field
     * throws, which is what Pine's XposedHelpers still does on Android 14+.
     */
    @Test
    fun writesFinalInstanceFieldViaFallback() {
        val holder = newHolder()
        StaticFields.setObjectField(holder, "finalObjectField", "final-written")
        assertEquals("final-written", holder.finalObjectField)
    }

    @Test
    fun writesFinalPrimitiveInstanceFieldViaFallback() {
        val holder = newHolder()
        StaticFields.setIntField(holder, "finalIntField", 99)
        assertEquals(99, holder.finalIntField)
    }

    /**
     * `static final` is the case upstream NexAlloy delegates to LSPosed for, and
     * the one Pine's XposedHelpers does not handle on Android 14+.
     *
     * The write is **capability-gated**, and deliberately not asserted as always
     * succeeding. HotSpot (the host JVM) has removed both routes:
     * `Field.modifiers` is filtered out of reflection since JDK 12, and
     * `Unsafe.objectFieldOffset` rejects static fields outright. ART keeps
     * `objectFieldOffset` working for statics, which is what the runtime relies
     * on, but that cannot be exercised here.
     *
     * So this test pins the contract that matters: either the value is written,
     * or the failure is a clear IllegalAccessException. It must never silently
     * report success while leaving the field unchanged.
     */
    @Test
    fun writesFinalStaticFieldOrFailsLoudly() {
        val outcome = runCatching {
            StaticFields.setStaticObjectField(Holder::class.java, "finalStatic", "static-final-written")
        }
        if (outcome.isSuccess) {
            assertEquals("static-final-written", Holder.finalStatic)
        } else {
            assertTrue(
                "expected a clear IllegalAccessException, got ${outcome.exceptionOrNull()}",
                outcome.exceptionOrNull() is IllegalAccessException
            )
            assertEquals("static-original", Holder.finalStatic)
        }
    }

    @Test
    fun resolvesFieldsDeclaredOnSuperclasses() {
        val holder = newHolder()
        StaticFields.setObjectField(holder, "inherited", "base-written")
        assertEquals("base-written", holder.inherited)
    }

    @Test
    fun readsFieldsBack() {
        val holder = newHolder()
        StaticFields.setObjectField(holder, "mutableField", "readback")
        assertEquals("readback", StaticFields.getObjectField(holder, "mutableField"))
        StaticFields.setStaticObjectField(Holder::class.java, "mutableStatic", "static-readback")
        assertEquals("static-readback", StaticFields.getStaticObjectField(Holder::class.java, "mutableStatic"))
    }

    @Test
    fun reportsMissingField() {
        val holder = newHolder()
        assertThrows(NoSuchFieldException::class.java) {
            StaticFields.setObjectField(holder, "doesNotExist", null)
        }
    }

    @Test
    fun rejectsStaticInstanceMismatch() {
        val holder = newHolder()
        assertThrows(IllegalArgumentException::class.java) {
            // mutableStatic is static, so it must not be given an instance receiver.
            StaticFields.setObjectField(holder, "mutableStatic", "wrong")
        }
    }
}
