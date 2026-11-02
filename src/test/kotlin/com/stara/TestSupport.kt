package com.stara

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val env = readEnv()

const val ADMIN_KEY = "test-admin-key"
const val WEBHOOK_SECRET = "test-webhook-secret"

fun testDb(): Db = Db(
    url = env["TEST_DATABASE_URL"]?.takeIf { it.isNotBlank() } ?: "jdbc:postgresql://localhost:5432/stara_test",
    user = env["DB_USER"] ?: "stara",
    password = env["DB_PASSWORD"] ?: "",
)

fun resetDb(db: Db) {
    db.use { it.createStatement().use { st -> st.execute("TRUNCATE customers RESTART IDENTITY CASCADE") } }
}

fun testConfig(aiTimeoutMs: Long = 200) = Config(
    port = 0,
    databaseUrl = "unused-in-tests",
    dbUser = "",
    dbPassword = "",
    aiProvider = "mock",
    aiApiKey = "",
    aiTimeoutMs = aiTimeoutMs,
    webhookSecret = WEBHOOK_SECRET,
    adminApiKey = ADMIN_KEY,
)

fun withApp(ai: ReplyGenerator = MockReplyGenerator(), block: suspend ApplicationTestBuilder.() -> Unit) =
    testApplication {
        application { module(testConfig(), testDb(), ai) }
        block()
    }

fun HttpRequestBuilder.admin() = header("X-API-Key", ADMIN_KEY)

fun HttpRequestBuilder.jsonBody(json: String) {
    contentType(ContentType.Application.Json)
    setBody(json)
}

suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
suspend fun HttpResponse.jsonList(): JsonArray = Json.parseToJsonElement(bodyAsText()).jsonArray
fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
