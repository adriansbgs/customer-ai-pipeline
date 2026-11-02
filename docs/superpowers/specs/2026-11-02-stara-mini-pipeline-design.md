# STARA Take-Home Test: Desain Mini Pipeline

Tanggal: 2026-11-02
Sumber soal: `Take-Home-Test-STARA.pdf` (Tugas 1–6 wajib, nilai plus opsional)
Metode: Spec-Driven Development (spec dulu, kode kemudian)

## 1. Maksud dan alasan

STARA membangun sistem yang menghubungkan Website, Database, API, Automation, WhatsApp, dan AI. Tes ini meminta versi mini dari alur:

> Pelanggan → API → Database → AI → Tampilan Web

Skenario: sebuah bisnis menerima pesan pelanggan lewat WhatsApp. Pesan disimpan, dibalas otomatis oleh AI secara sopan dan singkat, lalu dipantau staf lewat halaman dashboard sederhana. WhatsApp tidak disambungkan sungguhan; pesan masuk disimulasikan lewat webhook.

Penilaian: fungsi dan kerapian (bukan keindahan), pilihan stack, dan pemahaman kandidat. Kandidat harus bisa menjelaskan dan memodifikasi **setiap baris** kode di sesi review video (45–60 menit), termasuk mencari penyebab bug sederhana. Karena itu kode harus kecil, polos, dan mudah dibaca.

Kandidat nyaman dengan Kotlin dan belum paham React Native. Kode ditulis dengan bantuan AI penuh, jadi README wajib jujur soal bagian yang dibantu AI dan bagian yang diubah sendiri.

## 2. Tujuan, bukan-tujuan, dan anti-tujuan

**Tujuan**
- Semua Tugas wajib 1–6 selesai dan berjalan dari nol dengan langkah di README.
- Bonus: Docker, konteks percakapan (3–5 pesan terakhir), tes otomatis.
- Kode sederhana dan bisa dijelaskan kandidat baris per baris.

**Bukan-tujuan (ditunda atau dilewati)**
- Deploy: dilewati (tidak ada VPS/hosting).
- n8n: ditunda, dikerjakan belakangan kalau waktu ada.
- Login staf dan 2FA/TOTP: tidak dikerjakan; dicatat di README sebagai rencana lanjutan. (Autentikasi sederhana berbasis kunci rahasia tetap dikerjakan, lihat 4.9b.)
- Integrasi WhatsApp nyata, paginasi, tool migrasi database, multi-bahasa.
- Penyedia AI nyata (Gemini/Claude/lainnya): ditunda; lihat bagian 6.

**Anti-tujuan (dianggap gagal walau secara teknis jalan)**
- API key atau kata sandi masuk ke kode atau repositori.
- Webhook mengembalikan error 500 hanya karena AI gagal atau lambat.
- Kode terlalu rumit sehingga kandidat tidak bisa menjelaskannya.
- Riwayat pelanggan tersisa setelah pelanggan dihapus.

## 3. Keputusan yang sudah diambil kandidat

- Bahasa dan kerangka: Kotlin dengan Ktor.
- Database: PostgreSQL, dijalankan lewat Docker Compose untuk pengembangan. Supabase opsional nanti; cukup mengganti `DATABASE_URL`.
- Web: HTML + CSS + JavaScript biasa, disajikan langsung oleh Ktor. Tanpa React/React Native.
- AI: **mock dulu**, balasan palsu yang rapi. README menjelaskannya sebagai mock. Penyedia nyata dipilih nanti, bebas (Gemini/Claude/lainnya). Kandidat punya API key Gemini.
- Batas tunggu AI: **8 detik**. Lewat dari itu atau gagal → balasan cadangan.
- Hapus pelanggan: **semua pesannya ikut terhapus** (cascade).
- Bonus yang dikerjakan: Docker, konteks percakapan, tes otomatis, autentikasi sederhana (dua kunci rahasia: satu untuk webhook, satu untuk API/dashboard). 2FA ditolak untuk sekarang karena webhook dipanggil mesin dan login + TOTP terlalu besar untuk waktu tes.
- Nomor telepon dinormalisasi agar `0…`, `62…`, dan `+62…` dianggap sama.
- Keamanan diperhatikan secara eksplisit (bagian 4.9a).

## 4. Spesifikasi

### 4.1 Database (Tugas 1) → `schema.sql`

`customers`
- `id` kunci utama (auto-increment)
- `name` teks, wajib, tidak kosong
- `phone` teks, wajib, **unik**, selalu disimpan dalam bentuk ternormalisasi (lihat 4.1a)
- `status` salah satu dari `lead`, `active`, `inactive`; default `lead`
- `created_at` timestamp dengan zona waktu, default sekarang

