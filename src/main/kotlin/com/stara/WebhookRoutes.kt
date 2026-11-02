package com.stara

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class IncomingMessage(val phone: String? = null, val name: String? = null, val message: String? = null)

@Serializable
data class WebhookReply(@SerialName("customer_id") val customerId: Long, val reply: String)

const val CONTEXT_SIZE = 5

fun Route.webhookRoutes(db: Db, ai: ReplyGenerator, config: Config) {
    route("/webhook/incoming-message") {
        requireSecret("X-Webhook-Secret", config.webhookSecret)

        post {
            val input = call.receive<IncomingMessage>()
            val phone = normalizePhone(input.phone) ?: throw ApiException(HttpStatusCode.BadRequest, "phone is invalid")
            val text = input.message?.trim().orEmpty()
            if (text.isEmpty()) throw ApiException(HttpStatusCode.BadRequest, "message is required")
            if (text.length > 2000) throw ApiException(HttpStatusCode.BadRequest, "message must be at most 2000 characters")
            val name = input.name?.trim().orEmpty()
            if (name.length > 100) throw ApiException(HttpStatusCode.BadRequest, "name must be at most 100 characters")

            // 1. Find the customer by phone, or create a new lead. An existing customer's name is never overwritten.
            // ponytail: find-then-insert can race for the same new number at the same moment;
            // the UNIQUE constraint still protects the data (the second request gets 409).
            val customer = findCustomerByPhone(db, phone)
                ?: insertCustomer(db, name.ifEmpty { phone }, phone, "lead")

            // 2. Context is read before the new message is stored, so it holds only earlier messages.
            val history = recentMessages(db, customer.id, CONTEXT_SIZE)
            insertMessage(db, customer.id, "incoming", text)

            // 3-4. Ask the AI (falls back on failure/timeout) and store the reply.
            val reply = replyOrFallback(ai, history, text, config.aiTimeoutMs)
            insertMessage(db, customer.id, "outgoing", reply)

            // 5. Respond with the generated reply.
            call.respond(WebhookReply(customer.id, reply))
        }
    }
}
