package com.orion.app.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import java.nio.FloatBuffer

/**
 * High-performance on-device offline Wake Word Detector for "Hello Orion".
 *
 * Runs a 3-stage ONNX pipeline:
 * 1. melspectrogram.onnx: 16kHz audio -> Mel Spectrogram features
 * 2. embedding_model.onnx: 76-frame mel windows -> 96-dim speech embeddings (batched in 1 call)
 * 3. hello_orion.onnx: 16x96 embeddings -> Wake word probability [0.0 - 1.0]
 */
class WakeWordDetector(
    private val context: Context,
    private val threshold: Float = 0.80f,
    private val debounceMs: Long = 1500L,
    private val onWakeWordDetected: (confidence: Float) -> Unit
) {
    companion object {
        private const val TAG = "WakeWordDetector"
        private const val SAMPLE_RATE = 16000
        private const val WINDOW_SAMPLES = 32000 // 2 seconds audio buffer
        private const val CHUNK_SAMPLES = 1280    // 80ms audio chunk
        private const val EMBEDDING_WINDOW = 76
        private const val EMBEDDING_STRIDE = 8
        private const val NUM_WINDOWS = 16        // 16 windows of 76 frames
        private const val EMBEDDING_DIM = 96
        private const val PREDICT_INTERVAL_CHUNKS = 2 // Run prediction every ~160ms (2 chunks)
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var melSession: OrtSession? = null
    private var embSession: OrtSession? = null
    private var clfSession: OrtSession? = null

    private var audioRecord: AudioRecord? = null
    @Volatile private var isRunning = false
    private var workerThread: Thread? = null
    private var lastDetectionTime = 0L

    // Pre-allocated buffers to prevent GC thrashing on mobile
    private val audioBuffer = FloatArray(WINDOW_SAMPLES)
    private val pcmChunk = ShortArray(CHUNK_SAMPLES)
    private val batchedWindowBuffer = FloatBuffer.allocate(NUM_WINDOWS * EMBEDDING_WINDOW * 32)
    private val clfInputBuffer = FloatBuffer.allocate(NUM_WINDOWS * EMBEDDING_DIM)

    init {
        loadModels()
    }

    private fun loadModels() {
        try {
            val melBytes = context.assets.open("melspectrogram.onnx").readBytes()
            val embBytes = context.assets.open("embedding_model.onnx").readBytes()
            val clfBytes = context.assets.open("hello_orion.onnx").readBytes()

            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
            }
            melSession = env.createSession(melBytes, opts)
            embSession = env.createSession(embBytes, opts)
            clfSession = env.createSession(clfBytes, opts)
            Log.d(TAG, "Successfully loaded all 3 ONNX models from assets")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load ONNX models", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning) return
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBuf * 2, CHUNK_SAMPLES * 8)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            return
        }

        audioRecord?.startRecording()
        isRunning = true

        workerThread = Thread({ audioLoop() }, "WakeWordWorker").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
        Log.d(TAG, "Wake word detector started listening (threshold=$threshold)")
    }

    fun stop() {
        isRunning = false
        try {
            workerThread?.interrupt()
            workerThread?.join(500)
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping audio recorder", e)
        } finally {
            audioRecord = null
            workerThread = null
        }
        Log.d(TAG, "Wake word detector stopped")
    }

    private fun audioLoop() {
        var bufferCount = 0
        var chunksSinceLastPredict = 0

        while (isRunning && !Thread.currentThread().isInterrupted) {
            val read = audioRecord?.read(pcmChunk, 0, CHUNK_SAMPLES) ?: -1
            if (read <= 0) continue

            // Convert int16 PCM to normalized float32 [-1.0, 1.0] and shift buffer
            if (bufferCount + read <= WINDOW_SAMPLES) {
                for (i in 0 until read) {
                    audioBuffer[bufferCount + i] = pcmChunk[i] / 32768.0f
                }
                bufferCount += read
            } else {
                val shift = bufferCount + read - WINDOW_SAMPLES
                System.arraycopy(audioBuffer, shift, audioBuffer, 0, bufferCount - shift)
                bufferCount -= shift
                for (i in 0 until read) {
                    audioBuffer[bufferCount + i] = pcmChunk[i] / 32768.0f
                }
                bufferCount += read
            }

            chunksSinceLastPredict++

            // Predict every ~160ms once the buffer is full
            if (bufferCount >= WINDOW_SAMPLES && chunksSinceLastPredict >= PREDICT_INTERVAL_CHUNKS) {
                chunksSinceLastPredict = 0
                val score = predict(audioBuffer)
                
                if (score > 0.30f) {
                    Log.d(TAG, "Current Orion wake score: ${String.format("%.1f", score * 100)}% (threshold: ${String.format("%.0f", threshold * 100)}%)")
                }

                val now = SystemClock.elapsedRealtime()
                if (score >= threshold && (now - lastDetectionTime >= debounceMs)) {
                    lastDetectionTime = now
                    Log.i(TAG, ">>> WAKE WORD DETECTED! (confidence = ${String.format("%.2f", score * 100)}%) <<<")
                    bufferCount = 0 // Clear buffer to prevent double triggers
                    onWakeWordDetected(score)
                }
            }
        }
    }

    private fun predict(audio: FloatArray): Float {
        val melSess = melSession ?: return 0f
        val embSess = embSession ?: return 0f
        val clfSess = clfSession ?: return 0f

        try {
            // Stage 1: Mel Spectrogram
            val audioTensor = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(audio),
                longArrayOf(1, audio.size.toLong())
            )
            val melResult = melSess.run(mapOf("input" to audioTensor))
            val melOutput = melResult.get(0).value as Array<Array<Array<FloatArray>>> // (1, 1, timeFrames, 32)
            audioTensor.close()
            melResult.close()

            val melFrames = melOutput[0][0]
            val timeFrames = melFrames.size
            if (timeFrames < EMBEDDING_WINDOW) return 0f

            // Apply normalization: mel / 10.0 + 2.0 in-place
            for (t in 0 until timeFrames) {
                val row = melFrames[t]
                for (m in 0 until 32) {
                    row[m] = (row[m] / 10.0f) + 2.0f
                }
            }

            // Stage 2: Batched Speech Embedding (16 windows of 76 frames in ONE ONNX call)
            batchedWindowBuffer.clear()
            for (start in 0..(timeFrames - EMBEDDING_WINDOW) step EMBEDDING_STRIDE) {
                for (t in start until (start + EMBEDDING_WINDOW)) {
                    batchedWindowBuffer.put(melFrames[t])
                }
            }
            batchedWindowBuffer.flip()

            val embTensor = OnnxTensor.createTensor(
                env,
                batchedWindowBuffer,
                longArrayOf(NUM_WINDOWS.toLong(), EMBEDDING_WINDOW.toLong(), 32, 1)
            )
            val embResult = embSess.run(mapOf("input_1" to embTensor))
            val embOutput = embResult.get(0).value as Array<Array<Array<FloatArray>>> // (16, 1, 1, 96)
            embTensor.close()
            embResult.close()

            // Stage 3: Classifier (Flatten 16x96 embeddings into 1x16x96)
            clfInputBuffer.clear()
            for (w in 0 until NUM_WINDOWS) {
                clfInputBuffer.put(embOutput[w][0][0])
            }
            clfInputBuffer.flip()

            val clfTensor = OnnxTensor.createTensor(
                env,
                clfInputBuffer,
                longArrayOf(1, NUM_WINDOWS.toLong(), EMBEDDING_DIM.toLong())
            )
            val clfResult = clfSess.run(mapOf("embeddings" to clfTensor))
            val scoreArray = clfResult.get(0).value as Array<FloatArray> // (1, 1)
            clfTensor.close()
            clfResult.close()

            return scoreArray[0][0]
        } catch (e: Exception) {
            Log.e(TAG, "Prediction error in ONNX pipeline", e)
            return 0f
        }
    }

    fun release() {
        stop()
        melSession?.close()
        embSession?.close()
        clfSession?.close()
    }
}
