package com.stara

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

fun msg(content: String, direction: String = "incoming") = Message(1, 1, direction, content, "2026-01-01T00:00:00Z")

class AiTest {
    private fun sentences(s: String) = s.split(Regex("[.!?]+")).count { it.isNotBlank() }

    @Test
    fun `mock replies politely in at most three sentences`() = runBlocking {
        val first = MockReplyGenerator().generate(emptyList(), "Halo")
        val followUp = MockReplyGenerator().generate(listOf(msg("halo")), "lagi")
        assertTrue(sentences(first) in 1..3, first)
        assertTrue(sentences(followUp) in 1..3, followUp)
        assertNotEquals(first, followUp)
    }

    @Test
    fun `failure falls back`() = runBlocking {
        val failing = object : ReplyGenerator {
            override suspend fun generate(history: List<Message>, incoming: String): String = error("boom")
        }
        assertEquals(FALLBACK_REPLY, replyOrFallback(failing, emptyList(), "x", 1000))
    }

    @Test
    fun `slow ai falls back after timeout`() = runBlocking {
        val slow = object : ReplyGenerator {
            override suspend fun generate(history: List<Message>, incoming: String): String {
                delay(5_000)
                return "late"
            }
        }
        assertEquals(FALLBACK_REPLY, replyOrFallback(slow, emptyList(), "x", 100))
    }

    @Test
    fun `blank reply falls back`() = runBlocking {
        val blank = object : ReplyGenerator {
            override suspend fun generate(history: List<Message>, incoming: String) = "  "
        }
        assertEquals(FALLBACK_REPLY, replyOrFallback(blank, emptyList(), "x", 1000))
    }

    @Test
    fun `system prompt is loaded`() {
        assertContains(SYSTEM_PROMPT, "STARA")
    }
}
