package com.example.capable.detection

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult

/**
 * Helper class for MediaPipe Object Detection
 * Uses EfficientDet-Lite0 model for fast inference on mobile
 */
class ObjectDetectorHelper(
    private val context: Context,
    private val threshold: Float = 0.5f,
    private val maxResults: Int = 5,
    private val runningMode: RunningMode = RunningMode.IMAGE,
    private val objectDetectorListener: DetectorListener? = null
) {
    private var objectDetector: ObjectDetector? = null

    init {
        setupObjectDetector()
    }

    private fun setupObjectDetector() {
        // Check if model file exists first
        val modelExists = try {
            context.assets.open("efficientdet_lite0.tflite").close()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Model file not found: efficientdet_lite0.tflite")
            objectDetectorListener?.onError("Model file not found")
            false
        }

        if (!modelExists) {
            return
        }

        // Try GPU first, then fallback to CPU
        try {
            val baseOptionsBuilder = BaseOptions.builder()
                .setDelegate(Delegate.GPU)
                .setModelAssetPath("efficientdet_lite0.tflite")

            val optionsBuilder = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptionsBuilder.build())
                .setScoreThreshold(threshold)
                .setMaxResults(maxResults)
                .setRunningMode(runningMode)

            if (runningMode == RunningMode.LIVE_STREAM) {
                optionsBuilder.setResultListener { result, input ->
                    objectDetectorListener?.onResults(result, input.width, input.height)
                }
                optionsBuilder.setErrorListener { error ->
                    objectDetectorListener?.onError(error.message ?: "Unknown error")
                }
            }

            objectDetector = ObjectDetector.createFromOptions(context, optionsBuilder.build())
            Log.i(TAG, "Object detector initialized with GPU")
        } catch (e: Exception) {
            Log.w(TAG, "GPU init failed, trying CPU: ${e.message}")

            // Fallback to CPU
            try {
                val cpuOptionsBuilder = BaseOptions.builder()
                    .setDelegate(Delegate.CPU)
                    .setModelAssetPath("efficientdet_lite0.tflite")

                val cpuOptions = ObjectDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(cpuOptionsBuilder.build())
                    .setScoreThreshold(threshold)
                    .setMaxResults(maxResults)
                    .setRunningMode(runningMode)

                if (runningMode == RunningMode.LIVE_STREAM) {
                    cpuOptions.setResultListener { result, input ->
                        objectDetectorListener?.onResults(result, input.width, input.height)
                    }
                    cpuOptions.setErrorListener { error ->
                        objectDetectorListener?.onError(error.message ?: "Unknown error")
                    }
                }

                objectDetector = ObjectDetector.createFromOptions(context, cpuOptions.build())
                Log.i(TAG, "Object detector initialized with CPU")
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to initialize detector: ${e2.message}")
                objectDetectorListener?.onError("Failed to initialize detector: ${e2.message}")
            }
        }
    }

    fun detect(bitmap: Bitmap): ObjectDetectorResult? {
        if (objectDetector == null) return null

        val mpImage = BitmapImageBuilder(bitmap).build()
        return objectDetector?.detect(mpImage)
    }

    fun detectAsync(bitmap: Bitmap, frameTime: Long) {
        if (objectDetector == null) return

        val mpImage = BitmapImageBuilder(bitmap).build()
        objectDetector?.detectAsync(mpImage, frameTime)
    }

    fun close() {
        objectDetector?.close()
        objectDetector = null
    }

    interface DetectorListener {
        fun onResults(result: ObjectDetectorResult, imageWidth: Int, imageHeight: Int)
        fun onError(error: String)
    }

    companion object {
        private const val TAG = "ObjectDetectorHelper"
    }
}
