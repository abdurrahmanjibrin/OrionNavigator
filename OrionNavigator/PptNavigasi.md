# Draft Copy PPT: Sistem Navigasi Indoor Orion (Versi 1 Slide Padat)

*Saran Desain: Gunakan layout 3 Kolom atau Grid agar semua poin ini bisa masuk dalam 1 slide dengan rapi tanpa terlihat penuh.*

**Titik Utama (Header Slide):** 
**Navigasi Indoor Orion: Presisi, Aman, & Cerdas Tanpa GPS**

---

### Kolom 1: User Experience (Flow Penggunaan)
*Fokus: Kemudahan bagi tunanetra*
- **Start Instan:** Deteksi *real-time* posisi awal lewat beacon tedekat.
- **Anti-Delay:** Indikator bergerak otomatis setiap kali pengguna melangkah.
- **Koreksi Otomatis:** Posisi melenceng akan langsung diluruskan dan dinavigasi ulang ke rute teraman.
- **Tiba Tepat Sasaran:** Deteksi tujuan dengan akurasi tinggi (error tolerance hanya 1 - 2 meter).

### Kolom 2: Teknologi Inti (Behind The Scenes)
*Fokus: Arsitektur Engineering yang Advanced*
- **BLE Beacons & PDR:** Hardware pemancar sinyal (Bluetooth Low Energy) dipadukan dengan sensor internal HP (Akselerometer & Kompas).
- **Sensor Fusion (Kalman Filter):** Mengawinkan data Beacon dan sensor HP untuk akurasi ganda (Tingkat lanjut/Advanced Level).
- **Graph Routing & Trilaterasi:** Kalkulasi matriks matematis canggih untuk membaca persimpangan dan jalur (Node & Edge) terbaik.

### Kolom 3: Value Proposition (Keunggulan / Wow Factor untuk Juri)
*Fokus: Mengapa karya ini juara*
- **Super Smooth & Anti-Drift:** Tidak ada pergerakan peta yang meloncat-loncat/patah-patah (berkat racikan khusus Sensor Fusion).
- **Safety First (Path Clamping):** Algoritma mengunci posisi user SEPENUHNYA di lajur koridor. Menjamin keamanan absolut dari resiko menabrak tembok gedung.
- **Cost-Effective Scalability:** Akurasi kelas dewa (< 2 meter) namun dengan pemasangan infrastruktur yang minimal/sangat hemat biaya. Di YPAB, hanya memakai 8 titik Beacon untuk cover area luas.
