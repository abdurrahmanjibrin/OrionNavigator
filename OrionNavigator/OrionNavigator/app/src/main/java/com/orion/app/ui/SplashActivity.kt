package com.orion.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.appcompat.app.AppCompatActivity
import com.orion.app.R
import com.orion.app.navigation.MapDataLoader
import java.util.Locale

/**
 * Splash screen activity - entry point of the application
 * Checks for saved role and navigates accordingly
 * TTS announces app name for accessibility
 */
@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private val splashDelay = 2500L
    
    companion object {
        const val PREFS_NAME = "OrionPrefs"
        const val KEY_USER_ROLE = "user_role"
        const val ROLE_TUNANETRA = "tunanetra"
        const val ROLE_PENDAMPING = "pendamping"
        
        // Compass method preference
        const val KEY_COMPASS_METHOD = "compass_method"
        const val METHOD_HYBRID = "hybrid" // HYBRID: Kompas di awal -> Kunci Gyro saat berjalan (Rekomendasi Utama)
        const val METHOD_FOP = "fop"      // Google Fused Orientation Provider (Paling akurat & konsisten lintas merk HP)
        const val METHOD_GYRO = "gyro"    // Gyroscope Only (Game Rotation Vector) - Hanya Kiri/Kanan, Kebal Atas/Bawah
        const val METHOD_NEW = "new"      // Android Rotation Vector (OS sensor fusion)
        const val METHOD_OLD = "old"      // Accelerometer + Magnetometer (Legacy)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Load indoor navigation map from storage/assets
        MapDataLoader.loadMap(this)

        // Initialize TTS for welcome message
        tts = TextToSpeech(this, this)

        // Navigate after delay
        Handler(Looper.getMainLooper()).postDelayed({
            navigateBasedOnRole()
        }, splashDelay)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Set Indonesian language
            val result = tts?.setLanguage(Locale("id", "ID"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.getDefault())
            }
            
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            tts?.setAudioAttributes(audioAttributes)
            
            // Speak welcome message
            tts?.speak(
                getString(R.string.app_name) + ". Selamat datang.",
                TextToSpeech.QUEUE_FLUSH,
                null,
                "welcome"
            )
        }
    }
    
    private fun navigateBasedOnRole() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedRole = prefs.getString(KEY_USER_ROLE, null)
        
        val intent = when (savedRole) {
            ROLE_TUNANETRA -> Intent(this, TunanetraActivity::class.java)
            ROLE_PENDAMPING -> Intent(this, PendampingActivity::class.java)
            else -> Intent(this, RoleSelectionActivity::class.java)
        }
        
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
