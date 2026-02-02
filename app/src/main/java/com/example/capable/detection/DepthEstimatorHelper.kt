package com.example.capable.detection

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * MiDaS Depth Estimation Helper
 * Uses MiDaS Small model for fast depth estimation
 * Input: 256x256 RGB image
 * Output: 256x256 depth map
 */
class DepthEstimatorHelper(
    private val context: Context,
    private val listener: DepthListener? = null
) {
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null

    // MiDaS Small model specs
    private val inputSize = 256
    private val inputChannels = 3

    init {
        setupInterpreter()
    }

    private fun setupInterpreter() {
        // Check if model file exists first
        val modelExists = try {
            context.assets.open("midas_small.tflite").close()
            true
        } catch (e: Exception) {
            Log.w(TAG, "MiDaS model not found, depth estimation disabled")
            false
        }

        if (!modelExists) {
            return // Silently disable depth estimation if model not available
        }

        try {
            val options = Interpreter.Options()

            // Try GPU delegate first
            try {
                gpuDelegate = GpuDelegate()
                options.addDelegate(gpuDelegate)
                Log.i(TAG, "Using GPU delegate for depth estimation")
            } catch (e: Exception) {
                Log.w(TAG, "GPU delegate not available, using CPU: ${e.message}")
                gpuDelegate = null
            }

            options.setNumThreads(4)

            val modelBuffer = loadModelFile("midas_small.tflite")
            interpreter = Interpreter(modelBuffer, options)

            Log.i(TAG, "Depth estimator initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize depth estimator: ${e.message}")
            interpreter = null
            gpuDelegate?.close()
            gpuDelegate = null
        }
    }

    private fun loadModelFile(modelName: String): MappedByteBuffer {
        val assetFileDescriptor = context.assets.openFd(modelName)
        val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = fileInputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun estimateDepth(bitmap: Bitmap): FloatArray? {
        if (interpreter == null) return null

        // Resize bitmap to model input size
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)

        // Prepare input buffer
        val inputBuffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * inputChannels)
        inputBuffer.order(ByteOrder.nativeOrder())
        inputBuffer.rewind()

        // Convert bitmap to normalized float array
        val pixels = IntArray(inputSize * inputSize)
        resizedBitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        for (pixel in pixels) {
            // Normalize to [0, 1] and convert to RGB order
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f

            inputBuffer.putFloat(r)
            inputBuffer.putFloat(g)
            inputBuffer.putFloat(b)
        }

        // Prepare output buffer
        val outputBuffer = Array(1) { Array(inputSize) { FloatArray(inputSize) } }

        try {
            interpreter?.run(inputBuffer, outputBuffer)

            // Flatten and normalize the depth map
            val depthMap = FloatArray(inputSize * inputSize)
            var minDepth = Float.MAX_VALUE
            var maxDepth = Float.MIN_VALUE

            for (y in 0 until inputSize) {
                for (x in 0 until inputSize) {
                    val depth = outputBuffer[0][y][x]
                    depthMap[y * inputSize + x] = depth
                    minDepth = minOf(minDepth, depth)
                    maxDepth = maxOf(maxDepth, depth)
                }
            }

            // Normalize depth values to [0, 1]
            val range = maxDepth - minDepth
            if (range > 0) {
                for (i in depthMap.indices) {
                    depthMap[i] = (depthMap[i] - minDepth) / range
                }
            }

            return depthMap
        } catch (e: Exception) {
            Log.e(TAG, "Depth estimation failed: ${e.message}")
            listener?.onError("Depth estimation failed: ${e.message}")
            return null
        }
    }

    /**
     * Get the average depth in a region of the image
     * @param depthMap The depth map from estimateDepth()
     * @param left Left boundary (0.0 - 1.0)
     * @param top Top boundary (0.0 - 1.0)
     * @param right Right boundary (0.0 - 1.0)
     * @param bottom Bottom boundary (0.0 - 1.0)
     * @return Average depth in the region (0.0 = far, 1.0 = near)
     */
    fun getRegionDepth(
        depthMap: FloatArray,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ): Float {
        val startX = (left * inputSize).toInt().coerceIn(0, inputSize - 1)
        val startY = (top * inputSize).toInt().coerceIn(0, inputSize - 1)
        val endX = (right * inputSize).toInt().coerceIn(0, inputSize - 1)
        val endY = (bottom * inputSize).toInt().coerceIn(0, inputSize - 1)

        var sum = 0f
        var count = 0

        for (y in startY..endY) {
            for (x in startX..endX) {
                sum += depthMap[y * inputSize + x]
                count++
            }
        }

        return if (count > 0) sum / count else 0f
    }

    fun close() {
        interpreter?.close()
        gpuDelegate?.close()
        interpreter = null
        gpuDelegate = null
    }

    interface DepthListener {
        fun onError(error: String)
    }

    companion object {
        private const val TAG = "DepthEstimatorHelper"
    }
}
