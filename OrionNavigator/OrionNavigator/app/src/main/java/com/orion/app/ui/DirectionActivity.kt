package com.orion.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import android.widget.ImageButton as AndroidImageButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.orion.app.R
import com.orion.app.ble.BeaconSource
import com.orion.app.ble.BleScanner
import com.orion.app.camera.CameraManager
import com.orion.app.gemini.GeminiHelper
import com.orion.app.ml.YoloDetectorHelper
import com.orion.app.navigation.BeaconRoomMapper
import com.orion.app.navigation.GraphData
import com.orion.app.navigation.IndoorNavigationManager
import com.orion.app.sensor.CompassManager
import com.orion.app.tts.TTSManager
import com.orion.app.voice.GeminiCommandProcessor
import com.orion.app.voice.ListeningDialogHelper
import com.orion.app.voice.VoiceCommandManager
import com.orion.app.voice.VoiceIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * DirectionActivity - Compass-based indoor navigation with step-by-step guidance.
 * Uses ViewPager2 with 2 swipeable pages:
 *   Page 0: Full navigation UI (compass, beacons, step controls)
 *   Page 1: Camera AI + minimal navigation overlay
 * 
 * TTS navigation guidance works identically on both pages.
 * Camera AI detection is only active when page 1 is visible.
 */
