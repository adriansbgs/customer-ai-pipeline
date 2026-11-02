package com.stara

import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory

/** Anything that can write a reply to a customer. Swap the implementation to change the AI provider. */
interface ReplyGenerator {
    suspend fun generate(history: List<Message>, incoming: String): String
}

const val FALLBACK_REPLY = "Terima kasih, tim kami akan segera menghubungi Anda."

/** System prompt for a real LLM provider. The mock does not need it. */
val SYSTEM_PROMPT: String = object {}.javaClass.getResource("/prompts/system.txt")!!.readText()

// ponytail: mock; a real provider = a new ReplyGenerator implementation + AI_PROVIDER
class MockReplyGenerator : ReplyGenerator {
    override suspend fun generate(history: List<Message>, incoming: String): String =
        if (history.isEmpty()) {
            "Halo, terima kasih sudah menghubungi STARA. Pesan Anda sudah kami terima. " +
                "Tim kami akan membantu Anda secepatnya."
        } else {
            "Terima kasih atas pesan lanjutannya. Kami sedang meninjau percakapan kita sebelumnya " +
                "dan akan segera membalas dengan jawaban yang tepat."
        }
}

private val log = LoggerFactory.getLogger("com.stara.Ai")

/** Asks the AI for a reply. Any failure, timeout or empty answer gives [FALLBACK_REPLY]; it never throws. */
suspend fun replyOrFallback(ai: ReplyGenerator, history: List<Message>, incoming: String, timeoutMs: Long): String =
    try {
        withTimeout(timeoutMs) { ai.generate(history, incoming) }.trim().ifBlank { FALLBACK_REPLY }
    } catch (e: Exception) { // includes TimeoutCancellationException
        log.warn("AI failed, using fallback: ${e.javaClass.simpleName}")
        FALLBACK_REPLY
    }
