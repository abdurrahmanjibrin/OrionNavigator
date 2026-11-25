package com.orion.app.data

/**
 * Model data informasi detail suatu tempat berdasarkan identifikasi beacon / denah.
 */
data class LocationDetail(
    val locationName: String,
    val shortDescription: String,
    val detailedDescription: String,
    val facilities: List<String>,
    val ttsText: String
)

