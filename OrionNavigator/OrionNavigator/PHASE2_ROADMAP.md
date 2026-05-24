# ORION Navigator - Phase 2 Roadmap
## Compass + User Input Position Navigation

---

## Konsep

Menggabungkan **Compass Sensor** + **User Input Posisi Awal** + **1 Beacon** untuk navigasi turn-by-turn indoor.

---

## Arsitektur

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│ User Input  │────▶│   Denah     │────▶│ Pathfinding │
│ Titik Awal  │     │   (JSON)    │     │  Algorithm  │
└─────────────┘     └─────────────┘     └──────┬──────┘
                                               │
                                               ▼
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Compass   │────▶│  Navigation │◀────│    Route    │
│   Sensor    │     │   Manager   │     │   Steps     │
└─────────────┘     └──────┬──────┘     └─────────────┘
                           │
┌─────────────┐            │            ┌─────────────┐
│    Step     │────────────┤            │   BLE       │
│   Counter   │            │            │   Beacon    │
└─────────────┘            ▼            └──────┬──────┘
                    ┌─────────────┐            │
                    │     TTS     │◀───────────┘
                    │   Guidance  │
                    └─────────────┘
```

---

## Alur Navigasi

1. User pilih: **Titik Awal** dan **Tujuan**
2. Sistem hitung rute dari denah JSON
3. Compass deteksi arah hadap user
4. TTS beri instruksi: "Jalan lurus 10 langkah"
5. Step counter hitung langkah
6. TTS beri instruksi berikutnya: "Belok kanan"
7. Beacon RSSI konfirmasi arrival

---

## Komponen yang Dibutuhkan

### 1. Denah/Map JSON
```json
{
  "nodes": [
    {
      "id": "ruang_tamu",
      "name": "Ruang Tamu",
      "connections": [
        { "to": "lorong", "steps": 10, "direction": "lurus" }
      ]
    },
    {
      "id": "lorong",
      "name": "Lorong",
      "connections": [
        { "to": "ruang_tamu", "steps": 10, "direction": "putar balik" },
        { "to": "kamar", "steps": 5, "direction": "belok kanan" }
      ]
    },
    {
      "id": "kamar",
      "name": "Kamar",
      "beaconMinor": 103,
      "connections": [
        { "to": "lorong", "steps": 5, "direction": "belok kiri" }
      ]
    }
  ]
}
```

### 2. Sensor HP
- **Compass/Magnetometer** - arah hadap
- **Accelerometer** - step counter

### 3. Beacon (1 unit minimum)
- Di titik tujuan untuk konfirmasi arrival

---

## File yang Perlu Dibuat

| File | Deskripsi |
|------|-----------|
| `IndoorMap.kt` | Data class untuk denah |
| `MapRepository.kt` | Load denah dari JSON |
| `PathFinder.kt` | Algoritma cari rute |
| `CompassManager.kt` | Baca sensor compass |
| `StepCounter.kt` | Hitung langkah |
| `TurnByTurnNavigator.kt` | Koordinasi navigasi |
| `indoor_map.json` | Data denah lokasi |

---

## Persiapan Sebelum Implementasi

User perlu menyediakan:
1. ✏️ Sketsa/denah lokasi
2. 📝 Nama-nama ruangan
3. 📏 Perkiraan jarak antar ruangan (langkah)
4. 📍 Lokasi beacon (tujuan mana saja)

---

## Status: PLANNED
Menunggu informasi denah dari user untuk memulai implementasi.
