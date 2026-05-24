package com.orion.app.navigation

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.orion.app.sensor.CompassManager
import com.orion.app.tts.TTSManager
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * IndoorNavigationManager - Manages multi-step indoor navigation with compass guidance.
 * Provides step-by-step directions with alignment detection at each step.
 * 
 * Updated to use graph-based routing with Dijkstra algorithm (merged from YPAB project)
 */
class IndoorNavigationManager(
    private val context: Context,
    private val compassManager: CompassManager,
    private val ttsManager: TTSManager
) : CompassManager.Callback {

    // Beacon stabilization
    private val smoothedRssi = mutableMapOf<Int, Double>()  // nodeId -> smoothed RSSI
    private var candidateRoom: Int? = null        // Potential next room
    private var candidateStartTime: Long = 0         // When candidate first became strongest
    
    companion object {
        private const val TAG = "IndoorNavigationManager"
        
        // Tolerance for heading alignment (degrees)
        private const val DEFAULT_HEADING_TOLERANCE = 15f
        
        // Tighter tolerance for specific paths without physical guide rails
        private const val TIGHT_HEADING_TOLERANCE = 5f
        
        // Minimum heading change before re-announcing guidance
        private const val HEADING_CHANGE_THRESHOLD = 20f
        
        // Delay before starting first step after destination set
        private const val STEP_START_DELAY_MS = 2000L
        
        // Beacon stabilization constants
        private const val RSSI_SMOOTHING_ALPHA = 0.3      // EMA factor (lower = smoother)
        private const val MIN_DWELL_TIME_MS = 2000L        // 2 seconds before switching
        private const val RSSI_HYSTERESIS_DB = 6.0         // Must be 6 dBm stronger to switch
        private const val MIN_RSSI_THRESHOLD = -90         // Ignore very weak signals
    }

    private val handler = Handler(Looper.getMainLooper())
    
    private var callback: Callback? = null
    
    // Current state
    private var currentRoom: Int? = null          // Node ID
    private var currentRoomDisplayName: String? = null
    private var destinationRoom: Int? = null      // Node ID
    private var destinationDisplayName: String? = null
    
    // Navigation path (list of node IDs from Dijkstra)
    private var currentPath: List<Int>? = null
    private var currentPathIndex = 0  // Index of current position in path
    
    // Current step state
    private var currentStepInstruction: ManualNavGuide.NavigationInstruction? = null
    private var isAligning = false
    private var waitingForUserConfirmation = false
    private var lastSpokenHeading = -1f
    private var isNavigating = false
    private var isActivelyNavigating = false  // true when step-by-step navigation path is active

    // TTS throttling untuk arah salah (5 detik)
    private var lastGuidanceTtsTime = 0L
    private val GUIDANCE_TTS_INTERVAL_MS = 5000L

    // Guard agar 'Arah Sudah Benar' hanya diucapkan sekali per alignment event
    private var lastAlignedState = false

    // Step counting mode
    var useStepCounting = false
    private var currentUserStepCount = 0   // How many steps user has taken on current edge
    private var targetEdgeStepCount = 0    // Total steps needed for current edge

    /**
     * Set the callback for navigation events
     */
    fun setCallback(callback: Callback?) {
        this.callback = callback
    }

    /**
     * Start passive BLE detection only (no compass, no active navigation).
     * This allows detecting the user's current room via BLE beacons
     * without starting step-by-step navigation.
     */
    fun startPassiveDetection() {
        isNavigating = true
        Log.d(TAG, "Passive BLE detection started")
    }

    /**
     * Start compass for active indoor navigation.
     * Call this when user presses "Mulai Navigasi" to begin step-by-step guidance.
     */
    fun start() {
        compassManager.start()
        isNavigating = true
        // Always lock beacon detection when navigation starts.
        // This prevents intermediate beacons from resetting the route mid-navigation.
        // Unlock happens in resetNavigation() when navigation ends or is stopped.
        isActivelyNavigating = true
    }

    /**
     * Stop only the active navigation (route, destination, step-by-step),
     * but keep passive BLE detection alive so user still sees their current room.
     */
    fun stopNavigation() {
        resetNavigation()
        // Keep isNavigating = true so passive detection continues
        Log.d(TAG, "Active navigation stopped, passive detection continues")
    }

    /**
     * Stop everything — compass, navigation, and passive detection.
     * Call this in onPause()/onDestroy().
     */
    fun stop() {
        compassManager.stop()
        resetNavigation()
        isNavigating = false
        isActivelyNavigating = false
        smoothedRssi.clear()
        candidateRoom = null
    }

    /**
     * Check if navigation is active
     */
    fun isNavigating(): Boolean = isNavigating

    /**
     * Called when a beacon is detected
     * Uses BeaconRoomMapper to convert beacon to graph node
     * Applies RSSI smoothing and stabilization to prevent rapid room switching
     */
    fun onBeaconDetected(major: Int, minor: Int, rssi: Int) {
        val nodeId = BeaconRoomMapper.getNodeIdForBeacon(major, minor)
        
        if (nodeId == null) {
            Log.w(TAG, "Unknown beacon: Major=$major, Minor=$minor")
            return
        }
        
        // Ignore very weak signals
        if (rssi < MIN_RSSI_THRESHOLD) return
        
        // During active step-by-step navigation, ignore all intermediate beacons
        // Only respond to the destination beacon (for UI display purposes)
        if (isActivelyNavigating && nodeId != destinationRoom) {
            Log.d(TAG, "Ignoring intermediate beacon: nodeId=$nodeId (navigating to $destinationRoom)")
            // Still update smoothed RSSI for UI signal display
            val previousSmoothed = smoothedRssi[nodeId]
            val newSmoothed = if (previousSmoothed != null) {
                RSSI_SMOOTHING_ALPHA * rssi + (1 - RSSI_SMOOTHING_ALPHA) * previousSmoothed
            } else {
                rssi.toDouble()
            }
            smoothedRssi[nodeId] = newSmoothed
            return
        }
        
        // Apply exponential moving average smoothing
        val previousSmoothed = smoothedRssi[nodeId]
        val newSmoothed = if (previousSmoothed != null) {
            RSSI_SMOOTHING_ALPHA * rssi + (1 - RSSI_SMOOTHING_ALPHA) * previousSmoothed
        } else {
            rssi.toDouble()
        }
        smoothedRssi[nodeId] = newSmoothed
        
        // Find the strongest beacon by smoothed RSSI
        val strongestEntry = smoothedRssi.maxByOrNull { it.value } ?: return
        val strongestNodeId = strongestEntry.key
        val strongestRssi = strongestEntry.value
        
        val now = System.currentTimeMillis()
        
        // First detection (no current room set)
        if (currentRoom == null) {
            setCurrentRoom(strongestNodeId)
            return
        }
        
        // Same as current room - nothing to do
        if (strongestNodeId == currentRoom) {
            candidateRoom = null
            return
        }
        
        // Different beacon is strongest - apply hysteresis
        val currentRoomRssi = smoothedRssi[currentRoom] ?: Double.NEGATIVE_INFINITY
        val rssiDifference = strongestRssi - currentRoomRssi
        
        if (rssiDifference < RSSI_HYSTERESIS_DB) {
            // Not strong enough to switch - stay at current room
            candidateRoom = null
            return
        }
        
        // New beacon is significantly stronger - start dwell timer
        if (candidateRoom != strongestNodeId) {
            candidateRoom = strongestNodeId
            candidateStartTime = now
            return
        }
        
        // Same candidate - check if dwell time elapsed
        if (now - candidateStartTime >= MIN_DWELL_TIME_MS) {
            // Stable enough - switch room
            candidateRoom = null
            setCurrentRoom(strongestNodeId)
        }
    }
    
    /**
     * Actually update the current room after stabilization
     */
    private fun setCurrentRoom(nodeId: Int) {
        val displayName = GraphData.getDisplayName(nodeId)
        currentRoom = nodeId
        currentRoomDisplayName = displayName
        callback?.onRoomChanged(displayName)
        
        // During active step-by-step navigation, don't auto-declare arrival from BLE
        // Arrival is handled by confirmStepCompleted() on the last step
        if (isActivelyNavigating) {
            Log.d(TAG, "Active navigation: BLE detected destination beacon, but arrival handled by step confirmation")
            return
        }
        
        // Check if arrived at destination (only when NOT in step-by-step mode)
        if (destinationRoom != null && destinationRoom == nodeId) {
            val message = "Anda berada di $displayName. Selamat, Anda telah sampai di tujuan!"
            ttsManager.speak(message)
            resetNavigation()
            callback?.onDestinationReached(displayName)
            return
        }
        
        // Only speak room arrival when a destination is set (active navigation mode)
        // In passive mode, just update the UI silently via callback
        if (destinationRoom != null) {
            ttsManager.speak("Anda berada di $displayName")
        } else {
            Log.d(TAG, "Passive mode: Room updated to $displayName (nodeId=$nodeId)")
        }
        
        // If destination is set and we're on the path, update position
        if (destinationRoom != null && currentPath != null) {
            val pathIndex = currentPath!!.indexOf(nodeId)
            if (pathIndex >= 0) {
                currentPathIndex = pathIndex
                
                // Only start next step automatically if we are actively navigating
                if (isActivelyNavigating) {
                    startNextStep()
                }
            } else {
                // Not on path - recalculate route
                recalculateRoute()
            }
        } else if (destinationRoom != null) {
            // Destination set but no path yet - calculate
            recalculateRoute()
        }
    }

    /**
     * Calculate route using Dijkstra algorithm
     */
    private fun recalculateRoute() {
        val start = currentRoom ?: return
        val end = destinationRoom ?: return
        
        val path = Dijkstra.shortestPath(start, end)
        
        if (path.isEmpty() || path.size < 2) {
            ttsManager.speak("Tidak ada rute dari ${currentRoomDisplayName} ke $destinationDisplayName")
            return
        }
        
        currentPath = path
        currentPathIndex = 0
        
        // We do NOT set isActivelyNavigating = true here anymore.
        // It will be set to true when the user explicitly clicks "Mulai Navigasi" in the UI.
        // (But wait, we need to handle this properly, let's leave recalculateRoute as is for path generation,
        // but avoid locking BLE yet).
        
        val totalSteps = path.size - 1
        ttsManager.speak("Navigasi ke $destinationDisplayName. Total $totalSteps segmen.")
        
        // Start first step after delay
        handler.postDelayed({ startNextStep() }, STEP_START_DELAY_MS)
    }

    /**
     * Start the next navigation step
     */
    private fun startNextStep() {
        val path = currentPath ?: return
        
        if (currentPathIndex >= path.size - 1) {
            // Already at destination
            callback?.onNavigationComplete()
            return
        }
        
        val fromNodeId = path[currentPathIndex]
        val toNodeId = path[currentPathIndex + 1]
        
        val fromNode = GraphData.getNode(fromNodeId)
        val toNode = GraphData.getNode(toNodeId)
        
        if (fromNode == null || toNode == null) {
            Log.e(TAG, "Invalid nodes in path: $fromNodeId -> $toNodeId")
            return
        }
        
        // Generate instruction using current compass heading
        val currentHeading = compassManager.getCurrentHeading()
        currentStepInstruction = ManualNavGuide.generateNavInstruction(fromNode, toNode, currentHeading)
        
        isAligning = true
        waitingForUserConfirmation = false
        lastSpokenHeading = -1f
        
        // Reset step counter for this edge
        currentUserStepCount = 0
        targetEdgeStepCount = currentStepInstruction!!.stepCount
        
        val stepNum = currentPathIndex + 1
        val totalSteps = path.size - 1
        val instruction = currentStepInstruction!!
        
        callback?.onStepChanged(stepNum, totalSteps, instruction.targetBearing.toFloat(), 
            ManualNavGuide.instructionToSpeech(instruction))
        
        // If step counting mode, also send initial counter state
        if (useStepCounting) {
            callback?.onStepCountUpdated(0, targetEdgeStepCount)
        }
        
        // Announce the step — hanya kiri/kanan derajat, tanpa mata angin
        val speechText = ManualNavGuide.instructionToSpeech(instruction)
        val message = "Langkah $stepNum dari $totalSteps. $speechText."
        
        ttsManager.speak(message)
        callback?.onAlignmentGuidance("Menyesuaikan arah...", 0f)
    }

    override fun onHeadingChanged(heading: Float) {
        val instruction = currentStepInstruction

        if (instruction == null) {
            callback?.onHeadingUpdated(heading, -1f)
            return
        }

        val targetHeading = instruction.targetBearing.toFloat()
        callback?.onHeadingUpdated(heading, targetHeading)

        val diff = compassManager.calculateHeadingDiff(heading, targetHeading)
        
        // Check if tighter tolerance is needed for the current step
        var currentTolerance = DEFAULT_HEADING_TOLERANCE
        val path = currentPath
        if (path != null && currentPathIndex < path.size - 1) {
            val fromNodeId = path[currentPathIndex]
            val toNodeId = path[currentPathIndex + 1]
            if ((fromNodeId == 10 && toNodeId == 11) || (fromNodeId == 11 && toNodeId == 10)) {
                currentTolerance = TIGHT_HEADING_TOLERANCE
            }
        }
        
        val isAligned = abs(diff) <= currentTolerance
        val now = System.currentTimeMillis()

        if (isAligning) {
            if (isAligned) {
                // ✅ Arah TEPAT — interupsi TTS salah, ucapkan "Arah Sudah Benar" SEKALI
                if (!lastAlignedState) {
                    lastAlignedState = true
                    waitingForUserConfirmation = true
                    isAligning = false
                    lastSpokenHeading = -1f

                    val path = currentPath
                    val isLastStep = path != null && currentPathIndex >= path.size - 2
                    val speechInstruction = ManualNavGuide.instructionToSpeech(instruction)
                    val lastStepSuffix = if (isLastStep) " Ini langkah terakhir." else ""
                    val ttsMessage = if (instruction.stepCount > 0)
                        "Arah Sudah Benar. Maju ${instruction.stepCount} langkah.$lastStepSuffix"
                    else
                        "Arah Sudah Benar.$lastStepSuffix"

                    // Interrupt TTS yang sedang berjalan, langsung ucapkan konfirmasi arah
                    ttsManager.speakInterrupt(ttsMessage)
                    callback?.onAlignmentComplete(speechInstruction)
                }
            } else {
                // ❌ Arah SALAH — TTS setiap 5 detik
                lastAlignedState = false
                if (now - lastGuidanceTtsTime >= GUIDANCE_TTS_INTERVAL_MS) {
                    val guidance = ManualNavGuide.getAlignmentGuidance(diff, currentTolerance)
                    ttsManager.speak(guidance)
                    callback?.onAlignmentGuidance(guidance, abs(diff))
                    lastGuidanceTtsTime = now
                }
            }
        } else if (waitingForUserConfirmation) {
            if (!isAligned) {
                // User berbelok setelah aligned
                if (lastAlignedState) {
                    lastAlignedState = false
                }
                if (now - lastGuidanceTtsTime >= GUIDANCE_TTS_INTERVAL_MS) {
                    val warning = ManualNavGuide.getAlignmentGuidance(diff, currentTolerance)
                    ttsManager.speak(warning)
                    callback?.onAlignmentGuidance(warning, abs(diff))
                    lastGuidanceTtsTime = now
                }
            } else {
                // Masih aligned — update UI hijau tanpa TTS berulang
                if (!lastAlignedState) {
                    lastAlignedState = true
                    val stepInfo = if (instruction.stepCount > 0) "Jalan ${instruction.stepCount} langkah." else ""
                    ttsManager.speakInterrupt("Arah Sudah Benar. $stepInfo")
                    callback?.onAlignmentComplete(ManualNavGuide.instructionToSpeech(instruction))
                }
            }
        }

    }

    /**
     * Called when user confirms they completed the current step.
     * Advances to next step or finishes navigation.
     * Button is always enabled - alignment is advisory only.
     */
    fun confirmStepCompleted() {
        val path = currentPath ?: return
        
        currentPathIndex++
        waitingForUserConfirmation = false
        isAligning = false
        currentStepInstruction = null
        currentUserStepCount = 0
        targetEdgeStepCount = 0
        
        if (currentPathIndex >= path.size - 1) {
            // All steps completed - announce arrival at destination!
            val destName = destinationDisplayName ?: "tujuan"
            ttsManager.speak("Selamat, Anda sudah sampai di $destName!")
            callback?.onDestinationReached(destName)
            callback?.onNavigationComplete()
            resetNavigation()
        } else {
            // Start next step
            startNextStep()
        }
    }

    /**
     * Called when user taps the step counting button.
     * Increments the step counter for the current edge and announces progress.
     * When steps reach the target, auto-advances to next edge.
     */
    fun onUserStep() {
        if (!useStepCounting || targetEdgeStepCount <= 0) return
        
        currentUserStepCount++
        val remaining = (targetEdgeStepCount - currentUserStepCount).coerceAtLeast(0)
        
        callback?.onStepCountUpdated(currentUserStepCount, targetEdgeStepCount)
        
        // Announce progress at every 5 steps, or when close to target (last 3)
        if (currentUserStepCount % 5 == 0 || remaining <= 3) {
            if (remaining > 0) {
                ttsManager.speak("$currentUserStepCount langkah, sisa $remaining")
            }
        }
        
        // Auto-advance when target reached
        if (currentUserStepCount >= targetEdgeStepCount) {
            ttsManager.speak("Langkah selesai. Lanjut ke segmen berikutnya.")
            // Short delay before advancing so TTS finishes
            handler.postDelayed({ confirmStepCompleted() }, 1500L)
        }
    }

    fun getCurrentUserStepCount(): Int = currentUserStepCount
    fun getTargetEdgeStepCount(): Int = targetEdgeStepCount

    /**
     * Check if we should speak guidance based on heading change
     */
    private fun shouldSpeakGuidance(currentHeading: Float): Boolean {
        if (lastSpokenHeading < 0) {
            lastSpokenHeading = currentHeading
            return true
        }
        
        var change = abs(currentHeading - lastSpokenHeading)
        if (change > 180) change = 360 - change
        
        if (change >= HEADING_CHANGE_THRESHOLD) {
            lastSpokenHeading = currentHeading
            return true
        }
        return false
    }

    /**
     * Set navigation destination using display name
     */
    fun setDestination(displayName: String) {
        // Find node ID from display name
        val nodeId = GraphData.getDestinations().find { 
            GraphData.getDisplayName(it.id) == displayName 
        }?.id
        
        if (nodeId == null) {
            ttsManager.speak("Tujuan tidak ditemukan: $displayName")
            return
        }
        
        setDestinationById(nodeId)
    }
    
    /**
     * Set navigation destination using node ID
     */
    fun setDestinationById(nodeId: Int) {
        destinationRoom = nodeId
        destinationDisplayName = GraphData.getDisplayName(nodeId)
        resetNavigationState()
        
        if (currentRoom != null) {
            // Already at a beacon, calculate route
            recalculateRoute()
        } else {
            ttsManager.speak("Tujuan: $destinationDisplayName. Jalan menuju beacon terdekat.")
        }
    }

    /**
     * Get all destination room display names
     */
    fun getAllRooms(): List<String> {
        return GraphData.getDestinationNames()
    }
    
    /**
     * Get available destinations (all rooms except current)
     */
    fun getAvailableDestinations(): List<String> {
        return GraphData.getDestinations()
            .filter { it.id != currentRoom }
            .map { GraphData.getDisplayName(it.id) }
    }

    private fun resetNavigationState() {
        currentPath = null
        currentPathIndex = 0
        currentStepInstruction = null
        isAligning = false
        waitingForUserConfirmation = false
        lastSpokenHeading = -1f
        currentUserStepCount = 0
        targetEdgeStepCount = 0
    }

    private fun resetNavigation() {
        destinationRoom = null
        destinationDisplayName = null
        isActivelyNavigating = false  // Unlock BLE - respond to all beacons again
        resetNavigationState()
    }

    fun getCurrentRoom(): String? = currentRoomDisplayName
    fun getDestinationRoom(): String? = destinationDisplayName
    fun isWaitingForConfirmation(): Boolean = waitingForUserConfirmation
    fun getCurrentStepIndex(): Int = currentPathIndex
    fun getTotalSteps(): Int = (currentPath?.size ?: 1) - 1

    /**
     * Navigation step data class (for callback compatibility)
     */
    data class NavigationStep(
        val heading: Float,
        val instruction: String
    )

    /**
     * Callback interface for navigation events
     */
    interface Callback {
        fun onRoomChanged(roomName: String)
        fun onDestinationReached(roomName: String)
        fun onHeadingUpdated(currentHeading: Float, targetHeading: Float)
        fun onStepChanged(stepNum: Int, totalSteps: Int, targetHeading: Float, instruction: String)
        fun onAlignmentGuidance(guidance: String, degreesRemaining: Float)
        fun onAlignmentComplete(instruction: String)
        fun onNavigationComplete()
        fun onStepCountUpdated(currentStep: Int, targetStep: Int)
    }
}
