package com.orion.app.navigation

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import com.orion.app.ble.BeaconSource
import com.orion.app.ble.BleScanner
import com.orion.app.ble.DistanceCalculator
import com.orion.app.data.BeaconInfo
import com.orion.app.data.BeaconRepository
import java.util.Locale

/**
 * Navigation Manager - Core navigation logic
 * Handles BLE scanning, distance calculation, and TTS guidance
 * 
 * IMPORTANT: This single-beacon system can only measure DISTANCE (closer/farther).
 * It CANNOT provide directional guidance (left/right/straight) because:
 * - BLE signals don't carry directional information
 * - For turn-by-turn navigation, multiple beacons at waypoints would be needed
 */
class NavigationManager(private val context: Context) : BeaconSource.OnBeaconDetectedListener {

    companion object {
        private const val TAG = "NavigationManager"
        
        // Debounce interval for TTS announcements (milliseconds)
        private const val TTS_DEBOUNCE_MS = 3000L
        
        // Arrival announcement debounce (only announce once)
        private const val ARRIVAL_DEBOUNCE_MS = 10000L
        
        // Minimum RSSI change to trigger an update
        private const val RSSI_CHANGE_THRESHOLD = 5
    }

    private val bleScanner = BleScanner(context)
    private val distanceCalculator = DistanceCalculator()
    private val beaconRepository = BeaconRepository(context)
    
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    
    private var listener: NavigationListener? = null
    
    // Current navigation state
    private var targetBeacon: BeaconInfo? = null
    private var isNavigating = false
    private var lastTtsTime = 0L
    private var lastArrivalTime = 0L // Track when we last announced arrival
    private var lastCategory: DistanceCalculator.DistanceCategory? = null
    private var lastRssi: Int = 0
    private var hasAnnouncedArrival = false // Prevent repeated arrival announcements

