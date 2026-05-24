package com.orion.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * BLE Scanner implementation for detecting iBeacon advertisements
 * Implements BeaconSource interface for abstraction
 */
class BleScanner(private val context: Context) : BeaconSource {

    companion object {
        private const val TAG = "BleScanner"
        
        // iBeacon prefix bytes
        private const val IBEACON_MANUFACTURER_ID = 0x004C // Apple's manufacturer ID
        private const val IBEACON_TYPE = 0x0215
        
        // Scan interval
        private const val SCAN_PERIOD_MS = 1000L
        private const val BEACON_TIMEOUT_MS = 5000L // Consider beacon lost after 5 seconds
        
        // Default Tx Power if not found in advertisement
        private const val DEFAULT_TX_POWER = -59
        
        // Retry settings for scan failures (common on MediaTek chipsets)
        private const val MAX_SCAN_RETRIES = 3
        private const val RETRY_BASE_DELAY_MS = 1000L
    }

    private val bluetoothManager: BluetoothManager? = 
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private var bleScanner: BluetoothLeScanner? = null
    
    private var listener: BeaconSource.OnBeaconDetectedListener? = null
    private var isCurrentlyScanning = false
    private var scanRetryCount = 0
    
    private val handler = Handler(Looper.getMainLooper())
    private val detectedBeacons = mutableMapOf<String, Long>() // beaconId -> lastSeenTime
    