`messages`
- `id` kunci utama
- `customer_id` kunci asing ke `customers.id`, `ON DELETE CASCADE`, wajib
- `direction` salah satu dari `incoming`, `outgoing`
- `content` teks, wajib
- `created_at` timestamp dengan zona waktu, default sekarang

Batas panjang: `name` ≤ 100 karakter, `content` ≤ 2000 karakter.

Aturan (CHECK, NOT NULL, UNIQUE, batas panjang, format telepon `^\+62[0-9]{8,13}$`) ditaruh di database, bukan hanya di kode. `schema.sql` juga dimuat otomatis saat Postgres di Compose pertama kali dibuat.

### 4.1a Normalisasi nomor telepon

Satu fungsi normalisasi dipakai di **semua** jalur (create, update, webhook, pencarian), agar `0812…`, `62812…`, dan `+62812…` dianggap pelanggan yang sama. Bentuk simpanan: `+62` diikuti digit (format E.164 Indonesia).

Langkah:
1. `trim`, lalu buang spasi, `-`, `.`, `(`, `)`.
2. Sisa harus hanya digit, boleh diawali satu `+`. Selain itu → tidak valid.
3. Ubah awalan:
   - `+62…` → tetap
   - `62…` → `+62…`
   - `0…` → `+62` + sisanya tanpa `0`
   - lainnya (misalnya `+1…`, `812…` tanpa awalan) → tidak valid
4. Kalau setelah `+62` masih diawali `0` (contoh `+62 0812…`), buang `0` itu.
5. Jumlah digit setelah `+62` harus 8–13. Di luar itu → tidak valid.

Contoh:

| Masukan | Hasil |
|---|---|
| `081234567890` | `+6281234567890` |
| `6281234567890` | `+6281234567890` |
| `+62 812-3456-7890` | `+6281234567890` |
| `+62 0812 3456 7890` | `+6281234567890` |
| `0` | tidak valid (400) |
| `+1 555 123 4567` | tidak valid (400) |
| `08abc` | tidak valid (400) |
| `` (kosong) | tidak valid (400) |

Fungsi ini punya tes unit sendiri yang memuat semua contoh di atas.

### 4.2 REST API Customer (Tugas 2)

Semua respons JSON.

| Aksi | Endpoint | Sukses | Catatan |
|---|---|---|---|
| Create | `POST /customers` | 201 + objek pelanggan | body: `name`, `phone`, `status` (opsional, default `lead`) |
| List | `GET /customers` | 200 + array | filter `?status=active`; status tak dikenal → 400 |
| Detail | `GET /customers/{id}` | 200 | pelanggan + `messages` (urut dari terlama ke terbaru) |
| Update | `PUT /customers/{id}` | 200 + objek | ubah `name`, `phone`, `status`, divalidasi sama seperti create |
| Delete | `DELETE /customers/{id}` | 204 | pesan ikut terhapus |

Kode status: 400 data tidak valid (nama kosong/terlalu panjang, telepon tidak valid menurut 4.1a, status di luar daftar, JSON rusak, `id` bukan angka), 404 tidak ditemukan, 409 nomor telepon (setelah dinormalisasi) sudah dipakai, 201 saat membuat. Kolom yang tidak dikenal di body diabaikan.
Bentuk error seragam: `{"error": "pesan singkat"}`.

### 4.3 Webhook pesan masuk (Tugas 3)

`POST /webhook/incoming-message`, body:
```json
{ "phone": "081234567890", "name": "Budi Santoso", "message": "Halo, saya mau tanya harga paket layanan" }
```

Alur:
1. Validasi: `phone` wajib dan valid (4.1a), `message` wajib, tidak kosong, ≤ 2000 karakter; kalau tidak → 400 dengan `{"error": "..."}` yang menyebut kolom yang bermasalah. `name` opsional (≤ 100 karakter).
2. Normalisasi `phone`, lalu cari pelanggan berdasarkan nomor ternormalisasi. Belum ada → buat dengan status `lead` (nama = `name`, atau nomor telepon bila `name` kosong). Pelanggan yang sudah ada tidak diubah namanya.
3. Ambil riwayat (lihat 4.5), lalu simpan pesan masuk (`direction = incoming`).
4. Minta balasan ke AI (4.4).
5. Simpan balasan (`direction = outgoing`).
6. Kembalikan 200: `{"customer_id": 1, "reply": "..."}`.

Status pelanggan tidak berubah otomatis oleh webhook.

### 4.4 Integrasi AI (Tugas 4)

