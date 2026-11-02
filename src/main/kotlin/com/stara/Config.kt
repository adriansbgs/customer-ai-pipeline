package com.stara

import java.io.File

data class Config(
    val port: Int,
    val databaseUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val aiProvider: String,
    val aiApiKey: String,
    val aiTimeoutMs: Long,
    val webhookSecret: String,
    val adminApiKey: String,
)

fun loadConfig(env: Map<String, String>): Config {
    fun required(name: String): String =
        env[name]?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("$name must be set (see .env.example)")

    val aiProvider = env["AI_PROVIDER"]?.takeIf { it.isNotBlank() } ?: "mock"
    if (aiProvider !in AI_PROVIDERS) throw IllegalStateException("AI_PROVIDER '$aiProvider' is not supported, use one of $AI_PROVIDERS")

    return Config(
        port = env["PORT"]?.toIntOrNull() ?: 8080,
        databaseUrl = required("DATABASE_URL"),
        dbUser = env["DB_USER"] ?: "stara",
        dbPassword = env["DB_PASSWORD"] ?: "",
        aiProvider = aiProvider,
        aiApiKey = env["AI_API_KEY"] ?: "",
        aiTimeoutMs = env["AI_TIMEOUT_MS"]?.toLongOrNull() ?: 8000L,
        webhookSecret = required("WEBHOOK_SECRET"),
        adminApiKey = required("ADMIN_API_KEY"),
    )
}

/** Values from a .env file (if present), overridden by real environment variables. */
fun readEnv(dotenv: File = File(".env")): Map<String, String> {
    val fromFile = if (dotenv.exists()) {
        dotenv.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
            .associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }
    } else {
        emptyMap()
    }
    return fromFile + System.getenv()
}
