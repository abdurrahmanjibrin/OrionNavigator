package com.orion.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import android.widget.ImageButton
import com.google.android.material.card.MaterialCardView
import com.orion.app.R
import com.orion.app.ble.BeaconSource
import com.orion.app.ble.BleScanner
import com.orion.app.navigation.BeaconRoomMapper
import com.orion.app.voice.GeminiCommandProcessor
import com.orion.app.voice.ListeningDialogHelper
import com.orion.app.voice.VoiceCommandManager
import com.orion.app.voice.VoiceIntent
import java.util.*

/**
 * Tunanetra Home Activity - Feature Selection
 * 
 * Shows 3 main features:
 * 1. Live Location - Share location with caregiver
 * 2. Navigation - Compass-based indoor navigation
 * 3. Camera AI - Object detection with camera
 * 
 * Fully accessible with TTS and haptic feedback
 */
class TunanetraActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    
    companion object {
        private const val TAG = "TunanetraActivity"
    }

    private lateinit var cardLiveLocation: MaterialCardView
    private lateinit var cardNavigation: MaterialCardView
    private lateinit var cardCameraAI: MaterialCardView
    private lateinit var btnLogout: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var btnMic: ImageButton
    private lateinit var btnWakeWord: ImageButton

    // Beacon panel views
    private var tvBeaconName: TextView? = null
    private var tvBeaconRssi: TextView? = null
    private var tvBeaconCount: TextView? = null
    private var progressBeaconRssi: ProgressBar? = null
    private var cardBeaconPanel: LinearLayout? = null

    // BLE Scanner for beacon panel
    private var bleScanner: BleScanner? = null
    // Map of beaconId -> Pair(displayName, rssi)
    private val beaconMap = mutableMapOf<String, Pair<String, Int>>()

    private var tts: TextToSpeech? = null
    private var vibrator: Vibrator? = null
    private var ttsReady = false
    private var toneGenerator: ToneGenerator? = null

    // Voice Command
    private var voiceCommandManager: VoiceCommandManager? = null
    private var geminiProcessor: GeminiCommandProcessor? = null
    private var isWakeWordEnabled = false  // tracks wake word toggle state
    private var pulseAnimation: Animation? = null

    // Permission launcher for RECORD_AUDIO
    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            voiceCommandManager?.startWakeWordMode()
        } else {
            speak("Izin mikrofon diperlukan untuk perintah suara")
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tunanetra_home)
        
        initViews()
        setupListeners()
        initTts()
        initVoiceCommand()
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        toneGenerator = try { ToneGenerator(AudioManager.STREAM_MUSIC, 100) } catch (e: Exception) { null }
        initBeaconPanel()
    }

    override fun onResume() {
        super.onResume()
        if (isWakeWordEnabled && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            voiceCommandManager?.startWakeWordMode()
        }
    }

    override fun onPause() {
        super.onPause()
        voiceCommandManager?.stopWakeWordMode()
    }
    
    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        voiceCommandManager?.destroy()
        toneGenerator?.release()
        toneGenerator = null
        bleScanner?.stopScan()
        bleScanner?.setOnBeaconDetectedListener(null)
        super.onDestroy()
    }
    
    private fun initViews() {
        cardLiveLocation = findViewById(R.id.cardLiveLocation)
        cardNavigation = findViewById(R.id.cardNavigation)
        cardCameraAI = findViewById(R.id.cardCameraAI)
        btnLogout = findViewById(R.id.btnLogout)
        btnSettings = findViewById(R.id.btnSettings)
        btnMic = findViewById(R.id.btnMic)
        btnWakeWord = findViewById(R.id.btnWakeWord)

        // Beacon panel views
        cardBeaconPanel = findViewById(R.id.cardBeaconPanel)
        tvBeaconName = findViewById(R.id.tvBeaconName)
        tvBeaconRssi = findViewById(R.id.tvBeaconRssi)
        tvBeaconCount = findViewById(R.id.tvBeaconCount)
        progressBeaconRssi = findViewById(R.id.progressBeaconRssi)

        // Setup pulse animation for mic button
        pulseAnimation = AlphaAnimation(1f, 0.4f).apply {
            duration = 600
            repeatCount = Animation.INFINITE
            repeatMode = Animation.REVERSE
        }
    }
    
    private fun initTts() {
        tts = TextToSpeech(this, this)
    }

    // -------- Beacon Panel --------
    @androidx.annotation.RequiresPermission(
        allOf = [
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ]
    )
    private fun initBeaconPanel() {
        // Only start if we have BLE permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return

        bleScanner = BleScanner(this)
        // Register known beacon UUID agar hanya beacon terdaftar yang terdeteksi
        bleScanner?.addRegisteredUuid("0112233445566778899aabbccddeeff0")
        bleScanner?.setOnBeaconDetectedListener(object : BeaconSource.OnBeaconDetectedListener {
            override fun onBeaconDetected(
                beaconId: String,
                uuid: String,
                major: Int,
                minor: Int,
                rssi: Int,
                txPower: Int
            ) {
                if (!BeaconRoomMapper.isBeaconRegistered(major, minor)) return
                
                val rawName = BeaconRoomMapper.getDisplayNameForBeacon(major, minor)
                val displayName = rawName.split(" ").joinToString(" ") { word ->
                    word.replaceFirstChar { it.uppercase() }
                }
                beaconMap[beaconId] = Pair(displayName, rssi)
                runOnUiThread { refreshBeaconPanel() }
            }

            override fun onBeaconLost(beaconId: String) {
                beaconMap.remove(beaconId)
                runOnUiThread { refreshBeaconPanel() }
            }

            override fun onScanError(errorMessage: String) {
                Log.w("TunanetraActivity", "BeaconPanel scan error: $errorMessage")
            }
        })
        bleScanner?.startScan()
    }

    private fun refreshBeaconPanel() {
        val count = beaconMap.size
        tvBeaconCount?.text = "$count beacon"

        if (beaconMap.isEmpty()) {
            tvBeaconName?.text = "Mencari sinyal beacon..."
            tvBeaconName?.contentDescription = "Posisi: Mencari sinyal beacon"
            tvBeaconRssi?.text = "-- dBm"
            progressBeaconRssi?.progress = 0
            return
        }

        // Pick beacon with strongest RSSI (highest value, since dBm is negative)
        val strongest = beaconMap.values.maxByOrNull { it.second } ?: return
        val name = strongest.first
        val rssi = strongest.second

        // Map RSSI from -30 dBm (100%) to -100 dBm (0%)
        val progress = ((rssi + 100).coerceIn(0, 70) * 100 / 70).coerceIn(0, 100)

        tvBeaconName?.text = name
        tvBeaconName?.contentDescription = "Posisi saat ini: $name"
        tvBeaconRssi?.text = "$rssi dBm"
        progressBeaconRssi?.progress = progress
    }
    // -------- End Beacon Panel --------

    private var listeningDialog: ListeningDialogHelper? = null

    private fun initVoiceCommand() {
        geminiProcessor = GeminiCommandProcessor(lifecycleScope)
        listeningDialog = ListeningDialogHelper(this)

        voiceCommandManager = VoiceCommandManager(
            context = this,
            geminiProcessor = geminiProcessor,
            listener = object : VoiceCommandManager.OnCommandListener {
                override fun onCommandRecognized(spokenText: String, intent: VoiceIntent) {
                    Log.d(TAG, "Voice command: '$spokenText' → $intent")
                    runOnUiThread { listeningDialog?.dismiss() }
                    handleVoiceIntent(intent, spokenText)
                }

                override fun onWakeWordDetected() {
                    runOnUiThread {
                        btnMic.setImageResource(R.drawable.ic_mic_active)
                        btnMic.startAnimation(pulseAnimation)
                        listeningDialog?.show(
                            statusText = "Mendengarkan...",
                            hintText = "Ucapkan perintah Anda"
                        )
                    }
                }

                override fun onListeningStarted() {
                    runOnUiThread {
                        btnMic.setImageResource(R.drawable.ic_mic_active)
                        btnMic.startAnimation(pulseAnimation)
                    }
                }

                override fun onListeningStopped() {
                    runOnUiThread {
                        btnMic.clearAnimation()
                        btnMic.setImageResource(R.drawable.ic_mic)
                        listeningDialog?.dismiss()
                    }
                }

                override fun onError(message: String) {
                    Log.e(TAG, "Voice error: $message")
                    runOnUiThread {
                        speak(message)
                    }
                }
            }
        )

        // Only allow home-screen intents
        voiceCommandManager?.setAllowedIntents(setOf(
            VoiceIntent.OPEN_LIVE_LOCATION,
            VoiceIntent.OPEN_NAVIGATION,
            VoiceIntent.OPEN_CAMERA,
            VoiceIntent.CHECK_LOCATION,
            VoiceIntent.LOGOUT,
            VoiceIntent.GO_BACK,
            VoiceIntent.HELP
        ))
    }

    private fun handleVoiceIntent(intent: VoiceIntent, spokenText: String = "") {
        vibrate()
        when (intent) {
            VoiceIntent.OPEN_LIVE_LOCATION -> {
                speakAndNavigate(
                    getString(R.string.feature_live_location),
                    LiveLocationChildActivity::class.java
                )
            }
            VoiceIntent.OPEN_NAVIGATION -> {
                speakAndNavigate(
                    "Navigasi Kompas",
                    DirectionActivity::class.java
                )
            }
            VoiceIntent.OPEN_CAMERA -> {
                speakAndNavigate(
                    getString(R.string.feature_camera_ai),
                    CameraAIActivity::class.java
                )
            }
            VoiceIntent.CHECK_LOCATION -> {
                val beaconName = tvBeaconName?.text?.toString() ?: ""
                if (beaconName == "Mencari sinyal beacon..." || beaconName.isBlank()) {
                    speak("Lokasi belum ditemukan, masih mencari sinyal.")
                } else {
                    speak("Posisi Anda saat ini berada di sekitar $beaconName.")
                }
            }
            VoiceIntent.LOGOUT -> {
                showLogoutDialog()
            }
            VoiceIntent.GO_BACK -> {
                speak("Kembali")
                finish()
            }
            VoiceIntent.HELP -> {
                speakHelp()
            }
            VoiceIntent.UNKNOWN -> {
                if (spokenText.isBlank()) {
                    speak("Maaf, suara tidak terdengar jelas. Silakan ucapkan Hello Orion dan coba lagi.")
                } else {
                    speak("Perintah \"$spokenText\" tidak dikenali. Katakan bantuan untuk daftar perintah.")
                }
            }
            else -> {
                speak("Perintah tidak tersedia di halaman ini.")
            }
        }
    }

    private fun speakHelp() {
        speak("Ucapkan Hello Orion diikuti perintah. " +
                "Perintah yang tersedia: " +
                "Buka kamera, untuk membuka kamera AI. " +
                "Buka navigasi, untuk navigasi kompas. " +
                "Buka lokasi, untuk berbagi lokasi. " +
                "Di mana saya, untuk cek lokasi saat ini. " +
                "Keluar, untuk logout.")
    }

    private fun toggleWakeWord() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        if (isWakeWordEnabled) {
            isWakeWordEnabled = false
            voiceCommandManager?.stopWakeWordMode()
            btnWakeWord.setImageResource(R.drawable.ic_wakeword_off)
            speak("Hello Orion dinonaktifkan")
        } else {
            isWakeWordEnabled = true
            voiceCommandManager?.startWakeWordMode()
            btnWakeWord.setImageResource(R.drawable.ic_wakeword)
            speak("Hello Orion diaktifkan. Ucapkan Hello Orion diikuti perintah.")
        }
    }

    private fun directListen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        listeningDialog?.show(
            statusText = "Mendengarkan...",
            hintText = "Ucapkan perintah Anda"
        )
        voiceCommandManager?.startListening()
    }

    private fun speak(text: String) {
        if (ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "speak")
        }
    }
    
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("id", "ID")
            
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            tts?.setAudioAttributes(audioAttributes)
            
            ttsReady = true
            
            // Announce screen after TTS ready + pause/resume wake word during TTS
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    // Pause wake word while TTS is speaking
                    voiceCommandManager?.pauseForTTS()
                }
                override fun onDone(utteranceId: String?) {
                    // Resume wake word after TTS finishes
                    voiceCommandManager?.resumeAfterTTS()
                    if (utteranceId == "welcome") {
                        runOnUiThread {
                            tts?.speak(
                                "Tiga fitur tersedia: Live Location, Navigasi Kompas, dan Kamera AI.",
                                TextToSpeech.QUEUE_ADD,
                                null,
                                "features"
                            )
                        }
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    voiceCommandManager?.resumeAfterTTS()
                }
            })
            
            tts?.speak(
                getString(R.string.tunanetra_home_title),
                TextToSpeech.QUEUE_FLUSH,
                null,
                "welcome"
            )
        }
    }
    
    private fun setupListeners() {
        // Live Location card
        cardLiveLocation.setOnClickListener {
            vibrate()
            speakAndNavigate(
                getString(R.string.feature_live_location),
                LiveLocationChildActivity::class.java
            )
        }
        
        // Navigation card
        cardNavigation.setOnClickListener {
            vibrate()
            speakAndNavigate(
                "Navigasi Kompas",
                DirectionActivity::class.java
            )
        }
        
        // Long-press Navigation card → open Compass Test (temporary for testing)
        cardNavigation.setOnLongClickListener {
            vibrate()
            startActivity(Intent(this, CompassTestActivity::class.java))
            true
        }
        
        // Camera AI card
        cardCameraAI.setOnClickListener {
            vibrate()
            speakAndNavigate(
                getString(R.string.feature_camera_ai),
                CameraAIActivity::class.java
            )
        }
        
        // Logout button
        btnLogout.setOnClickListener {
            vibrate()
            showLogoutDialog()
        }

        // Settings button — compass settings
        btnSettings.setOnClickListener {
            vibrate()
            showCompassSettingsDialog()
        }

        // Mic button — direct listen (skip wake word)
        btnMic.setOnClickListener {
            vibrate()
            directListen()
        }

        // Wake word toggle button
        btnWakeWord.setOnClickListener {
            vibrate()
            toggleWakeWord()
        }
    }
    
    private fun vibrate() {
        vibrator?.vibrate(50)
    }
    
    private fun speakAndNavigate(featureName: String, activityClass: Class<*>) {
        if (ttsReady) {
            tts?.speak("Membuka $featureName", TextToSpeech.QUEUE_FLUSH, null, "navigate")
        }
        
        // Navigate after short delay for TTS
        android.os.Handler(mainLooper).postDelayed({
            val intent = Intent(this, activityClass)
            startActivity(intent)
        }, 800)
    }

    private fun showCompassSettingsDialog() {
        val prefs = getSharedPreferences(SplashActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val currentMethod = prefs.getString(SplashActivity.KEY_COMPASS_METHOD, SplashActivity.METHOD_NEW)
        
        val options = arrayOf(
            "🟢 Baru (Rotation Vector + Remap) — Lebih akurat",
            "🔴 Lama (Accelerometer + Magnetometer)"
        )
        val checkedItem = if (currentMethod == SplashActivity.METHOD_NEW) 0 else 1
        var selectedItem = checkedItem
        
        AlertDialog.Builder(this)
            .setTitle("⚙️ Metode Kompas")
            .setSingleChoiceItems(options, checkedItem) { _, which ->
                selectedItem = which
            }
            .setPositiveButton("Simpan") { _, _ ->
                val newMethod = if (selectedItem == 0) SplashActivity.METHOD_NEW else SplashActivity.METHOD_OLD
                if (newMethod != currentMethod) {
                    prefs.edit().putString(SplashActivity.KEY_COMPASS_METHOD, newMethod).apply()
                    val methodName = if (newMethod == SplashActivity.METHOD_NEW) "Baru" else "Lama"
                    speak("Metode kompas diubah ke $methodName. Perubahan akan berlaku saat membuka navigasi.")
                }
            }
            .setNegativeButton("Batal") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
    
    private fun showLogoutDialog() {
        tts?.speak(getString(R.string.logout_confirm), TextToSpeech.QUEUE_FLUSH, null, "confirm")
        
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.logout))
            .setMessage(getString(R.string.logout_confirm))
            .setPositiveButton(getString(R.string.yes)) { _, _ ->
                logout()
            }
            .setNegativeButton(getString(R.string.no)) { dialog, _ ->
                dialog.dismiss()
                tts?.speak("Dibatalkan", TextToSpeech.QUEUE_FLUSH, null, "cancel")
            }
            .show()
    }
    
    private fun logout() {
        // Clear saved role
        val prefs = getSharedPreferences(SplashActivity.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(SplashActivity.KEY_USER_ROLE).apply()
        
        if (ttsReady) {
            tts?.speak(getString(R.string.role_changed), TextToSpeech.QUEUE_FLUSH, null, "logout")
        }
        
        // Navigate to role selection
        android.os.Handler(mainLooper).postDelayed({
            val intent = Intent(this, RoleSelectionActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }, 1500)
    }
}
