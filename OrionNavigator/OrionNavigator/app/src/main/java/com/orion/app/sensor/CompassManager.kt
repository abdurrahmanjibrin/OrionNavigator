package com.orion.app.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.DeviceOrientation
import com.google.android.gms.location.DeviceOrientationListener
import com.google.android.gms.location.DeviceOrientationRequest
import com.google.android.gms.location.FusedOrientationProviderClient
import com.google.android.gms.location.LocationServices
import com.orion.app.ui.SplashActivity
import kotlin.math.abs

/**
 * CompassManager - Manages device compass heading.
 * Supports three methods:
 * 1. FOP (DEFAULT): Google Fused Orientation Provider (Google Play Services)
 *    Fuses accelerometer, gyroscope, and magnetometer with Google's advanced multi-device algorithms.
 *    Most consistent across different device manufacturers.
 * 2. NEW: Android Rotation Vector (OS sensor fusion) with orientation stability & smooth upright detection.
 * 3. OLD: Accelerometer + Magnetometer (legacy fallback).
 */
class CompassManager(private val context: Context, private val callback: Callback) : SensorEventListener {

    companion object {
        private const val TAG = "CompassManager"
        
        // Smoothing factor for compass readings (circular low-pass filter)
        private const val ALPHA = 0.35f
        
        // Tolerance for alignment check (degrees)
        const val HEADING_TOLERANCE = 15f

        // Upright thresholds with hysteresis (degrees).
        // Standard portrait handheld holding angle is between 25° and 55°.
        // Only when the phone is held nearly vertical (e.g. pointing the camera straight ahead in Camera AI mode)
        // should the forward vector be remapped to the back camera (-Z).
        private const val UPRIGHT_ENTER_THRESHOLD = 70f
        private const val UPRIGHT_EXIT_THRESHOLD = 55f
    }

    private val sensorManager: SensorManager = 
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // Google Fused Orientation Provider (FOP)
    private val fopClient: FusedOrientationProviderClient? = try {
        LocationServices.getFusedOrientationProviderClient(context)
    } catch (e: Exception) {
        Log.w(TAG, "FusedOrientationProviderClient not available", e)
        null
    }
    private var isFopActive = false

    private val fopListener = DeviceOrientationListener { orientation: DeviceOrientation ->
        val heading = orientation.headingDegrees
        if (!heading.isNaN()) {
            lastCompassHeading = heading
            if (!isGyroLocked) {
                applyNewHeading(heading)
            }
        }
    }

    // Hardware sensors
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val rotationVector: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gameRotationVector: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    // Old method data
    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var hasGravity = false
    private var hasGeomagnetic = false

    // State
    private var currentHeading = 0f
    private var isFirstHeading = true
    private var isRunning = false
    private var isUpright = false
    private var gyroZeroOffset = 0f
    private var isGyroZeroCalibrated = false

    // Hybrid tracking state
    private var isGyroLocked = false
    private var gyroLockOffset = 0f
    private var lastRawGyroYaw = 0f
    private var lastCompassHeading = 0f

    // Active method setting
    private val selectedMethod: String

    init {
        val prefs = context.getSharedPreferences(SplashActivity.PREFS_NAME, Context.MODE_PRIVATE)
        selectedMethod = prefs.getString(SplashActivity.KEY_COMPASS_METHOD, SplashActivity.METHOD_HYBRID) ?: SplashActivity.METHOD_HYBRID
        Log.d(TAG, "Configured compass method: $selectedMethod")
    }

    /**
     * Start listening to compass updates
     */
    fun start() {
        if (isRunning) return
        
        isFirstHeading = true
        isUpright = false
        isFopActive = false
        isGyroLocked = false

        // 1. Gyroscope Only (Game Rotation Vector) mode - ignores pitch/tilt, only tracks left/right turns
        if (selectedMethod == SplashActivity.METHOD_GYRO) {
            val sensorToUse = gameRotationVector ?: rotationVector
            if (sensorToUse != null) {
                sensorManager.registerListener(this, sensorToUse, SensorManager.SENSOR_DELAY_GAME)
                isRunning = true
                Log.i(TAG, "Compass started with Gyro Only (GameRotationVector)")
                return
            }
        }

        // 2. In Hybrid or FOP mode: register gameRotationVector in background for gyro tracking
        val gyroSensor = gameRotationVector ?: rotationVector
        if (gyroSensor != null) {
            sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_GAME)
        }

