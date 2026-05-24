package com.orion.app.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Manager class for CameraX functionality
 * Handles camera initialization, preview, and image analysis
 * Uses aspect ratio instead of fixed resolution for responsive display on all devices
 */
class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /**
     * Start the camera with preview and optional image analysis
     */
    fun startCamera(
        onAnalyze: ((androidx.camera.core.ImageProxy) -> Unit)? = null,
        onError: (Exception) -> Unit
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases(onAnalyze)
                Log.d(TAG, "Camera started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start camera", e)
                onError(e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Determine the best aspect ratio based on device screen dimensions.
     * Most modern phones are 16:9 or taller, so RATIO_16_9 gives the widest FOV.
     */
    @Suppress("DEPRECATION")
    private fun getScreenAspectRatio(): Int {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val size = android.graphics.Point()
        wm.defaultDisplay.getRealSize(size)
        val width = size.x
        val height = size.y
        val ratio = maxOf(width, height).toFloat() / minOf(width, height).toFloat()
        Log.d(TAG, "Screen size: ${width}x${height}, ratio: $ratio")
        return if (ratio > 1.5f) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3
    }

    private fun bindCameraUseCases(onAnalyze: ((androidx.camera.core.ImageProxy) -> Unit)?) {
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera not initialized")

        // Unbind all use cases before rebinding
        cameraProvider.unbindAll()

        val aspectRatio = getScreenAspectRatio()
        Log.d(TAG, "Using aspect ratio: ${if (aspectRatio == AspectRatio.RATIO_16_9) "16:9" else "4:3"}")

        // Preview use case - matches screen aspect ratio for natural display
        val preview = Preview.Builder()
            .setTargetAspectRatio(aspectRatio)
            .build()
            .also { previewUseCase ->
                previewUseCase.setSurfaceProvider(previewView.surfaceProvider)
            }

        // Image analysis use case (for object detection)
        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(aspectRatio)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also { analysis ->
                onAnalyze?.let { analyzer ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        analyzer(imageProxy)
                    }
                }
            }

        // Select back camera
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            // Bind use cases to lifecycle
            if (onAnalyze != null && imageAnalyzer != null) {
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalyzer!!
                )
            } else {
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview
                )
            }
            Log.d(TAG, "Camera use cases bound")
        } catch (e: Exception) {
            Log.e(TAG, "Use case binding failed", e)
            throw e
        }
    }

    /**
     * Stop the camera and release resources
     */
    fun stopCamera() {
        cameraProvider?.unbindAll()
        Log.d(TAG, "Camera stopped")
    }

    /**
     * Shutdown the camera executor
     */
    fun shutdown() {
        stopCamera()
        cameraExecutor.shutdown()
        Log.d(TAG, "Camera manager shutdown")
    }

    companion object {
        private const val TAG = "CameraManager"
    }
}
