package com.stara

/**
 * Normalizes an Indonesian phone number to "+62…" so that 0812…, 62812… and +62812… are the same customer.
 * Returns null when the input is not a valid Indonesian number.
 */
fun normalizePhone(raw: String?): String? {
    val cleaned = raw?.trim()?.filterNot { it in " -.()" } ?: return null
    if (!cleaned.matches(Regex("\\+?[0-9]+"))) return null

    var national = when {
        cleaned.startsWith("+62") -> cleaned.removePrefix("+62")
        cleaned.startsWith("62") -> cleaned.removePrefix("62")
        cleaned.startsWith("0") -> cleaned.removePrefix("0")
        else -> return null
    }
    // "+62 0812…" is a common way of writing it: drop the extra trunk 0.
    national = national.removePrefix("0")

    return if (national.length in 8..13) "+62$national" else null
}
