# MikuRemote

Aplikasi untuk mengontrol dan memantau HP Android lain yang dipakai sebagai
mini server di rumah. **v0.5.0: Auto Start / Always Connected (Phase 4)** —
HP server dibiarkan menyala tanpa pernah dibuka lagi: setelah boot, service
otomatis start (RECEIVE_BOOT_COMPLETED resmi), connect VPS, dan muncul ONLINE
di controller. Plus semua fitur v0.4.0 (front camera + Server Guard),
v0.3.0 (live screen + screenshot), dan v0.2.x.

> **v0.5.0 (Phase 4: unattended):**
> - **BootReceiver**: BOOT_COMPLETED → auto-start ServerService (hanya jika
>   paired + Auto Start ON). Tidak perlu buka APK setelah reboot.
> - **AUTO START SERVER** toggle (default ON) + panel **SERVER RELIABILITY**
>   (checklist pairing/auto-start/battery/camera/VPS + tombol battery settings
>   resmi, tanpa mengubah setting sistem diam-diam).
> - **Network callback**: internet kembali → reconnect langsung (tanpa polling).
> - **Persistensi**: Server Mode & Server Guard kembali sesuai state sebelum
>   reboot; guard config di-sync ulang dari VPS setelah authenticate.
> - **Status lifecycle**: device melaporkan `starting` / `online` /
>   `reconnecting`; controller menampilkan STARTING / ONLINE / RECONNECTING.
> - Single-instance service (BootReceiver + Activity + system restart tidak
>   membuat koneksi WS ganda).

> **v0.4.0 (Phase 3B):**
> - **FRONT CAMERA**: Camera2 → MediaCodec H.264 → WS binary relay → decoder
>   controller. Izin kamera via tombol "ENABLE FRONT CAMERA" di app server
>   (resmi, tanpa bypass); jika belum ada → `CAMERA_PERMISSION_REQUIRED`.
> - **SERVER GUARD**: warning screen custom di HP server (judul, pesan, nama
>   & nomor kontak, teks tombol) yang disimpan di **VPS** dan didorong ke
>   server (sync saat reconnect, tak hilang saat offline). Tombol kontak
>   memakai ACTION_DIAL (buka dialer, bukan auto-call); nomor kosong = tombol
>   disembunyikan.
> - **Konflik stream**: LIVE SCREEN dan FRONT CAMERA saling menolak dengan
>   pesan jelas (hemat RAM/CPU/baterai; hanya satu stream berat aktif).
> - **Watchdog**: VPS offline > ~8 detik saat streaming → stream dihentikan.
> - **Resource warnings**: battery/storage/RAM/suhu dievaluasi di server
>   (threshold dari config guard) → tampil di Device Detail & log.
> - **Rate limit VPS**: SCREENSHOT/GET_DEVICE_INFO maks 12/menit per
>   controller; update config guard maks 10/menit.
> - **Bugfix**: `BlackoutActivity` tidak terdaftar di manifest (SERVER_MODE
>   selalu crash sejak fitur itu dibuat) — kini terdaftar.

> **v0.2.2 (fix crash):** `ServerApp` (pembuat notification channel) tidak
> terdaftar di AndroidManifest, sehingga START SERVER selalu crash
> (`RemoteServiceException: Bad notification for startForeground`) karena
> notifikasi foreground memakai channel yang belum ada. Fix: daftarkan
> `android:name=".ServerApp"` di manifest + startForeground dibungkus try-catch
> defensif + izin notifikasi ditolak pun server tetap jalan.
>
> **v0.3.0 (Phase 3):** Live screen H.264 (MediaProjection di HP server →
> MediaCodec encoder → WS binary relay VPS → MediaCodec decoder di controller)
> dengan pilihan kualitas LOW/MEDIUM/HIGH, overlay FPS/resolusi, dan
> screenshot JPEG yang otomatis tersimpan ke galeri controller
> (Pictures/MikuRemote). Sesi consent capture dipertahankan antar permintaan
> selama service hidup — dialog izin tidak muncul berulang.

## Mode Pribadi (v0.2.1)

Kedua APK memakai satu **kunci pribadi** yang sama (`PRIVATE_KEY` di `.env`
VPS). Saat build, kunci di-inject dari `keystore.properties`
(`privateKey=...`) ke `BuildConfig.PRIVATE_KEY` — jadi di HP tidak ada input
apa pun:

