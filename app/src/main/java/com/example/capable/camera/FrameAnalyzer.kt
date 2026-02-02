package com.example.capable.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
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
        
        // Log every 30 frames to confirm processing
        if (frameCounter % 30 == 0L) {
            Log.d(TAG, "Processing frame #$frameCounter at $currentTime")
        }

        try {
            val bitmap = imageProxy.toBitmap()

            // Rotate bitmap if needed based on rotation
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val rotatedBitmap = if (rotationDegrees != 0) {
                rotateBitmap(bitmap, rotationDegrees.toFloat())
            } else {
                bitmap
            }

            // Convert to RGB_565 then back to ARGB_8888 to ensure proper format for MediaPipe
            // This strips alpha and ensures correct channel order
            val rgbBitmap = convertToMediaPipeFormat(rotatedBitmap)

            frameProcessor.processFrame(rgbBitmap, currentTime)

        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error: ${e.message}", e)
        } finally {
            imageProxy.close()
        }
    }

    /**
     * Convert bitmap to a format compatible with MediaPipe.
     * MediaPipe expects ARGB_8888 with specific memory layout.
     * We force a software bitmap copy to avoid hardware bitmap issues.
     */
    private fun convertToMediaPipeFormat(source: Bitmap): Bitmap {
        // First, ensure we have a software bitmap (not hardware)
        val softwareBitmap = if (source.config == Bitmap.Config.HARDWARE) {
            source.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            source
        }
        
        // Create a fresh ARGB_8888 bitmap with proper memory layout
        val result = Bitmap.createBitmap(softwareBitmap.width, softwareBitmap.height, Bitmap.Config.ARGB_8888)
        
        // Copy pixels to ensure proper format
        val pixels = IntArray(softwareBitmap.width * softwareBitmap.height)
        softwareBitmap.getPixels(pixels, 0, softwareBitmap.width, 0, 0, softwareBitmap.width, softwareBitmap.height)
        result.setPixels(pixels, 0, result.width, 0, 0, result.width, result.height)
        
        return result
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
