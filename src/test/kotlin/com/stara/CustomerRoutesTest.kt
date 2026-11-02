package com.stara

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.client.statement.HttpResponse
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class CustomerRoutesTest {
    private val db = testDb()

    @BeforeTest
    fun reset() = resetDb(db)

    private suspend fun ApplicationTestBuilder.create(json: String): HttpResponse =
        client.post("/customers") { admin(); jsonBody(json) }

    @Test
    fun `create returns 201 with normalized phone and default status`() = withApp {
        val res = create("""{"name":"Budi","phone":"0812-3456-7890"}""")
        assertEquals(201, res.status.value)
        val body = res.json()
        assertEquals("+6281234567890", body.str("phone"))
        assertEquals("lead", body.str("status"))
    }

    @Test
    fun `create validates input`() = withApp {
        val cases = mapOf(
            """{"name":"  ","phone":"081234567890"}""" to "name",
            """{"name":"${"a".repeat(101)}","phone":"081234567890"}""" to "name",
            """{"name":"Budi","phone":"0"}""" to "phone",
            """{"name":"Budi"}""" to "phone",
            """{"name":"Budi","phone":"081234567890","status":"vip"}""" to "status",
        )
        cases.forEach { (json, field) ->
            val res = create(json)
            assertEquals(400, res.status.value, json)
            assertContains(res.json().str("error"), field)
        }
        assertEquals(0, listCustomers(db, null).size)
    }

    @Test
    fun `malformed or mistyped json returns 400`() = withApp {
        assertEquals(400, create("{bukan json").status.value)
        assertEquals(400, create("""{"name":"A","phone":812}""").status.value)
    }

    @Test
    fun `non-json content type returns 415 not 500`() = withApp {
        // Ktor's ContentNegotiation answers 415 itself (empty body) before our handler runs.
        val res = client.post("/customers") {
            admin()
            contentType(ContentType.Text.Plain)
            setBody("""{"name":"A","phone":"081234567890"}""")
        }
        assertEquals(415, res.status.value)
        assertEquals(0, listCustomers(db, null).size)
    }

    @Test
    fun `duplicate phone in another format returns 409`() = withApp {
        create("""{"name":"A","phone":"081234567890"}""")
        val res = create("""{"name":"B","phone":"+62 812 3456 7890"}""")
        assertEquals(409, res.status.value)
        assertEquals("phone already exists", res.json().str("error"))
    }

    @Test
    fun `list supports status filter`() = withApp {
        insertCustomer(db, "A", "+6281111111111", "lead")
        insertCustomer(db, "B", "+6282222222222", "active")
        val active = client.get("/customers?status=active") { admin() }.jsonList()
        assertEquals(listOf("B"), active.map { it.jsonObject.str("name") })
        assertEquals(2, client.get("/customers") { admin() }.jsonList().size)
        assertEquals(400, client.get("/customers?status=vip") { admin() }.status.value)
    }

    @Test
    fun `get one includes messages oldest first`() = withApp {
        val c = insertCustomer(db, "A", "+6281111111111", "lead")
        insertMessage(db, c.id, "incoming", "m1")
        insertMessage(db, c.id, "outgoing", "m2")
        val body = client.get("/customers/${c.id}") { admin() }.json()
        assertEquals(listOf("m1", "m2"), body.getValue("messages").jsonArray.map { it.jsonObject.str("content") })
    }

    @Test
    fun `get unknown or invalid id`() = withApp {
        assertEquals(404, client.get("/customers/999") { admin() }.status.value)
        assertEquals(400, client.get("/customers/abc") { admin() }.status.value)
    }

    @Test
    fun `update changes fields and keeps status when omitted`() = withApp {
        val c = insertCustomer(db, "A", "+6281111111111", "active")
        val res = client.put("/customers/${c.id}") { admin(); jsonBody("""{"name":"Baru","phone":"081299998888"}""") }
        assertEquals(200, res.status.value)
        val body = res.json()
        assertEquals("Baru", body.str("name"))
        assertEquals("+6281299998888", body.str("phone"))
        assertEquals("active", body.str("status"))
    }

    @Test
    fun `update to another customer's phone returns 409`() = withApp {
        insertCustomer(db, "A", "+6281111111111", "lead")
        val b = insertCustomer(db, "B", "+6282222222222", "lead")
        val res = client.put("/customers/${b.id}") { admin(); jsonBody("""{"name":"B","phone":"081111111111"}""") }
        assertEquals(409, res.status.value)
    }

    @Test
    fun `update unknown returns 404`() = withApp {
        val res = client.put("/customers/999") { admin(); jsonBody("""{"name":"B","phone":"081111111111"}""") }
        assertEquals(404, res.status.value)
    }

    @Test
    fun `delete returns 204 then 404`() = withApp {
        val c = insertCustomer(db, "A", "+6281111111111", "lead")
        assertEquals(204, client.delete("/customers/${c.id}") { admin() }.status.value)
        assertEquals(404, client.get("/customers/${c.id}") { admin() }.status.value)
        assertEquals(404, client.delete("/customers/${c.id}") { admin() }.status.value)
    }

    @Test
    fun `responses carry security headers`() = withApp {
        val res = client.get("/customers") { admin() }
        assertEquals("default-src 'self'", res.headers["Content-Security-Policy"])
        assertEquals("nosniff", res.headers["X-Content-Type-Options"])
    }
}
