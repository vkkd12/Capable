package com.example.capable.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * Image analyzer that processes camera frames at a controlled rate
 * Skips frames to maintain performance while providing real-time detection
 */
class FrameAnalyzer(
    private val frameProcessor: FrameProcessor,
    private val targetFps: Int = 10 // Process ~10 frames per second
) : ImageAnalysis.Analyzer {

    private var frameCounter = 0L
    private val skipInterval = (30 / targetFps).coerceAtLeast(1) // Assuming 30fps camera
    private var lastProcessTime = 0L
    private val minFrameInterval = 1000L / targetFps // Minimum ms between processed frames

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        frameCounter++
        val currentTime = System.currentTimeMillis()

        // Skip frames based on interval and time
        if (frameCounter % skipInterval != 0L ||
            currentTime - lastProcessTime < minFrameInterval) {
            imageProxy.close()
            return
        }

        lastProcessTime = currentTime

        try {
            val bitmap = imageProxy.toBitmap()

            // Rotate bitmap if needed based on rotation
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val rotatedBitmap = if (rotationDegrees != 0) {
                rotateBitmap(bitmap, rotationDegrees.toFloat())
            } else {
                bitmap
            }

            frameProcessor.processFrame(rotatedBitmap, currentTime)

        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error: ${e.message}")
        } finally {
            imageProxy.close()
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degrees)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    interface FrameProcessor {
        fun processFrame(bitmap: Bitmap, timestamp: Long)
    }

    companion object {
        private const val TAG = "FrameAnalyzer"
    }
}
