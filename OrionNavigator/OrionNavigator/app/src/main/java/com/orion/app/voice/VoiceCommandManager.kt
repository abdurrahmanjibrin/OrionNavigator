package com.orion.app.voice

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * VoiceCommandManager - Handles speech recognition and voice command processing.
 *
 * Triple-layer approach:
 * 1. Wake word detection ("Hello Orion") — always-on passive listening
 * 2. Local keyword matching (instant, offline) for common commands
 * 3. Gemini API fallback (NLU) for natural/ambiguous commands
 *
 * Wake word flow:
 * - Passive mode: continuously listens → detects "Hello Orion" → extracts command
 * - If command follows wake word in same utterance → process immediately
 * - If only wake word → play confirmation → listen for next utterance as command
 *
 * TTS integration:
 * - Call pauseForTTS() before speaking and resumeAfterTTS() after TTS completes
 *   to prevent the microphone from picking up TTS audio output.
 */
class VoiceCommandManager(
    private val context: Context,
    private val geminiProcessor: GeminiCommandProcessor? = null,
    private val listener: OnCommandListener
) {

    companion object {
        private const val TAG = "VoiceCommandManager"
        private const val RESTART_DELAY_MS = 500L
        private const val ERROR_RESTART_DELAY_MS = 1500L
        private const val TTS_RESUME_DELAY_MS = 800L

        // All known variants of "hello orion" that Android STT may produce
        private val WAKE_WORD_VARIANTS = listOf(
            "hello orion", "halo orion", "hai orion", "hey orion",
            "elo orion", "helo orion", "hallo orion", "alo orion",
            "hallo rion", "halo rion", "hello rion", "helo rion",
            "hello oreo", "halo oreo", "hello ori", "halo ori",
            "hello oryon", "halo oryon", "hello orien", "halo orien",
            "hello orio", "halo orio", "hai orio", "hey orio",
            "hallow orion", "hollow orion", "halo orlan", "hello orlan",
            "heloorion", "haloorion", "haiorion", "helo rion",
            "hello ryan", "halo ryan", "hello rian", "halo rian",
            "hello arion", "halo arion", "hai arion", "hey arion",
            "halo riyon", "hello riyon", "allo orion", "alo orion"
        )

        // Words that commonly appear as part of wake-word misrecognition
        private val WAKE_FRAGMENTS_GREETING = listOf(
            "hello", "halo", "hai", "hey", "hallo", "elo", "helo", "alo", "allo", "hallow", "hollow"
        )
        private val WAKE_FRAGMENTS_NAME = listOf(
            "orion", "oreo", "oryon", "orien", "orio", "rion", "riyon",
            "orlan", "ryan", "rian", "arion", "ori", "orione"
        )
    }

    interface OnCommandListener {
        fun onCommandRecognized(spokenText: String, intent: VoiceIntent)
        fun onListeningStarted()
        fun onListeningStopped()
        fun onError(message: String)
        /** Called when wake word is detected without a command (user said only "Hello Orion") */
        fun onWakeWordDetected() {}
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    // Wake word mode state
    private var isWakeWordModeActive = false
    private var isActiveListeningAfterWake = false
    private var isPausedForTTS = false
    private val handler = Handler(Looper.getMainLooper())
    private val restartRunnable = Runnable { restartWakeWordListening() }

    // Audio manager for muting/unmuting recognizer beep sounds
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var originalNotifVolume = -1

    // Scope of intents this manager should handle (set by each Activity)
    private var allowedIntents: Set<VoiceIntent> = VoiceIntent.entries.toSet()

    /**
     * Set which intents this manager should respond to.
     * Commands outside this scope will be ignored.
     */
    fun setAllowedIntents(intents: Set<VoiceIntent>) {
        allowedIntents = intents + setOf(VoiceIntent.GO_BACK, VoiceIntent.HELP) // always allow
    }

    // =========================================================================
    // Audio Mute — suppress SpeechRecognizer "ting tung" beep sounds
    // =========================================================================

    private fun muteRecognizerBeep() {
        try {
            originalNotifVolume = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
            audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
            // Also mute STREAM_MUSIC as some devices play beep on this stream
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Could not mute recognizer beep", e)
        }
    }

    private fun unmuteRecognizerBeep() {
        try {
            if (originalNotifVolume >= 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, originalNotifVolume, 0)
                originalNotifVolume = -1
            }
            // Restore music volume to a reasonable level
            val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val currentMusic = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (currentMusic == 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic / 2, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not unmute recognizer beep", e)
        }
    }

    // =========================================================================
    // TTS Integration — Pause/Resume wake word to avoid mic picking up TTS
    // =========================================================================

    /**
     * Call BEFORE TTS starts speaking.
     * Temporarily stops wake word listening so the mic doesn't capture TTS output.
     */
    fun pauseForTTS() {
        if (!isWakeWordModeActive) return
        isPausedForTTS = true
        handler.removeCallbacks(restartRunnable)
        destroyRecognizer()
        // Unmute so TTS audio is audible
        unmuteRecognizerBeep()
        Log.d(TAG, "Wake word PAUSED for TTS")
    }

    /**
     * Call AFTER TTS finishes speaking.
     * Resumes wake word listening after a short delay.
     */
    fun resumeAfterTTS() {
        if (!isWakeWordModeActive || !isPausedForTTS) return
        isPausedForTTS = false
        // Small delay to let audio system settle after TTS
        scheduleRestart(TTS_RESUME_DELAY_MS)
        Log.d(TAG, "Wake word RESUME scheduled after TTS")
    }

    // =========================================================================
    // Wake Word Mode (always-on passive listening)
    // =========================================================================

    /**
     * Start wake word mode — continuously listens for "Hello Orion".
     * Call in onResume() after checking mic permission.
     */
    fun startWakeWordMode() {
        if (isWakeWordModeActive) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "Speech recognition not available")
            return
        }

        isWakeWordModeActive = true
        isActiveListeningAfterWake = false
        isPausedForTTS = false
        Log.d(TAG, "Wake word mode STARTED")
        startWakeWordListening()
    }

    /**
     * Stop wake word mode — stops all listening.
     * Call in onPause().
     */
    fun stopWakeWordMode() {
        isWakeWordModeActive = false
        isActiveListeningAfterWake = false
        isPausedForTTS = false
        handler.removeCallbacks(restartRunnable)
        destroyRecognizer()
        unmuteRecognizerBeep()
        Log.d(TAG, "Wake word mode STOPPED")
    }

    fun isWakeWordActive(): Boolean = isWakeWordModeActive

    private fun startWakeWordListening() {
        if (!isWakeWordModeActive || isPausedForTTS) return

        try {
            // Mute the notification/beep sound before starting recognizer
            muteRecognizerBeep()

            destroyRecognizer()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(createWakeWordListener())

            val recognizerIntent = createRecognizerIntent()
            speechRecognizer?.startListening(recognizerIntent)
            isListening = true
            Log.d(TAG, if (isActiveListeningAfterWake) "Active listening (post-wake)" else "Passive listening for wake word")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting wake word listening", e)
            scheduleRestart(ERROR_RESTART_DELAY_MS)
        }
    }

    private fun restartWakeWordListening() {
        if (!isWakeWordModeActive || isPausedForTTS) return
        startWakeWordListening()
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!isWakeWordModeActive || isPausedForTTS) return
        handler.removeCallbacks(restartRunnable)
        handler.postDelayed(restartRunnable, delayMs)
    }

    // =========================================================================
    // Wake Word Detection — Fuzzy matching with multiple strategies
    // =========================================================================

    /**
     * Check if text contains the wake word using multiple strategies.
     * Returns the command portion after the wake word, or null if not detected.
     * Returns empty string if only wake word was spoken.
     */
    private fun extractCommandAfterWakeWord(text: String): String? {
        val lower = text.lowercase().trim()

        // Strategy 1: Exact substring match against known variants
        for (variant in WAKE_WORD_VARIANTS) {
            val idx = lower.indexOf(variant)
            if (idx >= 0) {
                val afterWake = lower.substring(idx + variant.length).trim()
                val cleaned = afterWake.trimStart(',', '.', '!', ' ')
                Log.d(TAG, "Wake word exact match: '$variant' in '$lower'")
                return cleaned
            }
        }

        // Strategy 2: Fuzzy two-word combination (greeting + name)
        val words = lower.split("\\s+".toRegex())
        for (i in words.indices) {
            val word = words[i]
            // Check if this word is a greeting fragment
            val isGreeting = WAKE_FRAGMENTS_GREETING.any { greeting ->
                fuzzyMatch(word, greeting)
            }
            if (isGreeting) {
                // Check if next word is a name fragment
                if (i + 1 < words.size) {
                    val nextWord = words[i + 1]
                    val isName = WAKE_FRAGMENTS_NAME.any { name ->
                        fuzzyMatch(nextWord, name)
                    }
                    if (isName) {
                        // Wake word found at position i and i+1
                        val afterWake = words.drop(i + 2).joinToString(" ").trim()
                        val cleaned = afterWake.trimStart(',', '.', '!', ' ')
                        Log.d(TAG, "Wake word fuzzy match: '${words[i]} ${words[i+1]}' in '$lower'")
                        return cleaned
                    }
                }
                // Check if greeting is stuck together with name (e.g. "haloorion")
                val isNameStuck = WAKE_FRAGMENTS_NAME.any { name ->
                    word.endsWith(name) || word.contains(name)
                }
                if (isNameStuck) {
                    val afterWake = words.drop(i + 1).joinToString(" ").trim()
                    val cleaned = afterWake.trimStart(',', '.', '!', ' ')
                    Log.d(TAG, "Wake word stuck match: '${words[i]}' in '$lower'")
                    return cleaned
                }
            }

            // Check if word alone is a close match to a full variant (no spaces)
            for (variant in WAKE_WORD_VARIANTS) {
                val noSpaceVariant = variant.replace(" ", "")
                if (fuzzyMatch(word, noSpaceVariant)) {
                    val afterWake = words.drop(i + 1).joinToString(" ").trim()
                    Log.d(TAG, "Wake word nospace fuzzy match: '$word' ~ '$noSpaceVariant'")
                    return afterWake
                }
            }
        }

        return null
    }

    /**
     * Fuzzy match two strings — returns true if they're close enough.
     * Uses simple edit distance threshold based on word length.
     */
    private fun fuzzyMatch(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.isEmpty() || b.isEmpty()) return false

        // If one contains the other entirely
        if (a.contains(b) || b.contains(a)) return true

        // Compute Levenshtein distance
        val distance = levenshteinDistance(a, b)
        val maxLen = maxOf(a.length, b.length)

        // Allow more tolerance for longer words
        val threshold = when {
            maxLen <= 3 -> 1
            maxLen <= 5 -> 2
            maxLen <= 8 -> 3
            else -> 4
        }

        return distance <= threshold
    }

    /**
     * Standard Levenshtein distance.
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        val dp = Array(m + 1) { IntArray(n + 1) }

        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j

        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // deletion
                    dp[i][j - 1] + 1,      // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )
            }
        }

        return dp[m][n]
    }

    private fun createWakeWordListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "Wake listener: ready")
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "Wake listener: speech started")
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(TAG, "Wake listener: speech ended")
                isListening = false
            }

            override fun onError(error: Int) {
                isListening = false
                Log.d(TAG, "Wake listener error: $error")

                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        if (isActiveListeningAfterWake) {
                            // User didn't say anything or speech was unclear after wake word
                            Log.d(TAG, "No command after wake word, notifying user")
                            isActiveListeningAfterWake = false
                            listener.onListeningStopped()
                            // Notify user that nothing was understood
                            listener.onCommandRecognized("", VoiceIntent.UNKNOWN)
                        }
                        scheduleRestart(RESTART_DELAY_MS)
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        scheduleRestart(ERROR_RESTART_DELAY_MS)
                    }
                    SpeechRecognizer.ERROR_CLIENT -> {
                        scheduleRestart(ERROR_RESTART_DELAY_MS)
                    }
                    else -> {
                        scheduleRestart(ERROR_RESTART_DELAY_MS * 2)
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)

                if (!matches.isNullOrEmpty()) {
                    Log.d(TAG, "Wake results: $matches")

                    if (isActiveListeningAfterWake) {
                        // This is the command after wake word was detected alone
                        isActiveListeningAfterWake = false
                        val bestResult = matches[0]
                        Log.d(TAG, "Processing post-wake command: '$bestResult'")
                        listener.onListeningStopped()
                        processRecognizedText(bestResult)
                        scheduleRestart(RESTART_DELAY_MS)
                    } else {
                        // Check ALL candidates for wake word (not just first)
                        var detected = false
                        for (candidate in matches) {
                            val commandAfterWake = extractCommandAfterWakeWord(candidate)
                            if (commandAfterWake != null) {
                                detected = true
                                if (commandAfterWake.isNotEmpty()) {
                                    // "Hello Orion, buka navigasi" → process "buka navigasi"
                                    Log.d(TAG, "Wake word + command: '$commandAfterWake' (from '$candidate')")
                                    processRecognizedText(commandAfterWake)
                                    scheduleRestart(RESTART_DELAY_MS)
                                } else {
                                    // Just "Hello Orion" — switch to active listening
                                    Log.d(TAG, "Wake word only — switching to active listening")
                                    isActiveListeningAfterWake = true
                                    listener.onWakeWordDetected()
                                    listener.onListeningStarted()
                                    scheduleRestart(RESTART_DELAY_MS)
                                }
                                break
                            }
                        }
                        if (!detected) {
                            // No wake word in any candidate — restart passive
                            scheduleRestart(RESTART_DELAY_MS)
                        }
                    }
                } else {
                    if (isActiveListeningAfterWake) {
                        // Empty results while waiting for command
                        isActiveListeningAfterWake = false
                        listener.onListeningStopped()
                        listener.onCommandRecognized("", VoiceIntent.UNKNOWN)
                    }
                    scheduleRestart(RESTART_DELAY_MS)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!partial.isNullOrEmpty()) {
                    Log.d(TAG, "Wake partial: ${partial[0]}")
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    // =========================================================================
    // Manual Listening (legacy — mic button press)
    // =========================================================================

    /**
     * Start listening for voice commands (manual trigger, no wake word needed).
     * Temporarily pauses wake word mode if active.
     */
    fun startListening() {
        if (isListening) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            listener.onError("Speech recognition tidak tersedia di perangkat ini")
            return
        }

        // Pause wake word mode while doing manual listen
        val wasWakeMode = isWakeWordModeActive
        if (wasWakeMode) {
            handler.removeCallbacks(restartRunnable)
        }

        try {
            destroyRecognizer()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(createManualListener(wasWakeMode))

            val recognizerIntent = createRecognizerIntent()
            speechRecognizer?.startListening(recognizerIntent)
            isListening = true
            listener.onListeningStarted()
            Log.d(TAG, "Started manual listening")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting speech recognition", e)
            listener.onError("Gagal memulai pengenalan suara: ${e.localizedMessage}")
            if (wasWakeMode) scheduleRestart(RESTART_DELAY_MS)
        }
    }

    /**
     * Stop manual listening.
     */
    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping", e)
        }
        isListening = false
        listener.onListeningStopped()
        Log.d(TAG, "Stopped listening")

        if (isWakeWordModeActive) {
            scheduleRestart(RESTART_DELAY_MS)
        }
    }

    /**
     * Release all resources.
     */
    fun destroy() {
        isWakeWordModeActive = false
        isPausedForTTS = false
        handler.removeCallbacks(restartRunnable)
        destroyRecognizer()
        unmuteRecognizerBeep()
        isListening = false
    }

    fun isCurrentlyListening(): Boolean = isListening

    // =========================================================================
    // Keyword Matching & Text Processing
    // =========================================================================

    /**
     * Local keyword-to-intent mapping (Indonesian + English).
     * Comprehensive list to catch all natural language variations.
     * Returns the first matching intent, or null if no match.
     */
    private fun matchKeyword(text: String): VoiceIntent? {
        val lower = text.lowercase().trim()

        // Ordered by priority — first match wins
        val keywordMap = listOf(
            // ---- Home: Open features ----
            listOf(
                "buka lokasi", "live location", "share lokasi", "bagikan lokasi",
                "berbagi lokasi", "kirim lokasi", "lokasi langsung",
                "fitur lokasi", "buka live", "lacak lokasi"
            ) to VoiceIntent.OPEN_LIVE_LOCATION,

            listOf(
                "buka navigasi", "navigasi kompas", "arahkan saya", "navigasi",
                "kompas", "buka kompas", "arah jalan", "petunjuk arah",
                "pandu saya", "tunjukkan jalan", "buka arah", "fitur navigasi",
                "navigation", "compass"
            ) to VoiceIntent.OPEN_NAVIGATION,

            listOf(
                "buka kamera", "kamera ai", "lihat depan", "kamera",
                "deteksi objek", "buka camera", "lihat sekitar", "apa di depan saya",
                "apa yang ada di sekitar", "buka deteksi", "fitur kamera",
                "camera", "object detection"
            ) to VoiceIntent.OPEN_CAMERA,

            listOf(
                "keluar", "logout", "log out", "keluar akun", "sign out"
            ) to VoiceIntent.LOGOUT,

            // ---- Camera AI ----
            listOf(
                "jelaskan", "deskripsikan", "apa yang ada di depan", "apa di depan",
                "lihat apa", "ceritakan", "apa yang kamu lihat", "apa yang terlihat",
                "gambarkan", "jelaskan pemandangan", "apa yang ada di sekitar",
                "describe", "apa ini", "lihat ini", "apa yang ada",
                "tolong jelaskan", "tolong deskripsikan", "coba jelaskan",
                "coba lihat", "lihat dong", "apa sih di depan"
            ) to VoiceIntent.DESCRIBE_SCENE,

            listOf(
                "suara hidup", "nyalakan suara", "aktifkan suara", "sound on",
                "hidupkan suara", "on kan suara", "bunyikan", "volume on",
                "suara on", "aktifkan deteksi suara", "nyalakan deteksi"
            ) to VoiceIntent.TOGGLE_SOUND_ON,

            listOf(
                "suara mati", "matikan suara", "nonaktifkan suara", "sound off",
                "diam", "off kan suara", "suara off", "volume off",
                "matikan deteksi suara", "matikan deteksi", "senyap", "hening",
                "jangan bicara", "berhenti bicara"
            ) to VoiceIntent.TOGGLE_SOUND_OFF,

            // ---- Direction/Navigation: Location check ----
            listOf(
                "di mana saya", "lokasi saya sekarang", "saya di mana", "posisi saya",
                "cek lokasi", "lokasi sekarang", "di mana lokasi saya",
                "dimana saya", "dimana lokasi saya", "dimana saya sekarang",
                "di mana saya sekarang", "saya ada di mana", "saya berada di mana",
                "ini di mana", "sekarang di mana", "cek posisi", "posisi sekarang",
                "di mana posisi saya", "saya sedang di mana", "lokasi saya di mana",
                "ruangan apa ini", "ini ruangan apa", "ruangan mana ini",
                "saya di ruangan mana", "di ruangan mana saya",
                "where am i", "my location", "check location"
            ) to VoiceIntent.CHECK_LOCATION,

            // ---- Direction/Navigation: Select destination (uses contains) ----
            listOf(
                "saya ingin ke", "saya mau ke", "tujuan ke", "pergi ke",
                "arahkan ke", "navigasi ke", "antar ke", "bawa ke",
                "mau pergi ke", "ingin pergi ke", "tolong antar ke",
                "carikan jalan ke", "tunjukkan jalan ke", "pandu ke",
                "saya mau pergi ke", "antarkan ke", "bawa saya ke",
                "arahkan saya ke", "pandu saya ke", "go to", "navigate to"
            ) to VoiceIntent.SELECT_DESTINATION,

            listOf(
                "mulai navigasi", "mulai", "start", "jalan",
                "mulai jalan", "ayo jalan", "mari jalan", "jalankan navigasi",
                "start navigasi", "begin", "ayo mulai", "lanjut",
                "mulai arahkan", "mulai pandu"
            ) to VoiceIntent.START_NAVIGATION,

            listOf(
                "berhenti", "stop", "selesai navigasi", "berhenti navigasi",
                "stop navigasi", "hentikan navigasi", "sudah sampai",
                "selesai", "cukup", "hentikan", "berhenti jalan"
            ) to VoiceIntent.STOP_NAVIGATION,

            // ---- Live Location ----
            listOf(
                "hidupkan lokasi", "aktifkan lokasi", "nyalakan lokasi", "mulai berbagi",
                "aktifkan live location", "mulai share lokasi", "on kan lokasi",
                "bagikan lokasi saya", "mulai lacak"
            ) to VoiceIntent.TOGGLE_LOCATION_ON,

            listOf(
                "matikan lokasi", "nonaktifkan lokasi", "berhenti berbagi",
                "off kan lokasi", "matikan live location", "stop berbagi",
                "hentikan lokasi", "berhenti lacak"
            ) to VoiceIntent.TOGGLE_LOCATION_OFF,

            // ---- Global ----
            listOf(
                "kembali", "balik", "tutup", "back", "ke belakang",
                "mundur", "kembali ke belakang", "go back"
            ) to VoiceIntent.GO_BACK,

            listOf(
                "bantuan", "help", "tolong", "apa saja perintah", "daftar perintah",
                "perintah apa saja", "bisa apa saja", "apa yang bisa dilakukan",
                "cara pakai", "panduan", "petunjuk", "gimana caranya",
                "bagaimana cara", "tutorial"
            ) to VoiceIntent.HELP,
        )

        for ((keywords, intent) in keywordMap) {
            if (intent !in allowedIntents) continue
            for (keyword in keywords) {
                if (lower.contains(keyword)) {
                    Log.d(TAG, "Keyword matched: '$keyword' → $intent")
                    return intent
                }
            }
        }

        return null
    }

    /**
     * Process recognized text: try local keywords first, then Gemini.
     */
    private fun processRecognizedText(text: String) {
        Log.d(TAG, "Processing: '$text'")

        // Layer 1: Local keyword matching
        val localIntent = matchKeyword(text)
        if (localIntent != null) {
            listener.onCommandRecognized(text, localIntent)
            return
        }

        // Layer 2: Gemini NLU fallback
        val processor = geminiProcessor
        if (processor != null) {
            Log.d(TAG, "No keyword match, trying Gemini NLU...")
            processor.classifyIntent(text, allowedIntents) { geminiIntent ->
                listener.onCommandRecognized(text, geminiIntent)
            }
        } else {
            // No Gemini available — unknown
            listener.onCommandRecognized(text, VoiceIntent.UNKNOWN)
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun createRecognizerIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }
    }

    private fun destroyRecognizer() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying recognizer", e)
        }
        speechRecognizer = null
        isListening = false
    }

    private fun createManualListener(resumeWakeAfter: Boolean): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "Manual: ready for speech")
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "Manual: speech started")
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(TAG, "Manual: speech ended")
                isListening = false
                listener.onListeningStopped()
            }

            override fun onError(error: Int) {
                isListening = false
                listener.onListeningStopped()
                val message = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Kesalahan audio"
                    SpeechRecognizer.ERROR_CLIENT -> "Kesalahan klien"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Izin mikrofon belum diberikan"
                    SpeechRecognizer.ERROR_NETWORK -> "Kesalahan jaringan"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Waktu jaringan habis"
                    SpeechRecognizer.ERROR_NO_MATCH -> "Tidak ada yang terdengar, coba lagi"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Pengenalan suara sedang sibuk"
                    SpeechRecognizer.ERROR_SERVER -> "Kesalahan server"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Tidak ada suara terdeteksi"
                    else -> "Kesalahan tidak diketahui ($error)"
                }
                Log.e(TAG, "Manual recognition error: $message ($error)")

                if (error != SpeechRecognizer.ERROR_NO_MATCH &&
                    error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    listener.onError(message)
                }

                if (resumeWakeAfter && isWakeWordModeActive) {
                    scheduleRestart(RESTART_DELAY_MS)
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                listener.onListeningStopped()

                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val bestResult = matches[0]
                    Log.d(TAG, "Manual results: $matches, using: '$bestResult'")
                    processRecognizedText(bestResult)
                } else {
                    Log.d(TAG, "Manual: no results")
                }

                if (resumeWakeAfter && isWakeWordModeActive) {
                    scheduleRestart(RESTART_DELAY_MS)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!partial.isNullOrEmpty()) {
                    Log.d(TAG, "Manual partial: ${partial[0]}")
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }
}
