package com.stara

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfigTest {
    private val base = mapOf(
        "DATABASE_URL" to "jdbc:postgresql://localhost:5432/stara",
        "WEBHOOK_SECRET" to "w",
        "ADMIN_API_KEY" to "a",
    )

    @Test
    fun `applies defaults`() {
        val c = loadConfig(base)
        assertEquals(8080, c.port)
        assertEquals("mock", c.aiProvider)
        assertEquals(8000L, c.aiTimeoutMs)
    }

    @Test
    fun `refuses to start without secrets`() {
        val e = assertFailsWith<IllegalStateException> { loadConfig(base - "WEBHOOK_SECRET") }
        assertContains(e.message!!, "WEBHOOK_SECRET")
        assertFailsWith<IllegalStateException> { loadConfig(base + ("ADMIN_API_KEY" to " ")) }
    }

    @Test
    fun `rejects unknown ai provider`() {
        assertFailsWith<IllegalStateException> { loadConfig(base + ("AI_PROVIDER" to "gemini")) }
    }

    @Test
    fun `reads dotenv and lets real env win`() {
        val file = kotlin.io.path.createTempFile().toFile().apply {
            writeText("# komentar\nFOO_STARA_TEST=dari-file\n\nPATH=palsu\n")
            deleteOnExit()
        }
        val env = readEnv(file)
        assertEquals("dari-file", env["FOO_STARA_TEST"])
        assertEquals(System.getenv("PATH"), env["PATH"])
    }
}
