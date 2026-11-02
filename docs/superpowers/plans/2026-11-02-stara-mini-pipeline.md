# STARA Mini Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Layanan Kotlin/Ktor yang menerima pesan WhatsApp simulasi lewat webhook, menyimpannya di Postgres, membalas lewat AI (mock) dengan fallback, dan menampilkannya di dashboard HTML sederhana.

**Architecture:** Satu aplikasi Ktor (satu modul Gradle) yang menyajikan REST API, webhook, dan file statis dashboard. Akses database lewat JDBC biasa dengan query berparameter, tanpa ORM. AI berada di balik satu antarmuka `ReplyGenerator`; sekarang hanya ada `MockReplyGenerator`. Postgres dijalankan lewat Docker Compose; tes memakai database `stara_test` di container yang sama.

**Tech Stack:** Kotlin 2.1.0, Ktor 3.0.3 (Netty, ContentNegotiation + kotlinx.serialization, StatusPages), PostgreSQL 17 + `org.postgresql:postgresql:42.7.4`, Logback 1.5.12, `kotlin("test")` + `ktor-server-test-host`, Gradle 8.11.1 (wrapper), JDK 21, HTML/CSS/JS biasa.

**Spec:** `docs/superpowers/specs/2026-11-02-stara-mini-pipeline-design.md` (baca bersama plan ini).

## Global Constraints

- Kode dan identifier dalam Bahasa Inggris; teks yang dilihat pelanggan (balasan AI, fallback) dalam Bahasa Indonesia.
- Package: `com.stara`. Semua kode di `src/main/kotlin/com/stara/`, tes di `src/test/kotlin/com/stara/`.
- JDK lokal: `C:\Users\Legion\.jdks\jbr-21.0.11`. Java/Gradle tidak ada di PATH: setiap perintah Gradle dijalankan dengan `JAVA_HOME` itu, contoh (Git Bash): `JAVA_HOME=/c/Users/Legion/.jdks/jbr-21.0.11 ./gradlew test`.
- Docker CLI belum di PATH sesi agen: pakai `"/c/Program Files/Docker/Docker/resources/bin/docker.exe"` bila `docker` tidak dikenal.
- Semua SQL memakai `PreparedStatement` dengan parameter `?`. Tidak ada SQL dari rangkaian string masukan.
- Error ke klien selalu `{"error": "<pesan singkat>"}`; tidak pernah stack trace atau detail SQL.
- Balasan cadangan persis: `Terima kasih, tim kami akan segera menghubungi Anda.`
- Batas: `name` ≤ 100 karakter, `content`/`message` ≤ 2000 karakter, telepon 8–13 digit setelah `+62`.
- Riwayat konteks AI: 5 pesan terakhir, diambil **sebelum** pesan masuk baru disimpan, urut terlama → terbaru.
- `AI_TIMEOUT_MS` default 8000.
- Variabel env (nama persis): `PORT`, `DATABASE_URL`, `DB_USER`, `DB_PASSWORD`, `AI_PROVIDER`, `AI_API_KEY`, `AI_TIMEOUT_MS`, `WEBHOOK_SECRET`, `ADMIN_API_KEY`, ditambah `TEST_DATABASE_URL` khusus tes.
- Header auth: `X-Webhook-Secret` untuk webhook, `X-API-Key` untuk `/customers…`. Salah/hilang → 401 `{"error": "unauthorized"}`.
- Dependensi hanya yang tercantum di Tech Stack. Jangan tambah ORM, DI framework, Testcontainers, atau pustaka validasi.
- Kode harus mudah dijelaskan baris per baris: fungsi kecil, tanpa abstraksi yang tidak dipakai.

## Review Focus

1. Nomor yang sama dalam format berbeda (`0812…` lalu `+62 812-…`) di webhook harus memakai pelanggan yang sama, bukan 409 atau pelanggan ganda → tes di Task 6.
2. Webhook untuk telepon yang sudah ada dengan `name` berbeda tidak boleh mengubah nama → tes di Task 6.
3. JSON dengan tipe salah (`"phone": 812`) atau body bukan JSON harus 400, bukan 500 → tes di Task 4 dan Task 6.
4. Batas panjang tepat: pesan 2000 karakter diterima, 2001 ditolak; emoji/Unicode tersimpan utuh → tes di Task 6.
5. `PUT` yang mengganti telepon ke nomor milik pelanggan lain harus 409 → tes di Task 4.

---

## File Structure

```
.gitattributes                         # *.sh eol=lf (skrip init Postgres di Windows)
.env.example
build.gradle.kts / settings.gradle.kts / gradlew* / gradle/wrapper/*
schema.sql                             # Tugas 1
db/init.sh                             # buat tabel di `stara` dan `stara_test`
docker-compose.yml
Dockerfile
README.md
src/main/kotlin/com/stara/
  Application.kt     # main(), module(config, db, ai): install plugin + routes
  Config.kt          # Config, loadConfig(), .env loader
  Phone.kt           # normalizePhone()
  Db.kt              # Db (koneksi JDBC)
  Repository.kt      # Customer, Message, fungsi query
  Errors.kt          # ApiException, ErrorResponse, StatusPages + security headers
  Auth.kt            # secretMatches(), SecretHeader plugin
  CustomerRoutes.kt  # Tugas 2
  WebhookRoutes.kt   # Tugas 3
  Ai.kt              # ReplyGenerator, MockReplyGenerator, replyOrFallback()
src/main/resources/
  logback.xml
  prompts/system.txt
  static/index.html, static/app.js, static/style.css
src/test/kotlin/com/stara/
  TestSupport.kt     # testConfig(), testDb(), resetDb(), helper request
  ConfigTest.kt, PhoneTest.kt, RepositoryTest.kt, AuthTest.kt,
  CustomerRoutesTest.kt, AiTest.kt, WebhookTest.kt, StaticTest.kt
```

