package com.orion.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * BLE Advertiser for phone-as-beacon mode
 * Broadcasts iBeacon-compatible advertisement with UUID, Major, Minor
 */
class BleAdvertiser(private val context: Context) {

    companion object {
        private const val TAG = "BleAdvertiser"
        
        // Apple's manufacturer ID for iBeacon
        private const val APPLE_MANUFACTURER_ID = 0x004C
        
        // iBeacon prefix
        private const val IBEACON_TYPE: Byte = 0x02
        private const val IBEACON_LENGTH: Byte = 0x15 // 21 bytes
        
        // Default TX Power at 1 meter
        const val DEFAULT_TX_POWER: Byte = -59
    }

    private val bluetoothManager: BluetoothManager? = 
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private var advertiser: BluetoothLeAdvertiser? = null
    
    private var isCurrentlyAdvertising = false
    private var listener: AdvertiseListener? = null

    private var currentUuid: String? = null
    private var currentMajor: Int = 0
    private var currentMinor: Int = 0

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d(TAG, "Advertising started successfully")
            isCurrentlyAdvertising = true
            listener?.onAdvertiseStarted()
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising failed with error code: $errorCode")
            isCurrentlyAdvertising = false
            val errorMessage = when (errorCode) {
                ADVERTISE_FAILED_ALREADY_STARTED -> "Sudah broadcasting"
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "Data terlalu besar"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "BLE Advertising tidak didukung"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "Error internal"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Terlalu banyak advertiser aktif"
                else -> "Error tidak dikenal: $errorCode"
            }
            listener?.onAdvertiseFailed(errorMessage)
        }
    }

    /**
     * Set listener for advertising events
     */
    fun setAdvertiseListener(listener: AdvertiseListener?) {
        this.listener = listener
    }

    /**
     * Check if advertising is supported on this device
     */
    fun isAdvertisingSupported(): Boolean {
        return bluetoothAdapter?.isMultipleAdvertisementSupported == true
    }

    /**
     * Check if currently advertising
     */
    fun isAdvertising(): Boolean = isCurrentlyAdvertising

    /**
     * Start advertising as a beacon using manufacturer data (iBeacon-like format)
     * This is more compact and compatible with BLE advertising size limits
     * 
     * @param uuid Beacon UUID
     * @param major Major value (area identifier)
     * @param minor Minor value (specific location)
     * @param txPower TX Power for distance estimation
     */
    @SuppressLint("MissingPermission")
    fun startAdvertising(uuid: String, major: Int, minor: Int, txPower: Byte = DEFAULT_TX_POWER) {
        if (isCurrentlyAdvertising) {
            Log.d(TAG, "Already advertising")
            return
        }

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            listener?.onAdvertiseFailed("Bluetooth tidak aktif")
            return
        }

        if (!isAdvertisingSupported()) {
            listener?.onAdvertiseFailed("Perangkat tidak mendukung BLE Advertising")
            return
        }

        advertiser = bluetoothAdapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            listener?.onAdvertiseFailed("BLE Advertiser tidak tersedia")
            return
        }

        currentUuid = uuid
        currentMajor = major
        currentMinor = minor

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(0) // Advertise indefinitely
            .build()

        // Create iBeacon-compatible manufacturer data
        val manufacturerData = createIBeaconData(uuid, major, minor, txPower)
        
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(APPLE_MANUFACTURER_ID, manufacturerData)
            .build()

        try {
            advertiser?.startAdvertising(settings, data, advertiseCallback)
            Log.d(TAG, "Starting iBeacon advertising: UUID=$uuid, Major=$major, Minor=$minor")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start advertising", e)
            listener?.onAdvertiseFailed("Gagal memulai broadcast: ${e.message}")
        }
    }

    /**
     * Stop advertising
     */
    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        if (!isCurrentlyAdvertising) return

        try {
            advertiser?.stopAdvertising(advertiseCallback)
            isCurrentlyAdvertising = false
            listener?.onAdvertiseStopped()
            Log.d(TAG, "Advertising stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop advertising", e)
        }
    }

    /**
     * Create iBeacon-compatible manufacturer data
     * Format:
     * - Byte 0: iBeacon type (0x02)
     * - Byte 1: Data length (0x15 = 21)
     * - Bytes 2-17: UUID (16 bytes)
     * - Bytes 18-19: Major (2 bytes, big-endian)
     * - Bytes 20-21: Minor (2 bytes, big-endian)
     * - Byte 22: TX Power (1 byte)
     */
    private fun createIBeaconData(uuid: String, major: Int, minor: Int, txPower: Byte): ByteArray {
        val buffer = ByteBuffer.allocate(23).order(ByteOrder.BIG_ENDIAN)
        
        // iBeacon prefix
        buffer.put(IBEACON_TYPE)
        buffer.put(IBEACON_LENGTH)
        
        // UUID (16 bytes)
        val uuidBytes = uuidToBytes(uuid)
        buffer.put(uuidBytes)
        
        // Major (2 bytes)
        buffer.putShort(major.toShort())
        
        // Minor (2 bytes)
        buffer.putShort(minor.toShort())
        
        // TX Power (1 byte)
        buffer.put(txPower)
        
        return buffer.array()
    }

    /**
     * Convert UUID string to 16-byte array
     */
    private fun uuidToBytes(uuidString: String): ByteArray {
        val uuid = UUID.fromString(uuidString)
        val buffer = ByteBuffer.allocate(16)
        buffer.putLong(uuid.mostSignificantBits)
        buffer.putLong(uuid.leastSignificantBits)
        return buffer.array()
    }

    /**
     * Get current broadcast info
     */
    fun getCurrentBeaconInfo(): Triple<String?, Int, Int> {
        return Triple(currentUuid, currentMajor, currentMinor)
    }

    /**
     * Listener interface for advertising events
     */
    interface AdvertiseListener {
        fun onAdvertiseStarted()
        fun onAdvertiseStopped()
        fun onAdvertiseFailed(errorMessage: String)
    }
}
