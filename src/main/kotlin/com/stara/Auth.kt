package com.stara

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import java.security.MessageDigest

/** Constant-time comparison so the response time does not reveal how much of the secret was right. */
fun secretMatches(expected: String, provided: String?): Boolean =
    provided != null && MessageDigest.isEqual(expected.toByteArray(), provided.toByteArray())

class SecretHeaderConfig {
    var headerName: String = ""
    var secret: String = ""
}

/** Answers 401 before the route handler runs unless the header carries the secret. */
private val SecretHeader = createRouteScopedPlugin("SecretHeader", ::SecretHeaderConfig) {
    val headerName = pluginConfig.headerName
    val secret = pluginConfig.secret
    onCall { call ->
        if (!secretMatches(secret, call.request.headers[headerName])) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized"))
        }
    }
}

fun Route.requireSecret(headerName: String, secret: String) {
    install(SecretHeader) {
        this.headerName = headerName
        this.secret = secret
    }
}
