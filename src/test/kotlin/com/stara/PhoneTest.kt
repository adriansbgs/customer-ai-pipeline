package com.stara

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PhoneTest {
    @Test
    fun `normalizes indonesian formats`() {
        val expected = "+6281234567890"
        listOf("081234567890", "6281234567890", "+62 812-3456-7890", "+62 0812 3456 7890", " (0812) 3456.7890 ")
            .forEach { assertEquals(expected, normalizePhone(it), "input: $it") }
    }

    @Test
    fun `rejects invalid input`() {
        listOf(null, "", "   ", "0", "+1 555 123 4567", "08abc", "812345678", "++6281234567", "+62")
            .forEach { assertNull(normalizePhone(it), "input: $it") }
    }

    @Test
    fun `enforces 8 to 13 digits after country code`() {
        assertEquals("+6212345678", normalizePhone("012345678")) // 8 digits
        assertNull(normalizePhone("01234567")) // 7 digits
        assertEquals("+621234567890123", normalizePhone("01234567890123")) // 13 digits
        assertNull(normalizePhone("012345678901234")) // 14 digits
    }
}
