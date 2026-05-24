package com.orion.app.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * YOLOv8 Object Detection Helper
 * Uses YOLOv8s model for high-accuracy offline object detection
 * 
 * Model Details:
 * - Input: 640x640 RGB image
 * - Output: [1, 84, 8400] tensor
 *   - 84 = 4 (bbox coords) + 80 (class scores)
 *   - 8400 = number of detection proposals
 */
class YoloDetectorHelper(
    private val context: Context,
    private val onDetectionResult: (List<DetectionResult>) -> Unit,
    private val onError: (Exception) -> Unit
) {
    private var interpreter: Interpreter? = null
    private var isProcessing = false
    // Flag volatile untuk mencegah use-after-free saat kamera thread memanggil detectObjects
    // setelah close() dipanggil dari main thread di onDestroy
    @Volatile private var isClosed = false
    private val interpreterLock = Any()
    
    // YOLOv8 configuration
    private val inputSize = 640
    private val numClasses = 80
    private val numProposals = 8400
    private val confidenceThreshold = 0.50f  // Higher threshold to reduce false positives
    private val iouThreshold = 0.5f
    
    // Output tensor shape: [1, 84, 8400]
    private val outputShape = intArrayOf(1, 84, numProposals)
    private val outputBuffer = Array(1) { 
        Array(84) { 
            FloatArray(numProposals) 
        } 
    }

    init {
        setupInterpreter()
    }

    private fun setupInterpreter() {
        try {
            val options = Interpreter.Options()
            
            // Use CPU with multiple threads for stable performance
            options.setNumThreads(4)
            Log.d(TAG, "Using CPU with 4 threads")
            
            // Load model from assets
            val model = FileUtil.loadMappedFile(context, MODEL_FILE)
            interpreter = Interpreter(model, options)
            
            Log.d(TAG, "YOLOv8s interpreter initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize YOLOv8 interpreter: ${e.message}", e)
            onError(e)
        }
    }

    /**
     * Detect objects in the given bitmap
     */
    fun detectObjects(bitmap: Bitmap) {
        // Guard: jika sudah di-close, skip silently — mencegah SIGSEGV di TFLite JNI
        if (isClosed) return
        if (isProcessing) return

        isProcessing = true

        try {
            Log.d(TAG, "Processing frame: ${bitmap.width}x${bitmap.height}")

            // Preprocess image
            val inputImage = preprocessImage(bitmap)
            Log.d(TAG, "Image preprocessed to ${inputSize}x${inputSize}")

            // Run inference — dilindungi synchronized agar tidak tabrakan dengan close()
            val results: List<DetectionResult>
            synchronized(interpreterLock) {
                if (isClosed || interpreter == null) {
                    isProcessing = false
                    return
                }
                val startTime = System.currentTimeMillis()
                interpreter!!.run(inputImage.buffer, outputBuffer)
                val inferenceTime = System.currentTimeMillis() - startTime
                Log.d(TAG, "Inference completed in ${inferenceTime}ms")

                // Postprocess results
                results = postprocessOutput(bitmap.width, bitmap.height)
            }

            Log.d(TAG, "Detected ${results.size} objects")
            onDetectionResult(results)
        } catch (e: Exception) {
            Log.e(TAG, "Detection failed: ${e.message}", e)
            onDetectionResult(emptyList())
        } finally {
            isProcessing = false
        }
    }

    /**
     * Preprocess image to YOLOv8 input format
     * Resize to 640x640 and convert to float32 normalized to [0, 1]
     */
    private fun preprocessImage(bitmap: Bitmap): TensorImage {
        // Resize bitmap to 640x640
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        
        // Create TensorImage with FLOAT32 data type
        val tensorImage = TensorImage(org.tensorflow.lite.DataType.FLOAT32)
        tensorImage.load(resizedBitmap)
        
        // Normalize the image to [0, 1] range
        val imageProcessor = ImageProcessor.Builder()
            .add(org.tensorflow.lite.support.common.ops.NormalizeOp(0f, 255f))  // Normalize from [0, 255] to [0, 1]
            .build()
        
        return imageProcessor.process(tensorImage)
    }

    /**
     * Postprocess YOLOv8 output
     * Apply confidence threshold and NMS (Non-Maximum Suppression)
     */
    private fun postprocessOutput(originalWidth: Int, originalHeight: Int): List<DetectionResult> {
        val detections = mutableListOf<Detection>()
        var candidatesCount = 0
        
        // Parse output: [1, 84, 8400]
        // First 4 values are bbox (x_center, y_center, width, height)
        // Remaining 80 values are class scores
        for (i in 0 until numProposals) {
            // Get bbox coordinates (in 640x640 space)
            val xCenter = outputBuffer[0][0][i]
            val yCenter = outputBuffer[0][1][i]
            val width = outputBuffer[0][2][i]
            val height = outputBuffer[0][3][i]
            
            // Find best class and confidence
            var maxScore = 0f
            var maxClassId = 0
            
            for (c in 0 until numClasses) {
                val score = outputBuffer[0][4 + c][i]
                if (score > maxScore) {
                    maxScore = score
                    maxClassId = c
                }
            }
            
            // Filter by confidence threshold
            if (maxScore >= confidenceThreshold) {
                candidatesCount++
                
                // Convert to corner coordinates
                val x1 = xCenter - width / 2f
                val y1 = yCenter - height / 2f
                val x2 = xCenter + width / 2f
                val y2 = yCenter + height / 2f
                
                detections.add(
                    Detection(
                        classId = maxClassId,
                        confidence = maxScore,
                        x1 = x1,
                        y1 = y1,
                        x2 = x2,
                        y2 = y2
                    )
                )
            }
        }
        
        Log.d(TAG, "Found $candidatesCount candidates above threshold $confidenceThreshold")
        
        // Apply Non-Maximum Suppression
        val nmsDetections = applyNMS(detections)
        Log.d(TAG, "After NMS: ${nmsDetections.size} detections")
        
        // Log top detections
        nmsDetections.take(3).forEach { det ->
            Log.d(TAG, "Detection: class=${COCO_CLASSES.getOrNull(det.classId)} conf=${det.confidence}")
        }
        
        // Convert to DetectionResult and scale to original image size
        return nmsDetections.map { detection ->
            val scaleX = originalWidth.toFloat() / inputSize
            val scaleY = originalHeight.toFloat() / inputSize
            
            DetectionResult(
                label = COCO_CLASSES.getOrElse(detection.classId) { "Unknown" },
                confidence = detection.confidence,
                boundingBox = RectF(
                    detection.x1 * scaleX,
                    detection.y1 * scaleY,
                    detection.x2 * scaleX,
                    detection.y2 * scaleY
                )
            )
        }
    }

    /**
     * Apply Non-Maximum Suppression to remove overlapping boxes
     */
    private fun applyNMS(detections: List<Detection>): List<Detection> {
        if (detections.isEmpty()) return emptyList()
        
        // Sort by confidence (descending)
        val sortedDetections = detections.sortedByDescending { it.confidence }
        val selected = mutableListOf<Detection>()
        val suppressed = BooleanArray(sortedDetections.size)
        
        for (i in sortedDetections.indices) {
            if (suppressed[i]) continue
            
            selected.add(sortedDetections[i])
            
            for (j in i + 1 until sortedDetections.size) {
                if (suppressed[j]) continue
                
                // Calculate IoU (Intersection over Union)
                val iou = calculateIoU(sortedDetections[i], sortedDetections[j])
                
                if (iou > iouThreshold) {
                    suppressed[j] = true
                }
            }
        }
        
        return selected
    }

    /**
     * Calculate Intersection over Union (IoU) between two boxes
     */
    private fun calculateIoU(box1: Detection, box2: Detection): Float {
        val x1 = max(box1.x1, box2.x1)
        val y1 = max(box1.y1, box2.y1)
        val x2 = min(box1.x2, box2.x2)
        val y2 = min(box1.y2, box2.y2)
        
        val intersectionArea = max(0f, x2 - x1) * max(0f, y2 - y1)
        
        val box1Area = (box1.x2 - box1.x1) * (box1.y2 - box1.y1)
        val box2Area = (box2.x2 - box2.x1) * (box2.y2 - box2.y1)
        
        val unionArea = box1Area + box2Area - intersectionArea
        
        return if (unionArea > 0) intersectionArea / unionArea else 0f
    }

    /**
     * Get the best detection result
     */
    fun getBestLabel(results: List<DetectionResult>): Pair<String, Float>? {
        if (results.isEmpty()) return null
        
        val best = results.maxByOrNull { it.confidence }
        return if (best != null) {
            Pair(best.label, best.confidence)
        } else {
            null
        }
    }

    /**
     * Translate COCO class labels to Indonesian
     */
    fun translateLabel(englishLabel: String): String {
        return labelTranslations[englishLabel.lowercase()] 
            ?: englishLabel.replaceFirstChar { it.uppercase() }
    }

    /**
     * Release resources
     */
    fun close() {
        isClosed = true  // Set DULU sebelum close — stop semua detectObjects() yang baru masuk
        synchronized(interpreterLock) {
            interpreter?.close()
            interpreter = null
        }
        Log.d(TAG, "YOLOv8 detector closed")
    }

    /**
     * Internal detection class for NMS processing
     */
    private data class Detection(
        val classId: Int,
        val confidence: Float,
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float
    )

    /**
     * Public detection result data class
     */
    data class DetectionResult(
        val label: String,
        val confidence: Float,
        val boundingBox: RectF
    )

    companion object {
        private const val TAG = "YoloDetectorHelper"
        private const val MODEL_FILE = "yolov8s_float16_udhtraining.tflite"
        
        // COCO dataset 80 classes
        private val COCO_CLASSES = listOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
            "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
            "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
            "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
            "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
            "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
            "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
            "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
            "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator",
            "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
        )
        
        // Indonesian translations for COCO classes
        private val labelTranslations = mapOf(
            "person" to "Orang",
            "bicycle" to "Sepeda",
            "car" to "Mobil",
            "motorcycle" to "Motor",
            "airplane" to "Pesawat",
            "bus" to "Bus",
            "train" to "Kereta",
            "truck" to "Truk",
            "boat" to "Perahu",
            "traffic light" to "Lampu Lalu Lintas",
            "fire hydrant" to "Hidran Air",
            "stop sign" to "Rambu Stop",
            "parking meter" to "Meteran Parkir",
            "bench" to "Bangku",
            "bird" to "Burung",
            "cat" to "Kucing",
            "dog" to "Anjing",
            "horse" to "Kuda",
            "sheep" to "Domba",
            "cow" to "Sapi",
            "elephant" to "Gajah",
            "bear" to "Beruang",
            "zebra" to "Zebra",
            "giraffe" to "Jerapah",
            "backpack" to "Tas Ransel",
            "umbrella" to "Payung",
            "handbag" to "Tas Tangan",
            "tie" to "Dasi",
            "suitcase" to "Koper",
            "frisbee" to "Frisbee",
            "skis" to "Ski",
            "snowboard" to "Papan Salju",
            "sports ball" to "Bola",
            "kite" to "Layang-layang",
            "baseball bat" to "Tongkat Bisbol",
            "baseball glove" to "Sarung Bisbol",
            "skateboard" to "Skateboard",
            "surfboard" to "Papan Selancar",
            "tennis racket" to "Raket Tenis",
            "bottle" to "Botol",
            "wine glass" to "Gelas Anggur",
            "cup" to "Cangkir",
            "fork" to "Garpu",
            "knife" to "Pisau",
            "spoon" to "Sendok",
            "bowl" to "Mangkuk",
            "banana" to "Pisang",
            "apple" to "Apel",
            "sandwich" to "Sandwich",
            "orange" to "Jeruk",
            "broccoli" to "Brokoli",
            "carrot" to "Wortel",
            "hot dog" to "Hot Dog",
            "pizza" to "Pizza",
            "donut" to "Donat",
            "cake" to "Kue",
            "chair" to "Kursi",
            "couch" to "Sofa",
            "potted plant" to "Tanaman Pot",
            "bed" to "Tempat Tidur",
            "dining table" to "Meja Makan",
            "toilet" to "Toilet",
            "tv" to "Televisi",
            "laptop" to "Laptop",
            "mouse" to "Mouse",
            "remote" to "Remote",
            "keyboard" to "Keyboard",
            "cell phone" to "Ponsel",
            "microwave" to "Microwave",
            "oven" to "Oven",
            "toaster" to "Pemanggang Roti",
            "sink" to "Wastafel",
            "refrigerator" to "Kulkas",
            "book" to "Buku",
            "clock" to "Jam",
            "vase" to "Vas",
            "scissors" to "Gunting",
            "teddy bear" to "Boneka Beruang",
            "hair drier" to "Pengering Rambut",
            "toothbrush" to "Sikat Gigi"
        )
    }
}
