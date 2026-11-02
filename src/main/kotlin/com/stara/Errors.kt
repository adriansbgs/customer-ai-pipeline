package com.stara

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.header
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import org.postgresql.util.PSQLException
import org.slf4j.LoggerFactory

/** Thrown anywhere in a handler to answer with [status] and {"error": message}. */
class ApiException(val status: HttpStatusCode, override val message: String) : RuntimeException(message)

@Serializable
data class ErrorResponse(val error: String)

private val log = LoggerFactory.getLogger("com.stara.Errors")

fun Application.installErrorHandling() {
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respond(e.status, ErrorResponse(e.message)) }
        // Malformed JSON or wrong field types end up here.
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid JSON body"))
        }
        exception<PSQLException> { call, e ->
            when (e.sqlState) {
                "23505" -> call.respond(HttpStatusCode.Conflict, ErrorResponse("phone already exists"))
                "23514" -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid data"))
                else -> {
                    log.error("Database error", e)
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal server error"))
                }
            }
        }
        // Never leak stack traces or SQL details to the client.
        exception<Throwable> { call, e ->
            log.error("Unhandled error", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal server error"))
        }
    }
}

private val SecurityHeaders = createApplicationPlugin("SecurityHeaders") {
    onCall { call ->
        call.response.header("Content-Security-Policy", "default-src 'self'")
        call.response.header("X-Content-Type-Options", "nosniff")
    }
}

fun Application.installSecurityHeaders() {
    install(SecurityHeaders)
}
