package com.stara

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebhookTest {
    private val db = testDb()

    @BeforeTest
    fun reset() = resetDb(db)

    private suspend fun ApplicationTestBuilder.postWebhook(json: String, secret: String? = WEBHOOK_SECRET): HttpResponse =
        client.post("/webhook/incoming-message") {
            if (secret != null) header("X-Webhook-Secret", secret)
            jsonBody(json)
        }

    private fun payload(phone: String? = "081234567890", name: String? = "Budi Santoso", message: String? = "Halo") =
        buildJsonObject {
            phone?.let { put("phone", JsonPrimitive(it)) }
            name?.let { put("name", JsonPrimitive(it)) }
            message?.let { put("message", JsonPrimitive(it)) }
        }.toString()

    @Test
    fun `creates lead customer and stores both messages`() = withApp {
        val res = postWebhook(payload(message = "Halo, saya mau tanya harga paket layanan"))
        assertEquals(200, res.status.value)
        val body = res.json()
        val reply = body.str("reply")
        assertTrue(reply.isNotBlank())

        val customer = listCustomers(db, null).single()
        assertEquals(customer.id.toString(), body.str("customer_id"))
        assertEquals(listOf("+6281234567890", "lead", "Budi Santoso"), listOf(customer.phone, customer.status, customer.name))
        val messages = listMessages(db, customer.id)
        assertEquals(listOf("incoming", "outgoing"), messages.map { it.direction })
        assertEquals(listOf("Halo, saya mau tanya harga paket layanan", reply), messages.map { it.content })
    }

    @Test
    fun `reuses customer across phone formats and keeps name`() = withApp {
        assertEquals(200, postWebhook(payload(phone = "081234567890", name = "Budi")).status.value)
        assertEquals(200, postWebhook(payload(phone = "+62 812-3456-7890", name = "Orang Lain")).status.value)
        val customer = listCustomers(db, null).single()
        assertEquals("Budi", customer.name)
        assertEquals(4, listMessages(db, customer.id).size)
    }

    @Test
    fun `missing name uses phone as name`() = withApp {
        postWebhook(payload(name = null))
        assertEquals("+6281234567890", listCustomers(db, null).single().name)
    }

    @Test
    fun `incomplete or invalid data returns 400 with clear error`() = withApp {
        postWebhook(payload(phone = null)).let {
            assertEquals(400, it.status.value)
            assertContains(it.json().str("error"), "phone")
        }
        postWebhook(payload(message = "  ")).let {
            assertEquals(400, it.status.value)
            assertContains(it.json().str("error"), "message")
        }
        assertEquals(400, postWebhook("bukan json").status.value)
        assertEquals(400, postWebhook("""{"phone":812,"message":"Halo"}""").status.value)
        assertEquals(400, postWebhook(payload(name = "a".repeat(101))).status.value)
        assertEquals(0, listCustomers(db, null).size)
    }

    @Test
    fun `message length boundary and unicode`() = withApp {
        assertEquals(200, postWebhook(payload(message = "a".repeat(2000))).status.value)
        assertEquals(400, postWebhook(payload(message = "a".repeat(2001))).status.value)
        val tricky = "Halo 👋 <script>alert(1)</script>"
        assertEquals(200, postWebhook(payload(message = tricky)).status.value)
        val customer = listCustomers(db, null).single()
        assertTrue(listMessages(db, customer.id).any { it.content == tricky })
    }

    @Test
    fun `ai failure still stores message and returns fallback`() {
        val failing = object : ReplyGenerator {
            override suspend fun generate(history: List<Message>, incoming: String): String = error("boom")
        }
        withApp(ai = failing) {
            val res = postWebhook(payload())
            assertEquals(200, res.status.value)
            assertEquals(FALLBACK_REPLY, res.json().str("reply"))
            val messages = listMessages(db, listCustomers(db, null).single().id)
            assertEquals(listOf("Halo", FALLBACK_REPLY), messages.map { it.content })
        }
    }

    @Test
    fun `passes last five messages as context, before saving the new one`() {
        var lastHistory: List<Message> = emptyList()
        val recording = object : ReplyGenerator {
            override suspend fun generate(history: List<Message>, incoming: String): String {
                lastHistory = history
                return "ok"
            }
        }
        withApp(ai = recording) {
            (1..4).forEach { postWebhook(payload(message = "pesan $it")) }
            postWebhook(payload(message = "pesan 5"))
        }
        assertEquals(5, lastHistory.size)
        assertEquals("outgoing", lastHistory.last().direction)
        assertFalse(lastHistory.any { it.content == "pesan 5" })
        assertEquals(listOf("ok", "pesan 3", "ok", "pesan 4", "ok"), lastHistory.map { it.content })
    }

    @Test
    fun `requires webhook secret`() = withApp {
        assertEquals(401, postWebhook(payload(), secret = null).status.value)
        assertEquals(401, postWebhook(payload(), secret = ADMIN_KEY).status.value)
        assertEquals(0, listCustomers(db, null).size)
    }
}
