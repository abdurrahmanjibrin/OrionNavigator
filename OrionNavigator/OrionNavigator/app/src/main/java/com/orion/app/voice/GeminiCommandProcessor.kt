package com.orion.app.voice

import android.util.Log
import com.orion.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * GeminiCommandProcessor - Uses Gemini API as NLU to classify
 * natural language voice commands into VoiceIntent.
 *
 * Only called when local keyword matching fails.
 * Uses text-only Gemini call (no image).
 */
class GeminiCommandProcessor(
    private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "GeminiCommandProcessor"
        private const val PRIMARY_MODEL = "gemini-2.5-flash"
        private const val FALLBACK_MODEL = "gemini-2.5-flash-lite"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
    }

    private val apiKey: String = BuildConfig.GEMINI_API_KEY

    fun isConfigured(): Boolean = apiKey.isNotEmpty()

    /**
     * Classify a spoken text into a VoiceIntent using Gemini.
     * Runs async, calls [onResult] on Main thread.
     */
    fun classifyIntent(
        spokenText: String,
        allowedIntents: Set<VoiceIntent>,
        onResult: (VoiceIntent) -> Unit
    ) {
        if (!isConfigured()) {
            Log.w(TAG, "Gemini API key not configured")
            onResult(VoiceIntent.UNKNOWN)
            return
        }

        scope.launch(Dispatchers.IO) {
            val intent = callGeminiForIntent(spokenText, allowedIntents)
            withContext(Dispatchers.Main) {
                onResult(intent)
            }
        }
    }

    private fun callGeminiForIntent(
        spokenText: String,
        allowedIntents: Set<VoiceIntent>
    ): VoiceIntent {
        val intentNames = allowedIntents.joinToString(", ") { it.name }

        val prompt = """
Kamu adalah sistem klasifikasi intent untuk aplikasi navigasi tunanetra bernama Orion.

Pengguna mengucapkan: "$spokenText"

Klasifikasikan ucapan tersebut ke SALAH SATU intent berikut:
$intentNames

Penjelasan intent:
- OPEN_LIVE_LOCATION: Buka fitur berbagi lokasi
- OPEN_NAVIGATION: Buka fitur navigasi kompas
- OPEN_CAMERA: Buka fitur kamera AI / deteksi objek
- DESCRIBE_SCENE: Minta deskripsi apa yang ada di depan kamera
- TOGGLE_SOUND_ON: Nyalakan suara
- TOGGLE_SOUND_OFF: Matikan suara
- CHECK_LOCATION: Cek lokasi saat ini / tanya di mana posisi saat ini / saya di mana
- SELECT_DESTINATION: Memilih tujuan navigasi, misalnya 'saya ingin ke kantin', 'pergi ke musik', 'arahkan ke toilet'
- START_NAVIGATION: Mulai navigasi
- STOP_NAVIGATION: Berhenti navigasi
- TOGGLE_LOCATION_ON: Aktifkan berbagi lokasi
- TOGGLE_LOCATION_OFF: Matikan berbagi lokasi
- GO_BACK: Kembali ke halaman sebelumnya
- LOGOUT: Keluar aplikasi
- HELP: Minta bantuan / daftar perintah
- UNKNOWN: Jika tidak cocok dengan intent manapun

JAWAB HANYA DENGAN NAMA INTENT, tidak ada penjelasan tambahan.
        """.trimIndent()

        // Try primary model
        var result = callGemini(PRIMARY_MODEL, prompt)
        if (result == null) {
            result = callGemini(FALLBACK_MODEL, prompt)
        }

        if (result != null) {
            val cleaned = result.trim().uppercase().replace(" ", "_")
            return try {
                val parsed = VoiceIntent.valueOf(cleaned)
                if (parsed in allowedIntents || parsed == VoiceIntent.GO_BACK || parsed == VoiceIntent.HELP) {
                    Log.d(TAG, "Gemini classified '$spokenText' → $parsed")
                    parsed
                } else {
                    Log.w(TAG, "Gemini returned $parsed but not in allowed intents")
                    VoiceIntent.UNKNOWN
                }
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Gemini returned unknown intent: '$cleaned'")
                VoiceIntent.UNKNOWN
            }
        }

        return VoiceIntent.UNKNOWN
    }

    private fun callGemini(model: String, prompt: String): String? {
        return try {
            val body = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", prompt)
                            })
                        })
                    })
                })
                // Keep response short
                put("generationConfig", JSONObject().apply {
                    put("maxOutputTokens", 20)
                    put("temperature", 0.1)
                })
            }

            val url = URL("$BASE_URL/$model:generateContent?key=$apiKey")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 10000
                readTimeout = 10000
            }

            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            if (code == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val text = JSONObject(response)
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                    .trim()
                Log.d(TAG, "Gemini ($model) response: '$text'")
                text
            } else {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown"
                conn.disconnect()
                Log.e(TAG, "API error ($code) for $model: $err")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception calling $model", e)
            null
        }
    }
}
