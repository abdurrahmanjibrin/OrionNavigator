package com.orion.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Repository untuk memuat dan mencari data informasi detail suatu tempat
 * berdasarkan sinyal beacon terdekat.
 */
object LocationDetailRepository {

    private const val TAG = "LocationDetailRepo"
    private const val FILE_NAME = "location_details.json"

    private var cachedDetails: List<LocationDetail>? = null
    private val normalizedIndex = mutableMapOf<String, LocationDetail>()

    /**
     * Memuat data detail lokasi dari assets/location_details.json
     */
    fun loadDetails(context: Context): List<LocationDetail> {
        cachedDetails?.let { return it }

        return try {
            val jsonString = context.assets.open(FILE_NAME).bufferedReader(Charsets.UTF_8).use {
                it.readText()
            }
            val listType = object : TypeToken<List<LocationDetail>>() {}.type
            val details: List<LocationDetail> = Gson().fromJson(jsonString, listType)

            cachedDetails = details
            normalizedIndex.clear()
            for (item in details) {
                normalizedIndex[normalize(item.locationName)] = item
            }
            Log.i(TAG, "Berhasil memuat ${details.size} informasi detail lokasi dari $FILE_NAME")
            details
        } catch (e: Exception) {
            Log.e(TAG, "Gagal memuat $FILE_NAME", e)
            emptyList()
        }
    }

    /**
     * Cari detail lokasi berdasarkan nama beacon atau nama node.
     */
    fun findDetail(context: Context, locationName: String): LocationDetail? {
        if (cachedDetails == null) {
            loadDetails(context)
        }

        val key = normalize(locationName)
        // 1. Pencocokan langsung (exact normalized)
        normalizedIndex[key]?.let { return it }

        // 2. Pencocokan mengandung substring
        for ((k, v) in normalizedIndex) {
            if (k.contains(key) || key.contains(k)) {
                return v
            }
        }

        // 3. Keyword matching khusus jika ada variasi nama
        return when {
            key.contains("difabel") -> normalizedIndex[normalize("Toilet Difabel")]
            key.contains("toilet") && key.contains("cafe") -> normalizedIndex[normalize("Toilet Cafenesa")]
            key.contains("toilet") && (key.contains("perpustakaan") || key.contains("perpus")) -> normalizedIndex[normalize("Toilet Perpustakaan")]
            key.contains("working") && key.contains("cafe") -> normalizedIndex[normalize("Working Space Cafenesa")]
            key.contains("working") && (key.contains("perpustakaan") || key.contains("perpus")) -> normalizedIndex[normalize("Working Space Perpustakaan")]
            key.contains("lift") && (key.contains("perpustakaan") || key.contains("perpus")) -> normalizedIndex[normalize("Lift Perpustakaan")]
            key.contains("lift") -> normalizedIndex[normalize("Lift Lobby")]
            key.contains("satpam") -> normalizedIndex[normalize("Satpam Perpustakaan")]
            key.contains("sayap") -> normalizedIndex[normalize("Pintu Sayap Perpustakaan")]
            key.contains("tengah") -> normalizedIndex[normalize("Pintu Lobby Tengah")]
            key.contains("information") || key.contains("informasi") -> normalizedIndex[normalize("Information Center")]
            key.contains("cafenesa") || key.contains("cafe") -> normalizedIndex[normalize("Cafenesa")]
            key.contains("lobby") -> normalizedIndex[normalize("Lobby")]
            else -> null
        }
    }

    private fun normalize(text: String): String {
        return text.trim().lowercase().replace(Regex("\\s+"), " ")
    }
}

