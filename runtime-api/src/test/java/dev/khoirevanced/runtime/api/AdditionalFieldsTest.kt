package dev.khoirevanced.runtime.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdditionalFieldsTest {
    @Test
    fun storesValuesPerObject() {
        val first = Any()
        val second = Any()
        AdditionalFields.put(first, "key", 7)
        assertEquals(7, AdditionalFields[first, "key"])
        assertNull(AdditionalFields[second, "key"])
        assertEquals(7, AdditionalFields.remove(first, "key"))
    }
}
