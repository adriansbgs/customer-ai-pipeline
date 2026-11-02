package com.stara

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class StaticTest {
    @Test
    fun `dashboard is served without api key and with security headers`() = withApp {
        val res = client.get("/")
        assertEquals(200, res.status.value)
        assertContains(res.bodyAsText(), "<script src=\"app.js\"")
        assertEquals("default-src 'self'", res.headers["Content-Security-Policy"])
        assertEquals(200, client.get("/app.js").status.value)
        assertEquals(200, client.get("/style.css").status.value)
    }

    @Test
    fun `app js never uses innerHTML`() {
        val js = this::class.java.getResource("/static/app.js")!!.readText()
        assertFalse(js.contains("innerHTML"))
    }
}