- Antarmuka kecil satu fungsi: terima riwayat + pesan baru, kembalikan teks balasan. Implementasi sekarang: `MockReplyGenerator`. Mengganti ke penyedia nyata nanti = menambah satu implementasi + memilihnya lewat `.env` (`AI_PROVIDER`).
- Balasan: Bahasa Indonesia, sopan, **maksimal 3 kalimat**.
- Prompt sistem ("Kamu adalah asisten customer service STARA...") ditulis kandidat sendiri dan ditaruh di file terpisah (`src/main/resources/prompts/system.txt`). Mock tidak memakainya, tapi file tetap ada dan dipakai penyedia nyata kelak.
- Timeout 8 detik (`AI_TIMEOUT_MS=8000`). Gagal, error, atau timeout → balasan cadangan: `"Terima kasih, tim kami akan segera menghubungi Anda."`. Pesan masuk **tetap tersimpan** dan balasan cadangan juga disimpan sebagai outgoing.
- Mock bisa disuruh gagal atau lambat (lewat tes) supaya jalur cadangan bisa diuji.

### 4.5 Konteks percakapan (bonus)

Sebelum menyimpan pesan baru, ambil **5 pesan terakhir** milik pelanggan itu dan kirim ke AI sebagai riwayat (urut dari terlama). Mock boleh mengabaikannya, tapi tes memastikan riwayat benar-benar diteruskan.

### 4.6 Halaman web (Tugas 5)

Satu halaman `index.html` (+ JS) disajikan di `/`, memanggil API yang sama.
- Daftar pelanggan: nama, telepon, status.
- Klik pelanggan → tampil riwayat percakapan (masuk dan balasan).
- Form tambah pelanggan baru; pesan error dari API ditampilkan.
- Teks dari pelanggan dan pesan **harus ditampilkan sebagai teks biasa** (bukan HTML), agar pesan jahat tidak bisa menyuntik skrip.
- Tampilan tidak perlu indah.

### 4.7 Docker (bonus)

- `Dockerfile` untuk aplikasi Ktor (build bertahap).
- `docker-compose.yml`: layanan `db` (Postgres) dan `app`. `schema.sql` dimuat otomatis ke `db`. Satu perintah menjalankan semuanya.
- Konfigurasi dibaca dari `.env`.

### 4.8 Tes otomatis (bonus)

Beberapa tes sederhana (tanpa kerangka berat):
- Normalisasi telepon: semua contoh di tabel 4.1a.
- Autentikasi: tanpa header atau key salah → 401 untuk webhook dan `/customers`; key webhook tidak berlaku di `/customers` dan sebaliknya.
- Validasi CRUD: 201, 400 (nama kosong, telepon tidak valid, status salah, JSON rusak), 404, 409 telepon ganda (termasuk `0812…` vs `+62812…`).
- Webhook: pelanggan baru dibuat sebagai lead; pelanggan lama dipakai ulang; data tidak lengkap → 400; dua pesan tersimpan (incoming dan outgoing).
- Fallback: AI yang dibuat gagal/timeout → tetap 200 dengan balasan cadangan, dan pesan tersimpan.
- Konteks: riwayat terakhir diteruskan ke AI.
- Cascade: hapus pelanggan → pesannya hilang.

### 4.9 Konfigurasi dan keamanan

`.env` tidak masuk repositori (`.gitignore`). `.env.example` berisi nama variabel saja:
`PORT`, `DATABASE_URL`, `DB_USER`, `DB_PASSWORD`, `AI_PROVIDER` (default `mock`), `AI_API_KEY` (kosong), `AI_TIMEOUT_MS`, `WEBHOOK_SECRET`, `ADMIN_API_KEY`.

Aplikasi menolak start (pesan jelas) bila `WEBHOOK_SECRET` atau `ADMIN_API_KEY` kosong, agar tidak pernah berjalan tanpa pengaman.

### 4.9b Autentikasi

Dua kunci rahasia terpisah dari `.env`:

| Endpoint | Header | Kunci |
|---|---|---|
| `POST /webhook/incoming-message` | `X-Webhook-Secret` | `WEBHOOK_SECRET` (meniru secret bersama milik penyedia WhatsApp) |
| Semua `/customers…` | `X-API-Key` | `ADMIN_API_KEY` |
| `/` dan file statis dashboard | tidak perlu | halaman boleh dimuat, tapi datanya hanya bisa diambil dengan key |

- Header hilang atau salah → 401 `{"error": "unauthorized"}`; pemeriksaan terjadi sebelum validasi body.
- Perbandingan memakai fungsi waktu-konstan (`MessageDigest.isEqual`) agar tahan timing attack.
- Dashboard meminta API key sekali, menyimpannya di `sessionStorage` (hilang saat tab ditutup), mengirimnya di setiap permintaan, dan meminta ulang bila mendapat 401.
- Key tidak pernah di-log.

