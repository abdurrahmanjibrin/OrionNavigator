package com.orion.app.gemini

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.orion.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * GeminiHelper - Calls the Gemini REST API directly (no SDK).
 *
 * Uses v1beta endpoint with working models:
 * - gemini-2.5-flash (primary — verified working)
 * - gemini-2.5-flash-lite (fallback)
 */
class GeminiHelper {

    private val apiKey: String = BuildConfig.GEMINI_API_KEY

    private val PRIMARY_MODEL = "gemini-2.5-flash"
    private val FALLBACK_MODEL = "gemini-2.5-flash-lite"
    private val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"

    fun isConfigured(): Boolean = apiKey.isNotEmpty()

    suspend fun describeImage(bitmap: Bitmap): String {
        return withContext(Dispatchers.IO) {
            if (apiKey.isEmpty()) {
                return@withContext "API Key Gemini belum dikonfigurasi."
            }

            // Coba primary model dulu
            Log.d(TAG, "Trying primary: $PRIMARY_MODEL")
            val result = callGemini(PRIMARY_MODEL, bitmap)

            if (!result.startsWith("Error")) {
                return@withContext result
            }

            Log.e(TAG, "Primary failed: $result")

            // Kalau rate limit (429), tunggu 5 detik lalu coba fallback
            if (result.contains("429")) {
                Log.d(TAG, "Rate limited — waiting 5s before fallback...")
                delay(5000)
            }

            // Coba fallback model
            Log.d(TAG, "Trying fallback: $FALLBACK_MODEL")
            val fallbackResult = callGemini(FALLBACK_MODEL, bitmap)

            if (!fallbackResult.startsWith("Error")) {
                return@withContext fallbackResult
            }

            Log.e(TAG, "Fallback also failed: $fallbackResult")
            fallbackResult
        }
    }

    private fun callGemini(model: String, bitmap: Bitmap): String {
        return try {
            val base64 = bitmapToBase64(bitmap)

            val body = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("inline_data", JSONObject().apply {
                                    put("mime_type", "image/jpeg")
                                    put("data", base64)
                                })
                            })
                            put(JSONObject().apply {
                                put("text", "Beritahu tuna netra apa yang ada di depan kamera dengan kalimat pendek dan informatif. Gunakan awalan 'Ada...' atau 'Terlihat...'. LANGSUNG deskripsikan objeknya. JANGAN pakai kata pembuka seperti 'Tentu', 'Berikut adalah', atau 'Gambar ini menunjukkan'. Cukup katakan apa yang terlihat. Contoh: 'Ada botol air mineral tutup biru di tengah meja.'")
                            })
                        })
                    })
                })
            }

            val url = URL("$BASE_URL/$model:generateContent?key=$apiKey")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 15000
                readTimeout = 15000
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
                
                cleanText(text)
            } else {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown"
                conn.disconnect()
                Log.e(TAG, "API error ($code) for $model: $err")
                "Error $code: $err"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception calling $model", e)
            "Error: ${e.localizedMessage}"
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val maxSize = 1024
        val ratio = maxSize.toFloat() / maxOf(bitmap.width, bitmap.height)
        val resized = if (ratio < 1f)
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
        else bitmap

        val out = ByteArrayOutputStream()
        resized.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun cleanText(input: String): String {
        return input.replace("*", "")
            .replace("#", "")
            .replace("- ", "")
            .trim()
    }

    companion object {
        private const val TAG = "GeminiHelper"
    }
}
