package com.orion.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.orion.app.R
import java.util.*

/**
 * Role selection screen - shown only on first launch
 * User selects: Tunanetra (blind user) or Pendamping (caregiver)
 * Role is saved permanently until logout
 */
class RoleSelectionActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    
    private lateinit var cardTunanetra: MaterialCardView
    private lateinit var cardPendamping: MaterialCardView
    
    private var tts: TextToSpeech? = null
    private var vibrator: Vibrator? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_role_selection)
        
        initViews()
        setupListeners()
        initTts()
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    
    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
    
    private fun initViews() {
        cardTunanetra = findViewById(R.id.cardChild)
        cardPendamping = findViewById(R.id.cardParent)
    }
    
    private fun initTts() {
        tts = TextToSpeech(this, this)
    }
    
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("id", "ID")
            
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            tts?.setAudioAttributes(audioAttributes)
            
            // Set listener to announce second option after first
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == "role_intro") {
                        // Announce both options with pause
                        runOnUiThread {
                            tts?.playSilentUtterance(500, TextToSpeech.QUEUE_ADD, null)
                            tts?.speak(
                                "Pilihan pertama: ${getString(R.string.role_child)}. ${getString(R.string.role_child_desc)}",
                                TextToSpeech.QUEUE_ADD,
                                null,
                                "option1"
                            )
                            tts?.playSilentUtterance(800, TextToSpeech.QUEUE_ADD, null)
                            tts?.speak(
                                "Pilihan kedua: ${getString(R.string.role_parent)}. ${getString(R.string.role_parent_desc)}",
                                TextToSpeech.QUEUE_ADD,
                                null,
                                "option2"
                            )
                        }
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {}
            })
            
            // Start announcement
            tts?.speak(
                getString(R.string.welcome_first_time),
                TextToSpeech.QUEUE_FLUSH,
                null,
                "role_intro"
            )
        }
    }
    
    private fun setupListeners() {
        cardTunanetra.setOnClickListener {
            vibrate()
            tts?.speak("Anda memilih ${getString(R.string.role_child)}", TextToSpeech.QUEUE_FLUSH, null, "selected")
            selectRole(SplashActivity.ROLE_TUNANETRA)
        }
        
        cardPendamping.setOnClickListener {
            vibrate()
            tts?.speak("Anda memilih ${getString(R.string.role_parent)}", TextToSpeech.QUEUE_FLUSH, null, "selected")
            selectRole(SplashActivity.ROLE_PENDAMPING)
        }
    }
    
    private fun vibrate() {
        vibrator?.vibrate(100)
    }
    
    private fun selectRole(role: String) {
        // Save role permanently
        val prefs = getSharedPreferences(SplashActivity.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(SplashActivity.KEY_USER_ROLE, role).apply()
        
        // Navigate to appropriate screen after short delay for TTS
        android.os.Handler(mainLooper).postDelayed({
            val intent = when (role) {
                SplashActivity.ROLE_TUNANETRA -> Intent(this, TunanetraActivity::class.java)
                SplashActivity.ROLE_PENDAMPING -> Intent(this, PendampingActivity::class.java)
                else -> return@postDelayed
            }
            startActivity(intent)
            finish()
        }, 1500)
    }
}