### 4.9a Keamanan

| Ancaman | Penanganan |
|---|---|
| SQL injection | Semua query memakai parameter (prepared statement). Tidak ada SQL yang dirangkai dari string masukan. |
| XSS di dashboard | Isi dari pengguna ditampilkan lewat `textContent`, tidak pernah `innerHTML`. JS di file terpisah (tanpa skrip inline), dan header `Content-Security-Policy: default-src 'self'` + `X-Content-Type-Options: nosniff`. |
| Data sampah / terlalu besar | Validasi di 4.1a dan 4.2, batas panjang di kode dan di database. JSON rusak → 400, bukan 500. |
| Bocor informasi lewat error | Klien hanya menerima `{"error": "..."}` singkat. Stack trace dan detail SQL hanya masuk log server; error tak terduga → 500 dengan pesan umum. |
| Rahasia bocor | API key dan kata sandi hanya di `.env`; tidak pernah di-log atau dikirim ke klien. |
| Prompt injection lewat pesan pelanggan | Pesan pelanggan diperlakukan sebagai data, dipisah dari prompt sistem. Balasan AI hanya disimpan dan ditampilkan sebagai teks, tidak pernah dieksekusi. (Relevan saat penyedia nyata dipasang.) |
| Akses dari luar | Tidak ada CORS (dashboard satu origin dengan API). Di Compose, port Postgres hanya terikat ke `127.0.0.1`; aplikasi di container berjalan sebagai pengguna non-root. |
| Akses tanpa izin ke endpoint | Kunci rahasia per jenis endpoint, lihat 4.9b. |

Tidak dikerjakan (dicatat di README sebagai keterbatasan): rate limiting, HTTPS (diserahkan ke reverse proxy saat deploy).

### 4.10 README (Tugas 6)

Wajib memuat: (1) cara menjalankan dari nol, langkah demi langkah; (2) teknologi dan alasannya, termasuk alasan AI dimock; (3) daftar endpoint dengan contoh `curl` (termasuk header kunci); (4) hal yang belum selesai atau akan diperbaiki (login staf + 2FA/TOTP, n8n, deploy, penyedia AI nyata, rate limiting); (5) penggunaan AI dalam pengerjaan: bagian yang dibantu AI dan bagian yang diubah/diperbaiki sendiri oleh kandidat. Bagian (5) ditulis kandidat sendiri dan jujur.

## 5. Yang dikumpulkan

Repositori GitHub (atau ZIP) dengan README.md, `schema.sql`, `.env.example`, Docker files, kode, dan tes. Opsional: video demo maksimal 3 menit.

## 6. Diserahkan ke pembangun (builder)

- Struktur Gradle/paket, versi JDK/Ktor, dan pustaka JSON/DB (disarankan paling sedikit dependensi; JDBC biasa atau Exposed, pilih yang paling mudah dijelaskan).
- Cara menyiapkan database untuk tes (saran saya: database terpisah `stara_test` di Postgres Compose; tanpa Testcontainers).
- Teks mock balasan (harus Bahasa Indonesia, sopan, ≤ 3 kalimat) dan teks prompt sistem.
- Gaya dan tata letak halaman web.

## 7. Keputusan yang saya ambil sendiri (belum diputuskan kandidat; mohon dikoreksi)

1. `phone` dibuat UNIK (atas nilai ternormalisasi) → 409 saat ganda.
2. Hanya nomor Indonesia (+62) yang diterima; bentuk simpanan `+62…`; 8–13 digit setelah `+62`.
3. Webhook tanpa `name` memakai nomor telepon sebagai nama pelanggan baru.
4. Webhook mengembalikan `{"customer_id", "reply"}`.
5. Riwayat konteks = 5 pesan, dan diambil sebelum pesan baru disimpan.
6. Teks balasan cadangan persis: "Terima kasih, tim kami akan segera menghubungi Anda."
7. Bentuk error seragam `{"error": "..."}`.
8. Status pelanggan tidak berubah otomatis oleh webhook.
9. Mock AI dipilih lewat `AI_PROVIDER=mock`.
10. Dashboard menampilkan semua isi sebagai teks biasa (anti-XSS).
11. Batas panjang: nama 100, pesan 2000 karakter.

12. Header bernama `X-Webhook-Secret` dan `X-API-Key`; halaman dashboard sendiri tidak dikunci, hanya datanya.
13. Aplikasi menolak start tanpa kedua kunci.
