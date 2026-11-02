# STARA Mini Pipeline

Versi mini dari alur **Pelanggan → API → Database → AI → Tampilan Web**.
Pesan WhatsApp disimulasikan lewat webhook, disimpan di PostgreSQL, dibalas otomatis oleh AI (saat ini mock), dan dipantau staf lewat dashboard sederhana.

## 1. Cara menjalankan dari nol

**Prasyarat:** Docker Desktop. JDK 21 hanya diperlukan untuk menjalankan tes atau aplikasi tanpa Docker.

```bash
git clone <repo-url> stara && cd stara
cp .env.example .env
```

Isi `.env`: `DB_PASSWORD`, `WEBHOOK_SECRET`, dan `ADMIN_API_KEY` dengan string acak (misalnya hasil `openssl rand -hex 24`). Aplikasi menolak start bila `WEBHOOK_SECRET` atau `ADMIN_API_KEY` kosong.

```bash
docker compose up -d --build
```

Buka <http://localhost:8080>, masukkan `ADMIN_API_KEY` dari `.env`.
Saat pertama kali dibuat, Postgres otomatis menjalankan `schema.sql` (lewat `db/init.sh`) untuk database `stara` dan database tes `stara_test`.

**Menjalankan tes** (butuh JDK 21 dan Postgres dari Compose):

```bash
docker compose up -d db
./gradlew test
```

**Menjalankan aplikasi tanpa Docker** (Postgres tetap dari Compose): `./gradlew run`. Nilai dibaca dari `.env`, dan variabel environment sungguhan menimpanya.

## 2. Teknologi dan alasannya

| Bagian | Pilihan | Alasan |
|---|---|---|
| Bahasa & server | Kotlin + Ktor 3 | Bahasa yang paling saya kuasai; Ktor ringan dan cukup untuk REST, webhook, dan file statis dalam satu aplikasi. |
| Database | PostgreSQL 17 via Docker Compose | Sesuai saran soal. Supabase cukup dengan mengganti `DATABASE_URL`, tanpa ubah kode. |
| Akses DB | JDBC biasa + `PreparedStatement` | Tanpa ORM supaya setiap query terlihat dan mudah dijelaskan; semua query berparameter (aman dari SQL injection). |
| Web | HTML + CSS + JavaScript biasa | Soal hanya butuh halaman fungsional; tanpa build step dan tanpa framework. |
| AI | Mock (`MockReplyGenerator`) | Lihat di bawah. |
| Tes | `kotlin.test` + Ktor test host | Tes HTTP sungguhan terhadap database tes, tanpa pustaka tambahan. |

**Kenapa AI dimock.** Penyedia AI nyata belum dipilih. Mock membalas dalam Bahasa Indonesia, sopan, maksimal 3 kalimat. Ia mengenali tiga topik lewat kata kunci (harga, jam buka, komplain); di luar itu ia memakai balasan umum yang berbeda bila sudah ada riwayat percakapan. Semua AI berada di balik antarmuka `ReplyGenerator` (`src/main/kotlin/com/stara/Ai.kt`), jadi menambah Gemini/Claude/lainnya berarti satu implementasi baru yang membaca `AI_API_KEY` dan prompt sistem di `src/main/resources/prompts/system.txt`, lalu dipilih lewat `AI_PROVIDER`.
Untuk mendemokan balasan cadangan, set `AI_PROVIDER=mock-error` (selalu gagal) atau `mock-slow` (selalu melewati batas waktu) di `.env`, lalu `docker compose up -d`. Kembalikan ke `mock` setelahnya.
Batas tunggu AI adalah `AI_TIMEOUT_MS` (default 8000 ms). Bila AI gagal, lambat, atau membalas kosong, pesan tetap tersimpan dan balasan cadangan dikirim: *"Terima kasih, tim kami akan segera menghubungi Anda."*

**Konteks percakapan.** Sebelum pesan baru disimpan, 5 pesan terakhir pelanggan diambil dan dikirim ke AI sebagai riwayat.

