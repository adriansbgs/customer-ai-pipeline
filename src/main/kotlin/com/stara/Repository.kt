package com.stara

import kotlinx.serialization.Serializable
import java.sql.ResultSet
import java.time.OffsetDateTime

@Serializable
data class Message(
    val id: Long,
    val customerId: Long,
    val direction: String,
    val content: String,
    val createdAt: String,
)

@Serializable
data class Customer(
    val id: Long,
    val name: String,
    val phone: String,
    val status: String,
    val createdAt: String,
    val messages: List<Message>? = null,
)

private const val CUSTOMER_COLUMNS = "id, name, phone, status, created_at"
private const val MESSAGE_COLUMNS = "id, customer_id, direction, content, created_at"

private fun ResultSet.createdAt() = getObject("created_at", OffsetDateTime::class.java).toString()

private fun ResultSet.toCustomer() =
    Customer(getLong("id"), getString("name"), getString("phone"), getString("status"), createdAt())

private fun ResultSet.toMessage() =
    Message(getLong("id"), getLong("customer_id"), getString("direction"), getString("content"), createdAt())

/** Runs a parameterized query and maps every row. All SQL in this file goes through here. */
private fun <T> query(db: Db, sql: String, vararg params: Any?, map: (ResultSet) -> T): List<T> = db.use { conn ->
    conn.prepareStatement(sql).use { st ->
        params.forEachIndexed { i, p -> st.setObject(i + 1, p) }
        st.executeQuery().use { rs -> generateSequence { if (rs.next()) map(rs) else null }.toList() }
    }
}

fun insertCustomer(db: Db, name: String, phone: String, status: String): Customer =
    query(db, "INSERT INTO customers (name, phone, status) VALUES (?, ?, ?) RETURNING $CUSTOMER_COLUMNS",
        name, phone, status) { it.toCustomer() }.single()

fun listCustomers(db: Db, status: String?): List<Customer> =
    if (status == null) {
        query(db, "SELECT $CUSTOMER_COLUMNS FROM customers ORDER BY id") { it.toCustomer() }
    } else {
        query(db, "SELECT $CUSTOMER_COLUMNS FROM customers WHERE status = ? ORDER BY id", status) { it.toCustomer() }
    }

fun findCustomer(db: Db, id: Long): Customer? =
    query(db, "SELECT $CUSTOMER_COLUMNS FROM customers WHERE id = ?", id) { it.toCustomer() }.singleOrNull()

fun findCustomerByPhone(db: Db, phone: String): Customer? =
    query(db, "SELECT $CUSTOMER_COLUMNS FROM customers WHERE phone = ?", phone) { it.toCustomer() }.singleOrNull()

fun updateCustomer(db: Db, id: Long, name: String, phone: String, status: String): Customer? =
    query(db, "UPDATE customers SET name = ?, phone = ?, status = ? WHERE id = ? RETURNING $CUSTOMER_COLUMNS",
        name, phone, status, id) { it.toCustomer() }.singleOrNull()

fun deleteCustomer(db: Db, id: Long): Boolean =
    query(db, "DELETE FROM customers WHERE id = ? RETURNING id", id) { it.getLong("id") }.isNotEmpty()

fun insertMessage(db: Db, customerId: Long, direction: String, content: String): Message =
    query(db, "INSERT INTO messages (customer_id, direction, content) VALUES (?, ?, ?) RETURNING $MESSAGE_COLUMNS",
        customerId, direction, content) { it.toMessage() }.single()

fun listMessages(db: Db, customerId: Long): List<Message> =
    query(db, "SELECT $MESSAGE_COLUMNS FROM messages WHERE customer_id = ? ORDER BY id", customerId) { it.toMessage() }

/** The last [limit] messages of a customer, returned oldest first. */
fun recentMessages(db: Db, customerId: Long, limit: Int): List<Message> =
    query(db, "SELECT $MESSAGE_COLUMNS FROM messages WHERE customer_id = ? ORDER BY id DESC LIMIT ?",
        customerId, limit) { it.toMessage() }.reversed()
