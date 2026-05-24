package com.orion.app.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.orion.app.ui.SplashActivity
import kotlin.math.abs

/**
 * CompassManager - Manages device compass heading.
 * Supports two methods:
 * 1. OLD: Accelerometer + Magnetometer (original, less accurate when tilted)
 * 2. NEW: Rotation Vector + remapCoordinateSystem (stable at all orientations)
 *
 * Method is read from SharedPreferences. Default: NEW.
 */
class CompassManager(context: Context, private val callback: Callback) : SensorEventListener {

    companion object {
        private const val TAG = "CompassManager"
        
        // Smoothing factor for compass readings (higher = more responsive, lower = smoother)
        private const val ALPHA = 0.5f
        
        // Tolerance for alignment check (degrees)
        const val HEADING_TOLERANCE = 15f

        // Pitch threshold for switching remap axes (new method)
        private const val PITCH_THRESHOLD = 40f
    }

    private val sensorManager: SensorManager = 
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // Sensors - old method
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    // Sensor - new method
    private val rotationVector: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    // Old method data
    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var hasGravity = false
    private var hasGeomagnetic = false

    // New method data
    private var pitch = 0f

    private var currentHeading = 0f
    private var isRunning = false

    // Which method to use
    private val useNewMethod: Boolean

    init {
        val prefs = context.getSharedPreferences(SplashActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val method = prefs.getString(SplashActivity.KEY_COMPASS_METHOD, SplashActivity.METHOD_NEW)
        useNewMethod = (method == SplashActivity.METHOD_NEW)
        Log.d(TAG, "Compass method: ${if (useNewMethod) "NEW (RotationVector)" else "OLD (Accel+Magneto)"}")
    }

    /**
     * Start listening to compass sensors
     */
    fun start() {
        if (isRunning) return
        
        if (useNewMethod) {
            // New method: Rotation Vector (+ accelerometer for pitch detection)
            rotationVector?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
            accelerometer?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
            magnetometer?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        } else {
            // Old method: Accelerometer + Magnetometer
            accelerometer?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
            magnetometer?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
        
        isRunning = true
        Log.d(TAG, "Compass started")
    }

    /**
     * Stop listening to compass sensors
     */
    fun stop() {
        if (!isRunning) return
        
        sensorManager.unregisterListener(this)
        isRunning = false
        hasGravity = false
        hasGeomagnetic = false
        Log.d(TAG, "Compass stopped")
    }

    /**
     * Check if compass is available on this device
     */
    fun isCompassAvailable(): Boolean {
        return if (useNewMethod) {
            rotationVector != null
        } else {
            accelerometer != null && magnetometer != null
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                // Low-pass filter
                gravity[0] = ALPHA * event.values[0] + (1 - ALPHA) * gravity[0]
                gravity[1] = ALPHA * event.values[1] + (1 - ALPHA) * gravity[1]
                gravity[2] = ALPHA * event.values[2] + (1 - ALPHA) * gravity[2]
                hasGravity = true

                if (!useNewMethod) {
                    updateOldMethod()
                }
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                // Low-pass filter
                geomagnetic[0] = ALPHA * event.values[0] + (1 - ALPHA) * geomagnetic[0]
                geomagnetic[1] = ALPHA * event.values[1] + (1 - ALPHA) * geomagnetic[1]
                geomagnetic[2] = ALPHA * event.values[2] + (1 - ALPHA) * geomagnetic[2]
                hasGeomagnetic = true

                if (!useNewMethod) {
                    updateOldMethod()
                }
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                if (useNewMethod) {
                    updateNewMethod(event)
                }
            }
        }
    }

    /**
     * OLD METHOD - Accelerometer + Magnetometer (no remap)
     */
    private fun updateOldMethod() {
        if (!hasGravity || !hasGeomagnetic) return

        val rotationMatrix = FloatArray(9)
        val inclinationMatrix = FloatArray(9)

        if (SensorManager.getRotationMatrix(rotationMatrix, inclinationMatrix, gravity, geomagnetic)) {
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientation)

            // Convert to degrees (0-360)
            var heading = Math.toDegrees(orientation[0].toDouble()).toFloat()
            if (heading < 0) {
                heading += 360f
            }

            currentHeading = heading
            callback.onHeadingChanged(heading)
        }
    }

    /**
     * NEW METHOD - Rotation Vector + remapCoordinateSystem
     * Heading stays consistent regardless of phone tilt
     */
    private fun updateNewMethod(event: SensorEvent) {
        val rotationMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

        // Also compute pitch from accelerometer for tilt detection
        if (hasGravity && hasGeomagnetic) {
            val tempR = FloatArray(9)
            val tempI = FloatArray(9)
            if (SensorManager.getRotationMatrix(tempR, tempI, gravity, geomagnetic)) {
                val tempO = FloatArray(3)
                SensorManager.getOrientation(tempR, tempO)
                pitch = Math.toDegrees(tempO[1].toDouble()).toFloat()
            }
        }

        val absPitch = abs(pitch)
        val remappedMatrix = FloatArray(9)

        if (absPitch < PITCH_THRESHOLD) {
            // Phone is mostly FLAT - standard mapping
            SensorManager.remapCoordinateSystem(
                rotationMatrix,
                SensorManager.AXIS_X,
                SensorManager.AXIS_Y,
                remappedMatrix
            )
        } else {
            // Phone is mostly UPRIGHT - remap Z axis
            SensorManager.remapCoordinateSystem(
                rotationMatrix,
                SensorManager.AXIS_X,
                SensorManager.AXIS_Z,
                remappedMatrix
            )
        }

        val orientation = FloatArray(3)
        SensorManager.getOrientation(remappedMatrix, orientation)

        var heading = Math.toDegrees(orientation[0].toDouble()).toFloat()
        if (heading < 0) heading += 360f

        currentHeading = heading
        callback.onHeadingChanged(heading)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used, but could be used to warn user about calibration
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
     * 
     * @param targetHeading Target heading in degrees (0-360)
     * @return TurnInstruction with direction and angle
     */
    fun calculateTurn(targetHeading: Float): TurnInstruction {
        var diff = targetHeading - currentHeading

        // Normalize to -180 to 180
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

    /**
     * Turn direction enum
     */
    enum class TurnDirection {
        LEFT,
        RIGHT,
        STRAIGHT
    }

    /**
     * Data class for turn instruction
     */
    data class TurnInstruction(
        val direction: TurnDirection,
        val angle: Float
    ) {
        /**
         * Get the direction name in Indonesian
         */
        fun getDirectionText(): String {
            return when (direction) {
                TurnDirection.LEFT -> "kiri"
                TurnDirection.RIGHT -> "kanan"
                TurnDirection.STRAIGHT -> "lurus"
            }
        }

        /**
         * Convert to Indonesian speech text
         */
        fun toSpeech(): String {
            return when (direction) {
                TurnDirection.STRAIGHT -> "Jalan lurus"
                else -> {
                    // Round to nearest 15 degrees for clearer instructions
                    var roundedAngle = (Math.round(angle / 15) * 15).toInt()
                    if (roundedAngle < 15) roundedAngle = 15
                    "Putar ke ${getDirectionText()} $roundedAngle derajat"
                }
            }
        }

        /**
         * Check if aligned (direction is straight)
         */
        fun isAligned(): Boolean = direction == TurnDirection.STRAIGHT
    }
}
