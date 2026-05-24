package com.orion.app.data

/**
 * Data class representing a beacon location
 */
data class BeaconInfo(
    val uuid: String,
    val major: Int,
    val minor: Int,
    val location: String,
    val description: String = ""
)

/**
 * Wrapper class for JSON parsing
 */
data class BeaconConfig(
    val beacons: List<BeaconInfoDto>
)

/**
 * DTO for JSON parsing
 */
data class BeaconInfoDto(
    val uuid: String,
    val major: Int,
    val minor: Int,
    val location: String,
    val description: String = ""
) {
    fun toBeaconInfo(): BeaconInfo {
        return BeaconInfo(
            uuid = uuid,
            major = major,
            minor = minor,
            location = location,
            description = description
        )
    }
}