    // Registered beacon UUIDs to filter (from beacons.json)
    private val registeredUuids = mutableSetOf<String>()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            processScanResult(result)
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            results.forEach { processScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error code: $errorCode, retry=$scanRetryCount")
            isCurrentlyScanning = false
            
            // Auto-retry for recoverable errors (common on MediaTek chipsets)
            if (errorCode == SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ||
                errorCode == SCAN_FAILED_INTERNAL_ERROR) {
                if (scanRetryCount < MAX_SCAN_RETRIES) {
                    scanRetryCount++
                    val delay = RETRY_BASE_DELAY_MS * scanRetryCount
                    Log.w(TAG, "Retrying scan in ${delay}ms (attempt $scanRetryCount/$MAX_SCAN_RETRIES)")
                    listener?.onScanError("BLE scan gagal, mencoba ulang... ($scanRetryCount/$MAX_SCAN_RETRIES)")
                    handler.postDelayed({ retryScan() }, delay)
                    return
                }
            }
            
            val errorMessage = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "Scan already started"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "BLE registration gagal. Coba restart Bluetooth di Settings."
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "BLE scan tidak didukung HP ini"
                SCAN_FAILED_INTERNAL_ERROR -> "BLE error. Coba restart Bluetooth."
                else -> "Unknown error: $errorCode"
            }
            listener?.onScanError(errorMessage)
        }
    }
    
    // Check for lost beacons periodically
    private val beaconTimeoutChecker = object : Runnable {
        override fun run() {
            val currentTime = System.currentTimeMillis()
            val lostBeacons = detectedBeacons.filter { 
                currentTime - it.value > BEACON_TIMEOUT_MS 
            }.keys.toList()
            
            lostBeacons.forEach { beaconId ->
                detectedBeacons.remove(beaconId)
                listener?.onBeaconLost(beaconId)
            }
            
            if (isCurrentlyScanning) {
                handler.postDelayed(this, SCAN_PERIOD_MS)
            }
        }
    }

    /**
     * Add UUID to whitelist for filtering
     */
    fun addRegisteredUuid(uuid: String) {
        registeredUuids.add(uuid.lowercase().replace("-", ""))
    }

    /**
     * Set multiple registered UUIDs
     */
    fun setRegisteredUuids(uuids: Collection<String>) {
        registeredUuids.clear()
        registeredUuids.addAll(uuids.map { it.lowercase().replace("-", "") })
    }

    @SuppressLint("MissingPermission")
    override fun startScan() {
        if (isCurrentlyScanning) {
            Log.d(TAG, "Already scanning")
            return
        }
        
        scanRetryCount = 0
        startScanInternal(ScanSettings.SCAN_MODE_LOW_LATENCY)
    }
    
    @SuppressLint("MissingPermission")
    private fun retryScan() {
        Log.d(TAG, "Retrying BLE scan (attempt $scanRetryCount)")
        // Stop any existing scan first
        try {
            bleScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping scan before retry", e)
        }
        // Use LOW_POWER mode on retry (more compatible with some chipsets)
        startScanInternal(ScanSettings.SCAN_MODE_LOW_POWER)
    }
    
    @SuppressLint("MissingPermission")
    private fun startScanInternal(scanMode: Int) {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            listener?.onScanError("Bluetooth tidak aktif. Nyalakan Bluetooth di Settings.")
            return
        }

        bleScanner = bluetoothAdapter.bluetoothLeScanner
        if (bleScanner == null) {
            listener?.onScanError("BLE Scanner tidak tersedia. Coba restart Bluetooth.")
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(scanMode)
            .setReportDelay(0)
            .build()

        try {
            // Flush any pending results first (helps on some chipsets)
            try { bleScanner?.flushPendingScanResults(scanCallback) } catch (_: Exception) {}
            
            bleScanner?.startScan(null, settings, scanCallback)
            isCurrentlyScanning = true
            handler.postDelayed(beaconTimeoutChecker, SCAN_PERIOD_MS)
            Log.d(TAG, "BLE scan started (mode=$scanMode, retry=$scanRetryCount)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start scan", e)
            listener?.onScanError("Gagal memulai scan: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    override fun stopScan() {
        if (!isCurrentlyScanning) return

        try {
            bleScanner?.stopScan(scanCallback)
            isCurrentlyScanning = false
            handler.removeCallbacks(beaconTimeoutChecker)
            detectedBeacons.clear()
            Log.d(TAG, "BLE scan stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop scan", e)
        }
    }

    override fun isScanning(): Boolean = isCurrentlyScanning

    override fun setOnBeaconDetectedListener(listener: BeaconSource.OnBeaconDetectedListener?) {
        this.listener = listener
    }

    @SuppressLint("MissingPermission")
    private fun processScanResult(result: ScanResult) {
        val scanRecord = result.scanRecord ?: return
        val manufacturerData = scanRecord.getManufacturerSpecificData(IBEACON_MANUFACTURER_ID)
        
        if (manufacturerData != null && manufacturerData.size >= 23) {
            // Parse iBeacon data
            val beacon = parseIBeaconData(manufacturerData, result.rssi)
            if (beacon != null) {
                // Check if this beacon is registered
                if (registeredUuids.contains(beacon.uuid.lowercase().replace("-", ""))) {
                    val beaconId = "${beacon.uuid}-${beacon.major}-${beacon.minor}"
                    detectedBeacons[beaconId] = System.currentTimeMillis()
                    
                    listener?.onBeaconDetected(
                        beaconId = beaconId,
                        uuid = beacon.uuid,
                        major = beacon.major,
                        minor = beacon.minor,
                        rssi = beacon.rssi,
                        txPower = beacon.txPower
                    )
                }
            }
        } else {
            // Try to parse as custom BLE beacon (for phone-as-beacon mode)
            parseCustomBeacon(result)
        }
    }

    private fun parseIBeaconData(data: ByteArray, rssi: Int): BeaconData? {
        try {
            // iBeacon format:
            // Byte 0-1: iBeacon type (0x0215)
            // Byte 2-17: UUID (16 bytes)
            // Byte 18-19: Major (2 bytes)
            // Byte 20-21: Minor (2 bytes)
            // Byte 22: TX Power (1 byte)
            
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            
            val type = buffer.short.toInt() and 0xFFFF
            if (type != IBEACON_TYPE) return null
            
            // Parse UUID
            val uuidBytes = ByteArray(16)
            buffer.get(uuidBytes)
            val uuid = bytesToUuid(uuidBytes)
            
            // Parse Major & Minor
            val major = buffer.short.toInt() and 0xFFFF
            val minor = buffer.short.toInt() and 0xFFFF
            
            // Parse TX Power
            val txPower = buffer.get().toInt()
            
            return BeaconData(uuid, major, minor, rssi, txPower)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse iBeacon data", e)
            return null
        }
    }

    @SuppressLint("MissingPermission")
    private fun parseCustomBeacon(result: ScanResult) {
        // Parse custom beacon format for phone-as-beacon mode
        // This handles the format we broadcast from BleAdvertiser
        val scanRecord = result.scanRecord ?: return
        val serviceData = scanRecord.serviceData
        
        serviceData?.forEach { (uuid, data) ->
            if (data != null && data.size >= 4) {
                try {
                    val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
                    val major = buffer.short.toInt() and 0xFFFF
                    val minor = buffer.short.toInt() and 0xFFFF
                    val txPower = if (data.size > 4) data[4].toInt() else DEFAULT_TX_POWER
                    
                    val beaconUuid = uuid.uuid.toString()
                    
                    if (registeredUuids.contains(beaconUuid.lowercase().replace("-", ""))) {
                        val beaconId = "$beaconUuid-$major-$minor"
                        detectedBeacons[beaconId] = System.currentTimeMillis()
                        
                        listener?.onBeaconDetected(
                            beaconId = beaconId,
                            uuid = beaconUuid,
                            major = major,
                            minor = minor,
                            rssi = result.rssi,
                            txPower = txPower
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse custom beacon", e)
                }
            }
        }
    }

    private fun bytesToUuid(bytes: ByteArray): String {
        val buffer = ByteBuffer.wrap(bytes)
        val high = buffer.long
        val low = buffer.long
        return UUID(high, low).toString()
    }

    private data class BeaconData(
        val uuid: String,
        val major: Int,
        val minor: Int,
        val rssi: Int,
        val txPower: Int
    )
}
