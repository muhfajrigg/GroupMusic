# GroupMusic (Bahasa Indonesia)

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java](https://img.shields.io/badge/Java-21%2B-blue.svg)](https://adoptium.net/)
[![Platform](https://img.shields.io/badge/Platform-Paper-green.svg)](https://papermc.io/)
[![Simple Voice Chat](https://img.shields.io/badge/Simple%20Voice%20Chat-2.6.0%2B-blueviolet.svg)](https://modrinth.com/plugin/simple-voice-chat)

> **Plugin musik ala bot Discord untuk grup Simple Voice Chat di server Paper.**  
> Dibuat oleh **muhfajrigg** ([@muhfajrigg](https://github.com/muhfajrigg)).

---

## 🌟 Ringkasan

**GroupMusic** memutar musik berkualitas tinggi langsung di dalam grup suara Minecraft menggunakan [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) (tekan `V`). Pemain yang berada di grup voice chat yang sama dapat memutar, menjeda, mengulang, dan mengatur antrean lagu yang **hanya terdengar oleh anggota grup tersebut**.

Tiap grup memiliki antrean dan sesi pemutar tersendiri. Sumber audio utama diputar dari YouTube (dengan fallback otomatis ke SoundCloud jika gagal), serta mendukung pembacaan link dari Spotify, Apple Music, Deezer, dan lainnya.

---

## 🚀 Mengapa Memilih GroupMusic?

Sebagian besar plugin musik voice chat yang ada saat ini sering mengalami error saat memutar YouTube karena pemblokiran cipher anti-bot, atau mewajibkan setup token API yang rumit. **GroupMusic dirancang khusus untuk mengatasi masalah-masalah tersebut:**

| Fitur | Plugin SVC Musik Biasa | **GroupMusic** |
|---|---|---|
| **Blokir Cipher YouTube (SABR / 403)** | ❌ Sering macet / error tidak bisa diputar | ✅ **Remote cipher decoder + auto-fallback ke SoundCloud** |
| **Pencarian Link YouTube** | ⚠️ Wajib ketik prefix ribet ytsearch: | ✅ **Bisa langsung copas link atau judul apa saja** |
| **Playlist & Album Spotify** | ❌ Wajib daftar token Spotify Developer | ✅ **Scraper embed otomatis (Tanpa perlu API Spotify)** |
| **Pencarian Cerdas** | ⚠️ Harus judul persis | ✅ **AI Assistant opsional untuk tebak lirik typo & suasana** |
| **Keamanan Jaringan** | ❌ Rentan eksploitasi SSRF IP lokal server | ✅ **Proteksi SSRF aktif memblokir IP privat/lokal** |
| **Performa & TPS Server** | ⚠️ Berisiko membebani main thread server | ✅ **100% Asynchronous via audio pipeline Netty** |

---

## ✨ Fitur Unggulan

- ⚡️ **Asynchronous & Zero-Lag Architecture:** Seluruh alur pencarian lagu, resolusi cipher YouTube, scraping embed Spotify, dan streaming audio berjalan 100% asynchronous di luar main server thread. Audio buffer langsung dialirkan ke Simple Voice Chat via worker threads independen, menjaga TPS server tetap stabil di 20.0.
- 🎧 **Isolasi Audio per Grup:** Setiap grup voice chat memiliki bot musik dan antrean sendiri tanpa saling bertabrakan dengan grup lain.
- 🔁 **Mode Pengulangan:** Mendukung pengulangan satu lagu (*track*), pengulangan seluruh antrean (*queue*), dan acak (*shuffle*).
- 🔗 **Dukungan Link Lengkap:**
  - Pemutaran langsung: YouTube, SoundCloud, Bandcamp.
  - Pembacaan judul otomatis: Spotify, Apple Music, Deezer, TIDAL, Amazon Music, Genius, Shazam.
  - Mendukung playlist & album Spotify baik lewat API resmi maupun scraping embed publik (tanpa butuh Spotify API/Premium).
- 🛡️ **Pemulihan Otomatis (*Fallback Recovery*):**
  - Jika lagu YouTube gagal diputar (misalnya karena pembatasan login atau cipher SABR), GroupMusic otomatis membersihkan judul video dan mencarikan lagu yang sama di SoundCloud.
  - Perlindungan SSRF: memblokir IP privat/lokal dan membatasi ukuran unduhan web.
- 🔑 **Login YouTube Langsung di Game (`/musiclogin` atau `/lagulogin`):**
  - Menangkap kode aktivasi Google secara otomatis dari log server. Kode dan tautan login tampil di chat (dapat diklik dan disalin) serta *Title* layar.
  - Token login otomatis tersimpan ke `youtube.yml` tanpa perlu restart server.
- 🤖 **Pencarian AI & Rekomendasi Suasana (`/musicsearch` atau `/lagucari`):**
  - Perintah `/lagu search <suasana>` atau `/lagucari <suasana>` (contoh: `/lagu search lagu galau akustik indo 2000an`).
  - Memperbaiki pencarian yang typo atau berupa potongan lirik.
  - Kompatibel dengan OpenAI, Groq, OpenRouter, Google Gemini, Ollama, dan Anthropic Claude.
- 📊 **Bossbar & Pengaturan Volume:**
  - Menampilkan judul lagu, artis, dan durasi berjalan di Bossbar atas layar.
  - Terintegrasi dengan menu GUI Simple Voice Chat (tekan `V`), pemain bisa mengatur volume bot sendiri di slider klien.
- 📱 **Ramah Pemain Bedrock:** Mendeteksi pemain Geyser/Floodgate dan memberikan panduan penggunaan jembatan web voice chat.

---

## 📋 Syarat

- **Java 21** atau lebih baru
- **Paper** 1.20.6 / 1.21+ (atau fork modern Paper)
- Mod & plugin [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) versi **2.6.0+**

---

## 📦 Cara Build

```bash
git clone https://github.com/muhfajrigg/GroupMusic.git
cd GroupMusic
mvn clean package
```

File JAR hasil build akan berada di folder `target/GroupMusic-1.0.0.jar`. Pindahkan file tersebut ke folder `plugins/` server Anda.

---

## 📖 Cara Penggunaan & Ringkasan Perintah Cepat (Quick Command Cheatsheet)

GroupMusic dapat dijalankan dengan perintah utama `/lagu` atau `/music` (shortcut `/l`). Berikut adalah ringkasan perintah cepat yang sering digunakan:

| Command | Aliases | Description | Permission |
|---|---|---|---|
| `/lagu play <judul/url>` | `/l p`, `/music play` | Memutar atau menambahkan lagu ke antrean grup voice chat | `groupmusic.use` |
| `/lagu pause` / `/lagu resume` | `/l pause`, `/l resume` | Menjeda atau melanjutkan pemutaran lagu | `groupmusic.use` |
| `/lagu skip` | `/l s`, `/music skip` | Melewati lagu yang sedang diputar | `groupmusic.use` |
| `/lagu queue` | `/l q`, `/music queue` | Melihat daftar antrean lagu grup saat ini | `groupmusic.use` |
| `/lagu volume <0-100>` | `/l vol`, `/music volume` | Mengatur volume audio grup | `groupmusic.use` |
| `/lagu loop [off/track/queue]` | `/l lp`, `/music loop` | Mengatur mode pengulangan lagu | `groupmusic.use` |
| `/lagu search <deskripsi>` | `/l cari`, `/music search` | Pencarian pintar berbasis AI (opsional) | `groupmusic.use` |
| `/lagu stop` | `/l stop`, `/music stop` | Menghentikan pemutaran dan membersihkan antrean | `groupmusic.use` |
| `/lagu help` | `/l h`, `/music help` | Melihat menu panduan interaktif GroupMusic | `groupmusic.use` |
| `/lagureload` | `/musicreload` | Memuat ulang konfigurasi tanpa restart server | `groupmusic.admin` |

---

## 🎮 Semua Perintah & Izin (*Permissions*)

Semua perintah mandiri mendukung alias bahasa Inggris dan bahasa Indonesia:

| Perintah | Alias | Penjelasan | Izin |
|---|---|---|---|
| `/music <judul/link>` | `/lagu`, `/play`, `/gm`, `/l` | Putar atau tambahkan lagu ke antrean | `groupmusic.use` |
| `/musicsearch <deskripsi>` | `/lagucari`, `/search` | Cari lagu lewat deskripsi/suasana AI | `groupmusic.search` |
| `/musicstop` | `/lagustop`, `/mstop` | Hentikan musik dan hapus antrean | `groupmusic.use` |
| `/musicpause` | `/lagupause`, `/pause` | Jeda atau lanjutkan pemutaran | `groupmusic.use` |
| `/musicskip` | `/laguskip`, `/skip` | Lewati lagu yang sedang diputar | `groupmusic.use` |
| `/musicqueue` | `/laguqueue`, `/queue` | Lihat daftar antrean lagu | `groupmusic.use` |
| `/musicnow` | `/lagunow`, `/np` | Tampilkan info lagu yang sedang diputar | `groupmusic.use` |
| `/musicloop [track\|queue\|off]` | `/lagulagi`, `/loop` | Atur atau ganti mode pengulangan | `groupmusic.use` |
| `/musicshuffle` | `/lagushuffle`, `/shuffle` | Acak urutan antrean lagu | `groupmusic.use` |
| `/musicvolume [0-100]` | `/laguvolume`, `/volume` | Lihat atau atur volume grup | `groupmusic.use` |
| `/musicbar` | `/lagubar`, `/mbar` | Nyalakan/matikan tampilan Bossbar | `groupmusic.use` |
| `/musiclogin` | `/lagulogin` | Login akun Google YouTube (Admin) | `groupmusic.admin` |
| `/musicreload` | `/lagureload` | Muat ulang file konfigurasi & bahasa | `groupmusic.admin` |

### Izin (Permissions)
- `groupmusic.use`: Izin menggunakan perintah dasar musik (default: semua pemain).
- `groupmusic.search`: Izin menggunakan pencarian AI (default: semua pemain).
- `groupmusic.ai.bypass-cooldown`: Melewati jeda pemakaian AI (default: OP).
- `groupmusic.admin`: Perintah admin untuk login dan reload (default: OP).

---

## ⚙️ Berkas Konfigurasi

Semua konfigurasi tersimpan di folder `plugins/GroupMusic/`:

| Berkas | Isi Konfigurasi | Muat Ulang via `/musicreload` |
|---|---|---|
| `config.yml` | Batas antrean, durasi, timeout idle, tampilan bossbar, pilihan bahasa (`locale`) | Ya |
| `messages.yml` | Pesan pemain dalam Bahasa Inggris | Ya |
| `messages_id.yml` | Pesan pemain dalam Bahasa Indonesia | Ya |
| `sources.yml` | Domain yang diizinkan, kredensial Spotify API, dan penyedia pencarian | Ya (Kunci Spotify butuh restart) |
| `youtube.yml` | Urutan client YouTube, URL remote cipher, token OAuth | Butuh restart server |
| `ai.yml` | Provider AI, model, API key, prompt, batas pemakaian | Ya |

Untuk mengubah bahasa pesan ke Bahasa Indonesia, ubah pengaturan `locale: id` di `config.yml` lalu jalankan `/musicreload`.

---

### 🔑 Panduan Login YouTube (Setup Sekali Pakai)

Karena YouTube saat ini sangat ketat memblokir bot dan video berhak cipta resmi, beberapa lagu memerlukan otentikasi sekali jalan. GroupMusic sudah menyediakan sistem login OAuth praktis via perintah game:

1. Ketik perintah `/lagulogin` (atau `/musiclogin`) di console server atau in-game (butuh izin `groupmusic.admin`).
2. Klik tautan verifikasi Google yang muncul dan masukkan kode verifikasinya.
3. **Disarankan menggunakan akun Google cadangan / akun tumbal** (demi keamanan akun pribadi kamu).
4. Setelah berhasil, token sesi akan tersimpan otomatis di `youtube.yml`. Proses ini **cukup dilakukan satu kali saja**!

---

## 📄 Lisensi

Proyek ini dilisensikan di bawah [MIT License](LICENSE) - lihat berkas LICENSE untuk keterangan lengkap.  
Hak Cipta (c) 2026 **muhfajrigg** ([@muhfajrigg](https://github.com/muhfajrigg)).