    /**
     * Initialize the navigation manager
     */
    fun initialize() {
        // Setup BLE scanner
        val registeredUuids = beaconRepository.getBeacons().map { it.uuid }.distinct()
        bleScanner.setRegisteredUuids(registeredUuids)
        bleScanner.setOnBeaconDetectedListener(this)
        
        // Initialize TTS
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale("id", "ID"))
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.getDefault())
                }
                isTtsReady = true
                Log.d(TAG, "TTS initialized successfully")
            } else {
                Log.e(TAG, "TTS initialization failed")
            }
        }
    }

    /**
     * Set listener for navigation updates
     */
    fun setNavigationListener(listener: NavigationListener?) {
        this.listener = listener
    }

    /**
     * Start navigation to a specific beacon
     */
    fun startNavigation(beacon: BeaconInfo) {
        targetBeacon = beacon
        isNavigating = true
        lastCategory = null
        lastRssi = 0
        hasAnnouncedArrival = false
        lastArrivalTime = 0L
        distanceCalculator.clearAllHistory()
        
        bleScanner.startScan()
        
        speak("Navigasi dimulai. Menuju ke ${beacon.location}. Ikuti panduan suara berdasarkan jarak.")
        listener?.onNavigationStarted(beacon)
        listener?.onDistanceChanged(DistanceCalculator.DistanceCategory.UNKNOWN, 0.0, "Mencari sinyal beacon...")
        
        Log.d(TAG, "Navigation started to: ${beacon.location}")
    }

    /**
     * Stop navigation
     */
    fun stopNavigation() {
        isNavigating = false
        bleScanner.stopScan()
        distanceCalculator.clearAllHistory()
        
        speak("Navigasi dihentikan")
        listener?.onNavigationStopped()
        
        Log.d(TAG, "Navigation stopped")
    }

    /**
     * Check if currently navigating
     */
    fun isNavigating(): Boolean = isNavigating

    /**
     * Cleanup resources
     */
    fun destroy() {
        stopNavigation()
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    // BeaconSource.OnBeaconDetectedListener implementation
    
    override fun onBeaconDetected(
        beaconId: String,
        uuid: String,
        major: Int,
        minor: Int,
        rssi: Int,
        txPower: Int
    ) {
        if (!isNavigating) return
        
        val target = targetBeacon ?: return
        
        // Check if this is our target beacon
        if (uuid.equals(target.uuid, ignoreCase = true) &&
            major == target.major &&
            minor == target.minor
        ) {
            processTargetBeacon(beaconId, rssi, txPower)
        }
    }

    override fun onBeaconLost(beaconId: String) {
        if (!isNavigating) return
        
        val target = targetBeacon ?: return
        val targetId = "${target.uuid}-${target.major}-${target.minor}"
        
        if (beaconId.equals(targetId, ignoreCase = true)) {
            // Target beacon lost
            hasAnnouncedArrival = false // Reset arrival state
            listener?.onDistanceChanged(
                DistanceCalculator.DistanceCategory.UNKNOWN,
                -1.0,
                "Sinyal hilang. Berjalan perlahan..."
            )
            speakWithDebounce("Sinyal beacon hilang. Coba bergerak perlahan.")
        }
    }

    override fun onScanError(errorMessage: String) {
        Log.e(TAG, "Scan error: $errorMessage")
        listener?.onNavigationError(errorMessage)
    }

    /**
     * Process target beacon detection
     */
    private fun processTargetBeacon(beaconId: String, rssi: Int, txPower: Int) {
        val category = distanceCalculator.getDistanceCategory(beaconId, rssi, txPower)
        val distance = distanceCalculator.calculateSmoothedDistance(beaconId, rssi, txPower)
        val trend = distanceCalculator.getRssiTrend(beaconId)
        
        // Get appropriate guidance message
        val guidance = getGuidanceMessage(category, trend)
        
        // Update listener
        listener?.onDistanceChanged(category, distance, guidance)
        
        // Handle arrival announcement specially
        if (category == DistanceCalculator.DistanceCategory.VERY_CLOSE) {
            announceArrival()
        } else {
            // Reset arrival state if we move away from very close
            if (hasAnnouncedArrival && lastCategory == DistanceCalculator.DistanceCategory.VERY_CLOSE) {
                hasAnnouncedArrival = false
            }
            
            // Check if we should speak regular guidance
            val categoryChanged = lastCategory != category
            val significantRssiChange = kotlin.math.abs(rssi - lastRssi) > RSSI_CHANGE_THRESHOLD
            
            if (categoryChanged || (significantRssiChange && shouldSpeak())) {
                speakGuidance(category, trend)
            }
        }
        
        lastCategory = category
        lastRssi = rssi
    }
    
    /**
     * Announce arrival at destination
     */
    private fun announceArrival() {
        val target = targetBeacon ?: return
        val currentTime = System.currentTimeMillis()
        
        // Only announce if we haven't recently (prevent spam)
        if (!hasAnnouncedArrival || currentTime - lastArrivalTime > ARRIVAL_DEBOUNCE_MS) {
            hasAnnouncedArrival = true
            lastArrivalTime = currentTime
            lastTtsTime = currentTime
            
            val arrivalMessage = "Anda sudah sampai di ${target.location}. Tujuan ada di sekitar Anda."
            tts?.speak(arrivalMessage, TextToSpeech.QUEUE_FLUSH, null, currentTime.toString())
            
            listener?.onArrived(target)
            Log.d(TAG, "Arrival announced: ${target.location}")
        }
    }

    /**
     * Get guidance message based on distance and trend
     * NOTE: Since we only have 1 beacon, we can only guide based on distance (closer/farther)
     * We CANNOT provide directional guidance (left/right/straight) with single-beacon setup
     */
    private fun getGuidanceMessage(
        category: DistanceCalculator.DistanceCategory,
        trend: DistanceCalculator.RssiTrend
    ): String {
        val target = targetBeacon
        return when (category) {
            DistanceCalculator.DistanceCategory.VERY_CLOSE -> {
                "Anda sudah sampai di ${target?.location ?: "tujuan"}"
            }
            DistanceCalculator.DistanceCategory.CLOSE -> {
                when (trend) {
                    DistanceCalculator.RssiTrend.APPROACHING -> "Semakin dekat. Terus bergerak"
                    DistanceCalculator.RssiTrend.MOVING_AWAY -> "Menjauh! Coba arah lain"
                    else -> "Sudah dekat. Bergerak perlahan"
                }
            }
            DistanceCalculator.DistanceCategory.MEDIUM -> {
                when (trend) {
                    DistanceCalculator.RssiTrend.APPROACHING -> "Arah benar, terus bergerak"
                    DistanceCalculator.RssiTrend.MOVING_AWAY -> "Menjauh! Coba putar balik"
                    else -> "Bergerak perlahan, pantau sinyal"
                }
            }
            DistanceCalculator.DistanceCategory.FAR -> {
                when (trend) {
                    DistanceCalculator.RssiTrend.APPROACHING -> "Sinyal menguat, terus bergerak"
                    DistanceCalculator.RssiTrend.MOVING_AWAY -> "Sinyal melemah! Putar arah"
                    else -> "Beacon terdeteksi. Mulai bergerak ke segala arah, pantau sinyal"
                }
            }
            DistanceCalculator.DistanceCategory.UNKNOWN -> "Mencari sinyal beacon..."
        }
    }

    /**
     * Speak guidance with debounce
     */
    private fun speakGuidance(
        category: DistanceCalculator.DistanceCategory,
        trend: DistanceCalculator.RssiTrend
    ) {
        val target = targetBeacon
        val message = when (category) {
            DistanceCalculator.DistanceCategory.VERY_CLOSE -> {
                // Handled by announceArrival()
                return
            }
            DistanceCalculator.DistanceCategory.CLOSE -> {
                when (trend) {
                    DistanceCalculator.RssiTrend.APPROACHING -> "Semakin dekat"
                    DistanceCalculator.RssiTrend.MOVING_AWAY -> "Anda menjauh. Coba arah lain"
                    else -> "Sudah dekat dengan ${target?.location ?: "tujuan"}"
                }
            }
            DistanceCalculator.DistanceCategory.MEDIUM -> {
                when (trend) {
                    DistanceCalculator.RssiTrend.APPROACHING -> "Arah benar, terus bergerak"
                    DistanceCalculator.RssiTrend.MOVING_AWAY -> "Anda menjauh! Coba putar balik"
                    else -> "Bergerak perlahan"
                }
            }
            DistanceCalculator.DistanceCategory.FAR -> {
                when (trend) {
                    DistanceCalculator.RssiTrend.APPROACHING -> "Sinyal menguat, terus bergerak"
                    DistanceCalculator.RssiTrend.MOVING_AWAY -> "Sinyal melemah. Putar arah"
                    else -> "Beacon terdeteksi. Coba bergerak ke berbagai arah"
                }
            }
            DistanceCalculator.DistanceCategory.UNKNOWN -> return // Don't speak for unknown
        }
        
        speakWithDebounce(message)
    }

    private fun shouldSpeak(): Boolean {
        return System.currentTimeMillis() - lastTtsTime > TTS_DEBOUNCE_MS
    }

    private fun speakWithDebounce(message: String) {
        if (!shouldSpeak()) return
        speak(message)
    }

    private fun speak(message: String) {
        if (!isTtsReady) return
        lastTtsTime = System.currentTimeMillis()
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, System.currentTimeMillis().toString())
    }

    /**
     * Listener interface for navigation events
     */
    interface NavigationListener {
        fun onNavigationStarted(target: BeaconInfo)
        fun onNavigationStopped()
        fun onDistanceChanged(category: DistanceCalculator.DistanceCategory, distance: Double, guidance: String)
        fun onNavigationError(errorMessage: String)
        fun onArrived(target: BeaconInfo) {} // Default empty implementation
    }
}