@ExperimentalGetImage
class DirectionActivity : AppCompatActivity(), 
    CompassManager.Callback,
    IndoorNavigationManager.Callback,
    BeaconSource.OnBeaconDetectedListener {

    companion object {
        private const val TAG = "DirectionActivity"
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val CAMERA_PERMISSION_REQUEST_CODE = 1002
        private const val FEEDBACK_COOLDOWN_MS = 300L
        private const val VIBRATE_INTERVAL_MS = 1000L
        private const val EXACT_TOLERANCE = 3.0f
        private const val NEUTRAL_TOLERANCE = 10f
        
        private const val STATE_NONE = -1
        private const val STATE_EXACT = 0
        private const val STATE_NEUTRAL = 1
        private const val STATE_OFF = 2
        
        private const val PAGE_NAVIGATION = 0
        private const val PAGE_CAMERA = 1
    }

    // ===== Core Managers =====
    private lateinit var compassManager: CompassManager
    private lateinit var ttsManager: TTSManager
    private lateinit var navigationManager: IndoorNavigationManager
    private lateinit var bleScanner: BleScanner
    
    // ===== Camera AI =====
    private var cameraManager: CameraManager? = null
    private var objectDetectorHelper: YoloDetectorHelper? = null
    private var cameraTtsManager: TTSManager? = null  // Separate TTS for object detection
    private var isCameraActive = false
    private var frameSkipCounter = 0
    private val frameSkipInterval = 5
    private var analysisImageWidth = 1
    private var analysisImageHeight = 1

    // ===== Gemini Describe =====
    private var geminiHelper: GeminiHelper? = null
    private var lastBitmap: Bitmap? = null
    private var isProcessingDescription = false
    // ToneGenerator untuk loading sound saat Jelaskan (harus di-release di onDestroy)
    private var descLoadingToneGenerator: ToneGenerator? = null
    private var descLoadingSoundJob: Job? = null

    // ===== State =====
    private var isNavigating = false
    private var useStepCounting = false
    private var currentPage = PAGE_NAVIGATION
    
    // ===== Direction Feedback =====
    private var vibrator: Vibrator? = null
    private var toneGenerator: ToneGenerator? = null
    private var lastFeedbackTime = 0L
    private var lastFeedbackState = STATE_NONE
    
    // ===== Beacon Tracking =====
    private data class BeaconSignal(val name: String, val rssi: Int, val major: Int, val minor: Int)
    private val detectedBeacons = mutableMapOf<String, BeaconSignal>()

    // ===== ViewPager & View References =====
    private lateinit var viewPager: ViewPager2
    private var dotNav: View? = null
    private var dotCamera: View? = null
    
    // Page 0 (Navigation) views
    private var navCurrentRoom: TextView? = null
    private var navSpinnerDestination: android.widget.Spinner? = null
    private var navStepCountingToggle: LinearLayout? = null
    private var navSwitchStepCounting: SwitchMaterial? = null
    private var navBtnStartNavigation: MaterialButton? = null
    private var navCardStep: View? = null
    private var navTvStepProgress: TextView? = null
    private var navTvStatus: TextView? = null
    private var navTvInstruction: TextView? = null
    private var navTvCurrentHeading: TextView? = null
    private var navTvTargetHeading: TextView? = null
    private var navIvCompassArrow: ImageView? = null
    private var navProgressAlignment: ProgressBar? = null
    private var navTvStepCounter: TextView? = null
    private var navBtnConfirmStep: MaterialButton? = null
    private var navBtnCountStep: MaterialButton? = null
    private var navBtnStop: MaterialButton? = null
    // Beacon views
    private var navLlBeacon1: LinearLayout? = null
    private var navLlBeacon2: LinearLayout? = null
    private var navLlBeacon3: LinearLayout? = null
    private var navTvBeacon1Name: TextView? = null
    private var navTvBeacon1Rssi: TextView? = null
    private var navViewBeacon1Indicator: View? = null
    private var navTvBeacon2Name: TextView? = null
    private var navTvBeacon2Rssi: TextView? = null
    private var navViewBeacon2Indicator: View? = null
    private var navTvBeacon3Name: TextView? = null
    private var navTvBeacon3Rssi: TextView? = null
    private var navViewBeacon3Indicator: View? = null
    private var navTvNoBeacons: TextView? = null
    
    // Page 1 (Camera) views
    private var camPreviewView: PreviewView? = null
    private var camDetectionOverlay: ImageView? = null
    private var camTvCurrentRoom: TextView? = null
    private var camSpinnerDestination: android.widget.Spinner? = null
    private var camLlStepCountingToggle: LinearLayout? = null
    private var camSwitchStepCounting: SwitchMaterial? = null
    private var camBtnStartNavigation: MaterialButton? = null
    private var camNavInfoPanel: LinearLayout? = null
    private var camTvStepProgress: TextView? = null
    private var camTvHeading: TextView? = null
    private var camTvTargetHeading: TextView? = null
    private var camTvStatus: TextView? = null
    private var camProgressAlignment: ProgressBar? = null
    private var camTvStepCounter: TextView? = null
    private var camTvObjectName: TextView? = null
    private var camBtnAction: MaterialButton? = null
    private var camBtnStop: MaterialButton? = null
    private var camBtnDescribe: MaterialButton? = null
    private var camProgressDescribe: ProgressBar? = null
    private var isSyncingSpinner = false  // prevent infinite sync loops
    private var isSyncingToggle = false   // prevent infinite sync loops

    // Bounding box paints (for camera page)
    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
    }
    private val labelBgPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        isAntiAlias = true
        isFakeBoldText = true
        setShadowLayer(2f, 1f, 1f, Color.BLACK)
    }
    private val boxColors = listOf(
        Color.parseColor("#FF4CAF50"),
        Color.parseColor("#FF2196F3"),
        Color.parseColor("#FFFF9800"),
        Color.parseColor("#FFE91E63"),
        Color.parseColor("#FF9C27B0"),
        Color.parseColor("#FF00BCD4"),
    )

    // ===== Voice Command =====
    private var voiceCommandManager: VoiceCommandManager? = null
    private var geminiCommandProcessor: GeminiCommandProcessor? = null
    private var fabMic: View? = null
    private var btnWakeWord: AndroidImageButton? = null
    private var isWakeWordEnabled = false
    private var pulseAnimation: Animation? = null

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            voiceCommandManager?.startWakeWordMode()
        } else {
            ttsManager.speak("Izin mikrofon diperlukan untuk perintah suara")
        }
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_direction)
        
        // Initialize managers
        ttsManager = TTSManager(this)
        compassManager = CompassManager(this, this)
        navigationManager = IndoorNavigationManager(this, compassManager, ttsManager)
        navigationManager.setCallback(this)
        
        // Initialize BLE scanner
        bleScanner = BleScanner(this)
        // Register known beacon UUID agar hanya beacon terdaftar yang terdeteksi
        bleScanner.addRegisteredUuid("0112233445566778899aabbccddeeff0")
        bleScanner.setOnBeaconDetectedListener(this)
        
        // Initialize Gemini Helper
        geminiHelper = GeminiHelper()
        
        // Direction feedback
        // Direction feedback — gunakan VibratorManager di Android 12+ untuk kompatibilitas lebih baik
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        toneGenerator = try { ToneGenerator(AudioManager.STREAM_MUSIC, 100) } catch (e: Exception) {
            Log.e(TAG, "Failed to create ToneGenerator", e)
            null
        }
        
        setupViewPager()
        initVoiceCommand()
        checkPermissions()
    }

    override fun onResume() {
        super.onResume()
        compassManager.start()
        bleScanner.startScan()
        if (isCameraActive && currentPage == PAGE_CAMERA) {
            cameraTtsManager?.resetLastSpoken()
        }
        // Start wake word mode if mic permission is granted and wakeword was enabled
        if (isWakeWordEnabled && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            voiceCommandManager?.startWakeWordMode()
        }
    }

    override fun onPause() {
        super.onPause()
        compassManager.stop()
        bleScanner.stopScan()
        stopCamera()
        voiceCommandManager?.stopWakeWordMode()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 1. Stop tung loop dulu agar Handler tidak callback setelah destroy
        stopTungLoop()
        // 2. Cancel coroutine describe loading
        descLoadingSoundJob?.cancel()
        descLoadingSoundJob = null
        isProcessingDescription = false
        // 3. Release tone generators
        toneGenerator?.release()
        toneGenerator = null
        descLoadingToneGenerator?.release()
        descLoadingToneGenerator = null
        // 4. Stop & destroy managers
        navigationManager.stop()
        bleScanner.stopScan()
        ttsManager.shutdown()
        cameraTtsManager?.shutdown()
        cameraManager?.shutdown()
        objectDetectorHelper?.close()
        voiceCommandManager?.destroy()
        Log.d(TAG, "DirectionActivity destroyed, all resources cleaned up")
    }

    // =========================================================================
    // ViewPager2 Setup
    // =========================================================================
    
    private fun setupViewPager() {
        viewPager = findViewById(R.id.viewPager)
        dotNav = findViewById(R.id.dotNav)
        dotCamera = findViewById(R.id.dotCamera)
        fabMic = findViewById(R.id.fabMic)
        btnWakeWord = findViewById(R.id.btnWakeWord)

        // Setup pulse animation for mic button
        pulseAnimation = AlphaAnimation(1f, 0.4f).apply {
            duration = 600
            repeatCount = Animation.INFINITE
            repeatMode = Animation.REVERSE
        }

        // Mic button → direct listen (bypass wake word)
        fabMic?.setOnClickListener {
            provideHapticFeedback()
            directListen()
        }

        // Wake Word toggle button
        btnWakeWord?.setOnClickListener {
            provideHapticFeedback()
            toggleWakeWord()
        }
        
        viewPager.adapter = NavigationPagerAdapter()
        viewPager.offscreenPageLimit = 1  // Keep both pages in memory
        
        updateDotIndicator(0)
        
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentPage = position
                updateDotIndicator(position)
                
                when (position) {
                    PAGE_NAVIGATION -> {
                        Log.d(TAG, "Switched to Navigation page")
                        stopCamera()
                    }
                    PAGE_CAMERA -> {
                        Log.d(TAG, "Switched to Camera page")
                        checkCameraPermissionAndStart()
                        // Sync camera page UI with current navigation state
                        syncCameraPageState()
                    }
                }
            }
        })
    }
    
    private fun updateDotIndicator(position: Int) {
        val activeColor = ContextCompat.getColor(this, R.color.primary)
        val inactiveColor = Color.parseColor("#55FFFFFF")
        
        if (position == PAGE_NAVIGATION) {
            dotNav?.setBackgroundColor(activeColor)
            dotCamera?.setBackgroundColor(inactiveColor)
        } else {
            dotNav?.setBackgroundColor(inactiveColor)
            dotCamera?.setBackgroundColor(activeColor)
        }
    }

    // =========================================================================
    // ViewPager2 Adapter (2 pages: navigation & camera)
    // =========================================================================

    private inner class NavigationPagerAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        
        override fun getItemCount(): Int = 2
        
        override fun getItemViewType(position: Int): Int = position
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                PAGE_NAVIGATION -> {
                    val view = inflater.inflate(R.layout.page_navigation, parent, false)
                    NavPageViewHolder(view)
                }
                else -> {
                    val view = inflater.inflate(R.layout.page_nav_camera, parent, false)
                    CamPageViewHolder(view)
                }
            }
        }
        
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is NavPageViewHolder -> bindNavPage(holder)
                is CamPageViewHolder -> bindCamPage(holder)
            }
        }
    }
    
    private inner class NavPageViewHolder(view: View) : RecyclerView.ViewHolder(view)
    private inner class CamPageViewHolder(view: View) : RecyclerView.ViewHolder(view)

    private fun bindNavPage(holder: NavPageViewHolder) {
        val v = holder.itemView
        
        // Bind all navigation page views
        navCurrentRoom = v.findViewById(R.id.tvCurrentRoom)
        navSpinnerDestination = v.findViewById(R.id.spinnerDestination)
        navStepCountingToggle = v.findViewById(R.id.llStepCountingToggle)
        navSwitchStepCounting = v.findViewById(R.id.switchStepCounting)
        navBtnStartNavigation = v.findViewById(R.id.btnStartNavigation)
        navCardStep = v.findViewById(R.id.cardStep)
        navTvStepProgress = v.findViewById(R.id.tvStepProgress)
        navTvStatus = v.findViewById(R.id.tvStatus)
        navTvInstruction = v.findViewById(R.id.tvInstruction)
        navTvCurrentHeading = v.findViewById(R.id.tvCurrentHeading)
        navTvTargetHeading = v.findViewById(R.id.tvTargetHeading)
        navIvCompassArrow = v.findViewById(R.id.ivCompassArrow)
        navProgressAlignment = v.findViewById(R.id.progressAlignment)
        navTvStepCounter = v.findViewById(R.id.tvStepCounter)
        navBtnConfirmStep = v.findViewById(R.id.btnConfirmStep)
        navBtnCountStep = v.findViewById(R.id.btnCountStep)
        navBtnStop = v.findViewById(R.id.btnStop)
        
        // Beacon views
        navLlBeacon1 = v.findViewById(R.id.llBeacon1)
        navLlBeacon2 = v.findViewById(R.id.llBeacon2)
        navLlBeacon3 = v.findViewById(R.id.llBeacon3)
        navTvBeacon1Name = v.findViewById(R.id.tvBeacon1Name)
        navTvBeacon1Rssi = v.findViewById(R.id.tvBeacon1Rssi)
        navViewBeacon1Indicator = v.findViewById(R.id.viewBeacon1Indicator)
        navTvBeacon2Name = v.findViewById(R.id.tvBeacon2Name)
        navTvBeacon2Rssi = v.findViewById(R.id.tvBeacon2Rssi)
        navViewBeacon2Indicator = v.findViewById(R.id.viewBeacon2Indicator)
        navTvBeacon3Name = v.findViewById(R.id.tvBeacon3Name)
        navTvBeacon3Rssi = v.findViewById(R.id.tvBeacon3Rssi)
        navViewBeacon3Indicator = v.findViewById(R.id.viewBeacon3Indicator)
        navTvNoBeacons = v.findViewById(R.id.tvNoBeacons)
        
        // Setup UI for navigation page
        setupNavPageUI()
    }
    
    private fun bindCamPage(holder: CamPageViewHolder) {
        val v = holder.itemView
        
        // Bind camera page views — navigation controls
        camPreviewView = v.findViewById(R.id.camPreviewView)
        camDetectionOverlay = v.findViewById(R.id.camDetectionOverlay)
        camTvCurrentRoom = v.findViewById(R.id.camTvCurrentRoom)
        camSpinnerDestination = v.findViewById(R.id.camSpinnerDestination)
        camLlStepCountingToggle = v.findViewById(R.id.camLlStepCountingToggle)
        camSwitchStepCounting = v.findViewById(R.id.camSwitchStepCounting)
        camBtnStartNavigation = v.findViewById(R.id.camBtnStartNavigation)
        camNavInfoPanel = v.findViewById(R.id.camNavInfoPanel)
        camTvStepProgress = v.findViewById(R.id.camTvStepProgress)
        camTvHeading = v.findViewById(R.id.camTvHeading)
        camTvTargetHeading = v.findViewById(R.id.camTvTargetHeading)
        camTvStatus = v.findViewById(R.id.camTvStatus)
        camProgressAlignment = v.findViewById(R.id.camProgressAlignment)
        camTvStepCounter = v.findViewById(R.id.camTvStepCounter)
        camTvObjectName = v.findViewById(R.id.camTvObjectName)
        camBtnAction = v.findViewById(R.id.camBtnAction)
        camBtnStop = v.findViewById(R.id.camBtnStop)
        camBtnDescribe = v.findViewById(R.id.camBtnDescribe)
        camProgressDescribe = v.findViewById(R.id.camProgressDescribe)
        
        // Setup camera page UI (spinner, toggle, buttons)
        setupCamPageUI()
    }

    // =========================================================================
    // Navigation Page UI Setup (same logic as before)
    // =========================================================================
    
    private fun setupNavPageUI() {
        setupDestinationSpinner()
        
        navBtnStartNavigation?.setOnClickListener {
            provideHapticFeedback()
            val selectedRoom = navSpinnerDestination?.selectedItem as? String
            if (selectedRoom != null) {
                startNavigation()
            } else {
                ttsManager.speak("Pilih tujuan terlebih dahulu")
            }
        }
        
        navBtnConfirmStep?.setOnClickListener {
            provideHapticFeedback()
            navigationManager.confirmStepCompleted()
        }
        
        navBtnCountStep?.setOnClickListener {
            provideHapticFeedback()
            navigationManager.onUserStep()
        }
        
        navSwitchStepCounting?.setOnCheckedChangeListener { _, isChecked ->
            if (isSyncingToggle) return@setOnCheckedChangeListener
            useStepCounting = isChecked
            navigationManager.useStepCounting = isChecked
            // Sync camera page toggle
            isSyncingToggle = true
            camSwitchStepCounting?.isChecked = isChecked
            isSyncingToggle = false
            if (isChecked) {
                ttsManager.speak("Mode hitung langkah diaktifkan. Tekan tombol setiap melangkah.")
            } else {
                ttsManager.speak("Mode hitung langkah dinonaktifkan.")
            }
        }
        
        navBtnStop?.setOnClickListener {
            provideHapticFeedback()
            stopNavigation()
        }
        
        updateUIState(false)
    }
    
    private fun setupCamPageUI() {
        // --- Destination Spinner (same data as nav page) ---
        setupCamDestinationSpinner()
        
        // --- Step Counting Toggle (bidirectional sync) ---
        camSwitchStepCounting?.isChecked = useStepCounting
        camSwitchStepCounting?.setOnCheckedChangeListener { _, isChecked ->
            if (isSyncingToggle) return@setOnCheckedChangeListener
            useStepCounting = isChecked
            navigationManager.useStepCounting = isChecked
            // Sync nav page toggle
            isSyncingToggle = true
            navSwitchStepCounting?.isChecked = isChecked
            isSyncingToggle = false
            if (isChecked) {
                ttsManager.speak("Mode hitung langkah diaktifkan. Tekan tombol setiap melangkah.")
            } else {
                ttsManager.speak("Mode hitung langkah dinonaktifkan.")
            }
        }
        
        // --- Start Navigation Button ---
        camBtnStartNavigation?.setOnClickListener {
            provideHapticFeedback()
            val selectedRoom = camSpinnerDestination?.selectedItem as? String
            if (selectedRoom != null) {
                startNavigation()
            } else {
                ttsManager.speak("Pilih tujuan terlebih dahulu")
            }
        }
        
        // --- Action Button (confirm step / count step) ---
        camBtnAction?.setOnClickListener {
            provideHapticFeedback()
            if (useStepCounting) {
                navigationManager.onUserStep()
            } else {
                navigationManager.confirmStepCompleted()
            }
        }
        
        // --- Stop Navigation Button ---
        camBtnStop?.setOnClickListener {
            provideHapticFeedback()
            stopNavigation()
        }
        
        // --- Describe Scene Button ---
        camBtnDescribe?.setOnClickListener {
            provideHapticFeedback()
            describeCurrentScene()
        }
        
        // Set initial state
        syncCameraPageState()
    }
    
    private fun setupCamDestinationSpinner() {
        val rooms = navigationManager.getAllRooms()
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, rooms) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                // TalkBack: set contentDescription hanya nama item
                view.contentDescription = getItem(position)
                return view
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent)
                val itemName = getItem(position) ?: ""
                view.contentDescription = itemName
                // Fix TalkBack: hapus role "radio button" dan status checked dari CheckedTextView
                ViewCompat.setAccessibilityDelegate(view, object : AccessibilityDelegateCompat() {
                    override fun onInitializeAccessibilityNodeInfo(
                        host: View,
                        info: AccessibilityNodeInfoCompat
                    ) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = "android.widget.TextView"  // ubah dari CheckedTextView → hapus "radio button"
                        info.roleDescription = ""        // hapus role tambahan
                        info.isCheckable = false         // hapus "checked/non check"
                        info.isChecked = false
                        info.contentDescription = itemName  // hanya nama ruangan
                    }
                })
                return view
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        camSpinnerDestination?.adapter = adapter

        // TalkBack fix: hilangkan "Dropdown" / "Tombol dropdown" dari output suara
        camSpinnerDestination?.let { spinner ->
            ViewCompat.setAccessibilityDelegate(spinner, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfoCompat
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    // Kosongkan roleDescription agar TalkBack tidak sebut "Dropdown"
                    info.roleDescription = ""
                }
            })
        }

        // Sync initial selection dengan nav page
        val navSelection = navSpinnerDestination?.selectedItemPosition ?: 0
        if (navSelection >= 0 && navSelection < rooms.size) {
            camSpinnerDestination?.setSelection(navSelection)
        }

        camSpinnerDestination?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isSyncingSpinner) return
                // Sync selection ke nav page spinner
                isSyncingSpinner = true
                navSpinnerDestination?.setSelection(position)
                isSyncingSpinner = false

                val selectedRoom = rooms[position]
                // Update contentDescription Spinner agar TalkBack hanya baca nama lokasi
                camSpinnerDestination?.contentDescription = selectedRoom

                if (!isNavigating) {
                    ttsManager.speak("Tujuan dipilih: $selectedRoom. Tekan tombol Mulai Navigasi untuk memulai.")
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Set initial contentDescription ke item pertama
        if (rooms.isNotEmpty()) {
            val initIdx = navSpinnerDestination?.selectedItemPosition ?: 0
            camSpinnerDestination?.contentDescription = rooms.getOrElse(initIdx) { rooms[0] }
        }
    }

    private fun setupDestinationSpinner() {
        val rooms = navigationManager.getAllRooms()
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, rooms) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                // TalkBack: set contentDescription hanya nama item, bukan label tambahan
                view.contentDescription = getItem(position)
                return view
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent)
                val itemName = getItem(position) ?: ""
                view.contentDescription = itemName
                // Fix TalkBack: hapus role "radio button" dan status checked dari CheckedTextView
                ViewCompat.setAccessibilityDelegate(view, object : AccessibilityDelegateCompat() {
                    override fun onInitializeAccessibilityNodeInfo(
                        host: View,
                        info: AccessibilityNodeInfoCompat
                    ) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = "android.widget.TextView"  // ubah dari CheckedTextView → hapus "radio button"
                        info.roleDescription = ""        // hapus role tambahan
                        info.isCheckable = false         // hapus "checked/non check"
                        info.isChecked = false
                        info.contentDescription = itemName  // hanya nama ruangan
                    }
                })
                return view
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        navSpinnerDestination?.adapter = adapter

        // TalkBack fix: hilangkan "Dropdown" / "Tombol dropdown" dari output suara
        // Gunakan ViewCompat roleDescription kosong agar TalkBack hanya baca contentDescription
        navSpinnerDestination?.let { spinner ->
            ViewCompat.setAccessibilityDelegate(spinner, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfoCompat
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    // Kosongkan roleDescription agar TalkBack tidak sebut "Dropdown"
                    info.roleDescription = ""
                    // contentDescription sudah berisi nama lokasi terpilih
                }
            })
        }

        navSpinnerDestination?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isSyncingSpinner) return
                // Sync selection ke camera page spinner
                isSyncingSpinner = true
                camSpinnerDestination?.setSelection(position)
                isSyncingSpinner = false

                val selectedRoom = rooms[position]
                // Update contentDescription Spinner agar TalkBack hanya baca nama lokasi
                navSpinnerDestination?.contentDescription = selectedRoom

                if (!isNavigating) {
                    ttsManager.speak("Tujuan dipilih: $selectedRoom. Tekan tombol Mulai Navigasi untuk memulai.")
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Set initial contentDescription ke item pertama
        if (rooms.isNotEmpty()) {
            navSpinnerDestination?.contentDescription = rooms[0]
        }
    }

    // =========================================================================
    // Camera AI Logic
    // =========================================================================
    
    private fun checkCameraPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE
            )
        }
    }
    
    private fun startCamera() {
        if (isCameraActive) return
        val preview = camPreviewView ?: return
        
        Log.d(TAG, "Starting camera for nav+camera page")
        
        // Initialize separate TTS for object detection (won't interrupt navigation TTS)
        if (cameraTtsManager == null) {
            cameraTtsManager = TTSManager(this)
        }
        
        // Initialize YOLO detector
        if (objectDetectorHelper == null) {
            objectDetectorHelper = YoloDetectorHelper(
                context = this,
                onDetectionResult = { detectionResults ->
                    runOnUiThread {
                        handleDetectionResult(detectionResults)
                    }
                },
                onError = { e ->
                    Log.e(TAG, "Detection error", e)
                    runOnUiThread {
                        camTvObjectName?.visibility = View.GONE
                    }
                }
            )
        }
        
        // Initialize Camera Manager
        cameraManager = CameraManager(
            context = this,
            lifecycleOwner = this,
            previewView = preview
        )
        
        cameraManager?.startCamera(
            onAnalyze = { imageProxy -> processImageProxy(imageProxy) },
            onError = { e ->
                Log.e(TAG, "Camera error", e)
                runOnUiThread {
                    ttsManager.speak("Gagal memulai kamera")
                }
            }
        )
        
        isCameraActive = true
        ttsManager.speak("Kamera AI aktif. Geser kanan untuk kembali ke navigasi.")
    }
    
    private fun stopCamera() {
        if (!isCameraActive) return
        Log.d(TAG, "Stopping camera")
        cameraManager?.shutdown()
        cameraManager = null
        isCameraActive = false
        camDetectionOverlay?.setImageBitmap(null)
        camTvObjectName?.visibility = View.GONE
    }
    
    private fun describeCurrentScene() {
        if (isProcessingDescription) return

        val helper = geminiHelper ?: return
        if (!helper.isConfigured()) {
            Toast.makeText(this, "API Key belum dikonfigurasi", Toast.LENGTH_LONG).show()
            ttsManager.speak("API Key belum dikonfigurasi")
            return
        }

        val bitmap = lastBitmap
        if (bitmap == null) {
            Toast.makeText(this, "Kamera belum siap", Toast.LENGTH_SHORT).show()
            return
        }

        isProcessingDescription = true
        camBtnDescribe?.isEnabled = false
        camBtnDescribe?.text = ""
        camProgressDescribe?.visibility = View.VISIBLE

        ttsManager.speak("Sedang memproses, mohon tunggu")

        // Inisialisasi ToneGenerator di class field — akan di-release di onDestroy
        if (descLoadingToneGenerator == null) {
            descLoadingToneGenerator = try {
                ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create describe ToneGenerator", e)
                null
            }
        }

        descLoadingSoundJob = lifecycleScope.launch(Dispatchers.IO) {
            delay(2000)
            while (isProcessingDescription) {
                descLoadingToneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                delay(300)
                descLoadingToneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                delay(300)
                descLoadingToneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                delay(2000)
            }
        }

        lifecycleScope.launch(Dispatchers.Main) {
            val description = helper.describeImage(bitmap)

            descLoadingSoundJob?.cancel()
            descLoadingSoundJob = null
            isProcessingDescription = false

            camProgressDescribe?.visibility = View.GONE
            camBtnDescribe?.isEnabled = true
            camBtnDescribe?.text = "Jelaskan"

            ttsManager.speak(description)

            camTvObjectName?.text = description
            camTvObjectName?.visibility = View.VISIBLE
        }
    }
    
    private fun processImageProxy(imageProxy: ImageProxy) {
        frameSkipCounter++
        if (frameSkipCounter < frameSkipInterval) {
            imageProxy.close()
            return
        }
        frameSkipCounter = 0
        
        val bitmap = imageProxyToBitmap(imageProxy)
        imageProxy.close()
        
        if (bitmap != null) {
            // Cache for Gemini description
            lastBitmap = bitmap

            analysisImageWidth = bitmap.width
            analysisImageHeight = bitmap.height
            objectDetectorHelper?.detectObjects(bitmap)
        }
    }
    
    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val image = imageProxy.image ?: return null
        
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = android.graphics.YuvImage(
            nv21,
            android.graphics.ImageFormat.NV21,
            image.width,
            image.height,
            null
        )

        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(
            android.graphics.Rect(0, 0, image.width, image.height),
            75,
            out
        )

        val imageBytes = out.toByteArray()
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        return if (rotationDegrees != 0) {
            val matrix = Matrix()
            matrix.postRotate(rotationDegrees.toFloat())
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }
    }
    
    private fun handleDetectionResult(detectionResults: List<YoloDetectorHelper.DetectionResult>) {
        if (detectionResults.isEmpty()) {
            camTvObjectName?.visibility = View.GONE
            camDetectionOverlay?.setImageBitmap(null)
            return
        }

        // Translate labels
        val translatedResults = detectionResults.map { det ->
            val indonesianLabel = objectDetectorHelper?.translateLabel(det.label) ?: det.label
            YoloDetectorHelper.DetectionResult(
                label = indonesianLabel,
                confidence = det.confidence,
                boundingBox = det.boundingBox
            )
        }

        drawBoundingBoxes(translatedResults)

        // Don't speak interruptions if we are describing the scene
        if (isProcessingDescription) return

        val bestLabelPair = objectDetectorHelper?.getBestLabel(detectionResults)
        
        if (bestLabelPair != null) {
            val (englishLabel, confidence) = bestLabelPair
            val indonesianLabel = objectDetectorHelper?.translateLabel(englishLabel) ?: englishLabel
            val confidencePercent = (confidence * 100).toInt()

            camTvObjectName?.text = "$indonesianLabel ($confidencePercent%)"
            camTvObjectName?.visibility = View.VISIBLE

            // Use speakDetection: won't interrupt if nav TTS or previous detection is speaking
            if (confidence >= 0.7f) {
                cameraTtsManager?.speakDetection(indonesianLabel)
            }
        } else {
            camTvObjectName?.visibility = View.GONE
        }
    }
    
    private fun drawBoundingBoxes(detections: List<YoloDetectorHelper.DetectionResult>) {
        val overlay = camDetectionOverlay ?: return
        val viewWidth = overlay.width
        val viewHeight = overlay.height

        if (viewWidth == 0 || viewHeight == 0) return

        val bitmap = Bitmap.createBitmap(viewWidth, viewHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val scale = maxOf(
            viewWidth.toFloat() / analysisImageWidth,
            viewHeight.toFloat() / analysisImageHeight
        )
        val scaledW = analysisImageWidth * scale
        val scaledH = analysisImageHeight * scale
        val offsetX = (viewWidth - scaledW) / 2f
        val offsetY = (viewHeight - scaledH) / 2f

        for ((index, detection) in detections.withIndex()) {
            val color = boxColors[index % boxColors.size]
            boxPaint.color = color
            labelBgPaint.color = color

            val bb = detection.boundingBox
            val rect = RectF(
                bb.left * scale + offsetX,
                bb.top * scale + offsetY,
                bb.right * scale + offsetX,
                bb.bottom * scale + offsetY
            )

            rect.left = rect.left.coerceIn(0f, viewWidth.toFloat())
            rect.top = rect.top.coerceIn(0f, viewHeight.toFloat())
            rect.right = rect.right.coerceIn(0f, viewWidth.toFloat())
            rect.bottom = rect.bottom.coerceIn(0f, viewHeight.toFloat())

            if (rect.width() < 2f || rect.height() < 2f) continue

            canvas.drawRoundRect(rect, 8f, 8f, boxPaint)

            val confidencePercent = (detection.confidence * 100).toInt()
            val labelText = "${detection.label} $confidencePercent%"
            val textWidth = textPaint.measureText(labelText)
            val textHeight = textPaint.textSize

            val labelLeft = rect.left
            val labelTop = (rect.top - textHeight - 12f).coerceAtLeast(0f)
            val labelRight = (labelLeft + textWidth + 16f).coerceAtMost(viewWidth.toFloat())
            val labelBottom = labelTop + textHeight + 12f

            canvas.drawRoundRect(labelLeft, labelTop, labelRight, labelBottom, 6f, 6f, labelBgPaint)
            canvas.drawText(labelText, labelLeft + 8f, labelBottom - 6f, textPaint)
        }

        overlay.setImageBitmap(bitmap)
    }

    // =========================================================================
    // Permissions
    // =========================================================================

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            startPassiveDetection()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    startPassiveDetection()
                    ttsManager.speak("Izin diberikan. Mendeteksi lokasi. Silakan pilih tujuan dan tekan Mulai Navigasi.")
                } else {
                    ttsManager.speak("Izin diperlukan untuk navigasi")
                }
            }
            CAMERA_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startCamera()
                } else {
                    ttsManager.speak("Izin kamera diperlukan untuk fitur Kamera AI")
                }
            }
        }
    }

    // =========================================================================
    // Navigation Control
    // =========================================================================

    private fun startPassiveDetection() {
        bleScanner.startScan()
        compassManager.start()
        navigationManager.startPassiveDetection()
        Log.d(TAG, "Passive BLE detection + compass started")
    }

    private fun startNavigation() {
        if (!compassManager.isCompassAvailable()) {
            ttsManager.speak("Kompas tidak tersedia di perangkat ini")
            navTvStatus?.text = "Kompas tidak tersedia"
            return
        }
        
        isNavigating = true
        navigationManager.start()
        updateUIState(true)
        syncCameraPageState()
        
        // Read destination from whichever page is currently active
        val selectedRoom = if (currentPage == PAGE_CAMERA) {
            camSpinnerDestination?.selectedItem as? String
        } else {
            navSpinnerDestination?.selectedItem as? String
        }
        if (selectedRoom != null) {
            navigationManager.setDestination(selectedRoom)
        }
        
        ttsManager.speak("Navigasi dimulai. Geser kiri untuk mode kamera.")
    }

    private fun stopNavigation() {
        isNavigating = false
        navigationManager.stopNavigation()
        stopTungLoop()
        vibrator?.cancel()
        lastFeedbackState = STATE_NONE
        updateUIState(false)
        syncCameraPageState()
        ttsManager.speak("Navigasi dihentikan. Deteksi lokasi tetap aktif.")
    }

    private fun updateUIState(navigating: Boolean) {
        // Navigation page UI
        navBtnStartNavigation?.visibility = if (navigating) View.GONE else View.VISIBLE
        navSpinnerDestination?.isEnabled = !navigating
        navStepCountingToggle?.visibility = if (navigating) View.GONE else View.VISIBLE
        navCardStep?.visibility = if (navigating) View.VISIBLE else View.GONE
        
        if (navigating) {
            if (useStepCounting) {
                navBtnCountStep?.visibility = View.VISIBLE
                navBtnConfirmStep?.visibility = View.GONE
                navTvStepCounter?.visibility = View.VISIBLE
                navTvStepCounter?.text = "Langkah: 0 / 0"
            } else {
                navBtnConfirmStep?.visibility = View.VISIBLE
                navBtnConfirmStep?.text = "LANJUT\nLangkah Berikutnya"
                navBtnConfirmStep?.isEnabled = true
                navBtnCountStep?.visibility = View.GONE
                navTvStepCounter?.visibility = View.GONE
            }
        } else {
            navBtnConfirmStep?.visibility = View.GONE
            navBtnCountStep?.visibility = View.GONE
            navTvStepCounter?.visibility = View.GONE
        }
    }
    
    /** Sync the camera page buttons/text with current navigation state */
    private fun syncCameraPageState() {
        if (isNavigating) {
            // Hide pre-navigation controls
            camSpinnerDestination?.isEnabled = false
            camLlStepCountingToggle?.visibility = View.GONE
            camBtnStartNavigation?.visibility = View.GONE
            
            // Show navigation info & action buttons
            camNavInfoPanel?.visibility = View.VISIBLE
            camBtnAction?.visibility = View.VISIBLE
            camBtnAction?.isEnabled = true
            camBtnStop?.visibility = View.VISIBLE
            
            if (useStepCounting) {
                camBtnAction?.text = "LANGKAH\nTekan setiap melangkah"
                camTvStepCounter?.visibility = View.VISIBLE
            } else {
                camBtnAction?.text = "LANJUT\nLangkah Berikutnya"
                camTvStepCounter?.visibility = View.GONE
            }
            
            camTvStepProgress?.text = navTvStepProgress?.text ?: "Navigasi aktif"
        } else {
            // Show pre-navigation controls
            camSpinnerDestination?.isEnabled = true
            camLlStepCountingToggle?.visibility = View.VISIBLE
            camBtnStartNavigation?.visibility = View.VISIBLE
            
            // Hide navigation info & action buttons
            camNavInfoPanel?.visibility = View.GONE
            camBtnAction?.visibility = View.GONE
            camBtnStop?.visibility = View.GONE
            camTvStepProgress?.text = "Navigasi belum dimulai"
            camTvStepCounter?.visibility = View.GONE
        }
    }

    // =========================================================================
    // CompassManager.Callback
    // =========================================================================
    
    override fun onHeadingChanged(heading: Float) {
        runOnUiThread {
            val directionName = compassManager.getDirectionName(heading)
            val headingText = "${heading.roundToInt()}° ($directionName)"
            
            // Update both pages
            navTvCurrentHeading?.text = headingText
            navIvCompassArrow?.rotation = -heading
            camTvHeading?.text = headingText
        }
        
        navigationManager.onHeadingChanged(heading)
    }

    // =========================================================================
    // IndoorNavigationManager.Callback
    // =========================================================================
    
    override fun onRoomChanged(roomName: String) {
        runOnUiThread {
            navCurrentRoom?.text = "Lokasi: $roomName"
            // Update contentDescription agar TalkBack membaca nama lokasi yang benar
            navCurrentRoom?.contentDescription = "Lokasi saat ini: $roomName"
            camTvCurrentRoom?.text = "Lokasi: $roomName"
        }
    }

    override fun onDestinationReached(roomName: String) {
        runOnUiThread {
            navTvStatus?.text = "✓ Sampai di $roomName!"
            navTvStatus?.setTextColor(0xFF81C784.toInt())  // hijau terang di atas glass gelap
            navBtnConfirmStep?.text = "SAMPAI!\n$roomName"
            navBtnConfirmStep?.isEnabled = false
            
            // Camera page
            camTvStatus?.text = "✓ Sampai di $roomName!"
        }
    }

    override fun onHeadingUpdated(currentHeading: Float, targetHeading: Float) {
        runOnUiThread {
            if (targetHeading >= 0) {
                val targetDir = compassManager.getDirectionName(targetHeading)
                val targetText = "Target: ${targetHeading.roundToInt()}° ($targetDir)"
                
                navTvTargetHeading?.text = targetText
                navTvTargetHeading?.visibility = View.VISIBLE
                
                camTvTargetHeading?.text = "→ ${targetHeading.roundToInt()}° ($targetDir)"
                camTvTargetHeading?.visibility = View.VISIBLE
                
                val diff = compassManager.calculateHeadingDiff(currentHeading, targetHeading)
                updateAlignmentIndicator(diff)
                provideDirectionFeedback(diff)
            } else {
                navTvTargetHeading?.visibility = View.GONE
                camTvTargetHeading?.visibility = View.GONE
                lastFeedbackState = STATE_NONE
            }
        }
    }

    override fun onStepChanged(stepNum: Int, totalSteps: Int, targetHeading: Float, instruction: String) {
        runOnUiThread {
            val stepText = "Langkah $stepNum dari $totalSteps"
            val targetDir = compassManager.getDirectionName(targetHeading)
            val instrText = "$instruction\nTarget: ${targetHeading.roundToInt()}° ($targetDir)"
            val targetText = "→ ${targetHeading.roundToInt()}° ($targetDir)"
            
            // Navigation page
            navTvStepProgress?.text = stepText
            navTvInstruction?.text = instrText
            navTvTargetHeading?.text = targetText
            navTvTargetHeading?.visibility = View.VISIBLE
            
            // Camera page
            camTvStepProgress?.text = stepText
            camTvTargetHeading?.text = targetText
            camTvTargetHeading?.visibility = View.VISIBLE
            
            val isLastStep = stepNum == totalSteps
            if (useStepCounting) {
                navBtnCountStep?.isEnabled = true
                navBtnCountStep?.text = "LANGKAH\nTekan setiap melangkah"
                navTvStepCounter?.visibility = View.VISIBLE
                
                camBtnAction?.isEnabled = true
                camBtnAction?.text = "LANGKAH\nTekan setiap melangkah"
                camTvStepCounter?.visibility = View.VISIBLE
            } else {
                if (isLastStep) {
                    navBtnConfirmStep?.text = "SAMPAI DI TUJUAN\nTekan setelah tiba"
                    camBtnAction?.text = "SAMPAI DI TUJUAN\nTekan setelah tiba"
                } else {
                    navBtnConfirmStep?.text = "LANJUT\nLangkah ${stepNum + 1}"
                    camBtnAction?.text = "LANJUT\nLangkah ${stepNum + 1}"
                }
            }
            
            navBtnConfirmStep?.isEnabled = true
            camBtnAction?.isEnabled = true
        }
    }

    override fun onAlignmentGuidance(guidance: String, degreesRemaining: Float) {
        runOnUiThread {
            navTvStatus?.text = guidance
            navTvStatus?.setTextColor(0xFFFFCC80.toInt())  // oranye terang di atas glass gelap
            
            camTvStatus?.text = guidance
        }
    }

    override fun onAlignmentComplete(instruction: String) {
        runOnUiThread {
            navTvStatus?.text = "✓ Arah sudah benar"
            navTvStatus?.setTextColor(0xFF81C784.toInt())  // hijau terang di atas glass gelap
            navBtnConfirmStep?.isEnabled = true
            camTvStatus?.text = "✓ Arah sudah benar"

            // Set callback untuk mulai tung SETELAH TTS selesai
            // Callback berjalan di background thread, jadi harus runOnUiThread + cek state
            if (!isTungLoopActive) {
                ttsManager.setOneShotOnDone {
                    runOnUiThread {
                        // Hanya start tung jika arah MASIH benar saat TTS selesai
                        if (lastFeedbackState == STATE_EXACT) {
                            startTungLoop()
                        }
                    }
                }
            }
        }
    }

    override fun onNavigationComplete() {
        runOnUiThread {
            navTvStatus?.text = "✓ Navigasi selesai!"
            navTvStatus?.setTextColor(0xFF81C784.toInt())  // hijau terang di atas glass gelap
            navBtnConfirmStep?.isEnabled = false
            navBtnConfirmStep?.text = "SELESAI"
            navBtnCountStep?.isEnabled = false
            navBtnCountStep?.text = "SELESAI"
            
            camTvStatus?.text = "✓ Navigasi selesai!"
            camBtnAction?.isEnabled = false
            camBtnAction?.text = "SELESAI"
            
            provideHapticFeedback()
            provideHapticFeedback()
            
            // Auto stop navigation after 3 seconds to let TTS finish
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (!isFinishing && !isDestroyed) {
                    stopNavigation()
                }
            }, 3000L)
        }
    }

    override fun onStepCountUpdated(currentStep: Int, targetStep: Int) {
        runOnUiThread {
            val remaining = (targetStep - currentStep).coerceAtLeast(0)
            val counterText = "Langkah: $currentStep / $targetStep (sisa $remaining)"
            
            // Navigation page
            navTvStepCounter?.text = counterText
            if (remaining > 0) {
                navBtnCountStep?.text = "LANGKAH\n$currentStep / $targetStep"
            } else {
                navBtnCountStep?.text = "SELESAI!\nLanjut otomatis..."
                navBtnCountStep?.isEnabled = false
            }
            
            // Camera page
            camTvStepCounter?.text = counterText
            if (remaining > 0) {
                camBtnAction?.text = "LANGKAH\n$currentStep / $targetStep"
            } else {
                camBtnAction?.text = "SELESAI!\nLanjut otomatis..."
                camBtnAction?.isEnabled = false
            }
            
            val progressPercent = if (targetStep > 0) currentStep * 100 / targetStep else 0
            val color = when {
                progressPercent >= 100 -> R.color.status_success
                progressPercent >= 75 -> R.color.status_warning
                else -> R.color.primary
            }
            val colorValue = ContextCompat.getColor(this, color)
            navTvStepCounter?.setTextColor(colorValue)
            camTvStepCounter?.setTextColor(colorValue)
        }
    }

    // =========================================================================
    // Alignment & Feedback
    // =========================================================================

    private fun updateAlignmentIndicator(diff: Float) {
        val absDiff = abs(diff)
        val progress = ((180 - absDiff) / 180 * 100).toInt().coerceIn(0, 100)
        
        navProgressAlignment?.progress = progress
        camProgressAlignment?.progress = progress
        
        val color = when {
            absDiff <= CompassManager.HEADING_TOLERANCE -> R.color.status_success
            absDiff <= 45 -> R.color.status_warning
            else -> R.color.status_error
        }
        val tintList = ContextCompat.getColorStateList(this, color)
        navProgressAlignment?.progressTintList = tintList
        camProgressAlignment?.progressTintList = tintList
    }
    
    // Handler-based loop "tung tung tung" — reliable di main thread untuk ToneGenerator
    private val tungHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var isTungLoopActive = false
    private var tungBeat = 0

    private fun provideDirectionFeedback(diff: Float) {
        val absDiff = abs(diff)
        val now = System.currentTimeMillis()

        when {
            // ✅ Arah TEPAT — hentikan getaran, tung mulai dari onAlignmentComplete setelah TTS done
            absDiff <= EXACT_TOLERANCE -> {
                if (lastFeedbackState != STATE_EXACT) {
                    vibrator?.cancel()
                    lastFeedbackState = STATE_EXACT
                    lastFeedbackTime = now
                }
            }

            // ⚡ Zona NETRAL — pastikan tung berhenti, tidak ada getaran
            absDiff <= NEUTRAL_TOLERANCE -> {
                if (isTungLoopActive) stopTungLoop()   // hentikan tung jika masih aktif
                lastFeedbackState = STATE_NEUTRAL
            }

            // ❌ Arah SALAH — pastikan tung berhenti, getaran tiap interval
            else -> {
                if (isTungLoopActive) stopTungLoop()   // hentikan tung jika masih aktif
                lastFeedbackState = STATE_OFF
                if (now - lastFeedbackTime >= VIBRATE_INTERVAL_MS) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val timings = longArrayOf(0, 300, 100, 100)
                            val amplitudes = intArrayOf(0, VibrationEffect.DEFAULT_AMPLITUDE, 0, VibrationEffect.DEFAULT_AMPLITUDE)
                            vibrator?.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                        } else {
                            @Suppress("DEPRECATION")
                            vibrator?.vibrate(longArrayOf(0, 300, 100, 100), -1)
                        }
                    } catch (e: Exception) {
                        Log.e("DirectionFeedback", "Vibrate failed", e)
                    }
                    lastFeedbackTime = now
                }
            }
        }
    }

    /** Loop "tung tung tung" menggunakan single self-referencing Runnable — paling reliable */
    private val tungRunnable: Runnable = object : Runnable {
        override fun run() {
            if (!isTungLoopActive) return
            toneGenerator?.startTone(ToneGenerator.TONE_DTMF_0, 160)
            tungBeat = (tungBeat + 1) % 3
            val nextDelay = if (tungBeat == 0) 1000L else 420L
            tungHandler.postDelayed(this, nextDelay)  // 'this' = Runnable ini sendiri
        }
    }

    /** Mulai loop "tung" — dipanggil setelah TTS "Arah Sudah Benar" selesai */
    private fun startTungLoop() {
        if (isTungLoopActive) return
        isTungLoopActive = true
        tungBeat = 0
        // Pause mic voice command agar tidak mengganggu suara tung
        voiceCommandManager?.pauseForTTS()
        tungHandler.post(tungRunnable)
    }

    /** Hentikan loop "tung" */
    private fun stopTungLoop() {
        isTungLoopActive = false
        tungHandler.removeCallbacks(tungRunnable)
        toneGenerator?.stopTone()
        // Resume mic voice command setelah tung berhenti
        voiceCommandManager?.resumeAfterTTS()
    }


    
    private fun provideHapticFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(100)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Haptic feedback failed", e)
        }
    }

    // =========================================================================
    // Beacon Detection
    // =========================================================================

    override fun onBeaconDetected(beaconId: String, uuid: String, major: Int, minor: Int, rssi: Int, txPower: Int) {
        val actualRoomName = getRoomNameForBeacon(major, minor)
        detectedBeacons[beaconId] = BeaconSignal(actualRoomName, rssi, major, minor)
        navigationManager.onBeaconDetected(major, minor, rssi)
        
        runOnUiThread {
            updateBeaconSignalUI()
        }
    }
    
    private fun getRoomNameForBeacon(major: Int, minor: Int): String {
        return BeaconRoomMapper.getDisplayNameForBeacon(major, minor)
    }

    override fun onBeaconLost(beaconId: String) {
        detectedBeacons.remove(beaconId)
        runOnUiThread {
            updateBeaconSignalUI()
        }
    }

    override fun onScanError(error: String) {
        runOnUiThread {
            navTvNoBeacons?.text = "Error: $error"
            navTvNoBeacons?.visibility = View.VISIBLE
        }
    }
    
    private fun updateBeaconSignalUI() {
        val sortedBeacons = detectedBeacons.values.sortedByDescending { it.rssi }.take(3)
        
        navLlBeacon1?.visibility = View.GONE
        navLlBeacon2?.visibility = View.GONE
        navLlBeacon3?.visibility = View.GONE
        
        if (sortedBeacons.isEmpty()) {
            navTvNoBeacons?.visibility = View.VISIBLE
            navTvNoBeacons?.text = "Mencari beacon..."
        } else {
            navTvNoBeacons?.visibility = View.GONE
            
            sortedBeacons.forEachIndexed { index, beacon ->
                when (index) {
                    0 -> {
                        navLlBeacon1?.visibility = View.VISIBLE
                        navTvBeacon1Name?.text = beacon.name
                        navTvBeacon1Rssi?.text = "${beacon.rssi} dBm"
                        navViewBeacon1Indicator?.let { updateIndicatorColor(it, beacon.rssi) }
                    }
                    1 -> {
                        navLlBeacon2?.visibility = View.VISIBLE
                        navTvBeacon2Name?.text = beacon.name
                        navTvBeacon2Rssi?.text = "${beacon.rssi} dBm"
                        navViewBeacon2Indicator?.let { updateIndicatorColor(it, beacon.rssi) }
                    }
                    2 -> {
                        navLlBeacon3?.visibility = View.VISIBLE
                        navTvBeacon3Name?.text = beacon.name
                        navTvBeacon3Rssi?.text = "${beacon.rssi} dBm"
                        navViewBeacon3Indicator?.let { updateIndicatorColor(it, beacon.rssi) }
                    }
                }
            }
        }
    }
    
    private fun updateIndicatorColor(view: View, rssi: Int) {
        val color = when {
            rssi > -60 -> ContextCompat.getColor(this, R.color.status_success)
            rssi > -80 -> ContextCompat.getColor(this, R.color.status_warning)
            else -> ContextCompat.getColor(this, R.color.status_error)
        }
        val drawable = view.background as? GradientDrawable
        drawable?.setColor(color)
    }

    // =========================================================================
    // Voice Command
    // =========================================================================

    private var listeningDialog: ListeningDialogHelper? = null

    private fun initVoiceCommand() {
        geminiCommandProcessor = GeminiCommandProcessor(lifecycleScope)
        listeningDialog = ListeningDialogHelper(this)

        // Wire TTS ↔ wake word: pause mic when TTS speaks, resume when done
        ttsManager.setWakeWordCallbacks(
            onStart = { voiceCommandManager?.pauseForTTS() },
            onFinish = { voiceCommandManager?.resumeAfterTTS() }
        )

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
                        (fabMic as? AndroidImageButton)?.setImageResource(R.drawable.ic_mic_active)
                        (fabMic as? FloatingActionButton)?.setImageResource(R.drawable.ic_mic_active)
                        fabMic?.startAnimation(pulseAnimation)
                        listeningDialog?.show(
                            statusText = "Mendengarkan...",
                            hintText = "Ucapkan perintah Anda"
                        )
                    }
                }

                override fun onListeningStarted() {
                    runOnUiThread {
                        (fabMic as? AndroidImageButton)?.setImageResource(R.drawable.ic_mic_active)
                        (fabMic as? FloatingActionButton)?.setImageResource(R.drawable.ic_mic_active)
                        fabMic?.startAnimation(pulseAnimation)
                    }
                }

                override fun onListeningStopped() {
                    runOnUiThread {
                        fabMic?.clearAnimation()
                        (fabMic as? AndroidImageButton)?.setImageResource(R.drawable.ic_mic)
                        (fabMic as? FloatingActionButton)?.setImageResource(R.drawable.ic_mic)
                        listeningDialog?.dismiss()
                    }
                }

                override fun onError(message: String) {
                    Log.e(TAG, "Voice error: $message")
                    runOnUiThread {
                        ttsManager.speak(message)
                    }
                }
            }
        )

        voiceCommandManager?.setAllowedIntents(setOf(
            VoiceIntent.CHECK_LOCATION,
            VoiceIntent.SELECT_DESTINATION,
            VoiceIntent.START_NAVIGATION,
            VoiceIntent.STOP_NAVIGATION,
            VoiceIntent.DESCRIBE_SCENE,
            VoiceIntent.GO_BACK,
            VoiceIntent.HELP
        ))
    }

    private fun handleVoiceIntent(intent: VoiceIntent, spokenText: String = "") {
        provideHapticFeedback()
        when (intent) {
            VoiceIntent.CHECK_LOCATION -> {
                handleCheckLocation()
            }
            VoiceIntent.SELECT_DESTINATION -> {
                handleSelectDestination(spokenText)
            }
            VoiceIntent.START_NAVIGATION -> {
                if (!isNavigating) startNavigation()
                else ttsManager.speak("Navigasi sudah berjalan")
            }
            VoiceIntent.STOP_NAVIGATION -> {
                if (isNavigating) stopNavigation()
                else ttsManager.speak("Navigasi belum dimulai")
            }
            VoiceIntent.DESCRIBE_SCENE -> {
                if (currentPage == PAGE_CAMERA && isCameraActive) {
                    describeCurrentScene()
                } else {
                    ttsManager.speak("Geser ke kiri untuk membuka kamera terlebih dahulu")
                }
            }
            VoiceIntent.GO_BACK -> {
                ttsManager.speak("Kembali")
                finish()
            }
            VoiceIntent.HELP -> {
                ttsManager.speak("Ucapkan Hello Orion diikuti perintah. " +
                        "Perintah yang tersedia: " +
                        "Di mana saya, untuk cek lokasi saat ini. " +
                        "Saya ingin ke, diikuti nama tujuan untuk memilih tujuan. " +
                        "Mulai navigasi, untuk memulai panduan. " +
                        "Berhenti, untuk menghentikan navigasi. " +
                        "Jelaskan, untuk deskripsi kamera. " +
                        "Kembali, untuk keluar.")
            }
            VoiceIntent.UNKNOWN -> {
                if (spokenText.isBlank()) {
                    ttsManager.speak("Maaf, suara tidak terdengar jelas. Silakan ucapkan Hello Orion dan coba lagi.")
                } else {
                    ttsManager.speak("Perintah \"$spokenText\" tidak dikenali. Katakan bantuan untuk daftar perintah.")
                }
            }
            else -> {
                ttsManager.speak("Perintah tidak tersedia di halaman ini.")
            }
        }
    }

    private fun handleCheckLocation() {
        if (detectedBeacons.isEmpty()) {
            ttsManager.speak("Lokasi belum ditemukan, mohon untuk mendekat ke beacon terdekat")
            return
        }
        // Get the beacon with strongest signal (highest RSSI)
        val strongest = detectedBeacons.values.maxByOrNull { it.rssi }
        if (strongest != null) {
            ttsManager.speak("Anda saat ini berada di dekat ${strongest.name}")
        } else {
            ttsManager.speak("Lokasi belum ditemukan, mohon untuk mendekat ke beacon terdekat")
        }
    }

    private fun handleSelectDestination(spokenText: String) {
        if (isNavigating) {
            ttsManager.speak("Hentikan navigasi terlebih dahulu sebelum memilih tujuan baru")
            return
        }

        // Extract destination name by removing command prefixes
        val prefixes = listOf(
            "saya ingin ke ", "saya mau ke ", "tujuan ke ",
            "pergi ke ", "arahkan ke ", "navigasi ke ",
            "antar ke ", "bawa ke ", "ke "
        )
        var destinationKeyword = spokenText.lowercase().trim()
        for (prefix in prefixes) {
            if (destinationKeyword.contains(prefix)) {
                destinationKeyword = destinationKeyword.substringAfter(prefix).trim()
                break
            }
        }

        if (destinationKeyword.isEmpty()) {
            ttsManager.speak("Silakan sebutkan nama tujuan. Contoh: saya ingin ke kantin.")
            return
        }

        // Use GraphData fuzzy matching
        val matchedNode = GraphData.findDestinationByKeyword(destinationKeyword)
        if (matchedNode != null) {
            // Find the position in the spinner and set it
            val rooms = navigationManager.getAllRooms()
            val position = rooms.indexOfFirst { it.equals(matchedNode.nameSpoken, ignoreCase = true) }
            if (position >= 0) {
                runOnUiThread {
                    isSyncingSpinner = true
                    navSpinnerDestination?.setSelection(position)
                    camSpinnerDestination?.setSelection(position)
                    isSyncingSpinner = false
                }
                ttsManager.speak("Tujuan dipilih: ${matchedNode.nameSpoken}. Katakan Mulai Navigasi untuk memulai.")
            } else {
                ttsManager.speak("Tujuan ${matchedNode.nameSpoken} tidak ditemukan di daftar. Silakan coba lagi.")
            }
        } else {
            ttsManager.speak("Tujuan tidak ditemukan. Silakan coba lagi dengan nama ruangan yang benar.")
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
            btnWakeWord?.setImageResource(R.drawable.ic_wakeword_off)
            ttsManager.speak("Hello Orion dinonaktifkan")
        } else {
            isWakeWordEnabled = true
            voiceCommandManager?.startWakeWordMode()
            btnWakeWord?.setImageResource(R.drawable.ic_wakeword)
            ttsManager.speak("Hello Orion diaktifkan. Ucapkan Hello Orion diikuti perintah.")
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

    private fun updateWakeWordIcon() {
        val isActive = voiceCommandManager?.isWakeWordActive() == true
        btnWakeWord?.setImageResource(
            if (isActive) R.drawable.ic_wakeword else R.drawable.ic_wakeword_off
        )
    }
}
