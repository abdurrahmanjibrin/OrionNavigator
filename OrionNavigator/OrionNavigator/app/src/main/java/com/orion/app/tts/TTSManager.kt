package com.orion.app.tts

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Manager class for Text-to-Speech functionality
 * Supports Indonesian language and prevents repetition of same object.
 *
 * Integrates with VoiceCommandManager to automatically pause wake-word
 * listening while TTS is speaking, preventing mic from picking up TTS audio.
 */
class TTSManager(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var isSoundEnabled = true
    private var lastSpokenObject: String? = null
    private var lastSpeakTime: Long = 0
    
    // Minimum interval between speaking same object (in milliseconds)
    private val speakInterval = 3000L

    // Callback to pause/resume wake word during TTS
    private var onSpeakingStarted: (() -> Unit)? = null
    private var onSpeakingFinished: (() -> Unit)? = null

    // One-shot callback — dijalankan SEKALI saat utterance selesai, lalu dihapus
    private var oneShotOnDone: (() -> Unit)? = null

    /** Set callback yang dipanggil SEKALI setelah TTS selesai berbicara */
    fun setOneShotOnDone(callback: () -> Unit) {
        oneShotOnDone = callback
    }

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Try Indonesian first, fallback to English
            val result = tts?.setLanguage(Locale("id", "ID"))
            
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                // Fallback to English
                tts?.setLanguage(Locale.US)
                Log.w(TAG, "Indonesian not supported, using English")
            }
            
            // Set AudioAttributes for TalkBack compatibility
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            tts?.setAudioAttributes(audioAttributes)
            
            // Set speech rate slightly slower for clarity
            tts?.setSpeechRate(0.9f)
            isInitialized = true

            // Set utterance listener for wake-word pause/resume
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d(TAG, "TTS utterance start: $utteranceId")
                    onSpeakingStarted?.invoke()
                }

                override fun onDone(utteranceId: String?) {
                    Log.d(TAG, "TTS utterance done: $utteranceId")
                    onSpeakingFinished?.invoke()
                    // Jalankan one-shot callback lalu hapus agar tidak terpanggil lagi
                    oneShotOnDone?.invoke()
                    oneShotOnDone = null
                }

                @Deprecated("Deprecated")
                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "TTS utterance error: $utteranceId")
                    onSpeakingFinished?.invoke()
                }
            })

            Log.d(TAG, "TTS initialized successfully")
        } else {
            Log.e(TAG, "TTS initialization failed with status: $status")
        }
    }

    /**
     * Set callbacks to pause/resume wake word listening during TTS.
     * @param onStart called when TTS begins speaking (pause wake word)
     * @param onFinish called when TTS finishes speaking (resume wake word)
     */
    fun setWakeWordCallbacks(onStart: () -> Unit, onFinish: () -> Unit) {
        onSpeakingStarted = onStart
        onSpeakingFinished = onFinish
    }

    /**
     * Check if TTS is currently speaking
     */
    fun isSpeaking(): Boolean = tts?.isSpeaking == true

    /**
     * Speak with high priority (navigation, Gemini description, etc.)
     * Will interrupt any ongoing speech including detection announcements.
     */
    fun speak(objectName: String) {
        if (!isInitialized || !isSoundEnabled) return
        
        val currentTime = System.currentTimeMillis()
        
        // Only speak if:
        // 1. Different object from last time, OR
        // 2. Same object but enough time has passed
        if (objectName != lastSpokenObject || 
            currentTime - lastSpeakTime > speakInterval) {
            
            tts?.speak(objectName, TextToSpeech.QUEUE_FLUSH, null, "object_$currentTime")
            lastSpokenObject = objectName
            lastSpeakTime = currentTime
            Log.d(TAG, "Speaking (priority): $objectName")
        }
    }

    /**
     * Speak immediately, bypassing dedup interval and interrupting any current TTS.
     * Used for high-priority events like "Arah Sudah Benar" that must cut through
     * any ongoing wrong-direction guidance.
     */
    fun speakInterrupt(text: String) {
        if (!isInitialized || !isSoundEnabled) return
        val currentTime = System.currentTimeMillis()
        tts?.stop()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "interrupt_$currentTime")
        lastSpokenObject = text
        lastSpeakTime = currentTime
        Log.d(TAG, "Speaking (interrupt): $text")
    }

    /**
     * Speak for object detection (low priority).
     * Will NOT interrupt if TTS is currently speaking anything.
     * Waits until current speech finishes before announcing next detection.
     */
    fun speakDetection(objectName: String) {
        if (!isInitialized || !isSoundEnabled) return
        
        // Don't interrupt ongoing speech — wait until finished
        if (isSpeaking()) {
            Log.d(TAG, "Skipped detection speech (TTS busy): $objectName")
            return
        }
        
        val currentTime = System.currentTimeMillis()
        
        if (objectName != lastSpokenObject || 
            currentTime - lastSpeakTime > speakInterval) {
            
            tts?.speak(objectName, TextToSpeech.QUEUE_ADD, null, "detect_$currentTime")
            lastSpokenObject = objectName
            lastSpeakTime = currentTime
            Log.d(TAG, "Speaking (detection): $objectName")
        }
    }

    /**
     * Enable or disable sound
     */
    fun setSoundEnabled(enabled: Boolean) {
        isSoundEnabled = enabled
        if (!enabled) {
            tts?.stop()
        }
    }

    /**
     * Check if sound is enabled
     */
    fun isSoundEnabled(): Boolean = isSoundEnabled

    /**
     * Toggle sound on/off
     */
    fun toggleSound(): Boolean {
        isSoundEnabled = !isSoundEnabled
        if (!isSoundEnabled) {
            tts?.stop()
        }
        return isSoundEnabled
    }

    /**
     * Stop any ongoing speech without shutting down
     */
    fun stop() {
        tts?.stop()
    }

    /**
     * Reset the last spoken object (useful when restarting detection)
     */
    fun resetLastSpoken() {
        lastSpokenObject = null
        lastSpeakTime = 0
    }

    /**
     * Stop speaking and release resources
     */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        onSpeakingStarted = null
        onSpeakingFinished = null
        Log.d(TAG, "TTS shutdown")
    }

    companion object {
        private const val TAG = "TTSManager"
    }
}
