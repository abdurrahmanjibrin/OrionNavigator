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
import android.widget.Toast
import android.net.Uri
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
import com.orion.app.navigation.GraphData
import com.orion.app.navigation.MapDataLoader
import com.orion.app.tts.TTSManager
import com.orion.app.tts.TtsSettingsDialog
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

    // File picker launcher for JSON map import
    private val jsonPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            importJsonMap(uri)
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
        if (ttsReady) {
            tts?.setSpeechRate(TTSManager.getSavedSpeechRate(this))
        }
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
                if (checkVoiceFloorSwitch(spokenText)) {
                    return
                }
                if (spokenText.isBlank()) {
                    speak("Maaf, suara tidak terdengar jelas. Silakan ucapkan Hello Orion dan coba lagi.")
                } else {
                    speak("Perintah \"$spokenText\" tidak dikenali. Katakan bantuan untuk daftar perintah.")
                }
            }
            else -> {
                if (checkVoiceFloorSwitch(spokenText)) {
                    return
                }
                speak("Perintah tidak tersedia di halaman ini.")
            }
        }
    }

    private fun checkVoiceFloorSwitch(spokenText: String): Boolean {
        if (spokenText.isBlank()) return false
        val lower = spokenText.lowercase()
            .replace("satu", "1")
            .replace("dua", "2")
            .replace("tiga", "3")
            .replace("empat", "4")
            .replace("lima", "5")

        if (!lower.contains("lantai") && !lower.contains("floor") && !lower.contains("ground") && !lower.contains("layer")) {
            return false
        }

        val layers = MapDataLoader.getAvailableLayers(this)
        for (layer in layers) {
            val fLower = layer.floorName.lowercase()
            if (lower.contains(fLower) || (fLower.startsWith("lantai ") && lower.contains(fLower.substring(7)))) {
                MapDataLoader.setActiveLayer(this, layer.id)
                val msg = "Beralih ke layer ${layer.floorName}"
                speak(msg)
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                return true
            }
        }
        return false
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
            tts?.setSpeechRate(TTSManager.getSavedSpeechRate(this))
            
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

        // Settings button — compass & map settings
        btnSettings.setOnClickListener {
            vibrate()
            showSettingsMenuDialog()
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

    private fun showSettingsMenuDialog() {
        val currentTtsRate = TTSManager.getSavedSpeechRate(this)
        val options = arrayOf(
            "🗣️ Kecepatan Suara (TTS) — ${TTSManager.getSpeechRateLabel(currentTtsRate)}",
            "🏢 Pilih Lantai / Layer Denah",
            "➕ Impor Denah Baru (Tambah Layer)",
            "🗑️ Hapus Layer Denah Kustom",
            "🧭 Pengaturan Metode Kompas",
            "🧪 Layar Uji Sensor Kompas & Gyro",
            "🔄 Reset ke Bawaan (Lantai 1 Saja)",
            "📍 Info Denah & Layer Aktif"
        )

        AlertDialog.Builder(this)
            .setTitle("⚙️ Pengaturan Orion")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showTtsSpeedSettingsDialog()
                    1 -> showFloorSelectionDialog()
                    2 -> {
                        speak("Pilih file denah JSON dari penyimpanan")
                        jsonPickerLauncher.launch("*/*")
                    }
                    3 -> showDeleteLayerDialog()
                    4 -> showCompassSettingsDialog()
                    5 -> {
                        startActivity(Intent(this, CompassTestActivity::class.java))
                    }
                    6 -> resetMapToDefault()
                    7 -> showActiveMapInfo()
                }
            }
            .setNegativeButton("Tutup") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun showTtsSpeedSettingsDialog() {
        TtsSettingsDialog.show(
            context = this,
            onPreviewSpeak = { text, rate ->
                if (ttsReady) {
                    tts?.setSpeechRate(rate)
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_speed_preview")
                }
            },
            onRestoreRate = { originalRate ->
                if (ttsReady) {
                    tts?.setSpeechRate(originalRate)
                }
            },
            onSaved = { newRate ->
                if (ttsReady) {
                    tts?.setSpeechRate(newRate)
                }
            }
        )
    }

    private fun showFloorSelectionDialog() {
        val layers = MapDataLoader.getAvailableLayers(this)
        val activeId = MapDataLoader.getActiveLayerId(this)
        val activeIdx = layers.indexOfFirst { it.id == activeId }.coerceAtLeast(0)

        val items = layers.map { layer ->
            val status = if (layer.isBuiltIn) "Bawaan" else "Kustom"
            "${layer.displayName}\n${layer.nodeCount} titik • ${layer.destinationCount} ruangan ($status)"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("🏢 Pilih Lantai / Layer Denah")
            .setSingleChoiceItems(items, activeIdx) { dialog, which ->
                val selected = layers.getOrNull(which)
                if (selected != null) {
                    MapDataLoader.setActiveLayer(this, selected.id)
                    val msg = "Beralih ke ${selected.floorName}"
                    speak(msg)
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Batal") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun showDeleteLayerDialog() {
        val customLayers = MapDataLoader.getAvailableLayers(this).filter { !it.isBuiltIn }
        if (customLayers.isEmpty()) {
            val msg = "Tidak ada layer denah kustom yang tersimpan"
            speak(msg)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            return
        }

        val items = customLayers.map { "${it.displayName} (${it.nodeCount} titik)" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("🗑️ Hapus Layer Denah Kustom")
            .setItems(items) { _, which ->
                val target = customLayers[which]
                AlertDialog.Builder(this)
                    .setTitle("Konfirmasi Hapus")
                    .setMessage("Hapus layer '${target.displayName}'? Data denah ini akan dihapus dari penyimpanan.")
                    .setPositiveButton("Hapus") { _, _ ->
                        if (MapDataLoader.deleteLayer(this, target.id)) {
                            val msg = "Layer ${target.floorName} berhasil dihapus"
                            speak(msg)
                            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                        } else {
                            speak("Gagal menghapus layer")
                        }
                    }
                    .setNegativeButton("Batal", null)
                    .show()
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun importJsonMap(uri: Uri) {
        try {
            var fileName: String? = null
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    fileName = cursor.getString(nameIndex)
                }
            }
            contentResolver.openInputStream(uri)?.use { stream ->
                val result = MapDataLoader.importMapFromStream(this, stream, fileName)
                result.onSuccess { message ->
                    speak(message)
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }.onFailure { error ->
                    val errorMsg = "Gagal memuat file denah: ${error.message}"
                    speak(errorMsg)
                    Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
                }
            } ?: run {
                val errorMsg = "Tidak dapat membuka file yang dipilih"
                speak(errorMsg)
                Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            val errorMsg = "Terjadi kesalahan membaca file: ${e.message}"
            speak(errorMsg)
            Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
        }
    }

    private fun resetMapToDefault() {
        if (MapDataLoader.resetToDefault(this)) {
            val message = "Denah berhasil direset ke bawaan: ${GraphData.currentFloorName} (${GraphData.currentBuildingName})"
            speak(message)
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        } else {
            val message = "Gagal mereset denah ke bawaan"
            speak(message)
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showActiveMapInfo() {
        val activeLayer = MapDataLoader.getActiveLayer(this)
        val allLayers = MapDataLoader.getAvailableLayers(this)
        val destCount = GraphData.getDestinations().size
        val totalNodes = GraphData.nodes.size
        val totalEdges = GraphData.edges.size
        val statusSource = if (activeLayer.isBuiltIn) "Bawaan Aplikasi" else "Kustom (Impor)"

        val layersSummary = allLayers.joinToString(", ") { it.floorName }
        val infoMsg = "Denah aktif: ${activeLayer.floorName} - ${activeLayer.buildingName} ($statusSource).\n" +
                "Total ${totalNodes} titik (${destCount} ruangan) dan ${totalEdges} jalur.\n" +
                "Tersedia ${allLayers.size} layer denah tersimpan: $layersSummary."
        speak("Denah aktif ${activeLayer.floorName}. Total ${allLayers.size} layer tersedia.")
        
        AlertDialog.Builder(this)
            .setTitle("📍 Info Denah & Layer")
            .setMessage(infoMsg)
            .setPositiveButton("OK") { d, _ -> d.dismiss() }
            .show()
    }

    private fun showCompassSettingsDialog() {
        val prefs = getSharedPreferences(SplashActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val currentMethod = prefs.getString(SplashActivity.KEY_COMPASS_METHOD, SplashActivity.METHOD_HYBRID)
        
        val options = arrayOf(
            "🚀 HYBRID (Kompas di Awal ➔ Gyro Saat Berjalan) — Paling Direkomendasikan",
            "🌟 Fused Orientation Provider (Google FOP) — Murni Kompas Google",
            "🎮 Gyroscope Only (Game Rotation Vector) — Respons Kiri/Kanan Saja",
            "🟢 Android Rotation Vector — Fusi sensor perangkat",
            "🔴 Klasik (Accelerometer + Magnetometer)"
        )
        val checkedItem = when (currentMethod) {
            SplashActivity.METHOD_HYBRID -> 0
            SplashActivity.METHOD_FOP -> 1
            SplashActivity.METHOD_GYRO -> 2
            SplashActivity.METHOD_NEW -> 3
            else -> 4
        }
        var selectedItem = checkedItem
        
        AlertDialog.Builder(this)
            .setTitle("⚙️ Metode Kompas")
            .setSingleChoiceItems(options, checkedItem) { _, which ->
                selectedItem = which
            }
            .setPositiveButton("Simpan") { _, _ ->
                val (newMethod, methodName) = when (selectedItem) {
                    0 -> Pair(SplashActivity.METHOD_HYBRID, "Hybrid (Kompas + Gyro)")
                    1 -> Pair(SplashActivity.METHOD_FOP, "Google FOP")
                    2 -> Pair(SplashActivity.METHOD_GYRO, "Gyroscope Only")
                    3 -> Pair(SplashActivity.METHOD_NEW, "Rotation Vector")
                    else -> Pair(SplashActivity.METHOD_OLD, "Sensor Klasik")
                }
                if (newMethod != currentMethod) {
                    prefs.edit().putString(SplashActivity.KEY_COMPASS_METHOD, newMethod).apply()
                    speak("Metode kompas diubah ke $methodName. Perubahan akan berlaku saat membuka navigasi.")
                    Toast.makeText(this, "Metode kompas: $methodName", Toast.LENGTH_SHORT).show()
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
