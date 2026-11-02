package com.stara

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthTest {
    @BeforeTest
    fun reset() = resetDb(testDb())

    @Test
    fun `secretMatches is exact`() {
        assertTrue(secretMatches("abc", "abc"))
        assertFalse(secretMatches("abc", "abd"))
        assertFalse(secretMatches("abc", null))
        assertFalse(secretMatches("abc", ""))
    }

    @Test
    fun `customers require admin key`() = withApp {
        assertEquals(401, client.get("/customers").status.value)
        assertEquals(401, client.get("/customers") { header("X-API-Key", "wrong") }.status.value)
        assertEquals(401, client.get("/customers") { header("X-API-Key", WEBHOOK_SECRET) }.status.value)
        assertEquals(200, client.get("/customers") { admin() }.status.value)
    }

    @Test
    fun `unauthorized response uses the error shape`() = withApp {
        assertEquals("unauthorized", client.get("/customers").json().str("error"))
    }

    @Test
    fun `unauthorized request does not touch the database`() = withApp {
        client.post("/customers") { jsonBody("""{"name":"X","phone":"081234567890"}""") }
        assertEquals(0, listCustomers(testDb(), null).size)
    }
}