- **APK Server**: dibuka → otomatis daftar ke VPS (`POST /api/private/device`)
  → langsung siap START SERVER.
- **APK Controller**: dibuka → otomatis ambil sesi (`POST /api/private/session`)
  → daftar device langsung tampil. Device baru muncul otomatis begitu APK
  Server di HP lain diaktifkan.

Tanpa kunci yang benar, tidak ada yang bisa terhubung (403). Endpoint lama
(register/login/pairing code) masih ada tapi tidak dipakai UI. Jika APK
dibangun tanpa `privateKey=`, UI menampilkan input kunci sekali (tersimpan).

- **HP A** = Server Device (HP tua yang ditaruh di rumah, jalan 24/7)
- **HP B** = Controller Device (HP utama)
- **VPS** = relay backend (signaling + command + stream relay)

VPS **hanya** me-relay. Screen capture + encoding terjadi di HP Server
(Phase 3). Tidak ada penyimpanan video di VPS.

```
mikuremote/
├── server/                  # VPS backend (Node.js 22+, Express + ws + node:sqlite)
│   ├── src/
│   │   ├── routes/          # REST: auth, devices, pairing-exchange
│   │   ├── websocket/       # Relay WS (control JSON + binary video)
│   │   ├── auth/            # JWT, scrypt password hash, rate limit
│   │   ├── devices/         # device store + pairing
│   │   ├── database/        # SQLite (node:sqlite bawaan Node)
│   │   └── index.js
│   ├── test/smoke.js        # smoke test end-to-end (25 assertions)
│   └── .env.example
├── android-server/          # APK HP Server (Kotlin, Compose, Foreground Service)
├── android-controller/      # APK HP Controller (Kotlin, Compose)
├── MikuRemote-Server-v0.3.0-release.apk     # build rilis (signed, siap pasang)
└── MikuRemote-Controller-v0.3.0-release.apk # build rilis (signed, siap pasang)
```

## Status Phase

| Phase | Isi | Status |
|-------|-----|--------|
| 1 | VPS, WebSocket, pairing, kedua APK connect, online/offline | ✅ Selesai |
| 2 | Device info, heartbeat, torch ON/OFF, server mode | ✅ Selesai |
| 3 | MediaProjection, live screen, screenshot | ✅ Selesai |
| 3B | Front camera, Server Guard, remote config, konflik stream, watchdog | ✅ Selesai |
| 4 | Auto Start (boot receiver), always connected, reliability panel | ✅ Selesai |
| 5 | Hardening lanjutan, rotasi kunci device, audit log VPS | ⏳ Sebagian |

Yang sudah berfungsi end-to-end: register/login, pairing via kode 6 digit,
device online/offline realtime, torch ON/OFF, server mode (blackout),
device info (battery/storage/RAM/uptime), log viewer realtime,
**live screen H.264** (3 tingkat kualitas), dan **screenshot JPEG**
(otomatis tersimpan di galeri HP controller).

---

## 1. Menjalankan VPS

Syarat: Node.js **22+** (pakai `node:sqlite` bawaan, tanpa DB eksternal).

```bash
cd mikuremote/server
cp .env.example .env
# Isi JWT_SECRET:
node -e "console.log(require('crypto').randomBytes(48).toString('base64url'))"
nano .env   # tempel hasilnya ke JWT_SECRET

npm install
npm start   # baca .env via --env-file (di VPS ini: listen 127.0.0.1:8790)
```

Smoke test (server harus jalan dulu):

```bash
JWT_SECRET=test DB_FILE=:memory: PORT=8791 node src/index.js &
PORT=8791 node test/smoke.js
# -> SEMUA TES LULUS
```

### Production (deploy aktif di VPS ini)

- Proses via pm2:
  `pm2 start src/index.js --name mikuremote --node-args="--env-file=.env" --time`
  → listen `127.0.0.1:8790` (PORT/HOST di `.env`).
- Routing nginx (`/etc/nginx/sites-enabled/ashimusic.biz.id`):
  - `location /mikuremote/` → `http://127.0.0.1:8790/` (prefix dibuang:
    `/mikuremote/ws` → `/ws`, `/mikuremote/api/...` → `/api/...`).
  - sisanya → MikuChat di `127.0.0.1:8788` (dipindah dari 8787).
  - Listener: port `300` (gateway NAT provider terminate TLS) dan port `8787`
    (router untuk gateway yang meneruskan langsung ke 8787).
