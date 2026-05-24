package com.orion.app.ble

/**
 * Abstraction layer for beacon sources
 * This interface allows swapping between phone-based BLE beacon and hardware BLE beacon
 * without changing the navigation logic
 */
interface BeaconSource {
    
    /**
     * Start scanning for beacons
     */
    fun startScan()
    
    /**
     * Stop scanning for beacons
     */
    fun stopScan()
    
    /**
     * Check if currently scanning
     */
    fun isScanning(): Boolean
    
    /**
     * Set listener for beacon detection events
     */
    fun setOnBeaconDetectedListener(listener: OnBeaconDetectedListener?)
    
    /**
     * Listener interface for beacon detection events
     */
    interface OnBeaconDetectedListener {
        /**
         * Called when a beacon is detected
         * @param beaconId Unique identifier for the beacon (uuid-major-minor)
         * @param uuid Beacon UUID
         * @param major Major value
         * @param minor Minor value
         * @param rssi Signal strength in dBm
         * @param txPower Transmission power for distance calculation
         */
        fun onBeaconDetected(
            beaconId: String,
            uuid: String,
            major: Int,
            minor: Int,
            rssi: Int,
            txPower: Int
        )
        
        /**
         * Called when beacon signal is lost
         */
        fun onBeaconLost(beaconId: String)
        
        /**
         * Called when scan fails
         */
        fun onScanError(errorMessage: String)
    }
}