---

### Task 1: Kerangka proyek dan konfigurasi

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/wrapper/*`, `gradlew`, `gradlew.bat`, `.gitattributes`, `.env.example`, `src/main/kotlin/com/stara/Config.kt`, `src/main/resources/logback.xml`
- Test: `src/test/kotlin/com/stara/ConfigTest.kt`

**Interfaces:**
- Produces:
  - `data class Config(val port: Int, val databaseUrl: String, val dbUser: String, val dbPassword: String, val aiProvider: String, val aiApiKey: String, val aiTimeoutMs: Long, val webhookSecret: String, val adminApiKey: String)`
  - `fun loadConfig(env: Map<String, String>): Config`: melempar `IllegalStateException` dengan pesan yang menyebut nama variabel bila `WEBHOOK_SECRET`, `ADMIN_API_KEY`, atau `DATABASE_URL` kosong, atau bila `AI_PROVIDER` bukan `mock`. Default: `PORT=8080`, `AI_PROVIDER=mock`, `AI_TIMEOUT_MS=8000`, `DB_USER=stara`, `DB_PASSWORD=""`, `AI_API_KEY=""`.
  - `fun readEnv(dotenv: java.io.File = java.io.File(".env")): Map<String, String>`: isi `.env` (baris `KEY=VALUE`, abaikan baris kosong dan `#`) ditimpa oleh `System.getenv()`.

- [ ] **Step 1: Buat Gradle wrapper lewat Docker** (tidak ada Gradle lokal)

Run: `docker run --rm -v "$(pwd -W):/p" -w /p gradle:8.11.1-jdk21 gradle wrapper --gradle-version 8.11.1`
Expected: muncul `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`.

- [ ] **Step 2: Tulis `settings.gradle.kts` dan `build.gradle.kts`**

Plugin: `kotlin("jvm") version "2.1.0"`, `kotlin("plugin.serialization") version "2.1.0"`, `application`. `mainClass = "com.stara.ApplicationKt"`. `kotlin { jvmToolchain(21) }`. Dependensi: `io.ktor:ktor-server-netty`, `ktor-server-content-negotiation`, `ktor-serialization-kotlinx-json`, `ktor-server-status-pages` (semua 3.0.3), `org.postgresql:postgresql:42.7.4`, `ch.qos.logback:logback-classic:1.5.12`; test: `kotlin("test")`, `io.ktor:ktor-server-test-host:3.0.3`. `tasks.test { useJUnitPlatform() }`.

`.gitattributes`: `*.sh text eol=lf`. `.env.example`: semua variabel env dari Global Constraints dengan nilai kosong, kecuali `PORT=8080`, `AI_PROVIDER=mock`, `AI_TIMEOUT_MS=8000`, `DB_USER=stara`, `DATABASE_URL=jdbc:postgresql://localhost:5432/stara`, `TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/stara_test`.

- [ ] **Step 3: Tulis tes yang gagal**

```kotlin
class ConfigTest {
    private val base = mapOf(
        "DATABASE_URL" to "jdbc:postgresql://localhost:5432/stara",
        "WEBHOOK_SECRET" to "w", "ADMIN_API_KEY" to "a",
    )

    @Test fun `applies defaults`() {
        val c = loadConfig(base)
        assertEquals(8080, c.port); assertEquals("mock", c.aiProvider); assertEquals(8000L, c.aiTimeoutMs)
    }

    @Test fun `refuses to start without secrets`() {
        val e = assertFailsWith<IllegalStateException> { loadConfig(base - "WEBHOOK_SECRET") }
        assertContains(e.message!!, "WEBHOOK_SECRET")
        assertFailsWith<IllegalStateException> { loadConfig(base + ("ADMIN_API_KEY" to " ")) }
    }

    @Test fun `rejects unknown ai provider`() {
        assertFailsWith<IllegalStateException> { loadConfig(base + ("AI_PROVIDER" to "gemini")) }
    }

    @Test fun `reads dotenv and lets real env win`() {
        val file = kotlin.io.path.createTempFile().toFile().apply {
            writeText("# komentar\nFOO_STARA_TEST=dari-file\n\nPATH=palsu\n")
            deleteOnExit()
        }
        val env = readEnv(file)
        assertEquals("dari-file", env["FOO_STARA_TEST"])
        assertEquals(System.getenv("PATH"), env["PATH"])
    }
}
```

- [ ] **Step 4: Jalankan, pastikan gagal**

Run: `JAVA_HOME=/c/Users/Legion/.jdks/jbr-21.0.11 ./gradlew test --tests com.stara.ConfigTest`
Expected: FAIL kompilasi (`loadConfig` belum ada).

- [ ] **Step 5: Implementasikan `Config.kt`** sesuai Interfaces. `logback.xml`: level INFO ke console.

- [ ] **Step 6: Jalankan, pastikan lulus**

Run: perintah Step 4. Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "chore: scaffold Ktor project and config loading"
```

---

### Task 2: Normalisasi nomor telepon

**Files:**
- Create: `src/main/kotlin/com/stara/Phone.kt`
- Test: `src/test/kotlin/com/stara/PhoneTest.kt`

**Interfaces:**
- Produces: `fun normalizePhone(raw: String?): String?`: hasil `+62…` atau `null` bila tidak valid. Algoritma persis seperti spec 4.1a (langkah 1–5).

- [ ] **Step 1: Tulis tes yang gagal** (semua contoh spec + batas panjang)

```kotlin
class PhoneTest {
    @Test fun `normalizes indonesian formats`() {
        val expected = "+6281234567890"
        listOf("081234567890", "6281234567890", "+62 812-3456-7890", "+62 0812 3456 7890", " (0812) 3456.7890 ")
            .forEach { assertEquals(expected, normalizePhone(it), "input: $it") }
    }

    @Test fun `rejects invalid input`() {
        listOf(null, "", "   ", "0", "+1 555 123 4567", "08abc", "812345678", "++6281234567", "+62")
            .forEach { assertNull(normalizePhone(it), "input: $it") }
    }

    @Test fun `enforces 8 to 13 digits after country code`() {
        assertEquals("+6212345678", normalizePhone("012345678"))        // 8 digit
        assertNull(normalizePhone("01234567"))                          // 7 digit
        assertEquals("+621234567890123", normalizePhone("01234567890123")) // 13 digit
        assertNull(normalizePhone("012345678901234"))                   // 14 digit
    }
}
```

- [ ] **Step 2: Jalankan, pastikan gagal.** Run: `… ./gradlew test --tests com.stara.PhoneTest`. Expected: FAIL kompilasi.
- [ ] **Step 3: Implementasikan `normalizePhone`** (regex/`filter`, tanpa pustaka).
- [ ] **Step 4: Jalankan, pastikan lulus.** Expected: PASS.
- [ ] **Step 5: Commit** `git commit -m "feat: normalize Indonesian phone numbers"`

---

### Task 3: Skema database, Postgres Compose, dan repository

**Files:**
- Create: `schema.sql`, `db/init.sh`, `docker-compose.yml` (layanan `db` saja dulu), `src/main/kotlin/com/stara/Db.kt`, `src/main/kotlin/com/stara/Repository.kt`, `src/test/kotlin/com/stara/TestSupport.kt`
- Test: `src/test/kotlin/com/stara/RepositoryTest.kt`

**Interfaces:**
- Consumes: `Config`, `readEnv()` (Task 1).
- Produces:
  - `class Db(private val url: String, private val user: String, private val password: String) { fun <T> use(block: (java.sql.Connection) -> T): T }`: buka koneksi per panggilan via `DriverManager`, tutup dengan `use`. Beri komentar `// ponytail: koneksi per request, ganti ke HikariCP kalau trafik naik`.
  - `@Serializable data class Message(val id: Long, val customerId: Long, val direction: String, val content: String, val createdAt: String)`
  - `@Serializable data class Customer(val id: Long, val name: String, val phone: String, val status: String, val createdAt: String, val messages: List<Message>? = null)`: `createdAt` berupa string ISO-8601 dari `OffsetDateTime`.
  - Fungsi di `Repository.kt` (semua menerima `Db` sebagai parameter pertama):
    - `fun insertCustomer(db: Db, name: String, phone: String, status: String): Customer`
    - `fun listCustomers(db: Db, status: String?): List<Customer>` (urut `id`)
    - `fun findCustomer(db: Db, id: Long): Customer?`
    - `fun findCustomerByPhone(db: Db, phone: String): Customer?`
    - `fun updateCustomer(db: Db, id: Long, name: String, phone: String, status: String): Customer?`
    - `fun deleteCustomer(db: Db, id: Long): Boolean`
    - `fun insertMessage(db: Db, customerId: Long, direction: String, content: String): Message`
    - `fun listMessages(db: Db, customerId: Long): List<Message>` (terlama → terbaru)
    - `fun recentMessages(db: Db, customerId: Long, limit: Int): List<Message>` (N terakhir, dikembalikan terlama → terbaru)
  - Pelanggaran UNIQUE/CHECK dibiarkan naik sebagai `org.postgresql.util.PSQLException` (dipetakan di Task 4).
  - Di `TestSupport.kt`: `fun testDb(): Db` (URL dari `TEST_DATABASE_URL` via `readEnv()`, default `jdbc:postgresql://localhost:5432/stara_test`; kredensial `DB_USER`/`DB_PASSWORD`) dan `fun resetDb(db: Db)` (`TRUNCATE customers RESTART IDENTITY CASCADE`).

- [ ] **Step 1: Tulis `schema.sql`** sesuai spec 4.1:

```sql
CREATE TABLE IF NOT EXISTS customers (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL CHECK (length(trim(name)) > 0),
    phone VARCHAR(20) NOT NULL UNIQUE CHECK (phone ~ '^\+62[0-9]{8,13}$'),
    status VARCHAR(10) NOT NULL DEFAULT 'lead' CHECK (status IN ('lead', 'active', 'inactive')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS messages (
    id BIGSERIAL PRIMARY KEY,
    customer_id BIGINT NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    direction VARCHAR(8) NOT NULL CHECK (direction IN ('incoming', 'outgoing')),
    content VARCHAR(2000) NOT NULL CHECK (length(trim(content)) > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS messages_customer_id_idx ON messages (customer_id, id);
```

- [ ] **Step 2: Tulis `db/init.sh` dan `docker-compose.yml` (layanan `db`)**

`init.sh` (dijalankan oleh `/docker-entrypoint-initdb.d`): `set -e`; terapkan `/schema/schema.sql` ke `$POSTGRES_DB`; `createdb stara_test`; terapkan skema yang sama ke `stara_test`. Compose: image `postgres:17`, `POSTGRES_DB=stara`, `POSTGRES_USER=${DB_USER}`, `POSTGRES_PASSWORD=${DB_PASSWORD}`, port `127.0.0.1:5432:5432`, mount `./schema.sql:/schema/schema.sql:ro` dan `./db/init.sh:/docker-entrypoint-initdb.d/init.sh:ro`, volume bernama `pgdata`, healthcheck `pg_isready -U ${DB_USER} -d stara`.

- [ ] **Step 3: Buat `.env` lokal dari `.env.example` dan isi nilainya** (password DB, kedua secret: string acak). `.env` sudah di `.gitignore`; jangan di-commit.

- [ ] **Step 4: Jalankan database**

Run: `docker compose up -d db && docker compose exec db psql -U stara -d stara_test -c '\dt'`
Expected: tabel `customers` dan `messages` terdaftar.

- [ ] **Step 5: Tulis tes yang gagal**

```kotlin
class RepositoryTest {
    private val db = testDb()
    @BeforeTest fun reset() = resetDb(db)

    @Test fun `insert and find customer with default fields`() {
        val c = insertCustomer(db, "Budi", "+6281234567890", "lead")
        assertEquals(c, findCustomer(db, c.id))
        assertEquals(c.id, findCustomerByPhone(db, "+6281234567890")?.id)
    }

    @Test fun `filters by status`() {
        insertCustomer(db, "A", "+6281111111111", "lead")
        insertCustomer(db, "B", "+6282222222222", "active")
        assertEquals(listOf("B"), listCustomers(db, "active").map { it.name })
        assertEquals(2, listCustomers(db, null).size)
    }

    @Test fun `duplicate phone violates unique constraint`() {
        insertCustomer(db, "A", "+6281111111111", "lead")
        val e = assertFailsWith<PSQLException> { insertCustomer(db, "B", "+6281111111111", "lead") }
        assertEquals("23505", e.sqlState)
    }

    @Test fun `recent messages returns last N oldest first`() {
        val c = insertCustomer(db, "A", "+6281111111111", "lead")
        (1..7).forEach { insertMessage(db, c.id, "incoming", "m$it") }
        assertEquals(listOf("m3", "m4", "m5", "m6", "m7"), recentMessages(db, c.id, 5).map { it.content })
    }

    @Test fun `deleting customer cascades to messages`() {
        val c = insertCustomer(db, "A", "+6281111111111", "lead")
        insertMessage(db, c.id, "incoming", "halo")
        assertTrue(deleteCustomer(db, c.id))
        assertEquals(emptyList(), listMessages(db, c.id))
        assertFalse(deleteCustomer(db, c.id))
    }
}
```

- [ ] **Step 6: Jalankan, pastikan gagal.** Run: `… ./gradlew test --tests com.stara.RepositoryTest`. Expected: FAIL kompilasi.
- [ ] **Step 7: Implementasikan `Db.kt` dan `Repository.kt`** sesuai Interfaces (pakai `RETURNING` pada INSERT/UPDATE).
- [ ] **Step 8: Jalankan, pastikan lulus.** Expected: PASS.
- [ ] **Step 9: Commit** `git commit -m "feat: add schema, compose database and repository"`

---

### Task 4: Aplikasi Ktor, error, auth, dan CRUD customer

**Files:**
- Create: `src/main/kotlin/com/stara/Application.kt`, `Errors.kt`, `Auth.kt`, `CustomerRoutes.kt`
- Modify: `src/test/kotlin/com/stara/TestSupport.kt` (tambah helper aplikasi)
- Test: `src/test/kotlin/com/stara/AuthTest.kt`, `src/test/kotlin/com/stara/CustomerRoutesTest.kt`

**Interfaces:**
- Consumes: `Config`, `loadConfig`, `readEnv` (Task 1); `normalizePhone` (Task 2); `Db` dan fungsi repository (Task 3).
- Produces:
  - `fun main()`: `loadConfig(readEnv())`, buat `Db`, pilih `MockReplyGenerator()` (Task 5; sampai Task 5 selesai, `ai` belum dipakai), jalankan Netty di `config.port` dengan `module(config, db, ai)`.
  - `fun Application.module(config: Config, db: Db, ai: ReplyGenerator)`: memasang ContentNegotiation (`Json { ignoreUnknownKeys = true }`), StatusPages, security headers, lalu `customerRoutes(db, config.adminApiKey)`; Task 6 menambah `webhookRoutes(...)`, Task 7 menambah static.
    - Karena `ReplyGenerator` baru dibuat di Task 5, Task 4 membuat stub sementara `interface ReplyGenerator` kosong di `Ai.kt`; Task 5 mengisinya.
  - `class ApiException(val status: HttpStatusCode, override val message: String) : RuntimeException(message)`
  - `@Serializable data class ErrorResponse(val error: String)`
  - `fun Application.installErrorHandling()` di `Errors.kt`: `ApiException` → status + pesannya; `BadRequestException` / `ContentTransformationException` / `SerializationException` → 400 `"invalid JSON body"`; `PSQLException` sqlState `23505` → 409 `"phone already exists"`, `23514` → 400 `"invalid data"`; `Throwable` lainnya → log + 500 `"internal server error"`. Juga menambah header `Content-Security-Policy: default-src 'self'` dan `X-Content-Type-Options: nosniff` ke semua respons.
  - `fun secretMatches(expected: String, provided: String?): Boolean` di `Auth.kt`: `provided != null && MessageDigest.isEqual(expected.toByteArray(), provided.toByteArray())`.
  - `fun Route.requireSecret(headerName: String, secret: String)` di `Auth.kt`: route-scoped plugin (`createRouteScopedPlugin`) yang merespons 401 `ErrorResponse("unauthorized")` sebelum handler/validasi bila `secretMatches` false.
  - `@Serializable data class CustomerInput(val name: String? = null, val phone: String? = null, val status: String? = null)`
  - `fun validateCustomer(input: CustomerInput, defaultStatus: String): Triple<String, String, String>` (name, normalized phone, status) di `CustomerRoutes.kt`, melempar `ApiException(400, …)` dengan pesan yang menyebut kolomnya (`"name is required"`, `"name must be at most 100 characters"`, `"phone is invalid"`, `"status must be one of lead, active, inactive"`).
  - `fun Route.customerRoutes(db: Db, adminApiKey: String)`: lima endpoint spec 4.2. `GET /customers/{id}` mengembalikan `Customer` dengan `messages` terisi. `PUT`: `name` dan `phone` wajib; `status` kosong → status lama dipertahankan. `id` bukan angka → 400 `"invalid id"`; tidak ada → 404 `"customer not found"`. `?status=` di luar daftar → 400. `DELETE` sukses → 204 tanpa body.
  - Di `TestSupport.kt`: `fun testConfig(aiTimeoutMs: Long = 200): Config` (secret `test-webhook-secret` / `test-admin-key`), dan `fun withApp(ai: ReplyGenerator = MockReplyGenerator(), block: suspend ApplicationTestBuilder.(client: HttpClient) -> Unit)` yang menjalankan `testApplication { application { module(testConfig(), testDb(), ai) } }` dengan client ber-ContentNegotiation JSON. (Sebelum Task 5, default `ai` pakai `object : ReplyGenerator {}`.)

- [ ] **Step 1: Tulis tes auth yang gagal** (`AuthTest.kt`)

```kotlin
@Test fun `secretMatches is exact`() {
    assertTrue(secretMatches("abc", "abc")); assertFalse(secretMatches("abc", "abd"))
    assertFalse(secretMatches("abc", null)); assertFalse(secretMatches("abc", ""))
}
@Test fun `customers require admin key`() = withApp { client ->
    assertEquals(401, client.get("/customers").status.value)
    assertEquals(401, client.get("/customers") { header("X-API-Key", "wrong") }.status.value)
    assertEquals(401, client.get("/customers") { header("X-API-Key", "test-webhook-secret") }.status.value)
    assertEquals(200, client.get("/customers") { header("X-API-Key", "test-admin-key") }.status.value)
}
@Test fun `unauthorized request does not touch the database`() = withApp { client ->
    client.post("/customers") { contentType(Json); setBody("""{"name":"X","phone":"081234567890"}""") }
    assertEquals(0, listCustomers(testDb(), null).size)
}
```

- [ ] **Step 2: Tulis tes CRUD yang gagal** (`CustomerRoutesTest.kt`, `resetDb` di `@BeforeTest`, semua permintaan membawa `X-API-Key: test-admin-key`)

Kasus dan assertion:
- `create returns 201 with normalized phone and default status`: POST `{"name":"Budi","phone":"0812-3456-7890"}` → 201, body `phone == "+6281234567890"`, `status == "lead"`.
- `create validates input`: masing-masing → 400 dan `error` berisi kata kolomnya: nama kosong `"  "`, nama 101 karakter, telepon `"0"`, telepon hilang, status `"vip"`.
- `malformed or mistyped json returns 400`: body `"{bukan json"` → 400; `{"name":"A","phone":812}` → 400.
- `duplicate phone in another format returns 409`: buat `081234567890`, lalu buat `+62 812 3456 7890` → 409 `"phone already exists"`.
- `list supports status filter`: dua pelanggan (lead, active), `GET /customers?status=active` → 1 item; `?status=vip` → 400.
- `get one includes messages oldest first`: insert pelanggan + 2 pesan via repository, `GET /customers/{id}` → `messages.map { it.content } == listOf("m1","m2")`.
- `get unknown or invalid id`: `/customers/999` → 404; `/customers/abc` → 400.
- `update changes fields and keeps status when omitted`: buat dengan status `active`, PUT `{"name":"Baru","phone":"081299998888"}` → 200, `name == "Baru"`, `status == "active"`.
- `update to another customer's phone returns 409` (Review Focus 5).
- `update unknown returns 404`.
- `delete returns 204 then 404`: DELETE → 204; GET → 404; DELETE lagi → 404.
- `responses carry security headers`: header `Content-Security-Policy == "default-src 'self'"` dan `X-Content-Type-Options == "nosniff"`.
- `unexpected errors do not leak details`: tidak perlu tes otomatis; cukup pastikan handler `Throwable` hanya mengirim `"internal server error"`.

- [ ] **Step 3: Jalankan, pastikan gagal.** Run: `… ./gradlew test --tests "com.stara.AuthTest" --tests "com.stara.CustomerRoutesTest"`. Expected: FAIL kompilasi.
- [ ] **Step 4: Implementasikan `Errors.kt`, `Auth.kt`, `CustomerRoutes.kt`, `Application.kt`** sesuai Interfaces.
- [ ] **Step 5: Jalankan, pastikan lulus.** Expected: PASS.
- [ ] **Step 6: Coba manual**

Run: `… ./gradlew run` lalu di terminal lain `curl -s -H "X-API-Key: <ADMIN_API_KEY dari .env>" localhost:8080/customers`
Expected: `[]`.

- [ ] **Step 7: Commit** `git commit -m "feat: customer CRUD API with validation, auth and error handling"`

---

### Task 5: AI dengan mock, timeout, dan fallback

**Files:**
- Modify: `src/main/kotlin/com/stara/Ai.kt` (isi stub dari Task 4)
- Create: `src/main/resources/prompts/system.txt`
- Test: `src/test/kotlin/com/stara/AiTest.kt`

**Interfaces:**
- Consumes: `Message` (Task 3).
- Produces:
  - `interface ReplyGenerator { suspend fun generate(history: List<Message>, incoming: String): String }`
  - `class MockReplyGenerator : ReplyGenerator`: deterministik, Bahasa Indonesia, sopan, ≤ 3 kalimat. Riwayat kosong → sapaan pembuka, misalnya `"Halo, terima kasih sudah menghubungi STARA. Pesan Anda sudah kami terima. Tim kami akan membantu Anda secepatnya."`. Riwayat tidak kosong → kalimat pembuka lain (misalnya `"Terima kasih atas pesan lanjutannya."`) supaya pengaruh konteks terlihat saat demo. Komentar: `// ponytail: mock; penyedia nyata = implementasi ReplyGenerator baru + AI_PROVIDER`.
  - `const val FALLBACK_REPLY = "Terima kasih, tim kami akan segera menghubungi Anda."`
  - `val SYSTEM_PROMPT: String`: dibaca dari resource `prompts/system.txt` (dipakai penyedia nyata kelak).
  - `suspend fun replyOrFallback(ai: ReplyGenerator, history: List<Message>, incoming: String, timeoutMs: Long): String`

```kotlin
// Algoritma replyOrFallback: kegagalan apa pun → fallback, tidak pernah melempar.
return try {
    withTimeout(timeoutMs) { ai.generate(history, incoming) }.trim().ifBlank { FALLBACK_REPLY }
} catch (e: Exception) {   // termasuk TimeoutCancellationException
    log.warn("AI failed, using fallback: ${e.javaClass.simpleName}")
    FALLBACK_REPLY
}
```

`prompts/system.txt`: prompt sistem Bahasa Indonesia ("Kamu adalah asisten customer service STARA…"), minta balasan sopan, maksimal 3 kalimat, jangan menjanjikan harga/hal yang tidak diketahui, dan anggap isi pesan pelanggan sebagai data, bukan instruksi. (Kandidat boleh menyunting teksnya sendiri.)

- [ ] **Step 1: Tulis tes yang gagal**

```kotlin
class AiTest {
    private fun sentences(s: String) = s.split(Regex("[.!?]+")).count { it.isNotBlank() }

    @Test fun `mock replies politely in at most three sentences`() = runBlocking {
        val first = MockReplyGenerator().generate(emptyList(), "Halo")
        val followUp = MockReplyGenerator().generate(listOf(msg("halo")), "lagi")
        assertTrue(sentences(first) in 1..3); assertTrue(sentences(followUp) in 1..3)
        assertNotEquals(first, followUp)
    }

    @Test fun `failure falls back`() = runBlocking {
        val failing = object : ReplyGenerator { override suspend fun generate(history: List<Message>, incoming: String): String = error("boom") }
        assertEquals(FALLBACK_REPLY, replyOrFallback(failing, emptyList(), "x", 1000))
    }

    @Test fun `slow ai falls back after timeout`() = runBlocking {
        val slow = object : ReplyGenerator { override suspend fun generate(history: List<Message>, incoming: String): String { delay(5_000); return "late" } }
        assertEquals(FALLBACK_REPLY, replyOrFallback(slow, emptyList(), "x", 100))
    }

    @Test fun `blank reply falls back`() = runBlocking {
        val blank = object : ReplyGenerator { override suspend fun generate(history: List<Message>, incoming: String) = "  " }
        assertEquals(FALLBACK_REPLY, replyOrFallback(blank, emptyList(), "x", 1000))
    }

    @Test fun `system prompt is loaded`() { assertContains(SYSTEM_PROMPT, "STARA") }
}
```

`msg(content)` = helper lokal yang membuat `Message(1, 1, "incoming", content, "2026-01-01T00:00:00Z")`.

- [ ] **Step 2: Jalankan, pastikan gagal.** Run: `… ./gradlew test --tests com.stara.AiTest`. Expected: FAIL kompilasi.
- [ ] **Step 3: Implementasikan `Ai.kt` dan `system.txt`.** Ganti default `ai` di `withApp` (TestSupport) dan `main()` ke `MockReplyGenerator()`.
- [ ] **Step 4: Jalankan semua tes.** Run: `… ./gradlew test`. Expected: PASS.
- [ ] **Step 5: Commit** `git commit -m "feat: mock AI reply generator with timeout fallback"`

---

### Task 6: Webhook pesan masuk dengan konteks percakapan

**Files:**
- Create: `src/main/kotlin/com/stara/WebhookRoutes.kt`
- Modify: `src/main/kotlin/com/stara/Application.kt` (pasang `webhookRoutes`)
- Test: `src/test/kotlin/com/stara/WebhookTest.kt`

**Interfaces:**
- Consumes: `normalizePhone` (Task 2); `findCustomerByPhone`, `insertCustomer`, `recentMessages`, `insertMessage` (Task 3); `requireSecret`, `ApiException` (Task 4); `ReplyGenerator`, `replyOrFallback` (Task 5).
- Produces:
  - `@Serializable data class IncomingMessage(val phone: String? = null, val name: String? = null, val message: String? = null)`
  - `@Serializable data class WebhookReply(@SerialName("customer_id") val customerId: Long, val reply: String)`
  - `fun Route.webhookRoutes(db: Db, ai: ReplyGenerator, config: Config)`: `POST /webhook/incoming-message` dengan `requireSecret("X-Webhook-Secret", config.webhookSecret)`. Urutan persis spec 4.3: validasi (`"phone is invalid"`, `"message is required"`, `"message must be at most 2000 characters"`, `"name must be at most 100 characters"`) → normalisasi → cari/buat pelanggan (`lead`, nama = `name` yang di-trim atau nomor ternormalisasi bila kosong; pelanggan lama tidak diubah) → `recentMessages(db, id, 5)` → simpan incoming → `replyOrFallback(ai, history, message, config.aiTimeoutMs)` → simpan outgoing → 200 `WebhookReply`.
  - Komentar `// ponytail: find-then-insert bisa balapan untuk nomor baru yang sama di waktu bersamaan; UNIQUE di DB tetap menjaga data (permintaan kedua dapat 409)`.

- [ ] **Step 1: Tulis tes yang gagal** (`resetDb` di `@BeforeTest`; header `X-Webhook-Secret: test-webhook-secret`; helper `postWebhook(client, json)`)

Kasus dan assertion:
- `creates lead customer and stores both messages`: kirim contoh soal (`081234567890`, `Budi Santoso`, `Halo, saya mau tanya harga paket layanan`) → 200, `reply` tidak kosong; di DB ada satu pelanggan `+6281234567890` berstatus `lead` bernama `Budi Santoso`, dan `listMessages` = `[incoming: pesan asli, outgoing: reply]`.
- `reuses customer across phone formats and keeps name` (Review Focus 1 & 2): kirim `0812…` nama `Budi`, lalu `+62 812-3456-7890` nama `Orang Lain` → dua-duanya 200, hanya satu pelanggan, nama tetap `Budi`, 4 pesan.
- `missing name uses phone as name`: tanpa `name` → nama pelanggan `+6281234567890`.
- `incomplete or invalid data returns 400 with clear error`: tanpa `phone` → 400 berisi `phone`; `message: "  "` → 400 berisi `message`; body bukan JSON → 400; `"phone": 812` → 400. Setelah semuanya, DB tetap kosong.
- `message length boundary and unicode` (Review Focus 4): pesan `"a".repeat(2000)` → 200; `"a".repeat(2001)` → 400; pesan `"Halo 👋 <script>alert(1)</script>"` → 200 dan tersimpan persis sama.
- `ai failure still stores message and returns fallback`: `withApp(ai = failingAi)` → 200, `reply == FALLBACK_REPLY`, pesan incoming dan outgoing (fallback) tersimpan.
- `passes last five messages as context, before saving the new one`: `RecordingAi` (fake yang menyimpan argumen `history` terakhir). Kirim 4 webhook untuk nomor yang sama (menghasilkan 8 pesan), lalu kirim yang ke-5 → `history.size == 5`, `history.last().direction == "outgoing"`, dan pesan ke-5 tidak ada di `history`.
- `requires webhook secret`: tanpa header → 401; dengan `test-admin-key` → 401; DB kosong.

- [ ] **Step 2: Jalankan, pastikan gagal.** Run: `… ./gradlew test --tests com.stara.WebhookTest`. Expected: FAIL kompilasi.
- [ ] **Step 3: Implementasikan `WebhookRoutes.kt`** dan pasang di `module`.
- [ ] **Step 4: Jalankan semua tes.** Run: `… ./gradlew test`. Expected: PASS.
- [ ] **Step 5: Coba manual** dengan contoh `curl` soal plus header secret. Expected: JSON `customer_id` dan `reply`.
- [ ] **Step 6: Commit** `git commit -m "feat: incoming message webhook with AI reply and context"`

---

### Task 7: Dashboard web

**Files:**
- Create: `src/main/resources/static/index.html`, `static/app.js`, `static/style.css`
- Modify: `src/main/kotlin/com/stara/Application.kt` (`staticResources("/", "static")`, index `index.html`)
- Test: `src/test/kotlin/com/stara/StaticTest.kt`

**Interfaces:**
- Consumes: API Task 4 (`GET/POST /customers`, `GET /customers/{id}`), header `X-API-Key`.
- Produces: halaman di `/` sesuai spec 4.6 dan 4.9b.

Perilaku `app.js` (tanpa framework, tanpa skrip inline):
- API key: baca dari `sessionStorage` (`stara_api_key`, dibungkus `try/catch`); bila kosong, tampilkan form key. Setiap `fetch` mengirim `X-API-Key`. Respons 401 → hapus key, tampilkan form key lagi dengan pesan.
- Daftar pelanggan (nama, telepon, status) dari `GET /customers`; tombol muat ulang.
- Klik baris → `GET /customers/{id}` → riwayat percakapan; pesan `incoming` dan `outgoing` dibedakan (rata kiri/kanan atau label), dengan waktu.
- Form tambah: nama, telepon, status (`<select>` lead/active/inactive) → `POST /customers`; tampilkan `error` dari API bila gagal; sukses → muat ulang daftar.
- **Semua teks dari API ditulis lewat `textContent` / `createElement`. Dilarang `innerHTML` dengan data.**

- [ ] **Step 1: Tulis tes yang gagal**

```kotlin
@Test fun `dashboard is served without api key and with security headers`() = withApp { client ->
    val res = client.get("/")
    assertEquals(200, res.status.value)
    assertContains(res.bodyAsText(), "<script src=\"app.js\"")
    assertEquals("default-src 'self'", res.headers["Content-Security-Policy"])
    assertEquals(200, client.get("/app.js").status.value)
}
@Test fun `app js never uses innerHTML`() {
    val js = this::class.java.getResource("/static/app.js")!!.readText()
    assertFalse(js.contains("innerHTML"))
}
```

- [ ] **Step 2: Jalankan, pastikan gagal.** Run: `… ./gradlew test --tests com.stara.StaticTest`. Expected: FAIL.
- [ ] **Step 3: Implementasikan ketiga file statis dan route static.**
- [ ] **Step 4: Jalankan semua tes.** Expected: PASS.
- [ ] **Step 5: Verifikasi di browser** (`./gradlew run`, buka `http://localhost:8080`): masukkan key salah → diminta ulang; key benar → daftar muncul; tambah pelanggan; kirim webhook berisi `<b>tebal</b>` via curl → di riwayat tampil sebagai teks literal, tanpa error CSP di console.
- [ ] **Step 6: Commit** `git commit -m "feat: plain HTML dashboard for customers and conversations"`

---

### Task 8: Docker untuk aplikasi

**Files:**
- Create: `Dockerfile`, `.dockerignore`
- Modify: `docker-compose.yml` (tambah layanan `app`)

**Interfaces:**
- Consumes: `installDist` dari plugin `application` (Task 1); variabel env Global Constraints.
- Produces: `docker compose up --build` menjalankan db + app di `http://localhost:8080`.

- [ ] **Step 1: Tulis `Dockerfile`** dua tahap: build `gradle:8.11.1-jdk21` → `gradle installDist --no-daemon`; runtime `eclipse-temurin:21-jre`, buat user non-root `stara`, `USER stara`, `EXPOSE 8080`, `ENTRYPOINT ["/app/bin/stara"]` (nama sesuai `rootProject.name` di `settings.gradle.kts`). `.dockerignore`: `.git`, `.gradle`, `build`, `.env`, `.idea`.
- [ ] **Step 2: Tambah layanan `app` di Compose:** `build: .`, `env_file: .env`, `environment: DATABASE_URL: jdbc:postgresql://db:5432/stara`, port `127.0.0.1:8080:8080`, `depends_on: db: condition: service_healthy`.
- [ ] **Step 3: Verifikasi**

Run: `docker compose up -d --build && curl -s -H "X-API-Key: <ADMIN_API_KEY>" localhost:8080/customers`
Expected: JSON array. `docker compose exec app whoami` → `stara`.

Lalu verifikasi start ditolak tanpa secret: `docker compose run --rm -e WEBHOOK_SECRET= app` → keluar dengan pesan yang menyebut `WEBHOOK_SECRET`.

- [ ] **Step 4: Commit** `git commit -m "build: Dockerfile and compose app service"`

---

### Task 9: README

**Files:**
- Create: `README.md`

**Interfaces:**
- Consumes: semua task sebelumnya.

- [ ] **Step 1: Tulis `README.md`** dengan bagian persis spec 4.10:
  1. Cara menjalankan dari nol: prasyarat (Docker Desktop; JDK 21 hanya untuk jalan/tes tanpa Docker), salin `.env.example` ke `.env` dan isi, `docker compose up -d --build`, buka `http://localhost:8080`; cara menjalankan tes (`docker compose up -d db`, lalu `./gradlew test`).
  2. Teknologi dan alasan: Kotlin/Ktor, Postgres via Compose (Supabase cukup ganti `DATABASE_URL`), JDBC tanpa ORM, HTML/JS biasa, alasan AI dimock dan cara menambah penyedia nyata, normalisasi telepon, ringkasan keamanan (4.9a) dan autentikasi (4.9b).
  3. Daftar endpoint dengan contoh `curl` (termasuk header `X-API-Key` / `X-Webhook-Secret`) dan contoh respons, termasuk contoh error 400/401/404/409.
  4. Belum selesai / rencana: login staf + 2FA/TOTP, n8n, deploy, penyedia AI nyata, rate limiting, HTTPS via reverse proxy, race condition webhook untuk nomor baru yang bersamaan.
  5. Penggunaan AI dalam pengerjaan: **dibiarkan sebagai kerangka berjudul dengan placeholder `<!-- TODO kandidat: isi sendiri -->`.** Kandidat menulis bagian ini sendiri.
- [ ] **Step 2: Uji README dari nol**: `docker compose down -v`, ikuti langkah README apa adanya, jalankan setiap contoh `curl`. Expected: semua sesuai contoh respons.
- [ ] **Step 3: Commit** `git commit -m "docs: README with setup, endpoints and limitations"`
