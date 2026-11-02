package com.stara

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

val STATUSES = listOf("lead", "active", "inactive")

@Serializable
data class CustomerInput(val name: String? = null, val phone: String? = null, val status: String? = null)

/** Returns (name, normalized phone, status) or throws a 400 that names the bad field. */
fun validateCustomer(input: CustomerInput, defaultStatus: String): Triple<String, String, String> {
    val name = input.name?.trim().orEmpty()
    if (name.isEmpty()) throw ApiException(HttpStatusCode.BadRequest, "name is required")
    if (name.length > 100) throw ApiException(HttpStatusCode.BadRequest, "name must be at most 100 characters")
    val phone = normalizePhone(input.phone) ?: throw ApiException(HttpStatusCode.BadRequest, "phone is invalid")
    val status = input.status ?: defaultStatus
    if (status !in STATUSES) throw ApiException(HttpStatusCode.BadRequest, "status must be one of lead, active, inactive")
    return Triple(name, phone, status)
}

private fun ApplicationCall.customerId(): Long =
    parameters["id"]?.toLongOrNull() ?: throw ApiException(HttpStatusCode.BadRequest, "invalid id")

private fun notFound(): Nothing = throw ApiException(HttpStatusCode.NotFound, "customer not found")

fun Route.customerRoutes(db: Db, adminApiKey: String) {
    route("/customers") {
        requireSecret("X-API-Key", adminApiKey)

        post {
            val (name, phone, status) = validateCustomer(call.receive(), defaultStatus = "lead")
            call.respond(HttpStatusCode.Created, insertCustomer(db, name, phone, status))
        }

        get {
            val status = call.request.queryParameters["status"]
            if (status != null && status !in STATUSES) {
                throw ApiException(HttpStatusCode.BadRequest, "status must be one of lead, active, inactive")
            }
            call.respond(listCustomers(db, status))
        }

        get("/{id}") {
            val id = call.customerId()
            val customer = findCustomer(db, id) ?: notFound()
            call.respond(customer.copy(messages = listMessages(db, id)))
        }

        put("/{id}") {
            val id = call.customerId()
            val existing = findCustomer(db, id) ?: notFound()
            // status is optional on update: keep the current one when it is not sent.
            val (name, phone, status) = validateCustomer(call.receive(), defaultStatus = existing.status)
            call.respond(updateCustomer(db, id, name, phone, status) ?: notFound())
        }

        delete("/{id}") {
            if (!deleteCustomer(db, call.customerId())) notFound()
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
