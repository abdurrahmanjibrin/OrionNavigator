package com.orion.app.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.View
import com.orion.app.ml.YoloDetectorHelper

/**
 * Custom overlay view that draws bounding boxes and labels
 * over detected objects on the camera preview.
 */
class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "DetectionOverlay"
    }

    private var detections: List<YoloDetectorHelper.DetectionResult> = emptyList()

    // Source image dimensions (from camera analysis)
    private var sourceWidth: Int = 1
    private var sourceHeight: Int = 1

    init {
        // CRITICAL: Ensure this view participates in drawing
        setWillNotDraw(false)
        // Use software layer to avoid hardware acceleration issues with custom drawing
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    // Box paint
    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
    }

    // Label background paint
    private val labelBgPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // Label text paint
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        isAntiAlias = true
        isFakeBoldText = true
        setShadowLayer(2f, 1f, 1f, Color.BLACK)
    }

    // Colors for different classes (cycle through these)
    private val boxColors = listOf(
        Color.parseColor("#FF4CAF50"),  // Green
        Color.parseColor("#FF2196F3"),  // Blue
        Color.parseColor("#FFFF9800"),  // Orange
        Color.parseColor("#FFE91E63"),  // Pink
        Color.parseColor("#FF9C27B0"),  // Purple
        Color.parseColor("#FF00BCD4"),  // Cyan
        Color.parseColor("#FFFFEB3B"),  // Yellow
        Color.parseColor("#FF795548"),  // Brown
    )

    /**
     * Update the detections to draw.
     * @param results List of detection results from YOLOv8
     * @param imageWidth Width of the source analysis image
     * @param imageHeight Height of the source analysis image
     */
    fun setDetections(
        results: List<YoloDetectorHelper.DetectionResult>,
        imageWidth: Int,
        imageHeight: Int
    ) {
        detections = results
        sourceWidth = imageWidth
        sourceHeight = imageHeight
        Log.d(TAG, "setDetections: ${results.size} results, image=${imageWidth}x${imageHeight}, view=${width}x${height}")
        postInvalidate() // Use postInvalidate for thread-safety
    }

    fun clearDetections() {
        detections = emptyList()
        postInvalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        Log.d(TAG, "onSizeChanged: ${w}x${h} (was ${oldw}x${oldh})")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        Log.d(TAG, "onDraw called: detections=${detections.size}, viewSize=${width}x${height}")

        if (detections.isEmpty()) return
        if (width == 0 || height == 0) return

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        // fillCenter scaling: scale image to FILL the view completely, crop excess
        // This matches PreviewView's fillCenter behavior
        val scale = maxOf(viewWidth / sourceWidth, viewHeight / sourceHeight)
        val scaledImageWidth = sourceWidth * scale
        val scaledImageHeight = sourceHeight * scale
        // Offsets center the scaled image; one will be negative (cropped side)
        val offsetX = (viewWidth - scaledImageWidth) / 2f
        val offsetY = (viewHeight - scaledImageHeight) / 2f

        Log.d(TAG, "Scaling: source=${sourceWidth}x${sourceHeight}, scale=$scale, offset=($offsetX,$offsetY)")

        for ((index, detection) in detections.withIndex()) {
            val color = boxColors[index % boxColors.size]
            boxPaint.color = color
            labelBgPaint.color = color

            val bb = detection.boundingBox
            Log.d(TAG, "Detection[$index]: ${detection.label} bb=(${bb.left}, ${bb.top}, ${bb.right}, ${bb.bottom})")

            // Scale bounding box from source image space to view space
            val rect = RectF(
                bb.left * scale + offsetX,
                bb.top * scale + offsetY,
                bb.right * scale + offsetX,
                bb.bottom * scale + offsetY
            )

            Log.d(TAG, "Mapped rect: (${rect.left}, ${rect.top}, ${rect.right}, ${rect.bottom})")

            // Clamp to view bounds
            rect.left = rect.left.coerceIn(0f, viewWidth)
            rect.top = rect.top.coerceIn(0f, viewHeight)
            rect.right = rect.right.coerceIn(0f, viewWidth)
            rect.bottom = rect.bottom.coerceIn(0f, viewHeight)

            // Skip if box is too small after clamping
            if (rect.width() < 2f || rect.height() < 2f) {
                Log.d(TAG, "Skipping too-small box: ${rect.width()}x${rect.height()}")
                continue
            }

            // Draw bounding box
            canvas.drawRoundRect(rect, 8f, 8f, boxPaint)

            // Draw label background + text
            val confidencePercent = (detection.confidence * 100).toInt()
            val labelText = "${detection.label} $confidencePercent%"
            val textWidth = textPaint.measureText(labelText)
            val textHeight = textPaint.textSize

            val labelLeft = rect.left
            val labelTop = (rect.top - textHeight - 12f).coerceAtLeast(0f)
            val labelRight = (labelLeft + textWidth + 16f).coerceAtMost(viewWidth)
            val labelBottom = labelTop + textHeight + 12f

            // Label background
            canvas.drawRoundRect(
                labelLeft, labelTop, labelRight, labelBottom,
                6f, 6f, labelBgPaint
            )

            // Label text
            canvas.drawText(
                labelText,
                labelLeft + 8f,
                labelBottom - 6f,
                textPaint
            )
        }
    }
}
