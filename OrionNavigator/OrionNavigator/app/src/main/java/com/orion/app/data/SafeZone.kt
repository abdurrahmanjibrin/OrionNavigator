package com.orion.app.data

/**
 * Data class representing a safe zone (geofence)
 * Used by Pendamping to define areas for Tunanetra monitoring
 */
data class SafeZone(
    val id: String = "",
    val name: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val radiusMeters: Float = 500f,
    val alertOnExit: Boolean = true,
    val alertOnEnter: Boolean = false,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
) {
    /**
     * Calculate distance from zone center to a point
     * Returns distance in meters
     */
    fun distanceTo(lat: Double, lng: Double): Float {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(
            latitude, longitude,
            lat, lng,
            results
        )
        return results[0]
    }
    
    /**
     * Check if a point is inside this zone
     */
    fun contains(lat: Double, lng: Double): Boolean {
        return distanceTo(lat, lng) <= radiusMeters
    }
    
    /**
     * Convert to Firebase-compatible map
     */
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "name" to name,
        "latitude" to latitude,
        "longitude" to longitude,
        "radiusMeters" to radiusMeters,
        "alertOnExit" to alertOnExit,
        "alertOnEnter" to alertOnEnter,
        "isActive" to isActive,
        "createdAt" to createdAt
    )
    
    companion object {
        /**
         * Create SafeZone from Firebase snapshot map
         */
        fun fromMap(map: Map<String, Any?>): SafeZone {
            return SafeZone(
                id = map["id"] as? String ?: "",
                name = map["name"] as? String ?: "",
                latitude = (map["latitude"] as? Number)?.toDouble() ?: 0.0,
                longitude = (map["longitude"] as? Number)?.toDouble() ?: 0.0,
                radiusMeters = (map["radiusMeters"] as? Number)?.toFloat() ?: 500f,
                alertOnExit = map["alertOnExit"] as? Boolean ?: true,
                alertOnEnter = map["alertOnEnter"] as? Boolean ?: false,
                isActive = map["isActive"] as? Boolean ?: true,
                createdAt = (map["createdAt"] as? Number)?.toLong() ?: 0L
            )
        }
    }
}
