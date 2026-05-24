Di bawah ini adalah **ringkasan teknis + spesifikasi eksekusi** yang **bisa langsung kamu copy–paste ke AI/engineer (AI lin)** untuk mulai membangun **Orion versi tahap awal (MVP 1)**.

Aku buat **sangat rinci, terstruktur, dan tidak ambigu**, dengan asumsi:

* **Platform:** Android
* **Tahap awal:** Navigation saja
* **Sumber sinyal:** HP sebagai BLE Beacon
* **Arsitektur:** mudah diganti ke BLE Beacon asli nanti (tanpa ubah logika inti)

---

# 📱 RINGKASAN EKSEKUSI APLIKASI

## **ORION – Smart Navigation MVP (Phase 1)**

### 1. Tujuan Aplikasi

Membangun aplikasi Android untuk **navigasi dalam ruangan bagi tunanetra** menggunakan **sinyal Bluetooth Low Energy (BLE)**, dengan panduan **audio (Text-to-Speech)** berbasis jarak estimasi dari sinyal BLE.

Pada tahap awal:

* **HP berfungsi sebagai BLE Beacon**
* **HP lain menjalankan aplikasi Orion sebagai navigator**
* Sistem harus **mudah diganti** ke BLE Beacon fisik di tahap berikutnya

---

## 2. Platform & Teknologi Wajib

* **OS:** Android (minimum Android 8 / API 26)
* **Bahasa:** Kotlin
* **BLE API:** Android Bluetooth LE API
* **Audio:** Android TextToSpeech
* **Accessibility:** TalkBack compatible
* **Permission:** Bluetooth, Location, Foreground Service

---

## 3. Arsitektur Umum (High-Level)

```
[HP A - BLE Beacon]
   |
   | (BLE Advertising: UUID, Major, Minor, Tx Power)
   |
[HP B - Orion App]
   |
   | Scan BLE → RSSI → Distance Estimation
   |
   | Logic Navigation
   |
[Audio Instruction via TTS]
```

> Catatan penting:
> **HP A bisa diganti BLE Beacon fisik tanpa mengubah logic di HP B**

---

## 4. Fitur yang HARUS ADA (MVP Phase 1)

### 4.1 BLE Beacon Scanner

Aplikasi harus:

* Scan BLE signal secara kontinu
* Membaca:

  * UUID
  * Major
  * Minor
  * RSSI
* Filter hanya beacon yang terdaftar (whitelist)

**Catatan:**
Beacon awal berasal dari **HP lain**, bukan hardware khusus.

---

### 4.2 Beacon Abstraction Layer (PENTING)

Buat layer abstraksi agar sumber beacon bisa:

* HP (BLE Advertiser)
* BLE Beacon fisik (di masa depan)

Contoh struktur:

```kotlin
interface BeaconSource {
    fun startScan()
    fun stopScan()
    fun onBeaconDetected(beaconId, rssi)
}
```

> Ini **wajib** supaya sistem scalable.

---

### 4.3 Distance Estimation (Estimasi Jarak)

Gunakan rumus sederhana RSSI:

```
distance = 10 ^ ((TxPower - RSSI) / (10 * n))
```

Parameter:

* TxPower → dari beacon
* n → path loss exponent (default 2.0 indoor)

Jarak tidak perlu presisi tinggi, hanya kategori:

* Sangat dekat (<1 m)
* Dekat (1–3 m)
* Sedang (3–5 m)
* Jauh (>5 m)

---

### 4.4 Navigation Logic (Sederhana, Wajib)

Konsep:

* Setiap beacon = 1 titik lokasi (misal: “Ruang Guru”)
* User memilih **tujuan**
* Sistem memandu berdasarkan beacon terdekat

Contoh logic:

* Jika RSSI beacon tujuan meningkat → “Maju”
* Jika stagnan → “Berjalan perlahan”
* Jika menjauh → “Berhenti / koreksi arah”

---

### 4.5 Audio Guidance (WAJIB)

Gunakan **Text-to-Speech**, bukan suara statis.

Contoh output:

* “Beacon terdeteksi”
* “Maju tiga langkah”
* “Anda semakin dekat”
* “Tujuan di depan Anda”

Karakteristik:

* Bahasa Indonesia
* Volume jelas
* Tidak terlalu sering (debounce 2–3 detik)

---

### 4.6 UI / UX untuk Tunanetra

UI **minimal**, tidak visual-heavy.

Wajib:

* Tombol besar
* Gesture sederhana
* Bisa dipakai **tanpa melihat layar**
* Kompatibel TalkBack

Struktur layar minimal:

1. Splash
2. Pilih Tujuan (list sederhana)
3. Mode Navigasi (audio aktif)
4. Tombol Stop

---

## 5. Fitur BLE Advertiser (HP sebagai Beacon)

Di HP A:

* Aktifkan BLE Advertising
* Mode:

  * iBeacon atau custom BLE packet
* Broadcast:

  * UUID (hardcoded)
  * Major (area)
  * Minor (ruangan)

Contoh:

* Major = 1 → Gedung A
* Minor = 101 → Ruang Guru

Aplikasi beacon **boleh app terpisah** atau app utilitas.

---

## 6. Data & Konfigurasi (Sederhana)

Beacon mapping disimpan lokal (JSON / Room DB):

```json
{
  "beacons": [
    {
      "uuid": "xxxx",
      "major": 1,
      "minor": 101,
      "location": "Ruang Guru"
    }
  ]
}
```

---

## 7. Hal yang TIDAK perlu di tahap ini

❌ Cloud
❌ Antares
❌ GPS
❌ Computer Vision
❌ Backend server
❌ Dashboard web

---

## 8. Non-Functional Requirements

* Latency audio < 1 detik
* Scan BLE interval ≤ 1 detik
* Tidak crash saat screen off
* Jalan di background (foreground service)

---

## 9. Output yang Diharapkan dari AI / Developer

* APK Android
* Source code Kotlin
* 2 mode:

  1. Beacon mode (HP A)
  2. Navigator mode (HP B)
* Demo working:

  * 1 HP jadi beacon
  * 1 HP navigasi ke beacon

---

## 10. Catatan Penting untuk Masa Depan

Pastikan:

* Beacon source modular
* Logic navigation tidak bergantung ke “HP sebagai beacon”
* RSSI smoothing (moving average) diterapkan

---