        // 3. Try Google Fused Orientation Provider (FOP) if selected or in Hybrid mode
        val tryFop = (selectedMethod == SplashActivity.METHOD_HYBRID || selectedMethod == SplashActivity.METHOD_FOP)
        if (tryFop && fopClient != null) {
            try {
                val request = DeviceOrientationRequest.Builder(DeviceOrientationRequest.OUTPUT_PERIOD_DEFAULT).build()
                fopClient.requestOrientationUpdates(request, ContextCompat.getMainExecutor(context), fopListener)
                    .addOnSuccessListener {
                        isFopActive = true
                        isRunning = true
                        Log.i(TAG, "Compass started with Google Fused Orientation Provider (FOP) [Method=$selectedMethod]")
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "Failed to start Google FOP, falling back to Android Rotation Vector", e)
                        startHardwareSensors(useRotationVector = true)
                    }
                return
            } catch (e: Exception) {
                Log.w(TAG, "Error requesting FOP updates, falling back to Rotation Vector", e)
            }
        }

        // 4. Fallback to hardware compass sensors
        val useRotationVector = (selectedMethod != SplashActivity.METHOD_OLD)
        startHardwareSensors(useRotationVector)
    }

    private fun startHardwareSensors(useRotationVector: Boolean) {
        val canUseRotationVector = useRotationVector && (rotationVector != null)

        if (canUseRotationVector) {
            rotationVector?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        } else {
            accelerometer?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
            magnetometer?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }

        isRunning = true
        Log.d(TAG, "Compass started with hardware sensors (mode=${if (canUseRotationVector) "RotationVector" else "Accel+Magneto"})")
    }

    /**
     * Stop listening to compass updates
     */
    fun stop() {
        if (!isRunning && !isFopActive) return
        
        if (isFopActive && fopClient != null) {
            try {
                fopClient.removeOrientationUpdates(fopListener)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing FOP listener", e)
            }
            isFopActive = false
        }

        sensorManager.unregisterListener(this)
        isRunning = false
        hasGravity = false
        hasGeomagnetic = false
        isFirstHeading = true
        isUpright = false
        isGyroLocked = false
        isGyroZeroCalibrated = false
        Log.d(TAG, "Compass stopped")
    }

    /**
     * Check if compass is available on this device
     */
    fun isCompassAvailable(): Boolean {
        return fopClient != null || rotationVector != null || (accelerometer != null && magnetometer != null)
    }

    override fun onSensorChanged(event: SensorEvent) {
        // 1. ALWAYS process Gyroscope tracking (Game Rotation Vector), even when FOP is active!
        // This ensures lastRawGyroYaw is always alive and gyro tracking works seamlessly when locked.
        if (event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            updateGameRotationVector(event)
            return
        }

        // 2. If gyro is locked or gyro mode is active on a device without TYPE_GAME_ROTATION_VECTOR
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR && (selectedMethod == SplashActivity.METHOD_GYRO || isGyroLocked)) {
            updateGameRotationVector(event)
            return
        }

        // 3. For magnetic compass sensors, only process if Google FOP is NOT active
        if (isFopActive) return

        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                updateRotationVectorMethod(event)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                gravity[0] = ALPHA * event.values[0] + (1 - ALPHA) * gravity[0]
                gravity[1] = ALPHA * event.values[1] + (1 - ALPHA) * gravity[1]
                gravity[2] = ALPHA * event.values[2] + (1 - ALPHA) * gravity[2]
                hasGravity = true

                if (selectedMethod == SplashActivity.METHOD_OLD || rotationVector == null) {
                    updateOldMethod()
                }
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                geomagnetic[0] = ALPHA * event.values[0] + (1 - ALPHA) * geomagnetic[0]
                geomagnetic[1] = ALPHA * event.values[1] + (1 - ALPHA) * geomagnetic[1]
                geomagnetic[2] = ALPHA * event.values[2] + (1 - ALPHA) * geomagnetic[2]
                hasGeomagnetic = true

                if (selectedMethod == SplashActivity.METHOD_OLD || rotationVector == null) {
                    updateOldMethod()
                }
            }
        }
    }

    /**
     * METHOD: Gyroscope / Hybrid Tracking (Game Rotation Vector)
     * Pure Gyro + Accelerometer. Completely immune to pitch (tilt up/down) and magnetic interference.
     * Only turns left/right alter the heading.
     */
    private fun updateGameRotationVector(event: SensorEvent) {
        val rotationMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

        val orientation = FloatArray(3)
        SensorManager.getOrientation(rotationMatrix, orientation)

        // Pitch in Android: 0 is flat, -90° is upright (top pointing towards sky).
        val pitchDeg = abs(Math.toDegrees(orientation[1].toDouble()).toFloat())
        val rawYaw: Float
        if (pitchDeg > 45f) {
            val remappedMatrix = FloatArray(9)
            SensorManager.remapCoordinateSystem(
                rotationMatrix,
                SensorManager.AXIS_X,
                SensorManager.AXIS_MINUS_Z,
                remappedMatrix
            )
            val remappedOrientation = FloatArray(3)
            SensorManager.getOrientation(remappedMatrix, remappedOrientation)
            var y = Math.toDegrees(remappedOrientation[0].toDouble()).toFloat()
            if (y < 0) y += 360f
            rawYaw = y
        } else {
            var y = Math.toDegrees(orientation[0].toDouble()).toFloat()
            if (y < 0) y += 360f
            rawYaw = y
        }
        lastRawGyroYaw = rawYaw

        if (selectedMethod == SplashActivity.METHOD_GYRO) {
            // Pure gyro mode
            if (!isGyroZeroCalibrated) {
                gyroZeroOffset = rawYaw
                isGyroZeroCalibrated = true
                Log.i(TAG, "Gyro zero reference calibrated to: $gyroZeroOffset")
            }
            val relativeHeading = (rawYaw - gyroZeroOffset + 360f) % 360f
            applyNewHeading(relativeHeading)
        } else if (isGyroLocked) {
            // Hybrid locked mode: continues seamlessly from initial compass heading, driven purely by gyro yaw!
            val hybridHeading = (lastRawGyroYaw + gyroLockOffset + 360f) % 360f
            applyNewHeading(hybridHeading)
        }
    }

    /**
     * Lock heading tracking to Gyroscope.
     * Takes current compass heading as the anchor, then tracks all subsequent
     * movement strictly via Gyroscope (immune to tilt up/down and magnetic noise).
     */
    fun lockGyroTracking() {
        if (selectedMethod != SplashActivity.METHOD_HYBRID) {
            Log.d(TAG, "lockGyroTracking ignored: active method is '$selectedMethod' (not hybrid)")
            return
        }
        if (gameRotationVector == null && rotationVector == null) {
            Log.w(TAG, "Cannot lock gyro tracking: no gyro sensor available")
            return
        }
        val currentAnchor = if (lastCompassHeading > 0) lastCompassHeading else currentHeading
        gyroLockOffset = (currentAnchor - lastRawGyroYaw + 360f) % 360f
        isGyroLocked = true
        Log.i(TAG, "🚀 Hybrid Lock Engaged: Anchor=$currentAnchor°, GyroYaw=$lastRawGyroYaw°, Offset=$gyroLockOffset°")
    }

    /**
     * Unlock gyro tracking and return to absolute compass
     */
    fun unlockGyroTracking() {
        if (isGyroLocked) {
            isGyroLocked = false
            Log.i(TAG, "Hybrid Lock Released: Returned to Compass")
        }
    }

    fun isGyroLocked(): Boolean = isGyroLocked

    /**
     * Reset gyro zero point to the current facing direction (for Gyro-only mode)
     */
    fun resetGyroZero() {
        isGyroZeroCalibrated = false
    }

    /**
     * METHOD 2: Rotation Vector
     */
    private fun updateRotationVectorMethod(event: SensorEvent) {
        val rotationMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

        val orientation = FloatArray(3)
        SensorManager.getOrientation(rotationMatrix, orientation)

        // Pitch in Android: 0 is flat, -90° is upright (top pointing towards sky).
        val pitchDeg = abs(Math.toDegrees(orientation[1].toDouble()).toFloat())

        // Hysteresis to prevent flapping between handheld and upright mode
        if (!isUpright && pitchDeg > UPRIGHT_ENTER_THRESHOLD) {
            isUpright = true
        } else if (isUpright && pitchDeg < UPRIGHT_EXIT_THRESHOLD) {
            isUpright = false
        }

        val heading: Float
        if (isUpright) {
            // When held upright, remap device -Z (camera pointing forward) to World Y (North)
            val remappedMatrix = FloatArray(9)
            SensorManager.remapCoordinateSystem(
                rotationMatrix,
                SensorManager.AXIS_X,
                SensorManager.AXIS_MINUS_Z,
                remappedMatrix
            )
            val remappedOrientation = FloatArray(3)
            SensorManager.getOrientation(remappedMatrix, remappedOrientation)
            heading = Math.toDegrees(remappedOrientation[0].toDouble()).toFloat()
        } else {
            // Normal handheld walking angle: device +Y (top edge of phone) points forward
            heading = Math.toDegrees(orientation[0].toDouble()).toFloat()
        }

        lastCompassHeading = heading
        if (!isGyroLocked) {
            applyNewHeading(heading)
        }
    }

    /**
     * METHOD 3: Accelerometer + Magnetometer fallback
     */
    private fun updateOldMethod() {
        if (!hasGravity || !hasGeomagnetic) return

        val rotationMatrix = FloatArray(9)
        val inclinationMatrix = FloatArray(9)

        if (SensorManager.getRotationMatrix(rotationMatrix, inclinationMatrix, gravity, geomagnetic)) {
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientation)
            val heading = Math.toDegrees(orientation[0].toDouble()).toFloat()
            lastCompassHeading = heading
            if (!isGyroLocked) {
                applyNewHeading(heading)
            }
        }
    }

    /**
     * Apply circular smoothing and notify callback
     */
    private fun applyNewHeading(rawHeading: Float) {
        var heading = rawHeading
        if (heading < 0) heading += 360f

        if (isFirstHeading) {
            currentHeading = heading
            isFirstHeading = false
        } else {
            var diff = heading - currentHeading
            while (diff > 180f) diff -= 360f
            while (diff < -180f) diff += 360f
            currentHeading = (currentHeading + ALPHA * diff + 360f) % 360f
        }

        callback.onHeadingChanged(currentHeading)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        when (accuracy) {
            SensorManager.SENSOR_STATUS_UNRELIABLE -> {
                Log.w(TAG, "Sensor accuracy: UNRELIABLE - calibration needed")
            }
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> {
                Log.w(TAG, "Sensor accuracy: LOW")
            }
        }
    }

    /**
     * Get the current compass heading (0-360 degrees, 0 = North)
     */
    fun getCurrentHeading(): Float = currentHeading

    /**
     * Calculate the turn direction and angle to face the target heading.
     */
    fun calculateTurn(targetHeading: Float): TurnInstruction {
        var diff = targetHeading - currentHeading

        while (diff > 180) diff -= 360
        while (diff < -180) diff += 360

        val direction = when {
            abs(diff) < HEADING_TOLERANCE -> TurnDirection.STRAIGHT
            diff > 0 -> TurnDirection.RIGHT
            else -> TurnDirection.LEFT
        }

        return TurnInstruction(direction, abs(diff))
    }

    /**
     * Check if current heading is aligned with target heading
     */
    fun isAligned(targetHeading: Float): Boolean {
        val diff = calculateHeadingDiff(currentHeading, targetHeading)
        return abs(diff) <= HEADING_TOLERANCE
    }

    /**
     * Calculate the difference between two headings, normalized to -180 to 180
     */
    fun calculateHeadingDiff(current: Float, target: Float): Float {
        var diff = target - current
        while (diff > 180) diff -= 360
        while (diff < -180) diff += 360
        return diff
    }

    /**
     * Get the cardinal direction name for a heading in Indonesian
     */
    fun getDirectionName(heading: Float): String {
        return when {
            heading >= 337.5f || heading < 22.5f -> "Utara"
            heading >= 22.5f && heading < 67.5f -> "Timur Laut"
            heading >= 67.5f && heading < 112.5f -> "Timur"
            heading >= 112.5f && heading < 157.5f -> "Tenggara"
            heading >= 157.5f && heading < 202.5f -> "Selatan"
            heading >= 202.5f && heading < 247.5f -> "Barat Daya"
            heading >= 247.5f && heading < 292.5f -> "Barat"
            else -> "Barat Laut"
        }
    }

    /**
     * Callback interface for heading updates
     */
    interface Callback {
        fun onHeadingChanged(heading: Float)
    }

    enum class TurnDirection {
        LEFT,
        RIGHT,
        STRAIGHT
    }

    data class TurnInstruction(
        val direction: TurnDirection,
        val angle: Float
    ) {
        fun getDirectionText(): String {
            return when (direction) {
                TurnDirection.LEFT -> "kiri"
                TurnDirection.RIGHT -> "kanan"
                TurnDirection.STRAIGHT -> "lurus"
            }
        }

        fun toSpeech(): String {
            return when (direction) {
                TurnDirection.STRAIGHT -> "Jalan lurus"
                else -> {
                    var roundedAngle = (Math.round(angle / 15) * 15).toInt()
                    if (roundedAngle < 15) roundedAngle = 15
                    "Putar ke ${getDirectionText()} $roundedAngle derajat"
                }
            }
        }

        fun isAligned(): Boolean = direction == TurnDirection.STRAIGHT
    }
}
