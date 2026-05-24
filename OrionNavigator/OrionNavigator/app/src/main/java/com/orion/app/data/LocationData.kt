package com.orion.app.data

/**
 * Data class representing live location data
 */
data class LocationData(
    /** Latitude coordinate */
    val lat: Double = 0.0,
    /** Longitude coordinate */
    val lng: Double = 0.0,
    /** Timestamp of last update (Unix millis) */
    val updatedAt: Long = 0L,
    /** Whether live location is currently active */
    val isActive: Boolean = false,
    /** Location accuracy in meters */
    val accuracy: Float = 0f,
    /** Speed in meters/second */
    val speed: Float = 0f,
    /** Bearing in degrees (0-360) */
    val bearing: Float = 0f
) {
    /** No-arg constructor for Firebase deserialization */
    constructor() : this(0.0, 0.0, 0L, false, 0f, 0f, 0f)
}
