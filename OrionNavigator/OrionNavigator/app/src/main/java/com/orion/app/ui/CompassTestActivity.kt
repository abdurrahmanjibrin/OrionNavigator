package com.orion.app.ui

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.orion.app.R
import kotlin.math.abs

/**
 * CompassTestActivity - Membandingkan heading dari dua metode:
 * 1. LAMA: Accelerometer + Magnetometer (tanpa remap)
 * 2. BARU: Rotation Vector + remapCoordinateSystem
 *
 * Miringkan HP untuk melihat apakah heading baru tetap konsisten.
 * Setelah testing berhasil, activity ini bisa dihapus.
 */
class CompassTestActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager

    // Sensors
    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null
    private var rotationVector: Sensor? = null

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

        // Init views
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
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                // Low-pass filter (same as current CompassManager)
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
            Sensor.TYPE_ROTATION_VECTOR -> {
                updateNewMethod(event)
            }
        }
    }

    /**
     * METODE LAMA - Sama persis dengan CompassManager.kt saat ini
     * Tidak ada remapCoordinateSystem - heading berubah saat HP miring
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

            // Also get pitch for phone position display
            pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
            roll = Math.toDegrees(orientation[2].toDouble()).toFloat()

            oldHeading = heading
            updateUI()
        }
    }

    /**
     * METODE BARU - Menggunakan TYPE_ROTATION_VECTOR + remapCoordinateSystem
     * Heading harus konsisten di semua posisi HP
     */
    private fun updateNewMethod(event: SensorEvent) {
        val rotationMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

        // Detect phone orientation from pitch
        // Use gravity to determine if phone is flat or upright
        val absPitch = abs(pitch)

        val remappedMatrix = FloatArray(9)

        if (absPitch < 40) {
            // Phone is mostly FLAT (rata) - standard mapping
            // World X → Device X, World Y → Device Y
            SensorManager.remapCoordinateSystem(
                rotationMatrix,
                SensorManager.AXIS_X,
                SensorManager.AXIS_Y,
                remappedMatrix
            )
        } else {
            // Phone is mostly UPRIGHT (tegak) - remap Z axis
            // World X → Device X, World Z → Device Y
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

        newHeading = heading
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
        tvDifference.text = String.format("%.0f°", diff)

        // Verdict
        when {
            diff < 5 -> {
                tvVerdict.text = "✅ Sangat akurat! Heading konsisten."
                tvVerdict.setTextColor(0xFF4CAF50.toInt())
            }
            diff < 15 -> {
                tvVerdict.text = "⚠️ Selisih kecil, masih dalam toleransi (15°)"
                tvVerdict.setTextColor(0xFFFFD740.toInt())
            }
            else -> {
                tvVerdict.text = "❌ Selisih besar! Metode lama tidak akurat di posisi ini."
                tvVerdict.setTextColor(0xFFFF5252.toInt())
            }
        }

        // Phone position
        tvPitch.text = String.format("Pitch: %.0f°", pitch)
        tvRoll.text = String.format("Roll: %.0f°", roll)

        val absPitch = abs(pitch)
        when {
            absPitch < 20 -> {
                tvPhonePosition.text = "📱 Posisi: RATA (flat)"
                tvPhonePosition.setTextColor(0xFF4CAF50.toInt())
            }
            absPitch < 50 -> {
                tvPhonePosition.text = "📱 Posisi: MIRING (~${absPitch.toInt()}°)"
                tvPhonePosition.setTextColor(0xFFFFD740.toInt())
            }
            else -> {
                tvPhonePosition.text = "📱 Posisi: TEGAK (upright)"
                tvPhonePosition.setTextColor(0xFF64B5F6.toInt())
            }
        }
    }

    private fun getDirectionName(heading: Float): String {
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

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