- URL publik yang dipakai APK: `https://ashimusic.biz.id/mikuremote`
  (REST: `.../mikuremote/api/...`, WS: `wss://ashimusic.biz.id/mikuremote/ws`).

> Kedua APK mengubah `http://` → `ws://` dan `https://` → `wss://` otomatis.

## 2. Menjalankan HP Server

Install `MikuRemote-Server-v0.3.0-release.apk` (atau build sendiri, lihat bawah).

1. Buka aplikasi **MikuRemote Server**.
2. Isi **VPS URL** (default `https://ashimusic.biz.id/mikuremote`) + **pairing code**
   6 digit dari Controller.
3. Tekan **PAIR** → **START SERVER**.
4. Beri izin notifikasi (Android 13+) saat diminta.

Service foreground akan tampil sebagai notifikasi permanen
"MikuRemote Server — Server aktif • Connected" dan tetap hidup saat app
ditutup. **Server Mode** menampilkan layar hitam (tap 2x untuk keluar);
koneksi tetap jalan. Di aplikasi ada tombol **Unpair** untuk hapus token.

## 3. Menjalankan HP Controller

Install `MikuRemote-Controller-v0.3.0-release.apk`.

1. **Register** akun (username + password min. 8 karakter).
2. **+ ADD DEVICE** → beri nama (mis. "Server Rumah") → muncul
   **pairing code** (berlaku 15 menit, sekali pakai).
3. Masukkan kode itu di HP Server (langkah 2 di atas).
4. Tap device → **Device Detail**:
   - Info battery, storage, RAM, uptime
   - **LIVE SCREEN** → stream H.264 realtime dengan pilihan kualitas
     LOW/MEDIUM/HIGH + overlay FPS/resolusi (permintaan pertama memunculkan
     dialog izin capture di HP server — terima sekali, sesi dipakai terus)
   - **SENTER** ON/OFF
   - **SERVER MODE** ON/OFF
   - **SCREENSHOT** → JPEG tersimpan otomatis di galeri controller
     (folder Pictures/MikuRemote)
   - **LOG** → log viewer realtime dengan filter INFO/WARN/ERROR,
     pause, dan tombol muat ulang
   - **Revoke device** untuk unpair dari sisi controller

## 4. Build APK dari source

Syarat: JDK 17, Android SDK (platform 34, build-tools 34), Gradle 8.7
(wrapper disertakan).

```bash
cd mikuremote/android-server
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk

cd ../android-controller
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

Signing release membaca `keystore.properties` di root masing-masing modul
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). File ini **tidak
di-push** (lihat `.gitignore`); keystore rilis disimpan di VPS:
`/root/mikuremote-release.keystore` (alias `mikuremote`, validity 25 tahun).
Tanpa `keystore.properties`, build release menghasilkan APK unsigned.

URL VPS default di-set lewat `buildConfigField DEFAULT_VPS_URL` di
`app/build.gradle.kts` (kedua app, saat ini `https://ashimusic.biz.id/mikuremote`),
dan tetap bisa diubah dari UI (server app: "Ubah VPS"; controller: field VPS
URL di layar login).

## 5. Keamanan (yang sudah diimplementasikan)

- Password di-hash **scrypt** (N=16384) — tidak ada plaintext.
- JWT HS256 untuk sesi user; device token acak 32 byte (disimpan hash SHA-256 di DB).
- Pairing code 6 digit sekali pakai, expired 15 menit, maks 10 percobaan.
- Rate limit login/register/pairing per IP.
- VPS memvalidasi kepemilikan device sebelum relay command.
- Command di-whitelist (tidak ada shell/ADB/arbitrary exec).
- Batas ukuran pesan WS (512 KB control / 2 MB binary), budget pesan
  per koneksi, idle timeout 60s, heartbeat 15s.
- Token tidak pernah dicatat di log.

## 6. Roadmap setelah Phase 3

- Kontrol sentuh dua arah (tap/swipe dari controller ke HP server).
- Audio relay dari HP server.
- Hardening lanjutan: rotasi kunci device, audit log di VPS.
