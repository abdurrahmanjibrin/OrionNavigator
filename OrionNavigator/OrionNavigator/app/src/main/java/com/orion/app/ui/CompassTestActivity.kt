package com.orion.app.ui

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.orion.app.R
import kotlin.math.abs

/**
 * CompassTestActivity - Interactive testing lab for Orion sensor systems:
 * 1. 🎮 GYRO ONLY (Game Rotation Vector): Tests immunity to tilt up/down (pitch), only responds to left/right body turns.
 * 2. 🟢 ROTATION VECTOR + REMAP: Magnetic compass with upright compensation.
 * 3. 🔴 KLASIK: Legacy accelerometer + magnetometer.
 */
class CompassTestActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager

    // Sensors
    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null
    private var rotationVector: Sensor? = null
    private var gameRotationVector: Sensor? = null

    // Gyro only data
    private var gyroHeading = 0f
    private var gyroZeroOffset = 0f
    private var isGyroZeroCalibrated = false
    private var lastObservedPitch = 0f

    // Old method data
    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var hasGravity = false
    private var hasGeomagnetic = false
    private var oldHeading = 0f

    // New method data
    private var newHeading = 0f

    // Smoothing
    private val ALPHA = 0.3f

    // Phone orientation
    private var pitch = 0f
    private var roll = 0f

    // Views
    private lateinit var tvGyroHeading: TextView
    private lateinit var tvGyroDirection: TextView
    private lateinit var tvGyroLiveStatus: TextView
    private lateinit var btnResetGyroZero: MaterialButton

    private lateinit var tvOldHeading: TextView
    private lateinit var tvOldDirection: TextView
    private lateinit var tvNewHeading: TextView
    private lateinit var tvNewDirection: TextView
    private lateinit var tvDifference: TextView
    private lateinit var tvVerdict: TextView
    private lateinit var tvPitch: TextView
    private lateinit var tvRoll: TextView
    private lateinit var tvPhonePosition: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_compass_test)

        // Init Gyro views
        tvGyroHeading = findViewById(R.id.tvGyroHeading)
        tvGyroDirection = findViewById(R.id.tvGyroDirection)
        tvGyroLiveStatus = findViewById(R.id.tvGyroLiveStatus)
        btnResetGyroZero = findViewById(R.id.btnResetGyroZero)

        btnResetGyroZero.setOnClickListener {
            isGyroZeroCalibrated = false
            tvGyroLiveStatus.text = "🎯 Arah saat ini dikunci sebagai 0°!"
        }

        // Init other views
        tvOldHeading = findViewById(R.id.tvOldHeading)
        tvOldDirection = findViewById(R.id.tvOldDirection)
        tvNewHeading = findViewById(R.id.tvNewHeading)
        tvNewDirection = findViewById(R.id.tvNewDirection)
        tvDifference = findViewById(R.id.tvDifference)
        tvVerdict = findViewById(R.id.tvVerdict)
        tvPitch = findViewById(R.id.tvPitch)
        tvRoll = findViewById(R.id.tvRoll)
        tvPhonePosition = findViewById(R.id.tvPhonePosition)

        // Init sensors
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        gameRotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        magnetometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        rotationVector?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        val gyroSensor = gameRotationVector ?: rotationVector
        gyroSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                updateGyroOnlyMethod(event)
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                if (gameRotationVector == null) {
                    updateGyroOnlyMethod(event)
                }
                updateNewMethod(event)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                gravity[0] = ALPHA * event.values[0] + (1 - ALPHA) * gravity[0]
                gravity[1] = ALPHA * event.values[1] + (1 - ALPHA) * gravity[1]
                gravity[2] = ALPHA * event.values[2] + (1 - ALPHA) * gravity[2]
                hasGravity = true
                updateOldMethod()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                geomagnetic[0] = ALPHA * event.values[0] + (1 - ALPHA) * geomagnetic[0]
                geomagnetic[1] = ALPHA * event.values[1] + (1 - ALPHA) * geomagnetic[1]
                geomagnetic[2] = ALPHA * event.values[2] + (1 - ALPHA) * geomagnetic[2]
                hasGeomagnetic = true
                updateOldMethod()
            }
        }
    }

    /**
     * 1. GYRO ONLY (Game Rotation Vector)
     * Tracks yaw relative to Earth's gravity vector.
     * Completely ignores pitch (tilt up/down).
     */
    private fun updateGyroOnlyMethod(event: SensorEvent) {
        val rotationMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

        val orientation = FloatArray(3)
        SensorManager.getOrientation(rotationMatrix, orientation)

        var rawYaw = Math.toDegrees(orientation[0].toDouble()).toFloat()
        if (rawYaw < 0) rawYaw += 360f

        if (!isGyroZeroCalibrated) {
            gyroZeroOffset = rawYaw
            isGyroZeroCalibrated = true
        }

        var relHeading = (rawYaw - gyroZeroOffset + 360f) % 360f
        gyroHeading = relHeading

        updateGyroUI()
    }

    private fun updateGyroUI() {
        tvGyroHeading.text = String.format("%.0f°", gyroHeading)
        tvGyroDirection.text = getDirectionName(gyroHeading)

        val pitchDiff = abs(pitch - lastObservedPitch)
        if (pitchDiff > 10f) {
            lastObservedPitch = pitch
            tvGyroLiveStatus.text = String.format("📱 HP condong %.0f° (Atas/Bawah) ➔ Derajat Gyro tetap %.0f° (Stabil! ✅)", abs(pitch), gyroHeading)
        }
    }

    /**
     * 2. METODE LAMA - Accelerometer + Magnetometer tanpa remap
     */
    private fun updateOldMethod() {
        if (!hasGravity || !hasGeomagnetic) return

        val rotationMatrix = FloatArray(9)
        val inclinationMatrix = FloatArray(9)

        if (SensorManager.getRotationMatrix(rotationMatrix, inclinationMatrix, gravity, geomagnetic)) {
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientation)

            var heading = Math.toDegrees(orientation[0].toDouble()).toFloat()
            if (heading < 0) heading += 360f

            pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
            roll = Math.toDegrees(orientation[2].toDouble()).toFloat()

            oldHeading = heading
            updateUI()
        }
    }

    /**
     * 3. METODE BARU - TYPE_ROTATION_VECTOR dengan remap saat tegak
     */
    private fun updateNewMethod(event: SensorEvent) {
        val rotationMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

        val orientation = FloatArray(3)
        SensorManager.getOrientation(rotationMatrix, orientation)

        val pitchDeg = abs(Math.toDegrees(orientation[1].toDouble()).toFloat())

        val heading: Float = if (pitchDeg > 70f) {
            val remappedMatrix = FloatArray(9)
            SensorManager.remapCoordinateSystem(
                rotationMatrix,
                SensorManager.AXIS_X,
                SensorManager.AXIS_MINUS_Z,
                remappedMatrix
            )
            val remappedOrientation = FloatArray(3)
            SensorManager.getOrientation(remappedMatrix, remappedOrientation)
            Math.toDegrees(remappedOrientation[0].toDouble()).toFloat()
        } else {
            Math.toDegrees(orientation[0].toDouble()).toFloat()
        }

        var norm = heading
        if (norm < 0) norm += 360f
        newHeading = norm

        updateUI()
    }

    private fun updateUI() {
        // Old heading
        tvOldHeading.text = String.format("%.0f°", oldHeading)
        tvOldDirection.text = getDirectionName(oldHeading)

        // New heading
        tvNewHeading.text = String.format("%.0f°", newHeading)
        tvNewDirection.text = getDirectionName(newHeading)

        // Difference
        var diff = abs(newHeading - oldHeading)
        if (diff > 180) diff = 360 - diff
        tvDifference.text = String.format("Selisih Kompas Magnet: %.0f°", diff)

        // Verdict
        when {
            diff < 5 -> {
                tvVerdict.text = "✅ Kompas stabil di posisi ini."
                tvVerdict.setTextColor(0xFF4CAF50.toInt())
            }
            diff < 15 -> {
                tvVerdict.text = "⚠️ Selisih kecil (masih batas toleransi 15°)"
                tvVerdict.setTextColor(0xFFFFD740.toInt())
            }
            else -> {
                tvVerdict.text = "❌ Selisih besar pada metode klasik saat miring!"
                tvVerdict.setTextColor(0xFFFF5252.toInt())
            }
        }

        // Phone position
        tvPitch.text = String.format("Pitch (Condong Atas/Bawah): %.0f°", pitch)
        tvRoll.text = String.format("Roll (Miring Kiri/Kanan): %.0f°", roll)

        val absPitch = abs(pitch)
        when {
            absPitch < 20 -> {
                tvPhonePosition.text = "📱 Posisi: RATA (Flat)"
                tvPhonePosition.setTextColor(0xFF4CAF50.toInt())
            }
            absPitch < 65 -> {
                tvPhonePosition.text = "📱 Posisi: CONDONG DI DADA (~${absPitch.toInt()}°)"
                tvPhonePosition.setTextColor(0xFF00E5FF.toInt())
            }
            else -> {
                tvPhonePosition.text = "📱 Posisi: TEGAK (Upright)"
                tvPhonePosition.setTextColor(0xFF64B5F6.toInt())
            }
        }
    }

    private fun getDirectionName(heading: Float): String {
        return when {
            heading >= 337.5f || heading < 22.5f -> "Utara (0°)"
            heading >= 22.5f && heading < 67.5f -> "Timur Laut (45°)"
            heading >= 67.5f && heading < 112.5f -> "Timur (90°)"
            heading >= 112.5f && heading < 157.5f -> "Tenggara (135°)"
            heading >= 157.5f && heading < 202.5f -> "Selatan (180°)"
            heading >= 202.5f && heading < 247.5f -> "Barat Daya (225°)"
            heading >= 247.5f && heading < 292.5f -> "Barat (270°)"
            else -> "Barat Laut (315°)"
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
