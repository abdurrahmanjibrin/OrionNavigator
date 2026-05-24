package com.orion.app.ble

import kotlin.math.pow

/**
 * Utility class for calculating distance from RSSI signal strength
 * Uses moving average for RSSI smoothing
 */
class DistanceCalculator {

    companion object {
        // Path loss exponent (n) - typically 2.0 for indoor environments
        private const val PATH_LOSS_EXPONENT = 2.0
        
        // Number of samples for moving average
        private const val SMOOTHING_WINDOW_SIZE = 5
        
        // Default TX Power if not provided
        private const val DEFAULT_TX_POWER = -59
    }

    // Store recent RSSI values for smoothing (per beacon)
    private val rssiHistory = mutableMapOf<String, MutableList<Int>>()

    /**
     * Distance categories for navigation
     */
    enum class DistanceCategory {
        VERY_CLOSE,  // < 1 meter
        CLOSE,       // 1-3 meters
        MEDIUM,      // 3-5 meters
        FAR,         // > 5 meters
        UNKNOWN      // No signal
    }

    /**
     * Calculate estimated distance from RSSI
     * Formula: distance = 10 ^ ((TxPower - RSSI) / (10 * n))
     *
     * @param rssi Signal strength in dBm
     * @param txPower Transmission power at 1 meter (default -59)
     * @return Estimated distance in meters
     */
    fun calculateDistance(rssi: Int, txPower: Int = DEFAULT_TX_POWER): Double {
        if (rssi >= 0) return -1.0 // Invalid RSSI
        
        val ratio = (txPower - rssi) / (10.0 * PATH_LOSS_EXPONENT)
        return 10.0.pow(ratio)
    }

    /**
     * Calculate smoothed distance using moving average of RSSI values
     *
     * @param beaconId Unique beacon identifier
     * @param rssi Current RSSI value
     * @param txPower Transmission power
     * @return Smoothed distance estimate
     */
    fun calculateSmoothedDistance(beaconId: String, rssi: Int, txPower: Int = DEFAULT_TX_POWER): Double {
        // Add new RSSI to history
        val history = rssiHistory.getOrPut(beaconId) { mutableListOf() }
        history.add(rssi)
        
        // Keep only last N samples
        while (history.size > SMOOTHING_WINDOW_SIZE) {
            history.removeAt(0)
        }
        
        // Calculate moving average
        val averageRssi = history.average().toInt()
        
        return calculateDistance(averageRssi, txPower)
    }

    /**
     * Get distance category from distance value
     *
     * @param distance Distance in meters
     * @return DistanceCategory
     */
    fun categorizeDistance(distance: Double): DistanceCategory {
        return when {
            distance < 0 -> DistanceCategory.UNKNOWN
            distance < 1.0 -> DistanceCategory.VERY_CLOSE
            distance < 3.0 -> DistanceCategory.CLOSE
            distance < 5.0 -> DistanceCategory.MEDIUM
            else -> DistanceCategory.FAR
        }
    }

    /**
     * Convenience method to get distance category from RSSI directly
     * with smoothing applied
     */
    fun getDistanceCategory(beaconId: String, rssi: Int, txPower: Int = DEFAULT_TX_POWER): DistanceCategory {
        val distance = calculateSmoothedDistance(beaconId, rssi, txPower)
        return categorizeDistance(distance)
    }

    /**
     * Convenience method to get raw distance category without smoothing
     */
    fun getRawDistanceCategory(rssi: Int, txPower: Int = DEFAULT_TX_POWER): DistanceCategory {
        val distance = calculateDistance(rssi, txPower)
        return categorizeDistance(distance)
    }

    /**
     * Clear RSSI history for a specific beacon
     */
    fun clearHistory(beaconId: String) {
        rssiHistory.remove(beaconId)
    }

    /**
     * Clear all RSSI history
     */
    fun clearAllHistory() {
        rssiHistory.clear()
    }

    /**
     * Get current smoothed RSSI for a beacon
     */
    fun getSmoothedRssi(beaconId: String): Int? {
        return rssiHistory[beaconId]?.average()?.toInt()
    }

    /**
     * Get RSSI trend (approaching, stable, moving away)
     * Compares recent average with older average
     */
    fun getRssiTrend(beaconId: String): RssiTrend {
        val history = rssiHistory[beaconId] ?: return RssiTrend.UNKNOWN
        if (history.size < 3) return RssiTrend.UNKNOWN
        
        val recentAvg = history.takeLast(2).average()
        val olderAvg = history.dropLast(2).average()
        
        val diff = recentAvg - olderAvg
        
        return when {
            diff > 3 -> RssiTrend.APPROACHING  // RSSI getting stronger (less negative)
            diff < -3 -> RssiTrend.MOVING_AWAY // RSSI getting weaker (more negative)
            else -> RssiTrend.STABLE
        }
    }

    /**
     * RSSI trend for navigation guidance
     */
    enum class RssiTrend {
        APPROACHING,  // Getting closer to beacon
        STABLE,       // Staying at same distance
        MOVING_AWAY,  // Getting farther from beacon
        UNKNOWN       // Not enough data
    }
}