**Normalisasi nomor telepon.** `081234567890`, `6281234567890`, `+62 812-3456-7890`, dan `+62 0812 3456 7890` semuanya disimpan sebagai `+6281234567890`, jadi dianggap pelanggan yang sama. Hanya nomor Indonesia yang diterima (8–13 digit setelah `+62`). Aturan yang sama juga dipasang sebagai `CHECK` di database.

**Keamanan.**
- Autentikasi dengan dua kunci terpisah: `X-Webhook-Secret` untuk webhook (meniru secret bersama milik penyedia WhatsApp) dan `X-API-Key` untuk `/customers`. Perbandingan kunci memakai waktu konstan.
- Dashboard menyimpan API key di `sessionStorage` (hilang saat tab ditutup); halaman dimuat tanpa key, tapi datanya tidak.
- Semua isi pesan ditampilkan sebagai teks (`textContent`), ditambah header `Content-Security-Policy: default-src 'self'` dan `X-Content-Type-Options: nosniff`.
- Validasi di kode dan di database (panjang nama ≤ 100, pesan ≤ 2000, status, format telepon). JSON rusak → 400, bukan 500.
- Error ke klien hanya `{"error": "..."}`; detail teknis hanya di log server.
- Rahasia hanya di `.env` (tidak masuk git). Di Compose, port Postgres dan aplikasi hanya terbuka di `127.0.0.1`, dan aplikasi berjalan sebagai user non-root.
- Pesan pelanggan diperlakukan sebagai data, terpisah dari prompt sistem (mitigasi prompt injection saat penyedia nyata dipasang).

## 3. Endpoint

Contoh di bawah memakai `$ADMIN_API_KEY` dan `$WEBHOOK_SECRET` dari `.env`. Semua respons berupa JSON; error selalu berbentuk `{"error": "pesan"}`.

| Method | Path | Header | Sukses |
|---|---|---|---|
| POST | `/customers` | `X-API-Key` | 201 |
| GET | `/customers` (`?status=lead\|active\|inactive`) | `X-API-Key` | 200 |
| GET | `/customers/{id}` (beserta `messages`) | `X-API-Key` | 200 |
| PUT | `/customers/{id}` | `X-API-Key` | 200 |
| DELETE | `/customers/{id}` (pesan ikut terhapus) | `X-API-Key` | 204 |
| POST | `/webhook/incoming-message` | `X-Webhook-Secret` | 200 |

**Tambah pelanggan**
```bash
curl -X POST http://localhost:8080/customers \
  -H "X-API-Key: $ADMIN_API_KEY" -H "Content-Type: application/json" \
  -d '{"name":"Budi Santoso","phone":"0812-3456-7890"}'
```
```json
{"id":1,"name":"Budi Santoso","phone":"+6281234567890","status":"lead","created_at":"2026-11-02T04:16:37.352685Z"}
```

**Daftar pelanggan (filter status)**
```bash
curl "http://localhost:8080/customers?status=active" -H "X-API-Key: $ADMIN_API_KEY"
```

**Satu pelanggan beserta riwayat pesan**
```bash
curl http://localhost:8080/customers/1 -H "X-API-Key: $ADMIN_API_KEY"
```
```json
{"id":1,"name":"Budi Santoso","phone":"+6281234567890","status":"lead","created_at":"…",
 "messages":[{"id":1,"customer_id":1,"direction":"incoming","content":"Halo…","created_at":"…"}]}
```

**Ubah pelanggan** (`status` boleh dikosongkan untuk mempertahankan status lama)
```bash
curl -X PUT http://localhost:8080/customers/1 \
  -H "X-API-Key: $ADMIN_API_KEY" -H "Content-Type: application/json" \
  -d '{"name":"Budi S.","phone":"081234567890","status":"active"}'
```

**Hapus pelanggan**
```bash
curl -X DELETE http://localhost:8080/customers/1 -H "X-API-Key: $ADMIN_API_KEY"
```

