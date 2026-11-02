package com.stara

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

fun main() {
    val config = loadConfig(readEnv())
    val db = Db(config.databaseUrl, config.dbUser, config.dbPassword)
    val ai = aiFor(config.aiProvider)
    embeddedServer(Netty, port = config.port) { module(config, db, ai) }.start(wait = true)
}

fun Application.module(config: Config, db: Db, ai: ReplyGenerator) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; explicitNulls = false })
    }
    installErrorHandling()
    installSecurityHeaders()
    routing {
        customerRoutes(db, config.adminApiKey)
        webhookRoutes(db, ai, config)
        staticResources("/", "static")
    }
}
