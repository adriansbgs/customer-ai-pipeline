package com.stara

import org.postgresql.util.PSQLException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepositoryTest {
    private val db = testDb()

    @BeforeTest
    fun reset() = resetDb(db)

    @Test
    fun `insert and find customer with default fields`() {
        val c = insertCustomer(db, "Budi", "+6281234567890", "lead")
        assertEquals(c, findCustomer(db, c.id))
        assertEquals(c.id, findCustomerByPhone(db, "+6281234567890")?.id)
    }

    @Test
    fun `filters by status`() {
        insertCustomer(db, "A", "+6281111111111", "lead")
        insertCustomer(db, "B", "+6282222222222", "active")
        assertEquals(listOf("B"), listCustomers(db, "active").map { it.name })
        assertEquals(2, listCustomers(db, null).size)
    }

    @Test
    fun `duplicate phone violates unique constraint`() {
        insertCustomer(db, "A", "+6281111111111", "lead")
        val e = assertFailsWith<PSQLException> { insertCustomer(db, "B", "+6281111111111", "lead") }
        assertEquals("23505", e.sqlState)
    }

    @Test
    fun `update changes fields`() {
        val c = insertCustomer(db, "A", "+6281111111111", "lead")
        val u = updateCustomer(db, c.id, "B", "+6282222222222", "active")!!
        assertEquals(listOf("B", "+6282222222222", "active"), listOf(u.name, u.phone, u.status))
        assertEquals(null, updateCustomer(db, 999, "B", "+6283333333333", "active"))
    }

    @Test
    fun `recent messages returns last N oldest first`() {
        val c = insertCustomer(db, "A", "+6281111111111", "lead")
        (1..7).forEach { insertMessage(db, c.id, "incoming", "m$it") }
        assertEquals(listOf("m3", "m4", "m5", "m6", "m7"), recentMessages(db, c.id, 5).map { it.content })
    }

    @Test
    fun `deleting customer cascades to messages`() {
        val c = insertCustomer(db, "A", "+6281111111111", "lead")
        insertMessage(db, c.id, "incoming", "halo")
        assertTrue(deleteCustomer(db, c.id))
        assertEquals(emptyList(), listMessages(db, c.id))
        assertFalse(deleteCustomer(db, c.id))
    }
}
