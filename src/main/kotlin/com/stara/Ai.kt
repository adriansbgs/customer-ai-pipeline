package com.stara

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory

/** Anything that can write a reply to a customer. Swap the implementation to change the AI provider. */
interface ReplyGenerator {
    suspend fun generate(history: List<Message>, incoming: String): String
}

const val FALLBACK_REPLY = "Terima kasih, tim kami akan segera menghubungi Anda."

/** Same limit as messages.content in schema.sql. */
const val MAX_MESSAGE_LENGTH = 2000

/** System prompt for a real LLM provider. The mock does not need it. */
val SYSTEM_PROMPT: String = object {}.javaClass.getResource("/prompts/system.txt")!!.readText()

// ponytail: mock; a real provider = a new ReplyGenerator implementation + AI_PROVIDER
class MockReplyGenerator : ReplyGenerator {
    // First matching topic wins; keywords are checked against the lowercase message.
    private val topics = listOf(
        listOf("harga", "biaya", "tarif") to "Untuk informasi harga, kami punya beberapa paket layanan yang bisa disesuaikan " +
            "dengan kebutuhan Anda. Tim kami akan mengirimkan rincian paketnya segera.",
        listOf("jam", "buka", "tutup") to "Kami melayani Senin sampai Jumat, pukul 09:00 sampai 17:00 WIB. " +
            "Silakan hubungi kami kembali pada jam tersebut.",
        listOf("komplain", "kecewa", "rusak", "lambat") to "Mohon maaf atas ketidaknyamanan yang Anda alami. " +
            "Keluhan Anda sudah kami teruskan ke tim terkait dan akan segera kami tindak lanjuti.",
    )

    override suspend fun generate(history: List<Message>, incoming: String): String {
        val text = incoming.lowercase()
        topics.firstOrNull { (words, _) -> words.any { it in text } }?.let { return it.second }
        return if (history.isEmpty()) {
            "Halo, terima kasih sudah menghubungi STARA. Pesan Anda sudah kami terima. " +
                "Tim kami akan membantu Anda secepatnya."
        } else {
            "Terima kasih atas pesan lanjutannya. Kami sedang meninjau percakapan kita sebelumnya " +
                "dan akan segera membalas dengan jawaban yang tepat."
        }
    }
}

/** Demo providers: show the fallback reply working without breaking anything. */
private class FailingGenerator : ReplyGenerator {
    override suspend fun generate(history: List<Message>, incoming: String): String = error("demo failure")
}

private class SlowGenerator : ReplyGenerator {
    override suspend fun generate(history: List<Message>, incoming: String): String {
        delay(60_000)
        return "terlambat"
    }
}

val AI_PROVIDERS = listOf("mock", "mock-slow", "mock-error")

fun aiFor(provider: String): ReplyGenerator = when (provider) {
    "mock-slow" -> SlowGenerator()
    "mock-error" -> FailingGenerator()
    else -> MockReplyGenerator()
}

private val log = LoggerFactory.getLogger("com.stara.Ai")

/**
 * Asks the AI for a reply. Any failure, timeout, empty answer or answer too long to store
 * gives [FALLBACK_REPLY]; it never throws.
 */
suspend fun replyOrFallback(ai: ReplyGenerator, history: List<Message>, incoming: String, timeoutMs: Long): String =
    try {
        withTimeout(timeoutMs) { ai.generate(history, incoming) }.trim()
            .takeIf { it.isNotEmpty() && it.length <= MAX_MESSAGE_LENGTH } ?: FALLBACK_REPLY
    } catch (e: Exception) { // includes TimeoutCancellationException
        log.warn("AI failed, using fallback: ${e.javaClass.simpleName}")
        FALLBACK_REPLY
    }
