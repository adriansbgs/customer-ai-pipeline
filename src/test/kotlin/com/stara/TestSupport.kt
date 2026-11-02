package com.stara

private val env = readEnv()

fun testDb(): Db = Db(
    url = env["TEST_DATABASE_URL"]?.takeIf { it.isNotBlank() } ?: "jdbc:postgresql://localhost:5432/stara_test",
    user = env["DB_USER"] ?: "stara",
    password = env["DB_PASSWORD"] ?: "",
)

fun resetDb(db: Db) {
    db.use { it.createStatement().use { st -> st.execute("TRUNCATE customers RESTART IDENTITY CASCADE") } }
}
