Siap. Berikut adalah **SUMMARY TERBARU & FINAL** untuk fitur **Live Location Orion** **JIKA MENGGUNAKAN OPENSTREETMAP (OSM)**.
Summary ini **siap langsung kamu kirim ke AI Antigravity**, **tanpa Google Maps, tanpa API key, tanpa billing**, dan **100% konsisten dengan kondisi MVP Orion sekarang**.

---

# 🔵 ORION — LIVE LOCATION FEATURE (OSM VERSION)

## Tujuan Fitur

Menambahkan fitur **Live Location** pada aplikasi Orion agar **akun orang tua / caregiver** dapat memantau **lokasi pengguna tunanetra secara real-time** secara **aman, privat, dan berbasis persetujuan**, mirip WhatsApp Live Location, namun khusus untuk kebutuhan keamanan penyandang tunanetra.

Fitur ini berfokus pada **ketenangan dan keselamatan**, bukan pengawasan.

---

## Platform Peta

* **Menggunakan OpenStreetMap (OSM)**
* Implementasi Android: **OSMDroid**
* **Tidak menggunakan Google Maps**
* **Tidak memerlukan API key atau billing**

OSM hanya digunakan untuk:

* menampilkan peta dasar
* menampilkan marker lokasi pengguna
* memperbarui marker secara real-time

---

## Konsep Dasar

* Aplikasi memiliki **dua peran pengguna**:

  1. **User Tunanetra (Child)**
  2. **User Orang Tua / Caregiver (Parent)**
* Hubungan **one-to-one** (1 anak ↔ 1 orang tua).
* Lokasi bersifat **privat** dan **tidak dapat dibagikan ke pihak lain**.
* Live location dapat **diaktifkan / dinonaktifkan oleh user tunanetra**.

---

## Alur Sistem

### Setup Akun

1. User tunanetra login ke aplikasi Orion.
2. User menautkan akun orang tua (email / ID).
3. Orang tua menyetujui koneksi.
4. Setelah terhubung, orang tua dapat mengakses Live Location.

---

### Live Location Aktif

1. Aplikasi Orion (HP user tunanetra) mengambil lokasi GPS.
2. Lokasi dikirim ke backend secara periodik:

   * setiap 30–60 detik, atau
   * saat berpindah >20–30 meter.
3. Backend hanya menyimpan **lokasi terbaru**.
4. Aplikasi orang tua membaca data lokasi secara **real-time**.
5. Marker lokasi pada peta **OSM diperbarui otomatis**.
6. Status waktu update ditampilkan (misal: *“terakhir diperbarui 1 menit lalu”*).

---

## Hak Akses & Privasi

* Hanya akun orang tua yang terhubung yang dapat melihat lokasi.
* Tidak ada histori lokasi panjang.
* Tidak ada share link lokasi.
* Tidak ada pelacakan diam-diam.
* Live location dapat dimatikan kapan saja oleh user tunanetra.

---

## UI / UX Guidelines

### Aplikasi User Tunanetra

* **Audio-first (Text-to-Speech)**.
* Tombol besar dan sederhana.
* Feedback suara:

  * “Live location aktif”
  * “Live location nonaktif”
* Tidak membutuhkan interaksi visual.

---

### Aplikasi User Orang Tua

* Tampilan sederhana:

  * peta OpenStreetMap
  * satu marker lokasi anak
  * status koneksi & waktu update
* Mode **read-only** (tanpa kontrol).

---

## Integrasi dengan Fitur yang Sudah Ada

* Fitur Live Location **independen dari Bluetooth Finder**.
* Bluetooth Finder tetap digunakan untuk **navigasi indoor**.
* Live Location digunakan untuk **pemantauan jarak jauh & keamanan**.
* Tidak mengubah logika Bluetooth Finder yang sudah ada.

---

## Kebutuhan Teknis

### Frontend

* Android (Kotlin).
* OSMDroid untuk peta OSM.
* Location permission (foreground).
* Background location update yang hemat baterai.

### Backend

* Realtime database (contoh: Firebase Realtime Database).
* Authentication per user.
* Backend bersifat **map-agnostic** (mudah diganti ke Google Maps atau Antares di masa depan).

---

## Struktur Data (contoh)

```json
{
  "locations": {
    "child_001": {
      "lat": -7.28708,
      "lng": 112.78463,
      "updated_at": 1735684830000
    }
  }
}
```

---

## Definisi Keberhasilan

* Lokasi user tunanetra tampil realtime di aplikasi orang tua.
* Marker peta bergerak mengikuti posisi pengguna.
* Update stabil dan hemat baterai.
* User tunanetra memiliki kontrol penuh atas fitur.
* Tidak ada pelanggaran privasi.

---

## Catatan Implementasi

* OSM digunakan hanya sebagai **visualisasi peta**, bukan sumber lokasi.
* Arsitektur dibuat **fleksibel**, sehingga peta dapat diganti di tahap lanjutan.
* Fokus utama MVP adalah **alur live location dan keamanan pengguna**.

---

## Ringkasan Singkat (1 Kalimat)

> Tambahkan fitur live location privat di Orion menggunakan OpenStreetMap agar orang tua dapat memantau lokasi pengguna tunanetra secara real-time, aman, dan berbasis persetujuan, tanpa API key atau biaya tambahan.

---

Jika kamu mau, aku juga bisa:

* memadatkan ini jadi **prompt 5–6 baris**
* membuat **diagram alur sistem (sequence diagram)**
* bantu **cek implementasi Antigravity**
* atau menyiapkan **script demo fitur ini**

Tinggal bilang mau lanjut ke bagian mana 👍
