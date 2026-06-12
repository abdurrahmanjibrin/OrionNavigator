package com.orion.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.orion.app.R
import com.orion.app.camera.CameraManager
import com.orion.app.databinding.ActivityCameraAiBinding
import com.orion.app.gemini.GeminiHelper
import com.orion.app.ml.YoloDetectorHelper
import com.orion.app.tts.TTSManager
import com.orion.app.utils.PermissionHelper
import com.orion.app.voice.GeminiCommandProcessor
import com.orion.app.voice.ListeningDialogHelper
import com.orion.app.voice.VoiceCommandManager
import com.orion.app.voice.VoiceIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Camera AI Activity - Real-time Object Detection
 * 
 * Features:
 * - Real-time camera preview with CameraX
 * - Object detection using YOLOv8s TensorFlow Lite
 * - Bounding boxes drawn as Bitmap overlay on ImageView
 * - Text-to-Speech announcements in Indonesian (70%+ confidence only)
 * - Full TalkBack accessibility support
 */
@ExperimentalGetImage
class CameraAIActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraAiBinding
    private lateinit var permissionHelper: PermissionHelper
    private var cameraManager: CameraManager? = null
    private var objectDetectorHelper: YoloDetectorHelper? = null
    private var ttsManager: TTSManager? = null

    // Gemini AI Helper
    private var geminiHelper: GeminiHelper? = null
    private var lastBitmap: Bitmap? = null
    private var isProcessingDescription = false
    private var isShowingDescription = false

    // Frame skip counter for performance optimization
    private var frameSkipCounter = 0
    private val frameSkipInterval = 5 // Process every 5th frame for better performance
    // Guard volatile: set true di onDestroy agar processImageProxy() tidak lanjut saat shutdown
    @Volatile private var isActivityDestroyed = false

    // Track analysis image dimensions for bounding box scaling
    private var analysisImageWidth = 1
    private var analysisImageHeight = 1

    // Bounding box drawing paints
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
        Color.parseColor("#FF4CAF50"),  // Green
        Color.parseColor("#FF2196F3"),  // Blue
        Color.parseColor("#FFFF9800"),  // Orange
        Color.parseColor("#FFE91E63"),  // Pink
        Color.parseColor("#FF9C27B0"),  // Purple
        Color.parseColor("#FF00BCD4"),  // Cyan
    )

    // Voice Command
    private var voiceCommandManager: VoiceCommandManager? = null
    private var geminiCommandProcessor: GeminiCommandProcessor? = null
    private var pulseAnimation: Animation? = null
    private var toneGenerator: ToneGenerator? = null
    private var loadingToneGenerator: ToneGenerator? = null  // untuk suara loading Gemini
    private var loadingSoundJob: kotlinx.coroutines.Job? = null  // harus di-cancel saat destroy
    private var listeningDialog: ListeningDialogHelper? = null
    private var isWakeWordEnabled = false

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            voiceCommandManager?.startWakeWordMode()
        } else {
            ttsManager?.speak("Izin mikrofon diperlukan untuk perintah suara")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityCameraAiBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initializeComponents()
        setupClickListeners()
        initVoiceCommand()
        checkPermissionAndStart()
        toneGenerator = try { ToneGenerator(AudioManager.STREAM_MUSIC, 100) } catch (e: Exception) { null }
        loadingToneGenerator = try { ToneGenerator(AudioManager.STREAM_MUSIC, 80) } catch (e: Exception) { null }
    }

    private fun initializeComponents() {
        // Initialize TTS Manager
        ttsManager = TTSManager(this)

        // Initialize Gemini Helper
        geminiHelper = GeminiHelper()

        // Initialize Permission Helper
        permissionHelper = PermissionHelper(this) { isGranted ->
            if (isGranted) {
                showCameraView()
                startCameraWithDetection()
            } else {
                showPermissionDeniedView()
            }
        }
    }

    private fun setupClickListeners() {
        // Back button
        binding.fabBack.setOnClickListener {
            finish()
        }
        
        // Sound toggle button
        binding.fabSound.setOnClickListener {
            val isEnabled = ttsManager?.toggleSound() ?: false
            updateSoundButtonIcon(isEnabled)
            
            val message = if (isEnabled) {
                getString(R.string.sound_on)
            } else {
                getString(R.string.sound_off)
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }

        // Describe Scene button
        binding.fabDescribe.setOnClickListener {
            describeCurrentScene()
        }

        // Permission request button
        binding.btnRequestPermission.setOnClickListener {
            permissionHelper.requestCameraPermission()
        }
    }

    private fun describeCurrentScene() {
        if (isProcessingDescription) return

        val helper = geminiHelper ?: return
        if (!helper.isConfigured()) {
            Toast.makeText(this, "API Key belum dikonfigurasi", Toast.LENGTH_LONG).show()
            ttsManager?.speak("API Key belum dikonfigurasi")
            return
        }

        val bitmap = lastBitmap
        if (bitmap == null) {
            Toast.makeText(this, "Kamera belum siap", Toast.LENGTH_SHORT).show()
            return
        }

        isProcessingDescription = true
        isShowingDescription = true
        binding.fabDescribe.isEnabled = false
        binding.fabDescribe.text = ""
        binding.progressBar.visibility = View.VISIBLE

        ttsManager?.speak("Sedang memproses, mohon tunggu")

        // Gunakan loadingToneGenerator yang sudah diinisialisasi di onCreate (bukan buat baru)
        loadingSoundJob?.cancel()  // batalkan job sebelumnya jika masih ada
        loadingSoundJob = lifecycleScope.launch(Dispatchers.IO) {
            delay(2000)
            while (isProcessingDescription) {
                loadingToneGenerator?.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150)
                delay(300)
                loadingToneGenerator?.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150)
                delay(300)
                loadingToneGenerator?.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150)
                delay(2000)
            }
        }

        lifecycleScope.launch(Dispatchers.Main) {
            val description = helper.describeImage(bitmap)

            loadingSoundJob?.cancel()
            isProcessingDescription = false
            binding.progressBar.visibility = View.GONE
            binding.fabDescribe.isEnabled = true
            binding.fabDescribe.text = "Jelaskan"

            // Keep the description text visible until TTS finishes
            ttsManager?.setOneShotOnDone {
                lifecycleScope.launch(Dispatchers.Main) {
                    isShowingDescription = false
                }
            }
            ttsManager?.speak(description)

            binding.tvStatus.text = description
            binding.tvStatus.visibility = View.VISIBLE

            // Fallback timeout in case TTS is muted or fails to trigger callback
            delay(15000)
            isShowingDescription = false
        }
    }

    private fun checkPermissionAndStart() {
        if (permissionHelper.hasCameraPermission()) {
            showCameraView()
            startCameraWithDetection()
        } else {
            permissionHelper.requestCameraPermission()
        }
    }

    private fun startCameraWithDetection() {
        // Initialize YOLOv8 Object Detector
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
                    binding.tvStatus.text = getString(R.string.status_no_object)
                    binding.tvObjectName.visibility = View.GONE
                }
            }
        )

        // Initialize Camera Manager
        cameraManager = CameraManager(
            context = this,
            lifecycleOwner = this,
            previewView = binding.previewView
        )

        // Start camera with image analysis
        cameraManager?.startCamera(
            onAnalyze = { imageProxy ->
                processImageProxy(imageProxy)
            },
            onError = { e ->
                Log.e(TAG, "Camera error", e)
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.camera_error), Toast.LENGTH_LONG).show()
                }
            }
        )
        
        // Announce camera activation for accessibility
        ttsManager?.speak(getString(R.string.camera_ai_active))
    }

    private fun processImageProxy(imageProxy: ImageProxy) {
        // Guard: jika activity sedang destroy, skip frame ini — mencegah SIGSEGV di TFLite JNI
        if (isActivityDestroyed) {
            imageProxy.close()
            return
        }
        // Skip frames for performance
        frameSkipCounter++
        if (frameSkipCounter < frameSkipInterval) {
            imageProxy.close()
            return
        }
        frameSkipCounter = 0

        // Convert ImageProxy to Bitmap
        val bitmap = imageProxyToBitmap(imageProxy)
        imageProxy.close()

        if (bitmap != null) {
            // Cache for Gemini description
            lastBitmap = bitmap

            // Track image dimensions for overlay scaling
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
        
        // Rotate bitmap based on image rotation
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
        Log.d(TAG, "handleDetectionResult called with ${detectionResults.size} results")
        
        if (detectionResults.isEmpty()) {
            binding.tvStatus.text = getString(R.string.status_detecting)
            binding.tvObjectName.visibility = View.GONE
            binding.ivDetectionOverlay.setImageBitmap(null)
            return
        }

        // Translate labels for display
        val translatedResults = detectionResults.map { det ->
            val indonesianLabel = objectDetectorHelper?.translateLabel(det.label) ?: det.label
            YoloDetectorHelper.DetectionResult(
                label = indonesianLabel,
                confidence = det.confidence,
                boundingBox = det.boundingBox
            )
        }

        // Draw bounding boxes on a Bitmap and set on ImageView
        drawBoundingBoxes(translatedResults)

        // Don't speak interruptions or overwrite UI text if we are describing the scene
        if (isProcessingDescription || isShowingDescription) return

        // Get the best label (highest confidence)
        val bestLabelPair = objectDetectorHelper?.getBestLabel(detectionResults)
        
        if (bestLabelPair != null) {
            val (englishLabel, confidence) = bestLabelPair
            val indonesianLabel = objectDetectorHelper?.translateLabel(englishLabel) ?: englishLabel
            val confidencePercent = (confidence * 100).toInt()

            Log.d(TAG, "Best detection: $englishLabel -> $indonesianLabel ($confidencePercent%)")

            // Update UI text
            binding.tvObjectName.text = "$indonesianLabel ($confidencePercent%)"
            binding.tvObjectName.visibility = View.VISIBLE
            binding.tvStatus.text = getString(R.string.status_detecting)

            // Speak only if confidence >= 0.7f (uses speakDetection to not interrupt)
            if (confidence >= 0.7f) {
                ttsManager?.speakDetection(indonesianLabel)
                Log.d(TAG, "TTS announced: $indonesianLabel (confidence: $confidencePercent%)")
            } else {
                Log.d(TAG, "Skipped TTS - confidence too low: $confidencePercent%")
            }
        } else {
            binding.tvStatus.text = getString(R.string.status_detecting)
            binding.tvObjectName.visibility = View.GONE
        }
    }

    /**
     * Draw bounding boxes on a transparent Bitmap and display it on the ImageView overlay.
     * This approach is bulletproof — no z-ordering issues with CameraX.
     */
    private fun drawBoundingBoxes(detections: List<YoloDetectorHelper.DetectionResult>) {
        val overlay = binding.ivDetectionOverlay
        val viewWidth = overlay.width
        val viewHeight = overlay.height

        if (viewWidth == 0 || viewHeight == 0) {
            Log.w(TAG, "Overlay view has zero size: ${viewWidth}x${viewHeight}")
            return
        }

        // Create a transparent bitmap the same size as the overlay ImageView
        val bitmap = Bitmap.createBitmap(viewWidth, viewHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // fillCenter scaling to match PreviewView
        val scale = maxOf(
            viewWidth.toFloat() / analysisImageWidth,
            viewHeight.toFloat() / analysisImageHeight
        )
        val scaledW = analysisImageWidth * scale
        val scaledH = analysisImageHeight * scale
        val offsetX = (viewWidth - scaledW) / 2f
        val offsetY = (viewHeight - scaledH) / 2f

        Log.d(TAG, "drawBoundingBoxes: view=${viewWidth}x${viewHeight}, image=${analysisImageWidth}x${analysisImageHeight}, scale=$scale")

        for ((index, detection) in detections.withIndex()) {
            val color = boxColors[index % boxColors.size]
            boxPaint.color = color
            labelBgPaint.color = color

            val bb = detection.boundingBox

            // Scale from image space to view space
            val rect = RectF(
                bb.left * scale + offsetX,
                bb.top * scale + offsetY,
                bb.right * scale + offsetX,
                bb.bottom * scale + offsetY
            )

            // Clamp to bitmap bounds
            rect.left = rect.left.coerceIn(0f, viewWidth.toFloat())
            rect.top = rect.top.coerceIn(0f, viewHeight.toFloat())
            rect.right = rect.right.coerceIn(0f, viewWidth.toFloat())
            rect.bottom = rect.bottom.coerceIn(0f, viewHeight.toFloat())

            if (rect.width() < 2f || rect.height() < 2f) continue

            // Draw bounding box
            canvas.drawRoundRect(rect, 8f, 8f, boxPaint)

            // Draw label background + text
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

            Log.d(TAG, "Drew box[$index]: ${detection.label} at (${rect.left.toInt()},${rect.top.toInt()},${rect.right.toInt()},${rect.bottom.toInt()})")
        }

        // Set the bitmap on the ImageView
        overlay.setImageBitmap(bitmap)
    }

    private fun showCameraView() {
        binding.previewView.visibility = View.VISIBLE
        binding.ivDetectionOverlay.visibility = View.VISIBLE
        binding.overlayBottom.visibility = View.VISIBLE
        binding.tvStatus.visibility = View.VISIBLE
        binding.fabSound.visibility = View.VISIBLE
        binding.fabBack.visibility = View.VISIBLE
        binding.fabDescribe.visibility = View.VISIBLE
        binding.layoutPermissionDenied.visibility = View.GONE
    }

    private fun showPermissionDeniedView() {
        binding.previewView.visibility = View.GONE
        binding.ivDetectionOverlay.visibility = View.GONE
        binding.overlayBottom.visibility = View.GONE
        binding.tvStatus.visibility = View.GONE
        binding.tvObjectName.visibility = View.GONE
        binding.fabSound.visibility = View.GONE
        binding.fabBack.visibility = View.VISIBLE
        binding.fabDescribe.visibility = View.GONE
        binding.layoutPermissionDenied.visibility = View.VISIBLE
    }

    private fun updateSoundButtonIcon(isEnabled: Boolean) {
        val iconRes = if (isEnabled) {
            R.drawable.ic_volume_on
        } else {
            R.drawable.ic_volume_off
        }
        binding.fabSound.setImageResource(iconRes)
    }

    override fun onResume() {
        super.onResume()
        ttsManager?.resetLastSpoken()
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
        // Set flag PERTAMA untuk menghentikan processImageProxy() dari thread kamera
        isActivityDestroyed = true
        super.onDestroy()
        // Cancel loading sound job dulu agar tidak ada callback setelah destroy
        loadingSoundJob?.cancel()
        loadingSoundJob = null
        isProcessingDescription = false
        isShowingDescription = false
        cameraManager?.shutdown()
        objectDetectorHelper?.close()
        ttsManager?.shutdown()
        voiceCommandManager?.destroy()
        toneGenerator?.release()
        toneGenerator = null
        loadingToneGenerator?.release()
        loadingToneGenerator = null
        Log.d(TAG, "CameraAIActivity destroyed, all resources cleaned up")
    }

    // =========================================================================
    // Voice Command
    // =========================================================================

    private fun initVoiceCommand() {
        geminiCommandProcessor = GeminiCommandProcessor(lifecycleScope)
        listeningDialog = ListeningDialogHelper(this)

        // Wire TTS ↔ wake word: pause mic when TTS speaks, resume when done
        ttsManager?.setWakeWordCallbacks(
            onStart = { voiceCommandManager?.pauseForTTS() },
            onFinish = { voiceCommandManager?.resumeAfterTTS() }
        )

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
                        binding.fabMic.setImageResource(R.drawable.ic_mic_active)
                        binding.fabMic.startAnimation(pulseAnimation)
                        listeningDialog?.show(
                            statusText = "Mendengarkan...",
                            hintText = "Ucapkan perintah Anda"
                        )
                    }
                }

                override fun onListeningStarted() {
                    runOnUiThread {
                        binding.fabMic.setImageResource(R.drawable.ic_mic_active)
                        binding.fabMic.startAnimation(pulseAnimation)
                    }
                }

                override fun onListeningStopped() {
                    runOnUiThread {
                        binding.fabMic.clearAnimation()
                        binding.fabMic.setImageResource(R.drawable.ic_mic)
                        listeningDialog?.dismiss()
                    }
                }

                override fun onError(message: String) {
                    Log.e(TAG, "Voice error: $message")
                    runOnUiThread {
                        ttsManager?.speak(message)
                    }
                }
            }
        )

        voiceCommandManager?.setAllowedIntents(setOf(
            VoiceIntent.DESCRIBE_SCENE,
            VoiceIntent.TOGGLE_SOUND_ON,
            VoiceIntent.TOGGLE_SOUND_OFF,
            VoiceIntent.GO_BACK,
            VoiceIntent.HELP
        ))

        // Mic button → direct listen (bypass wake word)
        binding.fabMic.setOnClickListener {
            directListen()
        }

        // Wake Word toggle button
        binding.btnWakeWord.setOnClickListener {
            toggleWakeWord()
        }
    }

    private fun handleVoiceIntent(intent: VoiceIntent, spokenText: String = "") {
        when (intent) {
            VoiceIntent.DESCRIBE_SCENE -> describeCurrentScene()
            VoiceIntent.TOGGLE_SOUND_ON -> {
                ttsManager?.setSoundEnabled(true)
                updateSoundButtonIcon(true)
                ttsManager?.speak("Suara dinyalakan")
            }
            VoiceIntent.TOGGLE_SOUND_OFF -> {
                ttsManager?.setSoundEnabled(false)
                updateSoundButtonIcon(false)
                ttsManager?.speak("Suara dimatikan")
            }
            VoiceIntent.GO_BACK -> {
                ttsManager?.speak("Kembali")
                finish()
            }
            VoiceIntent.HELP -> {
                ttsManager?.speak("Ucapkan Hello Orion diikuti perintah. " +
                        "Perintah yang tersedia: " +
                        "Jelaskan, untuk deskripsi pemandangan. " +
                        "Suara hidup atau suara mati, untuk toggle suara. " +
                        "Kembali, untuk keluar.")
            }
            VoiceIntent.UNKNOWN -> {
                if (spokenText.isBlank()) {
                    ttsManager?.speak("Maaf, suara tidak terdengar jelas. Silakan ucapkan Hello Orion dan coba lagi.")
                } else {
                    ttsManager?.speak("Perintah \"$spokenText\" tidak dikenali. Katakan bantuan untuk daftar perintah.")
                }
            }
            else -> {
                ttsManager?.speak("Perintah tidak tersedia di halaman ini.")
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
            binding.btnWakeWord.setImageResource(R.drawable.ic_wakeword_off)
            ttsManager?.speak("Hello Orion dinonaktifkan")
        } else {
            isWakeWordEnabled = true
            voiceCommandManager?.startWakeWordMode()
            binding.btnWakeWord.setImageResource(R.drawable.ic_wakeword)
            ttsManager?.speak("Hello Orion diaktifkan. Ucapkan Hello Orion diikuti perintah.")
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

    companion object {
        private const val TAG = "CameraAIActivity"
    }
}
