package com.orion.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import android.widget.ImageButton
import com.orion.app.R
import com.orion.app.data.LiveLocationRepository
import com.orion.app.service.LocationUpdateService
import com.orion.app.voice.GeminiCommandProcessor
import com.orion.app.voice.ListeningDialogHelper
import com.orion.app.voice.VoiceCommandManager
import com.orion.app.voice.VoiceIntent
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.*

/**
 * Live Location screen for Child (visually impaired user)
 * Features: Big toggle button, TTS feedback, parent linking, modern animated UI
 */
class LiveLocationChildActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    
    companion object {
        private const val TAG = "LiveLocationChild"
    }

    private lateinit var tvStatus: TextView
    private lateinit var btnToggle: MaterialButton
    private lateinit var tvLinkStatus: TextView
    private lateinit var btnLinkParent: MaterialButton
    private lateinit var btnDisconnect: MaterialButton
    private lateinit var tvLinkCode: TextView
    private lateinit var tvCodeInstruction: TextView
    private lateinit var viewStatusIndicator: View
    private lateinit var layoutCodeDisplay: LinearLayout
    private lateinit var layoutNotLinked: LinearLayout
    private lateinit var layoutLinked: LinearLayout
    private lateinit var viewRingOuter: View
    private lateinit var viewRingMiddle: View
    private lateinit var viewRingInner: View
    private lateinit var fabMic: ImageButton
    private lateinit var btnWakeWord: ImageButton
    
    private val repository = LiveLocationRepository()
    private var userId: String? = null
    private var isLocationActive = false
    private var tts: TextToSpeech? = null

    // Voice Command
    private var voiceCommandManager: VoiceCommandManager? = null
    private var geminiCommandProcessor: GeminiCommandProcessor? = null
    private var pulseAnimation: Animation? = null
    private var toneGenerator: ToneGenerator? = null
    private var listeningDialog: ListeningDialogHelper? = null
    private var isWakeWordEnabled = false
    private var parentLinkJob: Job? = null

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            voiceCommandManager?.startWakeWordMode()
        } else {
            speakFeedback("Izin mikrofon diperlukan untuk perintah suara")
        }
    }
    
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            toggleLocationService(true)
        } else {
            Toast.makeText(this, R.string.error_permission_denied, Toast.LENGTH_LONG).show()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_location_child)
        
        initViews()
        setupStatusRings()
        setupListeners()
        initTts()
        initVoiceCommand()
        loadUserData()
        toneGenerator = try { ToneGenerator(AudioManager.STREAM_MUSIC, 100) } catch (e: Exception) { null }
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
        tts?.shutdown()
        voiceCommandManager?.destroy()
        toneGenerator?.release()
        toneGenerator = null
        super.onDestroy()
    }
    
    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        btnToggle = findViewById(R.id.btnToggle)
        tvLinkStatus = findViewById(R.id.tvLinkStatus)
        btnLinkParent = findViewById(R.id.btnGenerateCode) // Renamed from btnLinkParent to btnGenerateCode
        btnDisconnect = findViewById(R.id.btnDisconnect)
        tvLinkCode = findViewById(R.id.tvLinkCode)
        tvCodeInstruction = findViewById(R.id.tvLinkStatus) // Using tvLinkStatus if tvCodeInstruction doesn't exist
        viewStatusIndicator = findViewById(R.id.viewStatusIndicator)
        layoutCodeDisplay = findViewById(R.id.layoutCodeDisplay)
        layoutNotLinked = findViewById(R.id.layoutNotLinked)
        layoutLinked = findViewById(R.id.layoutLinked)
        viewRingOuter = findViewById(R.id.viewRingOuter)
        viewRingMiddle = findViewById(R.id.viewRingMiddle)
        viewRingInner = findViewById(R.id.viewRingInner)
        fabMic = findViewById(R.id.fabMic)
        btnWakeWord = findViewById(R.id.btnWakeWord)
    }
    
    private fun setupStatusRings() {
        // Create gradient drawables for the rings
        val inactiveColor = ContextCompat.getColor(this, R.color.status_pulse_inactive)
        
        listOf(viewRingOuter, viewRingMiddle, viewRingInner).forEach { ring ->
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(2, inactiveColor)
                setColor(android.graphics.Color.TRANSPARENT)
            }
            ring.background = drawable
        }
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
            
            // Pause/resume wake word during TTS to prevent mic interference
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    voiceCommandManager?.pauseForTTS()
                }
                override fun onDone(utteranceId: String?) {
                    voiceCommandManager?.resumeAfterTTS()
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    voiceCommandManager?.resumeAfterTTS()
                }
            })
        }
    }
    
    private fun setupListeners() {
        btnToggle.setOnClickListener {
            if (isLocationActive) {
                toggleLocationService(false)
            } else {
                checkPermissionsAndStart()
            }
        }
        
        btnLinkParent.setOnClickListener {
            generateLinkCode()
        }
        
        btnDisconnect.setOnClickListener {
            disconnectParent()
        }

        // Tombol kembali (exit icon kiri atas)
        findViewById<android.widget.ImageButton>(R.id.btnLogout).setOnClickListener {
            speakFeedback("Kembali ke beranda")
            finish()
        }
    }
    
    private fun copyCodeToClipboard() {
        val code = tvLinkCode.text.toString()
        if (code.isNotEmpty() && code != getString(R.string.generating_code) && code != "Error") {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Link Code", code)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, R.string.code_copied, Toast.LENGTH_SHORT).show()
            speakFeedback(getString(R.string.code_copied))
        }
    }
    
    private fun loadUserData() {
        // Instantly check if the service is running locally (no Firebase roundtrip needed)
        // This fixes the UI showing "inactive" when returning to this screen
        // while the foreground service is still running.
        isLocationActive = LocationUpdateService.isServiceRunning
        updateUI()
        
        lifecycleScope.launch {
            try {
                userId = repository.getCurrentUserId()
                
                // Double-check with Firebase (for cases where service restarted)
                if (!isLocationActive) {
                    isLocationActive = repository.isLocationActive(userId!!)
                    runOnUiThread { updateUI() }
                }
                
                // Check linked parent
                val parentId = repository.getLinkedParentId(userId!!)
                runOnUiThread { updateLinkingUI(parentId != null) }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    private fun updateLinkingUI(isLinked: Boolean) {
        if (isLinked) {
            layoutNotLinked.visibility = View.GONE
            layoutLinked.visibility = View.VISIBLE
            layoutCodeDisplay.visibility = View.GONE
        } else {
            layoutNotLinked.visibility = View.VISIBLE
            layoutLinked.visibility = View.GONE
        }
    }
    
    private fun disconnectParent() {
        lifecycleScope.launch {
            try {
                userId?.let { uid ->
                    repository.unlinkParent(uid)
                    runOnUiThread {
                        updateLinkingUI(false)
                        Toast.makeText(this@LiveLocationChildActivity, 
                            "Berhasil memutuskan hubungan", Toast.LENGTH_SHORT).show()
                        speakFeedback("Hubungan dengan pendamping telah diputus")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this@LiveLocationChildActivity, 
                        "Gagal memutuskan hubungan", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun updateUI() {
        val activeColor = ContextCompat.getColor(this, R.color.status_pulse_active)
        val inactiveColor = ContextCompat.getColor(this, R.color.status_pulse_inactive)
        val accentColor = ContextCompat.getColor(this, R.color.accent)
        
        if (isLocationActive) {
            tvStatus.text = getString(R.string.live_location_active)
            tvStatus.setTextColor(activeColor)
            btnToggle.text = getString(R.string.stop)
            btnToggle.setBackgroundColor(android.graphics.Color.parseColor("#99CC00"))
            viewStatusIndicator.setBackgroundResource(R.drawable.bg_pulse_active)
            
            // Animate rings (color stays white, only button color changes)
            startRingAnimations()
        } else {
            tvStatus.text = getString(R.string.live_location_inactive)
            tvStatus.setTextColor(inactiveColor)
            btnToggle.text = getString(R.string.live_location_toggle)
            btnToggle.setBackgroundColor(ContextCompat.getColor(this, R.color.button_secondary))
            viewStatusIndicator.setBackgroundResource(R.drawable.bg_pulse_inactive)
            
            // Reset rings to inactive
            updateRingsColor(inactiveColor)
            stopRingAnimations()
        }
    }
    
    private fun updateRingsColor(color: Int) {
        listOf(viewRingOuter, viewRingMiddle, viewRingInner).forEach { ring ->
            val drawable = ring.background as? GradientDrawable
            drawable?.setStroke(2, color)
        }
    }
    
    private fun startRingAnimations() {
        try {
            val pulseAnim = AnimationUtils.loadAnimation(this, R.anim.pulse_animation)
            val fadeAnim = AnimationUtils.loadAnimation(this, R.anim.fade_pulse)
            val rippleAnim = AnimationUtils.loadAnimation(this, R.anim.ripple_expand)
            
            viewRingOuter.startAnimation(rippleAnim)
            viewRingMiddle.startAnimation(fadeAnim)
            viewRingInner.startAnimation(pulseAnim)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun stopRingAnimations() {
        viewRingOuter.clearAnimation()
        viewRingMiddle.clearAnimation()
        viewRingInner.clearAnimation()
    }
    
    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (notGranted.isEmpty()) {
            toggleLocationService(true)
        } else {
            locationPermissionLauncher.launch(notGranted.toTypedArray())
        }
    }
    
    private fun toggleLocationService(enable: Boolean) {
        isLocationActive = enable
        updateUI()
        
        val intent = Intent(this, LocationUpdateService::class.java).apply {
            action = if (enable) LocationUpdateService.ACTION_START else LocationUpdateService.ACTION_STOP
            putExtra(LocationUpdateService.EXTRA_USER_ID, userId)
        }
        
        if (enable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            speakFeedback(getString(R.string.tts_live_location_on))
        } else {
            startService(intent)
            speakFeedback(getString(R.string.tts_live_location_off))
        }
    }
    
    private fun generateLinkCode() {
        layoutCodeDisplay.visibility = View.VISIBLE
        tvLinkCode.text = getString(R.string.generating_code)
        
        lifecycleScope.launch {
            try {
                // First, make sure we have a userId
                if (userId == null) {
                    userId = repository.getCurrentUserId()
                }
                
                userId?.let { uid ->
                    val code = repository.generateLinkCode(uid)
                    runOnUiThread {
                        tvLinkCode.text = code
                        speakFeedback("Kode anda adalah $code")
                    }
                    // Start observing for parent connection so code screen auto-dismisses
                    startObservingParentLink(uid)
                } ?: run {
                    runOnUiThread {
                        tvLinkCode.text = "Error"
                        Toast.makeText(this@LiveLocationChildActivity, 
                            "Gagal login. Pastikan internet aktif.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    tvLinkCode.text = "Error"
                    Toast.makeText(this@LiveLocationChildActivity, 
                        "Firebase Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    /**
     * Observe linked parent status in real-time.
     * When a parent connects using the link code, this will fire and
     * automatically dismiss the code display + switch to linked UI.
     */
    private fun startObservingParentLink(childId: String) {
        parentLinkJob?.cancel()
        parentLinkJob = lifecycleScope.launch {
            repository.observeLinkedParent(childId).collectLatest { parentId ->
                if (parentId != null) {
                    runOnUiThread {
                        updateLinkingUI(true)
                        speakFeedback("Pendamping berhasil terhubung")
                    }
                    // Parent connected — stop observing
                    parentLinkJob?.cancel()
                }
            }
        }
    }

    private fun speakFeedback(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "feedback")
    }

    // =========================================================================
    // Voice Command
    // =========================================================================

    private fun initVoiceCommand() {
        geminiCommandProcessor = GeminiCommandProcessor(lifecycleScope)
        listeningDialog = ListeningDialogHelper(this)

        // Setup pulse animation for mic button
        pulseAnimation = AlphaAnimation(1f, 0.4f).apply {
            duration = 600
            repeatCount = Animation.INFINITE
            repeatMode = Animation.REVERSE
        }

        voiceCommandManager = VoiceCommandManager(
            context = this,
            geminiProcessor = geminiCommandProcessor,
            listener = object : VoiceCommandManager.OnCommandListener {
                override fun onCommandRecognized(spokenText: String, intent: VoiceIntent) {
                    Log.d(TAG, "Voice: '$spokenText' → $intent")
                    runOnUiThread { listeningDialog?.dismiss() }
                    handleVoiceIntent(intent, spokenText)
                }

                override fun onWakeWordDetected() {
                    runOnUiThread {
                        fabMic.setImageResource(R.drawable.ic_mic_active)
                        fabMic.startAnimation(pulseAnimation)
                        listeningDialog?.show(
                            statusText = "Mendengarkan...",
                            hintText = "Ucapkan perintah Anda"
                        )
                    }
                }

                override fun onListeningStarted() {
                    runOnUiThread {
                        fabMic.setImageResource(R.drawable.ic_mic_active)
                        fabMic.startAnimation(pulseAnimation)
                    }
                }

                override fun onListeningStopped() {
                    runOnUiThread {
                        fabMic.clearAnimation()
                        fabMic.setImageResource(R.drawable.ic_mic)
                        listeningDialog?.dismiss()
                    }
                }

                override fun onError(message: String) {
                    Log.e(TAG, "Voice error: $message")
                    runOnUiThread {
                        speakFeedback(message)
                    }
                }
            }
        )

        voiceCommandManager?.setAllowedIntents(setOf(
            VoiceIntent.TOGGLE_LOCATION_ON,
            VoiceIntent.TOGGLE_LOCATION_OFF,
            VoiceIntent.GO_BACK,
            VoiceIntent.HELP
        ))

        // Mic button → direct listen (bypass wake word)
        fabMic.setOnClickListener {
            directListen()
        }

        // Wake Word toggle button
        btnWakeWord.setOnClickListener {
            toggleWakeWord()
        }
    }

    private fun handleVoiceIntent(intent: VoiceIntent, spokenText: String = "") {
        when (intent) {
            VoiceIntent.TOGGLE_LOCATION_ON -> {
                if (!isLocationActive) {
                    checkPermissionsAndStart()
                } else {
                    speakFeedback("Lokasi sudah aktif")
                }
            }
            VoiceIntent.TOGGLE_LOCATION_OFF -> {
                if (isLocationActive) {
                    toggleLocationService(false)
                } else {
                    speakFeedback("Lokasi sudah tidak aktif")
                }
            }
            VoiceIntent.GO_BACK -> {
                speakFeedback("Kembali")
                finish()
            }
            VoiceIntent.HELP -> {
                speakFeedback("Ucapkan Hello Orion diikuti perintah. " +
                        "Perintah yang tersedia: " +
                        "Aktifkan lokasi, untuk menyalakan berbagi lokasi. " +
                        "Matikan lokasi, untuk mematikan berbagi lokasi. " +
                        "Kembali, untuk keluar.")
            }
            VoiceIntent.UNKNOWN -> {
                if (spokenText.isBlank()) {
                    speakFeedback("Maaf, suara tidak terdengar jelas. Silakan ucapkan Hello Orion dan coba lagi.")
                } else {
                    speakFeedback("Perintah \"$spokenText\" tidak dikenali. Katakan bantuan untuk daftar perintah.")
                }
            }
            else -> {
                speakFeedback("Perintah tidak tersedia di halaman ini.")
            }
        }
    }

    /** Toggle wake word listening on/off */
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
            speakFeedback("Hello Orion dinonaktifkan")
        } else {
            isWakeWordEnabled = true
            voiceCommandManager?.startWakeWordMode()
            btnWakeWord.setImageResource(R.drawable.ic_wakeword)
            speakFeedback("Hello Orion diaktifkan. Ucapkan Hello Orion diikuti perintah.")
        }
    }

    /** Direct listen — one-shot command without wake word */
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
}
