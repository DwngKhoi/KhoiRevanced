package dev.khoirevanced.runtime.agent

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class RuntimeConfigTest {
    @Test
    fun parsesControllerFormat() {
        val file = File.createTempFile("khoirevanced", ".conf")
        file.writeText(
            """
            dex=/data/user/0/test/payload.dex
            cache=/data/user/0/test/cache
            agent=/data/user/0/test/libagent.so
            package=example.test
            profile=test
            cache_dir=/data/user/0/test/cache
            application_timeout_ms=123
            """.trimIndent()
        )
        val config = RuntimeConfig.parse(file)
        assertEquals("example.test", config.packageName)
        assertEquals("test", config.profile)
        assertEquals("/data/user/0/test/libagent.so", config.agentPath)
        assertEquals(123, config.applicationTimeoutMs)
    }
}