**Webhook pesan masuk**
```bash
curl -X POST http://localhost:8080/webhook/incoming-message \
  -H "X-Webhook-Secret: $WEBHOOK_SECRET" -H "Content-Type: application/json" \
  -d '{"phone":"081234567890","name":"Budi Santoso","message":"Halo, saya mau tanya harga paket layanan"}'
```
```json
{"customer_id":1,"reply":"Halo, terima kasih sudah menghubungi STARA. Pesan Anda sudah kami terima. Tim kami akan membantu Anda secepatnya."}
```

**Contoh error**

| Kasus | Status | Body |
|---|---|---|
| Header kunci hilang/salah | 401 | `{"error":"unauthorized"}` |
| Nama kosong | 400 | `{"error":"name is required"}` |
| Telepon tidak valid (mis. `"0"`) | 400 | `{"error":"phone is invalid"}` |
| Status di luar daftar | 400 | `{"error":"status must be one of lead, active, inactive"}` |
| Webhook tanpa pesan | 400 | `{"error":"message is required"}` |
| Body bukan JSON | 400 | `{"error":"invalid JSON body"}` |
| Pelanggan tidak ada | 404 | `{"error":"customer not found"}` |
| Nomor sudah dipakai (format apa pun) | 409 | `{"error":"phone already exists"}` |

## 4. Belum selesai / rencana perbaikan

- **Login staf + 2FA (TOTP)** untuk dashboard, menggantikan API key bersama.
- **Penyedia AI nyata** (Gemini/Claude) sebagai implementasi `ReplyGenerator` kedua.
- **n8n**: workflow yang meneruskan webhook atau mengirim notifikasi ke staf.
- **Deploy** (belum ada VPS/hosting) beserta HTTPS lewat reverse proxy.
- **Rate limiting** pada webhook dan API.
- **Race condition** kecil: dua webhook pertama untuk nomor baru yang sama pada saat bersamaan bisa membuat permintaan kedua mendapat 409 (data tetap aman karena `UNIQUE`). Perbaikannya `INSERT … ON CONFLICT`.
- Pool koneksi database (saat ini satu koneksi per query; cukup untuk skala tes ini).

## 5. Penggunaan AI dalam pengerjaan

Hampir seluruh proyek ini dikerjakan dengan bantuan AI (Claude Code), memakai alur *spec-driven*: spesifikasi dan rencana ditulis dulu, lalu kode dan tes dibuat mengikutinya. Saya merancang sistem ini bersama AI: saya menentukan arah dan aturan, AI mengusulkan desain rincinya dan saya menyetujuinya.

**Keputusan saya (arah dan aturan):**
- Stack: Kotlin + Ktor (bahasa yang saya kuasai), PostgreSQL via Docker Compose, dan dashboard HTML/JS biasa.
- Ruang lingkup: AI dimock dulu dengan batas waktu 8 detik; n8n dan deploy ditunda. Saya juga memilih skenario mock dan mode demo fallback.
- Aturan data: menghapus pelanggan ikut menghapus semua datanya (pesan), karena data pelanggan harus bersih saat dihapus.
- Kasus tepi nomor telepon (`+62`, `62`, `0` saja) dan normalisasi ke format `+62`.
- Keamanan sebagai fokus: tanpa rahasia di repo, dan autentikasi dua header secret. Saya sempat mengusulkan 2FA; saya sepakat menundanya dan mencatatnya sebagai pekerjaan lanjutan.
- Menjalankan dan mencoba aplikasi sendiri: dashboard, webhook lewat curl, dan demo fallback `mock-slow`.

**Diusulkan AI, saya setujui (desain rinci):**
- Pembagian file dan lapisan (`Repository`, `Errors`, `Auth`, dan lainnya), antarmuka `ReplyGenerator`, format error seragam, konteks 5 pesan, dan header keamanan (CSP, `nosniff`). Alasannya tercatat di `docs/superpowers/specs/`.

**Dikerjakan AI (implementasi):**
- Menulis spesifikasi dan rencana (`docs/superpowers/`), seluruh kode Kotlin/Ktor, `schema.sql`, Docker, halaman dashboard, dan 50 tes otomatis (tes ditulis lebih dulu, lalu kodenya).
- Menulis draf README ini, menjalankan review kode akhir oleh agen terpisah, lalu memperbaiki temuannya.
